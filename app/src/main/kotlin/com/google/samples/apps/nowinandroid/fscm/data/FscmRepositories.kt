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
import android.os.Build
import android.provider.Settings
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class AuthLoginResult(
    val acceptedFromLocalCredential: Boolean,
)

@Singleton
class AuthRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
) {
    val session = sessionStore.session

    suspend fun getLoginConfig(): LoginConfig =
        api.get("auth/login-config", auth = false)

    suspend fun login(username: String, password: String) {
        val payload = api.post<AuthPayload>(
            path = "auth/login",
            body = buildJsonObject {
                put("username", username)
                put("password", password)
            },
            auth = false,
        )
        sessionStore.applyAuth(payload, username = username, password = password)
    }

    suspend fun bootstrap(): Boolean {
        val current = sessionStore.current()
        if (current.token.isBlank()) return sessionStore.enterOfflineMode()
        return runCatching {
            val payload = api.get<AuthPayload>("auth/me")
            sessionStore.applyAuth(payload, preserveNamespace = true)
            true
        }.getOrElse { error ->
            if (current.isLoggedIn && current.hasValidOfflineSession && error !is FscmApiException) {
                sessionStore.enterOfflineMode()
            } else {
                sessionStore.clear()
                false
            }
        }
    }

    suspend fun loginOrOffline(username: String, password: String): AuthLoginResult {
        if (sessionStore.hasValidOfflineCredential() && sessionStore.tryOfflineLogin(username, password)) {
            return AuthLoginResult(acceptedFromLocalCredential = true)
        }
        login(username, password)
        return AuthLoginResult(acceptedFromLocalCredential = false)
    }

    suspend fun validateLocalLoginOnline(username: String, password: String): Boolean {
        return runCatching {
            login(username, password)
            true
        }.getOrElse { error ->
            if (error is FscmApiException) {
                sessionStore.clear()
                false
            } else {
                true
            }
        }
    }

    suspend fun switchNamespace(namespaceId: Long) {
        val response = api.put<SwitchNamespaceResponse>(
            "user/namespaces/switch",
            buildJsonObject { put("namespace_id", namespaceId) },
        )
        val matched = sessionStore.current().namespaces.firstOrNull { it.id == namespaceId }
        sessionStore.replaceNamespace(response.namespace ?: matched)
        bootstrap()
    }

    suspend fun logout() {
        runCatching { api.post<JsonObject>("auth/logout") }
        sessionStore.clear()
    }
}

