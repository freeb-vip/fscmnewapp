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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.net.URLEncoder
import java.text.DecimalFormat
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class InsightWarehouse(
    @SerialName("binding_id") val id: Long = 0,
    @SerialName("warehouse_name") val name: String = "",
    @SerialName("connection_name") val connection: String = "",
)

@Serializable
data class InsightCatalog(val options: List<InsightWarehouse> = emptyList(), val pending: Boolean = false, val errors: List<String> = emptyList())

@Serializable
data class InsightJob(val id: String = "", val status: String = "", @SerialName("fetched_count") val fetchedCount: Int = 0, val error: String = "")

@Serializable
data class InsightOrder(
    @SerialName("supplier_order_code") val supplierOrderCode: String = "",
    @SerialName("consolidation_order_code") val consolidationOrderCode: String = "",
    @SerialName("ordered_qty") val orderedQty: Long? = null,
    @SerialName("shipped_qty") val shippedQty: Long? = null,
    @SerialName("production_qty") val productionQty: Long? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class InsightRow(
    @SerialName("row_key") val key: String = "",
    val sku: String = "",
    @SerialName("local_sku") val localSku: String = "",
    @SerialName("local_sku_id") val localSkuId: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    val unit: String = "",
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("sales_quantities") val salesQuantities: Map<String, Long?> = emptyMap(),
    @SerialName("on_hand_qty") val onHand: Long? = null,
    @SerialName("allocated_qty") val allocated: Long? = null,
    @SerialName("available_qty") val available: Long? = null,
    @SerialName("in_transit_qty") val externalTransit: Long? = null,
    @SerialName("production_qty") val production: Long? = null,
    @SerialName("total_transit_qty") val transit: Long? = null,
    @SerialName("forecast_daily") val forecast: Double? = null,
    @SerialName("daily_sales_reference") val reference: Double? = null,
    @SerialName("forecast_demand_30") val demand: Double? = null,
    @SerialName("coverage_days") val coverage: Double? = null,
    @SerialName("transit_coverage_days") val transitCoverage: Double? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("data_statuses") val dataStatuses: List<String> = emptyList(),
    @SerialName("production_orders") val productionOrders: List<InsightOrder> = emptyList(),
) {
    fun metric(key: String): Number? = when {
        key.startsWith("sales") -> salesQuantities[key.removePrefix("sales")]
        else -> when (key) {
            "onHand" -> onHand
            "allocated" -> allocated
            "available" -> available
            "externalTransit" -> externalTransit
            "production" -> production
            "transit" -> transit
            "forecast" -> forecast
            "reference" -> reference
            "demand" -> demand
            "coverage" -> coverage
            "transitCoverage" -> transitCoverage
            else -> null
        }
    }
}

@Serializable
data class InsightPage(
    @SerialName("snapshot_id") val snapshotId: String = "",
    @SerialName("source_time") val sourceTime: String = "",
    @SerialName("source_timezone") val sourceTimezone: String = "",
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val freshness: String = "",
    val items: List<InsightRow> = emptyList(),
    val totals: InsightRow? = null,
    val total: Int = 0,
    val page: Int = 1,
    val job: InsightJob? = null,
    val warnings: List<String> = emptyList(),
)
data class InsightMetric(val key: String, val label: String, val group: String, val additive: Boolean = true)
val insightMetrics = listOf(3, 7, 15, 30, 60, 90).map { InsightMetric("sales$it", "${it}天销量", "销量") } + listOf(
    InsightMetric("onHand", "当前库存", "库存"), InsightMetric("allocated", "占用库存", "库存"), InsightMetric("available", "可用库存", "库存"),
    InsightMetric("externalTransit", "BigSeller 在途", "在途"), InsightMetric("production", "本地在途未入仓", "在途"), InsightMetric("transit", "总在途", "在途"),
    InsightMetric("forecast", "预测日均", "预测", false), InsightMetric("reference", "BigSeller 参考日均", "预测", false),
    InsightMetric("demand", "未来30天需求", "预测", false), InsightMetric("coverage", "现货可售天数", "预测", false), InsightMetric("transitCoverage", "含在途理论天数", "预测", false),
)
val defaultInsightMetrics = listOf("sales30", "available", "transit")
val insightSorts = linkedMapOf("sales30" to "30天销量", "sales7" to "7天销量", "sales15" to "15天销量", "sales3" to "3天销量", "sales60" to "60天销量", "sales90" to "90天销量", "sku" to "SKU", "available" to "可用库存", "production" to "本地在途", "transit" to "总在途", "forecast" to "预测日均", "coverage" to "现货可售天数")
val insightStatuses = linkedMapOf("" to "全部状态", "normal" to "正常", "hot" to "爆品", "surge" to "销量异动", "stockout" to "缺货", "stockout_risk" to "缺货风险", "stockout_no_transit" to "缺货无在途", "stockout_no_transit_15" to "15天缺货无在途", "stockout_no_transit_30" to "30天缺货无在途", "warning" to "数据异常")
fun insightNumber(value: Number?): String = value?.toString()?.let { DecimalFormat("#,##0.################").format(BigDecimal(it)) } ?: "—"
fun normalizedInsightMetrics(keys: List<String>): List<String> = keys.filter { key -> insightMetrics.any { it.key == key } }.distinct().take(6).ifEmpty { defaultInsightMetrics }
fun canAccessPurchaseInsights(session: SessionState): Boolean = session.sessionMode == SessionMode.Online && "purchase-insights" in session.permissions

@Serializable
data class InsightFilter(
    val warehouses: List<Long>? = null,
    val sku: String = "",
    val exact: Boolean = false,
    val batch: String = "",
    val status: String = "",
    val hasSales: Boolean = false,
    val salesDays: Int = 30,
    val producing: Boolean = false,
    val anomalies: Boolean = false,
) {
    fun count(totalWarehouses: Int): Int = listOf(warehouses?.size != totalWarehouses, sku.isNotBlank(), batch.isNotBlank(), status.isNotBlank(), hasSales, producing, anomalies).count { it }
    fun query(product: String, page: Int, sort: String, ascending: Boolean, snapshot: String?): JsonObject = buildJsonObject {
        put("binding_id", warehouses?.firstOrNull() ?: 0)
        put("binding_ids", JsonArray(warehouses.orEmpty().map(::JsonPrimitive)))
        put("product", product.trim())
        put("sku", sku.trim())
        put("match", if (exact) "exact" else "contains")
        put("skus", JsonArray(batch.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }.distinct().map(::JsonPrimitive)))
        if (status.isNotEmpty()) put("data_status", status)
        put("has_sales", hasSales)
        put("sales_days", salesDays)
        put("producing_only", producing)
        put("anomalies_only", anomalies)
        put("page", page)
        put("page_size", 50)
        put("sort", sort)
        put("order", if (ascending) "asc" else "desc")
        if (!snapshot.isNullOrBlank()) put("snapshot_id", snapshot)
    }
}

