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
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

@Serializable
data class FbsLine(
    val code: String,
    val image: String = "",
    @SerialName("warehouse_sku") val warehouseSku: String,
    val quantity: Long,
    val pack: Long,
    val units: Long,
    val label: Boolean,
    val picked: Boolean,
)

@Serializable
data class FbsOrder(
    val id: Long,
    val number: String,
    @SerialName("shop_number") val shopNumber: String,
    val stage: String,
    val version: Long,
    val revision: Long,
    val lines: List<FbsLine>,
    val packages: Int,
    val pickup: String,
)

@Serializable
data class FbsFile(val id: String, val kind: String)

@Serializable
data class FbsLocation(val sku: String, @SerialName("location_code") val location: String, val quantity: Long)

@Serializable
data class FbsPlan(val sku: String, @SerialName("source_sku") val source: String = "")

@Serializable
data class FbsBatch(val actual: List<FbsLocation>, val planned: List<FbsPlan>)

@Serializable
data class FbsDetail(val order: FbsOrder, val files: List<FbsFile>, val batches: List<FbsBatch>)

@Serializable
data class FbsPage(val rows: List<FbsOrder>, val total: Int)

class FbsRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessions: SessionStore,
    @param:ApplicationContext private val context: Context,
) {
    private val base = "mobile/fbs-outbounds"
    private val packingDrafts = context.getSharedPreferences("fbs-packing", Context.MODE_PRIVATE)

    private fun draftKey(scope: ShippingScope, id: Long) = "${scope.userId}/${scope.namespaceId}/$id"

    fun packageDraft(scope: ShippingScope, id: Long): String =
        packingDrafts.getString(draftKey(scope, id), "").orEmpty()

    fun savePackageDraft(scope: ShippingScope, id: Long, quantity: String) {
        val key = draftKey(scope, id)
        val editor = packingDrafts.edit()
        if (quantity.isEmpty()) editor.remove(key) else editor.putString(key, quantity)
        editor.apply()
    }

    suspend fun scope(): ShippingScope {
        val session = sessions.current()
        check(session.isLoggedIn && session.sessionMode == SessionMode.Online && "mobile-app:fbs-outbound" in session.permissions)
        return ShippingScope(requireNotNull(session.user).id, requireNotNull(session.currentNamespace).id)
    }

    suspend fun list(scope: ShippingScope, keyword: String, page: Int, completed: Boolean): FbsPage =
        api.get(base, mapOf("q" to keyword, "page" to page.toString(), "stage" to if (completed) "completed" else "pending"), headers = scope.headers())

    suspend fun detail(scope: ShippingScope, id: Long): FbsDetail = api.get("$base/$id", headers = scope.headers())

    suspend fun act(scope: ShippingScope, id: Long, action: String, body: JsonObject): Boolean =
        api.post("$base/$id/$action", headers = scope.headers(), body = body)

    private fun directory(scope: ShippingScope, id: Long) =
        File(context.filesDir, "fbs/${scope.userId}/${scope.namespaceId}/$id").apply { mkdirs() }

    fun newPhoto(scope: ShippingScope, id: Long, kind: String): File =
        File(directory(scope, id), "${kind}_${UUID.randomUUID()}.jpg")

    fun pending(scope: ShippingScope, id: Long): List<File> =
        directory(scope, id).listFiles().orEmpty().filter { it.extension == "jpg" && it.length() > 0 }.sortedBy { it.name }

    suspend fun upload(scope: ShippingScope, order: FbsOrder, photo: File) = withContext(Dispatchers.IO) {
        require(photo.parentFile?.canonicalFile == directory(scope, order.id).canonicalFile)
        val prepared = normalizeShippingPhoto(photo)
        val kind = photo.name.substringBefore('_')
        val key = photo.nameWithoutExtension.substringAfter('_')
        val path = "$base/${order.id}/uploads/$kind/$key?revision=${order.revision}"
        val digest = MessageDigest.getInstance("SHA-256").digest(prepared.readBytes()).joinToString("") { "%02x".format(it) }
        val headers = scope.headers() + mapOf("X-Upload-Size" to prepared.length().toString(), "X-Upload-SHA256" to digest)
        val status = api.get<ShippingPhotoUpload>(path, headers = headers)
        resumeShippingPhoto(prepared, 64 * 1024, status) { offset, bytes ->
            val part = MultipartBody.Part.createFormData("file", photo.name, bytes.toRequestBody("application/octet-stream".toMediaType()))
            api.upload(path, part, headers = headers + ("X-Upload-Offset" to offset.toString()))
        }
        prepared.delete()
        photo.delete()
        Unit
    }

    suspend fun download(scope: ShippingScope, order: FbsOrder, file: FbsFile, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = api.download("$base/${order.id}/files/${file.id}", scope.headers())
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
    }
}
