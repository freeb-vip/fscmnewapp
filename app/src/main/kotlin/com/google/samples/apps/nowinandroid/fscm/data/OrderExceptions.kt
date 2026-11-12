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

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WarehouseExceptionFile(
    @SerialName("object_key") val key: String,
    val name: String = "图片",
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
)

@Serializable
data class WarehouseExceptionType(val name: String = "")

@Serializable
data class WarehouseExceptionOrder(
    @SerialName("platform_order_id") val number: String = "",
    @SerialName("tracking_nos") val tracking: List<String> = emptyList(),
)

@Serializable
data class WarehouseExceptionOperation(
    val id: String,
    @SerialName("operation_type_code") val code: String = "",
    @SerialName("operation_type_name") val operation: String = "",
    @SerialName("actor_name") val name: String = "",
    @SerialName("actor_employee_code") val employeeCode: String = "",
    val operator: String = "",
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("business_date") val date: String = "",
)

@Serializable
data class WarehouseExceptionResolution(
    val id: Long,
    @SerialName("problem_type") val problemType: String,
    @SerialName("responsibility_type") val responsibilityType: String,
    @SerialName("responsibility_note") val responsibilityNote: String = "",
    val measures: String = "",
    @SerialName("actor_name") val actor: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val participants: List<WarehouseExceptionOperation> = emptyList(),
    val attachments: List<WarehouseExceptionFile> = emptyList(),
    @SerialName("request_id") val requestId: String = "",
)

@Serializable
data class WarehouseExceptionWarehouse(@SerialName("warehouse_name") val name: String = "")

@Serializable
data class WarehouseExceptionTicket(
    @SerialName("ticket_no") val number: String = "",
    val status: String = "",
)

@Serializable
data class WarehouseExceptionCollaboratorUser(val name: String = "", val username: String = "")

@Serializable
data class WarehouseExceptionCollaborator(@SerialName("user_id") val userId: Long = 0, val user: WarehouseExceptionCollaboratorUser? = null)

@Serializable
data class WarehouseException(
    @SerialName("scope_items") val scopeItems: List<TicketScopeItem> = emptyList(),
    val id: Long,
    val version: Long = 0,
    val status: String = "open",
    val description: String = "",
    val order: WarehouseExceptionOrder = WarehouseExceptionOrder(),
    val types: List<WarehouseExceptionType> = emptyList(),
    val warehouse: WarehouseExceptionWarehouse = WarehouseExceptionWarehouse(),
    val attachments: List<WarehouseExceptionFile> = emptyList(),
    val operations: List<WarehouseExceptionOperation> = emptyList(),
    val resolutions: List<WarehouseExceptionResolution> = emptyList(),
    val skus: List<String> = emptyList(),
    val ticket: WarehouseExceptionTicket? = null,
    @SerialName("handling_note") val handlingNote: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("can_resolve") val canResolve: Boolean = false,
    @SerialName("pending_collaborators") val pendingCollaborators: List<WarehouseExceptionCollaborator> = emptyList(),
    @SerialName("collaboration_version") val collaborationVersion: Long = 0,
)

@Serializable
data class WarehouseExceptionPage(
    val items: List<WarehouseException> = emptyList(),
    val total: Long = 0,
    val open: Long = 0,
    val resolved: Long = 0,
)

@Serializable
data class WarehouseExceptionForm(
    @SerialName("request_id") val requestId: String = UUID.randomUUID().toString(),
    val version: Long = 0,
    @SerialName("problem_type") val problemType: String = "",
    @SerialName("responsibility_type") val responsibilityType: String = "person",
    @SerialName("participant_ids") val participantIds: List<String> = emptyList(),
    @SerialName("responsibility_note") val responsibilityNote: String = "",
    val measures: String = "",
    @SerialName("attachment_keys") val attachmentKeys: List<String> = emptyList(),
    @SerialName("terminate_collaborations") val terminateCollaborations: Boolean = false,
    @SerialName("collaboration_version") val collaborationVersion: Long = 0,
)

