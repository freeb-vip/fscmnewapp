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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ReceivingScanOperationPayload(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("raw_code") val rawCode: String,
    val damaged: Boolean = false,
    @SerialName("actual_received_qty") val actualReceivedQty: Int? = null,
    @SerialName("original_raw_code") val originalRawCode: String = rawCode,
    val items: List<BoxLabelQrItem> = emptyList(),
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

@Serializable
data class ReceivingQuickScanOperationPayload(
    @SerialName("raw_code") val rawCode: String,
    @SerialName("actual_received_qty") val actualReceivedQty: Int? = null,
    @SerialName("original_raw_code") val originalRawCode: String = rawCode,
    val items: List<BoxLabelQrItem> = emptyList(),
)

@Serializable
data class CentralReceivingScanOperationPayload(
    @SerialName("receipt_id") val receiptId: Long? = null,
    @SerialName("raw_code") val rawCode: String,
    @SerialName("actual_box_qty") val actualBoxQty: Int? = null,
    @SerialName("original_raw_code") val originalRawCode: String = rawCode,
    val items: List<BoxLabelQrItem> = emptyList(),
)

@Serializable
data class OfflineOperationEnvelope(
    @SerialName("actor_user_id") val actorUserId: Long,
    @SerialName("namespace_id") val namespaceId: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_key_algorithm") val deviceKeyAlgorithm: String,
    @SerialName("offline_lease_id") val offlineLeaseId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val module: String,
    val action: String,
    @SerialName("resource_id") val resourceId: String,
    val payload: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("device_signature") val deviceSignature: String,
)

@Serializable
private data class OfflineOperationSyncRequest(val operations: List<OfflineOperationEnvelope>)

@Serializable
private data class OfflineOperationSyncResponse(
    val results: List<OfflineOperationSyncResult> = emptyList(),
)

@Serializable
private data class OfflineOperationSyncResult(
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    val status: String = "",
    val message: String = "",
)

@Singleton
class OfflineSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val UNIQUE_WORK_NAME = "fscm-offline-sync"
        const val PERIODIC_WORK_NAME = "fscm-offline-sync-periodic"
    }
}

