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

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "local_sku_catalog", primaryKeys = ["userId", "namespaceId", "code"], indices = [Index(value = ["userId", "namespaceId", "foldedCode"])])
data class LocalSkuCatalogEntity(
    val userId: Long,
    val namespaceId: Long,
    val code: String,
    val skuId: Long,
    val name: String,
    val spec: String,
    val productId: Long,
    val productCode: String,
    val productName: String,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val foldedCode: String = foldCatalogCode(code),
)

@Entity(tableName = "reference_cache_meta", primaryKeys = ["userId", "namespaceId", "kind"])
data class ReferenceCacheMeta(
    val userId: Long,
    val namespaceId: Long,
    val kind: String,
    val complete: Boolean,
    val totalCount: Int,
    val refreshedAt: Long,
)

@Dao
interface LocalSkuCatalogDao {
    @Query("SELECT * FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND foldedCode LIKE :pattern ESCAPE '\\' ORDER BY foldedCode, code LIMIT :limit")
    suspend fun prefixCandidates(user: Long, namespace: Long, pattern: String, limit: Int): List<LocalSkuCatalogEntity>

    @Query("SELECT * FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND code IN (:codes)")
    suspend fun find(user: Long, namespace: Long, codes: List<String>): List<LocalSkuCatalogEntity>

    @Query("SELECT * FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND foldedCode IN (:codes)")
    suspend fun candidates(user: Long, namespace: Long, codes: List<String>): List<LocalSkuCatalogEntity>

    @Query("SELECT * FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind IN (:kinds)")
    suspend fun metas(user: Long, namespace: Long, kinds: List<String>): List<ReferenceCacheMeta>

    @Query("SELECT * FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind = :kind")
    suspend fun meta(user: Long, namespace: Long, kind: String): ReferenceCacheMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(meta: ReferenceCacheMeta)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<LocalSkuCatalogEntity>)

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace")
    suspend fun deleteScope(user: Long, namespace: Long)

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND foldedCode IN (:codes)")
    suspend fun deleteGroups(user: Long, namespace: Long, codes: List<String>)

    @Query("DELETE FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind LIKE 'sku_group:%'")
    suspend fun deleteGroupMeta(user: Long, namespace: Long)

    @Transaction
    suspend fun applyResolvedGroups(user: Long, namespace: Long, rows: List<LocalSkuCatalogEntity>, groups: List<ReferenceCacheMeta>) {
        deleteGroups(user, namespace, groups.map { it.kind.removePrefix("sku_group:") })
        insert(rows)
        groups.forEach { saveMeta(it) }
    }

    @Transaction
    suspend fun replace(rows: List<LocalSkuCatalogEntity>, meta: ReferenceCacheMeta) {
        deleteScope(meta.userId, meta.namespaceId)
        deleteGroupMeta(meta.userId, meta.namespaceId)
        insert(rows)
        saveMeta(meta)
    }
}