@Serializable
data class InsightPreferences(val metrics: List<String> = defaultInsightMetrics, val filter: InsightFilter = InsightFilter())

@Serializable
data class InsightHistoryScope(val id: Long = 0, @SerialName("binding_id") val bindingId: Long = 0, @SerialName("warehouse_name") val warehouseName: String = "")

@Serializable
data class InsightHistoryRecord(
    @SerialName("sku_id") val skuId: Long = 0,
    val day: String = "",
    @SerialName("source_sku") val sourceSku: String = "",
    @SerialName("local_sku") val localSku: String = "",
    val unit: String = "",
    val sales7: Long? = null,
    val sales15: Long? = null,
    val sales30: Long? = null,
) {
    fun value(window: Int): Long? = when (window) {
        7 -> sales7
        15 -> sales15
        else -> sales30
    }
}

@Serializable
data class InsightHistoryCoverage(val day: String = "", val timezone: String = "", @SerialName("observed_at") val observedAt: String = "")

@Serializable
data class InsightHistoryPage(
    val items: List<InsightHistoryRecord> = emptyList(),
    val total: Int = 0,
    val coverage: List<InsightHistoryCoverage> = emptyList(),
    @SerialName("missing_dates") val missingDates: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)
data class InsightHistory(val records: List<InsightHistoryRecord>, val coverage: List<InsightHistoryCoverage>, val warnings: List<String>, val dates: List<String>)
fun insightHistoryDates(today: LocalDate, days: Int): List<String> = (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }

// Duplicate identity records on the same date are ambiguous and must not become invented sums.
fun insightHistoryPoints(records: List<InsightHistoryRecord>, dates: List<String>, source: String, window: Int): List<Long?> {
    val byDate = records.filter { it.sourceSku == source }.groupBy { it.day.take(10) }
    return dates.map { date -> byDate[date]?.singleOrNull()?.value(window) }
}

interface PurchaseInsightSource {
    suspend fun catalog(): InsightCatalog
    suspend fun query(body: JsonObject): InsightPage
    suspend fun detail(ids: List<Long>, snapshot: String, key: String): InsightRow
    suspend fun scopes(): List<InsightHistoryScope>
    suspend fun history(scope: Long, sku: String, dates: List<String>): InsightHistory
}

internal fun insightDetailParameters(ids: List<Long>, snapshot: String): Map<String, String> = buildMap {
    put("binding_id", ids.first().toString())
    put("snapshot_id", snapshot)
    // The existing endpoint accepts binding_ids only for a merged (2+ warehouse) snapshot.
    if (ids.size > 1) put("binding_ids", ids.joinToString(",", "[", "]"))
}

@Singleton
class PurchaseInsightRepository @Inject constructor(private val api: FscmApiClient) : PurchaseInsightSource {
    override suspend fun catalog(): InsightCatalog = api.post("purchase-insights/warehouse-catalog")
    override suspend fun query(body: JsonObject): InsightPage = api.post("purchase-insights/query", body)
    override suspend fun detail(ids: List<Long>, snapshot: String, key: String): InsightRow = api.get(
        "purchase-insights/skus/" + URLEncoder.encode(key, "UTF-8").replace("+", "%20"),
        insightDetailParameters(ids, snapshot),
    )
    override suspend fun scopes(): List<InsightHistoryScope> = api.get("purchase-insights/history/options")
    override suspend fun history(scope: Long, sku: String, dates: List<String>): InsightHistory {
        val records = mutableListOf<InsightHistoryRecord>()
        val coverage = mutableListOf<InsightHistoryCoverage>()
        val warnings = mutableSetOf<String>()
        var page = 1
        do {
            val result: InsightHistoryPage = api.post(
                "purchase-insights/history/query",
                buildJsonObject {
                    put("scope_id", scope)
                    put("kind", "snapshot")
                    put("grain", "day")
                    put("sku", sku)
                    put("from", dates.first())
                    put("to", dates.last())
                    put("page", page)
                    put("page_size", 200)
                },
            )
            records.addAll(result.items)
            coverage.addAll(result.coverage)
            warnings.addAll(result.warnings)
            check(records.size >= result.total || (result.items.isNotEmpty() && records.size < 10000)) { "历史分页不完整，请缩短时间范围后重试" }
            page++
        } while (records.size < result.total)
        return InsightHistory(records, coverage.distinctBy { it.day }, warnings.toList(), dates)
    }
}
interface PurchaseInsightPreferences {
    suspend fun read(scope: String): InsightPreferences
    suspend fun save(scope: String, value: InsightPreferences)
}
private val Context.purchaseInsightStore by preferencesDataStore(name = "fscm_purchase_insights")

@Singleton
class PurchaseInsightPreferenceStore @Inject constructor(@ApplicationContext context: Context, @param:FscmJson private val json: Json) : PurchaseInsightPreferences {
    private val store = context.purchaseInsightStore
    override suspend fun read(scope: String): InsightPreferences = store.data.first()[stringPreferencesKey(scope)]?.let { json.decodeFromString<InsightPreferences>(it) } ?: InsightPreferences()
    override suspend fun save(scope: String, value: InsightPreferences) {
        store.edit { it[stringPreferencesKey(scope)] = json.encodeToString(value) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PurchaseInsightModule {
    @Binds abstract fun source(repository: PurchaseInsightRepository): PurchaseInsightSource

    @Binds abstract fun preferences(store: PurchaseInsightPreferenceStore): PurchaseInsightPreferences
}