@Singleton
class OfflineSyncRepository @Inject constructor(
    private val api: FscmApiClient,
    private val pendingSyncDao: PendingSyncDao,
    private val productCountingDao: ProductCountingDao,
    private val sessionStore: SessionStore,
    private val deviceIdentityManager: DeviceIdentityManager,
    private val trustedClock: TrustedClock,
    @param:FscmJson
    private val json: Json,
) {
    val pendingCount: Flow<Int> = pendingSyncDao.pendingCount()

    suspend fun enqueueReceivingScan(payload: ReceivingScanOperationPayload): Boolean =
        enqueue(
            "receiving",
            "scan",
            receivingScanResourceId(payload.sessionId, payload.rawCode),
            json.encodeToString(payload),
            dedupeByResource = true,
        )

    suspend fun enqueueReceivingQuickScan(payload: ReceivingQuickScanOperationPayload): Boolean =
        enqueue(
            "receiving",
            "quick_scan",
            "global:${payload.rawCode}",
            json.encodeToString(payload),
            dedupeByResource = true,
        )

    suspend fun enqueueCentralReceivingScan(payload: CentralReceivingScanOperationPayload): Boolean =
        enqueue(
            module = "central_receiving",
            action = if (payload.receiptId == null) "quick_scan" else "scan",
            resourceId = "${payload.receiptId?.toString() ?: "global"}:${payload.rawCode}",
            payload = json.encodeToString(payload),
            dedupeByResource = true,
        )

    suspend fun enqueueProductCounting(action: String, payload: ProductCountingOperationPayload): Boolean =
        enqueue(
            module = "product_counting",
            action = action,
            resourceId = if (payload.clientScanId.isBlank()) {
                "${payload.clientTaskId}:$action:${payload.resetGeneration}"
            } else {
                "${payload.clientTaskId}:${payload.clientScanId}"
            },
            payload = json.encodeToString(payload),
        )

    suspend fun enqueueReceivingVoid(payload: ReceivingVoidOperationPayload) {
        enqueue("receiving", "void", payload.sessionId.toString(), json.encodeToString(payload))
    }

    suspend fun enqueueReceivingComplete(payload: ReceivingCompleteOperationPayload) {
        enqueue("receiving", "complete", payload.sessionId.toString(), json.encodeToString(payload))
    }

    suspend fun syncPending(): Boolean {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val actorUserId = session.user?.id ?: 0
        val operations = pendingSyncDao.pendingForSync().filter {
            it.namespaceId == namespaceId && it.actorUserId == actorUserId
        }
        return syncOperations(operations)
    }

    suspend fun syncReceivingSession(sessionId: Long): Boolean {
        val session = sessionStore.current()
        val operations = pendingSyncDao.pendingForModule(
            namespaceId = session.currentNamespace?.id ?: 0,
            actorUserId = session.user?.id ?: 0,
            module = "receiving",
        ).filter { operation ->
            operation.status in SYNCABLE_STATUSES &&
                (operation.resourceId == sessionId.toString() || operation.resourceId.startsWith("$sessionId:"))
        }
        return syncOperations(operations)
    }

    private suspend fun syncOperations(operations: List<PendingSyncOperationEntity>): Boolean {
        if (operations.isEmpty()) return true
        val session = sessionStore.current()
        if (session.token.isBlank()) return false
        var allSucceeded = true
        operations.forEach { operation ->
            pendingSyncDao.updateStatus(
                id = operation.id,
                status = PendingSyncStatus.Syncing.value,
                retryCount = operation.retryCount,
                lastError = operation.lastError,
            )
            runCatching { sync(operation, session) }
                .onSuccess { outcome ->
                    updateProductCountingSync(operation, outcome)
                    when (outcome.status) {
                        SyncOutcomeStatus.Accepted -> pendingSyncDao.delete(operation.id)
                        SyncOutcomeStatus.Conflict -> pendingSyncDao.updateStatus(
                            operation.id,
                            PendingSyncStatus.Conflict.value,
                            operation.retryCount,
                            outcome.message,
                        )
                        SyncOutcomeStatus.Rejected -> pendingSyncDao.updateStatus(
                            operation.id,
                            PendingSyncStatus.Rejected.value,
                            operation.retryCount,
                            outcome.message,
                        )
                    }
                }
                .onFailure { error ->
                    val disposition = error.offlineSyncFailureDisposition()
                    if (disposition == OfflineSyncFailureDisposition.Retry) allSucceeded = false
                    pendingSyncDao.updateStatus(
                        id = operation.id,
                        status = when (disposition) {
                            OfflineSyncFailureDisposition.Retry -> PendingSyncStatus.Failed.value
                            OfflineSyncFailureDisposition.Conflict -> PendingSyncStatus.Conflict.value
                            OfflineSyncFailureDisposition.Rejected -> PendingSyncStatus.Rejected.value
                        },
                        retryCount = operation.retryCount + if (disposition == OfflineSyncFailureDisposition.Retry) 1 else 0,
                        lastError = error.message.orEmpty().take(300),
                    )
                }
        }
        return allSucceeded
    }

    suspend fun pendingOperations(module: String): List<PendingSyncOperationEntity> {
        val session = sessionStore.current()
        return pendingSyncDao.pendingForModule(
            namespaceId = session.currentNamespace?.id ?: 0,
            actorUserId = session.user?.id ?: 0,
            module = module,
        )
    }

    suspend fun retryPending(id: Long) {
        pendingSyncDao.retry(sessionStore.current().currentNamespace?.id ?: 0, id)
    }

    suspend fun cancelPending(id: Long) {
        pendingSyncDao.delete(sessionStore.current().currentNamespace?.id ?: 0, id)
    }

    suspend fun cancelReceivingScan(sessionId: Long, labelCode: String): Boolean {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val actorUserId = session.user?.id ?: 0
        if (namespaceId <= 0 || actorUserId <= 0 || labelCode.isBlank()) return false
        return pendingSyncDao.deletePendingResource(
            namespaceId = namespaceId,
            actorUserId = actorUserId,
            module = "receiving",
            action = "scan",
            resourceId = receivingScanResourceId(sessionId, labelCode),
        ) > 0
    }

    private suspend fun enqueue(
        module: String,
        action: String,
        resourceId: String,
        payload: String,
        dedupeByResource: Boolean = false,
    ): Boolean {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        val actorUserId = session.user?.id ?: 0
        check(namespaceId > 0 && actorUserId > 0) { "Offline operation requires an active user and namespace" }
        session.requireOfflineOperationPermission(module)
        val duplicateCount = if (dedupeByResource) {
            pendingSyncDao.duplicateResourceCount(namespaceId, actorUserId, module, action, resourceId)
        } else {
            pendingSyncDao.duplicateCount(namespaceId, actorUserId, module, action, resourceId, payload)
        }
        if (duplicateCount > 0) return false
        val idempotencyKey = "mobile-offline-${UUID.randomUUID()}"
        val occurredAt = trustedClock.trustedNowMillis()
            ?: error("Device time must be trusted for offline operations")
        val deviceId = session.deviceId.ifBlank(deviceIdentityManager::deviceId)
        val canonical = offlineOperationCanonicalValue(
            actorUserId = actorUserId,
            namespaceId = namespaceId,
            deviceId = deviceId,
            offlineLeaseId = session.offlineLeaseId,
            idempotencyKey = idempotencyKey,
            module = module,
            action = action,
            resourceId = resourceId,
            payload = payload,
            occurredAt = occurredAt,
        )
        pendingSyncDao.insert(
            PendingSyncOperationEntity(
                namespaceId = namespaceId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                deviceKeyAlgorithm = session.deviceKeyAlgorithm,
                offlineLeaseId = session.offlineLeaseId,
                idempotencyKey = idempotencyKey,
                deviceSignature = deviceIdentityManager.signDetached(canonical),
                module = module,
                action = action,
                resourceId = resourceId,
                payload = payload,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            ),
        )
        return true
    }

    private suspend fun sync(operation: PendingSyncOperationEntity, activeSession: SessionState): SyncOutcome {
        if (operation.hasSignedEnvelope) {
            val signedResult = runCatching { syncSignedOperation(operation) }
            signedResult.getOrNull()?.let { return it }
            val error = signedResult.exceptionOrNull()
            if (error !is FscmApiException || error.statusCode != 404) throw error ?: IllegalStateException()
            if (operation.actorUserId != activeSession.user?.id) {
                error("Signed offline sync endpoint is required for a previous account operation")
            }
        } else if (operation.actorUserId > 0 && operation.actorUserId != activeSession.user?.id) {
            error("Legacy offline operation belongs to another account")
        }
        syncLegacy(operation)
        return SyncOutcome(SyncOutcomeStatus.Accepted)
    }

    private suspend fun updateProductCountingSync(
        operation: PendingSyncOperationEntity,
        outcome: SyncOutcome,
    ) {
        if (operation.module != "product_counting") return
        val payload = runCatching {
            json.decodeFromString<ProductCountingOperationPayload>(operation.payload)
        }.getOrNull() ?: return
        val status = when (outcome.status) {
            SyncOutcomeStatus.Accepted -> ProductCountSyncStatus.Synced.value
            SyncOutcomeStatus.Conflict -> ProductCountSyncStatus.Conflict.value
            SyncOutcomeStatus.Rejected -> ProductCountSyncStatus.Rejected.value
        }
        if (payload.clientScanId.isNotBlank()) {
            productCountingDao.updateEntrySync(payload.clientScanId, status, outcome.message)
        }
        productCountingDao.updateTaskSync(payload.clientTaskId, status, outcome.message)
    }

    private suspend fun syncSignedOperation(operation: PendingSyncOperationEntity): SyncOutcome {
        val body = json.encodeToJsonElement(OfflineOperationSyncRequest(listOf(operation.toEnvelope())))
        val response = api.post<OfflineOperationSyncResponse>(
            path = "mobile/offline-operations/sync",
            headers = deviceIdentityManager.proofHeaders(
                method = "POST",
                path = "mobile/offline-operations/sync",
                body = body.toString(),
                timestampMillis = trustedClock.trustedNowMillis() ?: System.currentTimeMillis(),
            ),
            body = body,
        )
        val result = response.results.firstOrNull { it.idempotencyKey == operation.idempotencyKey }
            ?: error("Offline sync response did not acknowledge ${operation.idempotencyKey}")
        return when (result.status.lowercase()) {
            "accepted", "acked", "synced", "duplicate" -> SyncOutcome(SyncOutcomeStatus.Accepted, result.message)
            "conflict" -> SyncOutcome(SyncOutcomeStatus.Conflict, result.message)
            "rejected", "invalid", "forbidden" -> SyncOutcome(SyncOutcomeStatus.Rejected, result.message)
            else -> error(result.message.ifBlank { "Unknown offline sync status: ${result.status}" })
        }
    }

    private suspend fun syncLegacy(operation: PendingSyncOperationEntity) {
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

            "receiving:quick_scan" -> {
                val payload = json.decodeFromString<ReceivingQuickScanOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "receiving-sessions/scan",
                    buildJsonObject {
                        put("raw_code", payload.rawCode)
                        payload.actualReceivedQty?.let { put("actual_received_qty", it) }
                    },
                )
            }

            "central_receiving:quick_scan" -> {
                val payload = json.decodeFromString<CentralReceivingScanOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "central-receipts/box-labels/scan",
                    buildJsonObject {
                        put("raw_code", payload.rawCode)
                        payload.actualBoxQty?.let { put("actual_box_qty", it) }
                    },
                )
            }

            "central_receiving:scan" -> {
                val payload = json.decodeFromString<CentralReceivingScanOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "central-receipts/${payload.receiptId}/box-labels/scan",
                    buildJsonObject {
                        put("raw_code", payload.rawCode)
                        payload.actualBoxQty?.let { put("actual_box_qty", it) }
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

            "product_counting:create_task" -> {
                val payload = json.decodeFromString<ProductCountingOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "mobile/app/product-counting/tasks",
                    json.encodeToJsonElement(payload),
                )
            }

            "product_counting:scan" -> {
                val payload = json.decodeFromString<ProductCountingOperationPayload>(operation.payload)
                api.post<JsonObject>(
                    "mobile/app/product-counting/tasks/${payload.clientTaskId}/scans",
                    json.encodeToJsonElement(payload),
                )
            }

            "product_counting:reset",
            "product_counting:complete",
            "product_counting:reopen",
            -> {
                val payload = json.decodeFromString<ProductCountingOperationPayload>(operation.payload)
                val action = when (operation.action) {
                    "reset" -> "reset"
                    "complete" -> "complete"
                    else -> "reopen"
                }
                api.post<JsonObject>(
                    "mobile/app/product-counting/tasks/${payload.clientTaskId}/$action",
                    json.encodeToJsonElement(payload),
                )
            }
        }
    }
}

