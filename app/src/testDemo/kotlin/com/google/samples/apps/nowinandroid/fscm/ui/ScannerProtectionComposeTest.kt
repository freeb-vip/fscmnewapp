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
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.samples.apps.nowinandroid.fscm.device.scanKey
import com.google.samples.apps.nowinandroid.fscm.device.testBroadcast
import com.google.samples.apps.nowinandroid.fscm.device.testScanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 36])
class ScannerProtectionComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nestedBusinessBackIsGuardedWithoutExecutingParentOrSavingDraft() {
        val scanner = testScanner()
        var parent = 0
        var child = 0
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScanAwareBackHandler { parent++ }
                Column {
                    ScanAwareBackHandler { child++ }
                    Text("Business")
                }
            }
        }
        compose.runOnIdle {
            scanner.testBroadcast()
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(0, child)
            assertEquals(0, parent)
            assertEquals(1, scanner.scannerState.value.suppressedBackCount)
            ShadowSystemClock.advanceBy(Duration.ofMillis(601))
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, child)
            assertEquals(0, parent)
        }
    }

    @Test
    fun touchingBackRemainsImmediateDuringProtection() {
        val scanner = testScanner()
        var navigated = 0
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScanAwareBackHandler { navigated++ }
                Button(onClick = { navigated++ }) { Text("Back") }
            }
        }
        compose.runOnIdle { scanner.testBroadcast() }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle {
            assertEquals(1, navigated)
            assertEquals(0, scanner.scannerState.value.suppressedBackCount)
        }
    }

    @Test
    fun dialogWindowConsumesBackPairIncludingLateUpAndTouchCancelStillWorks() {
        val scanner = testScanner()
        var dismissed = false
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScanAwareAlertDialog(
                    onDismissRequest = { dismissed = true },
                    confirmButton = { Button(onClick = {}) { Text("Confirm") } },
                    dismissButton = { Button(onClick = { dismissed = true }) { Text("Cancel") } },
                    text = { Text("Count confirmation") },
                )
            }
        }
        compose.runOnIdle {
            scanner.testBroadcast()
            val callback = ShadowDialog.getLatestDialog().window!!.callback
            val down = SystemClock.uptimeMillis()
            assertTrue(callback.dispatchKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, downTime = down)))
            ShadowSystemClock.advanceBy(Duration.ofMillis(700))
            assertTrue(callback.dispatchKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, down)))
            assertFalse(dismissed)
            assertEquals(1, scanner.scannerState.value.suppressedBackCount)
            scanner.testBroadcast()
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun dialogOutsideWindowRetainsNormalSystemDismissal() {
        val scanner = testScanner()
        var dismissed = false
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScanAwareDialog(onDismissRequest = { dismissed = true }) { Text("Scan") }
            }
        }
        compose.runOnIdle {
            scanner.testBroadcast()
            ShadowSystemClock.advanceBy(Duration.ofMillis(601))
            val callback = ShadowDialog.getLatestDialog().window!!.callback
            val down = SystemClock.uptimeMillis()
            callback.dispatchKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, downTime = down))
            callback.dispatchKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, down))
        }
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun dialogBackDispatcherWithoutKeyEventIsAlsoProtected() {
        val scanner = testScanner()
        var dismissed = false
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScanAwareDialog(onDismissRequest = { dismissed = true }) { Text("Scan") }
            }
        }
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
            scanner.testBroadcast()
            dialog.onBackPressedDispatcher.onBackPressed()
            assertFalse(dismissed)
            assertEquals(1, scanner.scannerState.value.suppressedBackCount)
            ShadowSystemClock.advanceBy(Duration.ofMillis(601))
            dialog.onBackPressedDispatcher.onBackPressed()
            assertTrue(dismissed)
        }
    }

    @Test
    fun composeEditorIsRecognizedAndManualEnterIsNotFiltered() {
        val scanner = testScanner()
        var text by mutableStateOf("")
        compose.setContent {
            ScannerInputProvider(scanner) {
                ScannerInputScope(true)
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Quantity") })
            }
        }
        compose.onNodeWithText("Quantity").performClick().performTextReplacement("12")
        compose.runOnIdle {
            scanner.testBroadcast()
            val editing = compose.activity.currentFocus?.onCheckIsTextEditor() == true
            assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_ENTER), editing))
            assertEquals("12", text)
        }
    }
}
