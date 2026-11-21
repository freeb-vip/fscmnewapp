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
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val CAPTURE_BASE = "mobile/app/product-capture"
private const val CAPTURE_PART_SIZE = 4 * 1024 * 1024
private const val CAPTURE_IMAGE_LIMIT = 6L * 1024 * 1024
private val queuedCaptureFile = Regex("""(?!draft-)([a-z0-9]+(?:-[a-z0-9]+)*)\.(jpg|mp4)""")

@Serializable
data class CaptureInstructions(val name: String, val action: String, val check: String, val steps: List<String> = emptyList())

@Serializable
data class CaptureShot(
    val key: String,
    val kind: String,
    val group: String,
    val name: String,
    val action: String,
    val check: String,
    val steps: List<String> = emptyList(),
    val localized: Map<String, CaptureInstructions> = emptyMap(),
    @SerialName("example_key") val exampleKey: String = "",
    @SerialName("reference_url") val referenceUrl: String = "",
) {
    fun instruction(language: String) = localized[language] ?: CaptureInstructions(name, action, check, steps)
}

@Serializable
data class CaptureTaskFile(val shot: String, val round: Int, val url: String? = null)

@Serializable
data class CaptureTask(
    val id: Long,
    val status: String,
    val round: Int,
    val scanned: Boolean,
    val template: String,
    val shots: List<CaptureShot> = emptyList(),
    val files: List<CaptureTaskFile>? = null,
    val retakes: Map<String, String> = emptyMap(),
    @SerialName("product_name") val productName: String,
    @SerialName("sku_code") val sku: String,
    val color: String = "",
    val spec: String = "",
    val warehouse: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("submit_requested") val submitRequested: Boolean = false,
) {
    val activeShots get() = if (round == 1) shots else shots.filter { it.key in retakes }
}

@Serializable
data class CaptureTaskPage(val items: List<CaptureTask>? = null, val total: Int)

@Serializable
private data class CaptureUpload(val parts: List<Int>, val complete: Boolean)

