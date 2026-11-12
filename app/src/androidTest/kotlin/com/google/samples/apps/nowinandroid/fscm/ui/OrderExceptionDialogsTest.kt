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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderExceptionDialogsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun keepTestWindowAwake() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
    }

    @Test
    fun multilineEditorKeepsDoneVisibleWhileTyping() {
        val value = mutableStateOf("Existing correction\n")
        var done = false
        compose.setContent { FscmTheme { ExceptionMeasuresEditor(value.value, { value.value = it }, { done = true }) } }
        compose.onNode(hasSetTextAction()).performTextInput("Improved packing\nAdded a check")
        compose.onNodeWithText(context.getString(R.string.oe_done)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(done)
            assertTrue(value.value.contains("Improved packing\nAdded a check"))
            assertTrue(value.value.contains("Existing correction"))
        }
    }

    @Test fun chineseConfirmationRequiresExplicitAction() = confirmation("zh-Hans")

    @Test fun englishConfirmationRequiresExplicitAction() = confirmation("en")

    @Test fun thaiConfirmationRequiresExplicitAction() = confirmation("th")

    @Test fun burmeseConfirmationRequiresExplicitAction() = confirmation("my")

    @Test
    fun photoSupportsDoubleTapResetAndClose() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        var closed = false
        compose.setContent { FscmTheme { ExceptionPhotoViewer(bytes, "Test photo") { closed = true } } }
        compose.onNode(hasStateDescription("100%")).performTouchInput { doubleClick() }
        compose.onNode(hasStateDescription("300%")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.oe_reset_zoom)).performClick()
        compose.onNode(hasStateDescription("100%")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.oe_close)).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    private fun confirmation(tag: String) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val localized = context.createConfigurationContext(configuration)
        var submitted = 0
        var dismissed = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources) {
                FscmTheme { ExceptionCorrectionConfirmation({ submitted++ }, { dismissed++ }) }
            }
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "exception-confirm-$tag.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithText(localized.getString(R.string.oe_confirm_message)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, submitted) }
        compose.onNodeWithText(localized.getString(R.string.oe_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(0, submitted)
            assertEquals(1, dismissed)
        }
        compose.onNodeWithText(localized.getString(R.string.oe_confirm_submit)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }
}
