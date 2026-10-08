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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PurchaseReceivingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun persistedSubmissionRetainsRequestIdentityAcrossRestart() {
        val original = SavedPurchaseReceipt(14, PurchaseReceiptRequest(fingerprint = "frozen"), submitted = true)
        val restored = json.decodeFromString<SavedPurchaseReceipt>(json.encodeToString(original))
        assertEquals(original.request.requestId, restored.request.requestId)
        assertEquals("frozen", restored.request.fingerprint)
        assertNotEquals(original.request.requestId, PurchaseReceiptRequest().requestId)
        val payload = json.parseToJsonElement(json.encodeToString(restored.request)).jsonObject
        assertEquals("normal", payload.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun externalVirtualReceiptAndPhysicalLocationStaySeparate() {
        val state = json.decodeFromString<PurchaseReceivingState>(
            """{"enabled":true,"boxes":{"12":{"status":"pending_move","quantity":24,"received_quantity":24,"location_code":"REAL","external_location_code":"VIRTUAL"}},"jobs":[{"id":"batch","status":"result_unknown","error_message":"待人工核实"}]}""",
        )
        assertEquals("pending_move", state.boxes.getValue("12").status)
        assertEquals(24, state.boxes.getValue("12").receivedQuantity)
        assertEquals("result_unknown", state.jobs.single().status)
    }

    @Test
    fun workspaceConfigRejectsStaleDraftWithoutChangingSavedSubmission() {
        val config = InventoryWorkspaceConfig(status = "configured", version = 2)
        config.validateDraft(2)
        assertFailsWith<IllegalStateException> { config.validateDraft(1) }
        assertFailsWith<IllegalStateException> { InventoryWorkspaceConfig().validateDraft() }
        val saved = SavedPurchaseReceipt(14, PurchaseReceiptRequest(), submitted = true, configVersion = 1)
        val restored = json.decodeFromString<SavedPurchaseReceipt>(json.encodeToString(saved))
        assertEquals(saved, restored)
    }
}
