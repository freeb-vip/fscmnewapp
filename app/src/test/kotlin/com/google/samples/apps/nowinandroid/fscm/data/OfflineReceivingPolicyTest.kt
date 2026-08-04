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

class OfflineReceivingPolicyTest {
    @Test
    fun receivingDetailRefreshesWhenRemoteSnapshotIsTwelveHoursOld() {
        val now = 1_800_000_000_000L

        assertEquals(
            true,
            ReceivingSession(localSyncedAt = now - 12 * 60 * 60 * 1000L + 1).isRemoteFresh(now),
        )
        assertEquals(
            false,
            ReceivingSession(localSyncedAt = now - 12 * 60 * 60 * 1000L).isRemoteFresh(now),
        )
    }

    @Test
    fun syncFailureDisposition_retriesTransientFailures() {
        listOf(401, 408, 425, 429, 500, 503).forEach { statusCode ->
            assertEquals(
                OfflineSyncFailureDisposition.Retry,
                FscmApiException("temporary", statusCode).offlineSyncFailureDisposition(),
            )
        }
        assertEquals(
            OfflineSyncFailureDisposition.Retry,
            IllegalStateException("network").offlineSyncFailureDisposition(),
        )
    }

    @Test
    fun syncFailureDisposition_separatesConflictAndRejectedFailures() {
        assertEquals(
            OfflineSyncFailureDisposition.Conflict,
            FscmApiException("conflict", 409).offlineSyncFailureDisposition(),
        )
        listOf(400, 403, 404, 422).forEach { statusCode ->
            assertEquals(
                OfflineSyncFailureDisposition.Rejected,
                FscmApiException("rejected", statusCode).offlineSyncFailureDisposition(),
            )
        }
    }

    @Test
    fun offlineReceiving_requiresValidLeaseAndWriteScope() {
        val valid = SessionState(
            sessionMode = SessionMode.Offline,
            offlineLease = "lease",
            offlineLeaseId = "lease-id",
            offlineLeaseExpiresAt = System.currentTimeMillis() + 60_000,
            offlineScopes = listOf("receiving.write"),
        )

        valid.requireOfflineOperationPermission("receiving")
        assertFailsWith<IllegalStateException> {
            valid.copy(offlineScopes = emptyList()).requireOfflineOperationPermission("receiving")
        }
        assertFailsWith<IllegalStateException> {
            valid.copy(offlineLeaseExpiresAt = 0).requireOfflineOperationPermission("receiving")
        }
        SessionState(sessionMode = SessionMode.Online).requireOfflineOperationPermission("receiving")
    }

    @Test
    fun cancelPendingScan_removesItemAndRollsBackProgress() {
        val pending = ReceivingScanItem(id = -1, labelCode = "BOX-1", status = "pending_exception")
        val session = ReceivingSession(
            expectedBoxCount = 5,
            pendingBoxCount = 2,
            scannedBoxCount = 3,
            talliedBoxCount = 3,
            exceptionBoxCount = 1,
            scanItems = listOf(pending, ReceivingScanItem(id = 2, labelCode = "BOX-2")),
        )

        val result = session.withoutPendingScan(pending)

        assertEquals(listOf(2L), result.scanItems.map { it.id })
        assertEquals(3, result.pendingBoxCount)
        assertEquals(2, result.scannedBoxCount)
        assertEquals(2, result.talliedBoxCount)
        assertEquals(0, result.exceptionBoxCount)
        assertEquals("12:BOX-1", receivingScanResourceId(12, "BOX-1"))
    }

    @Test
    fun successfulScanImmediatelyUpdatesProgressAndIgnoresDuplicateMerge() {
        val item = ReceivingScanItem(id = 9, labelCode = "BOX-9", status = "normal")
        val session = ReceivingSession(
            expectedBoxCount = 5,
            pendingBoxCount = 5,
        )

        val received = session.withReceivedScan(item, damaged = false)
        val duplicateMerge = received.withReceivedScan(item, damaged = false)

        assertEquals(1, received.scannedBoxCount)
        assertEquals(1, received.talliedBoxCount)
        assertEquals(4, received.pendingBoxCount)
        assertEquals(listOf("BOX-9"), received.scanItems.map { it.labelCode })
        assertEquals(received, duplicateMerge)
    }

    @Test
    fun duplicateReceivingResponseRecognizesFlagAndKnownStatuses() {
        assertEquals(true, ReceivingScanResponse(duplicate = true).isDuplicateReceipt())
        assertEquals(
            true,
            ReceivingScanResponse(data = ReceivingScanItem(status = "duplicate_received")).isDuplicateReceipt(),
        )
        assertEquals(
            false,
            ReceivingScanResponse(data = ReceivingScanItem(status = "normal")).isDuplicateReceipt(),
        )
    }

    @Test
    fun duplicateReceivingFailureRecognizesConflictAndServerMessage() {
        assertEquals(true, FscmApiException("conflict", 409).isDuplicateReceivingFailure())
        assertEquals(
            true,
            FscmApiException("unprocessable", 422, serverMessage = "箱唛已收货").isDuplicateReceivingFailure(),
        )
        assertEquals(false, FscmApiException("wrong order", 422).isDuplicateReceivingFailure())
    }

    @Test
    fun damagedScanImmediatelyUpdatesExceptionCount() {
        val result = ReceivingSession(expectedBoxCount = 2)
            .withReceivedScan(
                ReceivingScanItem(id = 10, labelCode = "BOX-10", status = "damaged"),
                damaged = true,
            )

        assertEquals(1, result.talliedBoxCount)
        assertEquals(1, result.pendingBoxCount)
        assertEquals(1, result.exceptionBoxCount)
    }

    @Test
    fun receivingCompletesWhenEitherReceivedCountReachesExpectedTotal() {
        assertEquals(
            true,
            ReceivingSession(expectedBoxCount = 3, talliedBoxCount = 3).isReceivingComplete(),
        )
        assertEquals(
            true,
            ReceivingSession(expectedBoxCount = 3, scannedBoxCount = 4).isReceivingComplete(),
        )
        assertEquals(
            false,
            ReceivingSession(expectedBoxCount = 3, scannedBoxCount = 2, talliedBoxCount = 2).isReceivingComplete(),
        )
    }

    @Test
    fun receivingCompletionHandlesStatusesAndZeroTotal() {
        assertEquals(false, ReceivingSession(expectedBoxCount = 0).isReceivingComplete())
        assertEquals(false, ReceivingSession(expectedBoxCount = 0, scannedBoxCount = 1).isReceivingComplete())
        assertEquals(true, ReceivingSession(status = "completed").isReceivingComplete())
        assertEquals(true, ReceivingSession(status = "complete_pending_sync").isReceivingComplete())
    }
}