@Singleton
class MobileRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    @param:FscmJson
    private val json: Json,
) {
    suspend fun workbenchOverview(): MobileWorkbenchOverview {
        val namespaceId = sessionStore.namespaceId()
        if (sessionStore.current().isOffline) {
            return cachedWorkbench(namespaceId) ?: MobileWorkbenchOverview()
        }
        return runCatching {
            api.get<MobileWorkbenchOverview>("mobile/app/workbench/overview")
        }.onSuccess { overview ->
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    namespaceId = namespaceId,
                    cacheKey = WORKBENCH_CACHE_KEY,
                    payload = json.encodeToString(overview),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.getOrElse { error ->
            cachedWorkbench(namespaceId)?.takeIf { error !is FscmApiException } ?: throw error
        }
    }

    private suspend fun cachedWorkbench(namespaceId: Long): MobileWorkbenchOverview? =
        offlineCacheDao.find(namespaceId, WORKBENCH_CACHE_KEY)
            ?.let { runCatching { json.decodeFromString<MobileWorkbenchOverview>(it.payload) }.getOrNull() }

    private companion object {
        const val WORKBENCH_CACHE_KEY = "workbench_overview"
    }
}

@Singleton
class GlobalSearchRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    @param:FscmJson
    private val json: Json,
) {
    private val runtimeBusinessTypes = mutableMapOf<String, List<SearchBusinessType>>()

    suspend fun search(
        query: String,
        overview: MobileWorkbenchOverview? = null,
        includeLocal: Boolean = true,
    ): GlobalSearchResponse {
        val session = sessionStore.current()
        val normalized = normalizeSearchQuery(query)
        if (normalized.isBlank()) return GlobalSearchResponse()

        if (includeLocal) {
            val localHits = localSearchHits(normalized, searchableMobileEntries(overview, session))
            if (localHits.isNotEmpty()) {
                return GlobalSearchResponse(query.trim(), normalized, localHits)
            }
        }

        val namespaceId = session.currentNamespace?.id ?: 0
        val cacheKey = searchCacheKey(session.user?.id ?: 0, normalized)
        cachedSearch(namespaceId, cacheKey, session)?.let { return it }
        if (session.isOffline) throw IllegalStateException("离线状态下没有该搜索记录")

        val allowedTypes = loadBusinessTypes(namespaceId, session)
        val response = api.get<GlobalSearchResponse>(
            "mobile/app/search",
            mapOf("q" to query.trim(), "limit" to SEARCH_LIMIT.toString()),
        ).copy(query = query.trim(), normalizedQuery = normalized)
        val filtered = response.copy(hits = filterAllowedHits(response.hits, allowedTypes, session.permissions))
        if (filtered.hits.isNotEmpty()) {
            val now = System.currentTimeMillis()
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    namespaceId = namespaceId,
                    cacheKey = cacheKey,
                    payload = json.encodeToString(SearchCachePayload(filtered, now)),
                    updatedAt = now,
                ),
            )
            prune(namespaceId, session.user?.id ?: 0, now)
        }
        return filtered
    }

    suspend fun destinationFor(
        hit: GlobalSearchHit,
        overview: MobileWorkbenchOverview?,
    ): SearchDestination {
        if (hit.businessType != BusinessTypeCode.LocalApp.wireValue) {
            return SearchDestination.Business(hit)
        }
        val session = sessionStore.current()
        val entry = searchableMobileEntries(overview, session)
            .firstOrNull { it.key == hit.targetCode }
            ?: MobileAppEntry(key = hit.targetCode, title = hit.title)
        return SearchDestination.LocalApp(entry)
    }

    private suspend fun cachedSearch(
        namespaceId: Long,
        cacheKey: String,
        session: SessionState,
    ): GlobalSearchResponse? {
        val entity = offlineCacheDao.find(namespaceId, cacheKey) ?: return null
        if (System.currentTimeMillis() - entity.updatedAt > SEARCH_CACHE_TTL_MS) return null
        val payload = runCatching { json.decodeFromString<SearchCachePayload>(entity.payload) }.getOrNull() ?: return null
        val allowedTypes = loadBusinessTypes(namespaceId, session, allowNetwork = false)
        val hits = filterAllowedHits(payload.response.hits, allowedTypes, session.permissions)
        return payload.response.copy(hits = hits).takeIf { hits.isNotEmpty() }
    }

    private suspend fun loadBusinessTypes(
        namespaceId: Long,
        session: SessionState,
        allowNetwork: Boolean = true,
    ): List<SearchBusinessType> {
        val runtimeKey = buildString {
            append(namespaceId)
            append(':')
            append(session.user?.id ?: 0)
            append(':')
            append(session.permissions.sorted().joinToString(",").hashCode())
        }
        runtimeBusinessTypes[runtimeKey]?.let { return it }
        val cacheKey = businessTypesCacheKey(session.user?.id ?: 0)
        val cached = offlineCacheDao.find(namespaceId, cacheKey)
            ?.takeIf { System.currentTimeMillis() - it.updatedAt <= SEARCH_CACHE_TTL_MS }
            ?.let { runCatching { json.decodeFromString<SearchBusinessTypesResponse>(it.payload).types }.getOrNull() }
        if (!cached.isNullOrEmpty()) {
            runtimeBusinessTypes[runtimeKey] = cached
            return cached
        }
        if (allowNetwork && !session.isOffline) {
            val remote = runCatching {
                api.get<SearchBusinessTypesResponse>("mobile/app/search/business-types").types
            }.getOrNull().orEmpty()
            if (remote.isNotEmpty()) {
                runtimeBusinessTypes[runtimeKey] = remote
                offlineCacheDao.upsert(
                    OfflineCacheEntity(
                        namespaceId = namespaceId,
                        cacheKey = cacheKey,
                        payload = json.encodeToString(SearchBusinessTypesResponse(remote)),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                return remote
            }
        }
        return fallbackBusinessTypes(session.permissions)
    }

    private suspend fun prune(namespaceId: Long, userId: Long, now: Long) {
        val prefix = searchCachePrefix(userId)
        offlineCacheDao.deleteOlderThan(namespaceId, prefix, now - SEARCH_CACHE_TTL_MS)
        offlineCacheDao.trimToNewest(namespaceId, prefix, MAX_SEARCH_CACHE_ENTRIES)
    }

    private companion object {
        const val SEARCH_LIMIT = 20
        const val MAX_SEARCH_CACHE_ENTRIES = 200
        const val SEARCH_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

@Singleton
class EdgePrintRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    @param:FscmJson
    private val json: Json,
) {
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    suspend fun availableNodes(): List<EdgePrintNodeOption> {
        return printNodes().mapNotNull { status ->
            status.node.availablePrintTemplate()?.let { template ->
                EdgePrintNodeOption(status.node, template, status.localReachable)
            }
        }
    }

    suspend fun availableTemplateOptions(): List<EdgePrintNodeOption> =
        printNodes().flatMap { status ->
            status.node.printInventory.templates
                .filter { it.isLabelTemplate() && it.available && it.printerName.isNotBlank() }
                .map { template -> EdgePrintNodeOption(status.node, template, status.localReachable) }
        }

    suspend fun registeredNodes(): List<EdgePrintNodeStatus> {
        val nodes = api.get<PagedList<EdgeNode>>(
            "edge/nodes/available",
            edgeNodeListParams(includeOffline = true),
        ).rows()
        return nodeStatuses(nodes)
    }

    suspend fun printNodes(): List<EdgePrintNodeStatus> {
        val nodes = api.get<PagedList<EdgeNode>>(
            "edge/nodes/available",
            edgeNodeListParams(includeOffline = false),
        ).rows()
        return nodeStatuses(nodes)
    }

    private suspend fun nodeStatuses(nodes: List<EdgeNode>): List<EdgePrintNodeStatus> {
        return coroutineScope {
            nodes.map { node ->
                async(Dispatchers.IO) {
                    EdgePrintNodeStatus(node, probe(node))
                }
            }.awaitAll()
        }.sortedWith(
            compareByDescending<EdgePrintNodeStatus> { it.localReachable }
                .thenByDescending { it.node.status == "online" }
                .thenByDescending { it.node.lastSeenAt },
        )
    }

    suspend fun discoverLocalNodes(port: Int = DEFAULT_EDGE_PORT): List<EdgePrintNodeStatus> {
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
        val prefixes = localSubnetPrefixes()
        check(prefixes.isNotEmpty()) { "未检测到可用的局域网 IPv4 地址" }
        val semaphore = Semaphore(DISCOVERY_CONCURRENCY)
        return coroutineScope {
            prefixes.flatMap { prefix ->
                (1..254).map { host -> "http://$prefix.$host:$port" }
            }.map { baseUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { discoverLocalNode(baseUrl) }
                }
            }.awaitAll()
        }.filterNotNull()
            .distinctBy { it.node.nodeId.ifBlank { it.node.lanBaseUrl } }
            .sortedBy { it.node.nodeName }
    }

    suspend fun printSku(
        option: EdgePrintNodeOption,
        sku: Sku,
        copies: Int,
        labelPrefix: String,
    ): EdgePrintSubmission {
        require(sku.id > 0) { "SKU 缺少有效 ID" }
        require(copies in 1..100) { "打印份数必须在 1 到 100 之间" }
        val idempotencyKey = "mobile-label-${UUID.randomUUID()}"
        val stillReachable = option.localReachable && withContext(Dispatchers.IO) { probe(option.node) }
        return if (stillReachable) {
            runCatching { submitLocal(option, sku, copies, labelPrefix, idempotencyKey) }
                .getOrElse { error ->
                    if (error is LocalDirectPrintUnavailableException) {
                        submitRemote(option, sku, copies, labelPrefix, idempotencyKey)
                    } else {
                        throw error
                    }
                }
        } else {
            submitRemote(option, sku, copies, labelPrefix, idempotencyKey)
        }
    }

    suspend fun printLabel(
        option: EdgePrintNodeOption,
        content: String,
        copies: Int,
    ): EdgePrintSubmission {
        val normalized = content.trim()
        require(normalized.isNotBlank()) { "请输入或扫描标签内容" }
        require(normalized.length <= 512) { "标签内容不能超过 512 个字符" }
        require(copies in 1..100) { "打印份数必须在 1 到 100 之间" }
        val idempotencyKey = "mobile-custom-label-${UUID.randomUUID()}"
        val stillReachable = option.localReachable && withContext(Dispatchers.IO) { probe(option.node) }
        return if (stillReachable) {
            runCatching { submitLocalLabel(option, normalized, copies, idempotencyKey) }
                .getOrElse { error ->
                    if (error is LocalDirectPrintUnavailableException) {
                        submitRemoteLabel(option, normalized, copies, idempotencyKey)
                    } else {
                        throw error
                    }
                }
        } else {
            submitRemoteLabel(option, normalized, copies, idempotencyKey)
        }
    }

    private fun probe(node: EdgeNode): Boolean {
        if (node.lanBaseUrl.isBlank()) return false
        val payload = fetchProbe(node.lanBaseUrl, localClient) ?: return false
        return payload.ok && payload.nodeId == node.nodeId && payload.directPrintAvailable != false
    }

    private fun fetchProbe(baseUrl: String, client: OkHttpClient): LocalEdgeProbe? = runCatching {
        localEdgeBaseUrlCandidates(baseUrl).firstNotNullOfOrNull { candidate ->
            runCatching {
                val request = Request.Builder()
                    .url(candidate + "/edge/probe")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString<LocalEdgeProbe>(body).takeIf { it.ok }
                }
            }.getOrNull()
        }
    }.getOrNull()

    private fun discoverLocalNode(baseUrl: String): EdgePrintNodeStatus? {
        val probe = fetchProbe(baseUrl, discoveryClient) ?: return null
        val config = fetchLocalPrintConfig(baseUrl)
        val printer = config?.defaultPrinter.orEmpty()
        val templateCode = config?.template.orEmpty()
        val inventory = EdgePrintInventory(
            printers = printer.takeIf { it.isNotBlank() }
                ?.let { listOf(EdgePrinter(name = it, isDefault = true, status = "ready")) }
                .orEmpty(),
            templates = templateCode.takeIf { it.isNotBlank() }
                ?.let {
                    listOf(
                        EdgePrintTemplate(
                            code = it,
                            name = it,
                            printerName = printer,
                            widthMM = config?.widthMM ?: 0.0,
                            heightMM = config?.heightMM ?: 0.0,
                            orientation = config?.orientation.orEmpty(),
                            available = printer.isNotBlank(),
                        ),
                    )
                }.orEmpty(),
        )
        return EdgePrintNodeStatus(
            node = EdgeNode(
                nodeId = probe.nodeId,
                nodeName = probe.nodeName.ifBlank { probe.nodeId },
                lanBaseUrl = baseUrl,
                capabilities = probe.capabilities,
                printInventory = inventory,
                status = "online",
            ),
            localReachable = probe.directPrintAvailable != false,
        )
    }

    private fun fetchLocalPrintConfig(baseUrl: String): LocalEdgePrintConfig? = runCatching {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/edge/print-config")
            .get()
            .build()
        localClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            json.decodeFromString<LocalEdgePrintConfig>(response.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun localSubnetPrefixes(): List<String> = runCatching {
        currentLocalIPv4Addresses()
            .asSequence()
            .mapNotNull(::ipv4SubnetPrefix)
            .distinct()
            .take(MAX_DISCOVERY_SUBNETS)
            .toList()
    }.getOrDefault(emptyList())

    private suspend fun submitLocal(
        option: EdgePrintNodeOption,
        sku: Sku,
        copies: Int,
        labelPrefix: String,
        idempotencyKey: String,
    ): EdgePrintSubmission = withContext(Dispatchers.IO) {
        val token = sessionStore.current().token
        check(token.isNotBlank()) { "登录状态已失效" }
        val payload = localPrintPayload(option, sku, copies, labelPrefix, idempotencyKey)
        val request = Request.Builder()
            .url(localEdgeDirectBaseUrl(option.node.lanBaseUrl) + "/edge/print-jobs/direct")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        localClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (isDirectPrintRouteUnavailable(response.code)) {
                    throw LocalDirectPrintUnavailableException(response.code)
                }
                val error = runCatching { json.decodeFromString<LocalEdgePrintResponse>(body).error }.getOrNull()
                throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "本地节点打印失败 (${response.code})")
            }
            val result = json.decodeFromString<LocalEdgePrintResponse>(body)
            EdgePrintSubmission(
                nodeName = option.node.nodeName,
                local = true,
                status = result.status,
                jobId = result.job?.id.orEmpty(),
            )
        }
    }

    private suspend fun submitRemote(
        option: EdgePrintNodeOption,
        sku: Sku,
        copies: Int,
        labelPrefix: String,
        idempotencyKey: String,
    ): EdgePrintSubmission {
        val job = api.post<EdgePrintJob>(
            "edge/print-jobs",
            buildJsonObject {
                put("edge_node_id", option.node.id)
                put("sku_id", sku.id)
                put("template_code", option.template.code)
                put("copies", copies)
                put("label_prefix", labelPrefix)
                put("idempotency_key", idempotencyKey)
            },
        )
        return EdgePrintSubmission(
            nodeName = option.node.nodeName,
            local = false,
            status = job.status,
            jobId = job.id.toString(),
        )
    }

    private suspend fun submitLocalLabel(
        option: EdgePrintNodeOption,
        content: String,
        copies: Int,
        idempotencyKey: String,
    ): EdgePrintSubmission = withContext(Dispatchers.IO) {
        val token = sessionStore.current().token
        check(token.isNotBlank()) { "登录状态已失效" }
        val payload = LocalEdgeDirectLabelPrintRequest(
            templateCode = option.template.code,
            text = content,
            labelPrefix = "",
            copies = copies,
            idempotencyKey = idempotencyKey,
        )
        val request = Request.Builder()
            .url(localEdgeDirectBaseUrl(option.node.lanBaseUrl) + "/edge/print-jobs/direct")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        localClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (isDirectPrintRouteUnavailable(response.code)) {
                    throw LocalDirectPrintUnavailableException(response.code)
                }
                val error = runCatching { json.decodeFromString<LocalEdgePrintResponse>(body).error }.getOrNull()
                throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "本地节点打印失败 (${response.code})")
            }
            val result = json.decodeFromString<LocalEdgePrintResponse>(body)
            EdgePrintSubmission(
                nodeName = option.node.nodeName,
                local = true,
                status = result.status,
                jobId = result.job?.id.orEmpty(),
            )
        }
    }

    private suspend fun submitRemoteLabel(
        option: EdgePrintNodeOption,
        content: String,
        copies: Int,
        idempotencyKey: String,
    ): EdgePrintSubmission {
        val job = api.post<EdgePrintJob>(
            "edge/print-jobs",
            buildJsonObject {
                put("edge_node_id", option.node.id)
                put("template_code", option.template.code)
                put("copies", copies)
                put("label_content", content)
                put("idempotency_key", idempotencyKey)
            },
        )
        return EdgePrintSubmission(
            nodeName = option.node.nodeName,
            local = false,
            status = job.status,
            jobId = job.id.toString(),
        )
    }

    private fun localPrintPayload(
        option: EdgePrintNodeOption,
        sku: Sku,
        copies: Int,
        labelPrefix: String,
        idempotencyKey: String,
    ) = LocalEdgePrintRequest(
        source = "mobile-app",
        printer = option.template.printerName,
        template = option.template.code,
        copies = copies,
        type = "sku_label",
        idempotencyKey = idempotencyKey,
        payloadSnapshot = EdgeSkuLabelPayload(
            skuId = sku.id,
            skuCode = sku.code,
            skuName = sku.name,
            spec = sku.spec,
            color = sku.color,
            productCode = sku.product?.code.orEmpty(),
            qrPayload = buildLabelCode(labelPrefix, sku.code),
            labelPrefix = labelPrefix.trim(),
            templateVersion = option.template.version,
        ),
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_EDGE_PORT = 8088
        const val DISCOVERY_CONCURRENCY = 32
        const val MAX_DISCOVERY_SUBNETS = 2
    }
}

