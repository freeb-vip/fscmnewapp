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

    suspend fun login(username: String, password: String) {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("device", json.encodeToJsonElement(deviceIdentityManager.registration()))
        }
        val payload = api.post<AuthPayload>(
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
        sessionStore.applyAuth(payload)
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

    suspend fun loginOrOffline(username: String, password: String) {
        login(username, password)
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

    suspend fun validateActiveOfflineSession(): Boolean = sessionStore.validateActiveOfflineSession()

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
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        if (session.isOffline) {
            return cachedWorkbench(userId, namespaceId) ?: MobileWorkbenchOverview()
        }
        return runCatching {
            api.get<MobileWorkbenchOverview>("mobile/app/workbench/overview")
        }.onSuccess { overview ->
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    userId = userId,
                    namespaceId = namespaceId,
                    cacheKey = WORKBENCH_CACHE_KEY,
                    payload = json.encodeToString(overview),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.getOrElse { error ->
            cachedWorkbench(userId, namespaceId)?.takeIf { error !is FscmApiException } ?: throw error
        }
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
        val entity = offlineCacheDao.find(session.user?.id ?: 0, namespaceId, cacheKey) ?: return null
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
    private val catalogTickets = mutableMapOf<String, CachedEdgeCatalogTicket>()

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
        val nodes = api.get<PagedList<EdgeNode>>(
            "edge/nodes/available",
            edgeNodeListParams(includeOffline = true),
        ).rows()
        return nodeStatuses(nodes)
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
        val now = System.currentTimeMillis()
        catalogTickets[node.nodeId]
            ?.takeIf { it.validUntilMillis > now + CATALOG_TICKET_REFRESH_MARGIN_MS }
            ?.token
            ?: api.post<EdgeCatalogTicketResponse>(
                path = "edge/catalog-ticket",
                headers = mapOf("X-FSCM-Edge-Node-ID" to node.nodeId),
            ).token.also { token ->
                catalogTickets[node.nodeId] = CachedEdgeCatalogTicket(
                    token = token,
                    validUntilMillis = now + CATALOG_TICKET_CACHE_MS,
                )
            }
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
        require(label.id > 0) { "箱唛缺少有效 ID" }
        require(label.printable) { "当前箱唛不可打印" }
        require(label.printSnapshot != null) { "箱唛缺少打印快照" }
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
        const val CATALOG_TICKET_CACHE_MS = 7L * 60L * 60L * 1000L
        const val CATALOG_TICKET_REFRESH_MARGIN_MS = 60_000L
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
        registrationMutex.withLock {
            val edgeNodeId = runCatching {
                edgePrintRepository.registeredNodes()
                    .firstOrNull { it.localReachable }
                    ?.node
                    ?.nodeId
            }.getOrNull()?.takeIf(String::isNotBlank) ?: return
            val now = System.currentTimeMillis()
            val registrationKey = listOf(
                session.currentNamespace.id,
                terminalId(),
                lanIp.ifBlank { "unknown" },
                edgeNodeId,
            ).joinToString(":")
            if (registrationKey == lastRegistrationKey && now - lastRegistrationAtMillis < REGISTRATION_INTERVAL_MS) return

            val request = EdgeTerminalRegistrationRequest(
                terminalId = terminalId(),
                edgeNodeId = edgeNodeId,
                terminalName = terminalName(),
                appVersion = BuildConfig.VERSION_NAME,
                platform = "android",
                lanIp = lanIp,
                capabilities = TERMINAL_CAPABILITIES,
            )
            val registered = runCatching {
                api.post<Unit>(
                    path = "edge/terminals/register",
                    body = json.encodeToJsonElement(request),
                )
            }.isSuccess
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

@Serializable
private data class EdgeCatalogTicketResponse(
    val token: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

private data class CachedEdgeCatalogTicket(
    val token: String,
    val validUntilMillis: Long,
)

private class LocalDirectPrintUnavailableException(statusCode: Int) :
    IllegalStateException("本地直打接口不可用 ($statusCode)")

internal class EdgePrintTemplateUnavailableException :
    IllegalStateException("打印模板已更新，请重新选择打印节点后再试")

internal fun isEdgeTemplateUnavailable(statusCode: Int, errorCode: String): Boolean =
    statusCode == 409 && errorCode == "LABEL_TEMPLATE_UNAVAILABLE"

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
            qrPayload = content,
            templateCode = option.template.code,
            templateVersion = option.template.version,
        ),
    ),
)

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
): List<MobileAppEntry> {
    val apps = visibleMobileApps(overview, session.permissions, session.sessionMode)
    val quickEntries = overview?.quickEntries.orEmpty().filter { entry ->
        entry.visible &&
            (entry.permission.isBlank() || entry.permission in session.permissions) &&
            (entry.businessPermission.isBlank() || entry.businessPermission in session.permissions)
    }.map(::normalizeMobileAppEntry).filter { it.key in implementedMobileAppKeys }
    return (apps + quickEntries)
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
) {
    suspend fun sessions(): List<ReceivingSession> {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        if (session.isOffline) return cachedSessions(userId, namespaceId)
        return runCatching {
            decodeReceivingSessionsPayload(
                payload = api.get("receiving-sessions", mapOf("page" to "1", "pageSize" to "50")),
                json = json,
            )
        }.onSuccess { rows ->
            receivingDao.upsertSessions(rows.map { it.toEntity(userId, namespaceId, json) })
        }.getOrElse { error ->
            cachedSessions(userId, namespaceId).takeIf { it.isNotEmpty() && error !is FscmApiException } ?: throw error
        }
    }

    suspend fun session(id: Long): ReceivingSession {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val userId = session.user?.id ?: 0
        if (session.isOffline) return cachedSession(userId, namespaceId, id) ?: ReceivingSession(id = id)
        return runCatching {
            val payload: JsonElement = api.get<JsonElement>("receiving-sessions/$id")
            decodeBusinessObject<ReceivingSession>(payload, json)
        }.onSuccess { row ->
            receivingDao.upsertSession(row.toEntity(userId, namespaceId, json))
        }.getOrElse { error ->
            cachedSession(userId, namespaceId, id).takeIf { error !is FscmApiException } ?: throw error
        }
    }

    suspend fun refreshCachedSession(id: Long): ReceivingSession = session(id)

    suspend fun preview(id: Long, rawCode: String): ReceivingScanResponse {
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) {
            return offlinePreview(parsed)
        }
        return runCatching {
            api.post<ReceivingScanResponse>(
                "receiving-sessions/$id/scan-preview",
                buildJsonObject { put("raw_code", parsed.labelCode) },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            offlinePreview(parsed)
        }
    }

    suspend fun quickScan(rawCode: String, actualReceivedQty: Int? = null): ReceivingQuickScanResponse {
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) return enqueueOfflineQuickScan(parsed, actualReceivedQty)
        return runCatching {
            api.post<ReceivingQuickScanResponse>(
                "receiving-sessions/scan",
                buildJsonObject {
                    put("raw_code", parsed.labelCode)
                    actualReceivedQty?.let { put("actual_received_qty", it) }
                },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineQuickScan(parsed, actualReceivedQty)
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
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) {
            return ReceivingQuickScanResponse(
                data = parsed.toLocalReceivingScanItem(status = "pending_sync"),
                message = "已离线识别，确认后保存待同步",
                parsedPayload = parsed,
                localFallback = true,
            )
        }
        return runCatching {
            api.post<ReceivingQuickScanResponse>(
                "receiving-sessions/scan-preview",
                buildJsonObject { put("raw_code", parsed.labelCode) },
            ).copy(parsedPayload = parsed)
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            ReceivingQuickScanResponse(
                data = parsed.toLocalReceivingScanItem(status = "pending_sync"),
                message = "网络不可用，确认后保存待同步",
                parsedPayload = parsed,
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
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) {
            return enqueueOfflineScan(id, parsed, damaged, actualReceivedQty)
        }
        return runCatching {
            api.post<ReceivingScanResponse>(
                "receiving-sessions/$id/scan",
                buildJsonObject {
                    put("raw_code", parsed.labelCode)
                    put("damaged", damaged)
                    put("tally_count", true)
                    if (actualReceivedQty != null) put("actual_received_qty", actualReceivedQty)
                },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineScan(id, parsed, damaged, actualReceivedQty)
        }
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
                buildJsonObject { put("reason", "移动端取消扫码") },
            )
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueueOfflineVoid(id, item.id)
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
            ReceivingScanOperationPayload(
                sessionId = id,
                rawCode = parsed.labelCode,
                damaged = damaged,
                actualReceivedQty = actualReceivedQty,
                originalRawCode = parsed.originalRawCode,
                items = parsed.items,
            ),
        )
        offlineSyncScheduler.enqueue()
        return ReceivingScanResponse(localItem)
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
        offlineSyncScheduler.enqueue()
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
        val userId = sessionStore.current().user?.id ?: 0
        val current = cachedSession(userId, namespaceId, id) ?: ReceivingSession(id = id)
        receivingDao.upsertSession(transform(current).toEntity(userId, namespaceId, json))
    }

    private suspend fun cachedSessions(userId: Long, namespaceId: Long): List<ReceivingSession> =
        receivingDao.sessions(userId, namespaceId).mapNotNull { it.toModel(json) }

    private suspend fun cachedSession(userId: Long, namespaceId: Long, id: Long): ReceivingSession? =
        receivingDao.session(userId, namespaceId, id)?.toModel(json)
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
    @param:FscmJson private val json: Json,
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
        val payload: JsonElement = api.get("skus/$encoded")
        return decodeBusinessObject(payload, json)
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
    private val sessionStore: SessionStore,
    private val edgePrintRepository: EdgePrintRepository,
    @param:FscmJson private val json: Json,
) {
    suspend fun resolveBoxLabel(rawCode: String): BoxLabel {
        val parsed = parseBoxLabelQr(rawCode)
        val local = parsed.toLocalBoxLabel()
        val edge = runCatching { edgePrintRepository.resolveBoxLabelFromEdge(parsed.labelCode) }.getOrNull()
        if (edge != null) return edge.mergeParsedPayload(parsed)
        if (sessionStore.current().isOffline) return local
        val encoded = java.net.URLEncoder.encode(parsed.labelCode, "UTF-8")
        return runCatching {
            val payload: JsonElement = api.get("box-labels/$encoded/resolve")
            decodeBusinessObject<BoxLabel>(payload, json).mergeParsedPayload(parsed)
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            local.copy(warnings = listOf("边缘节点和中心均不可用，当前仅展示二维码离线参数"))
        }
    }
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

private data class PendingReceivingPayload(
    val rawCode: String,
    val originalRawCode: String,
    val items: List<BoxLabelQrItem>,
    val actualReceivedQty: Int?,
    val sessionId: Long = 0,
)

private suspend fun SessionStore.namespaceId(): Long =
    current().currentNamespace?.id ?: 0

private fun ReceivingSession.toEntity(userId: Long, namespaceId: Long, json: Json): ReceivingSessionEntity =
    ReceivingSessionEntity(
        userId = userId,
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
        .filter { it.key in implementedMobileAppKeys }
        .filter { it.permission.isBlank() || it.permission in permissionSet || it.permission == "mobile-app:product" }
        .filter { it.businessPermission.isBlank() || it.businessPermission in permissionSet || it.businessPermission == "products" }
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
    "profit",
    "product_management",
    "box_labels",
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

private fun MobileAppEntry.hasOfflinePermissions(permissionSet: Set<String>): Boolean =
    offlineRequiredPermissions.all { it in permissionSet }

private val offlineImplementedAppKeys = setOf("receiving", "inbound_management")

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
    MobileAppEntry("profit", "", "", "/pages/profit/index", "mobile-app:profit", "", "", "#52c41a", sort = 50),
    MobileAppEntry("product_management", "", "", "/pages/product/index", "mobile-app:product", "products", "", "#1890ff", sort = 60),
    MobileAppEntry("box_labels", "", "", "/pages/box-labels/index", "mobile-app:box-labels", "", "", "#0f766e", sort = 70),
    MobileAppEntry("label_print", "", "", "/pages/print/label", "mobile-app:product", "products", "", "#52c41a", sort = 73),
    MobileAppEntry("edge_nodes", "", "", "/pages/edge/nodes", "mobile-app:product", "products", "", "#fa8c16", sort = 75),
    MobileAppEntry("pda_settings", "", "", "/pages/pda/settings", "", "", "", "#13a8a8", sort = 80),
).map { it.copy(localFallback = true) }
