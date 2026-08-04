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

import com.google.samples.apps.nowinandroid.fscm.data.MobileAnnouncement
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkbenchLoadPolicyTest {
    @Test
    fun onlineLoadShowsCachedOverviewWhileRefreshing() {
        val cached = cachedOverview()

        val state = workbenchLoadingState(cached, isOffline = false)

        assertTrue(state.loading)
        assertSame(cached, state.data)
        assertNull(state.error)
    }

    @Test
    fun onlineLoadWithoutCacheKeepsPageInLoadingState() {
        val state = workbenchLoadingState(cached = null, isOffline = false)

        assertTrue(state.loading)
        assertNull(state.data)
    }

    @Test
    fun offlineLoadCompletesWithCachedOrEmptyOverview() {
        val cached = cachedOverview()

        assertSame(cached, workbenchLoadingState(cached, isOffline = true).data)
        val emptyState = workbenchLoadingState(cached = null, isOffline = true)
        assertFalse(emptyState.loading)
        assertEquals(MobileWorkbenchOverview(), emptyState.data)
    }

    @Test
    fun refreshFailureKeepsPreviouslyDisplayedCache() {
        val cached = cachedOverview()
        val error = UiText.Dynamic("refresh failed")

        val failed = workbenchRefreshFailureState(
            current = LoadState(loading = true, data = cached),
            error = error,
        )

        assertFalse(failed.loading)
        assertSame(cached, failed.data)
        assertSame(error, failed.error)
    }

    private fun cachedOverview() = MobileWorkbenchOverview(
        announcements = listOf(MobileAnnouncement(id = 1, title = "Cached")),
    )
}