@Singleton
class ProductCaptureRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: FscmApiClient,
    private val sessions: SessionStore,
) {
    private val recovery = Mutex()

    suspend fun scope(): ShippingScope = checkNotNull(currentScope())

    private suspend fun currentScope(): ShippingScope? {
        val current = sessions.current()
        if (!current.isLoggedIn || current.sessionMode != SessionMode.Online || "mobile-app:product-capture" !in current.permissions) return null
        val user = current.user ?: return null
        val namespace = current.currentNamespace ?: return null
        return ShippingScope(user.id, namespace.id)
    }

    suspend fun tasks(status: String, page: Int): CaptureTaskPage =
        api.get("$CAPTURE_BASE/tasks", mapOf("status" to status, "page" to page.toString()), headers = scope().headers())

    suspend fun detail(id: Long): CaptureTask = api.get("$CAPTURE_BASE/tasks/$id", headers = scope().headers())

    suspend fun action(id: Long, name: String, barcode: String = "") {
        api.post<JsonObject>("$CAPTURE_BASE/tasks/$id/$name", headers = scope().headers(), body = buildJsonObject { put("barcode", barcode) })
    }

    private fun scopeDirectory(scope: ShippingScope) = File(context.filesDir, "product-capture/${scope.userId}/${scope.namespaceId}")

    private fun directory(scope: ShippingScope, task: Long, round: Int) =
        File(scopeDirectory(scope), "$task/$round").apply { mkdirs() }

    private fun mediaFile(dir: File, key: String, kind: String, prefix: String = "") =
        File(dir, "$prefix$key." + if (kind == "video") "mp4" else "jpg")

    fun draft(scope: ShippingScope, task: CaptureTask, shot: CaptureShot) =
        mediaFile(directory(scope, task.id, task.round), shot.key, shot.kind, "draft-")

    fun pending(scope: ShippingScope, task: CaptureTask): Map<String, Boolean> {
        val dir = directory(scope, task.id, task.round)
        val confirmed = task.files.orEmpty().filter { it.round == task.round }.mapTo(mutableSetOf()) { it.shot }
        return task.activeShots.mapNotNull { shot ->
            val receipt = File(dir, "${shot.key}.uploaded")
            when {
                shot.key in confirmed -> {
                    receipt.delete()
                    null
                }
                receipt.exists() || mediaFile(dir, shot.key, shot.kind).exists() -> shot.key to File(dir, "${shot.key}.error").exists()
                else -> null
            }
        }.toMap()
    }

    suspend fun confirm(scope: ShippingScope, task: CaptureTask, shot: CaptureShot, draft: File) = withContext(Dispatchers.IO) {
        check(scope == scope())
        val dir = directory(scope, task.id, task.round)
        val file = mediaFile(dir, shot.key, shot.kind)
        val staged = File(dir, file.name + ".tmp")
        if (shot.kind == "image" && draft.length() > CAPTURE_IMAGE_LIMIT) compressCapturePhoto(draft, staged) else draft.copyTo(staged, overwrite = true)
        if (!staged.renameTo(file)) throw IOException("Unable to save capture")
        File(dir, "${shot.key}.error").delete()
        enqueue(scope, task.id, task.round, shot.key, shot.kind)
        draft.delete()
    }

    fun resume(scope: ShippingScope, task: CaptureTask) {
        val pending = pending(scope, task)
        val dir = directory(scope, task.id, task.round)
        task.activeShots.filter { pending[it.key] == false && mediaFile(dir, it.key, it.kind).exists() }
            .forEach { enqueue(scope, task.id, task.round, it.key, it.kind) }
        if (submitted(scope, task) && !task.submitRequested && task.status in setOf("capturing", "retake")) enqueue(scope, task.id, task.round, "", "")
    }

    fun submit(scope: ShippingScope, task: CaptureTask) {
        File(directory(scope, task.id, task.round), "submitted").createNewFile()
        enqueue(scope, task.id, task.round, "", "")
    }

    fun submitted(scope: ShippingScope, task: CaptureTask) = File(directory(scope, task.id, task.round), "submitted").exists()

    suspend fun recoverUploads() {
        val scope = currentScope() ?: return
        if (!recovery.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                for (taskDirectory in scopeDirectory(scope).listFiles().orEmpty()) {
                    val task = taskDirectory.name.toLongOrNull()?.takeIf { it > 0 } ?: continue
                    for (roundDirectory in taskDirectory.listFiles().orEmpty()) {
                        val round = roundDirectory.name.toIntOrNull()?.takeIf { it > 0 } ?: continue
                        for (file in roundDirectory.listFiles().orEmpty()) {
                            val match = queuedCaptureFile.matchEntire(file.name)
                            if (match != null && file.isFile) {
                                val (shot, extension) = match.destructured
                                if (File(roundDirectory, "$shot.error").exists() || File(roundDirectory, "$shot.uploaded").exists()) continue
                                val kind = if (extension == "mp4") "video" else "image"
                                recoverWork(scope, task, round, shot, kind)
                            } else if (file.name == "submitted") {
                                recoverWork(scope, task, round, "", "")
                            }
                        }
                    }
                }
            }
        } finally {
            recovery.unlock()
        }
    }

    private suspend fun recoverWork(scope: ShippingScope, task: Long, round: Int, shot: String, kind: String) {
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(scope, task, round, shot)).first()
        if (scope != currentScope() || work.any { it.state == WorkInfo.State.RUNNING }) return
        if (work.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) {
            enqueue(scope, task, round, shot, kind, ExistingWorkPolicy.REPLACE)
        } else if (shot.isNotEmpty() && work.all { it.state.isFinished }) {
            enqueue(scope, task, round, shot, kind)
        }
    }

    private fun workName(scope: ShippingScope, task: Long, round: Int, shot: String) =
        "capture-${scope.userId}-${scope.namespaceId}-$task-$round-$shot"

    private fun enqueue(
        scope: ShippingScope,
        task: Long,
        round: Int,
        shot: String,
        kind: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val req = OneTimeWorkRequestBuilder<ProductCaptureWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setInputData(workDataOf("user" to scope.userId, "namespace" to scope.namespaceId, "task" to task, "round" to round, "shot" to shot, "kind" to kind))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(scope, task, round, shot), policy, req)
    }

    suspend fun upload(scope: ShippingScope, task: Long, round: Int, shot: String, kind: String): Boolean {
        if (scope != scope()) return false
        val headers = scope.headers()
        val dir = directory(scope, task, round)
        if (shot.isEmpty()) {
            api.post<JsonObject>("$CAPTURE_BASE/tasks/$task/submit", headers = headers, body = buildJsonObject { put("round", round) })
            return true
        }
        val file = mediaFile(dir, shot, kind)
        if (!file.exists()) return true
        val sha = withContext(Dispatchers.IO) { captureFileHash(file) }
        val id = UUID.nameUUIDFromBytes("$scope/$task/$round/$shot/$sha".toByteArray()).toString()
        try {
            val session = api.post<CaptureUpload>(
                "$CAPTURE_BASE/uploads",
                headers = headers,
                body = buildJsonObject {
                    put("id", id)
                    put("mode", "task")
                    put("task_id", task)
                    put("round", round)
                    put("shot", shot)
                    put("asset_type", kind)
                    put("filename", file.name)
                    put("sha256", sha)
                    put("size_bytes", file.length())
                },
            )
            if (!session.complete) {
                sendParts(file, id, session.parts.toSet(), headers)
                api.post<CaptureUpload>("$CAPTURE_BASE/uploads/$id/finish", headers = headers)
            }
            File(dir, "$shot.uploaded").createNewFile()
            file.delete()
        } catch (error: FscmApiException) {
            if (error.statusCode !in setOf(400, 404, 409)) throw error
            File(dir, "$shot.error").createNewFile()
        }
        return true
    }

    private suspend fun sendParts(file: File, id: String, confirmed: Set<Int>, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "r").use { source ->
            var offset = 0L
            var part = 0
            while (offset < source.length()) {
                val size = minOf(CAPTURE_PART_SIZE.toLong(), source.length() - offset).toInt()
                if (part !in confirmed) {
                    source.seek(offset)
                    val bytes = ByteArray(size).also { source.readFully(it) }
                    api.upload<JsonObject>("$CAPTURE_BASE/uploads/$id/parts/$part", MultipartBody.Part.createFormData("file", file.name, bytes.toRequestBody()), headers = headers)
                }
                offset += size
                part++
            }
        }
    }
}

