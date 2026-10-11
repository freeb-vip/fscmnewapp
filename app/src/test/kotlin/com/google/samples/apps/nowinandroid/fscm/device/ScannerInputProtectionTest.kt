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

package com.google.samples.apps.nowinandroid.fscm.device

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun testScanner(): ScannerManager {
    val context = RuntimeEnvironment.getApplication()
    val repository = Proxy.newProxyInstance(UserDataRepository::class.java.classLoader, arrayOf(UserDataRepository::class.java)) { _, method, _ ->
        error("Unexpected repository call: ${method.name}")
    } as UserDataRepository
    return ScannerManager(context, repository, ReceivingScanFeedback(context)).apply { pushOnScanListener {} }
}

internal fun ScannerManager.testBroadcast() = onScannerBroadcast(Intent(ScannerManager.FSCM_SCAN_ACTION).putExtra("barcode", "PRIVATE-SKU-123"))
internal fun scanKey(code: Int, action: Int = KeyEvent.ACTION_DOWN, downTime: Long = SystemClock.uptimeMillis()): KeyEvent =
    KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 36])
class ScannerInputProtectionTest {
    @Test
    fun validScanSurvivesControlBroadcastAndAutoModeStaysBroadcast() {
        val scanner = testScanner()
        scanner.testBroadcast()
        scanner.onScannerBroadcast(Intent("ACTION_BAR_SCANCFG"))
        assertFalse(scanner.scannerState.value.lastEvent!!.extracted)
        assertTrue(scanner.scannerState.value.lastValidEvent!!.extracted)
        assertEquals(ScannerInputMode.IntentBroadcast, scanner.scannerState.value.effectiveMode)
        assertTrue(scanner.consumeBackEventAfterScan())
    }

    @Test
    fun invalidBroadcastDoesNotStartOrExtendProtection() {
        val scanner = testScanner()
        scanner.applyInputMode(ScannerInputMode.IntentBroadcast)
        scanner.onScannerBroadcast(Intent("ACTION_BAR_SCANCFG"))
        assertFalse(scanner.consumeBackEventAfterScan())
        scanner.testBroadcast()
        ShadowSystemClock.advanceBy(Duration.ofMillis(601))
        scanner.onScannerBroadcast(Intent("ACTION_BAR_SCANCFG"))
        assertFalse(scanner.consumeBackEventAfterScan())
    }

    @Test
    fun boundaryAndConsecutiveScanUseMonotonicTime() {
        var now = 1000L
        val guard = ScannerInputGuard { now }
        assertFalse(guard.isProtected())
        guard.scanned()
        now += 600
        assertTrue(guard.isProtected())
        now++
        assertFalse(guard.isProtected())
        guard.scanned()
        assertTrue(guard.isProtected())
        guard.clear()
        assertFalse(guard.isProtected())
    }

    @Test
    fun backDownAndRepeatsAndLateUpConsumedOnce() {
        val scanner = testScanner()
        scanner.testBroadcast()
        val down = SystemClock.uptimeMillis()
        assertTrue(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, downTime = down), false))
        ShadowSystemClock.advanceBy(Duration.ofMillis(700))
        assertTrue(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, downTime = down), false))
        assertTrue(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP, down), false))
        assertEquals(1, scanner.scannerState.value.suppressedBackCount)
        assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK), false))
    }

    @Test
    fun returnBeforeBroadcastAndAfterWindowIsAllowedAndDiagnosed() {
        val scanner = testScanner()
        scanner.setEventRecording(true)
        scanner.applyInputMode(ScannerInputMode.IntentBroadcast)
        assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK), false))
        scanner.testBroadcast()
        ShadowSystemClock.advanceBy(Duration.ofMillis(601))
        assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_BACK), false))
        assertEquals(2, scanner.scannerState.value.inputEvents.count { "reason=outside_window" in it })
    }

    @Test
    fun keyboardModeAndModeSwitchAndBackgroundClearProtection() {
        val scanner = testScanner()
        scanner.applyInputMode(ScannerInputMode.Keyboard)
        scanner.testBroadcast()
        assertFalse(scanner.consumeBackEventAfterScan())
        assertEquals(ScannerInputMode.Keyboard, scanner.scannerState.value.effectiveMode)
        scanner.applyInputMode(ScannerInputMode.IntentBroadcast)
        assertFalse(scanner.consumeBackEventAfterScan())
        scanner.testBroadcast()
        scanner.unregister()
        assertFalse(scanner.consumeBackEventAfterScan())
        scanner.testBroadcast()
        scanner.clearInputProtection()
        assertFalse(scanner.consumeBackEventAfterScan())
    }

    @Test
    fun suffixKeysRequireActiveScannerAndDoNotAffectEditorsOrCharacters() {
        val scanner = testScanner()
        val owner = Any()
        scanner.testBroadcast()
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE)) {
            scanner.setScanScope(owner, false)
            assertFalse(scanner.handleKeyEvent(scanKey(key), false))
            scanner.setScanScope(owner, true)
            assertFalse(scanner.handleKeyEvent(scanKey(key), true))
            val down = SystemClock.uptimeMillis()
            assertTrue(scanner.handleKeyEvent(scanKey(key, downTime = down), false))
            assertTrue(scanner.handleKeyEvent(scanKey(key, KeyEvent.ACTION_UP, down), true))
        }
        assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_1), false))
        scanner.setScanScope(owner, null)
        assertFalse(scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_ENTER), false))
    }

    @Test
    fun lateBroadcastAfterLogoutDoesNotRestartProtection() {
        val scanner = testScanner()
        scanner.testBroadcast()
        scanner.popOnScanListener()
        scanner.testBroadcast()
        assertFalse(scanner.consumeBackEventAfterScan())
    }

    @Test
    fun pausedOwnerDoesNotDispatchOrProtectLateBroadcast() {
        val scanner = testScanner()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        scanner.register(owner)
        var delivered = false
        scanner.pushOnScanListener { delivered = true }
        scanner.testBroadcast()
        assertFalse(delivered)
        assertFalse(scanner.consumeBackEventAfterScan())
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
    }

    @Test
    fun traceIsOptInBoundedAndNeverContainsBarcodeOrCharacterKeys() {
        val scanner = testScanner()
        scanner.testBroadcast()
        assertTrue(scanner.scannerState.value.inputEvents.isEmpty())
        scanner.setEventRecording(true)
        repeat(110) {
            scanner.testBroadcast()
            scanner.handleKeyEvent(scanKey(KeyEvent.KEYCODE_A), true)
        }
        val events = scanner.scannerState.value.inputEvents
        assertEquals(100, events.size)
        assertTrue(events.none { "PRIVATE" in it || "KEYCODE_A" in it })
        scanner.setEventRecording(false)
        scanner.recordNavigation("system_back")
        assertEquals(events, scanner.scannerState.value.inputEvents)
        scanner.clearDiagnostics()
        assertTrue(scanner.scannerState.value.inputEvents.isEmpty())
    }
}