@Singleton
class EdgeTerminalRepository @Inject constructor(
    private val api: FscmApiClient,
    private val edgePrintRepository: EdgePrintRepository,
    private val sessionStore: SessionStore,
    @param:ApplicationContext private val context: Context,
    @param:FscmJson private val json: Json,
) {
    private val registrationMutex = Mutex()
    private var lastRegistrationKey = ""
    private var lastRegistrationAtMillis = 0L

    suspend fun registerCurrentTerminal() {
        val session = sessionStore.current()
        if (session.isOffline || session.token.isBlank() || session.currentNamespace == null) return

        val lanIp = currentLocalIPv4Addresses().firstOrNull().orEmpty()
        val reachableNodes = edgePrintRepository.registeredNodes().filter { it.localReachable && it.node.nodeId.isNotBlank() }
        if (reachableNodes.isEmpty()) return

        val registrationKey = buildString {
            append(session.currentNamespace.id)
            append(':')
            append(lanIp.ifBlank { "unknown" })
            append(':')
            append(reachableNodes.joinToString(",") { it.node.nodeId })
        }
        registrationMutex.withLock {
            val now = System.currentTimeMillis()
            if (registrationKey == lastRegistrationKey && now - lastRegistrationAtMillis < REGISTRATION_INTERVAL_MS) return

            val baseRequest = EdgeTerminalRegistrationRequest(
                terminalId = terminalId(),
                edgeNodeId = "",
                terminalName = terminalName(),
                appVersion = BuildConfig.VERSION_NAME,
                platform = "android",
                lanIp = lanIp,
                capabilities = TERMINAL_CAPABILITIES,
            )
            var registered = false
            reachableNodes.forEach { status ->
                runCatching {
                    api.post<Unit>(
                        path = "edge/terminals/register",
                        body = json.encodeToJsonElement(baseRequest.copy(edgeNodeId = status.node.nodeId)),
                    )
                }.onSuccess { registered = true }
            }
            if (registered) {
                lastRegistrationKey = registrationKey
                lastRegistrationAtMillis = now
            }
        }
    }

    private fun terminalId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val identity = androidId?.takeIf { it.isNotBlank() } ?: "${Build.FINGERPRINT}:${context.packageName}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${context.packageName}:$identity".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "android-${hash.take(32)}"
    }

    private fun terminalName(): String =
        listOf("FSCM", Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(200)

    private companion object {
        const val REGISTRATION_INTERVAL_MS = 60_000L
        val TERMINAL_CAPABILITIES = listOf("mobile-app", "pda-scan", "edge-print-client")
    }
}

