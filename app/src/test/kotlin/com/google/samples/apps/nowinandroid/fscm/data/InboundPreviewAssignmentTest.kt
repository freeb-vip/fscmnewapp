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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundPreviewAssignmentTest {
    private val batch = LocalInboundBatchEntity("batch", 7, 2, 11, "B-02", 202)
    private val preview = ReceivingScanResponse(
        item = ReceivingScanItem(boxPlanId = 5518, labelCode = "C3EAF29DF6CC", status = "normal", locationCode = "B-02", warehouseLocationId = 202),
    )

    @Test
    fun targetIsNeverTreatedAsAssignmentAndStaleCacheIsCleared() {
        val stale = inboundSnapshotFromPreview(batch, null, preview, ReceivingCurrentAssignment("B-02", 202))
        val actual = inboundSnapshotFromPreview(batch, stale, preview, ReceivingCurrentAssignment())
        assertEquals("", actual.locationCode)
        assertEquals(0L, actual.warehouseLocationId)
        assertTrue(actual.canScanIntoInboundLocation())
        assertEquals("B-02", batch.locationCode)
        requireInboundRebindConfirmation(batch, actual, null)
    }

    @Test
    fun realAssignmentStillBlocksAndFormalIdAloneIsNotUnassigned() {
        listOf(ReceivingCurrentAssignment("A-01", 101), ReceivingCurrentAssignment("", 101)).forEach {
            assertFalse(inboundSnapshotFromPreview(batch, null, preview, it).canScanIntoInboundLocation())
        }
        assertFalse(inboundSnapshotFromPreview(batch, null, preview.copy(duplicate = true), ReceivingCurrentAssignment()).eligible)
    }

    @Test
    fun rebindConfirmationUsesPersistedSourceRatherThanTarget() {
        val actual = inboundSnapshotFromPreview(batch, null, preview.copy(canScanRebind = true), ReceivingCurrentAssignment("A-01", 101))
        assertTrue(actual.canScanIntoInboundLocation())
        val error = kotlin.test.assertFailsWith<InboundRebindConfirmationRequired> {
            requireInboundRebindConfirmation(batch, actual, null)
        }
        assertEquals("A-01", error.confirmation.sourceLocationCode)
        assertEquals("B-02", error.confirmation.targetLocationCode)
        val virtual = inboundSnapshotFromPreview(batch, null, preview, ReceivingCurrentAssignment("FSCM-VIRTUAL-INBOUND", 99))
        assertTrue(virtual.canScanIntoInboundLocation())
        requireInboundRebindConfirmation(batch, virtual, null)
    }

    @Test
    fun missingAssignmentIsDistinguishableFromExplicitlyUnassigned() {
        assertNull(Json.decodeFromString<ReceivingScanResponse>("{}").currentAssignment)
        val decoded = Json.decodeFromString<ReceivingScanResponse>("""{"current_assignment":{"location_code":"","warehouse_location_id":null}}""")
        assertEquals(ReceivingCurrentAssignment(), decoded.currentAssignment)
    }

    @Test
    fun oldServerReadsPersistedStateAndReadFailureNeverAcceptsPreviewTarget() = runBlocking<Unit> {
        var reads = 0
        val assignment = resolveInboundPreviewAssignment(preview) {
            reads++
            ReceivingCurrentAssignment()
        }
        assertEquals(1, reads)
        assertEquals("", assignment.locationCode)
        resolveInboundPreviewAssignment(preview.copy(currentAssignment = assignment)) {
            error("new servers must not need another request")
        }
        kotlin.test.assertFailsWith<IllegalStateException> {
            resolveInboundPreviewAssignment(preview) { error("offline") }
        }
    }
}
