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
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.work.BackoffPolicy
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "central_receiving_snapshot_meta", primaryKeys = ["userId", "namespaceId"])
data class CentralReceivingSnapshotMetaEntity(
    val userId: Long,
    val namespaceId: Long,
    val activeGeneration: String = "",
    val snapshotAt: Long = 0,
    val complete: Boolean = false,
    val lastSyncError: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "central_receiving_receipts",
    primaryKeys = ["userId", "namespaceId", "generation", "remoteId"],
    indices = [Index(value = ["userId", "namespaceId", "generation", "status"])],
)
data class CentralReceivingReceiptEntity(
    val userId: Long,
    val namespaceId: Long,
    val remoteId: Long,
    val status: String,
    val payload: String,
    val generation: String,
)

@Entity(
    tableName = "central_receiving_labels",
    primaryKeys = ["userId", "namespaceId", "generation", "boxPlanId"],
    indices = [
        Index(value = ["userId", "namespaceId", "generation", "centralReceiptId"]),
        Index(value = ["userId", "namespaceId", "generation", "labelCode"]),
        Index(value = ["userId", "namespaceId", "generation", "boxUid"]),
        Index(value = ["userId", "namespaceId", "generation", "boxNo"]),
    ],
)
data class CentralReceivingLabelEntity(
    val userId: Long,
    val namespaceId: Long,
    val boxPlanId: Long,
    val centralReceiptId: Long,
    val centralReceiptItemId: Long,
    val labelCode: String,
    val boxUid: String,
    val boxNo: String,
    val remoteStatus: String,
    val signStatus: String,
    val localStatus: String = "",
    val payload: String,
    val generation: String,
    val lastLocalScannedAt: Long = 0,
)

@Entity(
    tableName = "central_receiving_scans",
    indices = [
        Index(value = ["clientScanId"], unique = true),
        Index(value = ["userId", "namespaceId", "status", "scannedAt"]),
        Index(value = ["userId", "namespaceId", "boxPlanId", "scannedAt"]),
    ],
)
data class CentralReceivingScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientScanId: String,
    val userId: Long,
    val namespaceId: Long,
    val boxPlanId: Long = 0,
    val centralReceiptId: Long = 0,
    val labelCode: String = "",
    val rawCode: String,
    val scannedAt: Long = System.currentTimeMillis(),
    val snapshotAt: Long = 0,
    val status: String = CentralReceivingScanStatus.Pending.value,
    val resultCode: String = "",
    val resultMessage: String = "",
    val retryCount: Int = 0,
    val syncedAt: Long = 0,
)

enum class CentralReceivingScanStatus(val value: String) {
    Pending("pending"),
    Uploading("uploading"),
    Received("received"),
    Duplicate("duplicate"),
    Retryable("retryable"),
    Rejected("rejected"),
}

