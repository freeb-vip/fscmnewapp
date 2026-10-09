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
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.DailyDateMode
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyCost
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyDay
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyFilter
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyFilterStore
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyOverview
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPage
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPreferences
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyShop
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySource
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySummary
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperatingDailyScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = SessionState(user = UserProfile(id = 7), currentNamespace = NamespaceProfile(id = 1), permissions = listOf("mobile-app:profit", "operating-profit-daily"))
    private val shops = listOf(
        OperatingDailyShop(1, "A 店铺", "shopee", "TH", totalOrders = 428, saleAmount = "41860", profit = "8362", profitMargin = "0.1998", status = "已更新"),
        OperatingDailyShop(2, "B 店铺", "tiktok", "TH", totalOrders = 360, saleAmount = "35400", profit = "7800", profitMargin = "0.2203", status = "已更新"),
    )
    private val summary = OperatingDailySummary(totalOrders = 788, saleAmount = "77260", profit = "16162", profitMargin = "0.2092", averageOrderValue = "98.05", days = 2, expectedDays = 2, orderDays = 2, profitDays = 2, sourceUpdatedAt = "2026-10-08T01:35:00Z")
    private val vm = OperatingDailyViewModel(
        object : OperatingDailySource {
            override suspend fun overview(filter: OperatingDailyFilter, range: Pair<String, String>, page: Int, sort: String) = OperatingDailyOverview(summary, shops, shops, 2)
            override suspend fun days(shopId: Long, range: Pair<String, String>, page: Int) = OperatingDailyPage(
                summary = summary.copy(totalOrders = 428, saleAmount = "41860", profit = "8362", profitMargin = "0.1998", averageOrderValue = "97.80", productCost = OperatingDailyCost("20000", "41860", "0.4778", 1), days = 1, reportDays = 1, expectedDays = 1, orderDays = 1, profitDays = 1),
                items = listOf(OperatingDailyDay(shopId = shopId, reportDate = range.first, totalOrders = 428, saleAmount = "41860", profit = "8362", profitMargin = "0.1998", status = "已更新")),
                total = 1,
            )
        },
        object : OperatingDailyPreferences {
            override suspend fun read(scope: String) = OperatingDailyFilter(dateMode = DailyDateMode.Custom, startDate = "2026-10-08", endDate = "2026-10-08")
            override suspend fun save(scope: String, filter: OperatingDailyFilter) = Unit
        },
    )

    @Test
    fun overviewRankingFilterAndDetailAreUsable() {
        render()
        compose.onNodeWithText("运营日报").assertIsDisplayed()
        compose.onNodeWithText("¥77,260.00").assertIsDisplayed()
        capture("overview")
        compose.onNodeWithText("排行榜").performClick()
        compose.onNodeWithText("销售额排行").performClick()
        capture("ranking")
        compose.onNodeWithTag("daily_filter").performClick()
        compose.mainClock.advanceTimeBy(1000)
        capture("filters", sheet = true)
        compose.onNodeWithText("应用筛选").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("daily_list").performScrollToNode(hasText("A 店铺"))
        compose.onNodeWithText("A 店铺").performClick()
        compose.onNodeWithText("费用明细 · 金额 / 比例").assertIsDisplayed()
        capture("detail")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp")
    fun smallScreenWithLargeTypeKeepsFilterActionsVisible() {
        render(1.5f)
        compose.onNodeWithTag("daily_filter").performClick()
        compose.mainClock.advanceTimeBy(1000)
        capture("filters-large-type", sheet = true)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.onNodeWithText("应用筛选").assertIsDisplayed()
    }

    @Test
    fun datastorePersistsModesAndKeepsAccountsSeparate() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val stored = OperatingDailyFilter(dateMode = DailyDateMode.Last7, allPlatforms = false, platforms = setOf("shopee"), allShops = false, shopIds = setOf(7, 12))
        OperatingDailyFilterStore(context, json).save("test-environment|7|1", stored)
        val recreated = OperatingDailyFilterStore(context, json)
        assertEquals(stored, recreated.read("test-environment|7|1"))
        assertEquals(OperatingDailyFilter(), recreated.read("test-environment|8|1"))
        assertEquals(OperatingDailyFilter(), recreated.read("test-environment|7|2"))
    }

    private fun render(fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag("zh-Hans"))
                this.fontScale = fontScale
            }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides localized, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                FscmTheme {
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { vm.enter(session) }
                    OperatingDailyContent(state, vm, {}, { vm.enter(session) })
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, sheet: Boolean = false) {
        val file = File("build/outputs/operating-daily/$name.png")
        file.parentFile.mkdirs()
        (if (sheet) compose.onNodeWithTag("daily_filter_sheet") else compose.onRoot()).captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
