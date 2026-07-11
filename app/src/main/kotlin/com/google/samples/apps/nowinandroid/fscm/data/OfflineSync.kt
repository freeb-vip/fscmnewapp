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
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ReceivingScanOperationPayload(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("raw_code") val rawCode: String,
    val damaged: Boolean = false,
    @SerialName("actual_received_qty") val actualReceivedQty: Int? = null,
)

@Serializable
data class ReceivingVoidOperationPayload(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("item_id") val itemId: Long,
    val reason: String = "移动端离线取消扫码",
)

@Serializable
data class ReceivingCompleteOperationPayload(
    @SerialName("session_id") val sessionId: Long,
)

@Singleton
class OfflineSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "fscm-offline-sync"
    }
}

@Singleton
class OfflineSyncRepository @Inject constructor(
    private val api: FscmApiClient,
    private val pendingSyncDao: PendingSyncDao,
    private val sessionStore: SessionStore,
    @param:FscmJson
    private val json: Json,
) {
    val pendingCount: Flow<Int> = pendingSyncDao.pendingCount()

    suspend fun enqueueReceivingScan(payload: ReceivingScanOperationPayload) {
        enqueue("receiving", "scan", payload.sessionId.toString(), json.encodeToString(payload))
    }

    suspend fun enqueueReceivingVoid(payload: ReceivingVoidOperationPayload) {
        enqueue("receiving", "void", payload.sessionId.toString(), json.encodeToString(payload))
    }

    suspend fun enqueueReceivingComplete(payload: ReceivingCompleteOperationPayload) {
        enqueue("receiving", "complete", payload.sessionId.toString(), json.encodeToString(payload))
    }

    suspend fun syncPending(): Boolean {
        if (sessionStore.current().token.isBlank()) return false
        var allSucceeded = true
        pendingSyncDao.pending().forEach { operation ->
            if (operation.retryCount >= MAX_RETRY_COUNT) {
                pendingSyncDao.updateStatus(
                    id = operation.id,
                    status = PendingSyncStatus.Failed.value,
                    retryCount = operation.retryCount,
                    lastError = operation.lastError.ifBlank { "已达到离线同步重试上限，请检查后手动重试" },
                )
                return@forEach
            }
            pendingSyncDao.updateStatus(
                id = operation.id,
                status = PendingSyncStatus.Syncing.value,
                retryCount = operation.retryCount,
                lastError = operation.lastError,
            )
            runCatching { sync(operation) }
                .onSuccess { pendingSyncDao.delete(operation.id) }
                .onFailure { error ->
                    allSucceeded = false
                    pendingSyncDao.updateStatus(
                        id = operation.id,
                        status = PendingSyncStatus.Failed.value,
                        retryCount = operation.retryCount + 1,
                        lastError = error.message.orEmpty().take(300),
                    )
                }
        }
        return allSucceeded
    }

    private suspend fun enqueue(module: String, action: String, resourceId: String, payload: String) {
        val namespaceId = sessionStore.current().currentNamespace?.id ?: 0
        pendingSyncDao.insert(
            PendingSyncOperationEntity(
                namespaceId = namespaceId,
                module = module,
                action = action,
                resourceId = resourceId,
                payload = payload,
            ),
        )
    }

    private suspend fun sync(operation: PendingSyncOperationEntity) {
        when ("${operation.module}:${operation.action}") {
            "receiving:scan" -> {
                val payload = json.decodeFromString<ReceivingScanOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "receiving-sessions/${payload.sessionId}/scan",
                    buildJsonObject {
                        put("raw_code", payload.rawCode)
                        put("damaged", payload.damaged)
                        put("tally_count", true)
                        payload.actualReceivedQty?.let { put("actual_received_qty", it) }
                    },
                )
            }

            "receiving:void" -> {
                val payload = json.decodeFromString<ReceivingVoidOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "receiving-sessions/${payload.sessionId}/scan-items/${payload.itemId}/void",
                    buildJsonObject { put("reason", payload.reason) },
                )
            }

            "receiving:complete" -> {
                val payload = json.decodeFromString<ReceivingCompleteOperationPayload>(operation.payload)
                api.post<JsonObject>("receiving-sessions/${payload.sessionId}/receiving-record")
            }
        }
    }

    private companion object {
        const val MAX_RETRY_COUNT = 5
    }
}

@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: OfflineSyncRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (repository.syncPending()) Result.success() else Result.retry()
}