@Dao
interface CentralReceivingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: CentralReceivingSnapshotMetaEntity)

    @Query("SELECT * FROM central_receiving_snapshot_meta WHERE userId = :userId AND namespaceId = :namespaceId LIMIT 1")
    suspend fun meta(userId: Long, namespaceId: Long): CentralReceivingSnapshotMetaEntity?

    @Query("SELECT * FROM central_receiving_snapshot_meta WHERE userId = :userId AND namespaceId = :namespaceId LIMIT 1")
    fun observeMeta(userId: Long, namespaceId: Long): Flow<CentralReceivingSnapshotMetaEntity?>

    @Query("SELECT COALESCE(SUM(scannedAt + retryCount + syncedAt), 0) FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId")
    fun observeScanVersion(userId: Long, namespaceId: Long): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipts(rows: List<CentralReceivingReceiptEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabels(rows: List<CentralReceivingLabelEntity>)

    @Query(
        "SELECT r.* FROM central_receiving_receipts r JOIN central_receiving_snapshot_meta m " +
            "ON m.userId = r.userId AND m.namespaceId = r.namespaceId AND m.activeGeneration = r.generation " +
            "WHERE r.userId = :userId AND r.namespaceId = :namespaceId ORDER BY r.remoteId DESC",
    )
    suspend fun receipts(userId: Long, namespaceId: Long): List<CentralReceivingReceiptEntity>

    @Query(
        "SELECT l.* FROM central_receiving_labels l JOIN central_receiving_snapshot_meta m " +
            "ON m.userId = l.userId AND m.namespaceId = l.namespaceId AND m.activeGeneration = l.generation " +
            "WHERE l.userId = :userId AND l.namespaceId = :namespaceId ORDER BY l.boxPlanId",
    )
    suspend fun labels(userId: Long, namespaceId: Long): List<CentralReceivingLabelEntity>

    @Query(
        "SELECT l.* FROM central_receiving_labels l JOIN central_receiving_snapshot_meta m " +
            "ON m.userId = l.userId AND m.namespaceId = l.namespaceId AND m.activeGeneration = l.generation " +
            "WHERE l.userId = :userId AND l.namespaceId = :namespaceId AND l.centralReceiptId = :receiptId ORDER BY l.boxPlanId",
    )
    suspend fun labelsForReceipt(userId: Long, namespaceId: Long, receiptId: Long): List<CentralReceivingLabelEntity>

    @Query(
        "SELECT l.* FROM central_receiving_labels l JOIN central_receiving_snapshot_meta m " +
            "ON m.userId = l.userId AND m.namespaceId = l.namespaceId AND m.activeGeneration = l.generation " +
            "WHERE l.userId = :userId AND l.namespaceId = :namespaceId " +
            "AND (UPPER(l.labelCode) = UPPER(:code) OR UPPER(l.boxUid) = UPPER(:code) OR UPPER(l.boxNo) = UPPER(:code)) LIMIT 1",
    )
    suspend fun findLabel(userId: Long, namespaceId: Long, code: String): CentralReceivingLabelEntity?

    @Query("DELETE FROM central_receiving_receipts WHERE userId = :userId AND namespaceId = :namespaceId AND generation <> :generation")
    suspend fun deleteOldReceipts(userId: Long, namespaceId: Long, generation: String)

    @Query("DELETE FROM central_receiving_labels WHERE userId = :userId AND namespaceId = :namespaceId AND generation <> :generation")
    suspend fun deleteOldLabels(userId: Long, namespaceId: Long, generation: String)

    @Query("DELETE FROM central_receiving_receipts WHERE userId = :userId AND namespaceId = :namespaceId AND generation = :generation")
    suspend fun deleteReceiptGeneration(userId: Long, namespaceId: Long, generation: String)

    @Query("DELETE FROM central_receiving_labels WHERE userId = :userId AND namespaceId = :namespaceId AND generation = :generation")
    suspend fun deleteLabelGeneration(userId: Long, namespaceId: Long, generation: String)

    @Query(
        "UPDATE central_receiving_labels SET " +
            "localStatus = CASE WHEN EXISTS (SELECT 1 FROM central_receiving_scans s " +
            "WHERE s.userId = central_receiving_labels.userId AND s.namespaceId = central_receiving_labels.namespaceId " +
            "AND s.boxPlanId = central_receiving_labels.boxPlanId AND s.status <> 'rejected') THEN 'received_local' ELSE '' END, " +
            "lastLocalScannedAt = COALESCE((SELECT MAX(s.scannedAt) FROM central_receiving_scans s " +
            "WHERE s.userId = central_receiving_labels.userId AND s.namespaceId = central_receiving_labels.namespaceId " +
            "AND s.boxPlanId = central_receiving_labels.boxPlanId AND s.status <> 'rejected'), 0) " +
            "WHERE userId = :userId AND namespaceId = :namespaceId",
    )
    suspend fun reconcileLocalScanState(userId: Long, namespaceId: Long)

    @Transaction
    suspend fun activateSnapshot(meta: CentralReceivingSnapshotMetaEntity) {
        reconcileLocalScanState(meta.userId, meta.namespaceId)
        upsertMeta(meta)
        deleteOldReceipts(meta.userId, meta.namespaceId, meta.activeGeneration)
        deleteOldLabels(meta.userId, meta.namespaceId, meta.activeGeneration)
    }

    @Transaction
    suspend fun discardSnapshotGeneration(userId: Long, namespaceId: Long, generation: String) {
        deleteReceiptGeneration(userId, namespaceId, generation)
        deleteLabelGeneration(userId, namespaceId, generation)
    }

    @Insert
    suspend fun insertScan(scan: CentralReceivingScanEntity): Long

    @Query("SELECT COUNT(*) FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND boxPlanId = :boxPlanId AND boxPlanId > 0 AND status <> 'rejected'")
    suspend fun scanCountForBox(userId: Long, namespaceId: Long, boxPlanId: Long): Int

    @Query("SELECT * FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId ORDER BY scannedAt DESC LIMIT :limit")
    suspend fun recentScans(userId: Long, namespaceId: Long, limit: Int = 30): List<CentralReceivingScanEntity>

    @Query("SELECT COUNT(*) FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending', 'uploading', 'retryable')")
    suspend fun pendingCount(userId: Long, namespaceId: Long): Int

    @Query("SELECT COUNT(*) FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('retryable', 'rejected')")
    suspend fun problemCount(userId: Long, namespaceId: Long): Int

    @Query("SELECT * FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending', 'retryable', 'uploading') ORDER BY scannedAt ASC LIMIT :limit")
    suspend fun pendingScans(userId: Long, namespaceId: Long, limit: Int = 100): List<CentralReceivingScanEntity>

    @Query("UPDATE central_receiving_scans SET status = 'pending' WHERE userId = :userId AND namespaceId = :namespaceId AND status = 'uploading'")
    suspend fun resetInterruptedUploads(userId: Long, namespaceId: Long)

    @Query("UPDATE central_receiving_scans SET status = :status WHERE id IN (:ids)")
    suspend fun updateScanStatus(ids: List<Long>, status: String)

    @Query("UPDATE central_receiving_scans SET status = :status, resultCode = :code, resultMessage = :message, retryCount = :retryCount, syncedAt = :syncedAt WHERE id = :id")
    suspend fun updateScanResult(
        id: Long,
        status: String,
        code: String,
        message: String,
        retryCount: Int,
        syncedAt: Long,
    )

    @Query("UPDATE central_receiving_labels SET localStatus = :status, lastLocalScannedAt = :scannedAt WHERE userId = :userId AND namespaceId = :namespaceId AND boxPlanId = :boxPlanId")
    suspend fun markLabelLocal(userId: Long, namespaceId: Long, boxPlanId: Long, status: String, scannedAt: Long)

    @Query("UPDATE central_receiving_scans SET status = 'pending', resultCode = '', resultMessage = '' WHERE userId = :userId AND namespaceId = :namespaceId AND id = :id")
    suspend fun retry(userId: Long, namespaceId: Long, id: Long)

    @Query("DELETE FROM central_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND id = :id AND status IN ('pending', 'retryable', 'rejected')")
    suspend fun cancel(userId: Long, namespaceId: Long, id: Long)
}

