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

package com.google.samples.apps.nowinandroid.fscm.ui

import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.InsightCatalog
import com.google.samples.apps.nowinandroid.fscm.data.InsightCatalogCache
import com.google.samples.apps.nowinandroid.fscm.data.InsightFilter
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistory
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistoryRecord
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistoryScope
import com.google.samples.apps.nowinandroid.fscm.data.InsightPage
import com.google.samples.apps.nowinandroid.fscm.data.InsightPreferences
import com.google.samples.apps.nowinandroid.fscm.data.InsightRow
import com.google.samples.apps.nowinandroid.fscm.data.InsightWarehouse
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseInsightPreferences
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseInsightSource
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.UserProfile
import com.google.samples.apps.nowinandroid.fscm.data.defaultInsightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightDetailParameters
import com.google.samples.apps.nowinandroid.fscm.data.insightHistoryDates
import com.google.samples.apps.nowinandroid.fscm.data.insightHistoryPoints
import com.google.samples.apps.nowinandroid.fscm.data.insightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightNumber
import com.google.samples.apps.nowinandroid.fscm.data.normalizedInsightMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseInsightTest {
    private fun session(namespace: Long = 1, permissions: List<String> = listOf("purchase-insights")) = SessionState(
        token = "test",
        user = UserProfile(id = 7),
        currentNamespace = NamespaceProfile(id = namespace),
        permissions = permissions,
    )
    private class Store : PurchaseInsightPreferences {
        val values = mutableMapOf<String, InsightPreferences>()
        override suspend fun read(scope: String) = values[scope] ?: InsightPreferences()
        override suspend fun save(scope: String, value: InsightPreferences) {
            values[scope] = value
        }
    }
    private open class Source : PurchaseInsightSource {
        override suspend fun demandSettings(binding: Long, product: Long, year: Int) = com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings(bindingId = binding, productId = product, year = year)
        override suspend fun saveDemandSettings(value: com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings) = value

        var directory = listOf(InsightWarehouse(1, "A仓"), InsightWarehouse(2, "B仓"))
        val queries = mutableListOf<JsonObject>()
        var response: suspend (JsonObject) -> InsightPage = { body ->
            val page = body.getValue("page").jsonPrimitive.int
            InsightPage(
                snapshotId = "fixed",
                sourceTimezone = "Asia/Bangkok",
                page = page,
                items = listOf(InsightRow(key = "row$page", sku = "TB1-$page", localSku = "B1-$page", localSkuId = 9)),
                total = 2,
                totals = InsightRow(skuCount = 2, available = 500),
            )
        }
        var historyResponse: suspend (Long, String, List<String>) -> InsightHistory = { _, _, dates -> InsightHistory(emptyList(), emptyList(), emptyList(), dates) }
        var catalogCalls = 0
        var catalogResult: suspend () -> InsightCatalog = { InsightCatalog(directory) }
        override suspend fun catalog(scope: String, refresh: Boolean): InsightCatalog {
            catalogCalls++
            return catalogResult()
        }
        override suspend fun query(body: JsonObject): InsightPage {
            queries += body
            return response(body)
        }
        override suspend fun detail(ids: List<Long>, snapshot: String, key: String) = InsightRow(key = key, sku = "TB1-1", localSku = "B1-1", localSkuId = 9)
        override suspend fun scopes() = listOf(InsightHistoryScope(11, 1, "A仓"), InsightHistoryScope(22, 2, "B仓"))
        override suspend fun history(scope: Long, sku: String, dates: List<String>) = historyResponse(scope, sku, dates)
    }

    @Test
    fun catalogCacheIsScopedExpiresAndDoesNotCacheColdPending() = runTest {
        var now = 0L
        val cache = InsightCatalogCache { now }
        var calls = 0
        val load: suspend () -> InsightCatalog = {
            calls++
            InsightCatalog(listOf(InsightWarehouse(calls.toLong(), "warehouse")))
        }
        assertEquals(1L, cache.read("account-a|workspace-a", load = load).options.single().id)
        assertEquals(1L, cache.read("account-a|workspace-a", load = load).options.single().id)
        assertEquals(1, calls)
        assertEquals(2L, cache.read("account-a|workspace-b", load = load).options.single().id)
        now += 301_000_000_000L
        cache.read("account-a|workspace-b", load = load)
        assertEquals(3, calls)
        cache.read("account-a|workspace-b", refresh = true, load = load)
        assertEquals(4, calls)
        cache.clear()
        cache.read("account-a|workspace-b") {
            calls++
            InsightCatalog(pending = true)
        }
        cache.read("account-a|workspace-b", load = load)
        assertEquals(6, calls)
        try {
            cache.read("account-a|workspace-b", refresh = true) { throw FscmApiException("forbidden", 403) }
        } catch (_: FscmApiException) { }
        cache.read("account-a|workspace-b", load = load)
        assertEquals(7, calls)
        cache.clear()
        cache.read("account-a|workspace-b") {
            calls++
            InsightCatalog(errors = listOf("unavailable"))
        }
        cache.read("account-a|workspace-b", load = load)
        assertEquals(9, calls)
    }

    @Test
    fun resumeAndIdleDoNotRepeatDirectoryOrListRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            source.catalogResult = { InsightCatalog(source.directory, pending = true) }
            source.response = { InsightPage(snapshotId = "cached", freshness = "loading", items = listOf(InsightRow(key = "kept")), total = 1) }
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            vm.stop()
            vm.enter(session())
            runCurrent()
            assertEquals(1, source.catalogCalls)
            assertEquals(1, source.queries.size)
            assertEquals("kept", vm.state.value.data?.items?.single()?.key)
            vm.refresh()
            runCurrent()
            assertEquals(2, source.catalogCalls)
            assertEquals(2, source.queries.size)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingCatalogNeedsExplicitRetryAndRefreshFailureKeepsRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            source.catalogResult = { InsightCatalog(pending = true) }
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(1, source.catalogCalls)
            assertTrue(source.queries.isEmpty())
            source.catalogResult = { InsightCatalog(errors = listOf("unavailable")) }
            vm.retry()
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.initialized)
            assertTrue(source.queries.isEmpty())
            source.catalogResult = { InsightCatalog(source.directory) }
            vm.retry()
            runCurrent()
            assertNotNull(vm.state.value.data)
            source.catalogResult = { throw java.io.IOException("offline") }
            vm.refresh()
            runCurrent()
            assertNotNull(vm.state.value.data)
            assertNotNull(vm.state.value.error)
            source.catalogResult = { throw FscmApiException("forbidden", 403) }
            vm.refresh()
            runCurrent()
            assertNull(vm.state.value.data)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun metricsPreserveUnknownPrecisionAndSumOnlyDemandQuantities() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val page = json.decodeFromString<InsightPage>("""{"items":[{"row_key":"a","sales_quantities":{"30":0,"7":null},"available_qty":9007199254740993,"forecast_daily":1.125,"warnings":null}],"totals":{"sku_count":52,"available_qty":700}}""")
        assertEquals("0", insightNumber(page.items.single().metric("sales30")))
        assertEquals("—", insightNumber(page.items.single().metric("sales7")))
        assertEquals("9,007,199,254,740,993", insightNumber(page.items.single().available))
        assertEquals("1.125", insightNumber(page.items.single().forecast))
        assertEquals(52, page.totals?.skuCount)
        assertEquals(listOf("demand"), insightMetrics.filter { it.group == "预测" && it.additive }.map { it.key })
        assertEquals(defaultInsightMetrics, normalizedInsightMetrics(listOf("removed")))
        assertEquals(6, normalizedInsightMetrics(insightMetrics.map { it.key }).size)
    }

    @Test
    fun forecastContractRetainsUnknownAndBeyondHorizon() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<InsightPage>("""{"items":[{"row_key":"a","forecast_demand_30":120.5,"coverage_days":365,"coverage_beyond_365":true,"forecast_model":{"method":"rolling_exposure","confidence":"low","months":[{"month":"2026-12","total_ratio":1.5,"daily_ratio":1.4516129,"source":"warehouse_manual"}]}}],"totals":{"forecast_demand_30":120.5,"forecast_incomplete":true}}""")
        val row = page.items.single()
        assertTrue(row.coverageBeyond)
        assertEquals(120.5, row.demand)
        assertEquals("warehouse_manual", row.forecastModel?.months?.single()?.source)
        assertEquals(1.5, row.forecastModel?.months?.single()?.totalRatio)
        assertTrue(page.totals?.forecastIncomplete == true)
        assertNull(row.transitCoverage)
        assertTrue("demand" in defaultInsightMetrics)
    }

    @Test
    fun detailParametersRespectSingleAndMergedWarehouseEndpoints() {
        assertEquals(mapOf("binding_id" to "2", "snapshot_id" to "fixed"), insightDetailParameters(listOf(2), "fixed"))
        assertEquals(mapOf("binding_id" to "2", "snapshot_id" to "fixed", "binding_ids" to "[2,3]"), insightDetailParameters(listOf(2, 3), "fixed"))
    }

    @Test
    fun filterUsesServerSnapshotAndExactProductContract() {
        val body = InsightFilter(warehouses = listOf(2, 3), batch = " A，B\nA ", exact = true, hasSales = true, salesDays = 15)
            .query(" TB1 ", 2, "available", true, "fixed")
        assertEquals("TB1", body["product"]?.jsonPrimitive?.content)
        assertEquals("exact", body["match"]?.jsonPrimitive?.content)
        assertEquals(listOf("A", "B"), body["skus"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("fixed", body["snapshot_id"]?.jsonPrimitive?.content)
        assertEquals(20, body["page_size"]?.jsonPrimitive?.int)
        assertEquals(listOf(2, 3), body["binding_ids"]?.jsonArray?.map { it.jsonPrimitive.int })
    }

    @Test
    fun chartLeavesMissingAndAmbiguousDaysBlankButKeepsRealZero() {
        val dates = insightHistoryDates(LocalDate.parse("2026-01-02"), 4)
        assertEquals(listOf("2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02"), dates)
        val rows = listOf(
            InsightHistoryRecord(day = dates[0], sourceSku = "A", sales30 = 10),
            InsightHistoryRecord(day = dates[1], sourceSku = "A", sales30 = 0),
            InsightHistoryRecord(day = dates[3], sourceSku = "A", sales30 = 3),
            InsightHistoryRecord(day = dates[3], sourceSku = "A", sales30 = 4),
            InsightHistoryRecord(day = dates[0], sourceSku = "B", sales30 = 999),
        )
        assertEquals(listOf(10L, 0L, null, null), insightHistoryPoints(rows, dates, "A", 30))
    }

    @Test
    fun queryPaginationKeepsServerTotalsAndMetricChangesAreLocal() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            assertEquals(listOf(1L, 2L), vm.state.value.filter.warehouses)
            vm.metrics(listOf("sales7", "available"))
            runCurrent()
            assertEquals(1, source.queries.size)
            vm.more()
            runCurrent()
            assertEquals("fixed", source.queries.last()["snapshot_id"]?.jsonPrimitive?.content)
            assertEquals(2, vm.state.value.data?.items?.size)
            assertEquals(500L, vm.state.value.data?.totals?.available)
            vm.applyFilter(InsightFilter(warehouses = emptyList()))
            runCurrent()
            assertNull(vm.state.value.data)
            assertEquals(2, source.queries.size)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun searchDebouncesAndRejectsLateResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            val late = CompletableDeferred<InsightPage>()
            source.response = { q ->
                if (q["product"]?.jsonPrimitive?.content == "B1") {
                    withContext(NonCancellable) { late.await() }
                } else {
                    InsightPage(snapshotId = "new", items = listOf(InsightRow(key = "B2")))
                }
            }
            vm.product("B")
            advanceTimeBy(200)
            vm.product("B1")
            advanceTimeBy(399)
            runCurrent()
            assertEquals(1, source.queries.size)
            advanceTimeBy(1)
            runCurrent()
            vm.product("B2", true)
            runCurrent()
            assertEquals("B2", vm.state.value.data?.items?.single()?.key)
            late.complete(InsightPage(items = listOf(InsightRow(key = "wrong"))))
            runCurrent()
            assertEquals("B2", vm.state.value.data?.items?.single()?.key)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resumeCommitsPendingSearchAndWarehouseChangesHideOldRowsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            vm.product("B1")
            vm.stop()
            vm.enter(session())
            runCurrent()
            assertEquals("B1", source.queries.last()["product"]?.jsonPrimitive?.content)
            val next = CompletableDeferred<InsightPage>()
            source.directory = listOf(InsightWarehouse(1, "A仓"))
            source.response = { next.await() }
            vm.refresh()
            runCurrent()
            assertNull(vm.state.value.data)
            next.complete(InsightPage(snapshotId = "single", items = listOf(InsightRow(key = "allowed")), total = 1))
            runCurrent()
            assertEquals("allowed", vm.state.value.data?.items?.single()?.key)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun revokedPermissionsClearDataAndNamespacesHaveSeparatePreferences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val store = Store()
            val vm = PurchaseInsightViewModel(source, store)
            vm.enter(session())
            runCurrent()
            vm.metrics(listOf("sales7"))
            vm.applyFilter(InsightFilter(warehouses = listOf(2)))
            runCurrent()
            vm.enter(session(2))
            runCurrent()
            assertEquals(defaultInsightMetrics, vm.state.value.metrics)
            assertEquals(listOf(1L, 2L), vm.state.value.filter.warehouses)
            vm.enter(session())
            runCurrent()
            assertEquals(listOf("sales7"), vm.state.value.metrics)
            assertEquals(listOf(2L), vm.state.value.filter.warehouses)
            source.directory = listOf(InsightWarehouse(1, "A仓"))
            vm.refresh()
            runCurrent()
            assertEquals(emptyList(), vm.state.value.filter.warehouses)
            assertNull(vm.state.value.data)
            vm.enter(session(permissions = emptyList()))
            runCurrent()
            assertFalse(vm.state.value.allowed)
            assertNull(vm.state.value.data)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun expiredPaginationReloadsFirstPageWithoutMixingVersions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = PurchaseInsightViewModel(source, Store())
            vm.enter(session())
            runCurrent()
            source.response = { q ->
                if (q["page"]?.jsonPrimitive?.int == 2) throw FscmApiException("expired", 409)
                InsightPage(snapshotId = "replacement", items = listOf(InsightRow(key = "new")), total = 1)
            }
            vm.more()
            runCurrent()
            assertEquals(listOf(1, 2, 1), source.queries.map { it["page"]?.jsonPrimitive?.int })
            assertEquals("replacement", vm.state.value.data?.snapshotId)
            assertEquals("new", vm.state.value.data?.items?.single()?.key)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detailSelectsFirstWarehouseWithHistoryAndNeverMergesSourceAliases() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = PurchaseInsightViewModel(source, Store())
            source.historyResponse = { scope, _, dates ->
                InsightHistory(
                    if (scope == 11L) {
                        emptyList()
                    } else {
                        listOf(
                            InsightHistoryRecord(day = LocalDate.now(ZoneId.of("Asia/Bangkok")).toString(), sourceSku = "A", sales30 = 1),
                            InsightHistoryRecord(day = LocalDate.now(ZoneId.of("Asia/Bangkok")).toString(), sourceSku = "B", sales30 = 2),
                        )
                    },
                    emptyList(),
                    emptyList(),
                    dates,
                )
            }
            vm.enter(session())
            runCurrent()
            vm.openDetail(vm.state.value.data!!.items.first())
            runCurrent()
            assertEquals(2L, vm.state.value.historyWarehouse)
            assertEquals("", vm.state.value.historySource)
            vm.historySource("A")
            assertEquals(1L, insightHistoryPoints(vm.state.value.history!!.records, vm.state.value.history!!.dates, "A", 30).last())
            vm.closeDetail()
            assertNotNull(vm.state.value.data)
            vm.stop()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
