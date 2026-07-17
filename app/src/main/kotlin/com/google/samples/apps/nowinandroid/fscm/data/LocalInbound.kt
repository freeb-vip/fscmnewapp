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

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "local_inbound_sessions", primaryKeys = ["userId", "namespaceId", "sessionId"])
data class LocalInboundSessionEntity(
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val sessionCode: String,
    val status: String = InboundSessionStatus.Active.value,
    val snapshotComplete: Boolean = false,
    val snapshotAt: Long = 0,
    val totalCount: Int = 0,
    val inboundedCount: Int = 0,
    val unassignedCount: Int = 0,
    val lastSyncAttemptAt: Long = 0,
    val lastSyncSuccessAt: Long = 0,
    val lastSyncError: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_inbound_snapshots",
    primaryKeys = ["userId", "namespaceId", "sessionId", "boxPlanId"],
    indices = [Index(value = ["userId", "namespaceId", "sessionId", "labelCode"])],
)
data class LocalInboundSnapshotEntity(
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val boxPlanId: Long,
    val labelCode: String,
    val boxNo: String,
    val boxUid: String,
    val status: String,
    val inboundStatus: String,
    val locationCode: String,
    val eligible: Boolean,
    val snapshotAt: Long,
)

@Entity(
    tableName = "local_inbound_batches",
    indices = [Index(value = ["userId", "namespaceId", "sessionId", "status", "createdAt"])],
)
data class LocalInboundBatchEntity(
    @PrimaryKey val id: String,
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val locationCode: String,
    val status: String = InboundBatchStatus.Draft.value,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_inbound_items",
    indices = [
        Index(value = ["batchId"]),
        Index(value = ["userId", "namespaceId", "labelCode"], unique = true),
        Index(value = ["userId", "namespaceId", "status", "scannedAt"]),
    ],
)
data class LocalInboundItemEntity(
    @PrimaryKey val requestId: String,
    val batchId: String,
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val boxPlanId: Long,
    val labelCode: String,
    val rawCode: String,
    val localLocationCode: String,
    val remoteLocationCode: String = "",
    val status: String = InboundItemStatus.Draft.value,
    val errorCode: Int = 0,
    val errorMessage: String = "",
    val correctionRequestId: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class InboundSessionStatus(val value: String) {
    Active("active"),
    LocalCompletePendingSync("local_complete_pending_sync"),
    ConflictPending("conflict_pending"),
    Complete("complete"),
    Failed("failed"),
}

enum class InboundBatchStatus(val value: String) {
    Draft("draft"),
    Pending("pending"),
    Syncing("syncing"),
    Synced("synced"),
    ConflictPending("conflict_pending"),
    Failed("failed"),
}

enum class InboundItemStatus(val value: String) {
    Draft("draft"),
    Pending("pending"),
    Syncing("syncing"),
    Synced("synced"),
    ConflictPending("conflict_pending"),
    RemoteAccepted("remote_accepted"),
    RemoteOverridden("remote_overridden"),
    Rejected("rejected"),
}

@Dao
interface LocalInboundDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: LocalInboundSessionEntity)

    @Query("SELECT * FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId ORDER BY updatedAt DESC")
    suspend fun sessions(userId: Long, namespaceId: Long): List<LocalInboundSessionEntity>

    @Query("SELECT * FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId LIMIT 1")
    suspend fun session(userId: Long, namespaceId: Long, sessionId: Long): LocalInboundSessionEntity?

    @Query("DELETE FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun deleteSnapshots(userId: Long, namespaceId: Long, sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshots(rows: List<LocalInboundSnapshotEntity>)

    @Query("SELECT * FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY boxNo, labelCode")
    suspend fun snapshots(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundSnapshotEntity>

    @Query("UPDATE local_inbound_snapshots SET locationCode = :locationCode, snapshotAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND boxPlanId = :boxPlanId")
    suspend fun updateSnapshotLocation(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        boxPlanId: Long,
        locationCode: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Transaction
    suspend fun replaceSnapshots(
        session: LocalInboundSessionEntity,
        rows: List<LocalInboundSnapshotEntity>,
    ) {
        deleteSnapshots(session.userId, session.namespaceId, session.sessionId)
        if (rows.isNotEmpty()) insertSnapshots(rows)
        upsertSession(session)
    }

    @Insert
    suspend fun insertBatch(entity: LocalInboundBatchEntity)

    @Query("SELECT * FROM local_inbound_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'draft' ORDER BY createdAt DESC LIMIT 1")
    suspend fun draftBatch(userId: Long, namespaceId: Long, sessionId: Long): LocalInboundBatchEntity?

    @Query("SELECT * FROM local_inbound_batches WHERE id = :batchId LIMIT 1")
    suspend fun batch(batchId: String): LocalInboundBatchEntity?

    @Query("SELECT * FROM local_inbound_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun batches(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundBatchEntity>

    @Query("UPDATE local_inbound_batches SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :batchId")
    suspend fun updateBatchStatus(
        batchId: String,
        status: String,
        completedAt: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM local_inbound_batches WHERE id = :batchId AND status = 'draft'")
    suspend fun deleteDraftBatch(batchId: String)

    @Insert
    suspend fun insertItem(entity: LocalInboundItemEntity)

    @Query("SELECT * FROM local_inbound_items WHERE batchId = :batchId ORDER BY scannedAt DESC")
    suspend fun itemsForBatch(batchId: String): List<LocalInboundItemEntity>

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY scannedAt DESC")
    suspend fun itemsForSession(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND labelCode = :labelCode LIMIT 1")
    suspend fun itemForLabel(userId: Long, namespaceId: Long, labelCode: String): LocalInboundItemEntity?

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND requestId = :requestId LIMIT 1")
    suspend fun item(userId: Long, namespaceId: Long, requestId: String): LocalInboundItemEntity?

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending', 'syncing') ORDER BY scannedAt ASC")
    suspend fun pendingItems(userId: Long, namespaceId: Long): List<LocalInboundItemEntity>

    @Query("UPDATE local_inbound_items SET status = 'pending', errorCode = 0, errorMessage = '', updatedAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'rejected'")
    suspend fun retryRejected(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'conflict_pending' ORDER BY scannedAt ASC")
    suspend fun conflicts(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query("UPDATE local_inbound_items SET status = :status, remoteLocationCode = :remoteLocationCode, boxPlanId = :boxPlanId, errorCode = :errorCode, errorMessage = :errorMessage, syncedAt = :syncedAt, updatedAt = :updatedAt WHERE requestId = :requestId")
    suspend fun updateItemResult(
        requestId: String,
        status: String,
        remoteLocationCode: String,
        boxPlanId: Long,
        errorCode: Int,
        errorMessage: String,
        syncedAt: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE local_inbound_items SET correctionRequestId = :correctionRequestId, updatedAt = :updatedAt WHERE requestId = :requestId")
    suspend fun updateCorrectionRequest(
        requestId: String,
        correctionRequestId: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM local_inbound_items WHERE batchId = :batchId AND status = 'draft'")
    suspend fun deleteDraftItems(batchId: String)

    @Query("DELETE FROM local_inbound_items WHERE requestId = :requestId AND status = 'draft'")
    suspend fun deleteDraftItem(requestId: String)

    @Query("UPDATE local_inbound_items SET status = 'pending', updatedAt = :updatedAt WHERE batchId = :batchId AND status = 'draft'")
    suspend fun queueDraftItems(batchId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM local_inbound_items WHERE batchId = :batchId")
    suspend fun batchItemCount(batchId: String): Int

    @Transaction
    suspend fun finishBatch(batchId: String, completedAt: Long = System.currentTimeMillis()) {
        queueDraftItems(batchId, completedAt)
        updateBatchStatus(batchId, InboundBatchStatus.Pending.value, completedAt, completedAt)
    }

    @Query("UPDATE OR IGNORE local_inbound_sessions SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacySessions(userId: Long, namespaceId: Long)

    @Query("UPDATE OR IGNORE local_inbound_snapshots SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacySnapshots(userId: Long, namespaceId: Long)

    @Query("UPDATE OR IGNORE local_inbound_batches SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacyBatches(userId: Long, namespaceId: Long)

    @Query("UPDATE OR IGNORE local_inbound_items SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacyItems(userId: Long, namespaceId: Long)
}

@Serializable
data class ReceivingInboundItem(
    val id: Long = 0,
    @SerialName("box_plan_id") val boxPlanId: Long = id,
    @SerialName("box_no") val boxNo: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("box_uid") val boxUid: String = "",
    @SerialName("location_code") val locationCode: String = "",
    val status: String = "",
    @SerialName("inbound_status") val inboundStatus: String = "",
)

@Serializable
data class ReceivingInboundSummary(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("inbounded_count") val inboundedCount: Int = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("exception_count") val exceptionCount: Int = 0,
    @SerialName("unassigned_count") val unassignedCount: Int = pendingCount,
)

@Serializable
private data class ReceivingInboundPage(
    val data: List<ReceivingInboundItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 200,
    val summary: ReceivingInboundSummary = ReceivingInboundSummary(),
)

@Serializable
private data class ReceivingInboundMutationResponse(
    val result: String = "",
    val item: ReceivingInboundItem = ReceivingInboundItem(),
    val duplicate: Boolean = false,
    val replayed: Boolean = false,
)

data class InboundSessionSummary(
    val sessionId: Long,
    val sessionCode: String,
    val remoteStatus: String,
    val totalCount: Int,
    val inboundedCount: Int,
    val localAssignedCount: Int,
    val remainingCount: Int,
    val pendingCount: Int,
    val conflictCount: Int,
    val rejectedCount: Int,
    val lastSyncAt: Long,
    val snapshotComplete: Boolean,
    val status: String,
)

data class InboundWorkspace(
    val summary: InboundSessionSummary,
    val draftBatch: LocalInboundBatchEntity?,
    val draftItems: List<LocalInboundItemEntity>,
    val conflicts: List<LocalInboundItemEntity>,
)

data class InboundProgress(
    val localContribution: Int,
    val remaining: Int,
    val pending: Int,
    val conflicts: Int,
    val rejected: Int,
)

internal fun calculateInboundProgress(
    snapshots: List<LocalInboundSnapshotEntity>,
    items: List<LocalInboundItemEntity>,
): InboundProgress {
    val assignedLocal = items.filterNot { it.status == InboundItemStatus.Rejected.value }
    val assignedLabels = assignedLocal.mapTo(hashSetOf()) { it.labelCode }
    return InboundProgress(
        localContribution = assignedLocal.count { item ->
            snapshots.firstOrNull { it.labelCode == item.labelCode }?.locationCode.isNullOrBlank()
        },
        remaining = snapshots.count { it.eligible && it.locationCode.isBlank() && it.labelCode !in assignedLabels },
        pending = items.count {
            it.status in setOf(
                InboundItemStatus.Draft.value,
                InboundItemStatus.Pending.value,
                InboundItemStatus.Syncing.value,
            )
        },
        conflicts = items.count { it.status == InboundItemStatus.ConflictPending.value },
        rejected = items.count { it.status == InboundItemStatus.Rejected.value },
    )
}

class InboundClientException(val reason: InboundClientError) : IllegalStateException()

enum class InboundClientError {
    SnapshotUnavailable,
    InvalidLocation,
    SessionComplete,
    BatchNotFound,
    EmptyBatch,
    InvalidBoxLabel,
    BoxNotInSession,
    BoxNotEligible,
    BoxAlreadyAssigned,
    BoxAlreadyScanned,
    ConflictNotFound,
    OfflineSyncUnavailable,
}

fun isValidInboundLocation(value: String): Boolean = INBOUND_LOCATION_REGEX.matches(value.trimScannerSuffix())

fun normalizeInboundLocation(value: String): String {
    val normalized = value.trimScannerSuffix()
    if (!isValidInboundLocation(normalized)) throw InboundClientException(InboundClientError.InvalidLocation)
    return normalized
}

@Singleton
class LocalInboundRepository @Inject constructor(
    private val api: FscmApiClient,
    private val receivingRepository: ReceivingRepository,
    private val sessionStore: SessionStore,
    private val dao: LocalInboundDao,
) {
    private val syncMutex = Mutex()

    suspend fun sessionSummaries(): List<InboundSessionSummary> {
        val scope = scope()
        val remote = receivingRepository.sessions()
        val local = dao.sessions(scope.userId, scope.namespaceId).associateBy { it.sessionId }
        return remote.map { session ->
            buildSummary(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = session.id,
                sessionCode = session.code.ifBlank { "RS-${session.id}" },
                remoteStatus = session.status,
                localSession = local[session.id],
                fallbackTotal = session.expectedBoxCount,
            )
        }
    }

    suspend fun prepareSession(sessionId: Long): InboundWorkspace {
        val session = receivingRepository.session(sessionId)
        val scope = scope()
        if (sessionStore.current().isOffline) {
            if (dao.session(scope.userId, scope.namespaceId, sessionId)?.snapshotComplete != true) {
                throw InboundClientException(InboundClientError.SnapshotUnavailable)
            }
        } else {
            refreshSnapshot(sessionId, session.code.ifBlank { "RS-$sessionId" })
        }
        return workspace(sessionId, session.status)
    }

    suspend fun workspace(sessionId: Long, remoteStatus: String = ""): InboundWorkspace {
        val scope = scope()
        val localSession = dao.session(scope.userId, scope.namespaceId, sessionId)
            ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        recomputeSession(scope.userId, scope.namespaceId, sessionId, remoteStatus)
        val refreshed = dao.session(scope.userId, scope.namespaceId, sessionId) ?: localSession
        val draft = dao.draftBatch(scope.userId, scope.namespaceId, sessionId)
        return InboundWorkspace(
            summary = buildSummary(scope.userId, scope.namespaceId, sessionId, refreshed.sessionCode, remoteStatus, refreshed),
            draftBatch = draft,
            draftItems = draft?.let { dao.itemsForBatch(it.id) }.orEmpty(),
            conflicts = dao.conflicts(scope.userId, scope.namespaceId, sessionId),
        )
    }

    suspend fun startBatch(sessionId: Long, rawLocation: String): LocalInboundBatchEntity {
        val scope = scope()
        dao.draftBatch(scope.userId, scope.namespaceId, sessionId)?.let { return it }
        val summary = buildSummary(
            scope.userId,
            scope.namespaceId,
            sessionId,
            dao.session(scope.userId, scope.namespaceId, sessionId)?.sessionCode.orEmpty(),
            "",
            dao.session(scope.userId, scope.namespaceId, sessionId),
        )
        if (summary.remainingCount <= 0) throw InboundClientException(InboundClientError.SessionComplete)
        val batch = LocalInboundBatchEntity(
            id = UUID.randomUUID().toString(),
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            sessionId = sessionId,
            locationCode = normalizeInboundLocation(rawLocation),
        )
        dao.insertBatch(batch)
        return batch
    }

    suspend fun addBox(batchId: String, rawCode: String): LocalInboundItemEntity {
        val batch = dao.batch(batchId)?.takeIf { it.status == InboundBatchStatus.Draft.value }
            ?: throw InboundClientException(InboundClientError.BatchNotFound)
        val parsed = runCatching { parseBoxLabelQr(rawCode) }
            .getOrElse { throw InboundClientException(InboundClientError.InvalidBoxLabel) }
        val candidates = setOf(parsed.labelCode, parsed.originalRawCode.trimScannerSuffix())
        val snapshot = dao.snapshots(batch.userId, batch.namespaceId, batch.sessionId).firstOrNull { row ->
            candidates.any { candidate ->
                candidate.equals(row.labelCode, true) ||
                    candidate.equals(row.boxNo, true) ||
                    candidate.equals(row.boxUid, true)
            }
        } ?: throw InboundClientException(InboundClientError.BoxNotInSession)
        if (!snapshot.eligible) throw InboundClientException(InboundClientError.BoxNotEligible)
        if (snapshot.locationCode.isNotBlank()) throw InboundClientException(InboundClientError.BoxAlreadyAssigned)
        if (dao.itemForLabel(batch.userId, batch.namespaceId, snapshot.labelCode) != null) {
            throw InboundClientException(InboundClientError.BoxAlreadyScanned)
        }
        val item = LocalInboundItemEntity(
            requestId = UUID.randomUUID().toString(),
            batchId = batch.id,
            userId = batch.userId,
            namespaceId = batch.namespaceId,
            sessionId = batch.sessionId,
            boxPlanId = snapshot.boxPlanId,
            labelCode = snapshot.labelCode,
            rawCode = parsed.originalRawCode,
            localLocationCode = batch.locationCode,
        )
        dao.insertItem(item)
        return item
    }

    suspend fun deleteDraftBox(requestId: String) = dao.deleteDraftItem(requestId)

    suspend fun cancelDraft(batchId: String) {
        dao.deleteDraftItems(batchId)
        dao.deleteDraftBatch(batchId)
    }

    suspend fun finishBatch(batchId: String): InboundWorkspace {
        val batch = dao.batch(batchId) ?: throw InboundClientException(InboundClientError.BatchNotFound)
        if (dao.batchItemCount(batchId) == 0) throw InboundClientException(InboundClientError.EmptyBatch)
        dao.finishBatch(batchId)
        recomputeSession(batch.userId, batch.namespaceId, batch.sessionId, "")
        return workspace(batch.sessionId)
    }

    suspend fun syncAllPending(): Boolean = syncMutex.withLock {
        if (sessionStore.current().isOffline) return false
        val scope = scope()
        val pending = dao.pendingItems(scope.userId, scope.namespaceId)
        val touchedSessions = pending.mapTo(linkedSetOf()) { it.sessionId }
        var allTransientSucceeded = true
        val attemptAt = System.currentTimeMillis()
        pending.forEach { item ->
            val result = runCatching { syncItem(item) }
            if (result.isFailure) allTransientSucceeded = false
        }
        touchedSessions.forEach { sessionId ->
            val session = dao.session(scope.userId, scope.namespaceId, sessionId) ?: return@forEach
            val refreshed = runCatching { refreshSnapshot(sessionId, session.sessionCode) }.isSuccess
            dao.upsertSession(
                (dao.session(scope.userId, scope.namespaceId, sessionId) ?: session).copy(
                    lastSyncAttemptAt = attemptAt,
                    lastSyncSuccessAt = if (refreshed) System.currentTimeMillis() else session.lastSyncSuccessAt,
                    lastSyncError = if (refreshed) "" else session.lastSyncError,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            recomputeSession(scope.userId, scope.namespaceId, sessionId, "")
            recomputeBatches(scope.userId, scope.namespaceId, sessionId)
        }
        allTransientSucceeded
    }

    suspend fun syncSessionManually(sessionId: Long): InboundWorkspace {
        if (sessionStore.current().isOffline) throw InboundClientException(InboundClientError.OfflineSyncUnavailable)
        val scope = scope()
        dao.retryRejected(scope.userId, scope.namespaceId, sessionId)
        syncAllPending()
        val session = dao.session(scope.userId, scope.namespaceId, sessionId)
            ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        refreshSnapshot(sessionId, session.sessionCode)
        recomputeSession(scope.userId, scope.namespaceId, sessionId, "")
        return workspace(sessionId)
    }

    suspend fun acceptRemote(requestId: String): InboundWorkspace {
        val scope = scope()
        val item = findConflict(scope.userId, scope.namespaceId, requestId)
        dao.updateItemResult(
            requestId = item.requestId,
            status = InboundItemStatus.RemoteAccepted.value,
            remoteLocationCode = item.remoteLocationCode,
            boxPlanId = item.boxPlanId,
            errorCode = 0,
            errorMessage = "",
            syncedAt = System.currentTimeMillis(),
        )
        dao.updateSnapshotLocation(scope.userId, scope.namespaceId, item.sessionId, item.boxPlanId, item.remoteLocationCode)
        recomputeSession(scope.userId, scope.namespaceId, item.sessionId, "")
        recomputeBatches(scope.userId, scope.namespaceId, item.sessionId)
        return workspace(item.sessionId)
    }

    suspend fun overrideRemote(requestId: String): InboundWorkspace {
        if (sessionStore.current().isOffline) throw InboundClientException(InboundClientError.OfflineSyncUnavailable)
        val scope = scope()
        var item = findConflict(scope.userId, scope.namespaceId, requestId)
        val correctionRequestId = item.correctionRequestId.ifBlank {
            UUID.randomUUID().toString().also { dao.updateCorrectionRequest(item.requestId, it) }
        }
        item = findConflict(scope.userId, scope.namespaceId, requestId)
        val response = try {
            api.put<ReceivingInboundMutationResponse>(
                "receiving-sessions/${item.sessionId}/inbound-items/${item.boxPlanId}/location",
                buildJsonObject {
                    put("expected_location_code", item.remoteLocationCode)
                    put("location_code", item.localLocationCode)
                    put("reason", "移动端离线入库冲突，用户确认采用本地库位")
                    put("request_id", correctionRequestId)
                },
            )
        } catch (error: FscmApiException) {
            if (error.standardCode() == INBOUND_CONCURRENT_CHANGE_CODE) {
                val authoritative = error.authoritativeLocation()
                dao.updateItemResult(
                    requestId = item.requestId,
                    status = InboundItemStatus.ConflictPending.value,
                    remoteLocationCode = authoritative.location.ifBlank { item.remoteLocationCode },
                    boxPlanId = authoritative.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
                    errorCode = INBOUND_CONCURRENT_CHANGE_CODE,
                    errorMessage = error.serverMessage.orEmpty(),
                    syncedAt = 0,
                )
            }
            throw error
        }
        val location = response.item.locationCode.ifBlank { item.localLocationCode }
        val boxPlanId = response.item.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId
        dao.updateItemResult(
            requestId = item.requestId,
            status = InboundItemStatus.RemoteOverridden.value,
            remoteLocationCode = location,
            boxPlanId = boxPlanId,
            errorCode = 0,
            errorMessage = "",
            syncedAt = System.currentTimeMillis(),
        )
        dao.updateSnapshotLocation(scope.userId, scope.namespaceId, item.sessionId, boxPlanId, location)
        recomputeSession(scope.userId, scope.namespaceId, item.sessionId, "")
        recomputeBatches(scope.userId, scope.namespaceId, item.sessionId)
        return workspace(item.sessionId)
    }

    private suspend fun syncItem(item: LocalInboundItemEntity) {
        dao.updateItemResult(
            requestId = item.requestId,
            status = InboundItemStatus.Syncing.value,
            remoteLocationCode = item.remoteLocationCode,
            boxPlanId = item.boxPlanId,
            errorCode = 0,
            errorMessage = "",
            syncedAt = 0,
        )
        try {
            val response = api.post<ReceivingInboundMutationResponse>(
                "receiving-sessions/${item.sessionId}/inbound-scan",
                buildJsonObject {
                    put("raw_code", item.rawCode)
                    put("location_code", item.localLocationCode)
                    put("request_id", item.requestId)
                    put("operation_mode", "scan")
                },
            )
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Synced.value,
                remoteLocationCode = response.item.locationCode.ifBlank { item.localLocationCode },
                boxPlanId = response.item.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
                errorCode = 0,
                errorMessage = "",
                syncedAt = System.currentTimeMillis(),
            )
        } catch (error: FscmApiException) {
            val code = error.standardCode()
            if (code == INBOUND_LOCATION_CONFLICT_CODE) {
                val authoritative = error.authoritativeLocation()
                dao.updateItemResult(
                    requestId = item.requestId,
                    status = InboundItemStatus.ConflictPending.value,
                    remoteLocationCode = authoritative.location,
                    boxPlanId = authoritative.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
                    errorCode = code,
                    errorMessage = error.serverMessage.orEmpty(),
                    syncedAt = 0,
                )
            } else if (error.statusCode >= 500 || error.statusCode == 401) {
                dao.updateItemResult(
                    requestId = item.requestId,
                    status = InboundItemStatus.Pending.value,
                    remoteLocationCode = item.remoteLocationCode,
                    boxPlanId = item.boxPlanId,
                    errorCode = code,
                    errorMessage = error.serverMessage.orEmpty(),
                    syncedAt = 0,
                )
                throw error
            } else {
                dao.updateItemResult(
                    requestId = item.requestId,
                    status = InboundItemStatus.Rejected.value,
                    remoteLocationCode = item.remoteLocationCode,
                    boxPlanId = item.boxPlanId,
                    errorCode = code,
                    errorMessage = error.serverMessage.orEmpty(),
                    syncedAt = 0,
                )
            }
        } catch (error: Exception) {
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Pending.value,
                remoteLocationCode = item.remoteLocationCode,
                boxPlanId = item.boxPlanId,
                errorCode = 0,
                errorMessage = error.message.orEmpty(),
                syncedAt = 0,
            )
            throw error
        }
    }

    private suspend fun refreshSnapshot(sessionId: Long, sessionCode: String) {
        val scope = scope()
        val rows = mutableListOf<ReceivingInboundItem>()
        var page = 1
        var total = Int.MAX_VALUE
        var summary = ReceivingInboundSummary()
        while (rows.size < total) {
            val response = api.get<ReceivingInboundPage>(
                "receiving-sessions/$sessionId/inbound-items",
                mapOf("page" to page.toString(), "pageSize" to SNAPSHOT_PAGE_SIZE.toString()),
            )
            rows += response.data
            total = response.total
            summary = response.summary
            if (response.data.isEmpty() || rows.size >= total) break
            page++
        }
        val now = System.currentTimeMillis()
        val snapshotRows = rows.map { row ->
            LocalInboundSnapshotEntity(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                boxPlanId = row.boxPlanId.takeIf { it > 0 } ?: row.id,
                labelCode = row.labelCode.ifBlank { row.boxNo.ifBlank { row.boxUid } },
                boxNo = row.boxNo,
                boxUid = row.boxUid,
                status = row.status,
                inboundStatus = row.inboundStatus,
                locationCode = row.locationCode,
                eligible = row.isInboundEligible(),
                snapshotAt = now,
            )
        }
        val eligibleRows = snapshotRows.filter { it.eligible }
        dao.replaceSnapshots(
            session = (
                dao.session(scope.userId, scope.namespaceId, sessionId)
                    ?: LocalInboundSessionEntity(scope.userId, scope.namespaceId, sessionId, sessionCode)
                ).copy(
                sessionCode = sessionCode,
                snapshotComplete = true,
                snapshotAt = now,
                totalCount = eligibleRows.size,
                inboundedCount = eligibleRows.count { it.locationCode.isNotBlank() },
                unassignedCount = summary.unassignedCount,
                updatedAt = now,
            ),
            rows = snapshotRows,
        )
        dao.conflicts(scope.userId, scope.namespaceId, sessionId).forEach { conflict ->
            val remote = snapshotRows.firstOrNull { it.boxPlanId == conflict.boxPlanId || it.labelCode == conflict.labelCode }
            if (remote?.locationCode?.isNotBlank() == true) {
                dao.updateItemResult(
                    requestId = conflict.requestId,
                    status = InboundItemStatus.ConflictPending.value,
                    remoteLocationCode = remote.locationCode,
                    boxPlanId = remote.boxPlanId,
                    errorCode = conflict.errorCode,
                    errorMessage = conflict.errorMessage,
                    syncedAt = 0,
                )
            }
        }
    }

    private suspend fun buildSummary(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        sessionCode: String,
        remoteStatus: String,
        localSession: LocalInboundSessionEntity?,
        fallbackTotal: Int = 0,
    ): InboundSessionSummary {
        val snapshots = dao.snapshots(userId, namespaceId, sessionId)
        val items = dao.itemsForSession(userId, namespaceId, sessionId)
        val progress = calculateInboundProgress(snapshots, items)
        return InboundSessionSummary(
            sessionId = sessionId,
            sessionCode = sessionCode.ifBlank { "RS-$sessionId" },
            remoteStatus = remoteStatus,
            totalCount = localSession?.totalCount?.takeIf { it > 0 } ?: snapshots.count { it.eligible }.takeIf { it > 0 } ?: fallbackTotal,
            inboundedCount = localSession?.inboundedCount ?: snapshots.count { it.eligible && it.locationCode.isNotBlank() },
            localAssignedCount = progress.localContribution,
            remainingCount = progress.remaining.takeIf { localSession?.snapshotComplete == true } ?: fallbackTotal,
            pendingCount = progress.pending,
            conflictCount = progress.conflicts,
            rejectedCount = progress.rejected,
            lastSyncAt = localSession?.lastSyncSuccessAt ?: 0,
            snapshotComplete = localSession?.snapshotComplete == true,
            status = localSession?.status ?: InboundSessionStatus.Active.value,
        )
    }

    private suspend fun recomputeSession(userId: Long, namespaceId: Long, sessionId: Long, remoteStatus: String) {
        val session = dao.session(userId, namespaceId, sessionId) ?: return
        val summary = buildSummary(userId, namespaceId, sessionId, session.sessionCode, remoteStatus, session)
        val status = when {
            summary.conflictCount > 0 -> InboundSessionStatus.ConflictPending.value
            summary.rejectedCount > 0 -> InboundSessionStatus.Failed.value
            summary.pendingCount > 0 && summary.remainingCount == 0 -> InboundSessionStatus.LocalCompletePendingSync.value
            summary.pendingCount > 0 -> InboundSessionStatus.Active.value
            summary.snapshotComplete && session.unassignedCount == 0 && summary.remainingCount == 0 -> InboundSessionStatus.Complete.value
            else -> InboundSessionStatus.Active.value
        }
        dao.upsertSession(session.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun recomputeBatches(userId: Long, namespaceId: Long, sessionId: Long) {
        dao.batches(userId, namespaceId, sessionId).filterNot { it.status == InboundBatchStatus.Draft.value }.forEach { batch ->
            val items = dao.itemsForBatch(batch.id)
            val status = when {
                items.any { it.status == InboundItemStatus.ConflictPending.value } -> InboundBatchStatus.ConflictPending.value
                items.any { it.status == InboundItemStatus.Rejected.value } -> InboundBatchStatus.Failed.value
                items.any { it.status in setOf(InboundItemStatus.Pending.value, InboundItemStatus.Syncing.value) } -> InboundBatchStatus.Pending.value
                else -> InboundBatchStatus.Synced.value
            }
            dao.updateBatchStatus(batch.id, status, batch.completedAt)
        }
    }

    private suspend fun findConflict(userId: Long, namespaceId: Long, requestId: String): LocalInboundItemEntity =
        dao.item(userId, namespaceId, requestId)?.takeIf { it.status == InboundItemStatus.ConflictPending.value }
            ?: throw InboundClientException(InboundClientError.ConflictNotFound)

    private suspend fun scope(): LocalInboundScope {
        val session = sessionStore.current()
        return LocalInboundScope(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
    }
}

private data class LocalInboundScope(val userId: Long, val namespaceId: Long)

private data class AuthoritativeInboundLocation(val boxPlanId: Long, val location: String)

private fun FscmApiException.standardCode(): Int =
    (payload as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull ?: statusCode

private fun FscmApiException.authoritativeLocation(): AuthoritativeInboundLocation {
    val data = runCatching { (payload as JsonObject)["data"]?.jsonObject }.getOrNull()
    return AuthoritativeInboundLocation(
        boxPlanId = data?.get("box_plan_id")?.jsonPrimitive?.intOrNull?.toLong()
            ?: data?.get("id")?.jsonPrimitive?.intOrNull?.toLong()
            ?: 0,
        location = data?.get("location_code")?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun ReceivingInboundItem.isInboundEligible(): Boolean =
    status.lowercase() !in INELIGIBLE_INBOUND_STATUSES && inboundStatus.lowercase() !in setOf("exception", "invalid")

private fun String.trimScannerSuffix(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }

private val INBOUND_LOCATION_REGEX = Regex("^[A-Z0-9-]{1,100}$")
private val INELIGIBLE_INBOUND_STATUSES = setOf("shortage_marked", "adjusted_out", "shorted", "cancelled", "voided", "exception")
private const val SNAPSHOT_PAGE_SIZE = 200
private const val INBOUND_LOCATION_CONFLICT_CODE = 40942
private const val INBOUND_CONCURRENT_CHANGE_CODE = 40943
