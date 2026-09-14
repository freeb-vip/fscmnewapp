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
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ReceivingProductBusiness(val value: String) {
    Warehouse("warehouse"),
    Central("central"),
    Inbound("inbound"),
}

enum class ReceivingProductWorkStatus(val value: String) {
    Pending("pending"),
    Completed("completed"),
    ClosedWithException("closed_with_exception"),
}

data class ReceivingProductProgress(
    val business: ReceivingProductBusiness,
    val documentId: Long,
    val productKey: String,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val productImage: String,
    val skuCount: Int,
    val skuCodes: List<String>,
    val sourceItemIds: List<Long>,
    val boxPlanIds: List<Long>,
    val totalBoxCount: Int,
    val completedBoxCount: Int,
    val pendingBoxCount: Int,
    val exceptionBoxCount: Int,
    val workStatus: String,
    val lastOperatedAt: Long,
    val draftBoxCount: Int = 0,
    val pendingSyncBoxCount: Int = 0,
)

data class ReceivingProductProgressSnapshot(
    val items: List<ReceivingProductProgress> = emptyList(),
    val complete: Boolean = false,
    val updatedAt: Long = 0,
    val inboundBoxes: List<ReceivingProductBoxDetail> = emptyList(),
)

data class ReceivingProductDocumentHeader(
    val code: String = "",
    val warehouse: String = "",
    val status: String = "",
    val completedBoxCount: Int = 0,
    val totalBoxCount: Int = 0,
    val pendingBoxCount: Int = 0,
    val exceptionBoxCount: Int = 0,
    val localPendingCount: Int = 0,
    val draftBoxCount: Int = 0,
)

data class ReceivingProductBoxDetail(
    val id: String,
    val labelCode: String,
    val skuCode: String,
    val status: String,
    val locationCode: String = "",
    val operator: String = "",
    val operatedAt: Long = 0,
    val exceptionReason: String = "",
    val localPending: Boolean = false,
    val skuName: String = "",
    val boxNo: String = "",
    val isMixed: Boolean = false,
    val collected: Boolean = false,
    val eligible: Boolean = true,
)

@Entity(
    tableName = "receiving_product_progress_cache",
    primaryKeys = ["userId", "namespaceId", "businessType", "documentId", "productKey"],
    indices = [Index(value = ["userId", "namespaceId", "businessType", "documentId", "pendingBoxCount"])],
)
data class ReceivingProductProgressEntity(
    val userId: Long,
    val namespaceId: Long,
    val businessType: String,
    val documentId: Long,
    val productKey: String,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val productImage: String,
    val skuCount: Int,
    val skuCodesJson: String,
    val sourceItemIdsJson: String,
    val boxPlanIdsJson: String,
    val totalBoxCount: Int,
    val completedBoxCount: Int,
    val pendingBoxCount: Int,
    val exceptionBoxCount: Int,
    val workStatus: String,
    val lastOperatedAt: Long,
)

@Entity(
    tableName = "receiving_product_progress_meta",
    primaryKeys = ["userId", "namespaceId", "businessType", "documentId"],
)
data class ReceivingProductProgressMetaEntity(
    val userId: Long,
    val namespaceId: Long,
    val businessType: String,
    val documentId: Long,
    val complete: Boolean,
    val updatedAt: Long,
)

@Dao
interface ReceivingProductProgressDao {
    @Query(
        "SELECT * FROM receiving_product_progress_cache WHERE userId = :userId AND namespaceId = :namespaceId " +
            "AND businessType = :businessType AND documentId = :documentId ORDER BY exceptionBoxCount DESC, pendingBoxCount DESC, lastOperatedAt DESC, productCode ASC",
    )
    fun observeItems(
        userId: Long,
        namespaceId: Long,
        businessType: String,
        documentId: Long,
    ): Flow<List<ReceivingProductProgressEntity>>