@Serializable
private data class CentralSnapshotResponse(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("max_box_plan_id") val maxBoxPlanId: Long = 0,
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val receipts: List<CentralReceipt> = emptyList(),
    val labels: List<CentralSnapshotLabel> = emptyList(),
)

@Serializable
private data class CentralSnapshotLabel(
    val id: Long = 0,
    @SerialName("box_plan_id") val boxPlanId: Long = 0,
    @SerialName("central_receipt_id") val centralReceiptId: Long = 0,
    @SerialName("central_receipt_item_id") val centralReceiptItemId: Long = 0,
    @SerialName("box_uid") val boxUid: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("box_no") val boxNo: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("case_spec_name") val caseSpecName: String = "",
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("actual_box_qty") val actualBoxQty: Int = 0,
    val status: String = "",
    @SerialName("sign_status") val signStatus: String = "",
    @SerialName("qr_code_text") val qrCodeText: String = "",
    @SerialName("central_received_at") val centralReceivedAt: String = "",
)

@Serializable
private data class CentralScanBatchRequest(
    @SerialName("batch_id") val batchId: String,
    @SerialName("device_id") val deviceId: String,
    val items: List<CentralScanBatchRequestItem>,
)

@Serializable
private data class CentralScanBatchRequestItem(
    @SerialName("client_scan_id") val clientScanId: String,
    @SerialName("raw_code") val rawCode: String,
    @SerialName("scanned_at") val scannedAt: String,
    @SerialName("snapshot_generated_at") val snapshotGeneratedAt: String? = null,
)

