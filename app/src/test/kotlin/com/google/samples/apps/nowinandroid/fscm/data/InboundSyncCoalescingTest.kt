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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InboundSyncCoalescingTest {
    @Test
    fun slowSyncCoalescesScansAndDrainsWorkArrivingDuringUpload() = runTest {
        var requested = 10L
        var completed = 0L
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            drainInboundSyncRequests({ requested }, { completed }, { completed = it }, { true }) {
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        started.await()
        requested = 20
        assertEquals(0L, completed)
        release.complete(Unit)
        job.join()
        assertEquals(2, calls)
        assertEquals(20L, completed)
        drainInboundSyncRequests({ requested }, { completed }, { completed = it }, { true }) { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun failureKeepsWorkPendingForRetryAndChangedScopeDoesNotUpload() = runTest {
        var completed = 0L
        assertFailsWith<IllegalStateException> {
            drainInboundSyncRequests({ 3 }, { completed }, { completed = it }, { true }) { error("offline") }
        }
        assertEquals(0L, completed)
        drainInboundSyncRequests({ 3 }, { completed }, { completed = it }, { false }) { error("wrong scope") }
        assertEquals(0L, completed)
        drainInboundSyncRequests({ 3 }, { completed }, { completed = it }, { true }) { }
        assertEquals(3L, completed)
    }
}
