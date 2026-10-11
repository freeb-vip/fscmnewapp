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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Entity(
    tableName = "local_warehouse_locations",
    primaryKeys = ["userId", "namespaceId", "locationId"],
    indices = [
        Index(value = ["userId", "namespaceId", "warehouseCode", "code"], unique = true),
        Index(value = ["userId", "namespaceId", "code"]),
    ],
)
data class LocalWarehouseLocationEntity(
    val userId: Long,
    val namespaceId: Long,
    val locationId: Long,
    val warehouseId: Long,
    val warehouseCode: String,
    val warehouseName: String,
    val zoneId: Long,
    val zoneCode: String,
    val zoneName: String,
    val code: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val remoteUpdatedAt: String,
    val syncedAt: Long,
)

@Entity(tableName = "warehouse_location_cache_meta", primaryKeys = ["userId", "namespaceId"])
data class WarehouseLocationCacheMetaEntity(
    val userId: Long,
    val namespaceId: Long,
    val refreshedAt: Long,
    val complete: Boolean,
    val totalCount: Int,
)

@Dao
interface WarehouseLocationDao {
    @Query("SELECT code, GROUP_CONCAT(DISTINCT warehouseName) AS description FROM local_warehouse_locations WHERE userId = :user AND namespaceId = :namespace AND isActive = 1 AND code LIKE :pattern ESCAPE '\\' GROUP BY code ORDER BY code COLLATE NOCASE, code LIMIT :limit")
    suspend fun prefixCandidates(user: Long, namespace: Long, pattern: String, limit: Int): List<OfflineCodeOption>

    @Query(
        """
        SELECT * FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND warehouseCode = :warehouseCode
          AND code = :code AND isActive = 1
        LIMIT 1
        """,
    )
    suspend fun findActive(
        userId: Long,
        namespaceId: Long,
        warehouseCode: String,
        code: String,
    ): LocalWarehouseLocationEntity?

    @Query(
        """
        SELECT * FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND locationId = :locationId AND isActive = 1
        LIMIT 1
        """,
    )
    suspend fun findActiveById(userId: Long, namespaceId: Long, locationId: Long): LocalWarehouseLocationEntity?

    @Query(
        """
        SELECT * FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND code = :code COLLATE NOCASE AND isActive = 1
        """,
    )
    suspend fun findActiveByCode(userId: Long, namespaceId: Long, code: String): List<LocalWarehouseLocationEntity>

    @Query(
        """
        SELECT code FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND warehouseCode = :warehouseCode COLLATE NOCASE AND isActive = 1
        """,
    )
    suspend fun activeCodes(userId: Long, namespaceId: Long, warehouseCode: String): List<String>

