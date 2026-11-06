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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS-w320dp-h640dp", sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TicketScopeSmallScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun smallScreenFilterRetainsLeadingZerosAndKeepsConfirmReachable() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                TicketScopeFilters(TicketsUiState()) { _, sku, product, _, _ -> submitted = sku to product }
            }
        }
        compose.onNodeWithText("Filter by shop and product").performClick()
        compose.onNodeWithText("SKU code").performTextInput("000123")
        compose.onNodeWithText("Product code").performTextInput("P00001")
        compose.onNodeWithText("Search").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("000123" to "P00001", submitted) }
    }
}
