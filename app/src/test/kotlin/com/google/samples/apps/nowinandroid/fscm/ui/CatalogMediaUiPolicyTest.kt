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

import com.google.samples.apps.nowinandroid.fscm.data.CatalogMediaRequest
import org.junit.Test
import kotlin.test.assertEquals

class CatalogMediaUiPolicyTest {
    @Test
    fun edgeFailurePrefersMediaCenterUrl() {
        val media = CatalogMediaRequest(
            url = "http://edge/media",
            centralUrl = "https://center/media.jpg",
            cacheKey = "media:v1",
            headerName = "X-Edge-Ticket",
            headerValue = "ticket",
        )

        assertEquals("https://center/media.jpg", catalogMediaFallbackUrl(media, "https://legacy/image.jpg"))
        assertEquals("https://legacy/image.jpg", catalogMediaFallbackUrl(media.copy(centralUrl = ""), "https://legacy/image.jpg"))
    }
}
