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
import android.util.Log
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val deviceIdentityManager: DeviceIdentityManager,
    @param:FscmJson private val json: Json,
) {
    val session = sessionStore.session

    suspend fun getLoginConfig(): LoginConfig =
        api.get("auth/login-config", auth = false)

    suspend fun login(
        username: String,
        password: String,
        captchaId: String = "",
        captchaAnswer: String = "",
    ) {
        val body = buildLoginRequestBody(
            username = username,
            password = password,
            device = json.encodeToJsonElement(deviceIdentityManager.registration()),
            captchaId = captchaId,
            captchaAnswer = captchaAnswer,
        )
        val payload = try {
            api.post<AuthPayload>(
                path = "auth/login",
                headers = deviceIdentityManager.proofHeaders(
                    method = "POST",
                    path = "auth/login",
                    body = body.toString(),
                    timestampMillis = System.currentTimeMillis(),
                ),
                body = body,
                auth = false,
            )
        } catch (error: FscmApiException) {
            val security = error.loginSecurityStateOrNull(json) ?: throw error
            throw LoginSecurityException(
                security = security,
                businessCode = error.payload.standardResponseCode(),
                serverMessage = error.serverMessage,
                cause = error,
            )
        }
        sessionStore.applyAuth(payload)
    }

    suspend fun refreshLoginCaptcha(username: String): LoginCaptcha =
        api.post(
            path = "auth/captcha",
            body = buildJsonObject { put("username", username) },
            auth = false,
        )

    suspend fun restoreLocalSession(): SessionState {
        val current = sessionStore.current()
        if (current.token.isNotBlank()) return current
        if (current.isOffline) {
            sessionStore.validateActiveOfflineSession()
        } else {
            sessionStore.enterOfflineMode()
        }
        return sessionStore.current()
    }

    suspend fun refreshStoredSession(): StoredSessionRefreshResult {
        val current = sessionStore.current()
        if (!shouldRefreshStoredSession(current)) return StoredSessionRefreshResult.NoSession
        return try {
            val payload = api.get<AuthPayload>("auth/me")
            sessionStore.applyAuth(payload, preserveNamespace = true)
            StoredSessionRefreshResult.Refreshed
        } catch (error: Throwable) {
            recoverStoredSessionAfterRefreshFailure(
                current = current,
                error = error,
                clearSession = sessionStore::clear,
                enterOfflineMode = sessionStore::enterOfflineMode,
            )
        }
    }

    suspend fun loginOrOffline(
        username: String,
        password: String,
        captchaId: String = "",
        captchaAnswer: String = "",
    ) {
        login(username, password, captchaId, captchaAnswer)
    }

    suspend fun switchNamespace(namespaceId: Long) {
        val response = api.put<SwitchNamespaceResponse>(
            "user/namespaces/switch",
            buildJsonObject { put("namespace_id", namespaceId) },
        )
        val matched = sessionStore.current().namespaces.firstOrNull { it.id == namespaceId }
        sessionStore.replaceNamespace(response.namespace ?: matched)
        refreshStoredSession()
    }

    suspend fun validateActiveOfflineSession(): Boolean = sessionStore.validateActiveOfflineSession()

    suspend fun logout() {
        runCatching { api.post<JsonObject>("auth/logout") }
        sessionStore.clear()
    }
}

enum class StoredSessionRefreshResult {
    Refreshed,
    Offline,
    Unavailable,
    Expired,
    NoSession,
}

internal fun shouldRefreshStoredSession(session: SessionState): Boolean =
    session.isLoggedIn && session.token.isNotBlank()

internal suspend fun recoverStoredSessionAfterRefreshFailure(
    current: SessionState,
    error: Throwable,
    clearSession: suspend () -> Unit,
    enterOfflineMode: suspend () -> Boolean,
): StoredSessionRefreshResult {
    if (error is CancellationException) throw error
    if (error is SessionExpiredException) {
        clearSession()
        return StoredSessionRefreshResult.Expired
    }
    if (current.isLoggedIn && current.hasValidOfflineSession && enterOfflineMode()) {
        return StoredSessionRefreshResult.Offline
    }
    return StoredSessionRefreshResult.Unavailable
}

class LoginSecurityException(
    val security: LoginSecurityState,
    val businessCode: Int?,
    val serverMessage: String?,
    cause: Throwable,
) : RuntimeException(serverMessage ?: cause.message, cause)

internal fun buildLoginRequestBody(
    username: String,
    password: String,
    device: JsonElement,
    captchaId: String = "",
    captchaAnswer: String = "",
): JsonObject = buildJsonObject {
    put("username", username)
    put("password", password)
    put("device", device)
    captchaId.takeIf(String::isNotBlank)?.let { put("captcha_id", it) }
    captchaAnswer.takeIf(String::isNotBlank)?.let { put("captcha_answer", it) }
}

internal fun FscmApiException.loginSecurityStateOrNull(json: Json): LoginSecurityState? {
    val data = (payload as? JsonObject)?.get("data") as? JsonObject ?: return null
    val securityFields = setOf(
        "failed_count",
        "require_captcha",
        "lock_remaining_seconds",
        "reason_code",
        "captcha",
    )
    if (data.keys.none(securityFields::contains)) return null
    return runCatching { json.decodeFromJsonElement<LoginSecurityState>(data) }.getOrNull()
}

