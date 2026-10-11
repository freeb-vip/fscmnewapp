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

/** Per-session protection. Both clock values use Android uptime, never wall-clock time. */
internal class ScannerInputGuard(private val now: () -> Long) {
    var lastValidScanUptime: Long? = null
        private set
    private val consumedKeys = mutableSetOf<KeyPress>()

    fun scanned() {
        lastValidScanUptime = now()
    }
    fun age(): Long? = lastValidScanUptime?.let { now() - it }
    fun isProtected(): Boolean = age()?.let { it in 0..ScannerManager.BACK_EVENT_GUARD_WINDOW_MS } == true

    fun clear() {
        lastValidScanUptime = null
        consumedKeys.clear()
    }

    fun key(press: KeyPress, action: Int, eligible: Boolean): KeyDecision {
        val alreadyConsumed = press in consumedKeys
        val consume = alreadyConsumed || (eligible && isProtected())
        if (consume && action == 0) consumedKeys.add(press)
        if (action == 1) consumedKeys.remove(press)
        return KeyDecision(consume, consume && !alreadyConsumed)
    }
}

internal data class KeyPress(val device: Int, val code: Int, val downTime: Long)
internal data class KeyDecision(val consume: Boolean, val first: Boolean)

internal fun scannerModeAfterBroadcast(
    configured: ScannerInputMode?,
    previous: ScannerInputMode,
    extracted: Boolean,
): ScannerInputMode = configured ?: if (extracted) ScannerInputMode.IntentBroadcast else previous
