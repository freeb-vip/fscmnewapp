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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ShippingTask(
    @SerialName("product_preview") val productPreview: OrderProductPreview? = null,
    @SerialName("order_id") val orderId: Long,
    @SerialName("order_no") val orderNo: String = "",
    val warehouse: String = "",
    val stage: String = "picking",
    val version: Long = 0,
    @SerialName("sku_count") val skuCount: Long = 0,
    val quantity: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("shipped_at") val shippedAt: String? = null,
    val recipient: ShippingRecipient = ShippingRecipient(),
    val items: List<ShippingItem> = emptyList(),
    val photos: List<ShippingPhoto> = emptyList(),
    @SerialName("packed_at") val packedAt: String? = null,
    @SerialName("packed_by") val packedBy: Long? = null,
    @SerialName("shipped_by") val shippedBy: Long? = null,
    val carrier: String = "",
    @SerialName("tracking_no") val trackingNo: String = "",
)

@Serializable
data class ShippingRecipient(val name: String = "", val phone: String = "", val address: String = "")

@Serializable
data class ShippingItem(
    @SerialName("item_id") val itemId: Long,
    @SerialName("sku_code") val skuCode: String,
    val name: String = "",
    val spec: String = "",
    val unit: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val quantity: Long,
    val picked: Boolean = false,
    val locations: List<String> = emptyList(),
    @SerialName("picked_by") val pickedBy: Long? = null,
    @SerialName("picked_at") val pickedAt: String? = null,
)

@Serializable
data class ShippingPhoto(
    val id: String,
    val stage: String,
    @SerialName("actor_id") val actorId: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    val confirmed: Boolean = false,
)

@Serializable
data class ShippingPage(val rows: List<ShippingTask> = emptyList(), val total: Long = 0)

data class ShippingScope(val userId: Long, val namespaceId: Long) {
    fun headers() = mapOf("X-FSCM-Expected-User-ID" to userId.toString(), "X-FSCM-Expected-Namespace-ID" to namespaceId.toString())
}

@Serializable
private data class PendingShippingAction(val key: String, val action: String, val body: JsonObject)

@Singleton
class OfflineShippingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessions: SessionStore,
    @param:FscmJson private val json: Json,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun scope(): ShippingScope {
        val session = sessions.current()
        check(session.sessionMode == SessionMode.Online && session.isLoggedIn && "mobile-app:offline-shipping" in session.permissions)
        return ShippingScope(requireNotNull(session.user).id, requireNotNull(session.currentNamespace).id)
    }

    private suspend fun verify(scope: ShippingScope) {
        check(scope == scope())
    }
    private fun base(id: Long) = "mobile/app/offline-shipping/tasks/$id"
    private fun directory(scope: ShippingScope, id: Long): File = File(context.filesDir, "offline-shipping/${scope.userId}/${scope.namespaceId}/$id").apply { mkdirs() }

    suspend fun list(scope: ShippingScope, shipped: Boolean, keyword: String, page: Int): ShippingPage {
        verify(scope)
        return api.get("mobile/app/offline-shipping/tasks", mapOf("status" to if (shipped) "shipped" else "pending", "keyword" to keyword, "page" to page.toString(), "page_size" to "20"), headers = scope.headers())
    }

    suspend fun detail(scope: ShippingScope, id: Long): ShippingTask {
        verify(scope)
        return api.get(base(id), headers = scope.headers())
    }

    suspend fun scan(scope: ShippingScope, id: Long, code: String): ShippingItem {
        verify(scope)
        return api.post("${base(id)}/scan", headers = scope.headers(), body = buildJsonObject { put("sku", code) })
    }

    private fun journal(scope: ShippingScope, id: Long) = AtomicFile(File(directory(scope, id), "pending.json"))

    suspend fun pending(scope: ShippingScope, id: Long): Boolean = withContext(Dispatchers.IO) { journal(scope, id).baseFile.exists() }

    suspend fun act(scope: ShippingScope, id: Long, action: String, body: JsonObject): ShippingTask {
        verify(scope)
        withContext(Dispatchers.IO) {
            val file = journal(scope, id)
            check(!file.baseFile.exists())
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(PendingShippingAction(UUID.randomUUID().toString(), action, body)).toByteArray())
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
        return requireNotNull(retry(scope, id))
    }

    suspend fun retry(scope: ShippingScope, id: Long): ShippingTask? {
        verify(scope)
        val saved = withContext(Dispatchers.IO) {
            val file = journal(scope, id)
            if (!file.baseFile.exists()) null else json.decodeFromString<PendingShippingAction>(file.readFully().decodeToString())
        } ?: return null
        try {
            val result: ShippingTask = api.post("${base(id)}/${saved.action}", headers = scope.headers() + ("Idempotency-Key" to saved.key), body = saved.body)
            withContext(Dispatchers.IO) { journal(scope, id).delete() }
            return result
        } catch (error: FscmApiException) {
            if (error.statusCode in listOf(400, 403, 404, 409)) withContext(Dispatchers.IO) { journal(scope, id).delete() }
            throw error
        }
    }

    suspend fun newPhoto(scope: ShippingScope, id: Long, stage: String): File = withContext(Dispatchers.IO) {
        verify(scope)
        require(stage in listOf("packing", "shipping"))
        File(directory(scope, id), "${stage}_${UUID.randomUUID()}.jpg").apply { createNewFile() }
    }

    suspend fun localPhotos(scope: ShippingScope, id: Long): List<File> = withContext(Dispatchers.IO) {
        verify(scope)
        directory(scope, id).listFiles()?.filter { it.extension == "jpg" && it.length() > 0 }?.sortedBy { it.name }.orEmpty()
    }

    suspend fun removeLocal(scope: ShippingScope, id: Long, file: File) = withContext(Dispatchers.IO) {
        verify(scope)
        check(file.parentFile?.canonicalFile == directory(scope, id).canonicalFile)
        file.delete()
        File(file.path + ".upload").delete()
        Unit
    }

    suspend fun upload(scope: ShippingScope, id: Long, file: File): ShippingPhoto = withContext(Dispatchers.IO) {
        verify(scope)
        check(file.parentFile?.canonicalFile == directory(scope, id).canonicalFile)
        val prepared = normalizeShippingPhoto(file)
        require(prepared.length() in 1..(10L shl 20))
        val stage = file.name.substringBefore('_')
        val photoId = file.nameWithoutExtension.substringAfter('_')
        val part = MultipartBody.Part.createFormData("file", file.name, prepared.readBytes().toRequestBody("image/jpeg".toMediaType()))
        val result: ShippingPhoto = api.upload("${base(id)}/photos/$stage/$photoId", part, headers = scope.headers())
        file.delete()
        prepared.delete()
        result
    }

    suspend fun removePhoto(scope: ShippingScope, id: Long, photo: ShippingPhoto) {
        verify(scope)
        api.delete<JsonElement>("${base(id)}/photos/${photo.id}", headers = scope.headers())
    }

    suspend fun photo(scope: ShippingScope, id: Long, photo: ShippingPhoto): ByteArray {
        verify(scope)
        return api.download("${base(id)}/photos/${photo.id}", scope.headers())
    }
}

// Persist the normalized upload once so a timeout retry has identical bytes.
internal fun normalizeShippingPhoto(source: File): File {
    val target = File(source.path + ".upload")
    val atomic = AtomicFile(target)
    if (target.exists()) return target
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
    val orientation = ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(270f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    val normalized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val stream = atomic.startWrite()
    try {
        check(normalized.compress(Bitmap.CompressFormat.JPEG, 85, stream))
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    } finally {
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
    }
    return target
}
