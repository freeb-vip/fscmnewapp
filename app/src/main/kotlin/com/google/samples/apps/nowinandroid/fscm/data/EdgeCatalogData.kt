/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.fscm.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class CatalogSource {
    Edge,
    Center,
    Offline,
}

data class CatalogResult<T>(
    val data: T,
    val source: CatalogSource,
    val nodeId: String = "",
    val nodeName: String = "",
    val isStale: Boolean = false,
    val syncedAt: String = "",
)

data class CatalogMediaRequest(
    val url: String,
    val centralUrl: String,
    val cacheKey: String,
    val headerName: String,
    val headerValue: String,
)

internal data class EdgeCatalogNode(
    val nodeId: String,
    val nodeName: String,
    val baseUrl: String,
    val capabilities: List<String>,
    val protocol: EdgeCatalogProtocol,
    val sequence: Long,
    val syncedAt: String,
    val syncLagMillis: Long,
    val latencyMillis: Long,
    val failureCount: Int = 0,
) {
    val isStale: Boolean get() = syncLagMillis > MAX_FRESHNESS_MS
}

internal enum class EdgeCatalogProtocol {
    V2,
    Legacy,
}

@Serializable
private data class EdgeCatalogHealth(
    val ok: Boolean = false,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    val capabilities: List<String> = emptyList(),
    val sequence: Long = 0,
    @SerialName("data_sequence") val dataSequence: Long = 0,
    @SerialName("synced_at") val syncedAt: String = "",
    @SerialName("sync_lag_seconds") val syncLagSeconds: Long? = null,
)

private data class EdgeAddress(
    val baseUrl: String,
    val expectedNodeId: String = "",
    val expectedNodeName: String = "",
)

private data class EdgeNodeSnapshot(
    val nodes: List<EdgeCatalogNode> = emptyList(),
    val validUntilMillis: Long = 0,
)

private data class EdgeFailureState(
    val count: Int,
    val blockedUntilMillis: Long,
)

