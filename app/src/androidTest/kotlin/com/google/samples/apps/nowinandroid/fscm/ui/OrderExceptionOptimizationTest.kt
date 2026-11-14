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

import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionOperation
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

class OrderExceptionOptimizationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chineseNarrowScreen() = layout("zh-Hans")

    @Test fun englishNarrowScreen() = layout("en")

    @Test fun thaiNarrowScreen() = layout("th")

    @Test fun burmeseNarrowScreen() = layout("my")

    private fun layout(tag: String) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val localized = context.createConfigurationContext(config)
        val draft = mutableStateOf<Map<String, String>>(emptyMap())
        var searches = 0
        val number = "000123456789012345678901234567890"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config, LocalResources provides localized.resources, LocalDensity provides Density(density.density, 1.5f)) {
                FscmTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ExceptionSearchFields(draft.value, { key, value -> draft.value += key to value }, { clear ->
                            searches++
                            if (clear) draft.value = emptyMap()
                        })
                        ExceptionSectionTitle(localized.getString(R.string.oe_order_details))
                        ExceptionCopyField(localized.getString(R.string.oe_order), number)
                        ExceptionCopyField(localized.getString(R.string.oe_tracking), "000TRACK")
                        ExceptionCopyField(localized.getString(R.string.oe_sku), "000SKU")
                        ExceptionSectionTitle(localized.getString(R.string.oe_operations))
                        ExceptionOperationCard(WarehouseExceptionOperation("1", code = "PACKING", name = "Operator A", employeeCode = "0007", occurredAt = "2026-11-14 10:30"))
                        ExceptionSectionTitle(localized.getString(R.string.oe_rectification))
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("000SKU")
        compose.runOnIdle { assertEquals(0, searches) }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(1, searches) }
        compose.onNodeWithText(localized.getString(R.string.oe_clear)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyMap(), draft.value) }
        screenshot("search-$tag")
        for (value in listOf(number, "000TRACK", "000SKU")) {
            compose.onNodeWithText(value).performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(value, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()) }
        }
        screenshot("identifiers-$tag")
        compose.onNodeWithText("Operator A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.oe_rectification)).performScrollTo().assertIsDisplayed()
        screenshot("operations-$tag")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "exception-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
