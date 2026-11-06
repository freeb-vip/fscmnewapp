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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RealtimeShop(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val site: String = "",
    val orders: Long? = null,
    val amount: String? = null,
    val ordersChangeRatio: String? = null,
    val amountChangeRatio: String? = null,
)

@Serializable
data class RealtimeProduct(
    val id: String = "",
    val sku: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val name: String = "",
    val imageUrl: String = "",
    val quantity: Long? = null,
    val amount: String? = null,
)

@Serializable
data class RealtimeDay(val date: String = "", val orders: Long? = null, val amount: String? = null)

@Serializable
data class RealtimeWorkDay(
    val date: String = "",
    val timezone: String = "",
    val packed: Long? = null,
    val efficiency: Double? = null,
    val status: String = "missing",
    val packedAt: String? = null,
    val efficiencyAt: String? = null,
    val count: Long? = null,
    val countAt: String? = null,
    val basis: String = "",
    val scope: String = "",
)

@Serializable
data class RealtimeWorkSummary(
    val today: RealtimeWorkDay,
    val yesterday: RealtimeWorkDay,
    val comparison: RealtimeWorkComparison? = null,
)

@Serializable
data class RealtimeWorkComparison(
    val mode: String = "completed_hours",
    val cutoff: String = "",
    val available: Boolean = false,
    val reason: String = "",
    val current: RealtimeWorkDay = RealtimeWorkDay(),
    val previous: RealtimeWorkDay = RealtimeWorkDay(),
)

@Serializable
private data class RealtimeAccounts(val items: List<SalesAccount> = emptyList())

interface RealtimeDataSource {
    suspend fun accounts(): List<SalesAccount>
    suspend fun overview(connectionId: String): SalesOverview
    suspend fun workSummary(): RealtimeWorkSummary
    suspend fun selectedAccount(session: SessionState): String?
    suspend fun selectAccount(session: SessionState, id: String)
}

@Singleton
class RealtimeDataRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    @param:FscmJson private val json: Json,
) : RealtimeDataSource {
    override suspend fun accounts(): List<SalesAccount> =
        api.get<RealtimeAccounts>("mobile/app/realtime-data/connections").items

    override suspend fun overview(connectionId: String): SalesOverview = api.get(
        "mobile/app/realtime-data/overview",
        params = mapOf("connectionId" to connectionId, "timezone" to "Asia/Shanghai"),
    )

    override suspend fun workSummary(): RealtimeWorkSummary = api.get("mobile/app/realtime-data/work-summary")

    override suspend fun selectedAccount(session: SessionState): String? =
        offlineCacheDao.find(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, "sales_source_selection")
            ?.let { runCatching { json.decodeFromString<SalesSourceSelection>(it.payload).id }.getOrNull() }

    override suspend fun selectAccount(session: SessionState, id: String) {
        val current = sessionStore.current()
        if (current.user?.id != session.user?.id || current.currentNamespace?.id != session.currentNamespace?.id) return
        offlineCacheDao.upsert(
            OfflineCacheEntity(
                userId = session.user?.id ?: 0,
                namespaceId = session.currentNamespace?.id ?: 0,
                cacheKey = "sales_source_selection",
                payload = json.encodeToString(SalesSourceSelection(id)),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

internal fun canAccessRealtime(permissions: List<String>, mode: SessionMode): Boolean =
    mode == SessionMode.Online && "mobile-app:realtime-data" in permissions && "realtime-data" in permissions

internal fun SessionState.realtimeScope(): String = "${user?.id}:${currentNamespace?.id}:$sessionMode"
