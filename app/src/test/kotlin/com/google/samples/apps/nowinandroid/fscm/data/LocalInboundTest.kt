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

class LocalInboundTest {
    @Test
    fun locationValidation_isStrictAndDoesNotUppercase() {
        assertTrue(isValidInboundLocation("A-01"))
        assertTrue(isValidInboundLocation("WH1-A-009\n"))
        assertFalse(isValidInboundLocation("a-01"))
        assertFalse(isValidInboundLocation("A/01"))
        assertFalse(isValidInboundLocation(""))
        assertFalse(isValidInboundLocation("A".repeat(101)))
    }

    @Test
    fun pendingLocalBox_completesLocalProgressWithoutDoubleCountingRemote() {
        val snapshots = listOf(
            snapshot("BOX-A"),
            snapshot("BOX-B", location = "B-01"),
            snapshot("BOX-C", eligible = false),
        )
        val progress = calculateInboundProgress(snapshots, listOf(item("BOX-A", InboundItemStatus.Pending)))

        assertEquals(1, progress.localContribution)
        assertEquals(0, progress.remaining)
        assertEquals(1, progress.pending)
        assertEquals(0, progress.conflicts)
    }

    @Test
    fun rejectedBox_doesNotCountAsAssigned() {
        val progress = calculateInboundProgress(
            snapshots = listOf(snapshot("BOX-A")),
            items = listOf(item("BOX-A", InboundItemStatus.Rejected)),
        )

        assertEquals(0, progress.localContribution)
        assertEquals(1, progress.remaining)
        assertEquals(1, progress.rejected)
    }

    @Test
    fun conflictCountsAsLocallyAssignedButRemainsUnresolved() {
        val progress = calculateInboundProgress(
            snapshots = listOf(snapshot("BOX-A")),
            items = listOf(item("BOX-A", InboundItemStatus.ConflictPending)),
        )

        assertEquals(1, progress.localContribution)
        assertEquals(0, progress.remaining)
        assertEquals(1, progress.conflicts)
    }

    @Test
    fun refreshedRemoteLocation_removesLocalContribution() {
        val progress = calculateInboundProgress(
            snapshots = listOf(snapshot("BOX-A", location = "A-01")),
            items = listOf(item("BOX-A", InboundItemStatus.Synced)),
        )

        assertEquals(0, progress.localContribution)
        assertEquals(0, progress.remaining)
    }

    private fun snapshot(
        label: String,
        location: String = "",
        eligible: Boolean = true,
    ) = LocalInboundSnapshotEntity(
        namespaceId = 1,
        sessionId = 10,
        boxPlanId = label.hashCode().toLong(),
        labelCode = label,
        boxNo = label,
        boxUid = "",
        status = "received",
        inboundStatus = if (eligible) "pending" else "exception",
        locationCode = location,
        eligible = eligible,
        snapshotAt = 1,
    )

    private fun item(label: String, status: InboundItemStatus) = LocalInboundItemEntity(
        requestId = "request-$label",
        batchId = "batch-1",
        namespaceId = 1,
        sessionId = 10,
        boxPlanId = label.hashCode().toLong(),
        labelCode = label,
        rawCode = label,
        localLocationCode = "A-01",
        status = status.value,
    )
}