@Singleton
class EdgeNodeRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val discovery: EdgeServiceDiscovery,
    @param:FscmJson private val json: Json,
) {
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val refreshMutex = Mutex()
    private val failures = mutableMapOf<String, EdgeFailureState>()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var snapshot = EdgeNodeSnapshot()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()

            override fun onLost(network: Network) = networkChanged()
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
        }
    }

    internal suspend fun candidates(): List<EdgeCatalogNode> {
        val now = System.currentTimeMillis()
        snapshot.takeIf { it.validUntilMillis > now }?.let { return availableNodes(it.nodes, now) }
        val localNodes = refresh(DISCOVERY_QUERY_TIMEOUT_MS, includeCenterRegistry = false)
        return if (shouldUseEdgeRegistryFallback(localNodes, sessionStore.current().isOffline)) {
            refresh(DISCOVERY_QUERY_TIMEOUT_MS, includeCenterRegistry = true, force = true)
        } else {
            localNodes
        }
    }

    internal suspend fun prewarm(): List<EdgeCatalogNode> =
        refresh(DISCOVERY_PREWARM_TIMEOUT_MS, includeCenterRegistry = true, force = true)

    fun invalidate() {
        snapshot = EdgeNodeSnapshot()
    }

    private fun networkChanged() {
        invalidate()
        applicationScope.launch { runCatching { prewarm() } }
    }

    internal fun recordSuccess(nodeId: String) {
        synchronized(failures) { failures.remove(nodeId) }
    }

    internal fun recordFailure(nodeId: String) {
        val now = System.currentTimeMillis()
        synchronized(failures) {
            val count = (failures[nodeId]?.count ?: 0) + 1
            failures[nodeId] = EdgeFailureState(
                count = count,
                blockedUntilMillis = if (count >= FAILURE_THRESHOLD) now + CIRCUIT_BREAKER_MS else 0,
            )
        }
    }

    private suspend fun refresh(
        timeoutMillis: Long,
        includeCenterRegistry: Boolean,
        force: Boolean = false,
    ): List<EdgeCatalogNode> = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force) {
            snapshot.takeIf { it.validUntilMillis > now }?.let { return@withLock availableNodes(it.nodes, now) }
        }

        val cached = discovery.cachedBaseUrls().map(::EdgeAddress)
        val discovered = withTimeoutOrNull(timeoutMillis) {
            discovery.discoverBaseUrls(timeoutMillis).map(::EdgeAddress)
        }.orEmpty()
        val registered = if (includeCenterRegistry && !sessionStore.current().isOffline) {
            withTimeoutOrNull(timeoutMillis) {
                runCatching {
                    api.get<PagedList<EdgeNode>>(
                        "edge/nodes/available",
                        edgeNodeListParams(includeOffline = false),
                    ).rows().map { EdgeAddress(it.lanBaseUrl, it.nodeId, it.nodeName) }
                }.getOrDefault(emptyList())
            }.orEmpty()
        } else {
            emptyList()
        }
        val addresses = (cached + discovered + registered)
            .filter { it.baseUrl.isNotBlank() }
            .distinctBy { localEdgeDirectBaseUrl(it.baseUrl) }
        val nodes = coroutineScope {
            addresses.map { address -> async(Dispatchers.IO) { probe(address) } }
                .awaitAll()
                .filterNotNull()
        }
        snapshot = EdgeNodeSnapshot(
            nodes = nodes,
            validUntilMillis = now + if (nodes.isEmpty()) NEGATIVE_CACHE_MS else HEALTH_CACHE_MS,
        )
        availableNodes(nodes, now)
    }

    private fun probe(address: EdgeAddress): EdgeCatalogNode? {
        val baseUrl = localEdgeDirectBaseUrl(address.baseUrl)
        val startedAt = System.nanoTime()
        val health = HEALTH_PATHS.firstNotNullOfOrNull { path -> fetchHealth(baseUrl + path) } ?: return null
        val protocol = edgeCatalogProtocol(health.capabilities)
        if (!health.ok || health.nodeId.isBlank() || protocol == null) return null
        if (address.expectedNodeId.isNotBlank() && address.expectedNodeId != health.nodeId) return null
        val lagMillis = if (protocol == EdgeCatalogProtocol.Legacy) {
            0
        } else {
            health.syncLagSeconds?.times(1_000)
                ?: health.syncedAt.toEpochMillisOrNull()?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
                ?: Long.MAX_VALUE
        }
        return EdgeCatalogNode(
            nodeId = health.nodeId,
            nodeName = health.nodeName.ifBlank { address.expectedNodeName }.ifBlank { health.nodeId },
            baseUrl = baseUrl,
            capabilities = health.capabilities,
            protocol = protocol,
            sequence = maxOf(health.sequence, health.dataSequence),
            syncedAt = health.syncedAt,
            syncLagMillis = lagMillis,
            latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            failureCount = synchronized(failures) { failures[health.nodeId]?.count ?: 0 },
        )
    }

    private fun fetchHealth(url: String): EdgeCatalogHealth? = runCatching {
        val request = Request.Builder().url(url).get().build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val root = json.parseToJsonElement(response.body?.string().orEmpty())
            val payload = runCatching { root.unwrapStandardPayload(response.code) }.getOrNull() ?: return@use null
            json.decodeFromJsonElement<EdgeCatalogHealth>(payload)
        }
    }.getOrNull()

    private fun availableNodes(nodes: List<EdgeCatalogNode>, now: Long): List<EdgeCatalogNode> {
        val active = nodes.filter { node ->
            synchronized(failures) { failures[node.nodeId]?.blockedUntilMillis?.let { it <= now } ?: true }
        }
        return rankEdgeCatalogNodes(active)
    }

    private companion object {
        val HEALTH_PATHS = listOf("/edge/v2/health", "/edge/probe")
        const val DISCOVERY_QUERY_TIMEOUT_MS = 300L
        const val DISCOVERY_PREWARM_TIMEOUT_MS = 1_800L
        const val PROBE_CONNECT_TIMEOUT_MS = 200L
        const val PROBE_READ_TIMEOUT_MS = 350L
        const val PROBE_CALL_TIMEOUT_MS = 500L
        const val HEALTH_CACHE_MS = 30_000L
        const val NEGATIVE_CACHE_MS = 30_000L
        const val FAILURE_THRESHOLD = 2
        const val CIRCUIT_BREAKER_MS = 30_000L
    }
}

internal fun rankEdgeCatalogNodes(nodes: List<EdgeCatalogNode>): List<EdgeCatalogNode> =
    nodes.sortedWith(
        compareBy<EdgeCatalogNode> { it.isStale }
            .thenByDescending { it.sequence }
            .thenBy { it.failureCount }
            .thenBy { it.latencyMillis },
    )

internal fun shouldUseEdgeRegistryFallback(nodes: List<EdgeCatalogNode>, isOffline: Boolean): Boolean =
    nodes.isEmpty() && !isOffline

internal fun isEdgeServedCatalogResponse(servedByHeader: String?): Boolean =
    servedByHeader in setOf("edge-catalog", "edge-cache", "center-via-edge")

internal fun shouldRetryEdgeAuthentication(statusCode: Int): Boolean = statusCode == 401