private val SYNCABLE_STATUSES = setOf(
    PendingSyncStatus.Pending.value,
    PendingSyncStatus.Syncing.value,
    PendingSyncStatus.Failed.value,
)

private data class SyncOutcome(val status: SyncOutcomeStatus, val message: String = "")

private enum class SyncOutcomeStatus {
    Accepted,
    Conflict,
    Rejected,
}

private val PendingSyncOperationEntity.hasSignedEnvelope: Boolean
    get() = actorUserId > 0 &&
        deviceId.isNotBlank() &&
        offlineLeaseId.isNotBlank() &&
        idempotencyKey.isNotBlank() &&
        deviceSignature.isNotBlank()

private fun PendingSyncOperationEntity.toEnvelope(): OfflineOperationEnvelope = OfflineOperationEnvelope(
    actorUserId = actorUserId,
    namespaceId = namespaceId,
    deviceId = deviceId,
    deviceKeyAlgorithm = deviceKeyAlgorithm,
    offlineLeaseId = offlineLeaseId,
    idempotencyKey = idempotencyKey,
    module = module,
    action = action,
    resourceId = resourceId,
    payload = payload,
    occurredAt = createdAt,
    deviceSignature = deviceSignature,
)

private fun offlineOperationCanonicalValue(
    actorUserId: Long,
    namespaceId: Long,
    deviceId: String,
    offlineLeaseId: String,
    idempotencyKey: String,
    module: String,
    action: String,
    resourceId: String,
    payload: String,
    occurredAt: Long,
): String = listOf(
    actorUserId,
    namespaceId,
    deviceId,
    offlineLeaseId,
    idempotencyKey,
    module,
    action,
    resourceId,
    payload,
    occurredAt,
).joinToString("\n")

internal enum class OfflineSyncFailureDisposition {
    Retry,
    Conflict,
    Rejected,
}

internal fun Throwable.offlineSyncFailureDisposition(): OfflineSyncFailureDisposition {
    if (this !is FscmApiException) return OfflineSyncFailureDisposition.Retry
    return when (statusCode) {
        401, 408, 425, 429 -> OfflineSyncFailureDisposition.Retry
        409 -> OfflineSyncFailureDisposition.Conflict
        in 400..499 -> OfflineSyncFailureDisposition.Rejected
        else -> OfflineSyncFailureDisposition.Retry
    }
}

internal fun SessionState.requireOfflineOperationPermission(module: String) {
    if (!isOffline) return
    check(hasValidOfflineSession && offlineLeaseId.isNotBlank()) { "离线租约无效或已过期" }
    val requiredScope = when (module) {
        "receiving", "central_receiving" -> "receiving.write"
        "product_counting" -> "product_counting.write"
        else -> null
    }
    check(requiredScope == null || requiredScope in offlineScopes) { "离线租约不允许执行当前操作" }
}

internal fun receivingScanResourceId(sessionId: Long, labelCode: String): String = "$sessionId:$labelCode"

@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: OfflineSyncRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching {
            if (repository.syncPending()) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
}
