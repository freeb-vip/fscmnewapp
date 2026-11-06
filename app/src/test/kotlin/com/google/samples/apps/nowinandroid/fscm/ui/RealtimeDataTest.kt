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
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeDataSource
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkComparison
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkDay
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkSummary
import com.google.samples.apps.nowinandroid.fscm.data.SalesAccount
import com.google.samples.apps.nowinandroid.fscm.data.SalesOverview
import com.google.samples.apps.nowinandroid.fscm.data.SalesSummary
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.UserProfile
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeDataTest {
    private val epoch = Instant.parse("2026-10-09T05:00:00Z").toEpochMilli()
    private val permissions = listOf("realtime-data", "mobile-app:realtime-data")
    private fun session(namespace: Long = 1) = SessionState(token = "fixture", user = UserProfile(id = 1), currentNamespace = NamespaceProfile(id = namespace), permissions = permissions)

    @Test
    fun amountsAndChangesKeepDecimalPrecision() {
        assertEquals("9,007,199,254,740,993.13", realtimeNumber("9007199254740993.125"))
        assertEquals("—", realtimeNumber(null))
        assertEquals("↑ +33.33%", realtimeChange("4", "3").text)
        assertEquals("↓ -50.00%", realtimeChange("1", "2").text)
        assertTrue(realtimeChange("1", "0").isNew)
        assertTrue(realtimeChange("0", "0").flat)
        assertNull(realtimeChange("1", null).text)
        assertEquals("↑ +30.00%", realtimeRatio("0.3").text)
    }

    @Test
    fun catalogRequiresBothPermissionsAndOnlineMode() {
        val overview = MobileWorkbenchOverview(apps = listOf(MobileAppEntry(key = "realtime_data")))
        assertEquals(AppGlyph.RealtimeData, resolveAppGlyph(MobileAppEntry(key = "realtime_data")))
        for (permission in permissions) assertTrue(visibleMobileApps(overview, listOf(permission)).isEmpty())
        assertEquals(1, visibleMobileApps(overview, permissions).size)
        assertTrue(visibleMobileApps(overview, permissions, SessionMode.Offline).isEmpty())
    }

    @Test
    fun workVisibilityAndRetentionPreserveCompleteTimestampsOnlyWithinSameDayAndZone() {
        val old = work(packed = 20, efficiency = 2.5)
        val missing = work(packed = 30, efficiency = null)
        assertTrue(RealtimeUiState(work = missing).showWork)
        assertFalse(RealtimeUiState(work = work(packed = 0, efficiency = 0.0)).showWork)
        val kept = retainCompleteWork(old, missing)
        assertEquals(old.today.copy(status = "delayed"), kept.today)
        assertSame(missing, retainCompleteWork(null, missing))
        val tomorrow = missing.copy(today = missing.today.copy(date = "2026-10-10"))
        assertSame(tomorrow, retainCompleteWork(old, tomorrow))
        val otherZone = missing.copy(today = missing.today.copy(timezone = "Asia/Bangkok"))
        assertSame(otherZone, retainCompleteWork(old, otherZone))
    }

    @Test
    fun comparisonRetentionNeverMixesHoursOrScopes() {
        val old = work().copy(comparison = RealtimeWorkComparison(cutoff = "14:00", available = true, current = work().today, previous = work().yesterday))
        val missing = work().copy(comparison = RealtimeWorkComparison(cutoff = "14:00"))
        val kept = retainCompleteWork(old, missing)
        assertEquals(old.comparison, kept.comparison)
        assertEquals("delayed", kept.today.status)
        val nextHour = missing.copy(comparison = RealtimeWorkComparison(cutoff = "15:00"))
        assertSame(nextHour, retainCompleteWork(old, nextHour))
        val otherScope = missing.copy(today = missing.today.copy(scope = "changed", efficiency = null))
        assertSame(otherScope, retainCompleteWork(old, otherScope))
        val legacy = RealtimeWorkSummary(RealtimeWorkDay(packed = 100), RealtimeWorkDay())
        assertFalse(RealtimeUiState(work = legacy).showWork)
    }

    @Test
    fun contractIncludesNullableRanksAndComparisonMode() {
        val data = Json { ignoreUnknownKeys = true }.decodeFromString<SalesOverview>(
            """{"connectionId":"a","date":"2026-10-09","timezone":"Asia/Shanghai","summary":{"amount":null,"orders":0},"comparison":{"mode":"current_time"},"shops":[{"id":"s","ordersChangeRatio":"0.3"}],"products":[{"id":"p","sku":"SKU","quantity":null}],"week":[{"date":"2026-10-08","amount":"123.45"}],"modules":{"summary":{"status":"delayed","reason":"source","sourceUpdatedAt":null}}}""",
        )
        assertNull(data.summary.amount)
        assertEquals(0L, data.summary.orders)
        assertEquals("current_time", data.comparison.mode)
        assertEquals("0.3", data.shops.single().ordersChangeRatio)
        assertNull(data.products.single().quantity)
        assertEquals("123.45", data.yesterdayTotal()?.amount)
        assertEquals("source", data.modules["summary"]?.reason)
    }

    @Test
    fun visibleLifecycleRefreshesOnceAndStopsInBackground() = runTest {
        val source = Source()
        val controller = RealtimeDataController(source, backgroundScope) { epoch + testScheduler.currentTime }
        controller.bind(session())
        val visible = backgroundScope.launch { controller.follow() }
        runCurrent()
        assertEquals(1, source.salesCalls)
        assertEquals(1, source.workCalls)
        advanceTimeBy(REALTIME_INTERVAL)
        runCurrent()
        assertEquals(2, source.salesCalls)
        visible.cancelAndJoin()
        advanceTimeBy(REALTIME_INTERVAL * 2)
        assertEquals(2, source.salesCalls)
        backgroundScope.launch { controller.follow() }
        runCurrent()
        assertEquals(3, source.salesCalls)
    }

    @Test
    fun concurrentRefreshesCoalesceWhileSalesAndWorkFailIndependently() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply {
            salesLoader = {
                gate.await()
                throw FscmApiException("unavailable", 503)
            }
        }
        val controller = RealtimeDataController(source, backgroundScope) { epoch }
        controller.bind(session())
        backgroundScope.launch { controller.follow() }
        runCurrent()
        repeat(3) { controller.refresh() }
        runCurrent()
        assertEquals(1, source.salesCalls)
        assertNotNull(controller.state.value.work)
        gate.complete(Unit)
        runCurrent()
        assertNotNull(controller.state.value.salesError)
        assertTrue(controller.state.value.showWork)
    }

    @Test
    fun switchingAccountRejectsLateOldResponseAndDoesNotReloadWork() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply {
            salesLoader = { id ->
                if (id == "a") withContext(NonCancellable) { gate.await() }
                sales(id)
            }
        }
        val controller = RealtimeDataController(source, backgroundScope) { epoch }
        controller.bind(session())
        backgroundScope.launch { controller.follow() }
        runCurrent()
        controller.select("b")
        runCurrent()
        assertEquals("b", controller.state.value.sales?.connectionId)
        assertEquals(1, source.workCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals("b", controller.state.value.sales?.connectionId)
        assertEquals("b", source.saved[1])
    }

    @Test
    fun permissionsAndNamespaceChangesClearOldStateAndRestoreScopedSelection() = runTest {
        val source = Source().apply { saved[2] = "b" }
        val controller = RealtimeDataController(source, backgroundScope) { epoch }
        controller.bind(session())
        var visible = backgroundScope.launch { controller.follow() }
        runCurrent()
        assertNotNull(controller.state.value.sales)
        visible.cancelAndJoin()
        controller.bind(session(2))
        assertNull(controller.state.value.sales)
        assertNull(controller.state.value.work)
        visible = backgroundScope.launch { controller.follow() }
        runCurrent()
        assertEquals("b", controller.state.value.sales?.connectionId)
        visible.cancelAndJoin()
        controller.bind(session(2).copy(permissions = listOf("realtime-data")))
        assertFalse(controller.state.value.allowed)
        assertNull(controller.state.value.sales)
        assertNull(controller.state.value.work)
    }

    @Test
    fun salesAndWorkRollOverAccordingToTheirOwnTimezones() = runTest {
        val beforeMidnight = Instant.parse("2026-10-09T15:59:59Z").toEpochMilli()
        val source = Source().apply { workData = work().let { it.copy(today = it.today.copy(timezone = "Asia/Bangkok")) } }
        val controller = RealtimeDataController(source, backgroundScope) { beforeMidnight + testScheduler.currentTime }
        controller.bind(session())
        backgroundScope.launch { controller.follow() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(controller.state.value.sales)
        assertNotNull(controller.state.value.work)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertNull(controller.state.value.work)
    }

    @Test
    fun errorsRetainSameDayDataButForbiddenClearsIt() = runTest {
        val source = Source()
        val controller = RealtimeDataController(source, backgroundScope) { epoch }
        controller.bind(session())
        backgroundScope.launch { controller.follow() }
        runCurrent()
        source.salesLoader = { throw FscmApiException("delayed", 503) }
        source.workData = work(packed = 8, efficiency = null)
        controller.refresh()
        runCurrent()
        assertNotNull(controller.state.value.sales)
        assertEquals(2.5, controller.state.value.work?.today?.efficiency)
        source.salesLoader = { throw FscmApiException("forbidden", 403) }
        source.workFailure = FscmApiException("forbidden", 403)
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.sales)
        assertNull(controller.state.value.work)
    }

    @Test
    fun removedAccountSelectsFirstValidAndNoAccountsStillAllowsWork() = runTest {
        val source = Source().apply { saved[1] = "removed" }
        val controller = RealtimeDataController(source, backgroundScope) { epoch }
        controller.bind(session())
        backgroundScope.launch { controller.follow() }
        runCurrent()
        assertEquals("a", controller.state.value.selectedId)
        source.accountList = emptyList()
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.sales)
        assertTrue(controller.state.value.showWork)
    }

    private fun sales(id: String) = SalesOverview(connectionId = id, date = "2026-10-09", timezone = "Asia/Shanghai", summary = SalesSummary(orders = 10, amount = "123.45"))
    private fun work(packed: Long? = 20, efficiency: Double? = 2.5) = RealtimeWorkSummary(
        today = RealtimeWorkDay("2026-10-09", "Asia/Shanghai", packed, efficiency, "ready", "2026-10-09T04:00:00Z", "2026-10-09T04:01:00Z", count = packed, basis = "orders_and_replenishments", scope = "scope"),
        yesterday = RealtimeWorkDay("2026-10-08", "Asia/Shanghai", 100, 3.0),
    )

    private inner class Source : RealtimeDataSource {
        var accountList = listOf(SalesAccount(id = "a"), SalesAccount(id = "b"))
        var salesCalls = 0
        var workCalls = 0
        var salesLoader: suspend (String) -> SalesOverview = { sales(it) }
        var workData = work()
        var workFailure: Exception? = null
        val saved = mutableMapOf<Long, String>()
        override suspend fun accounts() = accountList
        override suspend fun overview(connectionId: String): SalesOverview {
            salesCalls++
            return salesLoader(connectionId)
        }
        override suspend fun workSummary(): RealtimeWorkSummary {
            workCalls++
            workFailure?.let { throw it }
            return workData
        }
        override suspend fun selectedAccount(session: SessionState) = saved[session.currentNamespace?.id]
        override suspend fun selectAccount(session: SessionState, id: String) {
            saved[session.currentNamespace!!.id] = id
        }
    }
}