internal fun edgeCatalogProtocol(capabilities: List<String>): EdgeCatalogProtocol? = when {
    "catalog_v2" in capabilities -> EdgeCatalogProtocol.V2
    capabilities.any(LEGACY_CATALOG_CAPABILITIES::contains) -> EdgeCatalogProtocol.Legacy
    else -> null
}

internal fun EdgeCatalogNode.supportsCatalogPath(path: String): Boolean = when {
    protocol == EdgeCatalogProtocol.V2 -> true
    path.startsWith("products") || path.startsWith("skus") ->
        capabilities.any(LEGACY_PRODUCT_CATALOG_CAPABILITIES::contains)
    path == "box-labels/resolve" -> "box_label_catalog" in capabilities
    else -> false
}

private fun String.toEpochMillisOrNull(): Long? =
    takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private data class EdgeAuthorization(
    val headerName: String,
    val headerValue: String,
)

internal data class EdgeCatalogResponse(
    val payload: JsonElement,
    val node: EdgeCatalogNode,
    val authorization: Map<String, String>,
    val servedByEdge: Boolean,
)

private sealed interface EdgeAttempt {
    data class Success(val response: EdgeCatalogResponse) : EdgeAttempt
    data class AuthoritativeFailure(val error: FscmApiException) : EdgeAttempt
    data object AuthenticationFailure : EdgeAttempt
    data object RetryableFailure : EdgeAttempt
}

private data class LocalCatalogRequest(
    val method: String,
    val path: String,
    val body: JsonElement,
)

