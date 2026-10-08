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
import kotlin.test.assertNull

class InboundScanRejectionTest {
    private val batch = LocalInboundBatchEntity("batch", 7, 9, 11, "B-02", 202)
    private val snapshot = LocalInboundSnapshotEntity(
        userId = 7, namespaceId = 9, sessionId = 11, boxPlanId = 1, labelCode = "BOX-A",
        boxNo = "1", boxUid = "UID-A", status = "normal", inboundStatus = "pending",
        locationCode = "", eligible = true, snapshotAt = 100,
    )
    private val existing = LocalInboundItemEntity(
        requestId = "previous", batchId = batch.id, userId = 7, namespaceId = 9,
        sessionId = 11, boxPlanId = 1, labelCode = "BOX-A", rawCode = "BOX-A",
        localLocationCode = "A-01",
    )

    @Test
    fun localDuplicatesDistinguishCurrentAndOtherBatchesEvenWhenPreviewIsIneligible() {
        val rejected = snapshot.copy(eligible = false)
        assertEquals(InboundClientError.BoxScannedInCurrentBatch, inboundScanRejection(batch, rejected, existing, true)?.reason)
        val other = inboundScanRejection(batch, rejected, existing.copy(batchId = "other"), true)
        assertEquals(InboundClientError.BoxAlreadyScanned, other?.reason)
        assertEquals("A-01", other?.detail)
    }

    @Test
    fun actualAssignmentTakesPrecedenceOverDuplicateFlag() {
        val rejected = snapshot.copy(eligible = false, locationCode = "A-01")
        val error = inboundScanRejection(batch, rejected, null, true)
        assertEquals(InboundClientError.BoxAlreadyAssigned, error?.reason)
        assertEquals("A-01", error?.detail)
        assertEquals(InboundClientError.BoxAlreadyAssigned, inboundScanRejection(batch, rejected.copy(locationCode = "", warehouseLocationId = 101), null, true)?.reason)
        assertEquals(InboundClientError.BoxDuplicateRecord, inboundScanRejection(batch, snapshot.copy(eligible = false), null, true)?.reason)
    }

    @Test
    fun knownStatusesAndServerExplanationArePreserved() {
        mapOf(
            "voided" to InboundClientError.BoxVoided,
            "cancelled" to InboundClientError.BoxCancelled,
            "shortage_marked" to InboundClientError.BoxShorted,
            "shorted" to InboundClientError.BoxShorted,
            "adjusted_out" to InboundClientError.BoxAdjustedOut,
            "exception" to InboundClientError.BoxException,
        ).forEach { (status, reason) ->
            val error = inboundScanRejection(batch, snapshot.copy(eligible = false, status = status, exceptionReason = "Specific reason"), null)
            assertEquals(reason, error?.reason)
            assertEquals("Specific reason", error?.detail)
        }
        assertEquals(InboundClientError.BoxManualReceived, inboundScanRejection(batch, snapshot.copy(receiptScanStatus = "manual_received"), null)?.reason)
        assertEquals(InboundClientError.BoxException, inboundScanRejection(batch, snapshot.copy(eligible = false, exceptionReason = "Specific reason"), null)?.reason)
        assertEquals(InboundClientError.BoxNotEligible, inboundScanRejection(batch, snapshot.copy(eligible = false), null)?.reason)
    }

    @Test
    fun allowedRebindAndVirtualTransferRemainAllowed() {
        assertNull(inboundScanRejection(batch, snapshot, null))
        assertNull(inboundScanRejection(batch, snapshot.copy(locationCode = "A-01", canScanRebind = true), null, true))
        assertNull(inboundScanRejection(batch, snapshot.copy(locationCode = "FSCM-VIRTUAL-INBOUND"), existing.copy(status = InboundItemStatus.Synced.value), true))
    }

    @Test
    fun admissionMatchesPreviousRulesAcrossStateCombinations() {
        for (eligible in listOf(false, true)) {
            for (rebind in listOf(false, true)) {
                for (location in listOf("", "A-01", "FSCM-VIRTUAL-INBOUND")) {
                    for (locationId in listOf(0L, 101L)) {
                        for (manual in listOf(false, true)) {
                            for (prior in listOf(null, existing, existing.copy(status = InboundItemStatus.Synced.value))) {
                                val row = snapshot.copy(eligible = eligible, canScanRebind = rebind, locationCode = location, warehouseLocationId = locationId, receiptScanStatus = if (manual) "manual_received" else "")
                                val virtual = location == "FSCM-VIRTUAL-INBOUND"
                                val wasAllowed = !manual && eligible && (rebind || virtual || (location.isBlank() && locationId == 0L)) &&
                                    (prior == null || ((rebind || virtual) && !prior.blocksVirtualRebind()))
                                assertEquals(wasAllowed, inboundScanRejection(batch, row, prior, true) == null)
                            }
                        }
                    }
                }
            }
        }
    }
}
