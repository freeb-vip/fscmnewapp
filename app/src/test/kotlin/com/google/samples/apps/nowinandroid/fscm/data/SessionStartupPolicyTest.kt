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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStartupPolicyTest {
    @Test
    fun storedSessionRefreshRequiresLocalUserAndAccessToken() {
        assertTrue(
            shouldRefreshStoredSession(
                SessionState(token = "access", user = UserProfile(id = 7)),
            ),
        )
        assertFalse(shouldRefreshStoredSession(SessionState(token = "access")))
        assertFalse(shouldRefreshStoredSession(SessionState(user = UserProfile(id = 7))))
    }

    @Test
    fun definitiveExpiryClearsStoredSession() = runBlocking {
        var cleared = false
        var enteredOffline = false

        val result = recoverStoredSessionAfterRefreshFailure(
            current = onlineSession(),
            error = SessionExpiredException(),
            clearSession = { cleared = true },
            enterOfflineMode = {
                enteredOffline = true
                true
            },
        )

        assertEquals(StoredSessionRefreshResult.Expired, result)
        assertTrue(cleared)
        assertFalse(enteredOffline)
    }

    @Test
    fun transientFailureUsesValidOfflineLeaseWhenAvailable() = runBlocking {
        var cleared = false
        val result = recoverStoredSessionAfterRefreshFailure(
            current = onlineSession().copy(
                offlineLease = "lease",
                offlineLeaseExpiresAt = System.currentTimeMillis() + 60_000,
            ),
            error = IllegalStateException("network unavailable"),
            clearSession = { cleared = true },
            enterOfflineMode = { true },
        )

        assertEquals(StoredSessionRefreshResult.Offline, result)
        assertFalse(cleared)
    }

    @Test
    fun transientFailureWithoutOfflineLeasePreservesStoredSession() = runBlocking {
        var cleared = false
        var enteredOffline = false
        val result = recoverStoredSessionAfterRefreshFailure(
            current = onlineSession(),
            error = IllegalStateException("network unavailable"),
            clearSession = { cleared = true },
            enterOfflineMode = {
                enteredOffline = true
                true
            },
        )

        assertEquals(StoredSessionRefreshResult.Unavailable, result)
        assertFalse(cleared)
        assertFalse(enteredOffline)
    }

    private fun onlineSession() = SessionState(
        token = "access",
        refreshToken = "refresh",
        user = UserProfile(id = 7),
    )
}
