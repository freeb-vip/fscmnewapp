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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalReceivingBatch(
    val sessionId: Long,
    val sessionCode: String,
    val snapshotAt: Long,
    val totalCount: Int,
    val pendingCount: Int,
    val lastSyncError: String,
)

data class LocalReceivingScan(
    val id: Long,
    val clientScanId: String,
    val labelCode: String,
    val items: List<BoxLabelQrItem>,
    val actualReceivedQty: Int,
    val scannedAt: Long,
    val syncStatus: String,
    val syncError: String,
)

@Serializable
private data class LocalReceivingSyncRequest(
    val items: List<LocalReceivingSyncRequestItem>,
)

@Serializable
private data class LocalReceivingSyncRequestItem(
    @SerialName("client_scan_id") val clientScanId: String,
    @SerialName("label_code") val labelCode: String,
    @SerialName("original_raw_code") val originalRawCode: String,
    @SerialName("actual_received_qty") val actualReceivedQty: Int,
    @SerialName("scanned_at") val scannedAt: String,
)

@Serializable
private data class LocalReceivingSyncResponse(
    val items: List<LocalReceivingSyncResponseItem> = emptyList(),
)

@Serializable
private data class LocalReceivingSyncResponseItem(
    @SerialName("client_scan_id") val clientScanId: String,
    val status: String,
    val message: String = "",
)

