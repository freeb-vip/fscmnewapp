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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateDataTest {
    @Test
    fun versionCodeDeterminesOptionalAndForcedUpdates() {
        assertTrue(isAppUpdateAvailable(currentVersionCode = 8, latestVersionCode = 9))
        assertFalse(isAppUpdateAvailable(currentVersionCode = 9, latestVersionCode = 9))
        assertFalse(
            isAppUpdateForced(
                currentVersionCode = 8,
                latestVersionCode = 10,
                minSupportedVersionCode = 7,
            ),
        )
        assertTrue(
            isAppUpdateForced(
                currentVersionCode = 6,
                latestVersionCode = 10,
                minSupportedVersionCode = 7,
            ),
        )
    }

    @Test
    fun downloadPathIsResolvedAgainstBackendOrigin() {
        val url = resolveUpdateDownloadUrl(
            baseUrl = "https://fscm.example.com/api/",
            downloadPath = "/api/app-downloads/release-token",
        )

        assertTrue(url == "https://fscm.example.com/api/app-downloads/release-token")
    }

    @Test
    fun snoozeOnlySuppressesSameOptionalVersionBeforeDeadline() {
        val now = 1_000L
        assertFalse(
            shouldPromptForUpdate(
                releaseVersionCode = 9,
                forceUpdate = false,
                manual = false,
                snoozedVersionCode = 9,
                snoozedUntil = 2_000,
                now = now,
            ),
        )
        assertTrue(
            shouldPromptForUpdate(
                releaseVersionCode = 10,
                forceUpdate = false,
                manual = false,
                snoozedVersionCode = 9,
                snoozedUntil = 2_000,
                now = now,
            ),
        )
        assertTrue(
            shouldPromptForUpdate(
                releaseVersionCode = 9,
                forceUpdate = true,
                manual = false,
                snoozedVersionCode = 9,
                snoozedUntil = 2_000,
                now = now,
            ),
        )
        assertTrue(
            shouldPromptForUpdate(
                releaseVersionCode = 9,
                forceUpdate = false,
                manual = true,
                snoozedVersionCode = 9,
                snoozedUntil = 2_000,
                now = now,
            ),
        )
    }
}
