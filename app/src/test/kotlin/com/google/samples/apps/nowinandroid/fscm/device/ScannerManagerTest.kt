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

import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.toScannerProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerManagerTest {
    @Test
    fun scannerProfile_defaultsCoverCommonPdaVendors() {
        val profile = "".toScannerProfile()

        assertTrue("com.symbol.datawedge.data_string" in profile.actions)
        assertTrue("com.honeywell.decode.intent.action.BARCODE_RESULT" in profile.actions)
        assertTrue("com.datalogic.decodewedge.decode_action" in profile.actions)
        assertTrue("com.nlscan.action.SCANNER_RESULT" in profile.actions)
        assertTrue("com.symbol.datawedge.data_string" in profile.extraKeys)
    }

    @Test
    fun scannerProfile_acceptsConfiguredActionsAndExtras() {
        val profile = "com.vendor.SCAN;com.vendor.SCAN2|payload;label".toScannerProfile()

        assertEquals("com.vendor.SCAN", profile.actions.first())
        assertEquals("payload", profile.extraKeys.first())
        assertTrue("com.vendor.SCAN2" in profile.actions)
        assertTrue("label" in profile.extraKeys)
        assertTrue(ScannerManager.FSCM_SCAN_ACTION in profile.actions)
    }
}
