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
}
