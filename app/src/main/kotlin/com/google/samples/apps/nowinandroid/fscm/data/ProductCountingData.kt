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
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(
    tableName = "product_count_tasks",
    indices = [
        Index(value = ["userId", "namespaceId", "status", "updatedAt"]),
        Index(value = ["remoteId"]),
    ],
)
data class ProductCountTaskEntity(
    @PrimaryKey val clientTaskId: String,
    val userId: Long,
    val namespaceId: Long,
    val remoteId: Long = 0,
    val status: String = ProductCountTaskStatus.InProgress.value,
    val productCode: String = "",
    val productName: String = "",
    val resetGeneration: Int = 0,
    val revision: Long = 0,
    val editorDeviceId: String,
    val serverCount: Int = 0,
    val syncStatus: String = ProductCountSyncStatus.Pending.value,
    val lastSyncError: String = "",
    val uploadStatus: String = ProductCountUploadStatus.Local.value,
    val serverRevision: Long = 0,
    val lastUploadId: String = "",
    val uploadedAt: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long = 0,
)

@Entity(
    tableName = "product_count_entries",
    indices = [
        Index(value = ["userId", "namespaceId", "taskClientId", "resetGeneration", "scannedAt"]),
        Index(value = ["taskClientId", "resetGeneration", "labelCode"]),
    ],
)
data class ProductCountEntryEntity(
    @PrimaryKey val clientScanId: String,
    val userId: Long,
    val namespaceId: Long,
    val taskClientId: String,
    val resetGeneration: Int,
    val labelCode: String,
    val originalRawCode: String,
    val skuCodesJson: String,
    val skuItemsJson: String = "[]",
    val sequenceNo: Int = 0,
    val productCode: String,
    val productName: String,
    val resolutionSource: String,
    val duplicateConfirmed: Boolean,
    val outcome: String,
    val syncStatus: String = ProductCountSyncStatus.Pending.value,
    val syncError: String = "",
    val scannedAt: Long,
    val validationStatus: String = "pending",
    val validationMessage: String = "",
)

@Entity(
    tableName = "sku_product_index",
    primaryKeys = ["userId", "namespaceId", "skuCode"],
    indices = [Index(value = ["userId", "namespaceId", "productCode"])],
)
data class SkuProductIndexEntity(
    val userId: Long,
    val namespaceId: Long,
    val skuCode: String,
    val productId: Long = 0,
    val productCode: String,
    val productName: String,
    val remoteUpdatedAt: String = "",
    val syncedAt: Long,
)

data class ProductCountTaskSummary(
    @Embedded val task: ProductCountTaskEntity,
    val boxCount: Int,
)

enum class ProductCountTaskStatus(val value: String) {
    InProgress("in_progress"),
    Completed("completed"),
}

enum class ProductCountSyncStatus(val value: String) {
    Pending("pending"),
    Synced("synced"),
    Conflict("conflict"),
    Rejected("rejected"),
}

enum class ProductCountUploadStatus(val value: String) {
    Local("local"),
    Uploading("uploading"),
    Uploaded("uploaded"),
    UploadedWithWarnings("uploaded_with_warnings"),
    Failed("failed"),
}

@Serializable
data class ProductCountSkuItem(
    @SerialName("sku_code") val skuCode: String,
    val quantity: Int? = null,
    @SerialName("quantity_source") val quantitySource: String = "unknown",
)

data class ProductCountResolvedLabel(
    val labelCode: String,
    val originalRawCode: String,
    val skuCodes: List<String>,
    val productCode: String,
    val productName: String,
    val resolutionSource: String,
    val skuItems: List<ProductCountSkuItem> = emptyList(),
)

sealed interface ProductCountScanResult {
    data class Accepted(val entry: ProductCountEntryEntity, val boxCount: Int) : ProductCountScanResult
    data class Duplicate(val resolved: ProductCountResolvedLabel) : ProductCountScanResult
    data class ProductMismatch(val expectedProduct: String, val actualProduct: String) : ProductCountScanResult
    data object TaskCompleted : ProductCountScanResult
    data object NotEditor : ProductCountScanResult
    data object MissingTask : ProductCountScanResult
}

@Dao
interface ProductCountingDao {
    @Query(
        """
        SELECT t.*,
          (
            SELECT COUNT(*) FROM product_count_entries e
            WHERE e.userId = t.userId AND e.namespaceId = t.namespaceId
              AND e.taskClientId = t.clientTaskId AND e.resetGeneration = t.resetGeneration
              AND e.syncStatus NOT IN ('conflict', 'rejected')
          ) AS boxCount
        FROM product_count_tasks t
        WHERE t.userId = :userId AND t.namespaceId = :namespaceId
        ORDER BY t.updatedAt DESC
        """,
    )
    fun observeTasks(userId: Long, namespaceId: Long): Flow<List<ProductCountTaskSummary>>