    @Query(
        "SELECT * FROM receiving_product_progress_meta WHERE userId = :userId AND namespaceId = :namespaceId " +
            "AND businessType = :businessType AND documentId = :documentId LIMIT 1",
    )
    fun observeMeta(
        userId: Long,
        namespaceId: Long,
        businessType: String,
        documentId: Long,
    ): Flow<ReceivingProductProgressMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ReceivingProductProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: ReceivingProductProgressMetaEntity)

    @Query(
        "DELETE FROM receiving_product_progress_cache WHERE userId = :userId AND namespaceId = :namespaceId " +
            "AND businessType = :businessType AND documentId = :documentId",
    )
    suspend fun deleteItems(userId: Long, namespaceId: Long, businessType: String, documentId: Long)

    @Transaction
    suspend fun replace(
        meta: ReceivingProductProgressMetaEntity,
        items: List<ReceivingProductProgressEntity>,
    ) {
        deleteItems(meta.userId, meta.namespaceId, meta.businessType, meta.documentId)
        if (items.isNotEmpty()) insertItems(items)
        upsertMeta(meta)
    }
}

@Serializable
private data class ReceivingProductProgressDto(
    @SerialName("product_key") val productKey: String = "",
    @SerialName("product_id") val productId: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("product_image") val productImage: String = "",
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("sku_codes") val skuCodes: List<String> = emptyList(),
    @SerialName("source_item_ids") val sourceItemIds: List<Long> = emptyList(),
    @SerialName("box_plan_ids") val boxPlanIds: List<Long> = emptyList(),
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("completed_box_count") val completedBoxCount: Int = 0,
    @SerialName("pending_box_count") val pendingBoxCount: Int = 0,
    @SerialName("exception_box_count") val exceptionBoxCount: Int = 0,
    @SerialName("work_status") val workStatus: String = ReceivingProductWorkStatus.Pending.value,
    @SerialName("last_operated_at") val lastOperatedAt: String = "",
)

@Serializable
private data class ReceivingProductProgressPageDto(
    val items: List<ReceivingProductProgressDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 100,
)

