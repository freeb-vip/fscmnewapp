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

import com.google.samples.apps.nowinandroid.fscm.data.LoginCaptcha
import com.google.samples.apps.nowinandroid.fscm.data.LoginSecurityState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginSecurityPolicyTest {
    @Test
    fun changingUsernameClearsChallengeForPreviousAccount() {
        val state = captchaState().copy(currentUsername = "first-user")

        val updated = state.withUsername("second-user")

        assertEquals("second-user", updated.currentUsername)
        assertEquals("", updated.challengeUsername)
        assertNull(updated.security)
    }

    @Test
    fun captchaAndLockStateControlLoginSubmission() {
        val captchaState = captchaState()

        assertFalse(isLoginSubmissionEnabled("user", "password", "", captchaState))
        assertTrue(isLoginSubmissionEnabled("user", "password", "7", captchaState))
        assertFalse(
            isLoginSubmissionEnabled(
                "user",
                "password",
                "7",
                captchaState.copy(security = captchaState.security?.copy(lockRemainingSeconds = 10)),
            ),
        )
    }

    @Test
    fun lockCountdownUsesElapsedRealtimeDeadline() {
        val deadline = 3_000L

        assertEquals(3, remainingLoginLockSeconds(deadline, 0L))
        assertEquals(2, remainingLoginLockSeconds(deadline, 1_001L))
        assertEquals(0, remainingLoginLockSeconds(deadline, 3_000L))
        assertEquals(0, remainingLoginLockSeconds(deadline, 4_000L))
    }

    private fun captchaState() = LoginUiState(
        currentUsername = "user",
        challengeUsername = "user",
        security = LoginSecurityState(
            failedCount = 3,
            remainingAttempts = 2,
            requireCaptcha = true,
            captcha = LoginCaptcha(captchaId = "challenge-1", question = "3 + 4 = ?"),
        ),
    )
}
