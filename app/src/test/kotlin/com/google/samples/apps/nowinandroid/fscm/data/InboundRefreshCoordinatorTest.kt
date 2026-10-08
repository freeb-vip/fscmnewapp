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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InboundRefreshCoordinatorTest {
    @Test
    fun concurrentScreensShareOneDownloadButDifferentScopesDoNot() = runTest {
        val coordinator = InboundRefreshCoordinator<String, Int>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run("1:2:3") {
                calls++
                release.await()
                42
            }
        }
        val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run("1:2:3") { error("duplicate request") } }
        assertEquals(
            7,
            coordinator.run("9:2:3") {
                calls++
                7
            },
        )
        release.complete(Unit)
        assertEquals(42, first.await())
        assertEquals(42, joined.await())
        assertEquals(2, calls)
    }

    @Test
    fun uploadAcknowledgedDuringDownloadRequiresExactlyOneNewDownload() = runTest {
        val coordinator = InboundRefreshCoordinator<Long, Int>()
        val release = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        var calls = 0
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run(1) {
                calls++
                release.await()
                1
            }
        }
        val acknowledgedAt = System.nanoTime()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run(1, acknowledgedAt) {
                calls++
                secondRelease.await()
                2
            }
        }
        val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run(1, acknowledgedAt) { error("duplicate follow-up") } }
        release.complete(Unit)
        old.await()
        testScheduler.runCurrent()
        secondRelease.complete(Unit)
        assertEquals(2, first.await())
        assertEquals(2, joined.await())
        assertEquals(2, calls)
    }

    @Test
    fun failedDownloadIsNotRetainedAsSuccessfulCache() = runTest {
        val coordinator = InboundRefreshCoordinator<Int, Int>()
        assertFailsWith<IllegalStateException> { coordinator.run(1) { error("interrupted page") } }
        assertEquals(2, coordinator.run(1) { 2 })
    }
}
