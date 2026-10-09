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

import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.DailyDateMode
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyFilter
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyOverview
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPage
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPreferences
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyShop
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySource
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySummary
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.UserProfile
import com.google.samples.apps.nowinandroid.fscm.data.fscmRepeatedQueryPath
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyScope
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OperatingDailyTest {
    private val permissions = listOf("mobile-app:profit", "operating-profit-daily")
    private fun session(namespace: Long = 1) = SessionState(token = "test", user = UserProfile(id = 7), currentNamespace = NamespaceProfile(id = namespace), permissions = permissions)

    @Test
    fun dateModesRollAcrossYearsButCustomAndCalendarSelectionsRemainFixed() {
        val today = LocalDate.parse("2026-01-01")
        assertEquals("2025-12-31" to "2025-12-31", OperatingDailyFilter().range(today))
        assertEquals("2025-12-25" to "2025-12-31", OperatingDailyFilter(dateMode = DailyDateMode.Last7).range(today))
        val fixed = OperatingDailyFilter(dateMode = DailyDateMode.Custom, startDate = "2025-12-01", endDate = "2025-12-20")
        assertEquals(fixed.range(today), fixed.range(today.plusDays(10)))
        assertEquals("2024-02-01" to "2024-02-29", OperatingDailyFilter(dateMode = DailyDateMode.Month, calendar = "2024-02").range(today))
        assertEquals("2025-01-01" to "2025-12-31", OperatingDailyFilter(dateMode = DailyDateMode.Year, calendar = "2025").range(today))
        assertNull(fixed.copy(endDate = "2026-01-01").range(today))
        assertNull(fixed.copy(startDate = "2024-01-01").range(today))
        assertNull(fixed.copy(startDate = "bad").range(today))
        assertNull(fixed.copy(startDate = "2025-12-21").range(today))
    }

    @Test
    fun nullableAmountsAndRatiosKeepPrecision() {
        val json = Json { ignoreUnknownKeys = true }
        val report = json.decodeFromString<OperatingDailyOverview>("""{"summary":{"saleAmount":"9007199254740993.125","totalOrders":0,"profit":null,"profitMargin":"-0.0201"},"items":[],"shops":[],"total":0,"comparison":{}}""")
        assertEquals("¥9,007,199,254,740,993.13", dailyMoney(report.summary.saleAmount))
        assertEquals("—", dailyMoney(report.summary.profit))
        assertEquals("¥0.00", dailyMoney("0"))
        assertEquals("-2.01%", dailyPercent(report.summary.profitMargin))
        assertEquals(0L, report.summary.totalOrders)
    }

    @Test
    fun repeatedQueryValuesAreEncodedIndividually() {
        val filter = OperatingDailyFilter(allPlatforms = false, platforms = setOf("tiktok", "shop & one"), allShops = false, shopIds = setOf(2, 10))
        val url = ("https://example.invalid/" + fscmRepeatedQueryPath("operating-profit-daily/overview", filter.overviewQuery("2026-01-01" to "2026-01-02", 2, "saleAmount"))).toHttpUrl()
        assertEquals(listOf("shop & one", "tiktok"), url.queryParameterValues("platforms"))
        assertEquals(listOf("2", "10"), url.queryParameterValues("shopIds"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("saleAmount", url.queryParameter("sort"))
    }

    @Test
    fun selectionsDoNotBroadenWhenShopsDisappearAndAllIncludesNewShops() {
        val shops = listOf(OperatingDailyShop(1, platform = "shopee"), OperatingDailyShop(2, platform = "tiktok"))
        val selected = OperatingDailyFilter(allShops = false, shopIds = setOf(1))
        assertTrue(selected.selectionValid(shops))
        assertFalse(selected.selectionValid(shops.drop(1)))
        assertFalse(selected.copy(allPlatforms = false, platforms = setOf("tiktok")).selectionValid(shops))
        assertEquals(2, OperatingDailyFilter().visibleShops(shops).size)
        assertEquals(selected, Json.decodeFromString<OperatingDailyFilter>(Json.encodeToString(selected)))
        assertNotEquals(operatingDailyScope(session(), "https://one/api/"), operatingDailyScope(session(), "https://two/api/"))
        assertNotEquals(operatingDailyScope(session()), operatingDailyScope(session(2)))
    }

    @Test
    fun entryRequiresBothPermissionsAndOnlineSession() {
        val catalog = MobileWorkbenchOverview(apps = listOf(MobileAppEntry(key = "profit", businessPermission = "profit-report:boss")))
        permissions.forEach { assertTrue(visibleMobileApps(catalog, listOf(it)).isEmpty()) }
        assertEquals(1, visibleMobileApps(catalog, permissions).size)
        assertTrue(visibleMobileApps(catalog, permissions, SessionMode.Offline).isEmpty())
    }

    @Test
    fun paginationKeepsWholeScopeSummaryAndReloadKeepsSavedFilters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = Preferences()
            val source = Source()
            val vm = OperatingDailyViewModel(source, prefs)
            vm.enter(session())
            runCurrent()
            assertEquals(20, vm.state.value.data?.items?.size)
            val summary = vm.state.value.data?.summary
            vm.loadMore()
            runCurrent()
            assertEquals(25, vm.state.value.data?.items?.size)
            assertEquals(summary, vm.state.value.data?.summary)
            vm.selectTab(true)
            runCurrent()
            assertEquals("totalOrders", source.sort)
            vm.selectMetric("saleAmount")
            runCurrent()
            assertEquals("saleAmount", source.sort)
            val filter = OperatingDailyFilter(dateMode = DailyDateMode.Last7, allShops = false, shopIds = setOf(1))
            var applied = false
            vm.apply(filter) { applied = true }
            runCurrent()
            assertTrue(applied)
            val reopened = OperatingDailyViewModel(source, prefs)
            reopened.enter(session())
            runCurrent()
            assertEquals(filter, reopened.state.value.filter)
            reopened.enter(session(2))
            runCurrent()
            assertEquals(OperatingDailyFilter(), reopened.state.value.filter)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun expiredSelectionAndPermissionFailureNeverExposeUnfilteredData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = Preferences().apply { values[operatingDailyScope(session())] = OperatingDailyFilter(allShops = false, shopIds = setOf(99)) }
            val source = Source()
            val vm = OperatingDailyViewModel(source, prefs)
            vm.enter(session())
            runCurrent()
            assertNull(vm.state.value.data)
            assertTrue(vm.state.value.selectionInvalid)
            vm.apply(OperatingDailyFilter()) {}
            runCurrent()
            assertEquals(25, vm.state.value.data?.total)
            source.failure = FscmApiException("denied", 403)
            vm.refresh()
            runCurrent()
            assertNull(vm.state.value.data)
            assertTrue(vm.state.value.directory.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun oldRequestsCannotOverwriteNewScopeAndTransientErrorsKeepPreviousResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slow = CompletableDeferred<OperatingDailyOverview>()
            val source = Source().apply { delayed = slow }
            val vm = OperatingDailyViewModel(source, Preferences())
            vm.enter(session())
            runCurrent()
            source.delayed = null
            vm.enter(session(2))
            runCurrent()
            assertEquals(25, vm.state.value.data?.total)
            slow.complete(OperatingDailyOverview(total = 999))
            runCurrent()
            assertEquals(25, vm.state.value.data?.total)
            source.failure = IllegalStateException("offline")
            vm.refresh()
            runCurrent()
            assertEquals(25, vm.state.value.data?.total)
            assertEquals(R.string.od_load_failed, vm.state.value.error)
            source.failure = null
            vm.openShop(source.shops.first())
            runCurrent()
            vm.closeShop()
            assertEquals(25, vm.state.value.data?.total)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Preferences : OperatingDailyPreferences {
        val values = mutableMapOf<String, OperatingDailyFilter>()
        override suspend fun read(scope: String) = values[scope] ?: OperatingDailyFilter()
        override suspend fun save(scope: String, filter: OperatingDailyFilter) {
            values[scope] = filter
        }
    }

    private class Source : OperatingDailySource {
        val shops = (1L..25L).map { OperatingDailyShop(it, "Shop $it", "shopee", profit = if (it == 25L) null else "-1") }
        var sort = ""
        var failure: Exception? = null
        var delayed: CompletableDeferred<OperatingDailyOverview>? = null
        override suspend fun overview(filter: OperatingDailyFilter, range: Pair<String, String>, page: Int, sort: String): OperatingDailyOverview {
            this.sort = sort
            delayed?.let { return withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return OperatingDailyOverview(summary = OperatingDailySummary(saleAmount = "12345.67", profit = "-24"), items = shops.drop((page - 1) * 20).take(20), shops = shops, total = shops.size)
        }
        override suspend fun days(shopId: Long, range: Pair<String, String>, page: Int) = OperatingDailyPage()
    }
}