@Serializable
private data class CentralScanBatchResponse(val items: List<CentralScanBatchResult> = emptyList())

@Serializable
private data class CentralScanBatchResult(
    @SerialName("client_scan_id") val clientScanId: String,
    val status: String,
    val code: String = "",
    val message: String = "",
    @SerialName("box_plan_id") val boxPlanId: Long = 0,
    @SerialName("central_receipt_id") val centralReceiptId: Long = 0,
    @SerialName("plan_status") val planStatus: String = "",
)

@Singleton
class CentralReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val dao: CentralReceivingDao,
    private val scheduler: CentralReceivingSyncScheduler,
    @param:FscmJson private val json: Json,
) {
    private val scanMutex = Mutex()

    fun observeUpdates(): Flow<Long> = sessionStore.session.flatMapLatest { session ->
        val userId = session.user?.id ?: 0
        val namespaceId = session.currentNamespace?.id ?: 0
        if (userId <= 0 || namespaceId <= 0) {
            flowOf(0)
        } else {
            combine(
                dao.observeMeta(userId, namespaceId),
                dao.observeScanVersion(userId, namespaceId),
            ) { meta, scanVersion ->
                (meta?.updatedAt ?: 0) + scanVersion
            }
        }
    }

    suspend fun syncSummary(): CentralReceivingSyncSummary {
        val scope = scope()
        val meta = dao.meta(scope.userId, scope.namespaceId)
        return CentralReceivingSyncSummary(
            snapshotAt = meta?.snapshotAt ?: 0,
            pendingCount = dao.pendingCount(scope.userId, scope.namespaceId),
            problemCount = dao.problemCount(scope.userId, scope.namespaceId),
            lastError = meta?.lastSyncError.orEmpty(),
        )
    }

    suspend fun receipts(filter: BusinessFilter): List<CentralReceipt> {
        val scope = scope()
        return localReceipts(scope).filter { receipt ->
            when (filter) {
                BusinessFilter.Pending -> receipt.status in setOf("pending", "partial")
                BusinessFilter.Completed -> receipt.status == "completed"
                BusinessFilter.All -> true
            }
        }
    }

    suspend fun statistics(): CentralReceiptStatistics {
        val scope = scope()
        val receipts = localReceipts(scope)
        val labels = dao.labels(scope.userId, scope.namespaceId)
        return centralReceiptStatistics(receipts, labels)
    }

    suspend fun statistics(receiptId: Long): CentralReceiptStatistics {
        val scope = scope()
        val receipts = localReceipts(scope).filter { it.id == receiptId }
        val labels = dao.labelsForReceipt(scope.userId, scope.namespaceId, receiptId)
        return centralReceiptStatistics(receipts, labels)
    }

    suspend fun receipt(id: Long): CentralReceipt =
        localReceipts(scope()).firstOrNull { it.id == id } ?: CentralReceipt(id = id)

    suspend fun boxLabels(id: Long): List<CentralReceiptBoxLabel> {
        val scope = scope()
        return dao.labelsForReceipt(scope.userId, scope.namespaceId, id).map(::toDomainLabel)
    }

    suspend fun scan(id: Long?, rawCode: String, actualBoxQty: Int? = null): CentralReceiptScanResult = scanMutex.withLock {
        @Suppress("UNUSED_VARIABLE")
        val ignoredQuantity = actualBoxQty
        val parsed = parseCentralReceivingScanCode(rawCode)
        val scope = scope()
        val label = findLabel(scope, parsed)
        val duplicate = label?.let { dao.scanCountForBox(scope.userId, scope.namespaceId, it.boxPlanId) > 0 || it.effectiveReceived() } == true
        val now = System.currentTimeMillis()
        val meta = dao.meta(scope.userId, scope.namespaceId)
        val scan = CentralReceivingScanEntity(
            clientScanId = UUID.randomUUID().toString(),
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            boxPlanId = label?.boxPlanId ?: 0,
            centralReceiptId = label?.centralReceiptId ?: id.orZero(),
            labelCode = label?.labelCode.orEmpty().ifBlank { parsed.labelCode },
            rawCode = rawCode,
            scannedAt = now,
            snapshotAt = meta?.snapshotAt ?: 0,
            resultCode = if (duplicate) {
                "local_duplicate"
            } else if (label == null) {
                "pending_validation"
            } else {
                "local_received"
            },
            resultMessage = if (label == null) {
                "本地快照未找到箱唛，已保存待云端校验"
            } else if (duplicate) {
                "箱唛已确认收货"
            } else {
                "现场收货已确认"
            },
        )
        val localId = dao.insertScan(scan)
        if (label != null) {
            dao.markLabelLocal(
                scope.userId,
                scope.namespaceId,
                label.boxPlanId,
                if (duplicate) "duplicate_local" else "received_local",
                now,
            )
        }
        scheduler.enqueueSoon()
        return CentralReceiptScanResult(
            item = label?.let(::toDomainLabel) ?: CentralReceiptBoxLabel(labelCode = parsed.labelCode, boxNo = parsed.labelCode, qrCodeText = rawCode, status = "pending_validation"),
            centralReceiptId = label?.centralReceiptId ?: id.orZero(),
            duplicate = duplicate,
            confirmed = label != null,
            status = if (label == null) {
                "pending_validation"
            } else if (duplicate) {
                "duplicate_received"
            } else {
                "received_local"
            },
            message = if (label == null) {
                "本地快照未找到箱唛，已保存待云端校验"
            } else if (duplicate) {
                "箱唛已确认收货"
            } else {
                "现场收货已确认"
            },
            parsedPayload = parsed,
            pendingOperationId = localId,
            syncStatus = CentralReceivingScanStatus.Pending.value,
            localFallback = true,
        )
    }

    suspend fun pendingScans(): List<CentralReceiptScanResult> {
        val scope = scope()
        return dao.recentScans(scope.userId, scope.namespaceId).map { scan ->
            val label = if (scan.boxPlanId > 0) {
                dao.labels(scope.userId, scope.namespaceId).firstOrNull { it.boxPlanId == scan.boxPlanId }
            } else {
                null
            }
            CentralReceiptScanResult(
                item = label?.let(::toDomainLabel) ?: CentralReceiptBoxLabel(labelCode = scan.labelCode, boxNo = scan.labelCode, qrCodeText = scan.rawCode),
                centralReceiptId = scan.centralReceiptId,
                duplicate = scan.status == CentralReceivingScanStatus.Duplicate.value || scan.resultCode == "local_duplicate",
                confirmed = scan.boxPlanId > 0 && scan.status != CentralReceivingScanStatus.Rejected.value,
                status = when {
                    scan.status == CentralReceivingScanStatus.Pending.value && scan.boxPlanId > 0 -> "received_local"
                    scan.status == CentralReceivingScanStatus.Pending.value -> "pending_validation"
                    else -> scan.status
                },
                message = scan.resultMessage.ifBlank { centralLocalScanMessage(scan.status) },
                pendingOperationId = scan.id,
                syncStatus = scan.status,
                localFallback = scan.status !in setOf(CentralReceivingScanStatus.Received.value, CentralReceivingScanStatus.Duplicate.value),
            )
        }
    }

    suspend fun retryPending(id: Long) {
        val scope = scope()
        dao.retry(scope.userId, scope.namespaceId, id)
        scheduler.enqueueSoon()
    }

    suspend fun cancelPending(id: Long) {
        val scope = scope()
        dao.cancel(scope.userId, scope.namespaceId, id)
        dao.reconcileLocalScanState(scope.userId, scope.namespaceId)
    }

    fun requestSync() = scheduler.enqueueSoon()

    suspend fun syncAll(): Boolean {
        val session = sessionStore.current()
        if (session.token.isBlank() || session.user == null || session.currentNamespace == null) return false
        val scope = CentralReceivingScope(session.user.id, session.currentNamespace.id)
        dao.resetInterruptedUploads(scope.userId, scope.namespaceId)
        if (!uploadPending(scope, session.deviceId)) return false
        return downloadSnapshot(scope)
    }

    private suspend fun uploadPending(scope: CentralReceivingScope, deviceId: String): Boolean {
        while (true) {
            val rows = dao.pendingScans(scope.userId, scope.namespaceId)
            if (rows.isEmpty()) return true
            dao.updateScanStatus(rows.map { it.id }, CentralReceivingScanStatus.Uploading.value)
            val response = runCatching {
                api.post<CentralScanBatchResponse>(
                    "mobile/app/central-receiving/scans/batch",
                    json.encodeToJsonElement(
                        CentralScanBatchRequest(
                            batchId = UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            items = rows.map { row ->
                                CentralScanBatchRequestItem(
                                    clientScanId = row.clientScanId,
                                    rawCode = row.rawCode,
                                    scannedAt = Instant.ofEpochMilli(row.scannedAt).toString(),
                                    snapshotGeneratedAt = row.snapshotAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                                )
                            },
                        ),
                    ),
                )
            }.getOrElse { error ->
                rows.forEach { row ->
                    dao.updateScanResult(row.id, CentralReceivingScanStatus.Retryable.value, "network_error", error.message.orEmpty().take(300), row.retryCount + 1, 0)
                }
                return false
            }
            val results = response.items.associateBy { it.clientScanId }
            val syncedAt = System.currentTimeMillis()
            var hasRetryable = false
            rows.forEach { row ->
                val result = results[row.clientScanId]
                val status = when (result?.status) {
                    "received" -> CentralReceivingScanStatus.Received.value
                    "duplicate_received" -> CentralReceivingScanStatus.Duplicate.value
                    "rejected_unknown", "rejected_invalid" -> CentralReceivingScanStatus.Rejected.value
                    else -> CentralReceivingScanStatus.Retryable.value
                }
                if (status == CentralReceivingScanStatus.Retryable.value) hasRetryable = true
                dao.updateScanResult(
                    row.id,
                    status,
                    result?.code.orEmpty(),
                    result?.message.orEmpty().ifBlank { if (result == null) "云端未返回该扫码结果" else "" },
                    row.retryCount + if (status == CentralReceivingScanStatus.Retryable.value) 1 else 0,
                    if (status in setOf(CentralReceivingScanStatus.Received.value, CentralReceivingScanStatus.Duplicate.value)) syncedAt else 0,
                )
            }
            dao.reconcileLocalScanState(scope.userId, scope.namespaceId)
            if (hasRetryable) return false
            if (rows.size < 100) return true
        }
    }

    private suspend fun downloadSnapshot(scope: CentralReceivingScope): Boolean {
        val generation = UUID.randomUUID().toString()
        var cursor = 0L
        var maxBoxPlanId = 0L
        var snapshotAt = 0L
        var hasMore: Boolean
        do {
            val params = buildMap {
                put("page_size", "500")
                if (cursor > 0) put("cursor", cursor.toString())
                if (maxBoxPlanId > 0) put("max_box_plan_id", maxBoxPlanId.toString())
            }
            val page = runCatching { api.get<CentralSnapshotResponse>("mobile/app/central-receiving/snapshot", params) }
                .getOrElse { error ->
                    dao.discardSnapshotGeneration(scope.userId, scope.namespaceId, generation)
                    val current = dao.meta(scope.userId, scope.namespaceId)
                    dao.upsertMeta(
                        (current ?: CentralReceivingSnapshotMetaEntity(scope.userId, scope.namespaceId)).copy(
                            lastSyncError = error.message.orEmpty().take(300),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    return false
                }
            if (page.schemaVersion != 1) {
                dao.discardSnapshotGeneration(scope.userId, scope.namespaceId, generation)
                val current = dao.meta(scope.userId, scope.namespaceId)
                dao.upsertMeta(
                    (current ?: CentralReceivingSnapshotMetaEntity(scope.userId, scope.namespaceId)).copy(
                        lastSyncError = "不支持的集运收货快照版本: ${page.schemaVersion}",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                return false
            }
            if (maxBoxPlanId == 0L) maxBoxPlanId = page.maxBoxPlanId
            if (snapshotAt == 0L) snapshotAt = page.generatedAt.toEpochMillis()
            dao.upsertReceipts(
                page.receipts.map { receipt ->
                    CentralReceivingReceiptEntity(scope.userId, scope.namespaceId, receipt.id, receipt.status, json.encodeToString(receipt), generation)
                },
            )
            dao.upsertLabels(
                page.labels.map { label ->
                    val boxPlanId = label.boxPlanId.takeIf { it > 0 } ?: label.id
                    CentralReceivingLabelEntity(
                        userId = scope.userId,
                        namespaceId = scope.namespaceId,
                        boxPlanId = boxPlanId,
                        centralReceiptId = label.centralReceiptId,
                        centralReceiptItemId = label.centralReceiptItemId,
                        labelCode = label.labelCode,
                        boxUid = label.boxUid,
                        boxNo = label.boxNo,
                        remoteStatus = label.status,
                        signStatus = label.signStatus,
                        payload = json.encodeToString(label),
                        generation = generation,
                    )
                },
            )
            cursor = page.nextCursor
            hasMore = page.hasMore
        } while (hasMore)
        dao.activateSnapshot(
            CentralReceivingSnapshotMetaEntity(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                activeGeneration = generation,
                snapshotAt = snapshotAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                complete = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private suspend fun localReceipts(scope: CentralReceivingScope): List<CentralReceipt> {
        val labels = dao.labels(scope.userId, scope.namespaceId)
        val byReceiptAndItem = labels.filter(CentralReceivingLabelEntity::effectiveReceived)
            .groupingBy { it.centralReceiptId to it.centralReceiptItemId }
            .eachCount()
        return dao.receipts(scope.userId, scope.namespaceId).mapNotNull { entity ->
            runCatching { json.decodeFromString<CentralReceipt>(entity.payload) }.getOrNull()?.let { receipt ->
                val items = receipt.items.map { item ->
                    val explicit = byReceiptAndItem[receipt.id to item.id] ?: 0
                    val received = maxOf(item.receivedBoxCount, explicit)
                    item.copy(
                        receivedBoxCount = received,
                        pendingBoxCount = (item.expectedBoxCount - received).coerceAtLeast(0),
                    )
                }
                val completed = items.isNotEmpty() && items.all { it.pendingBoxCount == 0 }
                receipt.copy(status = if (completed) "completed" else receipt.status, items = items)
            }
        }
    }

    private suspend fun findLabel(scope: CentralReceivingScope, parsed: BoxLabelQrPayload): CentralReceivingLabelEntity? {
        val candidates = buildList {
            add(parsed.labelCode)
            add(parsed.originalRawCode)
        }.map(String::trim).filter(String::isNotBlank).distinct()
        return candidates.firstNotNullOfOrNull { dao.findLabel(scope.userId, scope.namespaceId, it) }
    }

    private fun toDomainLabel(entity: CentralReceivingLabelEntity): CentralReceiptBoxLabel {
        val remote = runCatching { json.decodeFromString<CentralSnapshotLabel>(entity.payload) }.getOrNull()
        return CentralReceiptBoxLabel(
            id = entity.boxPlanId,
            centralReceiptItemId = entity.centralReceiptItemId,
            boxUid = entity.boxUid,
            labelCode = entity.labelCode,
            boxNo = entity.boxNo,
            skuCode = remote?.skuCode.orEmpty(),
            caseSpecName = remote?.caseSpecName.orEmpty(),
            qtyPerCarton = remote?.qtyPerCarton ?: 0,
            actualBoxQty = remote?.actualBoxQty ?: 0,
            status = if (entity.localStatus.isNotBlank()) entity.localStatus else entity.remoteStatus,
            signStatus = if (entity.effectiveReceived()) "signed" else entity.signStatus,
            qrCodeText = remote?.qrCodeText.orEmpty(),
            centralReceivedAt = remote?.centralReceivedAt.orEmpty(),
        )
    }

    private suspend fun scope(): CentralReceivingScope {
        val session = sessionStore.current()
        return CentralReceivingScope(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
    }
}

private data class CentralReceivingScope(val userId: Long, val namespaceId: Long)

data class CentralReceivingSyncSummary(
    val snapshotAt: Long = 0,
    val pendingCount: Int = 0,
    val problemCount: Int = 0,
    val lastError: String = "",
)

internal fun CentralReceivingLabelEntity.effectiveReceived(): Boolean =
    localStatus in setOf("received_local", "duplicate_local") || signStatus == "signed" || remoteStatus in setOf("central_received", "received")

internal fun centralReceiptStatistics(
    receipts: List<CentralReceipt>,
    labels: List<CentralReceivingLabelEntity>,
): CentralReceiptStatistics {
    val received = labels.count { it.effectiveReceived() }
    return CentralReceiptStatistics(
        receiptCount = receipts.size,
        skuCount = receipts.sumOf { it.items.size },
        totalBoxCount = labels.size,
        receivedBoxCount = received,
        pendingBoxCount = (labels.size - received).coerceAtLeast(0),
        exceptionBoxCount = labels.count {
            it.signStatus in setOf("exception", "shortage_completed") && !it.effectiveReceived()
        },
    )
}

internal fun parseCentralReceivingScanCode(rawCode: String): BoxLabelQrPayload =
    runCatching { parseBoxLabelQr(rawCode) }.getOrElse {
        val normalized = rawCode.trim()
        BoxLabelQrPayload(normalized, normalized, format = BoxLabelQrFormat.ShortCode)
    }

private fun Long?.orZero(): Long = this ?: 0

private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0)

private fun centralLocalScanMessage(status: String): String = when (status) {
    CentralReceivingScanStatus.Pending.value -> "待联网同步"
    CentralReceivingScanStatus.Uploading.value -> "正在同步"
    CentralReceivingScanStatus.Received.value -> "云端收货成功"
    CentralReceivingScanStatus.Duplicate.value -> "云端已确认收货"
    CentralReceivingScanStatus.Retryable.value -> "同步失败，将自动重试"
    CentralReceivingScanStatus.Rejected.value -> "云端校验未通过"
    else -> status
}

@Singleton
class CentralReceivingSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<CentralReceivingSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueSoon() {
        val request = OneTimeWorkRequestBuilder<CentralReceivingSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val PERIODIC_WORK_NAME = "fscm-central-receiving-periodic-sync"
        const val IMMEDIATE_WORK_NAME = "fscm-central-receiving-immediate-sync"
    }
}

@HiltWorker
class CentralReceivingSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CentralReceivingRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (runCatching { repository.syncAll() }.getOrDefault(false)) Result.success() else Result.retry()
}