    @Query(
        """
        SELECT warehouseId FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND warehouseCode = :warehouseCode COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun warehouseId(userId: Long, namespaceId: Long, warehouseCode: String): Long?

    @Query("SELECT * FROM warehouse_location_cache_meta WHERE userId = :userId AND namespaceId = :namespaceId LIMIT 1")
    suspend fun meta(userId: Long, namespaceId: Long): WarehouseLocationCacheMetaEntity?

    @Query("DELETE FROM local_warehouse_locations WHERE userId = :userId AND namespaceId = :namespaceId")
    suspend fun deleteScope(userId: Long, namespaceId: Long)

    @Query(
        """
        DELETE FROM local_warehouse_locations
        WHERE userId = :userId AND namespaceId = :namespaceId
          AND warehouseCode = :warehouseCode COLLATE NOCASE
          AND code = :code COLLATE NOCASE
        """,
    )
    suspend fun deleteCode(userId: Long, namespaceId: Long, warehouseCode: String, code: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LocalWarehouseLocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: WarehouseLocationCacheMetaEntity)

    @Transaction
    suspend fun replaceScope(
        userId: Long,
        namespaceId: Long,
        rows: List<LocalWarehouseLocationEntity>,
        meta: WarehouseLocationCacheMetaEntity,
    ) {
        deleteScope(userId, namespaceId)
        if (rows.isNotEmpty()) upsertAll(rows)
        upsertMeta(meta)
    }
}

@Serializable
data class WarehouseLocationOption(
    val id: Long = 0,
    @SerialName("warehouse_id") val warehouseId: Long = 0,
    @SerialName("zone_id") val zoneId: Long = 0,
    val code: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val warehouse: WarehouseLocationWarehouse = WarehouseLocationWarehouse(),
    val zone: WarehouseLocationZone = WarehouseLocationZone(),
)

@Serializable
data class WarehouseLocationWarehouse(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
)

@Serializable
data class WarehouseLocationZone(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
)

@Serializable
internal data class MobileLocationSnapshotPage(
    val items: List<WarehouseLocationOption> = emptyList(),
    @SerialName("next_after_id") val nextAfterId: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

internal fun validateLocationSnapshotPage(page: MobileLocationSnapshotPage, after: Long) {
    check(page.items.zipWithNext().all { (a, b) -> a.id < b.id }) { "库位分页顺序异常" }
    check(page.items.all { it.id > after && it.code.isNotBlank() && it.warehouse.code.isNotBlank() && it.isActive }) { "库位快照记录无效" }
    check(!page.hasMore || (page.items.isNotEmpty() && page.nextAfterId == page.items.last().id)) { "库位快照未下载完整" }
}

data class WarehouseLocationCacheState(
    val complete: Boolean = false,
    val refreshedAt: Long = 0,
    val totalCount: Int = 0,
)

data class ResolvedWarehouseLocation(
    val location: LocalWarehouseLocationEntity,
    val fromCache: Boolean,
)

@Singleton
class WarehouseLocationRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val dao: WarehouseLocationDao,
) {
    private val refreshMutex = Mutex()

    suspend fun refreshIfStale(force: Boolean = false): WarehouseLocationCacheState = refreshMutex.withLock {
        val scope = scope()
        val cached = dao.meta(scope.userId, scope.namespaceId)
        if (sessionStore.current().isOffline) return cached.toState()
        if (
            !force && cached?.complete == true &&
            referenceDataIsFresh(cached.refreshedAt)
        ) {
            return cached.toState()
        }

        val options = mutableListOf<WarehouseLocationOption>()
        var after = 0L
        do {
            check(scope == scope()) { "工作空间已切换" }
            val response: MobileLocationSnapshotPage = api.get(
                "mobile/app/catalog/locations/snapshot",
                mapOf(
                    "after_id" to after.toString(),
                    "limit" to "500",
                ),
                headers = inboundScopeHeaders(scope.userId, scope.namespaceId),
            )
            validateLocationSnapshotPage(response, after)
            options += response.items
            after = response.nextAfterId
        } while (response.hasMore)
        val now = System.currentTimeMillis()
        val rows = options
            .asSequence()
            .filter { it.id > 0 && it.code.isNotBlank() && it.warehouseCode().isNotBlank() }
            .distinctBy { it.id }
            .map { it.toEntity(scope, now) }
            .toList()
        check(rows.size == options.size && rows.distinctBy { it.warehouseCode to it.code }.size == rows.size) { "库位快照不完整或包含重复记录" }
        check(scope == scope()) { "工作空间已切换" }
        val meta = WarehouseLocationCacheMetaEntity(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            refreshedAt = now,
            complete = true,
            totalCount = rows.size,
        )
        dao.replaceScope(scope.userId, scope.namespaceId, rows, meta)
        return meta.toState()
    }

    // Fresh center-owned identities stay local; missing or expired identities are revalidated.
    suspend fun resolveForCapture(rawCode: String, warehouseCode: String): ResolvedWarehouseLocation {
        val actor = scope()
        val warehouse = warehouseCode.trim().uppercase(Locale.ROOT)
        if (warehouse.isBlank()) throw InboundClientException(InboundClientError.TargetWarehouseMissing)
        val code = normalizeInboundLocation(rawCode)
        val location = dao.findActive(actor.userId, actor.namespaceId, warehouse, code)
        check(actor == scope()) { "工作空间已切换，请重新扫描" }
        if (location != null && (sessionStore.current().isOffline || referenceDataIsFresh(location.syncedAt))) {
            return ResolvedWarehouseLocation(location, fromCache = true)
        }
        return resolve(rawCode, warehouseCode)
    }

    suspend fun resolve(rawCode: String, warehouseCode: String): ResolvedWarehouseLocation {
        val normalizedCode = normalizeInboundLocation(rawCode)
        val normalizedWarehouse = warehouseCode.trim().uppercase(Locale.ROOT)
        if (normalizedWarehouse.isBlank()) throw InboundClientException(InboundClientError.TargetWarehouseMissing)
        val scope = scope()
        if (sessionStore.current().isOffline) {
            return ResolvedWarehouseLocation(
                location = requireCached(scope, normalizedWarehouse, normalizedCode),
                fromCache = true,
            )
        }

        return try {
            withTimeoutOrNull(REFERENCE_LOOKUP_TIMEOUT_MS) {
                refreshMutex.withLock {
                    check(scope == scope()) { "工作空间已切换，请重新扫描" }
                    // Another lookup or background snapshot may already have refreshed this row.
                    dao.findActive(scope.userId, scope.namespaceId, normalizedWarehouse, normalizedCode)
                        ?.takeIf { referenceDataIsFresh(it.syncedAt) }
                        ?.let { return@withLock ResolvedWarehouseLocation(it, fromCache = true) }
                    try {
                        val remote: WarehouseLocationOption = api.get(
                            "mobile/app/catalog/locations/resolve",
                            mapOf("code" to normalizedCode, "warehouse_code" to normalizedWarehouse),
                            headers = inboundScopeHeaders(scope.userId, scope.namespaceId),
                        )
                        check(selectWarehouseLocation(listOf(remote), normalizedCode, normalizedWarehouse) != null) { "中心库位响应不匹配" }
                        val entity = remote.toEntity(scope, System.currentTimeMillis())
                        check(scope == scope()) { "工作空间已切换，请重新扫描" }
                        dao.upsertAll(listOf(entity))
                        ResolvedWarehouseLocation(entity, fromCache = false)
                    } catch (error: FscmApiException) {
                        check(scope == scope()) { "工作空间已切换，请重新扫描" }
                        if (error.inboundStandardCode() in setOf(42244, 42245, 42246)) {
                            dao.deleteCode(scope.userId, scope.namespaceId, normalizedWarehouse, normalizedCode)
                        }
                        throw error
                    }
                }
            } ?: throw IOException("中心服务器查询超时，暂时无法核验库位")
        } catch (error: Throwable) {
            if (!error.canUseReferenceCache()) throw error
            check(scope == scope()) { "工作空间已切换，请重新扫描" }
            val cachedLocation = dao.findActive(scope.userId, scope.namespaceId, normalizedWarehouse, normalizedCode)
                ?: throw IOException("无法连接中心服务器，暂时无法核验库位，请联网后重试", error)
            ResolvedWarehouseLocation(location = cachedLocation, fromCache = true)
        }
    }

    suspend fun resolvePersisted(
        locationId: Long,
        locationCode: String,
        warehouseCode: String,
    ): ResolvedWarehouseLocation {
        val scope = scope()
        if (locationId > 0) {
            dao.findActiveById(scope.userId, scope.namespaceId, locationId)
                ?.takeIf { it.warehouseCode.equals(warehouseCode, true) && it.code.equals(locationCode, true) && (sessionStore.current().isOffline || referenceDataIsFresh(it.syncedAt)) }
                ?.let { return ResolvedWarehouseLocation(it, fromCache = true) }
        }
        return resolve(locationCode, warehouseCode)
    }

    suspend fun cacheState(): WarehouseLocationCacheState {
        val scope = scope()
        return dao.meta(scope.userId, scope.namespaceId).toState()
    }

    suspend fun knownCodes(warehouseCode: String): Set<String> {
        val scope = scope()
        return dao.activeCodes(scope.userId, scope.namespaceId, warehouseCode)
            .mapTo(mutableSetOf()) { it.uppercase(Locale.ROOT) }
    }

    private suspend fun requireCached(
        scope: WarehouseLocationScope,
        warehouseCode: String,
        code: String,
    ): LocalWarehouseLocationEntity {
        return resolveLocalWarehouseLocation(dao, scope.userId, scope.namespaceId, warehouseCode, code)
    }

    private suspend fun scope(): WarehouseLocationScope {
        val session = sessionStore.current()
        return WarehouseLocationScope(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
    }

    private fun WarehouseLocationOption.toEntity(scope: WarehouseLocationScope, syncedAt: Long) =
        LocalWarehouseLocationEntity(
            userId = scope.userId,
            namespaceId = scope.namespaceId,
            locationId = id,
            warehouseId = warehouseId.takeIf { it > 0 } ?: warehouse.id,
            warehouseCode = warehouseCode().uppercase(Locale.ROOT),
            warehouseName = warehouse.name,
            zoneId = zoneId.takeIf { it > 0 } ?: zone.id,
            zoneCode = zone.code,
            zoneName = zone.name,
            code = code.trim().uppercase(Locale.ROOT),
            name = name,
            type = type,
            isActive = isActive,
            remoteUpdatedAt = updatedAt,
            syncedAt = syncedAt,
        )

    private fun WarehouseLocationOption.warehouseCode(): String = warehouse.code.trim()

    private data class WarehouseLocationScope(val userId: Long, val namespaceId: Long)
}

private fun WarehouseLocationCacheMetaEntity?.toState() = WarehouseLocationCacheState(
    complete = this?.complete == true,
    refreshedAt = this?.refreshedAt ?: 0,
    totalCount = this?.totalCount ?: 0,
)

internal fun selectWarehouseLocation(
    options: List<WarehouseLocationOption>,
    code: String,
    warehouseCode: String,
): WarehouseLocationOption? = options.firstOrNull {
    it.isActive &&
        it.code.equals(code, ignoreCase = true) &&
        it.warehouse.code.equals(warehouseCode, ignoreCase = true)
}

internal suspend fun resolveLocalWarehouseLocation(
    dao: WarehouseLocationDao,
    user: Long,
    namespace: Long,
    warehouse: String,
    code: String,
): LocalWarehouseLocationEntity {
    dao.findActive(user, namespace, warehouse, code)?.let { return it }
    if (dao.findActiveByCode(user, namespace, code).any { !it.warehouseCode.equals(warehouse, true) }) {
        throw InboundClientException(InboundClientError.LocationWarehouseMismatch)
    }
    if (dao.meta(user, namespace)?.complete != true) {
        throw InboundClientException(InboundClientError.LocationCacheUnavailable)
    }
    throw InboundClientException(InboundClientError.LocationNotFound)
}
