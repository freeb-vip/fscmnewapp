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

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeProduct
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeShop
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkDay
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkSummary
import com.google.samples.apps.nowinandroid.fscm.data.SalesAccount
import com.google.samples.apps.nowinandroid.fscm.data.SalesComparison
import com.google.samples.apps.nowinandroid.fscm.data.SalesOverview
import com.google.samples.apps.nowinandroid.fscm.data.SalesSummary
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Isolated Compose fixtures; these screens do not represent live account data. */
class RealtimeDataScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test
    fun chineseLabelsRenderOnTheNativePage() {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                FscmTheme { RealtimeDataScreen(fixture(), {}, {}) }
            }
        }
        compose.onAllNodesWithText("今日销售额").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("今日订单数").onFirst().assertIsDisplayed()
        saveImage("realtime-detail-zh-fixture")
    }

    @Test
    fun homeSummaryOpensDetailAndHidesWithoutPermission() {
        val state = mutableStateOf(fixture())
        var opened = false
        compose.setContent {
            FscmTheme {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    RealtimeHomeCard(state.value, { opened = true }, {}, {})
                }
            }
        }
        compose.onNodeWithText("128,640.25").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rt_shops)).assertDoesNotExist()
        saveImage("realtime-home-fixture")
        compose.onNodeWithText(text(R.string.rt_today)).performClick()
        assertTrue(opened)
        compose.runOnIdle { state.value = state.value.copy(allowed = false) }
        compose.onNodeWithText(text(R.string.rt_today)).assertDoesNotExist()
    }

    @Test
    fun accountSheetAndBothRankingsAreUsable() {
        var selected = ""
        compose.setContent { FscmTheme { RealtimeDataScreen(fixture(), { selected = it }, {}) } }
        saveImage("realtime-detail-fixture")
        compose.onNodeWithTag("realtime-account-switch").performClick()
        compose.onNodeWithText("Demo account B").performClick()
        compose.runOnIdle { assertEquals("b", selected) }
        compose.onNodeWithTag("realtime-list").performScrollToNode(hasText("Demo shop"))
        compose.onNodeWithText("Demo shop").assertIsDisplayed()
        saveImage("realtime-shops-fixture")
        compose.onNodeWithText(text(R.string.rt_products)).performClick()
        compose.onNodeWithTag("realtime-list").performScrollToNode(hasText("DEMO-SKU-001"))
        compose.onNodeWithText("DEMO-SKU-001").assertIsDisplayed()
        saveImage("realtime-products-fixture")
    }

    @Test
    fun largeFontAndLargeAmountRemainScrollable() {
        compose.setContent {
            FscmTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    RealtimeDataScreen(fixture().let { it.copy(sales = it.sales?.copy(summary = SalesSummary(125468, "9007199254740993.13"))) }, {}, {})
                }
            }
        }
        compose.onNodeWithTag("realtime-list").performScrollToNode(hasText("9,007,199,254,740,993.13"))
        compose.onNodeWithText("9,007,199,254,740,993.13").assertIsDisplayed()
        saveImage("realtime-large-font-fixture")
        compose.onNodeWithTag("realtime-list").performScrollToNode(hasText("Demo shop"))
        compose.onNodeWithText("Demo shop").assertIsDisplayed()
    }

    private fun saveImage(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val target = File(context.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fixture() = RealtimeUiState(
        scope = "fixture",
        allowed = true,
        accountsLoaded = true,
        selectedId = "a",
        accounts = listOf(SalesAccount(id = "a", name = "Demo account A", provider = "bigseller"), SalesAccount(id = "b", name = "Demo account B", provider = "xinjian")),
        sales = SalesOverview(
            connectionId = "a",
            date = "2026-10-09",
            timezone = "Asia/Shanghai",
            summary = SalesSummary(842, "128640.25"),
            comparison = SalesComparison(mode = "current_time", current = SalesSummary(842, "128640.25"), previous = SalesSummary(700, "120000")),
            shops = listOf(RealtimeShop(id = "s", name = "Demo shop", platform = "Shopee", site = "TH", orders = 842, amount = "128640.25", amountChangeRatio = "0.072")),
            products = listOf(RealtimeProduct(id = "p", sku = "DEMO-SKU-001", shopId = "s", shopName = "Demo shop", name = "Long product name for mobile layout verification", imageUrl = "", quantity = 320, amount = "42000.00")),
        ),
        work = RealtimeWorkSummary(
            today = RealtimeWorkDay("2026-10-09", "Asia/Bangkok", 638, 55.42, "ready"),
            yesterday = RealtimeWorkDay("2026-10-08", "Asia/Bangkok", 1042, 48.20, "ready"),
        ),
    )
}
