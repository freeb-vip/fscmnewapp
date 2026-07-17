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

package com.google.samples.apps.nowinandroid.fscm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLabelQrParserTest {
    @Test
    fun likelyBoxLabel_matchesOnlySupportedFormats() {
        assertTrue(isLikelyBoxLabelQr("AB12CD34EF56"))
        assertTrue(isLikelyBoxLabelQr("AB12CD34EF56,SKU-A=6"))
        assertTrue(isLikelyBoxLabelQr("FSCMBOX:v1:BOX-8-9-001-123"))
        assertFalse(isLikelyBoxLabelQr("SKU-001"))
    }

    @Test
    fun compactPayload_parsesSingleSkuAndScannerTerminators() {
        val result = parseBoxLabelQr("\uFEFFab12cd34ef56,TSKU-A=12\r\n")

        assertEquals("AB12CD34EF56", result.labelCode)
        assertEquals(listOf(BoxLabelQrItem("TSKU-A", 12)), result.items)
        assertEquals(12, result.totalQuantity)
        assertEquals(BoxLabelQrFormat.Compact, result.format)
    }

    @Test
    fun compactPayload_preservesMixedSkuOrderAndTotalsQuantity() {
        val result = parseBoxLabelQr("AB12CD34EF56,SKU-A=6,SKU-B=4")

        assertEquals(listOf(BoxLabelQrItem("SKU-A", 6), BoxLabelQrItem("SKU-B", 4)), result.items)
        assertEquals(10, result.totalQuantity)
    }

    @Test
    fun bareShortCode_isAccepted() {
        val result = parseBoxLabelQr("ab12cd34ef56")

        assertEquals("AB12CD34EF56", result.labelCode)
        assertEquals(BoxLabelQrFormat.ShortCode, result.format)
    }

    @Test
    fun legacyFormats_resolveStableShortCodeAndV3Items() {
        val v1 = parseBoxLabelQr("FSCMBOX:v1:BOX-8-9-001-123")
        val v2 = parseBoxLabelQr("FSCMBOX:v2|u=BOX-8-9-001-123|s=SKU-A|q=12")
        val v3 = parseBoxLabelQr("FSCMBOX:v3|c=AB12CD34EF56|s=SKU-A＝6；SKU-B＝4|q=10")

        assertEquals("C7A4038398F1", v1.labelCode)
        assertEquals(v1.labelCode, v2.labelCode)
        assertEquals(listOf(BoxLabelQrItem("SKU-A", 6), BoxLabelQrItem("SKU-B", 4)), v3.items)
    }

    @Test
    fun malformedPayloads_areRejectedBeforeNetworkLookup() {
        listOf(
            "NOT-A-LABEL,SKU-A=1",
            "AB12CD34EF56,=1",
            "AB12CD34EF56,SKU-A=x",
            "AB12CD34EF56,SKU-A=0",
            "AB12CD34EF56,SKU-A=-1",
        ).forEach { raw ->
            assertThrows(BoxLabelQrFormatException::class.java) { parseBoxLabelQr(raw) }
        }
    }
}
