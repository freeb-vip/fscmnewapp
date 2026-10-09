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
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
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
    val scope: String = "personal",
    val date: String = "",
    val timezone: String = "",
    @SerialName("captured_at") val capturedAt: String = "",
    val status: String = "missing",
    val reason: String = "",
    val metric: WarehouseMetric? = null,
    val operations: Map<String, WarehouseOperationMetric> = emptyMap(),
    @SerialName("yesterday_date") val yesterdayDate: String = "",
    @SerialName("yesterday_captured_at") val yesterdayCapturedAt: String = "",
    @SerialName("yesterday_metric") val yesterdayMetric: WarehouseMetric? = null,
    @SerialName("comparison_available") val comparisonAvailable: Boolean = false,
    @Transient val cached: Boolean = false,
)

internal fun WarehouseWorkbench.forCurrentBusinessDate(now: Instant = Instant.now()): WarehouseWorkbench {
    val today = runCatching { now.atZone(ZoneId.of(timezone)).toLocalDate() }.getOrNull()
        ?: return copy(metric = null, yesterdayMetric = null, comparisonAvailable = false)
    if (date == today.toString()) return this
    val previous = today.minusDays(1).toString()
    return copy(
        date = today.toString(),
        status = "missing",
        capturedAt = "",
        metric = null,
        yesterdayDate = previous,
        yesterdayCapturedAt = capturedAt.takeIf { date == previous }.orEmpty(),
        yesterdayMetric = metric.takeIf { date == previous },
        comparisonAvailable = false,
    )
}

internal fun warehouseChangePercent(current: Double?, previous: Double?): Double? {
    if (current == null || previous == null || !current.isFinite() || !previous.isFinite() || previous <= 0) return null
    return ((current - previous) / previous * 100).takeIf { it.isFinite() }
}

internal fun canReadWarehouseSummary(session: SessionState): Boolean = "order_statistics" in session.permissions

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
    val reason: String = "",
    val queryAt: String? = null,
    val sourceUpdatedAt: String? = null,
)

@Serializable
data class SalesComparison(
    val mode: String = "",
    val cutoff: String = "",
    val current: SalesSummary = SalesSummary(),
    val previous: SalesSummary = SalesSummary(),
)

@Serializable
data class SalesOverview(
    val provider: String = "",
    val productBasis: String = "",
    val rankingLimit: Int = 50,
    val date: String = "",
    val timezone: String = "",
    val currency: String = "CNY",
    @SerialName("connectionId") val connectionId: String = "",
    val summary: SalesSummary = SalesSummary(),
    val comparison: SalesComparison = SalesComparison(),
    val hours: List<SalesHour> = emptyList(),
    val shops: List<RealtimeShop> = emptyList(),
    val products: List<RealtimeProduct> = emptyList(),
    val week: List<RealtimeDay> = emptyList(),
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
    val provider: String = "xinjian",
    @SerialName("defaultTimezone") val defaultTimezone: String = "Asia/Shanghai",
)

@Serializable
internal data class SalesSourceSelection(val id: String, val timezone: String = "Asia/Shanghai")

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

    suspend fun cachedWarehouse(session: SessionState): WarehouseWorkbench? {
        if (!canReadWarehouseSummary(session)) return WarehouseWorkbench(scope = "workspace", status = "no_permission")
        return offlineCacheDao.find(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, "warehouse_summary_v1")
            ?.let { runCatching { json.decodeFromString<WarehouseWorkbench>(it.payload) }.getOrNull() }
            ?.takeIf { it.scope == "workspace" }
            ?.copy(cached = true)
            ?.forCurrentBusinessDate()
    }

    suspend fun warehouse(session: SessionState): WarehouseWorkbench {
        if (!canReadWarehouseSummary(session)) return WarehouseWorkbench(scope = "workspace", status = "no_permission")
        val fresh = try {
            api.get<WarehouseWorkbench>("mobile/app/workbench/warehouse-summary")
        } catch (error: FscmApiException) {
            if (error.statusCode != 401 && error.statusCode != 403) throw error
            WarehouseWorkbench(scope = "workspace", status = "no_permission")
        }
        val active = sessionStore.current()
        if (active.user?.id == session.user?.id && active.currentNamespace?.id == session.currentNamespace?.id) {
            offlineCacheDao.upsert(
                OfflineCacheEntity(
                    userId = session.user?.id ?: 0,
                    namespaceId = session.currentNamespace?.id ?: 0,
                    cacheKey = "warehouse_summary_v1",
                    payload = json.encodeToString(fresh),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return fresh
    }

    suspend fun operations(): OperationsWorkbench = api.get(
        "mobile/app/workbench/operations",
        params = mapOf("include_sales" to "false"),
    )
}
