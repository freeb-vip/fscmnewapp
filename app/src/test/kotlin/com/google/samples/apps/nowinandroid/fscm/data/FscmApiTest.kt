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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FscmApiTest {
    @Test
    fun payloadOrError_preservesStandardConflictData() {
        val response = Response.error<JsonElement>(
            409,
            """{"code":40942,"data":{"id":12,"location_code":"B-02"},"msg":"conflict"}"""
                .toResponseBody("application/json".toMediaType()),
        )

        val payload = assertNotNull(response.payloadOrError(Json))

        assertEquals(40942, payload.standardResponseCode())
        assertEquals("conflict", payload.messageFromPayload())
    }

    @Test
    fun effectiveLanguageTag_usesSupportedApplicationLocaleAndEnglishFallback() {
        assertEquals("zh-Hans", effectiveLanguageTag("zh-Hans-CN", "en-US"))
        assertEquals("th", effectiveLanguageTag("th-TH", "en-US"))
        assertEquals("my", effectiveLanguageTag(null, "my-MM"))
        assertEquals("en", effectiveLanguageTag(null, "fr-FR"))
    }

    @Test
    fun retryAuthenticatedRequest_refreshesAndRetriesAfterHttpUnauthorized() = runBlocking {
        var requests = 0
        var refreshes = 0
        val success = Response.success<JsonElement>(buildJsonObject { put("code", 0) })

        val response = retryAuthenticatedRequest(
            auth = true,
            request = {
                requests += 1
                if (requests == 1) httpUnauthorizedResponse() else success
            },
            refreshSession = {
                refreshes += 1
                true
            },
        )

        assertSame(success, response)
        assertEquals(2, requests)
        assertEquals(1, refreshes)
    }

    @Test
    fun retryAuthenticatedRequest_refreshesAndRetriesAfterEnvelopeUnauthorized() = runBlocking {
        var requests = 0
        var refreshes = 0
        val success = Response.success<JsonElement>(buildJsonObject { put("code", 0) })
        val unauthorized = Response.success<JsonElement>(
            buildJsonObject {
                put("code", 401)
                put("msg", "Unauthorized")
            },
        )

        val response = retryAuthenticatedRequest(
            auth = true,
            request = {
                requests += 1
                if (requests == 1) unauthorized else success
            },
            refreshSession = {
                refreshes += 1
                true
            },
        )

        assertSame(success, response)
        assertEquals(2, requests)
        assertEquals(1, refreshes)
    }

    @Test
    fun retryAuthenticatedRequest_keepsInitialResponseWhenRefreshFails() = runBlocking {
        var requests = 0
        var refreshes = 0
        val unauthorized = httpUnauthorizedResponse()

        val response = retryAuthenticatedRequest(
            auth = true,
            request = {
                requests += 1
                unauthorized
            },
            refreshSession = {
                refreshes += 1
                false
            },
        )

        assertSame(unauthorized, response)
        assertEquals(1, requests)
        assertEquals(1, refreshes)
    }

    @Test
    fun retryAuthenticatedRequest_retriesOnlyOnceWhenRetryIsStillUnauthorized() = runBlocking {
        var requests = 0
        var refreshes = 0
        val firstUnauthorized = httpUnauthorizedResponse()
        val retryUnauthorized = httpUnauthorizedResponse()

        val response = retryAuthenticatedRequest(
            auth = true,
            request = {
                requests += 1
                if (requests == 1) firstUnauthorized else retryUnauthorized
            },
            refreshSession = {
                refreshes += 1
                true
            },
        )

        assertSame(retryUnauthorized, response)
        assertEquals(2, requests)
        assertEquals(1, refreshes)
    }

    @Test
    fun retryAuthenticatedRequest_doesNotRefreshForOtherBusinessErrors() = runBlocking {
        var requests = 0
        var refreshes = 0
        val forbidden = Response.success<JsonElement>(
            buildJsonObject {
                put("code", 403)
                put("msg", "Forbidden")
            },
        )

        val response = retryAuthenticatedRequest(
            auth = true,
            request = {
                requests += 1
                forbidden
            },
            refreshSession = {
                refreshes += 1
                true
            },
        )

        assertSame(forbidden, response)
        assertEquals(1, requests)
        assertEquals(0, refreshes)
    }

    private fun httpUnauthorizedResponse(): Response<JsonElement> =
        Response.error(
            401,
            "{}".toResponseBody("application/json".toMediaType()),
        )
}
