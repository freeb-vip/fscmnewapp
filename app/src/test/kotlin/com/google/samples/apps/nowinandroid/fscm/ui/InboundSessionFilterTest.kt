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

import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import org.junit.Test
import kotlin.test.assertEquals

class InboundSessionFilterTest {
    @Test
    fun defaultFilter_onlyShowsActiveSessions() {
        val state = InboundSessionListUiState(
            sessions = InboundSessionStatus.entries.mapIndexed { index, status ->
                session(index.toLong(), status)
            },
        )

        assertEquals(InboundSessionFilter.Active, state.filter)
        assertEquals(
            InboundSessionStatus.entries.filterNot { it == InboundSessionStatus.Complete }.map { it.value },
            state.visibleSessions.map { it.status },
        )
    }

    @Test
    fun completeFilter_onlyShowsCompleteSessions() {
        val sessions = listOf(
            session(1, InboundSessionStatus.Active),
            session(2, InboundSessionStatus.Complete),
        )

        assertEquals(
            listOf(2L),
            filterInboundSessions(sessions, InboundSessionFilter.Complete).map { it.sessionId },
        )
    }

    @Test
    fun completedVirtualSession_remainsInCompletedFilterWhileRebindIsAvailable() {
        val completedVirtual =
            session(2, InboundSessionStatus.Complete).copy(
                hasVirtualInbound = true,
                rebindableCount = 1,
            )

        assertEquals(
            listOf(2L),
            filterInboundSessions(listOf(completedVirtual), InboundSessionFilter.Complete).map { it.sessionId },
        )
        assertEquals(
            emptyList(),
            filterInboundSessions(listOf(completedVirtual), InboundSessionFilter.Active),
        )
    }

    private fun session(id: Long, status: InboundSessionStatus) = InboundSessionSummary(
        sessionId = id,
        sessionCode = "RS-$id",
        warehouseCode = "WH",
        warehouseName = "Warehouse",
        remoteStatus = "",
        totalCount = 1,
        inboundedCount = 0,
        localAssignedCount = 0,
        remainingCount = 1,
        centerPendingCount = 1,
        pendingCount = 0,
        conflictCount = 0,
        rejectedCount = 0,
        exceptionCount = 0,
        lastSyncAt = 0,
        snapshotComplete = true,
        locationCacheComplete = true,
        locationCacheRefreshedAt = 0,
        productCacheComplete = false,
        productCacheAt = 0,
        status = status.value,
    )
}