    @Query(
        """
        SELECT t.*,
          (
            SELECT COUNT(*) FROM product_count_entries e
            WHERE e.userId = t.userId AND e.namespaceId = t.namespaceId
              AND e.taskClientId = t.clientTaskId AND e.resetGeneration = t.resetGeneration
              AND e.syncStatus NOT IN ('conflict', 'rejected')
          ) AS boxCount
        FROM product_count_tasks t
        WHERE t.userId = :userId AND t.namespaceId = :namespaceId AND t.clientTaskId = :taskId
        LIMIT 1
        """,
    )
    fun observeTask(userId: Long, namespaceId: Long, taskId: String): Flow<ProductCountTaskSummary?>

    @Query(
        """
        SELECT * FROM product_count_entries
        WHERE userId = :userId AND namespaceId = :namespaceId AND taskClientId = :taskId
          AND resetGeneration = :generation
        ORDER BY sequenceNo DESC, scannedAt DESC
        """,
    )
    fun observeEntries(userId: Long, namespaceId: Long, taskId: String, generation: Int): Flow<List<ProductCountEntryEntity>>

    @Query("SELECT * FROM product_count_tasks WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId LIMIT 1")
    suspend fun task(userId: Long, namespaceId: Long, taskId: String): ProductCountTaskEntity?

    @Query(
        """
        SELECT COUNT(*) FROM product_count_entries
        WHERE userId = :userId AND namespaceId = :namespaceId AND taskClientId = :taskId
          AND resetGeneration = :generation AND labelCode = :labelCode
          AND syncStatus NOT IN ('conflict', 'rejected')
        """,
    )
    suspend fun acceptedLabelCount(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        generation: Int,
        labelCode: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM product_count_entries
        WHERE userId = :userId AND namespaceId = :namespaceId AND taskClientId = :taskId
          AND resetGeneration = :generation AND syncStatus NOT IN ('conflict', 'rejected')
        """,
    )
    suspend fun acceptedCount(userId: Long, namespaceId: Long, taskId: String, generation: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ProductCountTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<ProductCountTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: ProductCountEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<ProductCountEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkuMappings(rows: List<SkuProductIndexEntity>)

    @Query(
        """
        SELECT * FROM sku_product_index
        WHERE userId = :userId AND namespaceId = :namespaceId AND skuCode IN (:skuCodes)
        """,
    )
    suspend fun skuMappings(userId: Long, namespaceId: Long, skuCodes: List<String>): List<SkuProductIndexEntity>

    @Query("SELECT MAX(syncedAt) FROM sku_product_index WHERE userId = :userId AND namespaceId = :namespaceId")
    suspend fun latestSkuIndexSync(userId: Long, namespaceId: Long): Long?

    @Query(
        """
        UPDATE product_count_tasks SET productCode = :productCode, productName = :productName,
          syncStatus = 'pending', lastSyncError = '', updatedAt = :updatedAt
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
        """,
    )
    suspend fun updateProduct(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        productCode: String,
        productName: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE product_count_tasks SET updatedAt = :updatedAt, syncStatus = 'pending', lastSyncError = ''
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
        """,
    )
    suspend fun touchTask(userId: Long, namespaceId: Long, taskId: String, updatedAt: Long)

    @Query(
        """
        UPDATE product_count_tasks SET resetGeneration = resetGeneration + 1,
          productCode = '', productName = '', serverCount = 0, revision = revision + 1,
          syncStatus = 'pending', uploadStatus = 'local', lastUploadId = '',
          lastSyncError = '', updatedAt = :updatedAt
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
          AND status = 'in_progress' AND editorDeviceId = :deviceId
        """,
    )
    suspend fun resetTask(userId: Long, namespaceId: Long, taskId: String, deviceId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE product_count_tasks SET status = :status, editorDeviceId = :deviceId,
          completedAt = :completedAt, revision = revision + 1, syncStatus = 'pending',
          lastSyncError = '', updatedAt = :updatedAt
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
        """,
    )
    suspend fun updateStatus(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        status: String,
        deviceId: String,
        completedAt: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE product_count_tasks SET syncStatus = :syncStatus, lastSyncError = :error
        WHERE clientTaskId = :taskId
        """,
    )
    suspend fun updateTaskSync(taskId: String, syncStatus: String, error: String = "")

    @Query(
        """
        UPDATE product_count_entries SET syncStatus = :syncStatus, syncError = :error
        WHERE clientScanId = :scanId
        """,
    )
    suspend fun updateEntrySync(scanId: String, syncStatus: String, error: String = "")

