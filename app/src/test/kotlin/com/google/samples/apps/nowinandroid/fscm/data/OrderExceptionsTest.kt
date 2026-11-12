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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class OrderExceptionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authorizedExceptionEntrySurvivesApplicationCatalogFiltering() {
        val entry = MobileAppEntry(key = "order_exceptions", title = "异常订单", permission = "mobile-app:order-exceptions", sort = 44)
        val permissions = listOf(entry.permission)
        val overview = MobileWorkbenchOverview(appConfig = MobileAppConfig(apps = listOf(entry)))
        assertEquals(listOf(entry.key), visibleMobileApps(overview, permissions).map { it.key })
        assertEquals(listOf(entry.key), visibleMobileApps(MobileWorkbenchOverview(apps = listOf(entry)), permissions).map { it.key })
        assertEquals(emptyList(), visibleMobileApps(overview, emptyList()))
        assertEquals(emptyList(), visibleMobileApps(overview, permissions, SessionMode.Offline))
        assertEquals(emptyList(), visibleMobileApps(overview.copy(appConfig = MobileAppConfig(apps = listOf(entry.copy(visible = false)))), permissions))
        assertEquals(emptyList(), visibleMobileApps(null, permissions))
    }

    @Test
    fun resolutionIncludesRequiredDefaultResponsibilityAndStableRequest() {
        val form = WarehouseExceptionForm(requestId = "11111111-1111-4111-8111-111111111111", version = 3, problemType = "operation", participantIds = listOf("scan:1"))
        val first = encodeWarehouseExceptionForm(json, form).jsonObject
        assertEquals("person", first.getValue("responsibility_type").jsonPrimitive.content)
        assertEquals(0, first.getValue("attachment_keys").jsonArray.size)
        assertEquals("", first.getValue("measures").jsonPrimitive.content)
        assertEquals(first, encodeWarehouseExceptionForm(json, form))
        assertFalse("actor_id" in first)
        assertFalse("created_at" in first)
    }

    @Test
    fun existingWebReportDecodesWithoutLosingLeadingZeroes() {
        val report = json.decodeFromString(WarehouseException.serializer(), """{"id":1,"version":2,"status":"open","order":{"platform_order_id":"0000123","tracking_nos":["000456"]},"types":[{"name":"包装破损"}],"attachments":[],"events":[],"timezone":"Asia/Bangkok"}""")
        assertEquals("0000123", report.order.number)
        assertEquals("000456", report.order.tracking.single())
        assertFalse(report.canResolve)
    }

    @Test
    fun nonPersonnelResponsibilityAndPhotoReferencesSurviveDraftRestore() {
        val form = WarehouseExceptionForm(version = 3, problemType = "courier", responsibilityType = "courier", responsibilityNote = "运输破损", attachmentKeys = listOf("private/photo"))
        val encoded = encodeWarehouseExceptionForm(json, form)
        assertEquals(form, json.decodeFromJsonElement(WarehouseExceptionForm.serializer(), encoded))
    }

    @Test
    fun committedRequestRemainsIdentifiableAfterReportReopens() {
        val report = json.decodeFromString(WarehouseException.serializer(), """{"id":1,"version":4,"status":"open","resolutions":[{"id":1,"problem_type":"operation","responsibility_type":"person","request_id":"11111111-1111-4111-8111-111111111111"}]}""")
        assertEquals("open", report.status)
        assertEquals("11111111-1111-4111-8111-111111111111", report.resolutions.single().requestId)
    }

    @Test
    fun refreshedParticipantsPreserveUnsubmittedMeasuresAndPhotos() {
        val draft = WarehouseExceptionForm(version = 1, problemType = "operation", participantIds = listOf("scan:old"), measures = "更换包装", attachmentKeys = listOf("private/photo"), terminateCollaborations = true)
        val latest = WarehouseException(id = 1, version = 3, operations = listOf(WarehouseExceptionOperation("scan:new")), collaborationVersion = 7)
        val restored = restoreWarehouseExceptionForm(latest, draft)
        assertEquals(draft.requestId, restored.requestId)
        assertEquals("更换包装", restored.measures)
        assertEquals(draft.attachmentKeys, restored.attachmentKeys)
        assertEquals(emptyList(), restored.participantIds)
        assertEquals(3L, restored.version)
        assertEquals(7L, restored.collaborationVersion)
        assertFalse(restored.terminateCollaborations)
    }

    @Test
    fun reopeningAfterAmbiguousSuccessStartsANewProcessingRound() {
        val draft = WarehouseExceptionForm(version = 1, problemType = "operation", participantIds = listOf("scan:1"), attachmentKeys = listOf("photo"))
        val latest = WarehouseException(id = 1, version = 4, resolutions = listOf(WarehouseExceptionResolution(id = 1, problemType = "operation", responsibilityType = "person", requestId = draft.requestId)))
        val restored = restoreWarehouseExceptionForm(latest, draft)
        assertNotEquals(draft.requestId, restored.requestId)
        assertEquals(4L, restored.version)
        assertEquals(emptyList(), restored.attachmentKeys)
        assertEquals("", restored.problemType)
    }

    @Test
    fun completedOrderDoesNotRestoreAnObsoleteDraft() {
        val draft = WarehouseExceptionForm(version = 1, measures = "未提交的说明")
        val restored = restoreWarehouseExceptionForm(WarehouseException(id = 1, version = 2, status = "resolved"), draft)
        assertNotEquals(draft.requestId, restored.requestId)
        assertEquals("", restored.measures)
        assertEquals(2L, restored.version)
    }

    @Test
    fun missingDraftUsesCurrentReportVersion() {
        val restored = restoreWarehouseExceptionForm(WarehouseException(id = 1, version = 8, collaborationVersion = 9), null)
        assertEquals(8L, restored.version)
        assertEquals(9L, restored.collaborationVersion)
    }
}