@Singleton
class ReceivingProductProgressRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val dao: ReceivingProductProgressDao,
    private val receivingRepository: ReceivingRepository,
    private val centralReceivingRepository: CentralReceivingRepository,
    private val localInboundRepository: LocalInboundRepository,
    private val localReceivingDao: LocalReceivingDao,
    @param:FscmJson private val json: Json,
) {
    fun observe(
        business: ReceivingProductBusiness,
        documentId: Long,
    ): Flow<ReceivingProductProgressSnapshot> = sessionStore.session.flatMapLatest { session ->
        val userId = session.user?.id ?: 0
        val namespaceId = session.currentNamespace?.id ?: 0
        if (userId <= 0 || namespaceId <= 0) {
            flowOf(ReceivingProductProgressSnapshot())
        } else if (business == ReceivingProductBusiness.Inbound) {
            flow {
                emitAll(
                    localInboundRepository.observeProductCache(documentId).map { cache ->
                        val boxes = inboundCollectionBoxes(cache.snapshotItems, cache.collectionItems)
                        ReceivingProductProgressSnapshot(
                            items = collectionProducts(documentId, boxes),
                            inboundBoxes = boxes.map { it.toProductBox() },
                            complete = cache.complete,
                            updatedAt = cache.updatedAt,
                        )
                    }.flowOn(Dispatchers.Default),
                )
            }
        } else {
            combine(
                dao.observeItems(userId, namespaceId, business.value, documentId),
                dao.observeMeta(userId, namespaceId, business.value, documentId),
            ) { items, meta ->
                ReceivingProductProgressSnapshot(
                    items = items.map { it.toDomain(json) },
                    complete = meta?.complete == true,
                    updatedAt = meta?.updatedAt ?: 0,
                )
            }
        }
    }

    suspend fun currentScopeKey(): Pair<Long, Long> = localInboundRepository.currentScopeKey()

    suspend fun refresh(business: ReceivingProductBusiness, documentId: Long, onlyIfStale: Boolean = false): Boolean {
        if (business == ReceivingProductBusiness.Inbound) {
            if (onlyIfStale) localInboundRepository.refreshSessionIfStale(documentId) else localInboundRepository.refreshProductCache(documentId)
            return true
        }
        val session = sessionStore.current()
        val userId = session.user?.id ?: return false
        val namespaceId = session.currentNamespace?.id ?: return false
        if (session.isOffline) return false
        val path = when (business) {
            ReceivingProductBusiness.Warehouse -> "receiving-sessions/$documentId/receiving-products"
            ReceivingProductBusiness.Central -> "central-receipts/$documentId/products"
            ReceivingProductBusiness.Inbound -> return false
        }
        val rows = mutableListOf<ReceivingProductProgressDto>()
        var page = 1
        return runCatching {
            do {
                val response = api.get<ReceivingProductProgressPageDto>(
                    path,
                    mapOf("page" to page.toString(), "pageSize" to "100", "state" to "all"),
                )
                rows += response.items
                page++
            } while (rows.size < response.total)
            val now = System.currentTimeMillis()
            dao.replace(
                ReceivingProductProgressMetaEntity(userId, namespaceId, business.value, documentId, true, now),
                rows.map { it.toEntity(userId, namespaceId, business, documentId, json) },
            )
            true
        }.getOrElse { error ->
            if (error is FscmApiException && error.statusCode in setOf(404, 405)) {
                cacheFallback(userId, namespaceId, business, documentId)
            } else {
                false
            }
        }
    }

    suspend fun documentHeader(
        business: ReceivingProductBusiness,
        documentId: Long,
    ): ReceivingProductDocumentHeader = when (business) {
        ReceivingProductBusiness.Warehouse -> {
            val session = receivingRepository.session(documentId)
            val scope = sessionStore.current()
            ReceivingProductDocumentHeader(
                code = session.code,
                warehouse = listOf(session.warehouseCode, session.warehouseName).filter(String::isNotBlank).joinToString(" / "),
                status = session.status,
                completedBoxCount = session.scannedBoxCount,
                totalBoxCount = session.expectedBoxCount,
                pendingBoxCount = session.pendingBoxCount,
                exceptionBoxCount = session.exceptionBoxCount,
                localPendingCount = localReceivingDao.pendingCount(scope.user?.id ?: 0, scope.currentNamespace?.id ?: 0, documentId),
            )
        }
        ReceivingProductBusiness.Central -> {
            val receipt = centralReceivingRepository.receipt(documentId)
            val stats = centralReceivingRepository.statistics(documentId)
            val localPending = centralReceivingRepository.pendingScans().count { it.centralReceiptId == documentId && it.syncStatus !in setOf("received", "duplicate") }
            ReceivingProductDocumentHeader(
                code = receipt.code,
                warehouse = listOf(receipt.warehouseCode, receipt.warehouseName).filter(String::isNotBlank).joinToString(" / "),
                status = receipt.status,
                completedBoxCount = stats.receivedBoxCount,
                totalBoxCount = stats.totalBoxCount,
                pendingBoxCount = stats.pendingBoxCount,
                exceptionBoxCount = stats.exceptionBoxCount,
                localPendingCount = localPending,
            )
        }
        ReceivingProductBusiness.Inbound -> {
            val session = localInboundRepository.cachedSession(documentId)
            ReceivingProductDocumentHeader(
                code = session?.sessionCode.orEmpty(),
                warehouse = listOfNotNull(session?.warehouseCode, session?.warehouseName).filter(String::isNotBlank).joinToString(" / "),
                status = session?.status.orEmpty(),
            )
        }
    }

    suspend fun boxDetails(product: ReceivingProductProgress): List<ReceivingProductBoxDetail> = when (product.business) {
        ReceivingProductBusiness.Warehouse -> receivingRepository.session(product.documentId).scanItems
            .filter { it.consolidationOrderItemId in product.sourceItemIds || it.skuCode in product.skuCodes }
            .map { item ->
                ReceivingProductBoxDetail(
                    id = "warehouse-${item.id}",
                    labelCode = item.labelCode.ifBlank { item.boxNo.ifBlank { item.boxUid } },
                    skuCode = item.skuCode,
                    status = item.status,
                    locationCode = item.locationCode,
                    operator = item.scannedBy,
                    operatedAt = item.scannedAt.toEpochMillis(),
                    exceptionReason = item.exceptionReason,
                    localPending = item.localFallback,
                )
            }
        ReceivingProductBusiness.Central -> {
            val sourceIds = product.sourceItemIds.toSet()
            centralReceivingRepository.boxLabels(product.documentId)
                .filter { it.centralReceiptItemId in sourceIds || it.skuCode in product.skuCodes }
                .map { item ->
                    ReceivingProductBoxDetail(
                        id = "central-${item.id}",
                        labelCode = item.labelCode.ifBlank { item.boxNo.ifBlank { item.boxUid } },
                        skuCode = item.skuCode,
                        status = item.status.ifBlank { item.signStatus },
                        operatedAt = item.centralReceivedAt.toEpochMillis(),
                        localPending = item.status.endsWith("_local"),
                    )
                }
        }
        ReceivingProductBusiness.Inbound -> localInboundRepository.workspace(product.documentId).collectionBoxes
            .filter { it.snapshot.boxPlanId in product.boxPlanIds }.map { it.toProductBox() }
    }

    private suspend fun cacheFallback(
        userId: Long,
        namespaceId: Long,
        business: ReceivingProductBusiness,
        documentId: Long,
    ): Boolean {
        val rows = when (business) {
            ReceivingProductBusiness.Warehouse -> warehouseFallback(receivingRepository.session(documentId), business, documentId)
            ReceivingProductBusiness.Central -> centralFallback(centralReceivingRepository.receipt(documentId), business, documentId)
            ReceivingProductBusiness.Inbound -> emptyList()
        }
        if (rows.isEmpty()) return false
        val now = System.currentTimeMillis()
        dao.replace(
            ReceivingProductProgressMetaEntity(userId, namespaceId, business.value, documentId, false, now),
            rows.map { it.toEntity(userId, namespaceId, json) },
        )
        return true
    }
}

