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

import android.content.Intent
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.extractBarcode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.extractScannerEvent
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.toScannerConfig
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.toScannerProfile
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.withScannerInputMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ScannerManagerTest {
    @Test
    fun scannerProfile_defaultsCoverCommonPdaVendors() {
        val profile = "".toScannerProfile()

        assertTrue(profile.actions.contains("com.symbol.datawedge.api.ACTION_DATAWEDGE_FROM_6_2"))
        assertTrue(profile.actions.contains("com.honeywell.decode.intent.action.BARCODE_RESULT"))
        assertTrue(profile.actions.contains("com.datalogic.decodewedge.decode_action"))
        assertTrue(profile.actions.contains("com.nlscan.action.SCANNER_RESULT"))
        assertTrue(profile.actions.contains("cn.guanmai.scanner.data"))
        assertTrue(profile.actions.contains("ACTION_BAR_SCANCFG"))
        assertTrue(profile.extraKeys.contains("com.symbol.datawedge.data_string"))
        assertTrue(profile.extraKeys.contains("string"))
        assertTrue(profile.extraKeys.contains("SCAN_BARCODE2"))
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

    @Test
    fun scannerConfig_acceptsModePrefixAndKeepsConfiguredProfile() {
        val config = "mode=intent\ncom.vendor.SCAN|payload".toScannerConfig()

        assertEquals(ScannerInputMode.IntentBroadcast, config.configuredMode)
        assertEquals("com.vendor.SCAN", config.profile.actions.first())
        assertEquals("payload", config.profile.extraKeys.first())
    }

    @Test
    fun scannerConfig_withModePreservesLegacyActionExtraFormat() {
        val stored = "com.vendor.SCAN|payload".withScannerInputMode(ScannerInputMode.Keyboard)
        val config = stored.toScannerConfig()

        assertEquals(ScannerInputMode.Keyboard, config.configuredMode)
        assertEquals("com.vendor.SCAN", config.profile.actions.first())
        assertEquals("payload", config.profile.extraKeys.first())
    }

    @Test
    fun scannerProfile_deduplicatesConfiguredActionsAndExtras() {
        val profile = "com.vendor.SCAN;com.vendor.SCAN|payload;payload".toScannerProfile()

        assertEquals(1, profile.actions.count { it == "com.vendor.SCAN" })
        assertEquals(1, profile.extraKeys.count { it == "payload" })
    }

    @Test
    fun extractBarcode_readsCommonVendorExtras() {
        assertEquals(
            "GM-001",
            Intent("cn.guanmai.scanner.data")
                .putExtra("string", "GM-001")
                .extractBarcode(),
        )
        assertEquals(
            "SUNMI-001",
            Intent("com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED")
                .putExtra("data", "SUNMI-001")
                .extractBarcode(),
        )
        assertEquals(
            "NL-001",
            Intent("nlscan.action.SCANNER_RESULT")
                .putExtra("SCAN_BARCODE1", "NL-001")
                .extractBarcode(),
        )
        assertEquals(
            "NL-002",
            Intent("nlscan.action.SCANNER_RESULT")
                .putExtra("SCAN_BARCODE2", "NL-002")
                .extractBarcode(),
        )
        assertEquals(
            "ZEBRA-001",
            Intent("com.symbol.datawedge.api.RESULT_ACTION")
                .putExtra("com.symbol.datawedge.data_string", "ZEBRA-001")
                .extractBarcode(),
        )
    }

    @Test
    fun extractScannerEvent_reportsControlBroadcastWithoutBarcode() {
        val event = Intent("ACTION_BAR_SCANCFG").extractScannerEvent()

        assertEquals("ACTION_BAR_SCANCFG", event.action)
        assertFalse(event.extracted)
        assertEquals("", event.normalizedValue)
    }
}
