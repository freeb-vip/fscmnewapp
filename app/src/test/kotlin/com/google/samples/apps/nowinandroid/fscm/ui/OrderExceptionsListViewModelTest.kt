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

import com.google.samples.apps.nowinandroid.fscm.data.ExceptionChange
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionListSource
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionScope
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseException
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class OrderExceptionsListViewModelTest {
    private class Source : ExceptionListSource {
        override val changes = MutableStateFlow<ExceptionChange?>(null)
        val actor = ExceptionScope(1, 1)
        override val scopes = MutableStateFlow<ExceptionScope?>(actor)
        val calls = mutableListOf<Pair<Int, Map<String, String>>>()
        var failPage: Int? = null
        var response: suspend (Int) -> WarehouseExceptionPage = { page -> WarehouseExceptionPage(items = listOf(WarehouseException(id = page.toLong())), total = 3, open = 3) }
        override suspend fun scope() = actor
        override suspend fun list(scope: ExceptionScope, resolved: Boolean, filters: Map<String, String>, page: Int): WarehouseExceptionPage {
            calls += page to filters
            if (page == failPage) error("network")
            return response(page)
        }
    }

    @Test
    fun retriesFailedAppendWithoutLosingRowsAndRefreshesFirstPageAfterTTL() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            var time = 1L
            val vm = OrderExceptionsListViewModel(source) { time }
            vm.resume()
            advanceUntilIdle()
            vm.resume()
            advanceUntilIdle()
            assertEquals(1, source.calls.size)
            source.failPage = 2
            vm.refresh(more = true)
            advanceUntilIdle()
            assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
            assertNotNull(vm.state.value.error)
            source.failPage = null
            vm.retry()
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 2), source.calls.map { it.first })
            assertEquals(listOf(1L, 2L), vm.state.value.rows.map { it.id })
            vm.saveScroll(1, 18)
            time += 60_001
            vm.resume()
            advanceUntilIdle()
            assertEquals(1, source.calls.last().first)
            assertEquals(1, vm.state.value.rows.size)
            assertEquals(18, vm.scrollOffset)
            source.failPage = 1
            vm.refresh(userInitiated = true)
            advanceUntilIdle()
            assertEquals(1, vm.state.value.rows.size)
            assertFalse(vm.state.value.refreshing)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleSearchCannotOverwriteNewResultsAndClearResetsAllFilters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val old = CompletableDeferred<WarehouseExceptionPage>()
            source.response = { withContext(NonCancellable) { old.await() } }
            val vm = OrderExceptionsListViewModel(source) { 1L }
            vm.resume()
            runCurrent()
            source.response = { WarehouseExceptionPage(items = listOf(WarehouseException(id = 9)), total = 1, open = 1) }
            vm.editFilter("keyword", " 000SKU ")
            vm.editFilter("employee_code", " EMP1 ")
            vm.search()
            runCurrent()
            old.complete(WarehouseExceptionPage(items = listOf(WarehouseException(id = 1))))
            advanceUntilIdle()
            assertEquals(listOf(9L), vm.state.value.rows.map { it.id })
            assertEquals(mapOf("keyword" to "000SKU", "employee_code" to "EMP1"), source.calls.last().second)
            vm.search(clear = true)
            advanceUntilIdle()
            assertEquals(emptyMap(), source.calls.last().second)
            assertEquals(emptyMap(), vm.state.value.draft)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resolutionUpdatesCountsAndInvalidatesOnlyItsOwnWorkspace() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = OrderExceptionsListViewModel(source) { 1L }
            vm.resume()
            advanceUntilIdle()
            source.changes.value = ExceptionChange(ExceptionScope(1, 2), WarehouseException(id = 1, status = "resolved"))
            advanceUntilIdle()
            assertEquals(1, vm.state.value.rows.size)
            source.changes.value = ExceptionChange(source.actor, WarehouseException(id = 1, status = "resolved"))
            advanceUntilIdle()
            assertEquals(0, vm.state.value.rows.size)
            assertEquals(2, vm.state.value.open)
            assertEquals(1, vm.state.value.completed)
            vm.resume()
            advanceUntilIdle()
            assertEquals(2, source.calls.size)
            source.scopes.value = ExceptionScope(1, 2)
            advanceUntilIdle()
            assertEquals(0, vm.state.value.rows.size)
            assertFalse(vm.state.value.countsLoaded)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun snapshotRevisionChangeRestartsPagingAndFailurePreservesTimestamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            source.response = { page -> WarehouseExceptionPage(items = listOf(WarehouseException(id = page.toLong())), total = 3, open = 3, snapshotVersion = if (page == 1) 1 else 2, snapshotAt = "2026-11-16T00:00:00Z", refreshStatus = "ready") }
            val vm = OrderExceptionsListViewModel(source) { 1L }
            vm.resume()
            advanceUntilIdle()
            vm.refresh(more = true)
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 1), source.calls.map { it.first })
            assertEquals(listOf(1L), vm.state.value.rows.map { it.id })
            source.failPage = 1
            vm.refresh(userInitiated = true)
            advanceUntilIdle()
            assertEquals("2026-11-16T00:00:00Z", vm.state.value.snapshotAt)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
