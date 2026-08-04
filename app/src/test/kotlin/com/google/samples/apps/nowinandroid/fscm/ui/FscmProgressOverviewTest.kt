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

package com.google.samples.apps.nowinandroid.fscm.ui

import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptStatistics
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import org.junit.Test
import kotlin.test.assertEquals

class FscmProgressOverviewTest {
    @Test
    fun percentageHandlesZeroRoundingAndBounds() {
        assertEquals(0, progressPercentage(total = 0, completed = 8))
        assertEquals(67, progressPercentage(total = 3, completed = 2))
        assertEquals(100, progressPercentage(total = 10, completed = 12))
        assertEquals(0, progressPercentage(total = 10, completed = -1))
        assertEquals(0, progressPercentage(total = -10, completed = 5))
    }

    @Test
    fun warehouseProgressUsesLargestReceivedCountAndDerivesPending() {
        val metrics = warehouseReceivingProgress(
            ReceivingSession(
                expectedBoxCount = 10,
                pendingBoxCount = 10,
                talliedBoxCount = 8,
                scannedBoxCount = 10,
                exceptionBoxCount = 2,
            ),
        )

        assertEquals(FscmProgressMetrics(total = 10, pending = 0, completed = 10, exception = 2), metrics)
        assertEquals(100, metrics.percentage)
    }

    @Test
    fun centralProgressPreservesRepositoryStatistics() {
        val metrics = centralReceivingProgress(
            CentralReceiptStatistics(
                totalBoxCount = 120,
                pendingBoxCount = 31,
                receivedBoxCount = 89,
                exceptionBoxCount = 4,
            ),
        )

        assertEquals(FscmProgressMetrics(total = 120, pending = 31, completed = 89, exception = 4), metrics)
        assertEquals(74, metrics.percentage)
    }

    @Test
    fun inboundProgressUsesCenterExceptionCountSeparatelyFromLocalSyncIssues() {
        val metrics = inboundProgress(
            InboundSessionSummary(
                sessionId = 1,
                sessionCode = "RS-1",
                warehouseCode = "WH-1",
                warehouseName = "Warehouse 1",
                remoteStatus = "partial",
                totalCount = 50,
                inboundedCount = 20,
                localAssignedCount = 7,
                remainingCount = 23,
                centerPendingCount = 23,
                pendingCount = 4,
                conflictCount = 2,
                rejectedCount = 1,
                exceptionCount = 5,
                lastSyncAt = 0,
                snapshotComplete = true,
                locationCacheComplete = true,
                locationCacheRefreshedAt = 0,
                productCacheComplete = false,
                productCacheAt = 0,
                status = "active",
            ),
        )

        assertEquals(FscmProgressMetrics(total = 50, pending = 23, completed = 20, exception = 5), metrics)
        assertEquals(40, metrics.percentage)
    }
}