private fun captureFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun compressCapturePhoto(original: File, output: File) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(original.path, options)
    options.inJustDecodeBounds = false
    options.inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(options.outWidth, options.outHeight) / it <= 4600 }
    val decoded = BitmapFactory.decodeFile(original.path, options) ?: throw IOException("Invalid photo")
    val orientation = ExifInterface(original.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        setRotate(
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            },
        )
    }
    var bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (bitmap != decoded) decoded.recycle()
    try {
        repeat(6) {
            for (quality in listOf(95, 88, 80)) {
                output.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream) }
                if (output.length() in 1..CAPTURE_IMAGE_LIMIT) return
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .8).toInt().coerceAtLeast(1), (bitmap.height * .8).toInt().coerceAtLeast(1), true)
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        throw IOException("Photo exceeds size limit")
    } finally {
        bitmap.recycle()
    }
}

@HiltWorker
class ProductCaptureWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: ProductCaptureRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scope = ShippingScope(inputData.getLong("user", 0), inputData.getLong("namespace", 0))
        return try {
            val done = repository.upload(scope, inputData.getLong("task", 0), inputData.getInt("round", 0), inputData.getString("shot").orEmpty(), inputData.getString("kind").orEmpty())
            if (done) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FscmApiException) {
            if (error.statusCode == 404 || error.statusCode == 409) Result.failure() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