@Serializable
internal data class MobileSkuSnapshotPage(
    val items: List<MobileSkuSnapshotItem> = emptyList(),
    @SerialName("next_after_id") val nextAfterId: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
internal data class MobileSkuSnapshotItem(
    val id: Long,
    @SerialName("sku_code") val skuCode: String,
    @SerialName("sku_name") val skuName: String = "",
    val spec: String = "",
    @SerialName("product_id") val productId: Long,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String = "",
)

internal const val REFERENCE_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L
internal const val REFERENCE_LOOKUP_TIMEOUT_MS = 5_000L

internal fun referenceDataIsFresh(syncedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    syncedAt > 0 && now >= syncedAt && now - syncedAt < REFERENCE_REFRESH_INTERVAL_MS

internal fun Throwable.canUseReferenceCache(): Boolean =
    this !is CancellationException &&
        (this is IOException || this is SessionRefreshUnavailableException || this is FscmApiException && statusCode >= 500)

internal fun Throwable.referenceSyncMessage(): String = when {
    this is SessionExpiredException -> "登录已失效，请重新登录"
    this is SessionRefreshUnavailableException -> "登录续期暂时失败，请联网后重试"
    this is FscmApiException && statusCode == 404 -> "中心接口未就绪，请升级中心服务器"
    this is FscmApiException && statusCode == 401 -> "登录已失效，请重新登录"
    this is FscmApiException && statusCode == 403 -> "没有资料查询权限"
    this is FscmApiException -> serverMessage?.takeIf(String::isNotBlank) ?: "中心服务器请求失败（$statusCode）"
    this is IOException -> "网络不可用或请求超时，请联网后重试"
    else -> message?.takeIf(String::isNotBlank) ?: "资料更新失败，请重试"
}

@Serializable
internal data class MobileSkuLookupRequest(val codes: List<String>)

@Serializable
internal data class MobileSkuResolution(
    @SerialName("requested_code") val requestedCode: String,
    val status: String,
    @SerialName("matched_sku_id") val matchedSkuId: Long? = null,
    val candidates: List<MobileSkuSnapshotItem>,
)

@Serializable
internal data class MobileSkuResolveResult(val results: List<MobileSkuResolution>)

internal fun normalizeCatalogCode(code: String): String = code.trim()
internal fun foldCatalogCode(code: String): String = normalizeCatalogCode(code).uppercase(Locale.ROOT)

internal fun validateSkuResolutionResult(result: MobileSkuResolveResult, requested: List<String>): Map<String, List<MobileSkuSnapshotItem>> {
    check(result.results.map { it.requestedCode }.toSet() == requested.toSet() && result.results.size == requested.size) { "SKU 查询响应不完整" }
    val groups = mutableMapOf<String, List<MobileSkuSnapshotItem>>()
    for (resolution in result.results) {
        val key = foldCatalogCode(resolution.requestedCode)
        val candidates = resolution.candidates.sortedBy { it.id }
        check(candidates.all { it.id > 0 && it.productId > 0 && it.productCode.isNotBlank() && it.skuCode.isNotBlank() && foldCatalogCode(it.skuCode) == key }) { "SKU 查询身份无效" }
        check(candidates.distinctBy { it.id }.size == candidates.size) { "SKU 查询身份重复" }
        val exact = candidates.filter { normalizeCatalogCode(it.skuCode) == resolution.requestedCode }
        val match = exact.singleOrNull() ?: candidates.singleOrNull()?.takeIf { exact.isEmpty() }
        val status = if (match != null) {
            "matched"
        } else if (candidates.isEmpty()) {
            "missing"
        } else {
            "ambiguous"
        }
        check(resolution.status == status && resolution.matchedSkuId == match?.id) { "SKU 查询匹配结果无效" }
        check(groups[key] == null || groups[key] == candidates) { "SKU 查询候选不一致" }
        check(candidates.distinctBy { normalizeCatalogCode(it.skuCode) }.size == candidates.size) { "SKU 原始编码重复：${resolution.requestedCode}" }
        groups[key] = candidates
    }
    check(groups.values.flatten().distinctBy { it.id }.size == groups.values.sumOf { it.size }) { "SKU 查询身份重复" }
    return groups
}

internal data class SkuCatalogCache(val rows: List<LocalSkuCatalogEntity>, val metadata: List<ReferenceCacheMeta>) {
    private val groups = rows.groupBy { it.foldedCode }
    private val metas = metadata.associateBy { it.kind }

    fun isFresh(code: String): Boolean {
        val candidates = groups[foldCatalogCode(code)].orEmpty()
        val exact = candidates.singleOrNull { it.code == code }
        if (exact != null && referenceDataIsFresh(exact.syncedAt)) return true
        val groupMeta = metas["sku_group:${foldCatalogCode(code)}"]
        if (groupMeta?.complete == true && groupMeta.totalCount == candidates.size && referenceDataIsFresh(groupMeta.refreshedAt)) return true
        val all = metas["skus"]
        return all?.complete == true && referenceDataIsFresh(all.refreshedAt) && candidates.isNotEmpty() && candidates.all { referenceDataIsFresh(it.syncedAt) }
    }

    fun resolve(code: String): LocalSkuCatalogEntity? {
        val candidates = groups[foldCatalogCode(code)].orEmpty()
        candidates.singleOrNull { it.code == code }?.let { return it }
        if (candidates.size > 1) throw InboundClientException(InboundClientError.SkuAmbiguous, "$code (${candidates.map { it.code }.sorted().joinToString(" / ")})")
        val groupMeta = metas["sku_group:${foldCatalogCode(code)}"]
        val complete = metas["skus"]?.complete == true || (groupMeta?.complete == true && groupMeta.totalCount == candidates.size)
        return candidates.singleOrNull()?.takeIf { complete }
    }
}

internal fun validateSkuSnapshot(rows: List<LocalSkuCatalogEntity>) {
    check(rows.distinctBy { it.skuId }.size == rows.size) { "SKU 快照身份重复" }
    val duplicate = rows.groupingBy { it.code }.eachCount().entries.firstOrNull { it.value > 1 }
    check(duplicate == null) { "SKU 原始编码重复：${duplicate?.key}" }
}

internal fun validateSkuSnapshotPage(page: MobileSkuSnapshotPage, after: Long) {
    check(page.items.zipWithNext().all { (a, b) -> a.id < b.id }) { "SKU 分页顺序异常" }
    check(page.items.all { it.id > after && it.productId > 0 && it.skuCode.isNotBlank() && it.productCode.isNotBlank() }) { "SKU 快照记录无效" }
    check(!page.hasMore || (page.items.isNotEmpty() && page.nextAfterId == page.items.last().id)) { "SKU 快照未下载完整" }
}

@Singleton
class LocalSkuCatalogRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val database: FscmDatabase,
) {
    private val mutex = Mutex()
    private val dao get() = database.localSkuCatalogDao()

    suspend fun find(codes: List<String>): Map<String, LocalSkuCatalogEntity> {
        if (codes.isEmpty()) return emptyMap()
        val session = sessionStore.current()
        val normalized = codes.map(::normalizeCatalogCode).filter(String::isNotBlank).distinct()
        val user = session.user?.id ?: 0
        val namespace = session.currentNamespace?.id ?: 0
        suspend fun readCache(): SkuCatalogCache = database.withTransaction {
            val folded = normalized.map(::foldCatalogCode).distinct()
            SkuCatalogCache(
                folded.chunked(400).flatMap { dao.candidates(user, namespace, it) },
                (listOf("skus") + folded.map { "sku_group:$it" }).chunked(400).flatMap { dao.metas(user, namespace, it) },
            )
        }
        var cache = readCache()
        if (!session.isOffline && normalized.any { !cache.isFresh(it) }) {
            try {
                withTimeoutOrNull(REFERENCE_LOOKUP_TIMEOUT_MS) {
                    mutex.withLock {
                        checkScope(user, namespace)
                        val latest = readCache()
                        normalized.filter { !latest.isFresh(it) }.chunked(400).forEach { requested ->
                            val result: MobileSkuResolveResult = api.post(
                                "mobile/app/catalog/skus/resolve",
                                headers = inboundScopeHeaders(user, namespace),
                                body = Json.encodeToJsonElement(MobileSkuLookupRequest(requested)),
                            )
                            val groups = validateSkuResolutionResult(result, requested)
                            val now = System.currentTimeMillis()
                            val updates = groups.values.flatten().map { it.toEntity(user, namespace, now) }
                            checkScope(user, namespace)
                            dao.applyResolvedGroups(user, namespace, updates, groups.map { (key, items) -> ReferenceCacheMeta(user, namespace, "sku_group:$key", true, items.size, now) })
                        }
                    }
                    true
                } ?: throw IOException("中心服务器 SKU 查询超时")
            } catch (error: Exception) {
                if (error is FscmApiException && error.statusCode == 404) throw InboundClientException(InboundClientError.SkuServerUpgradeRequired)
                if (!error.canUseReferenceCache()) throw error
            }
            checkScope(user, namespace)
            cache = readCache()
        }
        checkScope(user, namespace)
        return normalized.mapNotNull { code -> cache.resolve(code)?.takeIf { it.skuId > 0 }?.let { code to it } }.toMap()
    }

    private suspend fun checkScope(user: Long, namespace: Long) {
        val current = sessionStore.current()
        check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
    }

    suspend fun cacheState(): ReferenceCacheMeta? {
        val session = sessionStore.current()
        return dao.meta(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, "skus")
    }

    private fun MobileSkuSnapshotItem.toEntity(user: Long, namespace: Long, now: Long) =
        LocalSkuCatalogEntity(user, namespace, normalizeCatalogCode(skuCode), id, skuName, spec, productId, productCode, productName, now)

    suspend fun resolve(codes: List<String>): List<LocalSkuCatalogEntity> {
        val rows = find(codes)
        return codes.map { rows[normalizeCatalogCode(it)] ?: throw InboundClientException(InboundClientError.SkuCacheMiss) }
    }

    suspend fun refreshIfStale(force: Boolean = false) = mutex.withLock {
        val session = sessionStore.current()
        val user = session.user?.id ?: return@withLock
        val namespace = session.currentNamespace?.id ?: return@withLock
        if (session.isOffline) return@withLock
        val old = dao.meta(user, namespace, "skus")
        if (!force && old?.complete == true && referenceDataIsFresh(old.refreshedAt)) return@withLock
        var after = 0L
        val rows = mutableListOf<LocalSkuCatalogEntity>()
        do {
            val current = sessionStore.current()
            check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
            val page: MobileSkuSnapshotPage = api.get("mobile/app/catalog/skus/snapshot", mapOf("after_id" to after.toString(), "limit" to "500"), headers = inboundScopeHeaders(user, namespace))
            validateSkuSnapshotPage(page, after)
            rows += page.items.map { it.toEntity(user, namespace, System.currentTimeMillis()) }
            after = page.nextAfterId
        } while (page.hasMore)
        validateSkuSnapshot(rows)
        val current = sessionStore.current()
        check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
        dao.replace(rows, ReferenceCacheMeta(user, namespace, "skus", true, rows.size, System.currentTimeMillis()))
    }
}

// Cancellation must never be converted into a completed or failed cache refresh.
internal suspend fun <T> cacheSyncResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
