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
import kotlin.test.assertFailsWith

class InboundRebindConfirmationTest {
    private val batch = LocalInboundBatchEntity("batch", 7, 9, 11, "B-02", 202)
    private val snapshot = LocalInboundSnapshotEntity(
        userId = 7, namespaceId = 9, sessionId = 11, boxPlanId = 1, labelCode = "BOX-A",
        boxNo = "1", boxUid = "UID-A", status = "normal", inboundStatus = "inbounded",
        locationCode = "A-01", warehouseLocationId = 101, eligible = true, canScanRebind = true,
        snapshotAt = 100,
    )

    @Test
    fun ordinaryLocationRequiresConfirmationEvenWhenBackendAllowsRebind() {
        val request = challenge()
        assertEquals("BOX-A", request.labelCode)
        assertEquals("A-01", request.sourceLocationCode)
        assertEquals("B-02", request.targetLocationCode)
        requireInboundRebindConfirmation(batch, snapshot, request)
    }

    @Test
    fun virtualAndUnassignedLocationsDoNotRequireConfirmation() {
        listOf("", "FSCM-VIRTUAL-INBOUND", " fscm-virtual-inbound ").forEach { code ->
            requireInboundRebindConfirmation(batch, snapshot.copy(locationCode = code), null)
        }
    }

    @Test
    fun confirmationDoesNotAuthorizeAnotherBoxBatchOrChangedLocation() {
        val request = challenge()
        listOf(
            request.copy(labelCode = "BOX-B"),
            request.copy(batchId = "other-batch"),
            request.copy(sourceLocationCode = "A-03"),
            request.copy(sourceLocationId = 103),
            request.copy(targetLocationCode = "B-04"),
            request.copy(targetLocationId = 204),
        ).forEach { stale ->
            assertFailsWith<InboundRebindConfirmationRequired> {
                requireInboundRebindConfirmation(batch, snapshot, stale)
            }
        }
    }

    @Test
    fun rescanningWithoutConfirmationAndScanningSameLocationStillRequireConfirmation() {
        challenge()
        challenge()
        assertFailsWith<InboundRebindConfirmationRequired> {
            requireInboundRebindConfirmation(batch.copy(locationCode = "A-01", warehouseLocationId = 101), snapshot, null)
        }
    }

    @Test
    fun newerSuccessfulLocalSyncProtectsOfflineScansWithStaleVirtualSnapshot() {
        val existing = LocalInboundItemEntity(
            requestId = "previous", batchId = "previous-batch", userId = 7, namespaceId = 9,
            sessionId = 11, boxPlanId = 1, labelCode = "BOX-A", rawCode = "BOX-A",
            localLocationCode = "A-01", localWarehouseLocationId = 101, status = InboundItemStatus.Synced.value,
            updatedAt = 200,
        )
        val error = assertFailsWith<InboundRebindConfirmationRequired> {
            requireInboundRebindConfirmation(batch, snapshot.copy(locationCode = "FSCM-VIRTUAL-INBOUND"), null, existing)
        }
        assertEquals("A-01", error.confirmation.sourceLocationCode)
        requireInboundRebindConfirmation(batch, snapshot.copy(locationCode = "FSCM-VIRTUAL-INBOUND"), error.confirmation, existing)
        // A fresh authoritative virtual location supersedes the older local result.
        requireInboundRebindConfirmation(batch, snapshot.copy(locationCode = "FSCM-VIRTUAL-INBOUND", snapshotAt = 300), null, existing)
    }

    private fun challenge() = assertFailsWith<InboundRebindConfirmationRequired> {
        requireInboundRebindConfirmation(batch, snapshot, null)
    }.confirmation
}
