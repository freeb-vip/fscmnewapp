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

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WorkbenchPresentation(
    val style: String = "general",
    val version: String = "",
    @SerialName("home_app_keys") val homeAppKeys: List<String> = emptyList(),
    val apps: List<MobileAppEntry> = emptyList(),
)

@Serializable
data class WarehouseMetric(
    val count: Long = 0,
    val efficiency: Double = 0.0,
    @SerialName("person_hours") val personHours: Double = 0.0,
    val available: Boolean? = null,
    @SerialName("efficiency_available") val efficiencyAvailable: Boolean? = null,
)

@Serializable
data class WarehouseWorkbench(
    val date: String = "",
    val timezone: String = "",
    @SerialName("captured_at") val capturedAt: String = "",
    val status: String = "missing",
    val reason: String = "",
    val metric: WarehouseMetric? = null,
    val operations: Map<String, WarehouseOperationMetric> = emptyMap(),
)

@Serializable
data class WarehouseOperationMetric(
    val included: Boolean = false,
    val count: Long = 0,
    val available: Boolean = false,
)

private val defaultWarehouseHomeKeys = listOf("receiving", "inbound_management", "central_receiving", "product_counting")

internal fun orderedWarehouseHomeApps(catalog: List<MobileAppEntry>, keys: List<String>): List<MobileAppEntry> =
    keys.mapNotNull { key -> catalog.firstOrNull { it.key == key } }.ifEmpty {
        defaultWarehouseHomeKeys.mapNotNull { key -> catalog.firstOrNull { it.key == key } }.take(4)
            .ifEmpty { catalog.take(4) }
    }

@Serializable
data class WorkbenchModule<T>(
    val status: String = "no_permission",
    val data: T? = null,
    val reason: String = "",
)

@Serializable
data class SalesSummary(
    val orders: Long? = null,
    val amount: String? = null,
)

@Serializable
data class SalesHour(
    val hour: Int = 0,
    val orders: Long? = null,
    val amount: String? = null,
    @SerialName("yesterdayOrders") val yesterdayOrders: Long? = null,
    @SerialName("yesterdayAmount") val yesterdayAmount: String? = null,
)

@Serializable
data class SalesModuleState(
    val status: String = "pending",
    @SerialName("fetchedAt") val fetchedAt: String? = null,
)

@Serializable
data class SalesComparison(
    val cutoff: String = "",
    val current: SalesSummary = SalesSummary(),
    val previous: SalesSummary = SalesSummary(),
)

@Serializable
data class SalesOverview(
    val date: String = "",
    val timezone: String = "",
    val currency: String = "CNY",
    @SerialName("connectionId") val connectionId: String = "",
    val summary: SalesSummary = SalesSummary(),
    val comparison: SalesComparison = SalesComparison(),
    val hours: List<SalesHour> = emptyList(),
    val modules: Map<String, SalesModuleState> = emptyMap(),
)

@Serializable
data class DailySummary(
    @SerialName("saleAmount") val saleAmount: String? = null,
    val profit: String? = null,
    @SerialName("profitMargin") val profitMargin: String? = null,
    @SerialName("totalOrders") val totalOrders: Long? = null,
)

@Serializable
data class DailyOverview(
    val date: String = "",
    val summary: DailySummary = DailySummary(),
)

@Serializable
data class OperationsWorkbench(
    val sales: WorkbenchModule<SalesOverview> = WorkbenchModule(),
    val daily: WorkbenchModule<DailyOverview> = WorkbenchModule(),
    val todos: WorkbenchModule<List<MobileTodo>> = WorkbenchModule(),
    val accounts: List<SalesAccount> = emptyList(),
)

@Serializable
data class SalesAccount(
    val id: String = "",
    val name: String = "",
    @SerialName("defaultTimezone") val defaultTimezone: String = "Asia/Shanghai",
)

@Serializable
private data class SalesSourceSelection(val id: String, val timezone: String)

@Singleton
class WorkbenchStyleRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    @param:FscmJson private val json: Json,
) {
    suspend fun presentation(session: SessionState): WorkbenchPresentation {
        val userId = session.user?.id ?: 0
        val namespaceId = session.currentNamespace?.id ?: 0
        val cached = offlineCacheDao.find(userId, namespaceId, "workbench_presentation")
            ?.let { runCatching { json.decodeFromString<WorkbenchPresentation>(it.payload) }.getOrNull() }
        if (session.isOffline) return cached ?: WorkbenchPresentation()
        return try {
            val fresh = api.get<WorkbenchPresentation>("mobile/app/workbench/presentation")
            val active = sessionStore.current()
            if (active.user?.id == userId && active.currentNamespace?.id == namespaceId) {
                offlineCacheDao.upsert(
                    OfflineCacheEntity(
                        userId = userId,
                        namespaceId = namespaceId,
                        cacheKey = "workbench_presentation",
                        payload = json.encodeToString(fresh),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            fresh
        } catch (error: FscmApiException) {
            if (error.statusCode == 404) WorkbenchPresentation() else cached ?: WorkbenchPresentation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            cached ?: WorkbenchPresentation()
        }
    }

    suspend fun warehouse(): WarehouseWorkbench = api.get("mobile/app/workbench/warehouse")

    suspend fun operations(session: SessionState): OperationsWorkbench {
        val selection = offlineCacheDao.find(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, "sales_source_selection")
            ?.let { runCatching { json.decodeFromString<SalesSourceSelection>(it.payload) }.getOrNull() }
        return api.get(
            "mobile/app/workbench/operations",
            params = selection?.let { mapOf("connectionId" to it.id, "timezone" to it.timezone) } ?: emptyMap(),
        )
    }

    suspend fun selectSalesSource(session: SessionState, account: SalesAccount) {
        if (account.id.isBlank()) return
        val current = sessionStore.current()
        if (current.user?.id != session.user?.id || current.currentNamespace?.id != session.currentNamespace?.id) return
        offlineCacheDao.upsert(
            OfflineCacheEntity(
                userId = session.user?.id ?: 0,
                namespaceId = session.currentNamespace?.id ?: 0,
                cacheKey = "sales_source_selection",
                payload = json.encodeToString(SalesSourceSelection(account.id, account.defaultTimezone)),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