internal fun restoreWarehouseExceptionForm(report: WarehouseException, draft: WarehouseExceptionForm?): WarehouseExceptionForm {
    val committed = draft != null && report.resolutions.any { it.requestId == draft.requestId }
    if (draft == null || committed || report.status != "open") {
        return WarehouseExceptionForm(version = report.version, collaborationVersion = report.collaborationVersion)
    }
    val available = report.operations.map { it.id }.toSet()
    return draft.copy(version = report.version, collaborationVersion = report.collaborationVersion, terminateCollaborations = false, participantIds = draft.participantIds.filter { it in available })
}

internal fun encodeWarehouseExceptionForm(json: Json, form: WarehouseExceptionForm) =
    Json(json) { encodeDefaults = true }.encodeToJsonElement(WarehouseExceptionForm.serializer(), form)

data class ExceptionScope(val user: Long, val namespace: Long) {
    fun headers() = mapOf("X-Namespace-ID" to namespace.toString())
}

@Singleton
class OrderExceptionsRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessions: SessionStore,
    @param:FscmJson private val json: Json,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun scope(): ExceptionScope {
        val session = sessions.current()
        check(session.sessionMode == SessionMode.Online && session.isLoggedIn && "mobile-app:order-exceptions" in session.permissions)
        return ExceptionScope(requireNotNull(session.user).id, requireNotNull(session.currentNamespace).id)
    }

    private suspend fun verify(scope: ExceptionScope) {
        check(scope == scope())
    }

    suspend fun list(scope: ExceptionScope, resolved: Boolean, filters: Map<String, String>, page: Int): WarehouseExceptionPage {
        verify(scope)
        val result: WarehouseExceptionPage = api.get("mobile/order-exceptions", filters + mapOf("status" to if (resolved) "resolved" else "open", "page" to page.toString(), "page_size" to "20"), headers = scope.headers())
        verify(scope)
        return result
    }

    suspend fun detail(scope: ExceptionScope, id: Long): WarehouseException {
        verify(scope)
        val result: WarehouseException = api.get("mobile/order-exceptions/$id", headers = scope.headers())
        verify(scope)
        return result
    }

    suspend fun resolve(scope: ExceptionScope, id: Long, form: WarehouseExceptionForm): WarehouseException {
        verify(scope)
        val result: WarehouseException = api.post("mobile/order-exceptions/$id/resolve", headers = scope.headers(), body = encodeWarehouseExceptionForm(json, form))
        verify(scope)
        return result
    }

    suspend fun photo(scope: ExceptionScope, id: Long, key: String): ByteArray {
        verify(scope)
        val result = api.download("mobile/order-exceptions/$id/attachments?key=${URLEncoder.encode(key, "UTF-8")}", scope.headers())
        verify(scope)
        return result
    }

    fun cameraFile(): File = File(context.cacheDir, "exception-photos/${UUID.randomUUID()}.jpg").apply {
        parentFile?.mkdirs()
        createNewFile()
    }

    suspend fun upload(scope: ExceptionScope, id: Long, requestId: String, uri: Uri): WarehouseExceptionFile = withContext(Dispatchers.IO) {
        verify(scope)
        val file = cameraFile()
        var prepared: File? = null
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri))
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 25 * 1024 * 1024) { "原图过大，请选择较小图片" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            prepared = normalizeShippingPhoto(file)
            require(prepared.length() in 1L..(5L * 1024 * 1024)) { "图片需小于5MB" }
            val part = MultipartBody.Part.createFormData("file", "整改照片.jpg", prepared.readBytes().toRequestBody("image/jpeg".toMediaType()))
            val result: WarehouseExceptionFile = api.upload("mobile/order-exceptions/$id/attachments?request_id=$requestId", part, headers = scope.headers())
            verify(scope)
            result
        } finally {
            file.delete()
            prepared?.delete()
        }
    }
}
