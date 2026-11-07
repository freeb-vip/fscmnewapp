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
import androidx.room.ForeignKey
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "local_sku_catalog", primaryKeys = ["userId", "namespaceId", "skuId"], indices = [Index(value = ["userId", "namespaceId", "foldedCode"])])
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
    @ColumnInfo(defaultValue = "''") val baseCode: String = code,
    @ColumnInfo(defaultValue = "''") val workspaceCode: String = "",
    @ColumnInfo(defaultValue = "0") val policyVersion: Long = 0,
    @ColumnInfo(defaultValue = "'[]'") val aliasesJson: String = "[]",
)

@Entity(
    tableName = "local_sku_code_aliases",
    primaryKeys = ["userId", "namespaceId", "skuId", "code"],
    indices = [Index(value = ["userId", "namespaceId", "foldedCode"])],
    foreignKeys = [ForeignKey(entity = LocalSkuCatalogEntity::class, parentColumns = ["userId", "namespaceId", "skuId"], childColumns = ["userId", "namespaceId", "skuId"], onDelete = ForeignKey.CASCADE)],
)
data class LocalSkuCodeAlias(val userId: Long, val namespaceId: Long, val skuId: Long, val code: String, val foldedCode: String)

internal fun LocalSkuCatalogEntity.catalogCodes(): List<String> =
    (listOf(code, baseCode) + Json.decodeFromString<List<String>>(aliasesJson)).filter(String::isNotBlank).distinct()

@Entity(tableName = "reference_cache_meta", primaryKeys = ["userId", "namespaceId", "kind"])
data class ReferenceCacheMeta(
    val userId: Long,
    val namespaceId: Long,
    val kind: String,
    val complete: Boolean,
    val totalCount: Int,
    val refreshedAt: Long,
    @ColumnInfo(defaultValue = "0") val policyVersion: Long = 0,
)

@Dao
interface LocalSkuCatalogDao {
    @Query("SELECT DISTINCT c.* FROM local_sku_catalog c JOIN local_sku_code_aliases a ON a.userId=c.userId AND a.namespaceId=c.namespaceId AND a.skuId=c.skuId WHERE c.userId = :user AND c.namespaceId = :namespace AND a.foldedCode LIKE :pattern ESCAPE '\\' ORDER BY c.foldedCode, c.code LIMIT :limit")
    suspend fun prefixCandidates(user: Long, namespace: Long, pattern: String, limit: Int): List<LocalSkuCatalogEntity>

    @Query("SELECT DISTINCT c.* FROM local_sku_catalog c JOIN local_sku_code_aliases a ON a.userId=c.userId AND a.namespaceId=c.namespaceId AND a.skuId=c.skuId WHERE c.userId = :user AND c.namespaceId = :namespace AND a.code IN (:codes)")
    suspend fun find(user: Long, namespace: Long, codes: List<String>): List<LocalSkuCatalogEntity>

    @Query("SELECT DISTINCT c.* FROM local_sku_catalog c JOIN local_sku_code_aliases a ON a.userId=c.userId AND a.namespaceId=c.namespaceId AND a.skuId=c.skuId WHERE c.userId = :user AND c.namespaceId = :namespace AND a.foldedCode IN (:codes)")
    suspend fun candidates(user: Long, namespace: Long, codes: List<String>): List<LocalSkuCatalogEntity>

    @Query("SELECT * FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind IN (:kinds)")
    suspend fun metas(user: Long, namespace: Long, kinds: List<String>): List<ReferenceCacheMeta>

