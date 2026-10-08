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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

@Entity(tableName = "local_sku_catalog", primaryKeys = ["userId", "namespaceId", "code"])
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
    @Query("SELECT * FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND code IN (:codes)")
    suspend fun find(user: Long, namespace: Long, codes: List<String>): List<LocalSkuCatalogEntity>

    @Query("SELECT * FROM reference_cache_meta WHERE userId = :user AND namespaceId = :namespace AND kind = :kind")
    suspend fun meta(user: Long, namespace: Long, kind: String): ReferenceCacheMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(meta: ReferenceCacheMeta)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<LocalSkuCatalogEntity>)

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace")
    suspend fun deleteScope(user: Long, namespace: Long)

    @Query("DELETE FROM local_sku_catalog WHERE userId = :user AND namespaceId = :namespace AND code IN (:codes)")
    suspend fun deleteCodes(user: Long, namespace: Long, codes: List<String>)

    @Transaction
    suspend fun applyLookup(user: Long, namespace: Long, codes: List<String>, rows: List<LocalSkuCatalogEntity>) {
        deleteCodes(user, namespace, codes)
        insert(rows)
    }

    @Transaction
    suspend fun replace(rows: List<LocalSkuCatalogEntity>, meta: ReferenceCacheMeta) {
        deleteScope(meta.userId, meta.namespaceId)
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
internal data class MobileSkuLookupResult(
    val items: List<MobileSkuSnapshotItem> = emptyList(),
    @SerialName("missing_codes") val missingCodes: List<String> = emptyList(),
)

internal fun normalizeCatalogCode(code: String): String = code.trim().uppercase(Locale.ROOT)

internal fun validateSkuSnapshotPage(page: MobileSkuSnapshotPage, after: Long) {
    check(page.items.zipWithNext().all { (a, b) -> a.id < b.id }) { "SKU 分页顺序异常" }
    check(page.items.all { it.id > after && it.skuCode.isNotBlank() && it.productCode.isNotBlank() }) { "SKU 快照记录无效" }
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
        var rows = normalized.chunked(400).flatMap { dao.find(user, namespace, it) }.associateBy { it.code }
        if (!session.isOffline && normalized.any { !referenceDataIsFresh(rows[it]?.syncedAt ?: 0) }) {
            try {
                withTimeoutOrNull(REFERENCE_LOOKUP_TIMEOUT_MS) {
                    mutex.withLock {
                        checkScope(user, namespace)
                        val latest = normalized.chunked(400).flatMap { dao.find(user, namespace, it) }.associateBy { it.code }
                        normalized.filter { !referenceDataIsFresh(latest[it]?.syncedAt ?: 0) }.chunked(400).forEach { requested ->
                            val result: MobileSkuLookupResult = api.post(
                                "mobile/app/catalog/skus/lookup",
                                headers = inboundScopeHeaders(user, namespace),
                                body = Json.encodeToJsonElement(MobileSkuLookupRequest(requested)),
                            )
                            val returned = result.items.map { normalizeCatalogCode(it.skuCode) }
                            val missing = result.missingCodes.map(::normalizeCatalogCode)
                            check(
                                returned.distinct().size == returned.size && missing.distinct().size == missing.size &&
                                    returned.toSet().intersect(missing.toSet()).isEmpty() && (returned + missing).toSet() == requested.toSet() &&
                                    result.items.all { it.id > 0 && it.productId > 0 && it.productCode.isNotBlank() },
                            ) { "SKU 查询响应不完整" }
                            val now = System.currentTimeMillis()
                            val updates = result.items.map { it.toEntity(user, namespace, now) }
                            checkScope(user, namespace)
                            dao.applyLookup(user, namespace, requested, updates)
                        }
                    }
                    true
                } ?: throw IOException("中心服务器 SKU 查询超时")
            } catch (error: Exception) {
                if (!error.canUseReferenceCache()) throw error
            }
            checkScope(user, namespace)
            rows = normalized.chunked(400).flatMap { dao.find(user, namespace, it) }.associateBy { it.code }
        }
        checkScope(user, namespace)
        return rows.filterValues { it.skuId > 0 }
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
        check(rows.distinctBy { it.code }.size == rows.size) { "SKU 编码存在歧义" }
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
