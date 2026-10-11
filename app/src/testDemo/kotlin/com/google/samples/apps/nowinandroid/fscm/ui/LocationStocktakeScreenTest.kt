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
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.CountTask
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeOption
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeResults
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeContext
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeLookup
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeStock
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeWorkspace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "zh-rCN-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LocationStocktakeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val location = "PICK-A-001-VERY-LONG-LOCATION-2026"
    private val sku = "SKU-2026-VERY-LONG-PRODUCT-CODE-001"
    private val stock = StocktakeStock("row", "location", location, sku, onHand = 201, unit = "PCS")
    private var state by mutableStateOf(
        LocationStocktakeUiState(
            allowed = true, canWrite = true, userId = 7, showTasks = false,
            task = CountTask("task", sku = sku, status = "in_progress", claimedBy = 7, unit = "PCS"),
            context = StocktakeContext(StocktakeWorkspace(status = "configured"), writeEnabled = true),
            lookup = StocktakeLookup("ready", stock, "snapshot", Instant.now().plusSeconds(120).toString()),
            location = location, sku = sku, quantity = "201", stage = StocktakeStage.Count,
        ),
    )

    @Test
    @Config(sdk = [28, 36], qualifiers = "zh-rCN-w320dp-h480dp")
    fun partialInputChoosesFullOfflineCodeWithoutSubmittingTypedPrefix() {
        var manual by mutableStateOf(StocktakeManualSearch())
        var submitted = ""
        var refreshes = 0
        val option = OfflineCodeOption(location, "Warehouse A")
        compose.setContent {
            FscmTheme {
                LazyColumn {
                    item {
                        StocktakeManualCodeInput(
                            manual,
                            true,
                            false,
                            false,
                            onInput = { manual = StocktakeManualSearch(it, OfflineCodeResults(listOf(option), true)) },
                            onSubmit = { submitted = it },
                            onCamera = {},
                            onRefresh = { refreshes++ },
                        )
                    }
                    if (manual.result.items.isNotEmpty()) {
                        item {
                            StocktakeOfflineOption(option, true) { submitted = option.code }
                        }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("PICK")
        compose.runOnIdle { assertEquals("", submitted) }
        compose.onNodeWithText(location).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(location, submitted) }
        compose.onNodeWithText(compose.activity.getString(com.google.samples.apps.nowinandroid.R.string.st_offline_refresh)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun smallPdaRetainsQuickEntryAfterScrollingAndBlocksKeyboardScannerInQuantity() {
        render()
        val before = compose.onNodeWithText("快速盘点").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(location).assertIsDisplayed()
        compose.onNodeWithText(sku).assertIsDisplayed()
        capture("target-320")
        compose.onNodeWithText("完成盘点").performScrollTo().assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("快速盘点").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("设为0").performClick()
        assertEquals("0", state.quantity)
        val quantity = compose.onNode(hasSetTextAction())
        quantity.performTextReplacement("12")
        assertEquals("12", state.quantity)
        quantity.performClick()
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            for (code in listOf(KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_ENTER)) {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    compose.activity.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
                }
            }
        }
        assertEquals("12", state.quantity)
        compose.onNodeWithText("完成盘点").assertIsEnabled()
        capture("quantity-320")
    }

    @Test
    fun zeroNegativeAndUnknownRemainVisibleButCannotBeSelected() {
        render(choices = true)
        compose.onNodeWithText("P-PICK").assertIsEnabled()
        compose.onNodeWithText("拣货区").assertIsDisplayed()
        compose.onNodeWithText("P-ZERO").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("P-NEGATIVE").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("P-UNKNOWN").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("快速盘点").assertIsDisplayed()
        capture("locations-320")
    }

    private fun render(choices: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.15f)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag("zh-Hans"))
                fontScale = 1.15f
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context.createConfigurationContext(config), LocalDensity provides Density(LocalDensity.current.density, 1.15f)) {
                FscmTheme {
                    Column(Modifier.fillMaxSize()) {
                        StocktakeToolbar(state, {}, {})
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (choices) {
                                item { StocktakeChoice(stock.copy(locationCode = "P-PICK", supportsPicking = true), false, true) {} }
                                item { StocktakeChoice(stock.copy(locationCode = "P-ZERO", onHand = 0), false, true) {} }
                                item { StocktakeChoice(stock.copy(locationCode = "P-NEGATIVE", onHand = -1), false, true) {} }
                                item { StocktakeChoice(stock.copy(locationCode = "P-UNKNOWN", onHand = null), false, true) {} }
                            } else {
                                item { StocktakeIdentity(state.location, state.sku) }
                                item { StocktakeQuantityPanel(state, { state = state.copy(quantity = it) }, { state = state.copy(quantity = "0") }, {}, {}) }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val file = File("build/outputs/stocktake-tasks/$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
