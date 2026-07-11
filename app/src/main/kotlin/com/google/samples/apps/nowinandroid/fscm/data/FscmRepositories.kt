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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
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
    return configured
        .filter { it.visible }
        .filter { it.permission.isBlank() || it.permission in permissionSet }
        .filter { it.businessPermission.isBlank() || it.businessPermission in permissionSet }
        .filter { app ->
            sessionMode == SessionMode.Online ||
                (app.offlineSupported && app.key in offlineImplementedAppKeys && app.hasOfflinePermissions(permissionSet))
        }
        .sortedWith(compareBy<MobileAppEntry> { it.sort }.thenBy { it.title })
}

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
)
