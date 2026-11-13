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

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.InsightCatalog
import com.google.samples.apps.nowinandroid.fscm.data.InsightDemandForecast
import com.google.samples.apps.nowinandroid.fscm.data.InsightDemandMonth
import com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistory
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistoryCoverage
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
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.NumberFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseInsightScreenTest {
    @get:Rule val compose = createComposeRule()
    private val session = SessionState(token = "test", user = UserProfile(id = 7), currentNamespace = NamespaceProfile(id = 1), permissions = listOf("purchase-insights"))
    private val row = InsightRow(
        key = "a", sku = "TB1-BLACK-EXTRA-LONG-SKU-123456", localSku = "B1", localSkuId = 9,
        salesQuantities = mapOf("30" to 1280, "7" to 360, "15" to 700), available = 320, transit = 600, onHand = 800, allocated = 480,
    )
    private val source = object : PurchaseInsightSource {
        override suspend fun demandSettings(binding: Long, product: Long, year: Int) = com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings(bindingId = binding, productId = product, year = year)
        override suspend fun saveDemandSettings(value: com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings) = value

        override suspend fun catalog(scope: String, refresh: Boolean) = InsightCatalog(listOf(InsightWarehouse(1, "泰国仓")))
        override suspend fun query(body: JsonObject) = InsightPage(
            snapshotId = "fixed",
            sourceTimezone = "Asia/Bangkok",
            fetchedAt = "2026-10-25 10:30",
            items = listOf(row, row.copy(key = "b", sku = "TB1-WHITE-L")),
            total = 2,
            totals = InsightRow(skuCount = 2, salesQuantities = mapOf("30" to 43659), available = 165091, transit = 8200),
        )
        override suspend fun detail(ids: List<Long>, snapshot: String, key: String) = row
        override suspend fun scopes() = listOf(InsightHistoryScope(1, 1, "泰国仓"))
        override suspend fun history(scope: Long, sku: String, dates: List<String>) = InsightHistory(
            dates.mapIndexed { index, date -> InsightHistoryRecord(day = date, sourceSku = row.sku, sales30 = if (index % 6 == 0) null else 100L + index * 5) },
            listOf(InsightHistoryCoverage(dates[dates.lastIndex - 1], "Asia/Bangkok", "2026-10-25T10:30:00+07:00")),
            emptyList(),
            dates,
        )
    }
    private val vm = PurchaseInsightViewModel(
        source,
        object : PurchaseInsightPreferences {
            override suspend fun read(scope: String) = InsightPreferences()
            override suspend fun save(scope: String, value: InsightPreferences) = Unit
        },
    )

    @Test
    fun searchTotalsFilterAndDetailAreUsable() {
        render()
        compose.onNodeWithText("采购洞察").assertIsDisplayed()
        compose.onNodeWithTag("insight-search").assertIsDisplayed()
        compose.onAllNodesWithText("320")[0].assertIsDisplayed()
        capture("list-360")
        compose.onNodeWithTag("insight-totals").performClick()
        compose.onNodeWithText("165,091").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithTag("insight-filter").performClick()
        compose.onNodeWithText("有销量").performScrollTo().performClick()
        compose.onNodeWithText("应用").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.filter.hasSales)
        compose.onNodeWithTag("insight-row-a").performClick()
        compose.onNodeWithText("SKU 详情").assertIsDisplayed()
        compose.onNodeWithTag("insight-detail").performScrollToNode(hasText("销量曲线 · 单仓滚动销量"))
        compose.onNodeWithText("销量曲线 · 单仓滚动销量").assertIsDisplayed()
        compose.onNodeWithTag("insight-detail").performScrollToNode(hasTestTag("insight-chart"))
        compose.onNodeWithTag("insight-chart").performTouchInput { click(centerLeft) }
        compose.onNodeWithText(vm.state.value.history!!.dates.first()).assertExists()
        capture("curve")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("insight-search").assertIsDisplayed()
        assertTrue(vm.state.value.filter.hasSales)
    }

    @Test
    fun selectionSupportsSixItemsAndRestoresDefaults() {
        render()
        compose.onNodeWithTag("insight-metrics").performClick()
        listOf("7天销量", "昨天", "当前库存").forEach { compose.onNodeWithText(it).performScrollTo().performClick() }
        compose.onNodeWithText("展示项 · 已选 6/6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("应用").performScrollTo().performClick()
        assertEquals(6, vm.state.value.metrics.size)
        capture("six-metrics")
        compose.onNodeWithTag("insight-metrics").performClick()
        compose.onNodeWithText("恢复默认").performScrollTo().performClick()
        compose.onNodeWithText("应用").performScrollTo().performClick()
        assertEquals(defaultInsightMetrics, vm.state.value.metrics)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp")
    fun narrowScreenLargeTypeCanReachActions() {
        render(1.5f)
        compose.runOnIdle { vm.metrics(listOf("sales30", "available", "transit", "sales7", "salesyesterday", "onHand")) }
        capture("list-320-large")
        compose.onNodeWithTag("insight-filter").performClick()
        compose.onNodeWithText("应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithTag("insight-search").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h850dp")
    fun mediumPhoneShowsCompleteNumbers() {
        render()
        compose.onAllNodesWithText("320")[0].assertIsDisplayed()
        capture("list-393")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w430dp-h900dp")
    fun widePhoneShowsCompleteNumbers() {
        render()
        compose.onAllNodesWithText("320")[0].assertIsDisplayed()
        capture("list-430")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp")
    fun demandChineseSmallLargeType() = demandSheets("zh")

    @Test
    @Config(qualifiers = "en-w320dp-h800dp")
    fun demandEnglishSmallLargeType() = demandSheets("en")

    @Test
    @Config(qualifiers = "th-w320dp-h800dp")
    fun demandThaiSmallLargeType() = demandSheets("th")

    @Test
    @Config(qualifiers = "my-w320dp-h800dp")
    fun demandBurmeseSmallLargeType() = demandSheets("my")

    @Test
    @Config(qualifiers = "en-w320dp-h800dp")
    fun cleanEnglishListLargeType() = cleanList("en")

    @Test
    @Config(qualifiers = "th-w320dp-h800dp")
    fun cleanThaiListLargeType() = cleanList("th")

    @Test
    @Config(qualifiers = "my-w320dp-h800dp")
    fun cleanBurmeseListLargeType() = cleanList("my")

    private fun cleanList(language: String) {
        render(1.5f)
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        compose.onNodeWithText(resources.getString(R.string.pi_title)).assertIsDisplayed()
        compose.onNodeWithTag("insight-search").assertIsDisplayed()
        compose.onNodeWithTag("insight-list").performScrollToNode(hasTestTag("insight-row-a"))
        val expectedSales = NumberFormat.getNumberInstance().format(1280)
        compose.onNodeWithTag("insight-list").performScrollToNode(hasText(expectedSales))
        compose.onAllNodesWithText(expectedSales)[0].assertIsDisplayed()
        capture("clean-$language-320-large")
        compose.onNodeWithTag("insight-row-actions-a").performClick()
        compose.onNodeWithText(resources.getString(R.string.pi_demand_basis)).assertIsDisplayed()
    }

    private fun demandSheets(language: String) {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val settings = mutableStateOf(false)
        val forecast = InsightDemandForecast(start = "2026-11-13T12:00:00+07:00", timezone = "Asia/Bangkok", method = "daily_damped", confidence = "high", months = listOf(InsightDemandMonth("2026-11", 1.8, 1.86, "product_manual")))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                FscmTheme {
                    if (settings.value) {
                        InsightDemandSettingsSheet(PurchaseInsightState(demandSettings = InsightDemandSettings(bindingId = 1, year = 2026)), vm)
                    } else {
                        InsightDemandForecastSheet(row.copy(demand = 5580.0, forecastModel = forecast)) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(resources.getString(R.string.pi_demand_basis)).assertIsDisplayed()
        capture("demand-$language-320-large")
        compose.onNodeWithText(resources.getString(R.string.pi_demand_product_manual)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { settings.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(resources.getString(R.string.pi_demand_settings)).assertIsDisplayed()
        capture("demand-settings-$language-320-large")
        compose.onNodeWithText(resources.getString(R.string.pi_demand_month, 12)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.pi_demand_save)).performScrollTo().assertIsDisplayed()
    }

    private fun render(scale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                FscmTheme {
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { vm.enter(session) }
                    DisposableEffect(Unit) { onDispose { vm.stop() } }
                    PurchaseInsightContent(state, vm, {})
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val file = File("build/outputs/purchase-insights/$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