@Serializable
private data class LocalEdgeProbe(
    val ok: Boolean = false,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("direct_print_available") val directPrintAvailable: Boolean? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class LocalEdgePrintConfig(
    @SerialName("default_printer") val defaultPrinter: String = "",
    val template: String = "",
    @SerialName("width_mm") val widthMM: Double = 0.0,
    @SerialName("height_mm") val heightMM: Double = 0.0,
    val orientation: String = "",
)

@Serializable
private data class LocalEdgePrintRequest(
    val source: String,
    val printer: String,
    val template: String,
    val copies: Int,
    val type: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("payload_snapshot") val payloadSnapshot: EdgeSkuLabelPayload,
)

@Serializable
private data class LocalEdgeDirectLabelPrintRequest(
    @SerialName("template_code") val templateCode: String,
    val text: String,
    @SerialName("label_prefix") val labelPrefix: String,
    val copies: Int,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class EdgeSkuLabelPayload(
    @SerialName("sku_id") val skuId: Long,
    @SerialName("sku_code") val skuCode: String,
    @SerialName("sku_name") val skuName: String,
    val spec: String,
    val color: String,
    @SerialName("product_code") val productCode: String,
    @SerialName("qr_payload") val qrPayload: String,
    @SerialName("label_prefix") val labelPrefix: String,
    @SerialName("template_version") val templateVersion: String,
)

@Serializable
private data class LocalEdgePrintJob(
    val id: String = "",
)

@Serializable
private data class LocalEdgePrintResponse(
    val status: String = "",
    val job: LocalEdgePrintJob? = null,
    val error: String = "",
)

private class LocalDirectPrintUnavailableException(statusCode: Int) :
    IllegalStateException("本地直打接口不可用 ($statusCode)")

internal fun isDirectPrintRouteUnavailable(statusCode: Int): Boolean =
    statusCode == 404 || statusCode == 405

internal fun localEdgeBaseUrlCandidates(baseUrl: String): List<String> {
    val normalized = baseUrl.trim().trimEnd('/')
    if (normalized.isBlank()) return emptyList()
    val withoutApiSuffix = normalized.removeSuffix("/api").trimEnd('/')
    return listOf(withoutApiSuffix, normalized).filter { it.isNotBlank() }.distinct()
}

internal fun localEdgeDirectBaseUrl(baseUrl: String): String =
    localEdgeBaseUrlCandidates(baseUrl).firstOrNull() ?: baseUrl.trim().trimEnd('/')

internal fun edgeNodeListParams(includeOffline: Boolean): Map<String, String> = buildMap {
    if (includeOffline) put("include_offline", "true")
    put("page_size", "100")
}

internal fun currentLocalIPv4Addresses(): List<String> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress.orEmpty() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}.getOrDefault(emptyList())

internal fun EdgeNode.availablePrintTemplate(): EdgePrintTemplate? {
    if (status != "online") return null
    return printInventory.templates.firstOrNull { it.isLabelTemplate() && it.available && it.printerName.isNotBlank() }
}

internal fun EdgePrintTemplate.isLabelTemplate(): Boolean = type.equals("label", ignoreCase = true)

internal fun buildLabelCode(prefix: String, skuCode: String): String =
    prefix.trim() + skuCode

internal fun ipv4SubnetPrefix(address: String): String? {
    val octets = address.split('.')
    if (octets.size != 4 || octets.any { octet -> octet.toIntOrNull()?.let { it in 0..255 } != true }) return null
    return octets.take(3).joinToString(".")
}

internal fun normalizeSearchQuery(query: String): String =
    query.trim().trimEnd('\r', '\n', '\t').lowercase(Locale.ROOT)

internal fun localSearchHits(query: String, entries: List<MobileAppEntry>): List<GlobalSearchHit> {
    val normalized = normalizeSearchQuery(query)
    return entries.mapNotNull { entry ->
        val candidates = buildList {
            add(entry.key)
            add(entry.title)
            add(entry.description)
            addAll(localSearchAliases[entry.key].orEmpty())
        }.map(::normalizeSearchQuery).filter { it.isNotBlank() }
        val kind = when {
            candidates.any { it == normalized } -> "exact"
            candidates.any { it.startsWith(normalized) } -> "prefix"
            candidates.any { normalized in it } -> "fuzzy"
            else -> return@mapNotNull null
        }
        GlobalSearchHit(
            businessType = BusinessTypeCode.LocalApp.wireValue,
            targetCode = entry.key,
            title = entry.title,
            subtitle = entry.description,
            matchKind = kind,
            score = when (kind) {
                "exact" -> 100.0
                "prefix" -> 70.0
                else -> 40.0
            },
            source = "local",
        )
    }.sortedByDescending { it.score }
}

private fun searchableMobileEntries(
    overview: MobileWorkbenchOverview?,
    session: SessionState,
): List<MobileAppEntry> {
    val apps = visibleMobileApps(overview, session.permissions, session.sessionMode)
    val quickEntries = overview?.quickEntries.orEmpty().filter { entry ->
        entry.visible &&
            (entry.permission.isBlank() || entry.permission in session.permissions) &&
            (entry.businessPermission.isBlank() || entry.businessPermission in session.permissions)
    }
    return (apps + quickEntries)
        .filter { it.key in nativeSearchableAppKeys }
        .distinctBy { it.key }
}

private fun filterAllowedHits(
    hits: List<GlobalSearchHit>,
    businessTypes: List<SearchBusinessType>,
    permissions: List<String>,
): List<GlobalSearchHit> {
    val enabledCodes = businessTypes.filter { it.enabled }.mapTo(mutableSetOf()) { it.code }
    return hits.filter { hit ->
        hit.businessType in enabledCodes && when (hit.businessType.toBusinessTypeCode()) {
            BusinessTypeCode.SkuDetail -> permissions.any { it in setOf("skus", "products", "mobile-app:product") }
            BusinessTypeCode.BoxLabelDetail -> permissions.any { it in setOf("mobile-app:box-labels", "mobile-app:carton", "box_labels") }
            BusinessTypeCode.LocalApp -> true
            BusinessTypeCode.Unknown -> true
        }
    }
}

private fun fallbackBusinessTypes(permissions: List<String>): List<SearchBusinessType> = buildList {
    if (permissions.any { it in setOf("skus", "products", "mobile-app:product") }) {
        add(SearchBusinessType(BusinessTypeCode.SkuDetail.wireValue, "SKU 详情", listOf("skus", "products")))
    }
    if (permissions.any { it in setOf("mobile-app:box-labels", "mobile-app:carton", "box_labels") }) {
        add(SearchBusinessType(BusinessTypeCode.BoxLabelDetail.wireValue, "箱唛详情", listOf("mobile-app:box-labels")))
    }
}

private fun searchCachePrefix(userId: Long): String = "global_search:$userId:"

private fun searchCacheKey(userId: Long, normalizedQuery: String): String =
    searchCachePrefix(userId) + MessageDigest.getInstance("SHA-256")
        .digest(normalizedQuery.toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun businessTypesCacheKey(userId: Long): String = "global_search_types:$userId"

private val nativeSearchableAppKeys = setOf(
    "profit",
    "receiving",
    "central_receiving",
    "product_management",
    "box_labels",
    "carton_management",
    "pda_settings",
    "pda_scanner_settings",
    "scanner_settings",
    "print_settings",
    "printer_settings",
    "label_print_settings",
    "edge_print_settings",
    "edge_nodes",
    "edge_node",
    "label_print",
    "label_printing",
    "print_label",
    "custom_label_print",
)

private val localSearchAliases = mapOf(
    "profit" to listOf("利润", "利润看板"),
    "receiving" to listOf("收货", "扫码入库", "仓库收货"),
    "central_receiving" to listOf("中心仓收货"),
    "product_management" to listOf("产品", "SKU", "SKU 管理"),
    "box_labels" to listOf("箱唛", "箱唛管理"),
    "carton_management" to listOf("箱唛", "箱唛管理"),
    "pda_settings" to listOf("PDA", "PDA 设置", "扫描设置"),
    "edge_nodes" to listOf("边缘节点", "边缘服务", "服务节点", "局域网探测"),
    "label_print" to listOf("标签打印", "字符打印", "条码打印", "打印标签"),
)

@Singleton
class ProfitRepository @Inject constructor(
    private val api: FscmApiClient,
) {
    suspend fun overview(
        audience: String,
        startDate: String,
        endDate: String,
        platform: String,
        metric: String,
    ): ProfitOverview {
        val params = buildMap {
            put("audience", audience)
            put("start_date", startDate)
            put("end_date", endDate)
            put("metric", metric)
            put("granularity", "auto")
            put("top_n", "8")
            if (platform != "all") put("platform", platform)
        }
        return api.get("mobile/app/profit/overview", params)
    }

    suspend fun shopDetail(
        shopId: Long,
        audience: String,
        startDate: String,
        endDate: String,
    ): ProfitShopDetail = api.get(
        "mobile/app/profit/shops/$shopId",
        mapOf(
            "audience" to audience,
            "start_date" to startDate,
            "end_date" to endDate,
        ),
    )
}

@Singleton
class ReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson
    private val json: Json,
    private val sessionStore: SessionStore,
    private val receivingDao: ReceivingDao,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val offlineSyncScheduler: OfflineSyncScheduler,
) {
    suspend fun sessions(): List<ReceivingSession> {
        val namespaceId = sessionStore.namespaceId()
        if (sessionStore.current().isOffline) return cachedSessions(namespaceId)
        return runCatching {
            decodeReceivingSessionsPayload(
                payload = api.get("receiving-sessions", mapOf("page" to "1", "page_size" to "50")),
                json = json,
            )
        }.onSuccess { rows ->
            receivingDao.upsertSessions(rows.map { it.toEntity(namespaceId, json) })
        }.getOrElse { error ->
            cachedSessions(namespaceId).takeIf { it.isNotEmpty() && error !is FscmApiException } ?: throw error
        }
    }

    suspend fun session(id: Long): ReceivingSession {
        val namespaceId = sessionStore.namespaceId()
        if (sessionStore.current().isOffline) return cachedSession(namespaceId, id) ?: ReceivingSession(id = id)
        return runCatching {
            api.get<ReceivingSession>("receiving-sessions/$id")
        }.onSuccess { row ->
            receivingDao.upsertSession(row.toEntity(namespaceId, json))
        }.getOrElse { error ->
            cachedSession(namespaceId, id).takeIf { error !is FscmApiException } ?: throw error
        }
    }

    suspend fun preview(id: Long, rawCode: String): ReceivingScanResponse {
        if (sessionStore.current().isOffline) {
            return ReceivingScanResponse(
                data = ReceivingScanItem(
                    id = -System.currentTimeMillis(),
                    rawCode = rawCode,
                    labelCode = rawCode,
                    status = "pending_sync",
                ),
            )
        }
        return api.post(
            "receiving-sessions/$id/scan-preview",
            buildJsonObject { put("raw_code", rawCode) },
        )
    }

    suspend fun scan(
        id: Long,
        rawCode: String,
        damaged: Boolean,
        actualReceivedQty: Int? = null,
    ): ReceivingScanResponse {
        if (sessionStore.current().isOffline) {
            return enqueueOfflineScan(id, rawCode, damaged, actualReceivedQty)
        }
        return runCatching {
            api.post<ReceivingScanResponse>(
                "receiving-sessions/$id/scan",
                buildJsonObject {
                    put("raw_code", rawCode)
                    put("damaged", damaged)
                    put("tally_count", true)
                    if (actualReceivedQty != null) put("actual_received_qty", actualReceivedQty)
                },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineScan(id, rawCode, damaged, actualReceivedQty)
        }
    }

    suspend fun voidScan(id: Long, itemId: Long) {
        if (sessionStore.current().isOffline || itemId < 0) {
            enqueueOfflineVoid(id, itemId)
            return
        }
        runCatching {
            api.post<JsonObject>(
                "receiving-sessions/$id/scan-items/$itemId/void",
                buildJsonObject { put("reason", "移动端取消扫码") },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineVoid(id, itemId)
        }
    }

    suspend fun complete(id: Long) {
        if (sessionStore.current().isOffline) {
            enqueueOfflineComplete(id)
            return
        }
        runCatching {
            api.post<JsonObject>("receiving-sessions/$id/receiving-record")
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineComplete(id)
        }
    }

    private suspend fun enqueueOfflineScan(
        id: Long,
        rawCode: String,
        damaged: Boolean,
        actualReceivedQty: Int?,
    ): ReceivingScanResponse {
        val namespaceId = sessionStore.namespaceId()
        val localItem = ReceivingScanItem(
            id = -System.currentTimeMillis(),
            rawCode = rawCode,
            labelCode = rawCode,
            boxNo = rawCode,
            actualReceivedQty = actualReceivedQty ?: 0,
            status = if (damaged) "pending_exception" else "pending_sync",
        )
        updateCachedSession(namespaceId, id) { session ->
            val scanItems = listOf(localItem) + session.scanItems
            session.copy(
                scanItems = scanItems,
                scannedBoxCount = session.scannedBoxCount + 1,
                talliedBoxCount = session.talliedBoxCount + 1,
                pendingBoxCount = (session.pendingBoxCount - 1).coerceAtLeast(0),
                exceptionBoxCount = session.exceptionBoxCount + if (damaged) 1 else 0,
            )
        }
        offlineSyncRepository.enqueueReceivingScan(
            ReceivingScanOperationPayload(id, rawCode, damaged, actualReceivedQty),
        )
        offlineSyncScheduler.enqueue()
        return ReceivingScanResponse(localItem)
    }

    private suspend fun enqueueOfflineVoid(id: Long, itemId: Long) {
        val namespaceId = sessionStore.namespaceId()
        updateCachedSession(namespaceId, id) { session ->
            session.copy(
                scanItems = session.scanItems.map { item ->
                    if (item.id == itemId) item.copy(status = "void_pending_sync") else item
                },
            )
        }
        if (itemId > 0) {
            offlineSyncRepository.enqueueReceivingVoid(ReceivingVoidOperationPayload(id, itemId))
            offlineSyncScheduler.enqueue()
        }
    }

    private suspend fun enqueueOfflineComplete(id: Long) {
        val namespaceId = sessionStore.namespaceId()
        updateCachedSession(namespaceId, id) { it.copy(status = "complete_pending_sync") }
        offlineSyncRepository.enqueueReceivingComplete(ReceivingCompleteOperationPayload(id))
        offlineSyncScheduler.enqueue()
    }

    private suspend fun updateCachedSession(
        namespaceId: Long,
        id: Long,
        transform: (ReceivingSession) -> ReceivingSession,
    ) {
        val current = cachedSession(namespaceId, id) ?: ReceivingSession(id = id)
        receivingDao.upsertSession(transform(current).toEntity(namespaceId, json))
    }

    private suspend fun cachedSessions(namespaceId: Long): List<ReceivingSession> =
        receivingDao.sessions(namespaceId).mapNotNull { it.toModel(json) }

    private suspend fun cachedSession(namespaceId: Long, id: Long): ReceivingSession? =
        receivingDao.session(namespaceId, id)?.toModel(json)
}

@Singleton
class ProductRepository @Inject constructor(
    private val api: FscmApiClient,
) {
    suspend fun products(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
    ): PagedList<Product> = api.get(
        "products",
        mapOf(
            "keyword" to normalizeSku(keyword),
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    suspend fun skus(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
    ): PagedList<Sku> {
        val params = mutableMapOf(
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        )
        if (keyword.startsWith("productId:")) {
            params["product_id"] = keyword.removePrefix("productId:")
        } else {
            params["keyword"] = normalizeSku(keyword)
        }
        return api.get("skus", params)
    }

    suspend fun sku(idOrCode: String): Sku {
        val encoded = java.net.URLEncoder.encode(idOrCode.trim(), "UTF-8")
        return api.get("skus/$encoded")
    }

    private fun normalizeSku(query: String): String {
        val q = query.trim()
        if (q.length <= 1) return q

        // If user inputs "TB10-", we want to match both "TB10-*" and "B10-*"
        // The backend usually handles substring matching.
        // By stripping the T/P/R prefix even for partial inputs,
        // the backend search "B10-" will typically match both "TB10-T1" and "B10-T1"
        // because "B10-" is a substring of both if the index is smart,
        // OR we just provide the "root" part to increase hit rate.

        val firstChar = q[0]
        if (firstChar in "TPR") {
            if (q.length > 1 && (q[1].isUpperCase() || q[1].isDigit())) {
                return q.substring(1)
            }
        }
        return q
    }
}

@Singleton
class CartonRepository @Inject constructor(
    private val api: FscmApiClient,
) {
    suspend fun resolveBoxLabel(rawCode: String): BoxLabel {
        // Backend expects the raw code in the path, which might contain special characters
        val encoded = java.net.URLEncoder.encode(rawCode.trim(), "UTF-8")
        return api.get("box-labels/$encoded/resolve")
    }
}

private suspend fun SessionStore.namespaceId(): Long =
    current().currentNamespace?.id ?: 0

private fun ReceivingSession.toEntity(namespaceId: Long, json: Json): ReceivingSessionEntity =
    ReceivingSessionEntity(
        namespaceId = namespaceId,
        remoteId = id,
        code = code,
        payload = json.encodeToString(this),
        updatedAt = System.currentTimeMillis(),
    )

private fun ReceivingSessionEntity.toModel(json: Json): ReceivingSession? =
    runCatching { json.decodeFromString<ReceivingSession>(payload) }.getOrNull()

internal fun decodeReceivingSessionsPayload(
    payload: JsonElement,
    json: Json,
): List<ReceivingSession> = when (payload) {
    is JsonArray -> json.decodeFromJsonElement<List<ReceivingSession>>(payload)
    is JsonObject -> json.decodeFromJsonElement<PagedList<ReceivingSession>>(payload).rows()
    else -> emptyList()
}

fun visibleMobileApps(
    overview: MobileWorkbenchOverview?,
    permissions: List<String>,
    sessionMode: SessionMode = SessionMode.Online,
): List<MobileAppEntry> {
    val configured = overview?.appConfig?.apps?.takeIf { it.isNotEmpty() }
        ?: overview?.apps?.takeIf { it.isNotEmpty() }
        ?: defaultMobileApps
    val permissionSet = permissions.mapTo(mutableSetOf()) { it.trim() }
    return (configured.map(::normalizeMobileAppEntry) + defaultMobileApps).distinctBy { it.key }
        .filter { it.visible }
        .filter { it.permission.isBlank() || it.permission in permissionSet || it.permission == "mobile-app:product" }
        .filter { it.businessPermission.isBlank() || it.businessPermission in permissionSet || it.businessPermission == "products" }
        .filter { app ->
            sessionMode == SessionMode.Online ||
                (app.offlineSupported && app.key in offlineImplementedAppKeys && app.hasOfflinePermissions(permissionSet))
        }
        .sortedWith(compareBy<MobileAppEntry> { it.sort }.thenBy { it.title })
}

private fun normalizeMobileAppEntry(entry: MobileAppEntry): MobileAppEntry =
    if (entry.key in legacyEdgeNodeAppKeys) {
        entry.copy(
            key = "edge_nodes",
            title = "边缘节点",
            description = "服务中心与局域网探测",
        )
    } else {
        entry
    }

private val legacyEdgeNodeAppKeys = setOf(
    "print_settings",
    "printer_settings",
    "label_print_settings",
    "edge_print_settings",
)

private fun MobileAppEntry.hasOfflinePermissions(permissionSet: Set<String>): Boolean =
    offlineRequiredPermissions.all { it in permissionSet }

private val offlineImplementedAppKeys = setOf("receiving", "central_receiving")

val defaultMobileApps = listOf(
    MobileAppEntry("todos", "待办", "待处理事项", "/pages/messages/todos/index", "mobile-app:todos", "workbench-access", "待", "#1677ff", sort = 10),
    MobileAppEntry("tickets", "工单", "服务协同", "/pages/messages/tickets/index", "mobile-app:tickets", "workbench-access", "单", "#fa8c16", sort = 30),
    MobileAppEntry(
        key = "receiving",
        title = "仓库收货",
        description = "扫码入库",
        path = "/pages/receiving/index",
        permission = "mobile-app:receiving",
        businessPermission = "receiving_records",
        icon = "收",
        color = "#08979c",
        sort = 45,
        offlineSupported = true,
        offlineSyncable = true,
        offlineDataScope = "namespace",
        offlineRequiredPermissions = listOf("mobile-app:receiving", "receiving_records"),
    ),
    MobileAppEntry("profit", "利润", "利润看板", "/pages/profit/index", "mobile-app:profit", "", "润", "#52c41a", sort = 50),
    MobileAppEntry("product_management", "产品管理", "SKU 信息维护", "/pages/product/index", "mobile-app:product", "products", "产", "#1890ff", sort = 60),
    MobileAppEntry("box_labels", "箱唛管理", "箱唛与装箱", "/pages/carton/index", "mobile-app:carton", "box_labels", "箱", "#722ed1", sort = 70),
    MobileAppEntry("label_print", "标签打印", "字符与条码标签", "/pages/print/label", "mobile-app:product", "products", "签", "#52c41a", sort = 72),
    MobileAppEntry("edge_nodes", "边缘节点", "服务中心与局域网探测", "/pages/edge/nodes", "mobile-app:product", "products", "节", "#fa8c16", sort = 75),
    MobileAppEntry("pda_settings", "PDA 设置", "扫码诊断", "/pages/pda/settings", "", "", "扫", "#13a8a8", sort = 80),
)
