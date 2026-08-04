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

import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptScanResult
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanResultFeedbackTest {
    @Test
    fun centralReceiving_requiresConfirmedAndNonDuplicateResult() {
        assertTrue(CentralReceiptScanResult(confirmed = true).isSuccessfulScanResult())
        assertFalse(CentralReceiptScanResult(confirmed = false).isSuccessfulScanResult())
        assertFalse(CentralReceiptScanResult(confirmed = true, duplicate = true).isSuccessfulScanResult())
    }

    @Test
    fun warehouseReceiving_rejectsEmptyAndExceptionResults() {
        assertTrue(ReceivingScanItem(labelCode = "BOX-001", status = "normal").isSuccessfulLookup())
        assertFalse(ReceivingScanItem().isSuccessfulLookup())
        assertFalse(ReceivingScanItem(labelCode = "BOX-001", status = "exception").isSuccessfulLookup())
    }

    @Test
    fun cartonManagement_requiresRecognizedAndNormalBoxLabel() {
        assertTrue(BoxLabel(recognized = true, status = "normal").isNormalBoxLabel())
        assertFalse(BoxLabel(recognized = false, status = "normal").isNormalBoxLabel())
        assertFalse(BoxLabel(recognized = true, validForCurrentContext = false).isNormalBoxLabel())
        assertFalse(BoxLabel(recognized = true, warningMessage = "Wrong receipt").isNormalBoxLabel())
        assertFalse(BoxLabel(recognized = true, status = "exception").isNormalBoxLabel())
    }

    @Test
    fun productScan_requiresAtLeastOneMatch() {
        assertTrue(isSuccessfulProductScanResult(1))
        assertFalse(isSuccessfulProductScanResult(0))
    }

    @Test
    fun playbackResult_reportsLoadingFailureAndSuccess() {
        assertEquals(ScanFeedbackPlaybackResult.Loading, scanFeedbackPlaybackResult(ready = false, streamId = 1))
        assertEquals(ScanFeedbackPlaybackResult.Failed, scanFeedbackPlaybackResult(ready = true, streamId = 0))
        assertEquals(ScanFeedbackPlaybackResult.Playing, scanFeedbackPlaybackResult(ready = true, streamId = 1))
    }
}
