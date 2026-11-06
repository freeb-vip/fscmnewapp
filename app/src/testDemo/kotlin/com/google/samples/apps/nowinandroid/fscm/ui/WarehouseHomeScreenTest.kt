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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkComparison
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkDay
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.UserProfile
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseMetric
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchPresentation
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WarehouseHomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private var opened = ""
    private val summary = WarehouseWorkbench(
        scope = "workspace",
        date = "2026-10-09",
        timezone = "Asia/Bangkok",
        capturedAt = "2026-10-09T07:32:00Z",
        status = "ready",
        metric = WarehouseMetric(count = 1200, efficiency = 80.0, available = true, efficiencyAvailable = true),
        yesterdayDate = "2026-10-08",
        yesterdayMetric = WarehouseMetric(count = 1000, efficiency = 100.0, available = true, efficiencyAvailable = true),
        comparison = RealtimeWorkComparison(
            cutoff = "14:00",
            available = true,
            current = RealtimeWorkDay(count = 600, efficiency = 40.0),
            previous = RealtimeWorkDay(count = 500, efficiency = 50.0),
        ),
    )

    @Test
    fun summaryAndLargeAppTilesKeepTheOriginalSimpleLayout() {
        render()
        compose.onNodeWithText("今日全仓作业").assertIsDisplayed()
        compose.onNodeWithText("1,200").assertIsDisplayed()
        compose.onNodeWithText("80.00").assertIsDisplayed()
        compose.onNodeWithText("昨日同时段 500").assertIsDisplayed()
        compose.onNodeWithText("↑ 20.0%").assertIsDisplayed()
        compose.onNodeWithText("↓ 20.0%").assertIsDisplayed()
        assertTwoColumns()
        compose.onNodeWithText("开始作业").assertDoesNotExist()
        compose.onNodeWithText("其他应用").assertDoesNotExist()
        compose.onNodeWithText("入库管理").performClick()
        assertEquals("inbound_management", opened)
        capture("home-360")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp")
    fun smallPdaWithLargeTypeStillUsesTwoColumns() {
        render(1.3f)
        assertTwoColumns()
        capture("home-320-large-type")
    }

    @Test
    fun refreshFailurePreservesTodayAndYesterday() {
        render(state = LoadState(data = summary.copy(cached = true), error = UiText.Dynamic("network error")))
        compose.onNodeWithText("1,200").assertIsDisplayed()
        compose.onNodeWithText("80.00").assertIsDisplayed()
        compose.onNodeWithText("昨日同时段 500").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        capture("home-cached")
    }

    private fun assertTwoColumns() {
        val left = compose.onNodeWithText("仓库收货").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithText("入库管理").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(left.right < right.left)
        assertEquals(left.top, right.top)
        assertTrue(right.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
    }

    private fun render(fontScale: Float = 1f, state: LoadState<WarehouseWorkbench> = LoadState(data = summary)) {
        RuntimeEnvironment.setFontScale(fontScale)
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
            ) {
                FscmTheme {
                    WarehouseStyleScreen(
                        session = SessionState(user = UserProfile(id = 7, username = "仓库操作员"), currentNamespace = NamespaceProfile(id = 1, name = "FSCM 仓库")),
                        presentation = WorkbenchPresentation(
                            style = "warehouse",
                            homeAppKeys = listOf("receiving", "inbound_management", "central_receiving", "product_counting"),
                            apps = listOf(
                                MobileAppEntry(key = "receiving", title = "仓库收货"),
                                MobileAppEntry(key = "inbound_management", title = "入库管理"),
                                MobileAppEntry(key = "central_receiving", title = "集运收货"),
                                MobileAppEntry(key = "product_counting", title = "产品盘点"),
                            ),
                        ),
                        state = state,
                        onProfile = {},
                        onRefresh = {},
                        onOpenApp = { opened = it.key },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val file = File("build/outputs/warehouse-home/$name.png")
        file.parentFile.mkdirs()
        compose.onRoot().captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
