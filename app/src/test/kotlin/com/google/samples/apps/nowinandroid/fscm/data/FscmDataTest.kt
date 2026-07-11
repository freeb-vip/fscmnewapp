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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FscmDataTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun unwrapStandardPayload_returnsDataWhenCodeIsZero() {
        val payload = buildJsonObject {
            put("code", 0)
            put("data", buildJsonObject { put("name", "FSCM") })
            put("msg", "ok")
        }

        val data = payload.unwrapStandardPayload(statusCode = 200) as JsonObject

        assertEquals("FSCM", data["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun unwrapStandardPayload_throwsApiExceptionWhenCodeIsNotZero() {
        val payload = buildJsonObject {
            put("code", 403)
            put("msg", "没有权限")
        }

        val error = assertFailsWith<FscmApiException> {
            payload.unwrapStandardPayload(statusCode = 200)
        }

        assertEquals("没有权限", error.message)
        assertEquals(200, error.statusCode)
    }

    @Test
    fun visibleMobileApps_filtersByVisibilityAndPermissionsThenSorts() {
        val overview = MobileWorkbenchOverview(
            appConfig = MobileAppConfig(
                apps = listOf(
                    MobileAppEntry(key = "hidden", title = "隐藏", visible = false, sort = 1),
                    MobileAppEntry(key = "denied", title = "无权限", permission = "mobile-app:denied", sort = 2),
                    MobileAppEntry(key = "receiving", title = "收货", permission = "mobile-app:receiving", businessPermission = "receiving_records", sort = 4),
                    MobileAppEntry(key = "profit", title = "利润", sort = 3),
                ),
            ),
        )

        val apps = visibleMobileApps(
            overview = overview,
            permissions = listOf("mobile-app:receiving", "receiving_records"),
        )

        assertEquals(listOf("profit", "receiving"), apps.map { it.key })
    }

    @Test
    fun visibleMobileApps_offlineShowsOnlyDeclaredAndImplementedApps() {
        val overview = MobileWorkbenchOverview(
            appConfig = MobileAppConfig(
                apps = listOf(
                    MobileAppEntry(key = "profit", title = "利润", sort = 1, offlineSupported = true),
                    MobileAppEntry(
                        key = "receiving",
                        title = "收货",
                        permission = "mobile-app:receiving",
                        businessPermission = "receiving_records",
                        sort = 2,
                        offlineSupported = true,
                        offlineRequiredPermissions = listOf("mobile-app:receiving"),
                    ),
                    MobileAppEntry(key = "todos", title = "待办", sort = 3, offlineSupported = false),
                ),
            ),
        )

        val apps = visibleMobileApps(
            overview = overview,
            permissions = listOf("mobile-app:receiving", "receiving_records"),
            sessionMode = SessionMode.Offline,
        )

        assertEquals(listOf("receiving"), apps.map { it.key })
    }

    @Test
    fun offlineCredentialHasher_usesPasswordAndDeviceSecret() {
        val salt = "salt".encodeToByteArray()
        val secret = "device-secret".encodeToByteArray()

        val verifier = OfflineCredentialHasher.verifier("demo", "password", salt, secret)
        val same = OfflineCredentialHasher.verifier("demo", "password", salt, secret)
        val different = OfflineCredentialHasher.verifier("demo", "wrong", salt, secret)

        assertEquals(verifier, same)
        assertNotEquals(verifier, different)
        Base64.getDecoder().decode(verifier)
    }

    @Test
    fun sessionState_hasUsableOfflineCredentialRequiresCompleteUnexpiredCredential() {
        val valid = SessionState(
            user = UserProfile(id = 1, username = "demo"),
            offlineCredentialId = "credential",
            offlineUsername = "demo",
            offlinePasswordSalt = "salt",
            offlinePasswordVerifier = "verifier",
            offlineExpiresAt = System.currentTimeMillis() + 60_000,
        )

        val expired = valid.copy(offlineExpiresAt = System.currentTimeMillis() - 1)
        val missingCredential = valid.copy(offlineCredentialId = "")

        assertEquals(true, valid.hasUsableOfflineCredential)
        assertEquals(false, expired.hasUsableOfflineCredential)
        assertEquals(false, missingCredential.hasUsableOfflineCredential)
        assertEquals(false, SessionState().hasUsableOfflineCredential)
    }

    @Test
    fun decodeReceivingSessionsPayload_acceptsRawArray() {
        val payload = json.encodeToJsonElement(
            listOf(
                ReceivingSession(id = 1, code = "RCV-001"),
                ReceivingSession(id = 2, code = "RCV-002"),
            ),
        )

        val rows = decodeReceivingSessionsPayload(payload, json)

        assertEquals(listOf("RCV-001", "RCV-002"), rows.map { it.code })
    }

    @Test
    fun decodeReceivingSessionsPayload_acceptsPagedObject() {
        val payload = buildJsonObject {
            put(
                "records",
                json.encodeToJsonElement(
                    listOf(ReceivingSession(id = 9, code = "RCV-009")),
                ),
            )
            put("total", 1)
        }

        val rows = decodeReceivingSessionsPayload(payload, json)

        assertEquals(listOf("RCV-009"), rows.map { it.code })
    }
}