private fun ReceivingProductProgressDto.toEntity(
    userId: Long,
    namespaceId: Long,
    business: ReceivingProductBusiness,
    documentId: Long,
    json: Json,
) = ReceivingProductProgressEntity(
    userId = userId,
    namespaceId = namespaceId,
    businessType = business.value,
    documentId = documentId,
    productKey = productKey.ifBlank { productId.takeIf { it > 0 }?.let { "id:$it" } ?: "legacy:${productCode.lowercase()}" },
    productId = productId,
    productCode = productCode,
    productName = productName,
    productImage = productImage,
    skuCount = skuCount,
    skuCodesJson = json.encodeToString(skuCodes),
    sourceItemIdsJson = json.encodeToString(sourceItemIds),
    boxPlanIdsJson = json.encodeToString(boxPlanIds),
    totalBoxCount = totalBoxCount,
    completedBoxCount = completedBoxCount,
    pendingBoxCount = pendingBoxCount,
    exceptionBoxCount = exceptionBoxCount,
    workStatus = workStatus,
    lastOperatedAt = lastOperatedAt.toEpochMillis(),
)

private fun ReceivingProductProgressEntity.toDomain(json: Json) = ReceivingProductProgress(
    business = ReceivingProductBusiness.entries.first { it.value == businessType },
    documentId = documentId,
    productKey = productKey,
    productId = productId,
    productCode = productCode,
    productName = productName,
    productImage = productImage,
    skuCount = skuCount,
    skuCodes = runCatching { json.decodeFromString<List<String>>(skuCodesJson) }.getOrDefault(emptyList()),
    sourceItemIds = runCatching { json.decodeFromString<List<Long>>(sourceItemIdsJson) }.getOrDefault(emptyList()),
    boxPlanIds = runCatching { json.decodeFromString<List<Long>>(boxPlanIdsJson) }.getOrDefault(emptyList()),
    totalBoxCount = totalBoxCount,
    completedBoxCount = completedBoxCount,
    pendingBoxCount = pendingBoxCount,
    exceptionBoxCount = exceptionBoxCount,
    workStatus = workStatus,
    lastOperatedAt = lastOperatedAt,
)