    @Query(
        """
        SELECT * FROM product_count_entries
        WHERE userId = :userId AND namespaceId = :namespaceId AND taskClientId = :taskId
        ORDER BY resetGeneration ASC, sequenceNo ASC, scannedAt ASC
        """,
    )
    suspend fun allEntries(userId: Long, namespaceId: Long, taskId: String): List<ProductCountEntryEntity>

    @Query(
        """
        SELECT COALESCE(MAX(sequenceNo), 0) FROM product_count_entries
        WHERE userId = :userId AND namespaceId = :namespaceId AND taskClientId = :taskId
        """,
    )
    suspend fun maxSequenceNo(userId: Long, namespaceId: Long, taskId: String): Int

    @Query(
        """
        UPDATE product_count_tasks SET uploadStatus = :uploadStatus, lastUploadId = :uploadId,
          lastSyncError = :error, updatedAt = :updatedAt
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
        """,
    )
    suspend fun updateUploadState(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        uploadStatus: String,
        uploadId: String,
        error: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE product_count_tasks SET remoteId = :remoteId, serverRevision = :serverRevision,
          uploadStatus = :uploadStatus, uploadedAt = :uploadedAt, lastSyncError = '',
          updatedAt = :uploadedAt
        WHERE userId = :userId AND namespaceId = :namespaceId AND clientTaskId = :taskId
        """,
    )
    suspend fun markUploaded(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        remoteId: Long,
        serverRevision: Long,
        uploadStatus: String,
        uploadedAt: Long,
    )

    @Query(
        """
        UPDATE product_count_entries SET validationStatus = :status, validationMessage = :message
        WHERE clientScanId = :scanId
        """,
    )
    suspend fun updateEntryValidation(scanId: String, status: String, message: String)

    @Transaction
    suspend fun acceptResolvedLabel(
        userId: Long,
        namespaceId: Long,
        taskId: String,
        deviceId: String,
        resolved: ProductCountResolvedLabel,
        duplicateConfirmed: Boolean,
        scannedAt: Long,
        json: Json,
    ): ProductCountScanResult {
        var current = task(userId, namespaceId, taskId) ?: return ProductCountScanResult.MissingTask
        val duplicateCount = acceptedLabelCount(
            userId,
            namespaceId,
            taskId,
            current.resetGeneration,
            resolved.labelCode,
        )
        when (
            productCountScanPolicy(
                taskStatus = current.status,
                editorDeviceId = current.editorDeviceId,
                currentDeviceId = deviceId,
                lockedProductCode = current.productCode,
                scannedProductCode = resolved.productCode,
                duplicateCount = duplicateCount,
                duplicateConfirmed = duplicateConfirmed,
            )
        ) {
            ProductCountScanPolicy.TaskCompleted -> return ProductCountScanResult.TaskCompleted
            ProductCountScanPolicy.NotEditor -> return ProductCountScanResult.NotEditor
            ProductCountScanPolicy.ProductMismatch -> return ProductCountScanResult.ProductMismatch(
                current.productName.ifBlank { current.productCode },
                resolved.productName.ifBlank { resolved.productCode },
            )
            ProductCountScanPolicy.RequireDuplicateConfirmation -> return ProductCountScanResult.Duplicate(resolved)
            ProductCountScanPolicy.Accept -> Unit
        }
        if (current.productCode.isBlank()) {
            updateProduct(userId, namespaceId, taskId, resolved.productCode, resolved.productName, scannedAt)
            current = current.copy(productCode = resolved.productCode, productName = resolved.productName)
        }
        val duplicate = duplicateCount > 0
        val entry = ProductCountEntryEntity(
            clientScanId = UUID.randomUUID().toString(),
            userId = userId,
            namespaceId = namespaceId,
            taskClientId = taskId,
            resetGeneration = current.resetGeneration,
            labelCode = resolved.labelCode,
            originalRawCode = resolved.originalRawCode,
            skuCodesJson = json.encodeToString(resolved.skuCodes),
            skuItemsJson = json.encodeToString(resolved.skuItems),
            sequenceNo = maxSequenceNo(userId, namespaceId, taskId) + 1,
            productCode = resolved.productCode,
            productName = resolved.productName,
            resolutionSource = resolved.resolutionSource,
            duplicateConfirmed = duplicate,
            outcome = if (duplicate) "duplicate_confirmed" else "accepted",
            scannedAt = scannedAt,
        )
        insertEntry(entry)
        touchTask(userId, namespaceId, taskId, scannedAt)
        return ProductCountScanResult.Accepted(
            entry = entry,
            boxCount = acceptedCount(userId, namespaceId, taskId, current.resetGeneration),
        )
    }
}

@Serializable
data class ProductCountingOperationPayload(
    @SerialName("client_task_id") val clientTaskId: String,
    @SerialName("client_scan_id") val clientScanId: String = "",
    @SerialName("reset_generation") val resetGeneration: Int = 0,
    val revision: Long = 0,
    @SerialName("raw_code") val rawCode: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("sku_codes") val skuCodes: List<String> = emptyList(),
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("duplicate_confirmed") val duplicateConfirmed: Boolean = false,
    @SerialName("occurred_at") val occurredAt: Long,
)

@Serializable
data class ProductCountUploadEntry(
    @SerialName("client_scan_id") val clientScanId: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("reset_generation") val resetGeneration: Int,
    @SerialName("label_code") val labelCode: String,
    @SerialName("raw_code") val rawCode: String,
    @SerialName("sku_items") val skuItems: List<ProductCountSkuItem>,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    @SerialName("duplicate_confirmed") val duplicateConfirmed: Boolean,
    @SerialName("resolution_source") val resolutionSource: String,
    @SerialName("scanned_at_ms") val scannedAt: Long,
)

@Serializable
data class ProductCountUploadRequest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("upload_id") val uploadId: String,
    @SerialName("client_task_id") val clientTaskId: String,
    @SerialName("base_revision") val baseRevision: Long,
    @SerialName("reset_generation") val resetGeneration: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    @SerialName("task_started_at_ms") val createdAt: Long,
    @SerialName("task_completed_at_ms") val completedAt: Long,
    val entries: List<ProductCountUploadEntry>,
)

@Serializable
data class ProductCountEntryValidationDto(
    @SerialName("client_scan_id") val clientScanId: String,
    val status: String = "accepted",
    val message: String = "",
)

@Serializable
data class ProductCountUploadWarning(
    val code: String = "",
    val message: String = "",
    @SerialName("client_scan_id") val clientScanId: String = "",
    @SerialName("sku_code") val skuCode: String = "",
)

@Serializable
data class ProductCountUploadResponse(
    @SerialName("task_id") val taskId: Long = 0,
    @SerialName("server_revision") val serverRevision: Long = 0,
    @SerialName("field_box_count") val fieldBoxCount: Int = 0,
    @SerialName("validation_warnings") val validationWarnings: List<ProductCountUploadWarning> = emptyList(),
    @SerialName("entry_validations") val entryValidations: List<ProductCountEntryValidationDto> = emptyList(),
)

@Serializable
private data class ProductCountReopenRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("base_revision") val baseRevision: Long,
)

@Serializable
private data class ProductCountTaskPage(
    val items: List<ProductCountTaskDto> = emptyList(),
)

@Serializable
private data class ProductCountTaskDetailDto(
    val task: ProductCountTaskDto,
    val entries: List<ProductCountEntryDto> = emptyList(),
)

@Serializable
private data class ProductCountTaskDto(
    val id: Long = 0,
    @SerialName("client_task_id") val clientTaskId: String,
    val status: String = ProductCountTaskStatus.InProgress.value,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("reset_generation") val resetGeneration: Int = 0,
    val revision: Long = 0,
    @SerialName("editor_device_id") val editorDeviceId: String = "",
    @SerialName("box_count") val boxCount: Int = 0,
    @SerialName("created_at_ms") val createdAt: Long = 0,
    @SerialName("updated_at_ms") val updatedAt: Long = 0,
    @SerialName("completed_at_ms") val completedAt: Long = 0,
)

@Serializable
private data class ProductCountEntryDto(
    @SerialName("client_scan_id") val clientScanId: String,
    @SerialName("client_task_id") val clientTaskId: String,
    @SerialName("reset_generation") val resetGeneration: Int,
    @SerialName("label_code") val labelCode: String,
    @SerialName("raw_code") val rawCode: String = "",
    @SerialName("sku_codes") val skuCodes: List<String> = emptyList(),
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String = "",
    @SerialName("duplicate_confirmed") val duplicateConfirmed: Boolean = false,
    @SerialName("scanned_at_ms") val scannedAt: Long = 0,
)

@Serializable
private data class SkuProductIndexPage(
    val items: List<SkuProductIndexItem> = emptyList(),
    @SerialName("next_after_id") val nextAfterId: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
private data class SkuProductIndexItem(
    val id: Long = 0,
    @SerialName("sku_code") val skuCode: String,
    @SerialName("product_id") val productId: Long = 0,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Singleton
class ProductCountingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val deviceIdentityManager: DeviceIdentityManager,
    private val dao: ProductCountingDao,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
    private val cartonRepository: CartonRepository,
    private val productRepository: ProductRepository,
    @param:FscmJson private val json: Json,
) {
    private val scanMutex = Mutex()

    fun isCurrentEditor(task: ProductCountTaskSummary): Boolean =
        task.task.editorDeviceId == deviceIdentityManager.deviceId()

    suspend fun observeTasks(): Flow<List<ProductCountTaskSummary>> {
        val scope = scope()
        return dao.observeTasks(scope.userId, scope.namespaceId)
    }

    suspend fun observeTask(taskId: String): Flow<ProductCountTaskSummary?> {
        val scope = scope()
        return dao.observeTask(scope.userId, scope.namespaceId, taskId)
    }

    suspend fun observeEntries(taskId: String, generation: Int): Flow<List<ProductCountEntryEntity>> {
        val scope = scope()
        return dao.observeEntries(scope.userId, scope.namespaceId, taskId, generation)
    }

    suspend fun createTask(): String {
        val scope = scope()
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        val deviceId = deviceIdentityManager.deviceId()
        dao.upsertTask(
            ProductCountTaskEntity(
                clientTaskId = taskId,
                userId = scope.userId,
                namespaceId = scope.namespaceId,
                editorDeviceId = deviceId,
                createdAt = now,
                updatedAt = now,
            ),
        )

        return taskId
    }

    suspend fun scan(taskId: String, rawCode: String): ProductCountScanResult = scanMutex.withLock {
        val resolved = resolveLabel(rawCode)
        accept(taskId, resolved, duplicateConfirmed = false)
    }

    suspend fun confirmDuplicate(taskId: String, resolved: ProductCountResolvedLabel): ProductCountScanResult =
        scanMutex.withLock { accept(taskId, resolved, duplicateConfirmed = true) }

    suspend fun reset(taskId: String): Boolean {
        val scope = scope()
        val task = dao.task(scope.userId, scope.namespaceId, taskId) ?: return false
        val now = System.currentTimeMillis()
        val changed = dao.resetTask(scope.userId, scope.namespaceId, taskId, deviceIdentityManager.deviceId(), now) > 0

        return changed
    }

    suspend fun complete(taskId: String): Boolean = updateTaskStatus(taskId, ProductCountTaskStatus.Completed)

    suspend fun reopen(taskId: String): Boolean {
        val scope = scope()
        val task = dao.task(scope.userId, scope.namespaceId, taskId) ?: return false
        if (task.uploadStatus == ProductCountUploadStatus.Uploading.value) return false
        if (task.remoteId == 0L) return updateTaskStatus(taskId, ProductCountTaskStatus.InProgress)
        if (sessionStore.current().isOffline) throw ProductCountingOnlineRequiredException()
        val response: ProductCountTaskDto = api.post(
            "mobile/app/product-counting/tasks/$taskId/reopen",
            json.encodeToJsonElement(ProductCountReopenRequest(deviceIdentityManager.deviceId(), task.serverRevision)),
        )
        dao.upsertTask(response.toEntity(scope, System.currentTimeMillis()).copy(uploadStatus = ProductCountUploadStatus.Local.value))
        return true
    }

    suspend fun upload(taskId: String): ProductCountUploadResponse {
        val scope = scope()
        val task = dao.task(scope.userId, scope.namespaceId, taskId) ?: error("Product counting task is unavailable")
        check(task.status == ProductCountTaskStatus.Completed.value) { "Complete the task before uploading" }
        check(task.uploadStatus != ProductCountUploadStatus.Uploading.value) { "Upload is already in progress" }
        val uploadId = task.lastUploadId.ifBlank { UUID.randomUUID().toString() }
        val entries = dao.allEntries(scope.userId, scope.namespaceId, taskId)
        val request = ProductCountUploadRequest(
            schemaVersion = 1,
            uploadId = uploadId,
            clientTaskId = task.clientTaskId,
            baseRevision = task.serverRevision,
            resetGeneration = task.resetGeneration,
            deviceId = deviceIdentityManager.deviceId(),
            productCode = task.productCode,
            productName = task.productName,
            createdAt = task.createdAt,
            completedAt = task.completedAt,
            entries = entries.map { entry ->
                ProductCountUploadEntry(
                    clientScanId = entry.clientScanId,
                    sequenceNo = entry.sequenceNo,
                    resetGeneration = entry.resetGeneration,
                    labelCode = entry.labelCode,
                    rawCode = entry.originalRawCode,
                    skuItems = runCatching { json.decodeFromString<List<ProductCountSkuItem>>(entry.skuItemsJson) }.getOrDefault(emptyList()),
                    productCode = entry.productCode,
                    productName = entry.productName,
                    duplicateConfirmed = entry.duplicateConfirmed,
                    resolutionSource = entry.resolutionSource,
                    scannedAt = entry.scannedAt,
                )
            },
        )
        val now = System.currentTimeMillis()
        dao.updateUploadState(scope.userId, scope.namespaceId, taskId, ProductCountUploadStatus.Uploading.value, uploadId, "", now)
        return try {
            val response: ProductCountUploadResponse = api.post(
                "mobile/app/product-counting/tasks/$taskId/uploads",
                json.encodeToJsonElement(request),
            )
            val uploadStatus = if (response.validationWarnings.isEmpty()) {
                ProductCountUploadStatus.Uploaded.value
            } else {
                ProductCountUploadStatus.UploadedWithWarnings.value
            }
            dao.markUploaded(scope.userId, scope.namespaceId, taskId, response.taskId, response.serverRevision, uploadStatus, System.currentTimeMillis())
            response.entryValidations.forEach { validation ->
                dao.updateEntryValidation(validation.clientScanId, validation.status, validation.message)
            }
            response
        } catch (error: Throwable) {
            dao.updateUploadState(scope.userId, scope.namespaceId, taskId, ProductCountUploadStatus.Failed.value, uploadId, error.message.orEmpty().take(300), System.currentTimeMillis())
            throw error
        }
    }

    suspend fun refresh() {
        val session = sessionStore.current()
        if (session.isOffline) return
        runCatching {
            val page: ProductCountTaskPage = api.get("mobile/app/product-counting/tasks", mapOf("limit" to "100"))
            mergeRemoteTasks(page.items)
        }
        refreshSkuIndexIfStale()
    }

    suspend fun refreshTask(taskId: String) {
        val session = sessionStore.current()
        if (session.isOffline) return
        runCatching {
            val detail: ProductCountTaskDetailDto = api.get("mobile/app/product-counting/tasks/$taskId")
            mergeRemoteTasks(listOf(detail.task))
            mergeRemoteEntries(detail.entries)
        }
    }

    suspend fun refreshSkuIndexIfStale(force: Boolean = false) {
        val scope = scope()
        val session = sessionStore.current()
        val latest = dao.latestSkuIndexSync(scope.userId, scope.namespaceId) ?: 0L
        if (session.isOffline || (!force && latest > 0 && System.currentTimeMillis() - latest < SKU_INDEX_REFRESH_INTERVAL_MS)) return
        val rows = mutableListOf<SkuProductIndexItem>()
        var afterId = 0L
        runCatching {
            do {
                val page: SkuProductIndexPage = api.get(
                    "mobile/app/product-counting/sku-product-index/snapshot",
                    mapOf("after_id" to afterId.toString(), "limit" to "500"),
                )
                rows += page.items
                afterId = page.nextAfterId
            } while (page.hasMore)
            val syncedAt = System.currentTimeMillis()
            dao.upsertSkuMappings(rows.map { it.toEntity(scope, syncedAt) })
        }
    }

    private suspend fun accept(
        taskId: String,
        resolved: ProductCountResolvedLabel,
        duplicateConfirmed: Boolean,
    ): ProductCountScanResult {
        val scope = scope()
        val result = dao.acceptResolvedLabel(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            taskId = taskId,
            deviceId = deviceIdentityManager.deviceId(),
            resolved = resolved,
            duplicateConfirmed = duplicateConfirmed,
            scannedAt = System.currentTimeMillis(),
            json = json,
        )
        return result
    }

    private suspend fun updateTaskStatus(taskId: String, status: ProductCountTaskStatus): Boolean {
        val scope = scope()
        val task = dao.task(scope.userId, scope.namespaceId, taskId) ?: return false
        val now = System.currentTimeMillis()
        val changed = dao.updateStatus(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            taskId = taskId,
            status = status.value,
            deviceId = deviceIdentityManager.deviceId(),
            completedAt = if (status == ProductCountTaskStatus.Completed) now else 0,
            updatedAt = now,
        ) > 0
        return changed
    }

    private suspend fun resolveLabel(rawCode: String): ProductCountResolvedLabel {
        val raw = rawCode.trimScannerInput()
        require(raw.isNotBlank()) { "箱唛内容为空" }
        val scope = scope()
        val parsed = boxLabelMappingRepository.resolve(raw)
        val mapping = boxLabelMappingRepository.cachedMapping(raw)
        val localSkuCodes = (parsed?.items.orEmpty().map(BoxLabelQrItem::skuCode) + listOf(mapping?.skuCode.orEmpty()))
            .normalizeSkuCodes()
        val canonical = mapping?.supplierLabelCode.orEmpty().ifBlank { parsed?.labelCode.orEmpty() }.normalizeLabelCode()
        if (mapping != null && mapping.productCode.isNotBlank() && canonical.isNotBlank()) {
            cacheSkuMappings(scope, localSkuCodes, mapping.productCode, mapping.productName)
            return ProductCountResolvedLabel(
                labelCode = canonical,
                originalRawCode = raw,
                skuCodes = localSkuCodes,
                productCode = mapping.productCode.normalizeProductCode(),
                productName = mapping.productName,
                resolutionSource = "mapping_cache",
                skuItems = parsed?.items.orEmpty().map { item ->
                    ProductCountSkuItem(item.skuCode.normalizeSkuCode(), item.quantity.takeIf { it > 0 }, "qr")
                }.ifEmpty {
                    listOfNotNull(
                        mapping.skuCode.normalizeSkuCode().takeIf(String::isNotBlank)?.let { skuCode ->
                            ProductCountSkuItem(skuCode, mapping.qtyPerCarton.takeIf { it > 0 }, "mapping_cache")
                        },
                    )
                },
            )
        }
        resolveProductFromSkuIndex(scope, canonical, raw, localSkuCodes)?.let { return it }

        val result = cartonRepository.resolveBoxLabelResult(raw)
        val label = result.data
        val resolvedSkuCodes = (localSkuCodes + label.skuItems.map(BoxLabelSKUItem::skuCode) + label.skuCode.split(','))
            .normalizeSkuCodes()
        var productCode = label.productCode.normalizeProductCode()
        var productName = label.productName
        if (productCode.isBlank() && resolvedSkuCodes.isNotEmpty()) {
            val products = runCatching {
                resolvedSkuCodes.map { skuCode ->
                    resolveProductSku(skuCode)
                }
            }.getOrElse { error ->
                if (sessionStore.current().isOffline) throw ProductCountingOfflineMappingMissingException()
                throw error
            }
            val productCodes = products.map(Product::code).filter(String::isNotBlank).map(String::normalizeProductCode).distinct()
            if (productCodes.size > 1) throw ProductCountingMixedProductException()
            productCode = productCodes.singleOrNull().orEmpty()
            productName = products.firstOrNull { it.code.isNotBlank() }?.name.orEmpty()
        }
        val resolvedCanonical = label.labelCode.ifBlank { canonical }.normalizeLabelCode()
        if (!label.recognized || resolvedCanonical.isBlank()) throw ProductCountingUnrecognizedException()
        if (productCode.isBlank()) {
            if (sessionStore.current().isOffline) throw ProductCountingOfflineMappingMissingException()
            throw ProductCountingProductMissingException()
        }
        cacheSkuMappings(scope, resolvedSkuCodes, productCode, productName)
        return ProductCountResolvedLabel(
            labelCode = resolvedCanonical,
            originalRawCode = raw,
            skuCodes = resolvedSkuCodes,
            productCode = productCode,
            productName = productName,
            resolutionSource = result.source.name.lowercase(Locale.ROOT),
            skuItems = label.skuItems.map { item ->
                ProductCountSkuItem(item.skuCode.normalizeSkuCode(), item.qtyPerBox.takeIf { it > 0 }, "center")
            }.ifEmpty {
                parsed?.items.orEmpty().map { item ->
                    ProductCountSkuItem(item.skuCode.normalizeSkuCode(), item.quantity.takeIf { it > 0 }, "qr")
                }
            },
        )
    }

    /**
     * Some handheld labels add a T prefix that is not stored in the central SKU master.
     * Keep the scanned code intact for field records and retry only the product lookup.
     */
    private suspend fun resolveProductSku(skuCode: String): Product {
        val lookupCode = skuCode.dropScannerSkuPrefix()
        return runCatching { productRepository.sku(skuCode) }
            .recoverCatching { error ->
                if (lookupCode == skuCode) throw error
                productRepository.sku(lookupCode)
            }.getOrThrow()
            .let { it.product ?: Product(id = it.productId) }
    }
    private suspend fun resolveProductFromSkuIndex(
        scope: ProductCountingScope,
        labelCode: String,
        rawCode: String,
        skuCodes: List<String>,
    ): ProductCountResolvedLabel? {
        if (labelCode.isBlank() || skuCodes.isEmpty()) return null
        val mappings = dao.skuMappings(scope.userId, scope.namespaceId, skuCodes)
        if (mappings.size != skuCodes.size) return null
        val products = mappings.map { it.productCode.normalizeProductCode() }.filter(String::isNotBlank).distinct()
        if (products.size > 1) throw ProductCountingMixedProductException()
        val productCode = products.singleOrNull() ?: return null
        return ProductCountResolvedLabel(
            labelCode = labelCode,
            originalRawCode = rawCode,
            skuCodes = skuCodes,
            productCode = productCode,
            productName = mappings.firstOrNull { it.productName.isNotBlank() }?.productName.orEmpty(),
            resolutionSource = "sku_index",
            skuItems = skuCodes.map { skuCode -> ProductCountSkuItem(skuCode, null, "unknown") },
        )
    }

    private suspend fun cacheSkuMappings(
        scope: ProductCountingScope,
        skuCodes: List<String>,
        productCode: String,
        productName: String,
    ) {
        if (skuCodes.isEmpty() || productCode.isBlank()) return
        val now = System.currentTimeMillis()
        dao.upsertSkuMappings(
            skuCodes.map {
                SkuProductIndexEntity(
                    userId = scope.userId,
                    namespaceId = scope.namespaceId,
                    skuCode = it,
                    productCode = productCode.normalizeProductCode(),
                    productName = productName,
                    syncedAt = now,
                )
            },
        )
    }

    private suspend fun mergeRemoteTasks(remote: List<ProductCountTaskDto>) {
        val scope = scope()
        val now = System.currentTimeMillis()
        remote.forEach { dto ->
            val local = dao.task(scope.userId, scope.namespaceId, dto.clientTaskId)
            if (local == null || local.syncStatus == ProductCountSyncStatus.Synced.value || dto.revision >= local.revision) {
                dao.upsertTask(dto.toEntity(scope, now))
            }
        }
    }

    private suspend fun mergeRemoteEntries(remote: List<ProductCountEntryDto>) {
        val scope = scope()
        dao.upsertEntries(remote.map { it.toEntity(scope, json) })
    }

    private suspend fun scope(): ProductCountingScope {
        val session = sessionStore.current()
        val userId = session.user?.id ?: 0
        val namespaceId = session.currentNamespace?.id ?: 0
        check(userId > 0 && namespaceId > 0) { "产品清点需要有效的用户和 namespace" }
        return ProductCountingScope(userId, namespaceId)
    }

    private data class ProductCountingScope(val userId: Long, val namespaceId: Long)

    private fun ProductCountTaskDto.toEntity(scope: ProductCountingScope, now: Long) = ProductCountTaskEntity(
        clientTaskId = clientTaskId,
        userId = scope.userId,
        namespaceId = scope.namespaceId,
        remoteId = id,
        status = status,
        productCode = productCode.normalizeProductCode(),
        productName = productName,
        resetGeneration = resetGeneration,
        revision = revision,
        editorDeviceId = editorDeviceId,
        serverCount = boxCount,
        syncStatus = ProductCountSyncStatus.Synced.value,
        createdAt = createdAt.takeIf { it > 0 } ?: now,
        updatedAt = updatedAt.takeIf { it > 0 } ?: now,
        completedAt = completedAt,
    )

    private fun ProductCountEntryDto.toEntity(scope: ProductCountingScope, json: Json) = ProductCountEntryEntity(
        clientScanId = clientScanId,
        userId = scope.userId,
        namespaceId = scope.namespaceId,
        taskClientId = clientTaskId,
        resetGeneration = resetGeneration,
        labelCode = labelCode.normalizeLabelCode(),
        originalRawCode = rawCode,
        skuCodesJson = json.encodeToString(skuCodes.normalizeSkuCodes()),
        productCode = productCode.normalizeProductCode(),
        productName = productName,
        resolutionSource = "center",
        duplicateConfirmed = duplicateConfirmed,
        outcome = if (duplicateConfirmed) "duplicate_confirmed" else "accepted",
        syncStatus = ProductCountSyncStatus.Synced.value,
        scannedAt = scannedAt,
    )

    private fun SkuProductIndexItem.toEntity(scope: ProductCountingScope, syncedAt: Long) = SkuProductIndexEntity(
        userId = scope.userId,
        namespaceId = scope.namespaceId,
        skuCode = skuCode.normalizeSkuCode(),
        productId = productId,
        productCode = productCode.normalizeProductCode(),
        productName = productName,
        remoteUpdatedAt = updatedAt,
        syncedAt = syncedAt,
    )

    private companion object {
        const val SKU_INDEX_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}

class ProductCountingOfflineMappingMissingException : IllegalStateException("缺少离线产品信息，请联网后重试")
class ProductCountingMixedProductException : IllegalArgumentException("箱唛包含多个产品，不能用于单产品清点")
class ProductCountingUnrecognizedException : IllegalArgumentException("无法识别箱唛，请检查后重试")
class ProductCountingProductMissingException : IllegalArgumentException("箱唛缺少产品归属信息")

class ProductCountingOnlineRequiredException : IllegalStateException("A network connection is required")
private fun String.trimScannerInput(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
private fun String.normalizeLabelCode(): String = trimScannerInput().uppercase(Locale.ROOT)
private fun String.normalizeSkuCode(): String = trim().uppercase(Locale.ROOT)
private fun String.dropScannerSkuPrefix(): String = normalizeSkuCode().let { normalized ->
    if (
        normalized.length > 1 &&
        normalized.startsWith('T') &&
        normalized[1].isLetterOrDigit()
    ) {
        normalized.drop(1)
    } else {
        normalized
    }
}
private fun String.normalizeProductCode(): String = trim().uppercase(Locale.ROOT)
private fun List<String>.normalizeSkuCodes(): List<String> = map(String::normalizeSkuCode).filter(String::isNotBlank).distinct()
