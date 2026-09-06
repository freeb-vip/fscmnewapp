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

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "local_inbound_sessions", primaryKeys = ["userId", "namespaceId", "sessionId"])
data class LocalInboundSessionEntity(
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val sessionCode: String,
    val consolidationOrderCode: String = "",
    val consolidationOrderName: String = "",
    val warehouseCode: String = "",
    val warehouseName: String = "",
    val hasVirtualInbound: Boolean = false,
    val status: String = InboundSessionStatus.Active.value,
    val snapshotComplete: Boolean = false,
    val snapshotAt: Long = 0,
    val totalCount: Int = 0,
    val inboundedCount: Int = 0,
    val exceptionCount: Int = -1,
    val unassignedCount: Int = 0,
    val lastSyncAttemptAt: Long = 0,
    val lastSyncSuccessAt: Long = 0,
    val lastSyncError: String = "",
    val productCacheComplete: Boolean = false,
    val productCacheAt: Long = 0,
    val lastOpenedAt: Long = 0,
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
    val warehouseLocationId: Long = 0,
    val canScanRebind: Boolean = false,
    val receiptScanStatus: String = "",
    val exceptionReason: String = "",
    val remoteScannedBy: String = "",
    val remoteScannedAt: Long = 0,
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
    val warehouseLocationId: Long = 0,
    val locationName: String = "",
    val zoneName: String = "",
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
    val localWarehouseLocationId: Long = 0,
    val remoteLocationCode: String = "",
    val remoteWarehouseLocationId: Long = 0,
    val status: String = InboundItemStatus.Draft.value,
    val errorCode: Int = 0,
    val errorMessage: String = "",
    val correctionRequestId: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_inbound_product_summaries",
    primaryKeys = ["userId", "namespaceId", "sessionId", "productId"],
    indices = [Index(value = ["userId", "namespaceId", "sessionId", "pendingBoxCount"])],
)
data class LocalInboundProductSummaryEntity(
    val userId: Long = 0,
    val namespaceId: Long,
    val sessionId: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val productImage: String,
    val skuCount: Int,
    val skuCodesJson: String = "[]",
    val boxPlanIdsJson: String = "[]",
    val totalBoxCount: Int,
    val inboundedBoxCount: Int,
    val pendingBoxCount: Int,
    val exceptionBoxCount: Int,
    val snapshotAt: Long,
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
    @Query("DELETE FROM local_inbound_product_summaries WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId NOT IN (SELECT DISTINCT sessionId FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending','syncing','conflict_pending','rejected'))")
    suspend fun purgeProductCaches(userId: Long, namespaceId: Long)

    @Query("DELETE FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId NOT IN (SELECT DISTINCT sessionId FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending','syncing','conflict_pending','rejected'))")
    suspend fun purgeSnapshots(userId: Long, namespaceId: Long)

    @Query("DELETE FROM local_inbound_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId NOT IN (SELECT DISTINCT sessionId FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending','syncing','conflict_pending','rejected'))")
    suspend fun purgeBatches(userId: Long, namespaceId: Long)

    @Query("DELETE FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId NOT IN (SELECT DISTINCT sessionId FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending','syncing','conflict_pending','rejected'))")
    suspend fun purgeSessions(userId: Long, namespaceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: LocalInboundSessionEntity)

    @Query("SELECT * FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId ORDER BY updatedAt DESC")
    suspend fun sessions(userId: Long, namespaceId: Long): List<LocalInboundSessionEntity>

    @Query("SELECT * FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId LIMIT 1")
    suspend fun session(userId: Long, namespaceId: Long, sessionId: Long): LocalInboundSessionEntity?

    @Query("SELECT * FROM local_inbound_sessions WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId LIMIT 1")
    fun observeSession(userId: Long, namespaceId: Long, sessionId: Long): Flow<LocalInboundSessionEntity?>

    @Query(
        "UPDATE local_inbound_sessions SET lastOpenedAt = :openedAt, updatedAt = :openedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId",
    )
    suspend fun touchSession(userId: Long, namespaceId: Long, sessionId: Long, openedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun deleteSnapshots(userId: Long, namespaceId: Long, sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshots(rows: List<LocalInboundSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(row: LocalInboundSnapshotEntity)

    @Query(
        "SELECT * FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY boxNo, labelCode",
    )
    suspend fun snapshots(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundSnapshotEntity>

    @Query(
        "SELECT * FROM local_inbound_snapshots WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY boxNo, labelCode",
    )
    fun observeSnapshots(userId: Long, namespaceId: Long, sessionId: Long): Flow<List<LocalInboundSnapshotEntity>>

    @Query(
        "UPDATE local_inbound_snapshots SET locationCode = :locationCode, warehouseLocationId = :warehouseLocationId, snapshotAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND boxPlanId = :boxPlanId",
    )
    suspend fun updateSnapshotLocation(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        boxPlanId: Long,
        locationCode: String,
        warehouseLocationId: Long,
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

    @Query(
        "SELECT * FROM local_inbound_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'draft' ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun draftBatch(userId: Long, namespaceId: Long, sessionId: Long): LocalInboundBatchEntity?

    @Query("SELECT * FROM local_inbound_batches WHERE id = :batchId LIMIT 1")
    suspend fun batch(batchId: String): LocalInboundBatchEntity?

    @Query(
        "UPDATE local_inbound_batches SET warehouseLocationId = :warehouseLocationId, locationCode = :locationCode, locationName = :locationName, zoneName = :zoneName, updatedAt = :updatedAt WHERE id = :batchId",
    )
    suspend fun updateBatchLocation(
        batchId: String,
        warehouseLocationId: Long,
        locationCode: String,
        locationName: String,
        zoneName: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Transaction
    suspend fun changeDraftLocation(
        batchId: String,
        userId: Long,
        namespaceId: Long,
        location: LocalWarehouseLocationEntity,
    ) {
        val draft = batch(batchId)
            ?.takeIf { it.userId == userId && it.namespaceId == namespaceId && it.status == InboundBatchStatus.Draft.value }
            ?: throw InboundClientException(InboundClientError.BatchNotFound)
        if (itemsForBatch(draft.id).any { it.status != InboundItemStatus.Draft.value }) {
            throw InboundClientException(InboundClientError.BatchNotFound)
        }
        updateBatchLocation(draft.id, location.locationId, location.code, location.name, location.zoneName)
        updateBatchItemsLocation(draft.id, location.locationId, location.code)
    }

    @Query(
        "SELECT * FROM local_inbound_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY createdAt DESC",
    )
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

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY scannedAt DESC",
    )
    suspend fun itemsForSession(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND labelCode = :labelCode LIMIT 1")
    suspend fun itemForLabel(userId: Long, namespaceId: Long, labelCode: String): LocalInboundItemEntity?

    @Query(
        "DELETE FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND labelCode = :labelCode AND status IN ('synced', 'remote_accepted', 'remote_overridden')",
    )
    suspend fun deleteTerminalItemForLabel(userId: Long, namespaceId: Long, labelCode: String)

    @Query("SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND requestId = :requestId LIMIT 1")
    suspend fun item(userId: Long, namespaceId: Long, requestId: String): LocalInboundItemEntity?

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND status IN ('pending', 'syncing') ORDER BY scannedAt ASC",
    )
    suspend fun pendingItems(userId: Long, namespaceId: Long): List<LocalInboundItemEntity>

    @Query(
        "UPDATE local_inbound_items SET status = 'pending', errorCode = 0, errorMessage = '', updatedAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'rejected'",
    )
    suspend fun retryRejected(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status = 'conflict_pending' ORDER BY scannedAt ASC",
    )
    suspend fun conflicts(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status IN ('conflict_pending', 'rejected') ORDER BY scannedAt DESC",
    )
    suspend fun syncIssues(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status IN ('pending', 'syncing', 'conflict_pending', 'rejected') ORDER BY scannedAt DESC",
    )
    suspend fun localSyncItems(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status IN ('pending', 'syncing', 'conflict_pending', 'rejected') ORDER BY scannedAt DESC",
    )
    fun observeLocalSyncItems(userId: Long, namespaceId: Long, sessionId: Long): Flow<List<LocalInboundItemEntity>>

    @Query(
        "SELECT * FROM local_inbound_items WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND status IN ('pending', 'syncing', 'conflict_pending', 'rejected') ORDER BY scannedAt ASC",
    )
    suspend fun reconcilableItems(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundItemEntity>

    @Query(
        "UPDATE local_inbound_items SET status = :status, remoteLocationCode = :remoteLocationCode, remoteWarehouseLocationId = :remoteWarehouseLocationId, boxPlanId = :boxPlanId, errorCode = :errorCode, errorMessage = :errorMessage, syncedAt = :syncedAt, updatedAt = :updatedAt WHERE requestId = :requestId",
    )
    suspend fun updateItemResult(
        requestId: String,
        status: String,
        remoteLocationCode: String,
        remoteWarehouseLocationId: Long,
        boxPlanId: Long,
        errorCode: Int,
        errorMessage: String,
        syncedAt: Long,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE local_inbound_items SET localWarehouseLocationId = :warehouseLocationId, localLocationCode = :locationCode, updatedAt = :updatedAt WHERE requestId = :requestId",
    )
    suspend fun updateItemLocalLocation(
        requestId: String,
        warehouseLocationId: Long,
        locationCode: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE local_inbound_items SET localWarehouseLocationId = :warehouseLocationId, localLocationCode = :locationCode, updatedAt = :updatedAt WHERE batchId = :batchId",
    )
    suspend fun updateBatchItemsLocation(
        batchId: String,
        warehouseLocationId: Long,
        locationCode: String,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductSummaries(rows: List<LocalInboundProductSummaryEntity>)

    @Query(
        "DELETE FROM local_inbound_product_summaries WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId",
    )
    suspend fun deleteProductSummaries(userId: Long, namespaceId: Long, sessionId: Long)

    @Query(
        "SELECT * FROM local_inbound_product_summaries WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY pendingBoxCount DESC, productCode ASC",
    )
    suspend fun productSummaries(userId: Long, namespaceId: Long, sessionId: Long): List<LocalInboundProductSummaryEntity>

    @Query(
        "SELECT * FROM local_inbound_product_summaries WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY pendingBoxCount DESC, productCode ASC",
    )
    fun observeProductSummaries(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
    ): Flow<List<LocalInboundProductSummaryEntity>>

    @Query(
        "UPDATE local_inbound_sessions SET productCacheComplete = 1, productCacheAt = :snapshotAt, updatedAt = :snapshotAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId",
    )
    suspend fun markProductCacheComplete(userId: Long, namespaceId: Long, sessionId: Long, snapshotAt: Long)

    @Transaction
    suspend fun replaceProductSummaries(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        rows: List<LocalInboundProductSummaryEntity>,
        snapshotAt: Long,
    ) {
        deleteProductSummaries(userId, namespaceId, sessionId)
        if (rows.isNotEmpty()) insertProductSummaries(rows)
        markProductCacheComplete(userId, namespaceId, sessionId, snapshotAt)
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
    @SerialName("warehouse_location_id") val warehouseLocationId: Long = 0,
    @SerialName("can_scan_rebind") val canScanRebind: Boolean = false,
    @SerialName("virtual_recovery_required") val virtualRecoveryRequired: Boolean = false,
    val status: String = "",
    @SerialName("inbound_status") val inboundStatus: String = "",
    @SerialName("receipt_scan_status") val receiptScanStatus: String = "",
    @SerialName("exception_reason") val exceptionReason: String = "",
    @SerialName("scanned_by") val scannedBy: String = "",
    @SerialName("scanned_at") val scannedAt: String = "",
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
private data class ReceivingInboundProductSummary(
    @SerialName("product_id") val productId: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("product_image") val productImage: String = "",
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("sku_codes") val skuCodes: List<String> = emptyList(),
    @SerialName("box_plan_ids") val boxPlanIds: List<Long> = emptyList(),
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("inbounded_box_count") val inboundedBoxCount: Int = 0,
    @SerialName("pending_box_count") val pendingBoxCount: Int = 0,
    @SerialName("exception_box_count") val exceptionBoxCount: Int = 0,
)

@Serializable
private data class ReceivingInboundProductPage(
    val items: List<ReceivingInboundProductSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 200,
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

@Serializable
private data class ReceivingInboundBatchItemResult(
    @SerialName("request_id") val requestId: String = "",
    val status: String = "",
    val code: Int = 0,
    val message: String = "",
    val item: ReceivingInboundItem = ReceivingInboundItem(),
)

@Serializable
private data class ReceivingInboundBatchResponse(
    val items: List<ReceivingInboundBatchItemResult> = emptyList(),
    val summary: ReceivingInboundSummary = ReceivingInboundSummary(),
)

data class InboundSessionSummary(
    val sessionId: Long,
    val sessionCode: String,
    val consolidationOrderCode: String = "",
    val consolidationOrderName: String = "",
    val warehouseCode: String,
    val warehouseName: String,
    val remoteStatus: String,
    val totalCount: Int,
    val inboundedCount: Int,
    val localAssignedCount: Int,
    val remainingCount: Int,
    val centerPendingCount: Int,
    val pendingCount: Int,
    val conflictCount: Int,
    val rejectedCount: Int,
    val exceptionCount: Int,
    val hasVirtualInbound: Boolean = false,
    val rebindableCount: Int = 0,
    val lastSyncAt: Long,
    val snapshotComplete: Boolean,
    val locationCacheComplete: Boolean,
    val locationCacheRefreshedAt: Long,
    val productCacheComplete: Boolean,
    val productCacheAt: Long,
    val status: String,
)

data class InboundWorkspace(
    val summary: InboundSessionSummary,
    val draftBatch: LocalInboundBatchEntity?,
    val draftItems: List<LocalInboundItemEntity>,
    val draftBoxDetails: List<InboundDraftBoxDetail> = emptyList(),
    val conflicts: List<LocalInboundItemEntity>,
    val centerExceptions: List<LocalInboundSnapshotEntity> = emptyList(),
    val syncIssues: List<LocalInboundItemEntity> = emptyList(),
    val localSyncItems: List<LocalInboundItemEntity> = emptyList(),
    val pendingProducts: List<LocalInboundProductSummaryEntity> = emptyList(),
    val locationCache: WarehouseLocationCacheState = WarehouseLocationCacheState(),
    val snapshotItems: List<LocalInboundSnapshotEntity> = emptyList(),
)

internal fun LocalInboundSnapshotEntity.isPendingPhysicalInbound(): Boolean =
    eligible && locationCode.isBlankOrVirtualInboundLocation()

internal fun LocalInboundSnapshotEntity.isCompletedPhysicalInbound(): Boolean =
    eligible && !locationCode.isBlankOrVirtualInboundLocation()

private fun normalizeRemoteInboundStatus(value: String): String =
    when (value.trim().lowercase()) {
        "completed", "complete", "inbound_completed", "inbounded" -> InboundSessionStatus.Complete.value
        "cancelled", "canceled" -> "cancelled"
        "failed", "error" -> InboundSessionStatus.Failed.value
        "" -> ""
        else -> InboundSessionStatus.Active.value
    }

@Serializable
private data class InboundCompletionPreview(
    @SerialName("snapshot_hash") val snapshotHash: String = "",
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean = false,
    @SerialName("can_auto_complete") val canAutoComplete: Boolean = false,
    val metrics: JsonObject = buildJsonObject {},
)

internal fun inboundCompletionRequest(snapshotHash: String, requestId: String, confirmExceptions: Boolean): JsonObject =
    buildJsonObject {
        put("request_id", requestId)
        put("snapshot_hash", snapshotHash)
        put("reason", "移动端扫描入库完成")
        put("confirm_exceptions", confirmExceptions)
    }

data class InboundProductCache(
    val items: List<LocalInboundProductSummaryEntity>,
    val complete: Boolean,
    val updatedAt: Long,
    val session: LocalInboundSessionEntity? = null,
    val localSyncItems: List<LocalInboundItemEntity> = emptyList(),
    val snapshotItems: List<LocalInboundSnapshotEntity> = emptyList(),
)

data class InboundSkuQuantity(
    val skuCode: String,
    val quantity: Int?,
)

data class InboundDraftBoxDetail(
    val item: LocalInboundItemEntity,
    val skuItems: List<InboundSkuQuantity>,
)

data class InboundDraftMetrics(
    val boxCount: Int,
    val skuCount: Int,
    val totalQuantity: Int,
    val quantityComplete: Boolean,
)

data class InboundProgress(
    val localContribution: Int,
    val remaining: Int,
    val pending: Int,
    val conflicts: Int,
    val rejected: Int,
)

internal data class InboundItemReconciliation(
    val status: String,
    val remoteLocationCode: String,
    val remoteWarehouseLocationId: Long,
    val boxPlanId: Long,
    val errorCode: Int,
    val errorMessage: String,
)

internal fun reconcileInboundItem(
    item: LocalInboundItemEntity,
    remote: LocalInboundSnapshotEntity?,
): InboundItemReconciliation? {
    if (
        item.status !in
        setOf(
            InboundItemStatus.Pending.value,
            InboundItemStatus.Syncing.value,
            InboundItemStatus.ConflictPending.value,
            InboundItemStatus.Rejected.value,
        ) ||
        remote == null
    ) {
        return null
    }
    val remoteLocation = remote.locationCode.trim()
    return when {
        !remote.eligible ->
            InboundItemReconciliation(
                status = InboundItemStatus.Rejected.value,
                remoteLocationCode = remoteLocation,
                remoteWarehouseLocationId = remote.warehouseLocationId,
                boxPlanId = remote.boxPlanId,
                errorCode = item.errorCode,
                errorMessage = remote.exceptionReason.ifBlank { remote.status },
            )
        remoteLocation.equals(item.localLocationCode, ignoreCase = true) ->
            InboundItemReconciliation(
                status = InboundItemStatus.Synced.value,
                remoteLocationCode = remoteLocation,
                remoteWarehouseLocationId = remote.warehouseLocationId,
                boxPlanId = remote.boxPlanId,
                errorCode = 0,
                errorMessage = "",
            )
        remoteLocation.isBlankOrVirtualInboundLocation() || remote.canScanRebind ->
            InboundItemReconciliation(
                status = InboundItemStatus.Pending.value,
                remoteLocationCode = remoteLocation,
                remoteWarehouseLocationId = remote.warehouseLocationId,
                boxPlanId = remote.boxPlanId,
                errorCode = 0,
                errorMessage = "",
            )
        else ->
            InboundItemReconciliation(
                status = InboundItemStatus.ConflictPending.value,
                remoteLocationCode = remoteLocation,
                remoteWarehouseLocationId = remote.warehouseLocationId,
                boxPlanId = remote.boxPlanId,
                errorCode = INBOUND_LOCATION_CONFLICT_CODE,
                errorMessage = item.errorMessage,
            )
    }
}

internal fun calculateInboundProgress(
    snapshots: List<LocalInboundSnapshotEntity>,
    items: List<LocalInboundItemEntity>,
): InboundProgress {
    val assignedLocal = items.filterNot { it.status == InboundItemStatus.Rejected.value }
    val assignedLabels = assignedLocal.mapTo(hashSetOf()) { it.labelCode }
    return InboundProgress(
        localContribution =
        assignedLocal.count { item ->
            snapshots.firstOrNull { it.labelCode == item.labelCode }?.locationCode.isNullOrVirtualInboundLocation()
        },
        remaining = snapshots.count {
            it.eligible && it.locationCode.isBlankOrVirtualInboundLocation() && it.labelCode !in assignedLabels
        },
        pending =
        items.count {
            it.status in
                setOf(
                    InboundItemStatus.Draft.value,
                    InboundItemStatus.Pending.value,
                    InboundItemStatus.Syncing.value,
                )
        },
        conflicts = items.count { it.status == InboundItemStatus.ConflictPending.value },
        rejected = items.count { it.status == InboundItemStatus.Rejected.value },
    )
}

data class InboundRebindConfirmation(
    val batchId: String,
    val labelCode: String,
    val sourceLocationCode: String,
    val sourceLocationId: Long,
    val targetLocationCode: String,
    val targetLocationId: Long,
)

class InboundRebindConfirmationRequired(val confirmation: InboundRebindConfirmation) : Exception()

internal fun requireInboundRebindConfirmation(
    batch: LocalInboundBatchEntity,
    snapshot: LocalInboundSnapshotEntity,
    confirmed: InboundRebindConfirmation?,
    existing: LocalInboundItemEntity? = null,
) {
    // A successful local sync can be newer than the offline snapshot.
    val newerLocal = existing?.takeIf { !it.blocksVirtualRebind() && it.updatedAt > snapshot.snapshotAt }
    val sourceCode = newerLocal?.let { it.remoteLocationCode.ifBlank { it.localLocationCode } } ?: snapshot.locationCode
    val sourceId = newerLocal?.let { it.remoteWarehouseLocationId.takeIf { id -> id > 0 } ?: it.localWarehouseLocationId }
        ?: snapshot.warehouseLocationId
    if (sourceCode.isBlankOrVirtualInboundLocation()) return
    val required = InboundRebindConfirmation(
        batchId = batch.id,
        labelCode = snapshot.labelCode,
        sourceLocationCode = sourceCode,
        sourceLocationId = sourceId,
        targetLocationCode = batch.locationCode,
        targetLocationId = batch.warehouseLocationId,
    )
    if (confirmed != required) throw InboundRebindConfirmationRequired(required)
}

internal fun LocalInboundSnapshotEntity.canScanIntoInboundLocation(): Boolean =
    eligible && (locationCode.isBlankOrVirtualInboundLocation() || canScanRebind)

internal fun LocalInboundItemEntity.blocksVirtualRebind(): Boolean =
    status !in TERMINAL_INBOUND_ITEM_STATUSES

internal fun InboundSessionSummary.canStartInboundBatch(): Boolean =
    remainingCount > 0 || rebindableCount > 0

internal fun resolveInboundSkuQuantities(
    rawCode: String,
    fallbackSkuCode: String = "",
    fallbackQuantity: Int = 0,
): List<InboundSkuQuantity> {
    val parsed = runCatching { parseBoxLabelQr(rawCode) }.getOrNull()
    if (!parsed?.items.isNullOrEmpty()) {
        return parsed.items.map { item -> InboundSkuQuantity(item.skuCode, item.quantity) }
    }
    return fallbackSkuCode.trim().takeIf(String::isNotBlank)?.let { skuCode ->
        listOf(InboundSkuQuantity(skuCode, fallbackQuantity.takeIf { it > 0 }))
    }.orEmpty()
}

internal fun calculateInboundDraftMetrics(details: List<InboundDraftBoxDetail>): InboundDraftMetrics {
    val skuItems = details.flatMap(InboundDraftBoxDetail::skuItems)
    return InboundDraftMetrics(
        boxCount = details.size,
        skuCount = skuItems.map { it.skuCode.trim().uppercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().size,
        totalQuantity = skuItems.sumOf { it.quantity ?: 0 },
        quantityComplete =
        details.isNotEmpty() &&
            details.all { detail ->
                detail.skuItems.isNotEmpty() && detail.skuItems.all { it.quantity != null }
            },
    )
}

internal fun sortInboundDraftBoxDetails(details: List<InboundDraftBoxDetail>): List<InboundDraftBoxDetail> =
    details.sortedByDescending { it.item.scannedAt }

internal fun mergeInboundSessionIds(
    remote: List<ReceivingSession>,
    local: List<LocalInboundSessionEntity>,
): Set<Long> = remote.mapTo(linkedSetOf()) { it.id }.apply { addAll(local.map { it.sessionId }) }

class InboundClientException(val reason: InboundClientError) : IllegalStateException()

enum class InboundClientError {
    SnapshotUnavailable,
    InvalidLocation,
    LocationCacheUnavailable,
    LocationNotFound,
    LocationInactive,
    LocationWarehouseMismatch,
    TargetWarehouseMissing,
    SessionComplete,
    BatchNotFound,
    EmptyBatch,
    InvalidBoxLabel,
    MappingCacheMiss,
    BoxNotInSession,
    BoxNotEligible,
    BoxAlreadyAssigned,
    BoxAlreadyScanned,
    ConflictNotFound,
    OfflineSyncUnavailable,
}

fun isValidInboundLocation(value: String): Boolean {
    val normalized = value.trimScannerSuffix()
    return normalized.isNotBlank() && normalized.length <= 100 && normalized.none(Char::isISOControl)
}

fun normalizeInboundLocation(value: String): String {
    val normalized = value.trimScannerSuffix().uppercase(Locale.ROOT)
    if (!isValidInboundLocation(normalized)) throw InboundClientException(InboundClientError.InvalidLocation)
    return normalized
}

@Singleton
class LocalInboundRepository
@Inject
constructor(
    private val api: FscmApiClient,
    private val receivingRepository: ReceivingRepository,
    private val sessionStore: SessionStore,
    private val dao: LocalInboundDao,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
    private val warehouseLocationRepository: WarehouseLocationRepository,
    private val syncScheduler: LocalInboundSyncScheduler,
) {
    private val syncMutex = Mutex()
    private val draftMutex = Mutex()

    @Volatile private var batchEndpointSupported: Boolean? = null

    suspend fun sessionSummaries(refreshRemote: Boolean = true): List<InboundSessionSummary> {
        val scope = scope()
        val localRows = dao.sessions(scope.userId, scope.namespaceId)
        val remote =
            if (!refreshRemote || sessionStore.current().isOffline) {
                emptyList()
            } else {
                runCatching { receivingRepository.sessions() }.getOrElse { error ->
                    if (localRows.isEmpty()) throw error else emptyList()
                }
            }
        val local = localRows.associateBy { it.sessionId }
        val remoteById = remote.associateBy { it.id }
        val cacheState = warehouseLocationRepository.cacheState()
        val orderedIds = mergeInboundSessionIds(remote, localRows)
        return orderedIds.map { sessionId ->
            val session = remoteById[sessionId]
            val localSession = local[sessionId]
            buildSummary(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                sessionCode = session?.code.orEmpty().ifBlank { localSession?.sessionCode.orEmpty() },
                consolidationOrderCode = session?.consolidationOrder?.code.orEmpty(),
                consolidationOrderName = session?.consolidationOrder?.name.orEmpty(),
                warehouseCode = session?.warehouseCode.orEmpty().ifBlank { localSession?.warehouseCode.orEmpty() },
                warehouseName = session?.warehouseName.orEmpty().ifBlank { localSession?.warehouseName.orEmpty() },
                hasVirtualInbound = session?.hasVirtualInbound == true,
                remoteStatus = session?.inboundStatus.orEmpty().ifBlank { session?.status.orEmpty() },
                localSession = localSession,
                fallbackTotal = session?.expectedBoxCount ?: 0,
                locationCache = cacheState,
            )
        }
    }

    suspend fun prepareOfflineData(): List<InboundSessionSummary> {
        if (sessionStore.current().isOffline) return sessionSummaries()
        syncScheduler.enqueueCacheRefresh()
        return sessionSummaries(refreshRemote = true)
    }

    suspend fun prepareSession(sessionId: Long): InboundWorkspace {
        val scope = scope()
        val localSession = dao.session(scope.userId, scope.namespaceId, sessionId)
        val locationCache = warehouseLocationRepository.cacheState()
        if (
            localSession?.snapshotComplete == true &&
            locationCache.complete &&
            localSession.warehouseCode.isNotBlank()
        ) {
            dao.touchSession(scope.userId, scope.namespaceId, sessionId)
            if (!sessionStore.current().isOffline) syncScheduler.enqueueCacheRefresh(sessionId)
            return workspace(sessionId)
        }
        if (sessionStore.current().isOffline) {
            throw InboundClientException(InboundClientError.SnapshotUnavailable)
        }
        warehouseLocationRepository.refreshIfStale()
        val workspace = refreshSessionSnapshot(sessionId)
        dao.touchSession(scope.userId, scope.namespaceId, sessionId)
        syncScheduler.enqueueCacheRefresh(sessionId)
        return workspace
    }

    suspend fun refreshSessionIfStale(sessionId: Long): InboundWorkspace? {
        val scope = scope()
        val localSession =
            dao.session(scope.userId, scope.namespaceId, sessionId)
                ?: return if (sessionStore.current().isOffline) null else refreshSessionSnapshot(sessionId)
        if (
            (!localSession.isSnapshotStale() && localSession.warehouseCode.isNotBlank()) ||
            sessionStore.current().isOffline
        ) {
            return null
        }
        return refreshSessionSnapshot(sessionId, localSession.sessionCode)
    }

    suspend fun workspace(sessionId: Long, remoteStatus: String = ""): InboundWorkspace {
        val scope = scope()
        val localSession =
            dao.session(scope.userId, scope.namespaceId, sessionId)
                ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        recomputeSession(scope.userId, scope.namespaceId, sessionId, remoteStatus)
        val refreshed = dao.session(scope.userId, scope.namespaceId, sessionId) ?: localSession
        val draft = dao.draftBatch(scope.userId, scope.namespaceId, sessionId)
        val draftItems = draft?.let { dao.itemsForBatch(it.id) }.orEmpty()
        return InboundWorkspace(
            summary =
            buildSummary(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                sessionCode = refreshed.sessionCode,
                remoteStatus = remoteStatus,
                localSession = refreshed,
                locationCache = warehouseLocationRepository.cacheState(),
            ),
            draftBatch = draft,
            draftItems = draftItems,
            draftBoxDetails =
            sortInboundDraftBoxDetails(
                draftItems.map { item ->
                    val mapping = boxLabelMappingRepository.cachedMapping(item.rawCode)
                    InboundDraftBoxDetail(
                        item = item,
                        skuItems =
                        resolveInboundSkuQuantities(
                            rawCode = item.rawCode,
                            fallbackSkuCode = mapping?.skuCode.orEmpty(),
                            fallbackQuantity = mapping?.qtyPerCarton ?: 0,
                        ),
                    )
                },
            ),
            conflicts = dao.conflicts(scope.userId, scope.namespaceId, sessionId),
            centerExceptions =
            dao.snapshots(scope.userId, scope.namespaceId, sessionId)
                .filterNot(LocalInboundSnapshotEntity::eligible),
            syncIssues = dao.syncIssues(scope.userId, scope.namespaceId, sessionId),
            localSyncItems = dao.localSyncItems(scope.userId, scope.namespaceId, sessionId),
            pendingProducts = pendingInboundProducts(dao.productSummaries(scope.userId, scope.namespaceId, sessionId)),
            locationCache = warehouseLocationRepository.cacheState(),
            snapshotItems = dao.snapshots(scope.userId, scope.namespaceId, sessionId),
        )
    }

    suspend fun observeProductCache(sessionId: Long): Flow<InboundProductCache> {
        val scope = scope()
        return combine(
            dao.observeSession(scope.userId, scope.namespaceId, sessionId),
            dao.observeProductSummaries(scope.userId, scope.namespaceId, sessionId),
            dao.observeLocalSyncItems(scope.userId, scope.namespaceId, sessionId),
            dao.observeSnapshots(scope.userId, scope.namespaceId, sessionId),
        ) { session, products, localSyncItems, snapshots ->
            InboundProductCache(
                items = pendingInboundProducts(products),
                complete = session?.productCacheComplete == true,
                updatedAt = session?.productCacheAt ?: 0,
                session = session,
                snapshotItems = snapshots,
                localSyncItems = localSyncItems,
            )
        }
    }

    suspend fun startBatch(sessionId: Long, rawLocation: String): LocalInboundBatchEntity {
        val scope = scope()
        sessionStore.current().requireOfflineOperationPermission("receiving")
        dao.draftBatch(scope.userId, scope.namespaceId, sessionId)?.let { return it }
        val localSession =
            dao.session(scope.userId, scope.namespaceId, sessionId)
                ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        val summary =
            buildSummary(
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                sessionCode = localSession.sessionCode,
                remoteStatus = "",
                localSession = localSession,
                warehouseCode = localSession.warehouseCode,
                warehouseName = localSession.warehouseName,
                locationCache = warehouseLocationRepository.cacheState(),
            )
        if (!summary.canStartInboundBatch()) throw InboundClientException(InboundClientError.SessionComplete)
        val resolved = warehouseLocationRepository.resolve(rawLocation, localSession.warehouseCode).location
        val batch =
            LocalInboundBatchEntity(
                id = UUID.randomUUID().toString(),
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                sessionId = sessionId,
                locationCode = resolved.code,
                warehouseLocationId = resolved.locationId,
                locationName = resolved.name,
                zoneName = resolved.zoneName,
            )
        dao.insertBatch(batch)
        return batch
    }

    suspend fun resolveDraftLocation(batchId: String, rawLocation: String): LocalWarehouseLocationEntity {
        val scope = scope()
        sessionStore.current().requireOfflineOperationPermission("receiving")
        val batch = dao.batch(batchId)
            ?.takeIf { it.userId == scope.userId && it.namespaceId == scope.namespaceId && it.status == InboundBatchStatus.Draft.value }
            ?: throw InboundClientException(InboundClientError.BatchNotFound)
        val session = dao.session(scope.userId, scope.namespaceId, batch.sessionId)
            ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        return warehouseLocationRepository.resolve(rawLocation, session.warehouseCode).location
    }

    suspend fun changeDraftLocation(batchId: String, rawLocation: String): InboundWorkspace =
        draftMutex.withLock { changeDraftLocationLocked(batchId, rawLocation) }

    private suspend fun changeDraftLocationLocked(batchId: String, rawLocation: String): InboundWorkspace {
        val location = resolveDraftLocation(batchId, rawLocation)
        val scope = scope()
        dao.changeDraftLocation(batchId, scope.userId, scope.namespaceId, location)
        return workspace(dao.batch(batchId)!!.sessionId)
    }

    suspend fun addBox(
        batchId: String,
        rawCode: String,
        confirmedRebind: InboundRebindConfirmation? = null,
    ): LocalInboundItemEntity = draftMutex.withLock { addBoxLocked(batchId, rawCode, confirmedRebind) }

    private suspend fun addBoxLocked(
        batchId: String,
        rawCode: String,
        confirmedRebind: InboundRebindConfirmation?,
    ): LocalInboundItemEntity {
        val currentSession = sessionStore.current()
        currentSession.requireOfflineOperationPermission("receiving")
        val storedBatch =
            dao.batch(batchId)?.takeIf { it.status == InboundBatchStatus.Draft.value }
                ?: throw InboundClientException(InboundClientError.BatchNotFound)
        val batch = validateBatchLocation(storedBatch)
        val parsed = boxLabelMappingRepository.resolve(rawCode)
        if (currentSession.isOffline && parsed == null) {
            throw InboundClientException(InboundClientError.MappingCacheMiss)
        }
        val candidates =
            buildSet {
                parsed?.labelCode?.let(::add)
                parsed?.originalRawCode?.trimScannerSuffix()?.let(::add)
                add(rawCode.trimScannerSuffix())
            }
        val cachedSnapshot =
            dao.snapshots(batch.userId, batch.namespaceId, batch.sessionId).firstOrNull { row ->
                candidates.any { candidate ->
                    candidate.equals(row.labelCode, true) ||
                        candidate.equals(row.boxNo, true) ||
                        candidate.equals(row.boxUid, true)
                }
            }
        var snapshot = cachedSnapshot
        if (!currentSession.isOffline) {
            val preview =
                try {
                    receivingRepository.preview(batch.sessionId, rawCode)
                } catch (_: BoxLabelMappingCacheMissException) {
                    throw InboundClientException(InboundClientError.MappingCacheMiss)
                }
            val item = preview.resolvedData
            if (!item.localFallback && item.boxPlanId > 0 && item.labelCode.isNotBlank()) {
                val canScanRebind = preview.resolvedCanScanRebind || item.locationCode.isVirtualInboundLocation()
                snapshot = LocalInboundSnapshotEntity(
                    userId = batch.userId,
                    namespaceId = batch.namespaceId,
                    sessionId = batch.sessionId,
                    boxPlanId = item.boxPlanId,
                    labelCode = item.labelCode,
                    boxNo = item.boxNo,
                    boxUid = item.boxUid,
                    status = item.status.ifBlank { cachedSnapshot?.status.orEmpty() },
                    inboundStatus = cachedSnapshot?.inboundStatus.orEmpty(),
                    locationCode = item.locationCode,
                    warehouseLocationId = item.warehouseLocationId,
                    canScanRebind = canScanRebind,
                    receiptScanStatus = cachedSnapshot?.receiptScanStatus.orEmpty(),
                    exceptionReason = item.exceptionReason,
                    remoteScannedBy = item.scannedBy,
                    remoteScannedAt = item.scannedAt.toInboundEpochMillis(),
                    eligible = canScanRebind || (!preview.duplicate && item.status.equals("normal", true)),
                    snapshotAt = System.currentTimeMillis(),
                ).also { dao.insertSnapshot(it) }
            }
        }
        val resolvedSnapshot = snapshot ?: throw InboundClientException(InboundClientError.BoxNotInSession)
        if (!resolvedSnapshot.eligible) throw InboundClientException(InboundClientError.BoxNotEligible)
        if (!resolvedSnapshot.canScanIntoInboundLocation()) {
            throw InboundClientException(InboundClientError.BoxAlreadyAssigned)
        }
        val existing = dao.itemForLabel(batch.userId, batch.namespaceId, resolvedSnapshot.labelCode)
        existing?.let { previous ->
            if (!resolvedSnapshot.canScanRebind && !resolvedSnapshot.locationCode.isVirtualInboundLocation()) {
                throw InboundClientException(InboundClientError.BoxAlreadyScanned)
            }
            if (previous.blocksVirtualRebind()) {
                throw InboundClientException(InboundClientError.BoxAlreadyScanned)
            }
        }
        // Do not remove the previous operation or create a new draft before explicit confirmation.
        requireInboundRebindConfirmation(batch, resolvedSnapshot, confirmedRebind, existing)
        if (existing != null) {
            dao.deleteTerminalItemForLabel(batch.userId, batch.namespaceId, resolvedSnapshot.labelCode)
        }
        val item =
            LocalInboundItemEntity(
                requestId = UUID.randomUUID().toString(),
                batchId = batch.id,
                userId = batch.userId,
                namespaceId = batch.namespaceId,
                sessionId = batch.sessionId,
                boxPlanId = resolvedSnapshot.boxPlanId,
                labelCode = resolvedSnapshot.labelCode,
                rawCode = parsed?.originalRawCode ?: rawCode.trimScannerSuffix(),
                localLocationCode = batch.locationCode,
                localWarehouseLocationId = batch.warehouseLocationId,
            )
        dao.insertItem(item)
        return item
    }

    suspend fun deleteDraftBox(requestId: String) = draftMutex.withLock { dao.deleteDraftItem(requestId) }

    suspend fun cancelDraft(batchId: String) = draftMutex.withLock {
        dao.deleteDraftItems(batchId)
        dao.deleteDraftBatch(batchId)
    }

    suspend fun finishBatch(batchId: String): InboundWorkspace =
        draftMutex.withLock { finishBatchLocked(batchId) }

    private suspend fun finishBatchLocked(batchId: String): InboundWorkspace {
        sessionStore.current().requireOfflineOperationPermission("receiving")
        val batch = dao.batch(batchId) ?: throw InboundClientException(InboundClientError.BatchNotFound)
        if (batch.status != InboundBatchStatus.Draft.value) throw InboundClientException(InboundClientError.BatchNotFound)
        if (dao.batchItemCount(batchId) == 0) throw InboundClientException(InboundClientError.EmptyBatch)
        dao.finishBatch(batchId)
        syncScheduler.enqueueSoon()
        // When online, close the server-side inbound stage after all scans are synced.
        // Offline completion remains local and is retried by the next foreground sync.
        if (!sessionStore.current().isOffline) {
            completeInboundIfReady(batch.sessionId)
        }
        recomputeSession(batch.userId, batch.namespaceId, batch.sessionId, "")
        return workspace(batch.sessionId)
    }

    /** Rebuilds inbound caches from the center service while preserving unsynced work. */
    suspend fun resetInboundCacheFromCenter(): List<InboundSessionSummary> {
        if (sessionStore.current().isOffline) throw InboundClientException(InboundClientError.OfflineSyncUnavailable)
        val scope = scope()
        val remote = receivingRepository.sessions()
        dao.purgeProductCaches(scope.userId, scope.namespaceId)
        dao.purgeSnapshots(scope.userId, scope.namespaceId)
        dao.purgeBatches(scope.userId, scope.namespaceId)
        dao.purgeSessions(scope.userId, scope.namespaceId)
        remote.filter(ReceivingSession::isInboundCacheCandidate)
            .forEach { refreshSessionSnapshot(it.id, it.code, it) }
        return sessionSummaries(refreshRemote = true)
    }

    suspend fun completeInboundIfReady(sessionId: Long): Boolean {
        if (sessionStore.current().isOffline) return false
        // Inbound completion must never close an order while the receiving
        // stage is still in progress. The receiving stage is authoritative.
        val receivingSession = receivingRepository.session(sessionId, forceRefresh = true)
        if (receivingSession.status.trim().lowercase() != "completed") return false
        return runCatching { completeInboundStage(sessionId) }.isSuccess
    }

    private suspend fun completeInboundStage(sessionId: Long) {
        syncPendingItems(sessionId = sessionId, refreshTouchedSessions = false)
        val preview = api.get<InboundCompletionPreview>(
            "receiving-sessions/$sessionId/inbound/completion-preview",
        )
        if (preview.snapshotHash.isBlank()) return
        api.post<JsonObject>(
            "receiving-sessions/$sessionId/inbound/complete",
            inboundCompletionRequest(preview.snapshotHash, UUID.randomUUID().toString(), preview.requiresConfirmation),
        )
        refreshSessionSnapshot(sessionId)
    }

    suspend fun knownLocationCodes(sessionId: Long): Set<String> {
        val scope = scope()
        val session = dao.session(scope.userId, scope.namespaceId, sessionId) ?: return emptySet()
        return warehouseLocationRepository.knownCodes(session.warehouseCode)
    }

    suspend fun syncAllPending(): Boolean =
        syncMutex.withLock {
            return@withLock syncPendingItems()
        }

    private suspend fun syncPendingItems(
        sessionId: Long? = null,
        refreshTouchedSessions: Boolean = true,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean {
        if (sessionStore.current().isOffline) return false
        val scope = scope()
        val pending =
            dao.pendingItems(scope.userId, scope.namespaceId)
                .filter { sessionId == null || it.sessionId == sessionId }
        val touchedSessions = pending.mapTo(linkedSetOf()) { it.sessionId }
        var allTransientSucceeded = true
        val attemptAt = System.currentTimeMillis()
        var processed = 0
        onProgress(processed, pending.size)
        groupInboundSyncItems(pending).forEach { group ->
            val result = runCatching { syncItemGroup(group) }
            if (!result.getOrDefault(false)) allTransientSucceeded = false
            processed += group.size
            onProgress(processed, pending.size)
        }
        if (!refreshTouchedSessions) return allTransientSucceeded
        touchedSessions.forEach { sessionId ->
            val session = dao.session(scope.userId, scope.namespaceId, sessionId) ?: return@forEach
            val refreshed =
                runCatching {
                    refreshSnapshot(
                        sessionId,
                        session.sessionCode,
                        session.consolidationOrderCode,
                        session.consolidationOrderName,
                        session.warehouseCode,
                        session.warehouseName,
                        session.hasVirtualInbound,
                    )
                }.isSuccess
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
        return allTransientSucceeded
    }

    suspend fun syncSessionManually(
        sessionId: Long,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): InboundWorkspace {
        if (sessionStore.current().isOffline) throw InboundClientException(InboundClientError.OfflineSyncUnavailable)
        val scope = scope()
        val attemptAt = System.currentTimeMillis()
        val session =
            dao.session(scope.userId, scope.namespaceId, sessionId)
                ?: throw InboundClientException(InboundClientError.SnapshotUnavailable)
        syncMutex.withLock {
            syncPendingItems(sessionId, refreshTouchedSessions = false, onProgress = onProgress)
        }
        val boxPlanIds = refreshSnapshot(
            sessionId,
            session.sessionCode,
            session.consolidationOrderCode,
            session.consolidationOrderName,
            session.warehouseCode,
            session.warehouseName,
            session.hasVirtualInbound,
        )
        boxLabelMappingRepository.refreshSession(sessionId, boxPlanIds)
        val refreshed = dao.session(scope.userId, scope.namespaceId, sessionId) ?: session
        dao.upsertSession(
            refreshed.copy(
                lastSyncAttemptAt = attemptAt,
                lastSyncSuccessAt = System.currentTimeMillis(),
                lastSyncError = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        recomputeSession(scope.userId, scope.namespaceId, sessionId, "")
        recomputeBatches(scope.userId, scope.namespaceId, sessionId)
        syncScheduler.enqueueCacheRefresh(sessionId)
        return workspace(sessionId)
    }

    suspend fun acceptRemote(requestId: String): InboundWorkspace {
        val scope = scope()
        val item = findConflict(scope.userId, scope.namespaceId, requestId)
        dao.updateItemResult(
            requestId = item.requestId,
            status = InboundItemStatus.RemoteAccepted.value,
            remoteLocationCode = item.remoteLocationCode,
            remoteWarehouseLocationId = item.remoteWarehouseLocationId,
            boxPlanId = item.boxPlanId,
            errorCode = 0,
            errorMessage = "",
            syncedAt = System.currentTimeMillis(),
        )
        dao.updateSnapshotLocation(
            scope.userId,
            scope.namespaceId,
            item.sessionId,
            item.boxPlanId,
            item.remoteLocationCode,
            item.remoteWarehouseLocationId,
        )
        recomputeSession(scope.userId, scope.namespaceId, item.sessionId, "")
        recomputeBatches(scope.userId, scope.namespaceId, item.sessionId)
        return workspace(item.sessionId)
    }

    suspend fun overrideRemote(requestId: String): InboundWorkspace {
        if (sessionStore.current().isOffline) throw InboundClientException(InboundClientError.OfflineSyncUnavailable)
        val scope = scope()
        var item = findConflict(scope.userId, scope.namespaceId, requestId)
        val correctionRequestId =
            item.correctionRequestId.ifBlank {
                UUID.randomUUID().toString().also { dao.updateCorrectionRequest(item.requestId, it) }
            }
        item = ensureItemLocation(findConflict(scope.userId, scope.namespaceId, requestId))
        val response =
            try {
                api.put<ReceivingInboundMutationResponse>(
                    "receiving-sessions/${item.sessionId}/inbound-items/${item.boxPlanId}/location",
                    inboundCorrectionRequest(item, correctionRequestId),
                )
            } catch (error: FscmApiException) {
                if (error.inboundStandardCode() == INBOUND_CONCURRENT_CHANGE_CODE) {
                    val authoritative = error.authoritativeLocation()
                    dao.updateItemResult(
                        requestId = item.requestId,
                        status = InboundItemStatus.ConflictPending.value,
                        remoteLocationCode = authoritative.location.ifBlank { item.remoteLocationCode },
                        remoteWarehouseLocationId =
                        authoritative.warehouseLocationId.takeIf { it > 0 }
                            ?: item.remoteWarehouseLocationId,
                        boxPlanId = authoritative.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
                        errorCode = INBOUND_CONCURRENT_CHANGE_CODE,
                        errorMessage = error.serverMessage.orEmpty(),
                        syncedAt = 0,
                    )
                }
                throw error
            }
        val location = response.item.locationCode.ifBlank { item.localLocationCode }
        val warehouseLocationId =
            response.item.warehouseLocationId.takeIf { it > 0 }
                ?: item.localWarehouseLocationId
        val boxPlanId = response.item.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId
        dao.updateItemResult(
            requestId = item.requestId,
            status = InboundItemStatus.RemoteOverridden.value,
            remoteLocationCode = location,
            remoteWarehouseLocationId = warehouseLocationId,
            boxPlanId = boxPlanId,
            errorCode = 0,
            errorMessage = "",
            syncedAt = System.currentTimeMillis(),
        )
        dao.updateSnapshotLocation(
            scope.userId,
            scope.namespaceId,
            item.sessionId,
            boxPlanId,
            location,
            warehouseLocationId,
        )
        recomputeSession(scope.userId, scope.namespaceId, item.sessionId, "")
        recomputeBatches(scope.userId, scope.namespaceId, item.sessionId)
        return workspace(item.sessionId)
    }

    private suspend fun syncItem(storedItem: LocalInboundItemEntity) {
        var item = storedItem
        try {
            item = ensureItemLocation(item)
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Syncing.value,
                remoteLocationCode = item.remoteLocationCode,
                remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                boxPlanId = item.boxPlanId,
                errorCode = 0,
                errorMessage = "",
                syncedAt = 0,
            )
            Log.i("FscmInboundScan", "scan path=receiving-sessions/${item.sessionId}/inbound-scan sessionId=${item.sessionId} boxPlanId=${item.boxPlanId} rawCode=${item.rawCode}")
            val response = api.post<ReceivingInboundMutationResponse>(
                "receiving-sessions/${item.sessionId}/inbound-scan",
                inboundScanRequest(item),
            )
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Synced.value,
                remoteLocationCode = response.item.locationCode.ifBlank { item.localLocationCode },
                remoteWarehouseLocationId =
                response.item.warehouseLocationId.takeIf { it > 0 }
                    ?: item.localWarehouseLocationId,
                boxPlanId = response.item.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
                errorCode = 0,
                errorMessage = "",
                syncedAt = System.currentTimeMillis(),
            )
        } catch (error: FscmApiException) {
            val code = error.inboundStandardCode()
            if (code == INBOUND_LOCATION_CONFLICT_CODE) {
                val authoritative = error.authoritativeLocation()
                dao.updateItemResult(
                    requestId = item.requestId,
                    status = InboundItemStatus.ConflictPending.value,
                    remoteLocationCode = authoritative.location,
                    remoteWarehouseLocationId = authoritative.warehouseLocationId,
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
                    remoteWarehouseLocationId = item.remoteWarehouseLocationId,
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
                    remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                    boxPlanId = item.boxPlanId,
                    errorCode = code,
                    errorMessage = error.serverMessage.orEmpty(),
                    syncedAt = 0,
                )
            }
        } catch (error: InboundClientException) {
            val retry = error.reason == InboundClientError.LocationCacheUnavailable
            dao.updateItemResult(
                requestId = item.requestId,
                status = if (retry) InboundItemStatus.Pending.value else InboundItemStatus.Rejected.value,
                remoteLocationCode = item.remoteLocationCode,
                remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                boxPlanId = item.boxPlanId,
                errorCode = error.reason.inboundLocationErrorCode(),
                errorMessage = error.reason.name,
                syncedAt = 0,
            )
            if (retry) throw error
        } catch (error: Exception) {
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Pending.value,
                remoteLocationCode = item.remoteLocationCode,
                remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                boxPlanId = item.boxPlanId,
                errorCode = 0,
                errorMessage = error.message.orEmpty(),
                syncedAt = 0,
            )
            throw error
        }
    }

    private suspend fun syncItemGroup(storedItems: List<LocalInboundItemEntity>): Boolean {
        val legacyItems = storedItems.filter { it.localWarehouseLocationId <= 0 }
        var succeeded = syncItemsIndividually(legacyItems)
        val batchItems = storedItems.filter { it.localWarehouseLocationId > 0 }
        if (batchItems.isEmpty()) return succeeded
        if (batchEndpointSupported == false) {
            return syncItemsIndividually(batchItems) && succeeded
        }
        batchItems.chunked(INBOUND_BATCH_SIZE).forEach { chunk ->
            val chunkSucceeded =
                runCatching { syncBatchChunk(chunk) }.getOrElse { error ->
                    if (error is FscmApiException && error.statusCode in setOf(404, 405)) {
                        batchEndpointSupported = false
                        syncItemsIndividually(chunk)
                    } else {
                        markBatchRetryable(chunk, error)
                        false
                    }
                }
            if (!chunkSucceeded) succeeded = false
        }
        return succeeded
    }

    private suspend fun syncItemsIndividually(items: List<LocalInboundItemEntity>): Boolean {
        var succeeded = true
        items.forEach { item ->
            if (runCatching { syncItem(item) }.isFailure) succeeded = false
        }
        return succeeded
    }

    private suspend fun syncBatchChunk(storedItems: List<LocalInboundItemEntity>): Boolean {
        val items = storedItems.map { ensureItemLocation(it) }
        items.forEach { item ->
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Syncing.value,
                remoteLocationCode = item.remoteLocationCode,
                remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                boxPlanId = item.boxPlanId,
                errorCode = 0,
                errorMessage = "",
                syncedAt = 0,
            )
        }
        val first = items.first()
        val response =
            api.post<ReceivingInboundBatchResponse>(
                "receiving-sessions/${first.sessionId}/inbound-scan/batch",
                inboundBatchScanRequest(first.batchId, items),
            )
        batchEndpointSupported = true
        val results = response.items.associateBy { it.requestId }
        var succeeded = true
        items.forEach { item ->
            val result = results[item.requestId]
            if (result == null) {
                succeeded = false
                updateBatchItemResult(item, "retryable", 0, "Batch response omitted item", ReceivingInboundItem())
            } else {
                if (result.status == "retryable") succeeded = false
                updateBatchItemResult(item, result.status, result.code, result.message, result.item)
            }
        }
        return succeeded
    }

    private suspend fun updateBatchItemResult(
        item: LocalInboundItemEntity,
        resultStatus: String,
        code: Int,
        message: String,
        remote: ReceivingInboundItem,
    ) {
        val status =
            when (resultStatus) {
                "synced" -> InboundItemStatus.Synced.value
                "conflict" -> InboundItemStatus.ConflictPending.value
                "rejected" -> InboundItemStatus.Rejected.value
                else -> InboundItemStatus.Pending.value
            }
        dao.updateItemResult(
            requestId = item.requestId,
            status = status,
            remoteLocationCode =
            remote.locationCode.ifBlank {
                if (status == InboundItemStatus.Synced.value) item.localLocationCode else item.remoteLocationCode
            },
            remoteWarehouseLocationId =
            remote.warehouseLocationId.takeIf { it > 0 }
                ?: if (status == InboundItemStatus.Synced.value) item.localWarehouseLocationId else item.remoteWarehouseLocationId,
            boxPlanId = remote.boxPlanId.takeIf { it > 0 } ?: item.boxPlanId,
            errorCode = code,
            errorMessage = message,
            syncedAt = if (status == InboundItemStatus.Synced.value) System.currentTimeMillis() else 0,
        )
    }

    private suspend fun markBatchRetryable(items: List<LocalInboundItemEntity>, error: Throwable) {
        items.forEach { item ->
            dao.updateItemResult(
                requestId = item.requestId,
                status = InboundItemStatus.Pending.value,
                remoteLocationCode = item.remoteLocationCode,
                remoteWarehouseLocationId = item.remoteWarehouseLocationId,
                boxPlanId = item.boxPlanId,
                errorCode = (error as? FscmApiException)?.inboundStandardCode() ?: 0,
                errorMessage = (error as? FscmApiException)?.serverMessage ?: error.message.orEmpty(),
                syncedAt = 0,
            )
        }
    }

    private suspend fun validateBatchLocation(batch: LocalInboundBatchEntity): LocalInboundBatchEntity {
        if (batch.warehouseLocationId > 0) return batch
        val session =
            ensureSessionWarehouse(
                dao.session(batch.userId, batch.namespaceId, batch.sessionId)
                    ?: throw InboundClientException(InboundClientError.SnapshotUnavailable),
            )
        val resolved =
            warehouseLocationRepository.resolvePersisted(
                locationId = batch.warehouseLocationId,
                locationCode = batch.locationCode,
                warehouseCode = session.warehouseCode,
            ).location
        dao.updateBatchLocation(
            batchId = batch.id,
            warehouseLocationId = resolved.locationId,
            locationCode = resolved.code,
            locationName = resolved.name,
            zoneName = resolved.zoneName,
        )
        dao.updateBatchItemsLocation(batch.id, resolved.locationId, resolved.code)
        return batch.copy(
            warehouseLocationId = resolved.locationId,
            locationCode = resolved.code,
            locationName = resolved.name,
            zoneName = resolved.zoneName,
        )
    }

    private suspend fun ensureItemLocation(item: LocalInboundItemEntity): LocalInboundItemEntity {
        if (item.localWarehouseLocationId > 0) return item
        val session =
            ensureSessionWarehouse(
                dao.session(item.userId, item.namespaceId, item.sessionId)
                    ?: throw InboundClientException(InboundClientError.SnapshotUnavailable),
            )
        val resolved =
            warehouseLocationRepository.resolvePersisted(
                locationId = item.localWarehouseLocationId,
                locationCode = item.localLocationCode,
                warehouseCode = session.warehouseCode,
            ).location
        dao.updateItemLocalLocation(item.requestId, resolved.locationId, resolved.code)
        return item.copy(
            localWarehouseLocationId = resolved.locationId,
            localLocationCode = resolved.code,
        )
    }

    private suspend fun ensureSessionWarehouse(
        session: LocalInboundSessionEntity,
    ): LocalInboundSessionEntity {
        if (session.warehouseCode.isNotBlank()) return session
        if (sessionStore.current().isOffline) {
            throw InboundClientException(InboundClientError.TargetWarehouseMissing)
        }
        val remote = receivingRepository.session(session.sessionId, forceRefresh = true)
        if (remote.warehouseCode.isBlank()) {
            throw InboundClientException(InboundClientError.TargetWarehouseMissing)
        }
        return session.copy(
            sessionCode = remote.code.ifBlank { session.sessionCode },
            warehouseCode = remote.warehouseCode,
            warehouseName = remote.warehouseName,
            hasVirtualInbound = session.hasVirtualInbound || remote.hasVirtualInbound,
            updatedAt = System.currentTimeMillis(),
        ).also { dao.upsertSession(it) }
    }

    private suspend fun refreshSnapshot(
        sessionId: Long,
        sessionCode: String,
        consolidationOrderCode: String,
        consolidationOrderName: String,
        warehouseCode: String,
        warehouseName: String,
        hasVirtualInbound: Boolean = false,
    ): List<Long> {
        val scope = scope()
        val rows = mutableListOf<ReceivingInboundItem>()
        var page = 1
        var total = Int.MAX_VALUE
        var summary = ReceivingInboundSummary()
        while (rows.size < total) {
            val response =
                api.get<ReceivingInboundPage>(
                    "receiving-sessions/$sessionId/inbound-items",
                    mapOf(
                        "page" to page.toString(),
                        "pageSize" to SNAPSHOT_PAGE_SIZE.toString(),
                        "include_summary" to (page == 1).toString(),
                    ),
                )
            rows += response.data
            total = response.total
            if (page == 1) summary = response.summary
            if (response.data.isEmpty() || rows.size >= total) break
            page++
        }
        val now = System.currentTimeMillis()
        val snapshotRows =
            rows.map { row ->
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
                    warehouseLocationId = row.warehouseLocationId,
                    canScanRebind = row.canScanRebind,
                    receiptScanStatus = row.receiptScanStatus,
                    exceptionReason = row.exceptionReason,
                    remoteScannedBy = row.scannedBy,
                    remoteScannedAt = row.scannedAt.toInboundEpochMillis(),
                    eligible = row.isInboundEligible(),
                    snapshotAt = now,
                )
            }
        val localSummary = summary.copy(
            inboundedCount = snapshotRows.count(LocalInboundSnapshotEntity::isCompletedPhysicalInbound),
            unassignedCount = snapshotRows.count(LocalInboundSnapshotEntity::isPendingPhysicalInbound),
        )
        val storedSession =
            dao.session(scope.userId, scope.namespaceId, sessionId)
                ?: LocalInboundSessionEntity(scope.userId, scope.namespaceId, sessionId, sessionCode)
        dao.replaceSnapshots(
            session =
            storedSession.copy(
                sessionCode = sessionCode,
                consolidationOrderCode = consolidationOrderCode,
                consolidationOrderName = consolidationOrderName,
                warehouseCode = warehouseCode,
                warehouseName = warehouseName,
                hasVirtualInbound =
                storedSession.hasVirtualInbound ||
                    hasVirtualInbound ||
                    snapshotRows.any { it.canScanRebind || it.locationCode.isVirtualInboundLocation() },
                snapshotComplete = true,
                snapshotAt = now,
                totalCount = localSummary.totalCount,
                inboundedCount = localSummary.inboundedCount,
                exceptionCount = localSummary.exceptionCount,
                unassignedCount = localSummary.unassignedCount,
                updatedAt = now,
            ),
            rows = snapshotRows,
        )
        dao.reconcilableItems(scope.userId, scope.namespaceId, sessionId).forEach { item ->
            val remote = snapshotRows.firstOrNull { it.boxPlanId == item.boxPlanId || it.labelCode == item.labelCode }
            val reconciliation = reconcileInboundItem(item, remote) ?: return@forEach
            dao.updateItemResult(
                requestId = item.requestId,
                status = reconciliation.status,
                remoteLocationCode = reconciliation.remoteLocationCode,
                remoteWarehouseLocationId = reconciliation.remoteWarehouseLocationId,
                boxPlanId = reconciliation.boxPlanId,
                errorCode = reconciliation.errorCode,
                errorMessage = reconciliation.errorMessage,
                syncedAt = if (reconciliation.status == InboundItemStatus.Synced.value) now else 0,
            )
        }
        return snapshotRows.map(LocalInboundSnapshotEntity::boxPlanId)
    }

    private suspend fun refreshSessionSnapshot(
        sessionId: Long,
        cachedSessionCode: String = "",
        prefetchedSession: ReceivingSession? = null,
    ): InboundWorkspace {
        val session =
            prefetchedSession
                ?: receivingRepository.session(sessionId, forceRefresh = cachedSessionCode.isBlank())
        val sessionCode = cachedSessionCode.ifBlank { session.code.ifBlank { "RS-$sessionId" } }
        val boxPlanIds = refreshSnapshot(
            sessionId,
            sessionCode,
            session.consolidationOrder?.code.orEmpty(),
            session.consolidationOrder?.name.orEmpty(),
            session.warehouseCode,
            session.warehouseName,
            session.hasVirtualInbound,
        )
        boxLabelMappingRepository.refreshSession(sessionId, boxPlanIds)
        return workspace(sessionId, session.inboundStatus.ifBlank { session.status })
    }

    suspend fun refreshCachesInBackground(preferredSessionId: Long? = null): Boolean {
        if (sessionStore.current().isOffline) return false
        return runCatching {
            warehouseLocationRepository.refreshIfStale()
            val scope = scope()
            val remote = receivingRepository.sessions()
                .filter(ReceivingSession::isInboundCacheCandidate)
            val localById = dao.sessions(scope.userId, scope.namespaceId).associateBy { it.sessionId }
            val ordered = remote.sortedWith(
                compareByDescending<ReceivingSession> { it.id == preferredSessionId }
                    .thenByDescending { localById[it.id]?.lastOpenedAt ?: 0 },
            )
            ordered.forEach { session ->
                val local = localById[session.id]
                if (local == null || local.isSnapshotStale() || local.warehouseCode.isBlank()) {
                    refreshSessionSnapshot(session.id, session.code, session)
                } else {
                    val planIds = dao.snapshots(scope.userId, scope.namespaceId, session.id).map { it.boxPlanId }
                    boxLabelMappingRepository.refreshSession(session.id, planIds)
                }
                refreshProductSummaries(session.id)
            }
            boxLabelMappingRepository.refreshIfStale()
        }.isSuccess
    }

    suspend fun refreshProductCache(sessionId: Long) {
        refreshProductSummaries(sessionId)
    }

    private suspend fun refreshProductSummaries(sessionId: Long) {
        val scope = scope()
        val rows = mutableListOf<ReceivingInboundProductSummary>()
        var page = 1
        var total = Int.MAX_VALUE
        while (rows.size < total) {
            val response = api.get<ReceivingInboundProductPage>(
                "receiving-sessions/$sessionId/inbound-products",
                mapOf("page" to page.toString(), "page_size" to PRODUCT_PAGE_SIZE.toString()),
            )
            rows += response.items
            total = response.total
            if (response.items.isEmpty() || rows.size >= total) break
            page++
        }
        val now = System.currentTimeMillis()
        dao.replaceProductSummaries(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            sessionId = sessionId,
            rows = rows.map { row ->
                LocalInboundProductSummaryEntity(
                    userId = scope.userId,
                    namespaceId = scope.namespaceId,
                    sessionId = sessionId,
                    productId = row.productId,
                    productCode = row.productCode,
                    productName = row.productName,
                    productImage = row.productImage,
                    skuCount = row.skuCount,
                    skuCodesJson = Json.encodeToString(row.skuCodes),
                    boxPlanIdsJson = Json.encodeToString(row.boxPlanIds),
                    totalBoxCount = row.totalBoxCount,
                    inboundedBoxCount = row.inboundedBoxCount,
                    pendingBoxCount = row.pendingBoxCount,
                    exceptionBoxCount = row.exceptionBoxCount,
                    snapshotAt = now,
                )
            },
            snapshotAt = now,
        )
    }

    private suspend fun buildSummary(
        userId: Long,
        namespaceId: Long,
        sessionId: Long,
        sessionCode: String,
        consolidationOrderCode: String = "",
        consolidationOrderName: String = "",
        hasVirtualInbound: Boolean = false,
        remoteStatus: String,
        localSession: LocalInboundSessionEntity?,
        fallbackTotal: Int = 0,
        warehouseCode: String = "",
        warehouseName: String = "",
        locationCache: WarehouseLocationCacheState = WarehouseLocationCacheState(),
    ): InboundSessionSummary {
        val snapshots = dao.snapshots(userId, namespaceId, sessionId)
        val items = dao.itemsForSession(userId, namespaceId, sessionId)
        val progress = calculateInboundProgress(snapshots, items)
        return InboundSessionSummary(
            sessionId = sessionId,
            sessionCode = sessionCode.ifBlank { "RS-$sessionId" },
            consolidationOrderCode = consolidationOrderCode.ifBlank { localSession?.consolidationOrderCode.orEmpty() },
            consolidationOrderName = consolidationOrderName.ifBlank { localSession?.consolidationOrderName.orEmpty() },
            warehouseCode = warehouseCode.ifBlank { localSession?.warehouseCode.orEmpty() },
            warehouseName = warehouseName.ifBlank { localSession?.warehouseName.orEmpty() },
            remoteStatus = remoteStatus,
            totalCount = localSession?.totalCount?.takeIf { it > 0 } ?: snapshots.size.takeIf { it > 0 } ?: fallbackTotal,
            inboundedCount = if (localSession?.snapshotComplete == true) {
                snapshots.count(LocalInboundSnapshotEntity::isCompletedPhysicalInbound)
            } else {
                localSession?.inboundedCount ?: 0
            },
            localAssignedCount = progress.localContribution,
            remainingCount = progress.remaining.takeIf { localSession?.snapshotComplete == true } ?: fallbackTotal,
            centerPendingCount = if (localSession?.snapshotComplete == true) {
                snapshots.count(LocalInboundSnapshotEntity::isPendingPhysicalInbound)
            } else {
                localSession?.unassignedCount ?: progress.remaining
            },
            pendingCount = progress.pending,
            conflictCount = progress.conflicts,
            rejectedCount = progress.rejected,
            exceptionCount =
            localSession?.exceptionCount?.takeIf { it >= 0 }
                ?: snapshots.count { !it.eligible },
            hasVirtualInbound =
            hasVirtualInbound ||
                localSession?.hasVirtualInbound == true ||
                snapshots.any { it.canScanRebind || it.locationCode.isVirtualInboundLocation() },
            rebindableCount =
            snapshots.count {
                it.eligible && (it.canScanRebind || it.locationCode.isVirtualInboundLocation())
            },
            lastSyncAt = localSession?.lastSyncSuccessAt ?: 0,
            snapshotComplete = localSession?.snapshotComplete == true,
            locationCacheComplete = locationCache.complete,
            locationCacheRefreshedAt = locationCache.refreshedAt,
            productCacheComplete = localSession?.productCacheComplete == true,
            productCacheAt = localSession?.productCacheAt ?: 0,
            // The center service is authoritative when online. Local status only
            // describes pending/offline work and must not override a remote state.
            status = normalizeRemoteInboundStatus(remoteStatus).ifBlank { localSession?.status ?: InboundSessionStatus.Active.value },
        )
    }

    private suspend fun recomputeSession(userId: Long, namespaceId: Long, sessionId: Long, remoteStatus: String) {
        val session = dao.session(userId, namespaceId, sessionId) ?: return
        val summary =
            buildSummary(
                userId = userId,
                namespaceId = namespaceId,
                sessionId = sessionId,
                sessionCode = session.sessionCode,
                remoteStatus = remoteStatus,
                localSession = session,
            )
        val status =
            when {
                summary.conflictCount > 0 -> InboundSessionStatus.ConflictPending.value
                summary.rejectedCount > 0 -> InboundSessionStatus.Failed.value
                summary.pendingCount > 0 && summary.remainingCount == 0 -> InboundSessionStatus.LocalCompletePendingSync.value
                summary.pendingCount > 0 -> InboundSessionStatus.Active.value
                summary.snapshotComplete &&
                    session.unassignedCount == 0 &&
                    summary.remainingCount == 0 -> InboundSessionStatus.Complete.value
                else -> InboundSessionStatus.Active.value
            }
        dao.upsertSession(session.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun recomputeBatches(userId: Long, namespaceId: Long, sessionId: Long) {
        dao.batches(userId, namespaceId, sessionId).filterNot { it.status == InboundBatchStatus.Draft.value }.forEach { batch ->
            val items = dao.itemsForBatch(batch.id)
            val status =
                when {
                    items.any { it.status == InboundItemStatus.ConflictPending.value } -> InboundBatchStatus.ConflictPending.value
                    items.any { it.status == InboundItemStatus.Rejected.value } -> InboundBatchStatus.Failed.value
                    items.any {
                        it.status in
                            setOf(
                                InboundItemStatus.Pending.value,
                                InboundItemStatus.Syncing.value,
                            )
                    } -> InboundBatchStatus.Pending.value
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

internal fun LocalInboundSessionEntity.isSnapshotStale(now: Long = System.currentTimeMillis()): Boolean =
    !snapshotComplete || snapshotAt <= 0 || now - snapshotAt >= INBOUND_SNAPSHOT_MAX_AGE_MILLIS

private data class LocalInboundScope(val userId: Long, val namespaceId: Long)

private data class AuthoritativeInboundLocation(
    val boxPlanId: Long,
    val warehouseLocationId: Long,
    val location: String,
)

internal fun FscmApiException.inboundStandardCode(): Int =
    (payload as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull ?: statusCode

private fun FscmApiException.authoritativeLocation(): AuthoritativeInboundLocation {
    val data = runCatching { (payload as JsonObject)["data"]?.jsonObject }.getOrNull()
    return AuthoritativeInboundLocation(
        boxPlanId =
        data?.get("box_plan_id")?.jsonPrimitive?.longOrNull
            ?: data?.get("id")?.jsonPrimitive?.longOrNull
            ?: 0,
        warehouseLocationId = data?.get("warehouse_location_id")?.jsonPrimitive?.longOrNull ?: 0,
        location = data?.get("location_code")?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun InboundClientError.inboundLocationErrorCode(): Int =
    when (this) {
        InboundClientError.LocationNotFound -> INBOUND_LOCATION_NOT_FOUND_CODE
        InboundClientError.LocationInactive -> INBOUND_LOCATION_INACTIVE_CODE
        InboundClientError.LocationWarehouseMismatch -> INBOUND_LOCATION_WAREHOUSE_MISMATCH_CODE
        else -> 0
    }

internal fun inboundScanRequest(item: LocalInboundItemEntity): JsonObject =
    buildJsonObject {
        put("raw_code", item.rawCode)
        put("warehouse_location_id", item.localWarehouseLocationId)
        put("location_code", item.localLocationCode)
        put("request_id", item.requestId)
        put("operation_mode", "scan")
    }

internal fun inboundBatchScanRequest(batchId: String, items: List<LocalInboundItemEntity>): JsonObject =
    buildJsonObject {
        val first = items.first()
        put("batch_id", batchId)
        put("warehouse_location_id", first.localWarehouseLocationId)
        put("location_code", first.localLocationCode)
        put("operation_mode", "scan")
        put("confirm_receipt", false)
        put(
            "items",
            buildJsonArray {
                items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("request_id", item.requestId)
                            put("box_plan_id", item.boxPlanId)
                            put("raw_code", item.rawCode)
                            put("box_no", item.labelCode)
                        },
                    )
                }
            },
        )
    }

internal fun groupInboundSyncItems(
    items: List<LocalInboundItemEntity>,
    batchSize: Int = INBOUND_BATCH_SIZE,
): List<List<LocalInboundItemEntity>> =
    items
        .groupBy { Triple(it.sessionId, it.localWarehouseLocationId, it.localLocationCode) }
        .values
        .flatMap { it.chunked(batchSize) }

internal fun pendingInboundProducts(
    products: List<LocalInboundProductSummaryEntity>,
    query: String = "",
): List<LocalInboundProductSummaryEntity> {
    val keyword = query.trim().lowercase(Locale.ROOT)
    return products
        .asSequence()
        .filter { it.pendingBoxCount > 0 }
        .filter {
            keyword.isBlank() ||
                it.productCode.lowercase(Locale.ROOT).contains(keyword) ||
                it.productName.lowercase(Locale.ROOT).contains(keyword)
        }
        .sortedWith(compareByDescending<LocalInboundProductSummaryEntity> { it.pendingBoxCount }.thenBy { it.productCode })
        .toList()
}

internal fun inboundCorrectionRequest(
    item: LocalInboundItemEntity,
    correctionRequestId: String,
): JsonObject =
    buildJsonObject {
        put("expected_location_code", item.remoteLocationCode)
        put("warehouse_location_id", item.localWarehouseLocationId)
        put("location_code", item.localLocationCode)
        put("reason", "移动端离线入库冲突，用户确认采用本地库位")
        put("request_id", correctionRequestId)
    }

private fun ReceivingInboundItem.isInboundEligible(): Boolean =
    status.lowercase() !in INELIGIBLE_INBOUND_STATUSES && inboundStatus.lowercase() !in setOf("exception", "invalid")

private fun ReceivingSession.isInboundCacheCandidate(): Boolean =
    status.trim().lowercase() !in CANCELLED_RECEIVING_SESSION_STATUSES &&
        (normalizeRemoteInboundStatus(inboundStatus) != InboundSessionStatus.Complete.value || hasVirtualInbound)

private fun String.trimScannerSuffix(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }

private fun String.isVirtualInboundLocation(): Boolean =
    trim().equals(VIRTUAL_INBOUND_LOCATION_CODE, ignoreCase = true)

private fun String?.isNullOrVirtualInboundLocation(): Boolean =
    isNullOrBlank() || orEmpty().isVirtualInboundLocation()

private fun String.isBlankOrVirtualInboundLocation(): Boolean =
    isBlank() || isVirtualInboundLocation()

private fun String.toInboundEpochMillis(): Long =
    takeIf(String::isNotBlank)
        ?.let { runCatching { Instant.parse(it).toEpochMilli().coerceAtLeast(0) }.getOrDefault(0) }
        ?: 0

private val INELIGIBLE_INBOUND_STATUSES = setOf("shortage_marked", "adjusted_out", "shorted", "cancelled", "voided", "exception")
private val CANCELLED_RECEIVING_SESSION_STATUSES = setOf("cancelled", "canceled")
private val TERMINAL_INBOUND_ITEM_STATUSES =
    setOf(
        InboundItemStatus.Synced.value,
        InboundItemStatus.RemoteAccepted.value,
        InboundItemStatus.RemoteOverridden.value,
    )
private const val VIRTUAL_INBOUND_LOCATION_CODE = "FSCM-VIRTUAL-INBOUND"
private const val SNAPSHOT_PAGE_SIZE = 200
private const val PRODUCT_PAGE_SIZE = 200
private const val INBOUND_BATCH_SIZE = 100
private const val INBOUND_SNAPSHOT_MAX_AGE_MILLIS = 12 * 60 * 60 * 1000L
private const val INBOUND_LOCATION_CONFLICT_CODE = 40942
private const val INBOUND_CONCURRENT_CHANGE_CODE = 40943
private const val INBOUND_LOCATION_NOT_FOUND_CODE = 42244
private const val INBOUND_LOCATION_INACTIVE_CODE = 42245
private const val INBOUND_LOCATION_WAREHOUSE_MISMATCH_CODE = 42246
