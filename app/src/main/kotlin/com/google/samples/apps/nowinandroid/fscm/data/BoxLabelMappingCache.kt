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
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Entity(
    tableName = "local_box_label_mappings",
    primaryKeys = ["userId", "namespaceId", "mappingId"],
    indices = [
        Index(value = ["userId", "namespaceId", "supplierLabelCode"]),
        Index(value = ["userId", "namespaceId", "supplierBoxNo"]),
        Index(value = ["userId", "namespaceId", "supplierBoxUid"]),
        Index(value = ["userId", "namespaceId", "jifengBoxNo"]),
        Index(value = ["userId", "namespaceId", "jifengQrCodeText"]),
    ],
)
data class LocalBoxLabelMappingEntity(
    val userId: Long,
    val namespaceId: Long,
    val mappingId: Long,
    val supplierOrderBoxPlanId: Long,
    val supplierLabelCode: String,
    val supplierBoxNo: String,
    val supplierBoxUid: String,
    val jifengBoxNo: String,
    val jifengQrCodeText: String,
    val mappingStatus: String,
    val productCode: String,
    val productName: String,
    val skuCode: String,
    val manufacturerName: String,
    val caseSpecName: String,
    val qtyPerCarton: Int,
    val boxQty: Int,
    val remoteUpdatedAt: String,
    val syncedAt: Long,
)

@Dao
interface BoxLabelMappingDao {
    @Query(
        """
        SELECT * FROM local_box_label_mappings
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND (
            supplierLabelCode = :code COLLATE NOCASE OR supplierBoxNo = :code COLLATE NOCASE OR
            supplierBoxUid = :code COLLATE NOCASE OR jifengBoxNo = :code COLLATE NOCASE OR
            jifengQrCodeText = :rawCode
          )
        ORDER BY mappingId DESC LIMIT 1
        """,
    )
    suspend fun find(userId: Long, namespaceId: Long, code: String, rawCode: String): LocalBoxLabelMappingEntity?

    @Query("SELECT * FROM local_box_label_mappings WHERE userId = :userId AND namespaceId = :namespaceId AND (supplierLabelCode = :code COLLATE NOCASE OR supplierBoxNo = :code COLLATE NOCASE OR supplierBoxUid = :code COLLATE NOCASE OR jifengBoxNo = :code COLLATE NOCASE OR jifengQrCodeText = :rawCode) LIMIT 2")
    suspend fun captureMatches(userId: Long, namespaceId: Long, code: String, rawCode: String): List<LocalBoxLabelMappingEntity>

    @Query("SELECT * FROM local_box_label_mappings WHERE userId = :userId AND namespaceId = :namespaceId AND (supplierLabelCode COLLATE NOCASE IN (:codes) OR supplierBoxNo COLLATE NOCASE IN (:codes) OR supplierBoxUid COLLATE NOCASE IN (:codes) OR jifengBoxNo COLLATE NOCASE IN (:codes) OR jifengQrCodeText IN (:rawCodes)) ORDER BY mappingId DESC")
    suspend fun findMany(userId: Long, namespaceId: Long, codes: List<String>, rawCodes: List<String>): List<LocalBoxLabelMappingEntity>

    @Query("SELECT MAX(syncedAt) FROM local_box_label_mappings WHERE userId = :userId AND namespaceId = :namespaceId")
    suspend fun latestSyncedAt(userId: Long, namespaceId: Long): Long?

    @Query(
        """
        SELECT jifengBoxNo FROM local_box_label_mappings
        WHERE userId = :userId AND namespaceId = :namespaceId AND jifengBoxNo <> ''
        UNION
        SELECT jifengQrCodeText FROM local_box_label_mappings
        WHERE userId = :userId AND namespaceId = :namespaceId AND jifengQrCodeText <> ''
        """,
    )
    suspend fun jifengAliases(userId: Long, namespaceId: Long): List<String>

    @Query("DELETE FROM local_box_label_mappings WHERE userId = :userId AND namespaceId = :namespaceId")
    suspend fun deleteScope(userId: Long, namespaceId: Long)

    @Query(
        "DELETE FROM local_box_label_mappings WHERE userId = :userId AND namespaceId = :namespaceId AND supplierOrderBoxPlanId IN (:boxPlanIds)",
    )
    suspend fun deletePlans(userId: Long, namespaceId: Long, boxPlanIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LocalBoxLabelMappingEntity>)

