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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.DailyOverview
import com.google.samples.apps.nowinandroid.fscm.data.DailySummary
import com.google.samples.apps.nowinandroid.fscm.data.MobileTodo
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.OperationsWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeDay
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeShop
import com.google.samples.apps.nowinandroid.fscm.data.SalesAccount
import com.google.samples.apps.nowinandroid.fscm.data.SalesComparison
import com.google.samples.apps.nowinandroid.fscm.data.SalesModuleState
import com.google.samples.apps.nowinandroid.fscm.data.SalesOverview
import com.google.samples.apps.nowinandroid.fscm.data.SalesSummary
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchModule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperationsHomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private var openedTodo = 0L
    private var openedAll = false

    @Test
    fun homeShowsYesterdayTotalAndSameTimeChangeAndEveryTodoOpensItsOwnDetail() {
        renderHome()
        compose.onNodeWithText("128,640.25").assertIsDisplayed()
        compose.onNodeWithText("昨日全天  150,000.00").assertIsDisplayed()
        compose.onNodeWithText("较昨日同时刻  ↑ +7.20%").assertIsDisplayed()
        assertNoSourceOrTime()
        compose.onNodeWithText("开始作业").assertDoesNotExist()
        capture("operations-home-360")
        for (index in 1..3) {
            scrollTo("待办 $index：核对店铺经营数据")
            compose.onNodeWithText("待办 $index：核对店铺经营数据").performClick()
            assertEquals(index.toLong(), openedTodo)
        }
        compose.onNodeWithText("待办 4：核对店铺经营数据").assertDoesNotExist()
        scrollTo("查看全部")
        compose.onNodeWithText("查看全部").performClick()
        assertTrue(openedAll)
        capture("operations-todos-360")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp")
    fun largeTypeOnSmallScreenKeepsMetricsAndLongTodosReadable() {
        renderHome(fontScale = 1.5f)
        compose.onNodeWithText("128,640.25").assertIsDisplayed()
        scrollTo("842")
        val amount = compose.onNodeWithText("128,640.25", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val orders = compose.onNodeWithText("842", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(orders.top > amount.top)
        capture("operations-home-320-large-type")
        scrollTo("待办 3：核对店铺经营数据")
        compose.onNodeWithText("待办 3：核对店铺经营数据").performClick()
        assertEquals(3L, openedTodo)
        capture("operations-todos-320-large-type")
    }

    @Test
    fun accountSwitchDoesNotOpenDetailAndSheetContainsOnlyAccountNames() {
        var opened = false
        var selected = ""
        render {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                RealtimeHomeCard(realtime(), { opened = true }, { selected = it }, {})
            }
        }
        compose.onNodeWithText("运营账户 A").assertDoesNotExist()
        compose.onNodeWithTag("realtime-account-switch").performClick()
        assertFalse(opened)
        compose.onNodeWithText("BigSeller").assertDoesNotExist()
        compose.onNodeWithText("心舰").assertDoesNotExist()
        compose.onNodeWithText("运营账户 B").performClick()
        assertEquals("b", selected)
        assertFalse(opened)
        compose.onNodeWithText("今日数据").performClick()
        assertTrue(opened)
    }

    @Test
    fun readyEmptyTodosShowAnExplicitEmptyStateAndNoPermissionHidesModules() {
        renderHome(
            data = OperationsWorkbench(todos = WorkbenchModule(status = "ready", data = emptyList())),
            live = RealtimeUiState(),
        )
        compose.onNodeWithText("暂无待办").assertIsDisplayed()
        compose.onNodeWithText("今日数据").assertDoesNotExist()
        compose.onNodeWithText("昨日运营日报").assertDoesNotExist()
        compose.onNodeWithText("查看全部").performClick()
        assertTrue(openedAll)
    }

    @Test
    fun detailRetainsRankingWithoutLeakingSourceOrTimeMetadata() {
        render { RealtimeDataScreen(realtime(), {}, {}) }
        compose.onNodeWithText("昨日全天  150,000.00").assertIsDisplayed()
        compose.onNodeWithText("较昨日同时刻  ↑ +7.20%").assertIsDisplayed()
        assertNoSourceOrTime()
        compose.onNodeWithTag("realtime-list").performScrollToNode(hasText("示例店铺"))
        compose.onNodeWithText("示例店铺").assertIsDisplayed()
        compose.onNodeWithText("BigSeller 同步中").assertDoesNotExist()
        capture("realtime-detail-360")
    }

    private fun renderHome(fontScale: Float = 1f, data: OperationsWorkbench = operations(), live: RealtimeUiState = realtime()) {
        render(fontScale) {
            OperationsStyleScreen(
                session = SessionState(currentNamespace = NamespaceProfile(id = 1, name = "FSCM 运营工作台")),
                state = LoadState(data = data), realtimeState = live,
                onRealtime = {}, onRealtimeSelect = {}, onSearch = {}, onDaily = {},
                onTodos = { openedAll = true }, onTodo = { openedTodo = it }, onRefresh = {},
            )
        }
    }

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag("zh-Hans"))
                this.fontScale = fontScale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context.createConfigurationContext(configuration),
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) { FscmTheme(content = content) }
        }
    }

    private fun scrollTo(text: String) = compose.onNodeWithTag("operations-home").performScrollToNode(hasText(text))

    private fun assertNoSourceOrTime() {
        listOf("BigSeller", "心舰", "2026-10-09", "北京时间", "UTC+8", "Asia/Shanghai", "更新于", "来源返回范围").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private fun realtime() = RealtimeUiState(
        allowed = true,
        accountsLoaded = true,
        selectedId = "a",
        accounts = listOf(SalesAccount("a", "运营账户 A", "bigseller"), SalesAccount("b", "运营账户 B", "xinjian")),
        sales = SalesOverview(
            date = "2026-10-09",
            timezone = "Asia/Shanghai",
            connectionId = "a",
            summary = SalesSummary(842, "128640.25"),
            comparison = SalesComparison(mode = "current_time", current = SalesSummary(842, "128640.25"), previous = SalesSummary(700, "120000")),
            week = listOf(RealtimeDay("2026-10-08", 1200, "150000")),
            shops = listOf(RealtimeShop(id = "shop", name = "示例店铺", orders = 842, amount = "128640.25")),
            modules = mapOf("summary" to SalesModuleState(status = "ready", fetchedAt = "2026-10-09T05:00:00Z"), "shops" to SalesModuleState(status = "pending", reason = "BigSeller 同步中")),
        ),
    )

    private fun operations() = OperationsWorkbench(
        daily = WorkbenchModule(status = "ready", data = DailyOverview("2026-10-08", DailySummary(saleAmount = "150000", profit = "24680.50", profitMargin = "0.1645", totalOrders = 1200))),
        todos = WorkbenchModule(status = "ready", data = (1..4).map { MobileTodo(id = it.toLong(), title = "待办 $it：核对店铺经营数据", description = "核对销售、订单和利润，查看详细处理说明。", status = "pending") }),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val file = File("build/outputs/operations-home/$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