@Singleton
class MobileRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    @param:FscmJson
    private val json: Json,
) {
    suspend fun cachedWorkbenchOverview(session: SessionState): MobileWorkbenchOverview? {
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        return cachedWorkbench(userId, namespaceId)
    }

    suspend fun refreshWorkbenchOverview(session: SessionState): MobileWorkbenchOverview {
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        val overview = api.get<MobileWorkbenchOverview>("mobile/app/workbench/overview")
        val activeSession = sessionStore.current()
        if (
            activeSession.user?.id == userId &&
            activeSession.currentNamespace?.id == namespaceId
        ) {
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    userId = userId,
                    namespaceId = namespaceId,
                    cacheKey = WORKBENCH_CACHE_KEY,
                    payload = json.encodeToString(overview),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return overview
    }

    private suspend fun cachedWorkbench(userId: Long, namespaceId: Long): MobileWorkbenchOverview? =
        offlineCacheDao.find(userId, namespaceId, WORKBENCH_CACHE_KEY)
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

    /** Searches only the mobile application entries available to the current user. */
    suspend fun searchApplications(
        query: String,
        overview: MobileWorkbenchOverview? = null,
    ): GlobalSearchResponse {
        val normalized = normalizeSearchQuery(query)
        if (normalized.isBlank()) return GlobalSearchResponse()

        val session = sessionStore.current()
        val hits = localSearchHits(normalized, searchableMobileEntries(overview, session))
        return GlobalSearchResponse(query = query.trim(), normalizedQuery = normalized, hits = hits)
    }

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
        cachedSearch(namespaceId, cacheKey, session, overview)?.let { return it }
        if (session.isOffline) throw IllegalStateException("离线状态下没有该搜索记录")

        val allowedTypes = loadBusinessTypes(namespaceId, session)
        val response = api.get<GlobalSearchResponse>(
            "mobile/app/search",
            mapOf("q" to query.trim(), "limit" to SEARCH_LIMIT.toString()),
        ).copy(query = query.trim(), normalizedQuery = normalized)
        val visibleAppKeys = searchableMobileEntries(overview, session).mapTo(mutableSetOf()) { it.key }
        val filtered = response.copy(
            hits = filterAllowedHits(response.hits, allowedTypes, session.permissions, visibleAppKeys),
        )
        if (filtered.hits.isNotEmpty()) {
            val now = System.currentTimeMillis()
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    userId = session.user?.id ?: 0,
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
    ): SearchDestination? {
        if (hit.businessType != BusinessTypeCode.LocalApp.wireValue) {
            return SearchDestination.Business(hit)
        }
        val session = sessionStore.current()
        val entry = searchableMobileEntries(overview, session)
            .firstOrNull { it.key == hit.targetCode }
            ?: return null
        return SearchDestination.LocalApp(entry)
    }

    private suspend fun cachedSearch(
        namespaceId: Long,
        cacheKey: String,
        session: SessionState,
        overview: MobileWorkbenchOverview?,
    ): GlobalSearchResponse? {
        val entity = offlineCacheDao.find(session.user?.id ?: 0, namespaceId, cacheKey) ?: return null
        if (System.currentTimeMillis() - entity.updatedAt > SEARCH_CACHE_TTL_MS) return null
        val payload = runCatching { json.decodeFromString<SearchCachePayload>(entity.payload) }.getOrNull() ?: return null
        val allowedTypes = loadBusinessTypes(namespaceId, session, allowNetwork = false)
        val visibleAppKeys = searchableMobileEntries(overview, session).mapTo(mutableSetOf()) { it.key }
        val hits = filterAllowedHits(payload.response.hits, allowedTypes, session.permissions, visibleAppKeys)
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
        val cached = offlineCacheDao.find(session.user?.id ?: 0, namespaceId, cacheKey)
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
                        userId = session.user?.id ?: 0,
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
        offlineCacheDao.deleteOlderThan(userId, namespaceId, prefix, now - SEARCH_CACHE_TTL_MS)
        offlineCacheDao.trimToNewest(userId, namespaceId, prefix, MAX_SEARCH_CACHE_ENTRIES)
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
    private val deviceIdentityManager: DeviceIdentityManager,
    private val trustedClock: TrustedClock,
    private val edgeServiceDiscovery: EdgeServiceDiscovery,
    private val edgeTicketProvider: EdgeTicketProvider,
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
    private val catalogMutex = Mutex()
    private var cachedCatalogNodes: List<EdgePrintNodeStatus> = emptyList()
    private var cachedCatalogNodesAt = 0L

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

    suspend fun availableBoxMarkOptions(): List<EdgePrintNodeOption> =
        printNodes().flatMap { status ->
            status.node.printInventory.templates
                .filter { template -> status.node.status == "online" && template.isAvailableBoxMarkTemplate() }
                .map { template -> EdgePrintNodeOption(status.node, template, status.localReachable) }
        }

    suspend fun registeredNodes(): List<EdgePrintNodeStatus> {
        if (sessionStore.current().isOffline) return discoverLocalNodes(includeSubnetScan = true)
        val nodes = registeredCenterNodes()
        return nodeStatuses(mergeWithLocalDiscovery(nodes))
    }

    suspend fun registeredCenterNodes(): List<EdgeNode> {
        if (sessionStore.current().isOffline) return emptyList()
        return api.get<PagedList<EdgeNode>>(
            "edge/nodes/available",
            edgeNodeListParams(includeOffline = true),
        ).rows()
    }

    suspend fun resolveBoxLabelFromEdge(labelCode: String): BoxLabel? {
        val normalized = labelCode.trim()
        if (normalized.isBlank()) return null
        val session = sessionStore.current()
        for (status in catalogNodes()) {
            val resolved = withContext(Dispatchers.IO) {
                if (session.offlineLease.isNotBlank() && "edge.cache.read" in session.offlineScopes) {
                    fetchLocalBoxLabelWithLease(status.node, normalized, session)
                } else {
                    val ticket = runCatching { catalogTicket(status.node) }.getOrNull() ?: return@withContext null
                    fetchLocalBoxLabel(status.node, normalized, ticket)
                }
            }
            if (resolved != null) return resolved
        }
        return null
    }

    private suspend fun catalogNodes(): List<EdgePrintNodeStatus> {
        val now = System.currentTimeMillis()
        catalogMutex.withLock {
            if (cachedCatalogNodes.isNotEmpty() && now - cachedCatalogNodesAt < CATALOG_NODE_CACHE_MS) {
                return cachedCatalogNodes
            }
        }
        val refreshed = runCatching { registeredNodes() }.getOrNull()
            ?.filter { status ->
                status.localReachable &&
                    status.node.lanBaseUrl.isNotBlank() &&
                    "box_label_catalog" in status.node.capabilities
            }
        return catalogMutex.withLock {
            if (refreshed != null) {
                cachedCatalogNodes = refreshed
                cachedCatalogNodesAt = now
            }
            cachedCatalogNodes
        }
    }

    private suspend fun catalogTicket(node: EdgeNode): String = catalogMutex.withLock {
        edgeTicketProvider.ticket(node.nodeId) ?: error("Edge ticket is unavailable")
    }

    private fun fetchLocalBoxLabel(node: EdgeNode, labelCode: String, ticket: String): BoxLabel? {
        val encoded = java.net.URLEncoder.encode(labelCode, "UTF-8")
        return localEdgeBaseUrlCandidates(node.lanBaseUrl).firstNotNullOfOrNull { baseUrl ->
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/box-labels/$encoded/resolve")
                    .header("X-Edge-Ticket", ticket)
                    .get()
                    .build()
                localClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val payload = json.parseToJsonElement(response.body?.string().orEmpty())
                    decodeBusinessObject<BoxLabel>(payload, json)
                }
            }.getOrNull()
        }
    }

    private fun fetchLocalBoxLabelWithLease(node: EdgeNode, labelCode: String, session: SessionState): BoxLabel? {
        val encoded = java.net.URLEncoder.encode(labelCode, "UTF-8")
        return localEdgeBaseUrlCandidates(node.lanBaseUrl).firstNotNullOfOrNull { baseUrl ->
            runCatching {
                val path = "/api/box-labels/$encoded/resolve"
                val requestBuilder = Request.Builder()
                    .url(baseUrl + path)
                    .header("X-FSCM-Offline-Lease", session.offlineLease)
                    .get()
                deviceIdentityManager.proofHeaders(
                    method = "GET",
                    path = path,
                    body = "",
                    timestampMillis = trustedClock.trustedNowMillis() ?: return@runCatching null,
                ).forEach(requestBuilder::header)
                localClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val payload = json.parseToJsonElement(response.body?.string().orEmpty())
                    decodeBusinessObject<BoxLabel>(payload, json)
                }
            }.getOrNull()
        }
    }

    suspend fun printNodes(): List<EdgePrintNodeStatus> {
        if (sessionStore.current().isOffline) return discoverLocalNodes(includeSubnetScan = true)
        val nodes = api.get<PagedList<EdgeNode>>(
            "edge/nodes/available",
            edgeNodeListParams(includeOffline = false),
        ).rows()
        return nodeStatuses(mergeWithLocalDiscovery(nodes))
    }

    /**
     * The center determines which nodes belong to this namespace. LAN discovery supplies the
     * address that is actually reachable by this device, including when the center address is stale.
     */
    private suspend fun mergeWithLocalDiscovery(centerNodes: List<EdgeNode>): List<EdgeNode> {
        val discovered = runCatching { discoverLocalNodes(includeSubnetScan = false) }
            .getOrElse { error ->
                Log.w(LOG_TAG, "Local edge discovery failed", error)
                emptyList()
            }
        if (discovered.isEmpty()) return centerNodes
        val discoveredById = discovered
            .mapNotNull { status -> status.node.nodeId.takeIf(String::isNotBlank)?.let { it to status.node } }
            .toMap()
        return centerNodes.map { center ->
            val local = discoveredById[center.nodeId] ?: return@map center
            center.copy(
                lanBaseUrl = local.lanBaseUrl,
                capabilities = local.capabilities.ifEmpty { center.capabilities },
            )
        }
    }

    private suspend fun nodeStatuses(nodes: List<EdgeNode>): List<EdgePrintNodeStatus> {
        return coroutineScope {
            nodes.map { node ->
                async(Dispatchers.IO) {
                    val localReachable = probe(node)
                    val localInventory = if (localReachable) fetchLocalPrintInventory(node.lanBaseUrl) else null
                    EdgePrintNodeStatus(
                        node = localInventory?.let { node.copy(printInventory = it) } ?: node,
                        localReachable = localReachable,
                    )
                }
            }.awaitAll()
        }.sortedWith(
            compareByDescending<EdgePrintNodeStatus> { it.localReachable }
                .thenByDescending { it.node.status == "online" }
                .thenByDescending { it.node.lastSeenAt },
        )
    }

    suspend fun discoverLocalNodes(
        port: Int = DEFAULT_EDGE_PORT,
        includeSubnetScan: Boolean = true,
    ): List<EdgePrintNodeStatus> {
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
        val advertisedNodes = edgeServiceDiscovery.discoverBaseUrls()
            .mapNotNull { baseUrl -> withContext(Dispatchers.IO) { discoverLocalNode(baseUrl) } }
            .distinctBy { it.node.nodeId.ifBlank { it.node.lanBaseUrl } }
        if (advertisedNodes.isNotEmpty() || !includeSubnetScan) {
            sessionStore.markEdgeReachable(advertisedNodes.isNotEmpty())
            return advertisedNodes.sortedBy { it.node.nodeName }
        }
        val prefixes = localSubnetPrefixes()
        check(prefixes.isNotEmpty()) { "未检测到可用的局域网 IPv4 地址" }
        val semaphore = Semaphore(DISCOVERY_CONCURRENCY)
        val scannedNodes = coroutineScope {
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
        sessionStore.markEdgeReachable(scannedNodes.isNotEmpty())
        return scannedNodes
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

    suspend fun printBoxMark(
        option: EdgePrintNodeOption,
        label: BoxLabel,
        copies: Int,
        idempotencyKey: String,
    ): EdgePrintSubmission {
        require(label.boxMarkPrintAvailability() == BoxMarkPrintAvailability.Ready) { "当前箱唛不可打印" }
        require(copies in 1..100) { "打印份数必须在 1 到 100 之间" }
        require(idempotencyKey.startsWith("mobile-box-mark-") && idempotencyKey.length <= 200) {
            "箱唛打印幂等键无效"
        }
        require(option.template.isAvailableBoxMarkTemplate()) { "厂家箱唛模板不可用" }

        val stillReachable = option.localReachable && withContext(Dispatchers.IO) { probe(option.node) }
        return if (stillReachable) {
            runCatching {
                submitLocalDirect(
                    option = option,
                    payload = buildLocalBoxMarkPrintRequest(option, label, copies, idempotencyKey),
                )
            }.getOrElse { error ->
                if (error is LocalDirectPrintUnavailableException) {
                    submitRemoteBoxMark(option, label, copies, idempotencyKey)
                } else {
                    throw error
                }
            }
        } else {
            submitRemoteBoxMark(option, label, copies, idempotencyKey)
        }
    }

    suspend fun isEdgeReachable(node: EdgeNode): Boolean = withContext(Dispatchers.IO) {
        if (node.lanBaseUrl.isBlank()) return@withContext false
        val payload = fetchProbe(node.lanBaseUrl, localClient) ?: return@withContext false
        payload.ok && payload.nodeId == node.nodeId
    }

    private fun probe(node: EdgeNode): Boolean {
        if (node.lanBaseUrl.isBlank()) return false
        val payload = fetchProbe(node.lanBaseUrl, localClient) ?: return false
        return payload.ok && payload.nodeId == node.nodeId && payload.directPrintAvailable != false
    }

    private fun fetchProbe(baseUrl: String, client: OkHttpClient): LocalEdgeProbe? {
        return localEdgeBaseUrlCandidates(baseUrl).firstNotNullOfOrNull { candidate ->
            try {
                val request = Request.Builder()
                    .url(candidate + "/edge/probe")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    json.decodeFromString<LocalEdgeProbe>(body).takeIf { it.ok }
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Edge probe failed for $candidate", error)
                null
            }
        }
    }

    private fun discoverLocalNode(baseUrl: String): EdgePrintNodeStatus? {
        val probe = fetchProbe(baseUrl, discoveryClient) ?: return null
        val inventory = fetchLocalPrintInventory(baseUrl) ?: EdgePrintInventory()
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

    private fun fetchLocalPrintInventory(baseUrl: String): EdgePrintInventory? {
        for (candidate in localEdgeBaseUrlCandidates(baseUrl)) {
            val current = runCatching {
                val request = Request.Builder().url(candidate + "/edge/print-inventory").get().build()
                localClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = json.decodeFromString<LocalEdgePrintInventoryResponse>(response.body?.string().orEmpty())
                    EdgePrintInventory(printers = body.printers, templates = body.templates)
                }
            }.getOrNull()
            if (current != null) return current

            val legacy = runCatching {
                val request = Request.Builder().url(candidate + "/edge/web/label-templates").get().build()
                localClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = json.decodeFromString<LegacyEdgeTemplateEnvelope>(response.body?.string().orEmpty())
                    legacyInventory(body.templates)
                }
            }.getOrNull()
            if (legacy != null) return legacy
        }
        return null
    }

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
    ): EdgePrintSubmission = submitLocalDirect(
        option = option,
        payload = buildLocalSkuPrintRequest(json, option, sku, copies, labelPrefix, idempotencyKey),
    )

    private suspend fun submitLocalDirect(
        option: EdgePrintNodeOption,
        payload: LocalEdgeDirectPrintRequest,
    ): EdgePrintSubmission = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        if (session.isOffline) require("edge.print" in session.offlineScopes) { "离线租约不允许边缘打印" }
        val path = "/edge/print-jobs/direct"
        val body = json.encodeToString(payload)
        val requestBuilder = Request.Builder()
            .url(localEdgeDirectBaseUrl(option.node.lanBaseUrl) + path)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        session.token.takeIf(String::isNotBlank)?.let { requestBuilder.header("Authorization", "Bearer $it") }
        session.offlineLease.takeIf(String::isNotBlank)?.let {
            requestBuilder.header("X-FSCM-Offline-Lease", it)
        }
        deviceIdentityManager.proofHeaders(
            method = "POST",
            path = path,
            body = body,
            timestampMillis = trustedClock.trustedNowMillis() ?: error("设备时间不可信，无法提交边缘打印"),
        ).forEach(requestBuilder::header)
        val request = requestBuilder.build()
        localClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (isDirectPrintRouteUnavailable(response.code)) {
                    throw LocalDirectPrintUnavailableException(response.code)
                }
                val error = runCatching { json.decodeFromString<LocalEdgePrintResponse>(body) }.getOrNull()
                if (isEdgeTemplateUnavailable(response.code, error?.code.orEmpty())) {
                    throw EdgePrintTemplateUnavailableException()
                }
                throw IllegalStateException(error?.error?.takeIf { it.isNotBlank() } ?: "本地节点打印失败 (${response.code})")
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
    ): EdgePrintSubmission = submitLocalDirect(
        option = option,
        payload = buildLocalLabelPrintRequest(json, option, content, copies, idempotencyKey),
    )

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

    private suspend fun submitRemoteBoxMark(
        option: EdgePrintNodeOption,
        label: BoxLabel,
        copies: Int,
        idempotencyKey: String,
    ): EdgePrintSubmission {
        val job = api.post<EdgePrintJob>(
            path = "edge/print-jobs/manufacturer-box-marks",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            body = buildJsonObject {
                put("edge_node_id", option.node.id)
                put("template_code", option.template.code)
                put("box_plan_ids", buildJsonArray { add(JsonPrimitive(label.id)) })
                put("copies", copies)
            },
        )
        return EdgePrintSubmission(
            nodeName = option.node.nodeName,
            local = false,
            status = job.status,
            jobId = job.id.toString(),
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_EDGE_PORT = 8089
        const val DISCOVERY_CONCURRENCY = 32
        const val MAX_DISCOVERY_SUBNETS = 2
        const val LOG_TAG = "FscmEdgeDiscovery"
        const val CATALOG_NODE_CACHE_MS = 60_000L
    }
}