    @Query("SELECT * FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind = :kind")
    suspend fun meta(user: Long, namespace: Long, kind: String): ReferenceCacheMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(meta: ReferenceCacheMeta)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRows(rows: List<LocalSkuCatalogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAliases(rows: List<LocalSkuCodeAlias>)

    @Transaction
    suspend fun insert(rows: List<LocalSkuCatalogEntity>) {
        val unique = rows.distinctBy { Triple(it.userId, it.namespaceId, it.skuId) }
        insertRows(unique)
        insertAliases(unique.flatMap { row -> row.catalogCodes().map { code -> LocalSkuCodeAlias(row.userId, row.namespaceId, row.skuId, code, foldCatalogCode(code)) } })
    }

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace")
    suspend fun deleteScope(user: Long, namespace: Long)

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND skuId IN (SELECT skuId FROM local_sku_code_aliases WHERE userId = :user AND namespaceId = :namespace AND foldedCode IN (:codes))")
    suspend fun deleteGroups(user: Long, namespace: Long, codes: List<String>)

    @Query("DELETE FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind LIKE 'sku_group:%'")
    suspend fun deleteGroupMeta(user: Long, namespace: Long)

    @Transaction
    suspend fun applyResolvedGroups(user: Long, namespace: Long, rows: List<LocalSkuCatalogEntity>, groups: List<ReferenceCacheMeta>) {
        val replacedCodes = groups.flatMap { group ->
            val code = group.kind.removePrefix("sku_group:")
            // Missing and fallback responses prove the whole single-T group;
            // evict stale alternatives before replacing it with server identities.
            if (rows.none { row -> row.catalogCodes().any { foldCatalogCode(it) == code } }) {
                listOf(code) + legacySkuFallbackCodes(code).map(::foldCatalogCode)
            } else {
                listOf(code)
            }
        }.distinct()
        deleteGroups(user, namespace, replacedCodes)
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
    @SerialName("policy_version") val policyVersion: Long = 0,
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
    @SerialName("base_code") val baseCode: String = "",
    @SerialName("workspace_code") val workspaceCode: String? = null,
    @SerialName("policy_version") val policyVersion: Long = 0,
    val aliases: List<String> = emptyList(),
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
internal data class MobileSkuResolveResult(val results: List<MobileSkuResolution>, @SerialName("policy_version") val policyVersion: Long = 0)

@Serializable
internal data class MobileCatalogPolicy(@SerialName("policy_version") val policyVersion: Long = 0, @SerialName("namespace_id") val namespaceId: Long = 0)

internal fun normalizeCatalogCode(code: String): String = code.trim()
internal fun foldCatalogCode(code: String): String = normalizeCatalogCode(code).uppercase(Locale.ROOT)

internal fun legacySkuFallbackCodes(code: String): List<String> {
    val normalized = normalizeCatalogCode(code)
    if (normalized.isEmpty()) return emptyList()
    return buildList {
        add("T$normalized")
        if (normalized.length > 1 && normalized.startsWith("T", ignoreCase = true) && normalized.drop(1).isNotBlank()) add(normalized.drop(1))
    }
}

internal fun validateSkuResolutionResult(result: MobileSkuResolveResult, requested: List<String>): Map<String, List<MobileSkuSnapshotItem>> {
    check(result.results.map { it.requestedCode }.toSet() == requested.toSet() && result.results.size == requested.size) { "SKU 查询响应不完整" }
    val groups = mutableMapOf<String, List<MobileSkuSnapshotItem>>()
    for (resolution in result.results) {
        val key = foldCatalogCode(resolution.requestedCode)
        val candidates = resolution.candidates.sortedBy { it.id }
        fun MobileSkuSnapshotItem.matchesCode(code: String): Boolean =
            (aliases + skuCode + baseCode).any { foldCatalogCode(it) == foldCatalogCode(code) }
        val direct = candidates.filter { it.matchesCode(resolution.requestedCode) }
        val fallbackCodes = legacySkuFallbackCodes(resolution.requestedCode)
        check(
            candidates.all { candidate ->
                candidate.id > 0 && candidate.productId > 0 && candidate.productCode.isNotBlank() && candidate.skuCode.isNotBlank() &&
                    (if (direct.isNotEmpty()) candidate.matchesCode(resolution.requestedCode) else fallbackCodes.any { candidate.matchesCode(it) })
            },
        ) { "SKU 查询身份无效" }
        check(candidates.distinctBy { it.id }.size == candidates.size) { "SKU 查询身份重复" }
        val sameBase = candidates.map { foldCatalogCode(it.baseCode.ifBlank { it.skuCode }) }.distinct().size <= 1
        val exact = if (sameBase) candidates.filter { normalizeCatalogCode(it.skuCode) == resolution.requestedCode || normalizeCatalogCode(it.baseCode) == resolution.requestedCode } else emptyList()
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
    check(groups.values.flatten().groupBy { it.id }.all { (_, items) -> items.distinct().size == 1 }) { "SKU 查询身份不一致" }
    return groups
}

internal data class SkuCatalogCache(val rows: List<LocalSkuCatalogEntity>, val metadata: List<ReferenceCacheMeta>) {
    private val groups = rows.distinctBy { it.skuId }.flatMap { row -> row.catalogCodes().map(::foldCatalogCode).distinct().map { it to row } }.groupBy({ it.first }, { it.second })
    private val metas = metadata.associateBy { it.kind }

    private fun candidates(code: String): List<LocalSkuCatalogEntity> =
        groups[foldCatalogCode(code)].orEmpty().ifEmpty {
            legacySkuFallbackCodes(code).flatMap { groups[foldCatalogCode(it)].orEmpty() }.distinctBy { it.skuId }
        }

    fun isFresh(code: String): Boolean {
        val candidates = candidates(code)
        val sameBase = candidates.map { foldCatalogCode(it.baseCode) }.distinct().size <= 1
        val exact = candidates.singleOrNull { sameBase && (it.code == code || it.baseCode == code) }
        if (exact != null && exact.policyVersion == 0L && referenceDataIsFresh(exact.syncedAt)) return true
        val groupMeta = metas["sku_group:${foldCatalogCode(code)}"]
        if (groupMeta?.complete == true && groupMeta.totalCount == candidates.size && referenceDataIsFresh(groupMeta.refreshedAt)) return true
        val all = metas["skus"]
        return all?.complete == true && referenceDataIsFresh(all.refreshedAt) && candidates.isNotEmpty() && candidates.all { referenceDataIsFresh(it.syncedAt) }
    }

    fun resolve(code: String): LocalSkuCatalogEntity? {
        val candidates = candidates(code)
        val sameBase = candidates.map { foldCatalogCode(it.baseCode) }.distinct().size <= 1
        val exact = candidates.singleOrNull { sameBase && (it.code == code || it.baseCode == code) }
        if (exact != null && exact.policyVersion == 0L) return exact
        if (candidates.size > 1) throw InboundClientException(InboundClientError.SkuAmbiguous, "$code (${candidates.map { it.code }.sorted().joinToString(" / ")})")
        val groupMeta = metas["sku_group:${foldCatalogCode(code)}"]
        val complete = metas["skus"]?.complete == true || (groupMeta?.complete == true && groupMeta.totalCount == candidates.size)
        return (exact ?: candidates.singleOrNull())?.takeIf { complete }
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
        if (!session.isOffline) {
            try {
                withTimeoutOrNull(REFERENCE_LOOKUP_TIMEOUT_MS) { verifyPolicy(user, namespace) } ?: throw IOException("中心编码规则查询超时")
            } catch (error: Exception) {
                if (!error.canUseReferenceCache()) throw error
            }
        }
        suspend fun readCache(): SkuCatalogCache = database.withTransaction {
            val folded = normalized.flatMap { listOf(it) + legacySkuFallbackCodes(it) }.map(::foldCatalogCode).distinct()
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
                            val updates = groups.values.flatten().distinctBy { it.id }.map { it.toEntity(user, namespace, now) }
                            checkScope(user, namespace)
                            dao.applyResolvedGroups(user, namespace, updates, groups.map { (key, items) -> ReferenceCacheMeta(user, namespace, "sku_group:$key", true, items.size, now, result.policyVersion) })
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

    private suspend fun verifyPolicy(user: Long, namespace: Long) {
        val policy: MobileCatalogPolicy = api.get("workspace-settings", headers = inboundScopeHeaders(user, namespace))
        checkScope(user, namespace)
        check(policy.namespaceId == namespace) { "编码规则不属于当前工作空间" }
        database.withTransaction {
            val old = dao.meta(user, namespace, "skus")
            if (old?.policyVersion != policy.policyVersion) {
                dao.deleteScope(user, namespace)
                dao.deleteGroupMeta(user, namespace)
                dao.saveMeta(ReferenceCacheMeta(user, namespace, "skus", false, 0, 0, policy.policyVersion))
            }
        }
    }

    suspend fun cacheState(): ReferenceCacheMeta? {
        val session = sessionStore.current()
        return dao.meta(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, "skus")
    }

    private fun MobileSkuSnapshotItem.toEntity(user: Long, namespace: Long, now: Long) =
        LocalSkuCatalogEntity(user, namespace, normalizeCatalogCode(skuCode), id, skuName, spec, productId, productCode, productName, now, baseCode = baseCode.ifBlank { skuCode }, workspaceCode = workspaceCode.orEmpty(), policyVersion = policyVersion, aliasesJson = Json.encodeToString(aliases))

    suspend fun resolve(codes: List<String>): List<LocalSkuCatalogEntity> {
        val rows = find(codes)
        return codes.map { rows[normalizeCatalogCode(it)] ?: throw InboundClientException(InboundClientError.SkuCacheMiss) }
    }

    suspend fun refreshIfStale(force: Boolean = false) = mutex.withLock {
        val session = sessionStore.current()
        val user = session.user?.id ?: return@withLock
        val namespace = session.currentNamespace?.id ?: return@withLock
        if (session.isOffline) return@withLock
        verifyPolicy(user, namespace)
        val old = dao.meta(user, namespace, "skus")
        if (!force && old?.complete == true && referenceDataIsFresh(old.refreshedAt)) return@withLock
        var after = 0L
        var policyVersion: Long? = null
        val rows = mutableListOf<LocalSkuCatalogEntity>()
        do {
            val current = sessionStore.current()
            check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
            val params = mutableMapOf("after_id" to after.toString(), "limit" to "500")
            policyVersion?.let { params["policy_version"] = it.toString() }
            val page: MobileSkuSnapshotPage = api.get("mobile/app/catalog/skus/snapshot", params, headers = inboundScopeHeaders(user, namespace))
            check(policyVersion == null || page.policyVersion == policyVersion) { "编码规则已变化，请重新下载SKU目录" }
            policyVersion = page.policyVersion
            validateSkuSnapshotPage(page, after)
            rows += page.items.map { it.toEntity(user, namespace, System.currentTimeMillis()) }
            after = page.nextAfterId
        } while (page.hasMore)
        validateSkuSnapshot(rows)
        val current = sessionStore.current()
        check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
        dao.replace(rows, ReferenceCacheMeta(user, namespace, "skus", true, rows.size, System.currentTimeMillis(), policyVersion ?: 0))
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