    @Transaction
    suspend fun replaceScope(userId: Long, namespaceId: Long, rows: List<LocalBoxLabelMappingEntity>) {
        deleteScope(userId, namespaceId)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Transaction
    suspend fun replacePlans(
        userId: Long,
        namespaceId: Long,
        boxPlanIds: List<Long>,
        rows: List<LocalBoxLabelMappingEntity>,
    ) {
        if (boxPlanIds.isNotEmpty()) deletePlans(userId, namespaceId, boxPlanIds)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Serializable
data class BoxLabelMappingSnapshotItem(
    val id: Long,
    @SerialName("supplier_order_box_plan_id") val supplierOrderBoxPlanId: Long,
    @SerialName("supplier_label_code") val supplierLabelCode: String = "",
    @SerialName("supplier_box_no") val supplierBoxNo: String = "",
    @SerialName("supplier_box_uid") val supplierBoxUid: String = "",
    @SerialName("jifeng_box_no") val jifengBoxNo: String = "",
    @SerialName("jifeng_qr_code_text") val jifengQrCodeText: String = "",
    @SerialName("mapping_status") val mappingStatus: String = "",
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("manufacturer_name") val manufacturerName: String = "",
    @SerialName("case_spec_name") val caseSpecName: String = "",
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("box_qty") val boxQty: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
internal data class BoxLabelMappingSnapshot(
    val items: List<BoxLabelMappingSnapshotItem> = emptyList(),
    @SerialName("next_after_id") val nextAfterId: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

// Adjustment aliases have their own pagination stream and are not mapping rows.
// Only items/next_after_id/has_more control this cache's full snapshot.
class BoxLabelMappingCacheMissException : IllegalStateException()

@Singleton
class BoxLabelMappingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val dao: BoxLabelMappingDao,
    private val database: FscmDatabase,
) {
    private val refreshMutex = Mutex()

    suspend fun refreshIfStale(force: Boolean = false): Boolean = refreshMutex.withLock {
        val scope = scope()
        if (scope.userId == 0L || scope.namespaceId == 0L || sessionStore.current().isOffline) return false
        val latest = database.localSkuCatalogDao().meta(scope.userId, scope.namespaceId, "box_mappings")?.refreshedAt ?: 0L
        if (!force && latest > 0 && System.currentTimeMillis() - latest < CACHE_REFRESH_INTERVAL_MS) return false
        val items = downloadSnapshot()
        val syncedAt = System.currentTimeMillis()
        check(scope == scope()) { "工作空间已切换" }
        database.withTransaction {
            dao.replaceScope(scope.userId, scope.namespaceId, items.map { it.toEntity(scope, syncedAt) })
            database.localSkuCatalogDao().saveMeta(ReferenceCacheMeta(scope.userId, scope.namespaceId, "box_mappings", true, items.size, syncedAt))
        }
        return true
    }

    suspend fun refreshSession(sessionId: Long, boxPlanIds: List<Long>): Boolean = refreshMutex.withLock {
        val scope = scope()
        if (
            scope.userId == 0L ||
            scope.namespaceId == 0L ||
            sessionId <= 0 ||
            sessionStore.current().isOffline
        ) {
            return false
        }
        val items = downloadSnapshot(sessionId)
        val syncedAt = System.currentTimeMillis()
        check(scope == scope()) { "工作空间已切换" }
        dao.replacePlans(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            boxPlanIds = boxPlanIds.distinct(),
            rows = items.map { it.toEntity(scope, syncedAt) },
        )
        return true
    }

    private suspend fun downloadSnapshot(sessionId: Long? = null): List<BoxLabelMappingSnapshotItem> {
        val actor = scope()
        val items = mutableListOf<BoxLabelMappingSnapshotItem>()
        var afterId = 0L
        do {
            val previousAfterId = afterId
            val params = mutableMapOf("after_id" to afterId.toString(), "limit" to PAGE_SIZE.toString())
            sessionId?.let { params["receiving_session_id"] = it.toString() }
            check(actor == scope()) { "工作空间已切换" }
            val page: BoxLabelMappingSnapshot = api.get("mobile/app/box-label-mappings/snapshot", params, headers = inboundScopeHeaders(actor.userId, actor.namespaceId))
            items += page.items
            afterId = page.nextAfterId
            check(!page.hasMore || (afterId > previousAfterId && page.items.isNotEmpty())) { "箱码快照未下载完整" }
        } while (page.hasMore)
        check(items.distinctBy { it.id }.size == items.size) { "箱码快照包含重复记录" }
        return items
    }

    suspend fun resolveForCapture(rawCode: String): BoxLabelQrPayload? {
        val actor = scope()
        val raw = rawCode.trimScannerCharactersForLookup()
        val matches = dao.captureMatches(actor.userId, actor.namespaceId, raw.uppercase(Locale.ROOT), raw)
        if (matches.size > 1) throw InboundClientException(InboundClientError.BoxOfflineNotFound)
        return runCatching { parseBoxLabelQr(rawCode) }.getOrNull()
            ?: matches.singleOrNull()?.toResolvedPayload(raw)
    }

    suspend fun resolve(rawCode: String): BoxLabelQrPayload? {
        runCatching { parseBoxLabelQr(rawCode) }.getOrNull()?.let { return it }
        val scope = scope()
        val raw = rawCode.trimScannerCharactersForLookup()
        if (raw.isBlank()) return null
        val mapping = dao.find(scope.userId, scope.namespaceId, raw.uppercase(Locale.ROOT), raw) ?: return null
        return mapping.toResolvedPayload(raw)
    }

    suspend fun cachedMapping(rawCode: String): LocalBoxLabelMappingEntity? {
        val scope = scope()
        val raw = rawCode.trimScannerCharactersForLookup()
        return dao.find(scope.userId, scope.namespaceId, raw.uppercase(Locale.ROOT), raw)
    }

    suspend fun cachedMappings(rawCodes: List<String>): Map<String, LocalBoxLabelMappingEntity> {
        if (rawCodes.isEmpty()) return emptyMap()
        val actor = scope()
        val result = mutableMapOf<String, LocalBoxLabelMappingEntity>()
        rawCodes.distinct().chunked(100).forEach { batch ->
            val normalized = batch.associateWith { it.trimScannerCharactersForLookup() }
            val rows = dao.findMany(actor.userId, actor.namespaceId, normalized.values.map { it.uppercase(Locale.ROOT) }, normalized.values.toList())
            val byCode = mutableMapOf<String, LocalBoxLabelMappingEntity>()
            val byRaw = mutableMapOf<String, LocalBoxLabelMappingEntity>()
            rows.forEach { row ->
                listOf(row.supplierLabelCode, row.supplierBoxNo, row.supplierBoxUid, row.jifengBoxNo).filter { it.isNotBlank() }.forEach { code ->
                    byCode.putIfAbsent(code.uppercase(Locale.ROOT), row)
                }
                if (row.jifengQrCodeText.isNotBlank()) byRaw.putIfAbsent(row.jifengQrCodeText, row)
            }
            normalized.forEach { (original, raw) ->
                listOfNotNull(byCode[raw.uppercase(Locale.ROOT)], byRaw[raw]).maxByOrNull { it.mappingId }?.let { result[original] = it }
            }
        }
        return result
    }

    suspend fun knownJifengAliases(): Set<String> {
        val scope = scope()
        return dao.jifengAliases(scope.userId, scope.namespaceId).mapTo(mutableSetOf()) {
            it.trim().uppercase(Locale.ROOT)
        }
    }

    private suspend fun scope(): MappingScope {
        val session = sessionStore.current()
        return MappingScope(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
    }

    private fun BoxLabelMappingSnapshotItem.toEntity(scope: MappingScope, syncedAt: Long) = LocalBoxLabelMappingEntity(
        userId = scope.userId,
        namespaceId = scope.namespaceId,
        mappingId = id,
        supplierOrderBoxPlanId = supplierOrderBoxPlanId,
        supplierLabelCode = supplierLabelCode,
        supplierBoxNo = supplierBoxNo,
        supplierBoxUid = supplierBoxUid,
        jifengBoxNo = jifengBoxNo,
        jifengQrCodeText = jifengQrCodeText,
        mappingStatus = mappingStatus,
        productCode = productCode,
        productName = productName,
        skuCode = skuCode,
        manufacturerName = manufacturerName,
        caseSpecName = caseSpecName,
        qtyPerCarton = qtyPerCarton,
        boxQty = boxQty,
        remoteUpdatedAt = updatedAt,
        syncedAt = syncedAt,
    )

    private data class MappingScope(val userId: Long, val namespaceId: Long)

    private companion object {
        const val PAGE_SIZE = 500
        const val CACHE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }
}

internal fun LocalBoxLabelMappingEntity.toCachedBoxLabel(scannedCode: String) = BoxLabel(
    id = supplierOrderBoxPlanId,
    recognized = true,
    labelCode = supplierLabelCode,
    boxUid = supplierBoxUid,
    boxNo = supplierBoxNo,
    status = mappingStatus,
    productCode = productCode,
    productName = productName,
    skuCode = skuCode,
    caseSpecName = caseSpecName,
    qtyPerCarton = qtyPerCarton,
    plannedBoxQty = boxQty,
    jifengBoxNo = jifengBoxNo,
    jifengQrCodeText = jifengQrCodeText,
    supplierLabelCode = supplierLabelCode,
    scannedCode = scannedCode,
    resolvedVia = "jifeng_mapping",
    localFallback = true,
)

internal fun LocalBoxLabelMappingEntity.toResolvedPayload(rawCode: String): BoxLabelQrPayload? {
    val canonical = supplierLabelCode.ifBlank { supplierBoxUid.ifBlank { supplierBoxNo } }
    if (canonical.isBlank()) return null
    return BoxLabelQrPayload(
        originalRawCode = rawCode.trimScannerCharactersForLookup(),
        labelCode = canonical.uppercase(Locale.ROOT),
        format = BoxLabelQrFormat.JifengMapping,
    )
}

internal fun isKnownMappedBoxLabel(code: String, jifengAliases: Set<String>): Boolean =
    isLikelyBoxLabelQr(code) || code.trimScannerCharactersForLookup().uppercase(Locale.ROOT) in jifengAliases

private fun String.trimScannerCharactersForLookup(): String =
    trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