@Singleton
class EdgeTerminalRepository @Inject constructor(
    private val api: FscmApiClient,
    private val edgePrintRepository: EdgePrintRepository,
    private val edgeTicketProvider: EdgeTicketProvider,
    private val sessionStore: SessionStore,
    @param:ApplicationContext private val context: Context,
    @param:FscmJson private val json: Json,
) {
    private val registrationMutex = Mutex()
    private var lastRegistrationKey = ""
    private var lastRegistrationAtMillis = 0L

    suspend fun registerCurrentTerminal() {
        maintainCenterPresence()
    }

    /** Keeps the central terminal record alive without requiring LAN edge connectivity. */
    suspend fun maintainCenterPresence() {
        val session = sessionStore.current()
        if (session.isOffline || session.token.isBlank() || session.currentNamespace == null) return

        val lanIp = currentLocalIPv4Addresses().firstOrNull().orEmpty()
        registrationMutex.withLock {
            val nodes = edgePrintRepository.registeredCenterNodes()
            val edgeNodeId = (
                nodes.firstOrNull { it.nodeId.isNotBlank() && it.status == "online" }
                    ?: nodes.firstOrNull { it.nodeId.isNotBlank() }
                )
                ?.nodeId
                ?.takeIf(String::isNotBlank)
                ?: error("No edge node is registered with the center for this namespace")
            val now = System.currentTimeMillis()
            val registrationKey = listOf(
                session.currentNamespace.id,
                terminalId(),
                lanIp.ifBlank { "unknown" },
                edgeNodeId,
            ).joinToString(":")
            val request = registration(edgeNodeId, lanIp)
            val needsRegistration = registrationKey != lastRegistrationKey ||
                now - lastRegistrationAtMillis >= REGISTRATION_INTERVAL_MS
            val path = if (needsRegistration) "edge/terminals/register" else "edge/terminals/heartbeat"
            api.post<Unit>(
                path = path,
                body = json.encodeToJsonElement(request),
            )
            if (needsRegistration) {
                lastRegistrationKey = registrationKey
                lastRegistrationAtMillis = now
            }
            Log.i(LOG_TAG, "Central terminal presence updated: path=$path edgeNodeId=$edgeNodeId")
        }
    }

    suspend fun connectionTarget(): EdgeTerminalConnectionTarget? {
        val session = sessionStore.current()
        if (!session.isLoggedIn || session.currentNamespace == null) return null
        val nodes = edgePrintRepository.registeredNodes()
            .filter { status ->
                status.node.nodeId.isNotBlank() &&
                    isRoutableEdgeBaseUrl(status.node.lanBaseUrl) &&
                    edgePrintRepository.isEdgeReachable(status.node)
            }
        val lastNodeId = preferences().getString(KEY_LAST_NODE_ID, null)
        val node = nodes.firstOrNull { it.node.nodeId == lastNodeId }?.node
            ?: nodes.firstOrNull()?.node
            ?: return null
        val ticket = edgeTicketProvider.ticket(node.nodeId) ?: return null
        preferences().edit().putString(KEY_LAST_NODE_ID, node.nodeId).apply()
        return EdgeTerminalConnectionTarget(
            nodeId = node.nodeId,
            nodeName = node.nodeName,
            baseUrl = localEdgeBaseUrlCandidates(node.lanBaseUrl).firstOrNull() ?: return null,
            ticket = ticket,
            registration = registration(node.nodeId, currentLocalIPv4Addresses().firstOrNull().orEmpty()),
        )
    }

    suspend fun register(target: EdgeTerminalConnectionTarget) {
        api.post<Unit>(
            path = "edge/terminals/register",
            body = json.encodeToJsonElement(target.registration),
        )
    }

    suspend fun heartbeat(target: EdgeTerminalConnectionTarget) {
        api.post<Unit>(
            path = "edge/terminals/heartbeat",
            body = json.encodeToJsonElement(target.registration),
        )
    }

    fun clearConnectionState() {
        preferences().edit().remove(KEY_LAST_NODE_ID).apply()
    }

    private fun registration(edgeNodeId: String, lanIp: String) = EdgeTerminalRegistrationRequest(
        terminalId = terminalId(),
        edgeNodeId = edgeNodeId,
        terminalName = terminalName(),
        appVersion = BuildConfig.VERSION_NAME,
        platform = "android",
        lanIp = lanIp,
        capabilities = TERMINAL_CAPABILITIES,
    )

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

    private fun preferences() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val LOG_TAG = "FscmEdgeTerminal"
        const val REGISTRATION_INTERVAL_MS = 60_000L
        const val PREFS_NAME = "fscm_edge_terminal"
        const val KEY_LAST_NODE_ID = "last_node_id"
        val TERMINAL_CAPABILITIES = listOf("mobile-app", "pda-scan", "edge-print-client", "find-device")
    }
}