private fun ReceivingProductProgress.toEntity(userId: Long, namespaceId: Long, json: Json) =
    ReceivingProductProgressEntity(
        userId = userId,
        namespaceId = namespaceId,
        businessType = business.value,
        documentId = documentId,
        productKey = productKey,
        productId = productId,
        productCode = productCode,
        productName = productName,
        productImage = productImage,
        skuCount = skuCount,
        skuCodesJson = json.encodeToString(skuCodes),
        sourceItemIdsJson = json.encodeToString(sourceItemIds),
        boxPlanIdsJson = json.encodeToString(boxPlanIds),
        totalBoxCount = totalBoxCount,
        completedBoxCount = completedBoxCount,
        pendingBoxCount = pendingBoxCount,
        exceptionBoxCount = exceptionBoxCount,
        workStatus = workStatus,
        lastOperatedAt = lastOperatedAt,
    )

private fun warehouseFallback(
    session: ReceivingSession,
    business: ReceivingProductBusiness,
    documentId: Long,
): List<ReceivingProductProgress> = session.tallyItems.groupBy { it.productCode.ifBlank { it.skuCode }.lowercase() }.map { (key, items) ->
    val total = items.sumOf { it.plannedBoxCount }
    val completed = items.sumOf { it.talliedBoxCount.coerceAtMost(it.plannedBoxCount) }
    ReceivingProductProgress(
        business = business,
        documentId = documentId,
        productKey = "legacy:$key",
        productId = 0,
        productCode = items.first().productCode,
        productName = items.first().productName,
        productImage = "",
        skuCount = items.map { it.skuCode }.filter(String::isNotBlank).distinct().size,
        skuCodes = items.map { it.skuCode }.filter(String::isNotBlank).distinct(),
        sourceItemIds = items.map { it.consolidationOrderItemId }.filter { it > 0 }.distinct(),
        boxPlanIds = emptyList(),
        totalBoxCount = total,
        completedBoxCount = completed,
        pendingBoxCount = (total - completed).coerceAtLeast(0),
        exceptionBoxCount = 0,
        workStatus = if (completed >= total) ReceivingProductWorkStatus.Completed.value else ReceivingProductWorkStatus.Pending.value,
        lastOperatedAt = session.localSyncedAt,
    )
}

private fun centralFallback(
    receipt: CentralReceipt,
    business: ReceivingProductBusiness,
    documentId: Long,
): List<ReceivingProductProgress> = receipt.items.groupBy { it.productCode.ifBlank { it.skuCode }.lowercase() }.map { (key, items) ->
    val total = items.sumOf { it.expectedBoxCount }
    val completed = items.sumOf { it.receivedBoxCount }
    ReceivingProductProgress(
        business = business,
        documentId = documentId,
        productKey = "legacy:$key",
        productId = 0,
        productCode = items.first().productCode,
        productName = items.first().productName,
        productImage = items.first().productImage,
        skuCount = items.map { it.skuCode }.filter(String::isNotBlank).distinct().size,
        skuCodes = items.map { it.skuCode }.filter(String::isNotBlank).distinct(),
        sourceItemIds = items.map { it.id },
        boxPlanIds = emptyList(),
        totalBoxCount = total,
        completedBoxCount = completed,
        pendingBoxCount = (total - completed).coerceAtLeast(0),
        exceptionBoxCount = 0,
        workStatus = if (completed >= total) ReceivingProductWorkStatus.Completed.value else ReceivingProductWorkStatus.Pending.value,
        lastOperatedAt = 0,
    )
}

private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0)
