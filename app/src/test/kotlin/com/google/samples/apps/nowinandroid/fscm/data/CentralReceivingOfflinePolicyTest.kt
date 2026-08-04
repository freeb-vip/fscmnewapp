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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralReceivingOfflinePolicyTest {
    @Test
    fun unknownRawCode_isPreservedForCloudValidation() {
        val parsed = parseCentralReceivingScanCode("SUPPLIER-BOX-2026-001")

        assertEquals("SUPPLIER-BOX-2026-001", parsed.originalRawCode)
        assertEquals("SUPPLIER-BOX-2026-001", parsed.labelCode)
    }

    @Test
    fun recognizedCompactCode_usesNormalizedLabelCode() {
        val parsed = parseCentralReceivingScanCode("aabbccddee01,SKU-1=12")

        assertEquals("AABBCCDDEE01", parsed.labelCode)
        assertEquals(12, parsed.totalQuantity)
    }

    @Test
    fun localAndRemoteReceiptStates_areCountedWithoutQuantityEdits() {
        val base = CentralReceivingLabelEntity(
            userId = 1,
            namespaceId = 2,
            boxPlanId = 3,
            centralReceiptId = 4,
            centralReceiptItemId = 5,
            labelCode = "AABBCCDDEE01",
            boxUid = "BOX-1",
            boxNo = "CR-1-001",
            remoteStatus = "shipped",
            signStatus = "pending",
            payload = "{}",
            generation = "g1",
        )

        assertFalse(base.effectiveReceived())
        assertTrue(base.copy(localStatus = "received_local").effectiveReceived())
        assertTrue(base.copy(signStatus = "signed").effectiveReceived())
        assertTrue(base.copy(remoteStatus = "central_received").effectiveReceived())
    }

    @Test
    fun statisticsUseEffectiveReceiptStateAndExcludeReceivedExceptions() {
        val base = CentralReceivingLabelEntity(
            userId = 1,
            namespaceId = 2,
            boxPlanId = 1,
            centralReceiptId = 4,
            centralReceiptItemId = 5,
            labelCode = "BOX-1",
            boxUid = "UID-1",
            boxNo = "CR-1-001",
            remoteStatus = "shipped",
            signStatus = "pending",
            payload = "{}",
            generation = "g1",
        )
        val statistics = centralReceiptStatistics(
            receipts = listOf(CentralReceipt(id = 4, items = listOf(CentralReceiptItem(id = 5)))),
            labels = listOf(
                base,
                base.copy(boxPlanId = 2, labelCode = "BOX-2", localStatus = "received_local"),
                base.copy(boxPlanId = 3, labelCode = "BOX-3", signStatus = "exception"),
                base.copy(boxPlanId = 4, labelCode = "BOX-4", signStatus = "shortage_completed"),
                base.copy(boxPlanId = 5, labelCode = "BOX-5", signStatus = "exception", localStatus = "received_local"),
            ),
        )

        assertEquals(1, statistics.receiptCount)
        assertEquals(1, statistics.skuCount)
        assertEquals(5, statistics.totalBoxCount)
        assertEquals(2, statistics.receivedBoxCount)
        assertEquals(3, statistics.pendingBoxCount)
        assertEquals(2, statistics.exceptionBoxCount)
    }
}