data class EdgeTerminalConnectionTarget(
    val nodeId: String,
    val nodeName: String,
    val baseUrl: String,
    val ticket: String,
    val registration: EdgeTerminalRegistrationRequest,
)

@Serializable
private data class LocalEdgeProbe(
    val ok: Boolean = false,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("direct_print_available") val directPrintAvailable: Boolean? = null,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class LocalEdgePrintInventoryResponse(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val printers: List<EdgePrinter> = emptyList(),
    val templates: List<EdgePrintTemplate> = emptyList(),
)

@Serializable
private data class LegacyEdgeTemplateEnvelope(
    val templates: List<LegacyEdgePrintTemplate> = emptyList(),
)

@Serializable
private data class LegacyEdgePrintTemplate(
    @SerialName("Id") val code: String = "",
    @SerialName("TemplateNumber") val number: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "label",
    @SerialName("Printer") val printerName: String = "",
    @SerialName("WidthMillimeters") val widthMM: Double = 0.0,
    @SerialName("HeightMillimeters") val heightMM: Double = 0.0,
    @SerialName("Orientation") val orientation: String = "",
    @SerialName("LayoutStyle") val layoutStyle: String = "stacked",
    @SerialName("TextFontSizePoints") val textFontSizePoints: Double = 0.0,
    @SerialName("MaxDisplayLength") val maxDisplayLength: Int = 16,
    @SerialName("LabelQrPrefix") val labelQrPrefix: String = "",
)

@Serializable
internal data class LocalEdgeDirectPrintRequest(
    val source: String,
    @SerialName("template_code") val templateCode: String,
    val copies: Int,
    val type: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("payload_snapshot") val payloadSnapshot: JsonElement,
)

@Serializable
internal data class EdgeSkuLabelPayload(
    val type: String,
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
internal data class EdgeCustomLabelPayload(
    val type: String,
    @SerialName("label_content") val labelContent: String,
    val text: String,
    @SerialName("qr_payload") val qrPayload: String,
    @SerialName("template_code") val templateCode: String,
    @SerialName("template_version") val templateVersion: String,
)

@Serializable
private data class LocalEdgePrintJob(
    val id: String = "",
)

@Serializable
private data class LocalEdgePrintResponse(
    val status: String = "",
    val code: String = "",
    val job: LocalEdgePrintJob? = null,
    val error: String = "",
)

private class LocalDirectPrintUnavailableException(statusCode: Int) :
    IllegalStateException("本地直打接口不可用($statusCode)")

internal class EdgePrintTemplateUnavailableException :
    IllegalStateException("打印模板已更新，请重新选择打印节点后再试")

internal fun isEdgeTemplateUnavailable(statusCode: Int, errorCode: String): Boolean =
    statusCode == 409 && errorCode in setOf("LABEL_TEMPLATE_UNAVAILABLE", "LABEL_TEMPLATE_CHANGED")

internal fun isDirectPrintRouteUnavailable(statusCode: Int): Boolean =
    statusCode == 404 || statusCode == 405

internal fun localEdgeBaseUrlCandidates(baseUrl: String): List<String> {
    val normalized = baseUrl.trim().trimEnd('/')
    if (normalized.isBlank()) return emptyList()
    val withoutApiSuffix = normalized.removeSuffix("/api").trimEnd('/')
    return listOf(withoutApiSuffix, normalized).filter { it.isNotBlank() }.distinct()
}

internal fun isRoutableEdgeBaseUrl(baseUrl: String): Boolean {
    val candidate = localEdgeBaseUrlCandidates(baseUrl).firstOrNull() ?: return false
    val host = runCatching { java.net.URI(candidate).host }.getOrNull()?.lowercase() ?: return false
    return host !in setOf("localhost", "127.0.0.1", "::1")
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

internal fun EdgePrintTemplate.isAvailableBoxMarkTemplate(): Boolean =
    type.equals("manufacturer_box_mark", ignoreCase = true) &&
        available &&
        printerName.isNotBlank() &&
        kotlin.math.abs(widthMM - 100.0) < 0.01 &&
        kotlin.math.abs(heightMM - 150.0) < 0.01 &&
        orientation.equals("portrait", ignoreCase = true)

internal fun buildLabelCode(prefix: String, skuCode: String): String =
    prefix.trim() + skuCode

internal fun buildLocalSkuPrintRequest(
    json: Json,
    option: EdgePrintNodeOption,
    sku: Sku,
    copies: Int,
    labelPrefix: String,
    idempotencyKey: String,
): LocalEdgeDirectPrintRequest = LocalEdgeDirectPrintRequest(
    source = "mobile-app",
    templateCode = option.template.code,
    copies = copies,
    type = "sku_label",
    idempotencyKey = idempotencyKey,
    payloadSnapshot = json.encodeToJsonElement(
        EdgeSkuLabelPayload(
            type = "sku_label",
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
    ),
)

internal fun buildLocalLabelPrintRequest(
    json: Json,
    option: EdgePrintNodeOption,
    content: String,
    copies: Int,
    idempotencyKey: String,
): LocalEdgeDirectPrintRequest = LocalEdgeDirectPrintRequest(
    source = "mobile-app",
    templateCode = option.template.code,
    copies = copies,
    type = "custom_label",
    idempotencyKey = idempotencyKey,
    payloadSnapshot = json.encodeToJsonElement(
        EdgeCustomLabelPayload(
            type = "custom_label",
            labelContent = content,
            text = content,
            qrPayload = option.template.labelQrPrefix + content,
            templateCode = option.template.code,
            templateVersion = option.template.version,
        ),
    ),
)

private fun legacyInventory(templates: List<LegacyEdgePrintTemplate>): EdgePrintInventory {
    val mapped = templates.map { template ->
        EdgePrintTemplate(
            type = template.type,
            code = template.code,
            number = template.number,
            name = template.name,
            printerName = template.printerName,
            widthMM = template.widthMM,
            heightMM = template.heightMM,
            orientation = template.orientation,
            layoutStyle = template.layoutStyle,
            textFontSizePoints = template.textFontSizePoints,
            maxDisplayLength = template.maxDisplayLength,
            labelQrPrefix = template.labelQrPrefix,
            available = template.printerName.isNotBlank(),
        )
    }
    return EdgePrintInventory(
        printers = mapped.map { it.printerName }
            .filter(String::isNotBlank)
            .distinct()
            .map { EdgePrinter(name = it, status = "ready") },
        templates = mapped,
    )
}

internal fun buildLocalBoxMarkPrintRequest(
    option: EdgePrintNodeOption,
    label: BoxLabel,
    copies: Int,
    idempotencyKey: String,
): LocalEdgeDirectPrintRequest = LocalEdgeDirectPrintRequest(
    source = "mobile-app",
    templateCode = option.template.code,
    copies = copies,
    type = "manufacturer_box_mark",
    idempotencyKey = idempotencyKey,
    payloadSnapshot = buildJsonObject {
        put("kind", "manufacturer_box_mark")
        put("document_version", "manufacturer_box_mark.v1")
        put("paper_width_mm", 100)
        put("paper_height_mm", 150)
        put("orientation", "portrait")
        put("items", JsonArray(listOf(requireNotNull(label.printSnapshot))))
    },
)

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
): List<MobileAppEntry> = visibleMobileApps(overview, session.permissions, session.sessionMode)

private fun filterAllowedHits(
    hits: List<GlobalSearchHit>,
    businessTypes: List<SearchBusinessType>,
    permissions: List<String>,
    visibleAppKeys: Set<String>,
): List<GlobalSearchHit> {
    val enabledCodes = businessTypes.filter { it.enabled }.mapTo(mutableSetOf()) { it.code }
    return hits.filter { hit ->
        hit.businessType in enabledCodes && when (hit.businessType.toBusinessTypeCode()) {
            BusinessTypeCode.SkuDetail -> permissions.any { it in setOf("skus", "products", "mobile-app:product") }
            BusinessTypeCode.BoxLabelDetail -> permissions.any { it in setOf("mobile-app:box-labels", "mobile-app:carton", "box_labels") }
            BusinessTypeCode.LocalApp -> hit.targetCode in visibleAppKeys
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

private val localSearchAliases = mapOf(
    "todos" to listOf("待办", "待处理", "任务"),
    "notes" to listOf("笔记", "工作记录"),
    "tickets" to listOf("工单", "服务协同"),
    "documents" to listOf("文档", "文档中心"),
    "profit" to listOf("利润", "利润看板"),
    "receiving" to listOf("收货", "扫码入库", "仓库收货"),
    "product_management" to listOf("产品", "SKU", "SKU 管理"),
    "box_labels" to listOf("箱唛", "箱唛管理"),
    "carton_management" to listOf("箱唛", "箱唛管理"),
    "supplier_orders" to listOf("厂家订单", "供应商订单"),
    "purchase_approvals" to listOf("采购审核", "采购审批"),
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
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
) {
    suspend fun sessions(): List<ReceivingSession> {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        if (session.isOffline) return cachedSessions(userId, namespaceId)
        val rows = runCatching {
            decodeReceivingSessionsPayload(
                payload = api.get("receiving-sessions", mapOf("page" to "1", "pageSize" to "50")),
                json = json,
            )
        }.getOrElse { error ->
            cachedSessions(userId, namespaceId).takeIf { it.isNotEmpty() && error !is FscmApiException } ?: throw error
        }
        val now = System.currentTimeMillis()
        receivingDao.upsertSessions(rows.map { it.toEntity(userId, namespaceId, json, now) })
        return rows.map { it.copy(localCached = true, localSyncedAt = now) }
    }

    suspend fun session(id: Long, forceRefresh: Boolean = false): ReceivingSession {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        val cached = cachedSession(userId, namespaceId, id)
        if (session.isOffline) return cached ?: ReceivingSession(id = id)
        if (!forceRefresh && cached != null && cached.isRemoteFresh()) return cached
        val row = runCatching {
            val payload: JsonElement = api.get<JsonElement>("receiving-sessions/$id")
            decodeBusinessObject<ReceivingSession>(payload, json)
        }.getOrElse { error ->
            cached.takeIf { error !is FscmApiException } ?: throw error
        }
        val now = System.currentTimeMillis()
        receivingDao.upsertSession(row.toEntity(userId, namespaceId, json, now))
        return row.copy(localCached = true, localSyncedAt = now)
    }

    suspend fun refreshCachedSession(id: Long): ReceivingSession = session(id, forceRefresh = true)

    suspend fun sync(id: Long): ReceivingSession {
        check(!sessionStore.current().isOffline) { "Network connection is unavailable" }
        offlineSyncRepository.syncReceivingSession(id)
        return session(id, forceRefresh = true)
    }

    suspend fun preview(id: Long, rawCode: String): ReceivingScanResponse {
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        if (sessionStore.current().isOffline) {
            return offlinePreview(parsed ?: throw BoxLabelMappingCacheMissException())
        }
        return runCatching {
            api.post<ReceivingScanResponse>(
                "receiving-sessions/$id/scan-preview",
                buildJsonObject { put("raw_code", rawCode.trim()) },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            offlinePreview(parsed ?: throw BoxLabelMappingCacheMissException())
        }
    }

    suspend fun quickScan(rawCode: String, actualReceivedQty: Int? = null): ReceivingQuickScanResponse {
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        if (sessionStore.current().isOffline) {
            return enqueueOfflineQuickScan(parsed ?: throw BoxLabelMappingCacheMissException(), actualReceivedQty)
        }
        return runCatching {
            api.post<ReceivingQuickScanResponse>(
                "receiving-sessions/scan",
                buildJsonObject {
                    put("raw_code", rawCode.trim())
                    actualReceivedQty?.let { put("actual_received_qty", it) }
                },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineQuickScan(parsed ?: throw BoxLabelMappingCacheMissException(), actualReceivedQty)
        }
    }

    suspend fun pendingQuickScans(): List<ReceivingQuickScanResponse> =
        offlineSyncRepository.pendingOperations("receiving")
            .filter { it.action == "quick_scan" || it.action == "scan" }
            .mapNotNull { operation ->
                runCatching {
                    val payload = if (operation.action == "quick_scan") {
                        val quick = json.decodeFromString<ReceivingQuickScanOperationPayload>(operation.payload)
                        PendingReceivingPayload(
                            rawCode = quick.rawCode,
                            originalRawCode = quick.originalRawCode,
                            items = quick.items,
                            actualReceivedQty = quick.actualReceivedQty,
                        )
                    } else {
                        val detail = json.decodeFromString<ReceivingScanOperationPayload>(operation.payload)
                        PendingReceivingPayload(
                            rawCode = detail.rawCode,
                            originalRawCode = detail.originalRawCode,
                            items = detail.items,
                            actualReceivedQty = detail.actualReceivedQty,
                            sessionId = detail.sessionId,
                        )
                    }
                    val parsed = runCatching { parseBoxLabelQr(payload.originalRawCode) }.getOrElse {
                        BoxLabelQrPayload(payload.rawCode, payload.rawCode, payload.items, BoxLabelQrFormat.ShortCode)
                    }
                    ReceivingQuickScanResponse(
                        data = parsed.toLocalReceivingScanItem(
                            actualReceivedQty = payload.actualReceivedQty ?: parsed.totalQuantity,
                            status = operation.status,
                        ),
                        message = operation.lastError.ifBlank {
                            if (operation.status == PendingSyncStatus.Failed.value) "同步失败" else "待联网同步"
                        },
                        receivingSessionId = payload.sessionId,
                        parsedPayload = parsed,
                        pendingOperationId = operation.id,
                        syncStatus = operation.status,
                        localFallback = true,
                    )
                }.getOrNull()
            }

    suspend fun retryPending(id: Long) {
        offlineSyncRepository.retryPending(id)
        offlineSyncScheduler.enqueue()
    }

    suspend fun cancelPending(id: Long) = offlineSyncRepository.cancelPending(id)

    suspend fun previewQuickScan(rawCode: String): ReceivingQuickScanResponse {
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        if (sessionStore.current().isOffline) {
            val local = parsed ?: throw BoxLabelMappingCacheMissException()
            return ReceivingQuickScanResponse(
                data = local.toLocalReceivingScanItem(status = "pending_sync"),
                message = "已离线识别，确认后保存待同步",
                parsedPayload = local,
                localFallback = true,
            )
        }
        return runCatching {
            api.post<ReceivingQuickScanResponse>(
                "receiving-sessions/scan-preview",
                buildJsonObject { put("raw_code", rawCode.trim()) },
            ).copy(parsedPayload = parsed)
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            val local = parsed ?: throw BoxLabelMappingCacheMissException()
            ReceivingQuickScanResponse(
                data = local.toLocalReceivingScanItem(status = "pending_sync"),
                message = "网络不可用，确认后保存待同步",
                parsedPayload = local,
                localFallback = true,
            )
        }
    }

    suspend fun scan(
        id: Long,
        rawCode: String,
        damaged: Boolean,
        actualReceivedQty: Int? = null,
    ): ReceivingScanResponse {
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        if (sessionStore.current().isOffline) {
            return enqueueOfflineScan(id, parsed ?: throw BoxLabelMappingCacheMissException(), damaged, actualReceivedQty)
        }
        var cachedByOfflineFallback = false
        val response = runCatching {
            api.post<ReceivingScanResponse>(
                "receiving-sessions/$id/scan",
                buildJsonObject {
                    put("raw_code", rawCode.trim())
                    put("damaged", damaged)
                    put("tally_count", true)
                    if (actualReceivedQty != null) put("actual_received_qty", actualReceivedQty)
                },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            cachedByOfflineFallback = true
            enqueueOfflineScan(id, parsed ?: throw BoxLabelMappingCacheMissException(), damaged, actualReceivedQty)
        }
        if (!cachedByOfflineFallback && !response.isDuplicateReceipt()) {
            runCatching {
                updateCachedSession(sessionStore.namespaceId(), id) { session ->
                    session.withReceivedScan(response.data, damaged)
                }
            }
        }
        return response
    }

    suspend fun voidScan(id: Long, item: ReceivingScanItem) {
        if (item.id < 0) {
            check(offlineSyncRepository.cancelReceivingScan(id, item.labelCode)) { "待同步扫描已开始处理，无法取消" }
            val namespaceId = sessionStore.namespaceId()
            updateCachedSession(namespaceId, id) { session -> session.withoutPendingScan(item) }
            return
        }
        if (sessionStore.current().isOffline) {
            enqueueOfflineVoid(id, item.id)
            return
        }
        runCatching {
            api.post<JsonObject>(
                "receiving-sessions/$id/scan-items/${item.id}/void",
                buildJsonObject { put("reason", "移动端取消扫描") },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineVoid(id, item.id)
        }
        runCatching {
            updateCachedSession(sessionStore.namespaceId(), id) { session -> session.withoutPendingScan(item) }
        }
    }

    suspend fun complete(id: Long): ReceivingSession {
        if (sessionStore.current().isOffline) {
            return enqueueOfflineComplete(id)
        }
        val completed = runCatching {
            api.post<JsonObject>("receiving-sessions/$id/receiving-record")
            updateCachedSession(sessionStore.namespaceId(), id) { it.copy(status = "completed") }
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineComplete(id)
        }
        if (completed.status == "complete_pending_sync") return completed
        return runCatching { session(id, forceRefresh = true) }.getOrDefault(completed)
    }

    private suspend fun enqueueOfflineScan(
        id: Long,
        parsed: BoxLabelQrPayload,
        damaged: Boolean,
        actualReceivedQty: Int?,
    ): ReceivingScanResponse {
        val namespaceId = sessionStore.namespaceId()
        val localItem = parsed.toLocalReceivingScanItem(
            id = -System.currentTimeMillis(),
            actualReceivedQty = actualReceivedQty ?: parsed.totalQuantity.takeIf { it > 0 } ?: 0,
            status = if (damaged) "pending_exception" else "pending_sync",
        )
        var duplicate = false
        updateCachedSession(namespaceId, id) { session ->
            session.withReceivedScan(localItem, damaged).also { updated -> duplicate = updated === session }
        }
        if (!duplicate) {
            offlineSyncRepository.enqueueReceivingScan(
                ReceivingScanOperationPayload(
                    sessionId = id,
                    rawCode = parsed.labelCode,
                    damaged = damaged,
                    actualReceivedQty = actualReceivedQty,
                    originalRawCode = parsed.originalRawCode,
                    items = parsed.items,
                ),
            )
        }
        return ReceivingScanResponse(data = localItem, duplicate = duplicate)
    }

    private suspend fun enqueueOfflineQuickScan(
        parsed: BoxLabelQrPayload,
        actualReceivedQty: Int?,
    ): ReceivingQuickScanResponse {
        val inserted = offlineSyncRepository.enqueueReceivingQuickScan(
            ReceivingQuickScanOperationPayload(
                rawCode = parsed.labelCode,
                actualReceivedQty = actualReceivedQty,
                originalRawCode = parsed.originalRawCode,
                items = parsed.items,
            ),
        )
        return ReceivingQuickScanResponse(
            data = parsed.toLocalReceivingScanItem(
                id = -System.currentTimeMillis(),
                actualReceivedQty = actualReceivedQty ?: parsed.totalQuantity.takeIf { it > 0 } ?: 0,
                status = "pending_sync",
            ),
            duplicate = !inserted,
            message = if (inserted) "已保存本地，联网后同步" else "本地重复，已在待同步队列",
            parsedPayload = parsed,
            localFallback = true,
        )
    }

    private fun offlinePreview(parsed: BoxLabelQrPayload) = ReceivingScanResponse(
        data = parsed.toLocalReceivingScanItem(
            id = -System.currentTimeMillis(),
            status = "pending_sync",
            exceptionReason = "当前离线，可确认后保存待同步",
        ),
    )

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
        }
    }

    private suspend fun enqueueOfflineComplete(id: Long): ReceivingSession {
        val namespaceId = sessionStore.namespaceId()
        val updated = updateCachedSession(namespaceId, id) { it.copy(status = "complete_pending_sync") }
        offlineSyncRepository.enqueueReceivingComplete(ReceivingCompleteOperationPayload(id))
        return updated
    }

    private suspend fun updateCachedSession(
        namespaceId: Long,
        id: Long,
        transform: (ReceivingSession) -> ReceivingSession,
    ): ReceivingSession {
        val userId = sessionStore.current().user?.id ?: 0
        val existing = receivingDao.session(userId, namespaceId, id)
        val current = existing?.toModel(json) ?: ReceivingSession(id = id)
        val remoteSyncedAt = existing?.remoteSyncedAt ?: System.currentTimeMillis()
        val updated = transform(current)
        receivingDao.upsertSession(
            updated.toEntity(userId, namespaceId, json, remoteSyncedAt),
        )
        return updated.copy(localCached = true, localSyncedAt = remoteSyncedAt)
    }

    private suspend fun cachedSessions(userId: Long, namespaceId: Long): List<ReceivingSession> =
        receivingDao.sessions(userId, namespaceId).mapNotNull { entity ->
            entity.toModel(json)?.copy(
                localCached = true,
                localSyncedAt = entity.remoteSyncedAt,
            )
        }

    private suspend fun cachedSession(userId: Long, namespaceId: Long, id: Long): ReceivingSession? =
        receivingDao.session(userId, namespaceId, id)?.let { entity ->
            entity.toModel(json)?.copy(
                localCached = true,
                localSyncedAt = entity.remoteSyncedAt,
            )
        }
}

internal fun ReceivingSession.isRemoteFresh(now: Long = System.currentTimeMillis()): Boolean =
    localSyncedAt > 0 && now - localSyncedAt < RECEIVING_DETAIL_MAX_AGE_MILLIS

internal fun ReceivingSession.isReceivingComplete(): Boolean =
    status.lowercase() in setOf("completed", "complete_pending_sync") ||
        (
            expectedBoxCount > 0 &&
                maxOf(scannedBoxCount.coerceAtLeast(0), talliedBoxCount.coerceAtLeast(0)) >= expectedBoxCount
            )

private const val RECEIVING_DETAIL_MAX_AGE_MILLIS = 12 * 60 * 60 * 1000L

internal fun ReceivingScanResponse.isDuplicateReceipt(): Boolean =
    duplicate || data.status.lowercase() in setOf("duplicate", "duplicate_received", "already_received")

internal fun Throwable.isDuplicateReceivingFailure(): Boolean {
    val apiError = this as? FscmApiException ?: return false
    val message = listOfNotNull(apiError.serverMessage, apiError.message).joinToString(" ").lowercase()
    return apiError.statusCode == 409 ||
        listOf("已收货", "请勿重复", "already received", "duplicate").any(message::contains)
}

internal fun ReceivingSession.withReceivedScan(
    item: ReceivingScanItem,
    damaged: Boolean,
): ReceivingSession {
    val alreadyRecorded = scanItems.any { existing ->
        (item.id != 0L && existing.id == item.id) ||
            (item.labelCode.isNotBlank() && existing.labelCode.equals(item.labelCode, ignoreCase = true)) ||
            (item.boxUid.isNotBlank() && existing.boxUid.equals(item.boxUid, ignoreCase = true))
    }
    if (alreadyRecorded) return this
    val scanned = scannedBoxCount.coerceAtLeast(0) + 1
    val tallied = talliedBoxCount.coerceAtLeast(0) + 1
    val completed = maxOf(scanned, tallied)
    return copy(
        scanItems = listOf(item) + scanItems,
        scannedBoxCount = scanned,
        talliedBoxCount = tallied,
        pendingBoxCount = expectedBoxCount.takeIf { it > 0 }
            ?.let { (it - completed).coerceAtLeast(0) }
            ?: (pendingBoxCount - 1).coerceAtLeast(0),
        exceptionBoxCount = exceptionBoxCount.coerceAtLeast(0) + if (damaged) 1 else 0,
    )
}

internal fun ReceivingSession.withoutPendingScan(item: ReceivingScanItem): ReceivingSession = copy(
    scanItems = scanItems.filterNot { it.id == item.id },
    scannedBoxCount = (scannedBoxCount - 1).coerceAtLeast(0),
    talliedBoxCount = (talliedBoxCount - 1).coerceAtLeast(0),
    pendingBoxCount = (pendingBoxCount + 1).let { pending ->
        expectedBoxCount.takeIf { it > 0 }?.let { pending.coerceAtMost(it) } ?: pending
    },
    exceptionBoxCount = (exceptionBoxCount - if (item.status == "pending_exception") 1 else 0).coerceAtLeast(0),
)

@Singleton
class ProductRepository @Inject constructor(
    private val api: FscmApiClient,
    private val edgeCatalogClient: EdgeCatalogClient,
    private val sessionStore: SessionStore,
    @param:FscmJson private val json: Json,
) {
    suspend fun products(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
    ): PagedList<Product> = productsResult(keyword, page, pageSize).data

    suspend fun productsResult(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
    ): CatalogResult<PagedList<Product>> {
        val params = mapOf(
            "keyword" to normalizeSkuSearchQuery(keyword),
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        )
        val edge = runCatching { edgeCatalogClient.get("products", params)?.toProductPageResult() }
            .getOrElse { error -> if (error is FscmApiException) throw error else null }
        if (edge != null && !edge.isStale) return edge
        if (sessionStore.current().isOffline) {
            return edge ?: throw IllegalStateException("离线状态下没有可用的产品目录节点")
        }
        return runCatching {
            CatalogResult<PagedList<Product>>(
                data = api.get("products", params),
                source = CatalogSource.Center,
            )
        }.getOrElse { error -> edge.takeIf { error !is FscmApiException } ?: throw error }
    }

    suspend fun skus(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
        exactMatch: Boolean = false,
    ): PagedList<Sku> = skusResult(keyword, page, pageSize, exactMatch).data

    suspend fun skusResult(
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 20,
        exactMatch: Boolean = false,
    ): CatalogResult<PagedList<Sku>> {
        val params = mutableMapOf(
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
            "match_mode" to if (exactMatch) "exact" else "prefix",
        )
        if (keyword.startsWith("productId:")) {
            params["product_id"] = keyword.removePrefix("productId:")
        } else {
            params["keyword"] = normalizeSkuSearchQuery(keyword)
        }
        val edge = runCatching { edgeCatalogClient.get("skus", params)?.toSkuPageResult() }
            .getOrElse { error -> if (error is FscmApiException) throw error else null }
        if (edge != null && !edge.isStale) return edge
        if (sessionStore.current().isOffline) {
            return edge ?: throw IllegalStateException("离线状态下没有可用的 SKU 目录节点")
        }
        return runCatching {
            CatalogResult<PagedList<Sku>>(
                data = api.get("skus", params),
                source = CatalogSource.Center,
            )
        }.getOrElse { error -> edge.takeIf { error !is FscmApiException } ?: throw error }
    }

    suspend fun sku(idOrCode: String): Sku = skuResult(idOrCode).data

    suspend fun skuResult(idOrCode: String): CatalogResult<Sku> {
        val encoded = java.net.URLEncoder.encode(idOrCode.trim(), "UTF-8")
        val edge = runCatching { edgeCatalogClient.get("skus/$encoded")?.toSkuResult() }
            .getOrElse { error -> if (error is FscmApiException) throw error else null }
        if (edge != null && !edge.isStale) return edge
        if (sessionStore.current().isOffline) {
            return edge ?: throw IllegalStateException("离线状态下没有可用的 SKU 目录节点")
        }
        return runCatching {
            val payload: JsonElement = api.get("skus/$encoded")
            CatalogResult<Sku>(
                data = decodeBusinessObject<Sku>(payload, json),
                source = CatalogSource.Center,
            )
        }.getOrElse { error -> edge.takeIf { error !is FscmApiException } ?: throw error }
    }

    private fun EdgeCatalogResponse.toProductPageResult(): CatalogResult<PagedList<Product>> {
        val page = decodeEdgeCatalogPage<Product>(payload, json)
        val enriched = page.copy(
            data = page.data.map { it.withEdgeMedia(this) },
            items = page.items.map { it.withEdgeMedia(this) },
            list = page.list.map { it.withEdgeMedia(this) },
            records = page.records.map { it.withEdgeMedia(this) },
        )
        return enriched.toCatalogResult(this)
    }

    private fun EdgeCatalogResponse.toSkuPageResult(): CatalogResult<PagedList<Sku>> {
        val page = decodeEdgeCatalogPage<Sku>(payload, json)
        val enriched = page.copy(
            data = page.data.map { it.withEdgeMedia(this) },
            items = page.items.map { it.withEdgeMedia(this) },
            list = page.list.map { it.withEdgeMedia(this) },
            records = page.records.map { it.withEdgeMedia(this) },
        )
        return enriched.toCatalogResult(this)
    }

    private fun EdgeCatalogResponse.toSkuResult(): CatalogResult<Sku> =
        json.decodeFromJsonElement<Sku>(payload).withEdgeMedia(this).toCatalogResult(this)
}

internal fun normalizeSkuSearchQuery(query: String): String =
    query.trim().uppercase(Locale.ROOT).let { normalized ->
        if (normalized.firstOrNull() in SKU_SEARCH_PREFIXES) normalized.drop(1) else normalized
    }

private val SKU_SEARCH_PREFIXES = setOf('T', 'P', 'M')

@Singleton
class CartonRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val edgeCatalogClient: EdgeCatalogClient,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
    @param:FscmJson private val json: Json,
) {
    suspend fun resolveBoxLabel(rawCode: String): BoxLabel = resolveBoxLabelResult(rawCode).data

    suspend fun resolveBoxLabelResult(rawCode: String): CatalogResult<BoxLabel> {
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        val cachedMapping = boxLabelMappingRepository.cachedMapping(rawCode)
        val local = cachedMapping?.toCachedBoxLabel(rawCode) ?: parsed?.toLocalBoxLabel()
        val edge = runCatching {
            edgeCatalogClient.post(
                path = "box-labels/resolve",
                body = buildJsonObject {
                    put("raw_code", rawCode.trim())
                    parsed?.labelCode?.takeIf(String::isNotBlank)?.let { put("label_code", it) }
                },
            )?.let { response ->
                decodeBusinessObject<BoxLabel>(response.payload, json)
                    .mergeLocalResolution(parsed, cachedMapping, rawCode)
                    .toCatalogResult(response)
            }
        }.getOrElse { error -> if (error is FscmApiException) throw error else null }
        if (edge != null && shouldUseEdgeBoxLabelWithoutCenter(edge)) return edge
        if (sessionStore.current().isOffline) {
            return edge ?: local?.let {
                CatalogResult(it, CatalogSource.Offline, isStale = true)
            } ?: throw BoxLabelMappingCacheMissException()
        }
        return runCatching {
            val payload: JsonElement = api.post(
                "box-labels/resolve",
                buildJsonObject { put("raw_code", rawCode.trim()) },
            )
            val resolved = decodeBusinessObject<BoxLabel>(payload, json)
            CatalogResult(
                data = resolved.mergeLocalResolution(parsed, cachedMapping, rawCode),
                source = CatalogSource.Center,
            )
        }.getOrElse { error ->
            if (error is FscmApiException && !error.isTemporaryCenterFailure()) throw error
            edge?.copy(
                data = edge.data.copy(
                    printable = false,
                    printSnapshot = null,
                    localFallback = true,
                ),
                isStale = true,
            ) ?: local?.let {
                CatalogResult(it, CatalogSource.Offline, isStale = true)
            } ?: throw BoxLabelMappingCacheMissException()
        }
    }
}

internal fun shouldUseEdgeBoxLabelWithoutCenter(result: CatalogResult<BoxLabel>): Boolean =
    !result.isStale && (!result.data.recognized || result.data.hasCompleteBoxMarkPrintContract())

internal fun FscmApiException.isTemporaryCenterFailure(): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode >= 500

private fun <T> T.toCatalogResult(response: EdgeCatalogResponse): CatalogResult<T> = CatalogResult(
    data = this,
    source = if (response.servedByEdge) CatalogSource.Edge else CatalogSource.Center,
    nodeId = response.node.nodeId.takeIf { response.servedByEdge }.orEmpty(),
    nodeName = response.node.nodeName.takeIf { response.servedByEdge }.orEmpty(),
    isStale = response.servedByEdge && response.node.isStale,
    syncedAt = response.node.syncedAt.takeIf { response.servedByEdge }.orEmpty(),
)

internal inline fun <reified T> decodeEdgeCatalogPage(payload: JsonElement, json: Json): PagedList<T> =
    if (payload is JsonArray) {
        val rows = json.decodeFromJsonElement<List<T>>(payload)
        PagedList(data = rows, total = rows.size.toLong())
    } else {
        json.decodeFromJsonElement(payload)
    }

private fun Product.withEdgeMedia(response: EdgeCatalogResponse): Product = copy(
    catalogMedia = response.mediaRequest(media, thumbnailUrl.ifBlank { imageUrl }),
    skus = skus.map { it.withEdgeMedia(response) },
)

private fun Sku.withEdgeMedia(response: EdgeCatalogResponse): Sku = copy(
    catalogMedia = response.mediaRequest(
        media,
        thumbnailUrl.ifBlank { imageUrl }.ifBlank { product?.thumbnailUrl.orEmpty() },
    ),
    product = product?.withEdgeMedia(response),
)

internal fun EdgeCatalogResponse.mediaRequest(media: MediaRef?, legacyUrl: String): CatalogMediaRequest? {
    val path = media?.thumbnailPath.orEmpty().ifBlank { media?.originalPath.orEmpty() }
    if (path.isBlank()) return null
    val base = node.baseUrl.trimEnd('/') + "/"
    val url = runCatching { URI(base).resolve(path.trimStart('/')).toString() }.getOrNull() ?: return null
    val header = authorization.entries.firstOrNull() ?: return null
    val identity = media?.id.orEmpty().ifBlank { path }
    val version = media?.version.orEmpty().ifBlank { "current" }
    return CatalogMediaRequest(
        url = url,
        centralUrl = media?.centralUrl.orEmpty().ifBlank { legacyUrl },
        cacheKey = "catalog:$identity:$version:thumbnail",
        headerName = header.key,
        headerValue = header.value,
    )
}

private fun BoxLabelQrPayload.toLocalReceivingScanItem(
    id: Long = -System.currentTimeMillis(),
    actualReceivedQty: Int = totalQuantity.takeIf { it > 0 } ?: 0,
    status: String,
    exceptionReason: String = "",
) = ReceivingScanItem(
    id = id,
    rawCode = originalRawCode,
    labelCode = labelCode,
    boxNo = labelCode,
    skuCode = items.joinToString(",") { it.skuCode },
    boxQty = totalQuantity,
    qtyPerCarton = totalQuantity,
    actualReceivedQty = actualReceivedQty,
    status = status,
    exceptionReason = exceptionReason,
    localFallback = true,
)

internal fun BoxLabelQrPayload.toLocalBoxLabel() = BoxLabel(
    recognized = true,
    labelCode = labelCode,
    boxNo = labelCode,
    status = "offline_parsed",
    skuCode = items.joinToString(",") { it.skuCode },
    qtyPerCarton = totalQuantity,
    skuItems = items.map { BoxLabelSKUItem(skuCode = it.skuCode, qtyPerBox = it.quantity) },
    warnings = listOf("离线解析结果，箱唛状态和归属待联网确认"),
    localFallback = true,
)

private fun BoxLabel.mergeParsedPayload(parsed: BoxLabelQrPayload) = copy(
    labelCode = labelCode.ifBlank { parsed.labelCode },
    skuItems = skuItems.ifEmpty {
        parsed.items.map { BoxLabelSKUItem(skuCode = it.skuCode, qtyPerBox = it.quantity) }
    },
)

private fun BoxLabel.mergeLocalResolution(
    parsed: BoxLabelQrPayload?,
    mapping: LocalBoxLabelMappingEntity?,
    rawCode: String,
): BoxLabel {
    val parsedResult = parsed?.let(::mergeParsedPayload) ?: this
    if (mapping == null) return parsedResult
    return parsedResult.copy(
        labelCode = parsedResult.labelCode.ifBlank { mapping.supplierLabelCode },
        boxUid = parsedResult.boxUid.ifBlank { mapping.supplierBoxUid },
        boxNo = parsedResult.boxNo.ifBlank { mapping.supplierBoxNo },
        productCode = parsedResult.productCode.ifBlank { mapping.productCode },
        productName = parsedResult.productName.ifBlank { mapping.productName },
        skuCode = parsedResult.skuCode.ifBlank { mapping.skuCode },
        caseSpecName = parsedResult.caseSpecName.ifBlank { mapping.caseSpecName },
        qtyPerCarton = parsedResult.qtyPerCarton.takeIf { it > 0 } ?: mapping.qtyPerCarton,
        plannedBoxQty = parsedResult.plannedBoxQty.takeIf { it > 0 } ?: mapping.boxQty,
        scannedCode = parsedResult.scannedCode.ifBlank { rawCode.trim() },
        resolvedVia = parsedResult.resolvedVia.ifBlank { "jifeng_mapping" },
        jifengBoxNo = parsedResult.jifengBoxNo.ifBlank { mapping.jifengBoxNo },
        jifengQrCodeText = parsedResult.jifengQrCodeText.ifBlank { mapping.jifengQrCodeText },
        supplierLabelCode = parsedResult.supplierLabelCode.ifBlank { mapping.supplierLabelCode },
    )
}

private data class PendingReceivingPayload(
    val rawCode: String,
    val originalRawCode: String,
    val items: List<BoxLabelQrItem>,
    val actualReceivedQty: Int?,
    val sessionId: Long = 0,
)

private suspend fun SessionStore.namespaceId(): Long =
    current().currentNamespace?.id ?: 0

private fun ReceivingSession.toEntity(
    userId: Long,
    namespaceId: Long,
    json: Json,
    remoteSyncedAt: Long = System.currentTimeMillis(),
): ReceivingSessionEntity =
    ReceivingSessionEntity(
        userId = userId,
        namespaceId = namespaceId,
        remoteId = id,
        code = code,
        payload = json.encodeToString(this),
        updatedAt = System.currentTimeMillis(),
        remoteSyncedAt = remoteSyncedAt,
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
    val configured = overview?.appConfig?.apps ?: overview?.apps ?: return emptyList()
    val permissionSet = permissions.mapTo(mutableSetOf()) { it.trim() }
    return configured
        .map(::normalizeMobileAppEntry)
        .map(::applyLocalMobileAppMetadata)
        .distinctBy { it.key }
        .filter { it.visible }
        .filter { it.key in implementedMobileAppKeys }
        .filter { app ->
            sessionMode == SessionMode.Online ||
                (app.offlineSupported && app.key in offlineImplementedAppKeys && app.hasOfflinePermissions(permissionSet))
        }
        .sortedWith(compareBy<MobileAppEntry> { it.sort }.thenBy { it.title })
}
private fun normalizeMobileAppEntry(entry: MobileAppEntry): MobileAppEntry {
    val normalizedKey = when (entry.key) {
        "carton_management" -> "box_labels"
        in legacyPdaSettingsAppKeys -> "pda_settings"
        in legacyEdgeNodeAppKeys -> "edge_nodes"
        in legacyLabelPrintAppKeys -> "label_print"
        else -> entry.key
    }
    return if (normalizedKey == entry.key) entry else entry.copy(key = normalizedKey)
}

internal val implementedMobileAppKeys = setOf(
    "receiving",
    "inbound_management",
    "central_receiving",
    "profit",
    "product_management",
    "product_counting",
    "box_labels",
    "supplier_orders",
    "label_print",
    "edge_nodes",
    "pda_settings",
)

private val legacyPdaSettingsAppKeys = setOf(
    "pda_scanner_settings",
    "scanner_settings",
)

private val legacyEdgeNodeAppKeys = setOf(
    "print_settings",
    "printer_settings",
    "label_print_settings",
    "edge_print_settings",
)

private val legacyLabelPrintAppKeys = setOf(
    "label_printing",
    "print_label",
    "custom_label_print",
)

private fun applyLocalMobileAppMetadata(entry: MobileAppEntry): MobileAppEntry {
    val fallback = defaultMobileApps.firstOrNull { it.key == entry.key } ?: return entry
    return entry.copy(
        offlineSupported = entry.offlineSupported || fallback.offlineSupported,
        offlineSyncable = entry.offlineSyncable || fallback.offlineSyncable,
        offlineDataScope = entry.offlineDataScope.ifBlank { fallback.offlineDataScope },
        offlineRequiredPermissions = entry.offlineRequiredPermissions.ifEmpty { fallback.offlineRequiredPermissions },
    )
}

private fun MobileAppEntry.hasOfflinePermissions(permissionSet: Set<String>): Boolean =
    offlineRequiredPermissions.all { it in permissionSet }

private val offlineImplementedAppKeys = setOf("receiving", "inbound_management", "central_receiving", "product_counting")

val defaultMobileApps = listOf(
    MobileAppEntry(
        key = "inbound_management",
        title = "",
        description = "",
        path = "/pages/receiving-sessions/inbound",
        permission = "mobile-app:receiving",
        businessPermission = "receiving_records",
        icon = "",
        color = "#1677ff",
        sort = 44,
        offlineSupported = true,
        offlineSyncable = true,
        offlineDataScope = "namespace",
        offlineRequiredPermissions = listOf("mobile-app:receiving", "receiving_records"),
    ),
    MobileAppEntry(
        key = "receiving",
        title = "",
        description = "",
        path = "/pages/receiving/index",
        permission = "mobile-app:receiving",
        businessPermission = "receiving_records",
        icon = "",
        color = "#08979c",
        sort = 45,
        offlineSupported = true,
        offlineSyncable = true,
        offlineDataScope = "namespace",
        offlineRequiredPermissions = listOf("mobile-app:receiving", "receiving_records"),
    ),
    MobileAppEntry(
        key = "central_receiving",
        title = "",
        description = "",
        path = "/pages/central-receiving/index",
        permission = "mobile-app:central-receiving",
        businessPermission = "receiving_records",
        icon = "",
        color = "#1677ff",
        sort = 46,
        offlineSupported = true,
        offlineSyncable = true,
        offlineDataScope = "namespace",
        offlineRequiredPermissions = listOf("mobile-app:central-receiving", "receiving_records"),
    ),
    MobileAppEntry("supplier_orders", "", "", "/pages/supplier-orders/index", "mobile-app:supplier-orders", "supplier-orders", "", "#14b8a6", sort = 35),
    MobileAppEntry(
        key = "product_counting",
        title = "",
        description = "",
        path = "/pages/product-counting/index",
        permission = "mobile-app:product-counting",
        businessPermission = "",
        icon = "fact_check",
        color = "#1677ff",
        sort = 48,
        offlineSupported = true,
        offlineSyncable = true,
        offlineDataScope = "namespace",
        offlineRequiredPermissions = listOf("mobile-app:product-counting"),
    ),
    MobileAppEntry("profit", "", "", "/pages/profit/index", "mobile-app:profit", "", "", "#52c41a", sort = 50),
    MobileAppEntry("product_management", "", "", "/pages/product/index", "mobile-app:product", "products", "", "#1890ff", sort = 60),
    MobileAppEntry("box_labels", "", "", "/pages/box-labels/index", "mobile-app:box-labels", "", "", "#0f766e", sort = 70),
    MobileAppEntry("label_print", "", "", "/pages/print/label", "mobile-app:product", "products", "", "#52c41a", sort = 73),
    MobileAppEntry("edge_nodes", "", "", "/pages/edge/nodes", "mobile-app:product", "products", "", "#fa8c16", sort = 75),
    MobileAppEntry("pda_settings", "", "", "/pages/pda/settings", "", "", "", "#13a8a8", sort = 80),
).map { it.copy(localFallback = true) }