@Singleton
class LocalReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val receivingRepository: ReceivingRepository,
    private val sessionStore: SessionStore,
    private val localReceivingDao: LocalReceivingDao,
    @param:FscmJson private val json: Json,
) {
    private val syncMutex = Mutex()

    suspend fun activate(sessionId: Long): LocalReceivingBatch {
        val session = receivingRepository.session(sessionId)
        val scope = scope()
        val existing = localReceivingDao.batch(scope.userId, scope.namespaceId, sessionId)
        val now = System.currentTimeMillis()
        localReceivingDao.deactivateAll(scope.userId, scope.namespaceId, now)
        localReceivingDao.upsertBatch(
            LocalReceivingBatchEntity(
                id = existing?.id ?: 0,
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                sessionCode = session.code.ifBlank { "RS-$sessionId" },
                snapshotAt = now,
                isActive = true,
                lastSyncError = existing?.lastSyncError.orEmpty(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        localReceivingDao.activate(scope.userId, scope.namespaceId, sessionId, now)
        return batch(sessionId) ?: throw LocalReceivingClientException(LocalReceivingClientError.BatchNotSaved)
    }

    suspend fun activeBatch(): LocalReceivingBatch? {
        val scope = scope()
        val entity = localReceivingDao.activeBatch(scope.userId, scope.namespaceId) ?: return null
        return batch(entity.sessionId)
    }

    suspend fun scans(sessionId: Long): List<LocalReceivingScan> {
        val scope = scope()
        return localReceivingDao.scans(scope.userId, scope.namespaceId, sessionId).map(::toLocalScan)
    }

    suspend fun confirmScan(payload: BoxLabelQrPayload, actualReceivedQty: Int): LocalReceivingScan {
        val batch = activeBatch() ?: throw LocalReceivingClientException(LocalReceivingClientError.NoActiveBatch)
        val scope = scope()
        if (localReceivingDao.duplicateLabelCount(scope.userId, scope.namespaceId, batch.sessionId, payload.labelCode) > 0) {
            throw LocalReceivingDuplicateException(payload.labelCode)
        }
        val entity = LocalReceivingScanEntity(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            sessionId = batch.sessionId,
            clientScanId = UUID.randomUUID().toString(),
            labelCode = payload.labelCode,
            originalRawCode = payload.originalRawCode,
            itemsPayload = json.encodeToString(payload.items),
            actualReceivedQty = actualReceivedQty,
        )
        val id = localReceivingDao.insertScan(entity)
        return toLocalScan(entity.copy(id = id))
    }

    suspend fun deleteScan(id: Long) {
        val scope = scope()
        localReceivingDao.deleteUnsyncedScan(scope.userId, scope.namespaceId, id)
    }

    suspend fun sync(sessionId: Long): LocalReceivingBatch = syncMutex.withLock {
        if (sessionStore.current().isOffline) {
            throw LocalReceivingClientException(LocalReceivingClientError.OfflineSyncUnavailable)
        }
        val scope = scope()
        val rows = localReceivingDao.pendingScans(scope.userId, scope.namespaceId, sessionId)
        if (rows.isEmpty()) {
            return batch(sessionId) ?: throw LocalReceivingClientException(LocalReceivingClientError.BatchNotFound)
        }
        localReceivingDao.updateScanStatuses(scope.userId, scope.namespaceId, rows.map { it.clientScanId }, LocalReceivingSyncStatus.Syncing.value, "")
        val result = runCatching {
            api.post<LocalReceivingSyncResponse>(
                "receiving-sessions/$sessionId/local-scans/sync",
                json.encodeToJsonElement(
                    LocalReceivingSyncRequest(
                        rows.map {
                            LocalReceivingSyncRequestItem(
                                clientScanId = it.clientScanId,
                                labelCode = it.labelCode,
                                originalRawCode = it.originalRawCode,
                                actualReceivedQty = it.actualReceivedQty,
                                scannedAt = Instant.ofEpochMilli(it.scannedAt).toString(),
                            )
                        },
                    ),
                ),
            )
        }
        result.onFailure { error ->
            val message = error.message.orEmpty().take(300)
            localReceivingDao.updateScanStatuses(scope.userId, scope.namespaceId, rows.map { it.clientScanId }, LocalReceivingSyncStatus.Failed.value, message)
            localReceivingDao.updateBatchError(scope.userId, scope.namespaceId, sessionId, message)
        }.onSuccess { response ->
            val results = response.items.associateBy { it.clientScanId }
            rows.forEach { row ->
                val item = results[row.clientScanId]
                val accepted = item?.status == "accepted" || item?.status == "duplicate"
                localReceivingDao.updateScanStatuses(
                    scope.userId,
                    scope.namespaceId,
                    listOf(row.clientScanId),
                    if (accepted) LocalReceivingSyncStatus.Synced.value else LocalReceivingSyncStatus.Failed.value,
                    item?.message.orEmpty(),
                )
            }
            localReceivingDao.updateBatchError(scope.userId, scope.namespaceId, sessionId, "")
            receivingRepository.refreshCachedSession(sessionId)
        }
        result.getOrThrow()
        return batch(sessionId) ?: throw LocalReceivingClientException(LocalReceivingClientError.BatchNotFound)
    }

    suspend fun syncPendingBatches(): Boolean {
        if (sessionStore.current().isOffline) return false
        val scope = scope()
        var allSucceeded = true
        localReceivingDao.pendingSessionIds(scope.userId, scope.namespaceId).forEach { sessionId ->
            runCatching { sync(sessionId) }.onFailure { allSucceeded = false }
        }
        return allSucceeded
    }

    private suspend fun batch(sessionId: Long): LocalReceivingBatch? {
        val scope = scope()
        val entity = localReceivingDao.batch(scope.userId, scope.namespaceId, sessionId) ?: return null
        return LocalReceivingBatch(
            sessionId = entity.sessionId,
            sessionCode = entity.sessionCode,
            snapshotAt = entity.snapshotAt,
            totalCount = localReceivingDao.totalCount(scope.userId, scope.namespaceId, sessionId),
            pendingCount = localReceivingDao.pendingCount(scope.userId, scope.namespaceId, sessionId),
            lastSyncError = entity.lastSyncError,
        )
    }

    private fun toLocalScan(entity: LocalReceivingScanEntity): LocalReceivingScan = LocalReceivingScan(
        id = entity.id,
        clientScanId = entity.clientScanId,
        labelCode = entity.labelCode,
        items = runCatching { json.decodeFromString<List<BoxLabelQrItem>>(entity.itemsPayload) }.getOrDefault(emptyList()),
        actualReceivedQty = entity.actualReceivedQty,
        scannedAt = entity.scannedAt,
        syncStatus = entity.syncStatus,
        syncError = entity.syncError,
    )

    private suspend fun scope(): LocalReceivingScope {
        val session = sessionStore.current()
        return LocalReceivingScope(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
    }
}

private data class LocalReceivingScope(val userId: Long, val namespaceId: Long)

enum class LocalReceivingClientError {
    BatchNotSaved,
    NoActiveBatch,
    OfflineSyncUnavailable,
    BatchNotFound,
}

class LocalReceivingClientException(
    val reason: LocalReceivingClientError,
) : IllegalStateException()

class LocalReceivingDuplicateException(
    val labelCode: String,
) : IllegalStateException()
