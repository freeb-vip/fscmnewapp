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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.OrderProductPreview
import com.google.samples.apps.nowinandroid.fscm.data.ShippingTask
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseException
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionOrder
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionType
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionWarehouse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Isolated product fixtures; no real order is read or changed. */
class OrderListCardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun keepTestWindowAwake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    @Test
    fun bothListsShowProductAndIdentifiersAndOpenTheOrder() {
        val image = fixtureImage()
        var exceptionOpened = false
        var shippingOpened = false
        compose.setContent {
            FscmTheme {
                Column(Modifier.fillMaxSize().background(FscmPageTop).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExceptionOrderListCard(
                        WarehouseException(id = 1, order = WarehouseExceptionOrder(number = "000123456789", tracking = listOf("000987654321")), warehouse = WarehouseExceptionWarehouse("Warehouse A"), types = listOf(WarehouseExceptionType("Packing issue")), createdAt = "2026-11-12 10:20", productPreview = OrderProductPreview("Protective shipping carton", "000SKU-A", image, 3)),
                    ) { exceptionOpened = true }
                    ShippingOrderListCard(ShippingTask(orderId = 2, orderNo = "OFF-000042", warehouse = "Warehouse A", stage = "packing", skuCount = 2, quantity = 12, productPreview = OrderProductPreview("Protective shipping carton", "000SKU-B", image, 2))) { shippingOpened = true }
                }
            }
        }
        saveScreenshot("order-list-cards")
        compose.onNodeWithText("000123456789").assertIsDisplayed().performClick()
        compose.onNodeWithText("OFF-000042").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(exceptionOpened)
            assertTrue(shippingOpened)
        }
    }

    @Test
    fun missingProductsAndLongIdentifiersRemainUsableWithLargeText() {
        var opened = false
        val order = "ORDER-000000000000000000000000000000123456789"
        compose.setContent {
            FscmTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                    Column(Modifier.fillMaxSize().background(FscmPageTop).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ExceptionOrderListCard(WarehouseException(id = 1, order = WarehouseExceptionOrder(number = order))) { opened = true }
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.order_card_product_missing)).assertIsDisplayed()
        saveScreenshot("order-list-cards-large-font")
        compose.onNodeWithText(order).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    private fun fixtureImage(): String {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(233, 241, 255))
        val paint = Paint().apply {
            color = Color.rgb(198, 155, 100)
            isAntiAlias = true
        }
        canvas.drawRect(35f, 55f, 205f, 200f, paint)
        paint.color = Color.rgb(237, 202, 148)
        canvas.drawRect(106f, 55f, 134f, 200f, paint)
        val file = File(context.cacheDir, "order-card-fixture.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