@Singleton
class EdgeCatalogClient @Inject constructor(
    private val router: EdgeNodeRouter,
    private val sessionStore: SessionStore,
    private val deviceIdentityManager: DeviceIdentityManager,
    private val trustedClock: TrustedClock,
    private val edgeTicketProvider: EdgeTicketProvider,
    @param:FscmJson private val json: Json,
) {
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(LOCAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LOCAL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(LOCAL_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun prewarm() {
        val nodes = router.prewarm().filterNot(EdgeCatalogNode::isStale)
        coroutineScope { nodes.map { node -> async { authorization(node) } }.awaitAll() }
    }

    fun invalidate() {
        router.invalidate()
    }

    internal suspend fun get(path: String, params: Map<String, String> = emptyMap()): EdgeCatalogResponse? =
        request("GET", path, params, JsonNull)

    internal suspend fun post(path: String, body: JsonElement): EdgeCatalogResponse? =
        request("POST", path, emptyMap(), body)

    private suspend fun request(
        method: String,
        path: String,
        params: Map<String, String>,
        body: JsonElement,
    ): EdgeCatalogResponse? {
        val nodes = router.candidates().filter { it.supportsCatalogPath(path) }
        if (nodes.isEmpty()) return null
        return withTimeoutOrNull(LOCAL_DEADLINE_MS) {
            coroutineScope {
                val outcomes = Channel<EdgeAttempt>(Channel.UNLIMITED)
                val jobs = nodes.mapIndexed { index, node ->
                    launch {
                        delay(index * HEDGE_DELAY_MS)
                        outcomes.send(execute(node, method, path, params, body))
                    }
                }
                var result: EdgeCatalogResponse? = null
                for (attempt in nodes.indices) {
                    when (val outcome = outcomes.receive()) {
                        is EdgeAttempt.Success -> {
                            result = outcome.response
                            break
                        }
                        is EdgeAttempt.AuthoritativeFailure -> {
                            jobs.forEach { it.cancel() }
                            throw outcome.error
                        }
                        EdgeAttempt.AuthenticationFailure,
                        EdgeAttempt.RetryableFailure,
                        -> Unit
                    }
                }
                jobs.forEach { it.cancel() }
                outcomes.close()
                result
            }
        }
    }

    private suspend fun execute(
        node: EdgeCatalogNode,
        method: String,
        path: String,
        params: Map<String, String>,
        body: JsonElement,
    ): EdgeAttempt {
        val authorization = authorization(node) ?: return EdgeAttempt.RetryableFailure
        val initial = executeOnce(node, method, path, params, body, authorization)
        if (initial != EdgeAttempt.AuthenticationFailure || authorization.headerName != "X-Edge-Ticket") {
            return initial
        }
        edgeTicketProvider.invalidate(node.nodeId)
        val renewed = authorization(node) ?: return EdgeAttempt.RetryableFailure
        return executeOnce(node, method, path, params, body, renewed)
            .takeUnless { it == EdgeAttempt.AuthenticationFailure }
            ?: EdgeAttempt.RetryableFailure
    }

    private suspend fun executeOnce(
        node: EdgeCatalogNode,
        method: String,
        path: String,
        params: Map<String, String>,
        body: JsonElement,
        authorization: EdgeAuthorization,
    ): EdgeAttempt = withContext(Dispatchers.IO) {
        runCatching {
            val localRequest = node.localRequest(method, path, body) ?: return@runCatching EdgeAttempt.RetryableFailure
            val url = (node.baseUrl + localRequest.path).toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply {
                    params.forEach { (key, value) ->
                        addQueryParameter(key, value)
                        if (node.protocol == EdgeCatalogProtocol.Legacy && key == "pageSize") {
                            addQueryParameter("page_size", value)
                        }
                    }
                }
                ?.build()
                ?: return@runCatching EdgeAttempt.RetryableFailure
            val requestBuilder = Request.Builder().url(url)
                .header(authorization.headerName, authorization.headerValue)
            sessionStore.current().currentNamespace?.id?.takeIf { it > 0 }?.let {
                requestBuilder.header("X-Namespace-ID", it.toString())
            }
            if (localRequest.method == "POST") {
                requestBuilder.post(localRequest.body.toString().toRequestBody(JSON_MEDIA_TYPE))
            } else {
                requestBuilder.get()
            }
            val session = sessionStore.current()
            if (session.isOffline) {
                deviceIdentityManager.proofHeaders(
                    method = localRequest.method,
                    path = localRequest.path,
                    body = if (localRequest.method == "POST") localRequest.body.toString() else "",
                    timestampMillis = trustedClock.trustedNowMillis() ?: return@runCatching EdgeAttempt.RetryableFailure,
                ).forEach(requestBuilder::header)
            }
            localClient.newCall(requestBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val parsed = raw.takeIf(String::isNotBlank)
                    ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                if (shouldRetryEdgeAuthentication(response.code)) return@use EdgeAttempt.AuthenticationFailure
                if (!response.isSuccessful && parsed != null) {
                    val message = parsed.messageFromPayload() ?: response.message.ifBlank { "边缘节点查询失败" }
                    return@use EdgeAttempt.AuthoritativeFailure(
                        FscmApiException(message, response.code, parsed, message),
                    )
                }
                if (!response.isSuccessful || parsed == null) return@use EdgeAttempt.RetryableFailure
                val payload = runCatching { parsed.unwrapStandardPayload(response.code) }
                    .getOrElse { return@use EdgeAttempt.RetryableFailure }
                router.recordSuccess(node.nodeId)
                EdgeAttempt.Success(
                    EdgeCatalogResponse(
                        payload = payload,
                        node = node,
                        authorization = mapOf(authorization.headerName to authorization.headerValue),
                        servedByEdge = isEdgeServedCatalogResponse(response.header("X-FSCM-Served-By")),
                    ),
                )
            }
        }.getOrElse {
            router.recordFailure(node.nodeId)
            EdgeAttempt.RetryableFailure
        }
    }

    private suspend fun authorization(node: EdgeCatalogNode): EdgeAuthorization? {
        val session = sessionStore.current()
        if (session.isOffline) {
            if (session.offlineLease.isBlank() || "edge.cache.read" !in session.offlineScopes) return null
            return EdgeAuthorization("X-FSCM-Offline-Lease", session.offlineLease)
        }
        val ticket = edgeTicketProvider.ticket(node.nodeId) ?: return null
        return EdgeAuthorization("X-Edge-Ticket", ticket)
    }

    private fun EdgeCatalogNode.localRequest(
        method: String,
        path: String,
        body: JsonElement,
    ): LocalCatalogRequest? {
        if (protocol == EdgeCatalogProtocol.V2) {
            return LocalCatalogRequest(method, "/edge/v2/catalog/" + path.trimStart('/'), body)
        }
        if (path == "box-labels/resolve") {
            val fields = body as? JsonObject ?: return null
            val code = fields["label_code"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: fields["raw_code"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return null
            return LocalCatalogRequest(
                method = "GET",
                path = "/api/box-labels/${URLEncoder.encode(code, "UTF-8")}/resolve",
                body = JsonNull,
            )
        }
        return LocalCatalogRequest("GET", "/api/" + path.trimStart('/'), JsonNull)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val LOCAL_CONNECT_TIMEOUT_MS = 250L
        const val LOCAL_READ_TIMEOUT_MS = 700L
        const val LOCAL_CALL_TIMEOUT_MS = 850L
        const val LOCAL_DEADLINE_MS = 900L
        const val HEDGE_DELAY_MS = 150L
    }
}

private const val MAX_FRESHNESS_MS = 10_000L
private val LEGACY_PRODUCT_CATALOG_CAPABILITIES = setOf("catalog_cache", "sku_query")
private val LEGACY_CATALOG_CAPABILITIES = LEGACY_PRODUCT_CATALOG_CAPABILITIES + "box_label_catalog"
