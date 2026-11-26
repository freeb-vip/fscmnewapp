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
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "cross_order_scans", indices = [Index(value = ["userId", "namespaceId", "sourceSessionId"])])
@Serializable
data class CrossOrderScanEntity(
    @PrimaryKey val id: String,
    val userId: Long,
    val namespaceId: Long,
    val sourceSessionId: Long,
    val batchId: String,
    val operation: String,
    val rawCode: String,
    val requestPayload: String,
    val confirmationPayload: String = "",
    val state: String = "pending",
    val effectiveSessionId: Long = 0,
    val resultPayload: String = "",
    val message: String = "已保存，待联网识别",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface CrossOrderScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRow(row: CrossOrderScanEntity)

    @Query("UPDATE auto_receipt_scan_items SET scanJson=:payload,errorMessage='' WHERE requestId=:id AND crossOrder=1")
    suspend fun updateFrozenScan(id: String, payload: String)

    @Transaction
    suspend fun save(row: CrossOrderScanEntity) {
        saveRow(row)
        updateFrozenScan(row.id, Json.encodeToString(row))
    }

    @Query("SELECT * FROM cross_order_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sourceSessionId = :sourceId ORDER BY updatedAt DESC")
    fun observe(userId: Long, namespaceId: Long, sourceId: Long): Flow<List<CrossOrderScanEntity>>

    @Query("SELECT * FROM cross_order_scans WHERE userId = :userId AND namespaceId = :namespaceId AND id = :id")
    suspend fun get(userId: Long, namespaceId: Long, id: String): CrossOrderScanEntity?

    @Query("SELECT * FROM cross_order_scans WHERE userId = :userId AND namespaceId = :namespaceId AND state IN ('pending','confirmed_pending') ORDER BY updatedAt")
    suspend fun pending(userId: Long, namespaceId: Long): List<CrossOrderScanEntity>

    @Query("SELECT * FROM cross_order_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sourceSessionId = :sourceId AND rawCode = :raw AND operation = :operation AND state <> 'completed' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun outstanding(userId: Long, namespaceId: Long, sourceId: Long, raw: String, operation: String): CrossOrderScanEntity?

    @Query("SELECT COUNT(*) FROM cross_order_scans WHERE userId = :userId AND namespaceId = :namespaceId AND batchId = :batchId")
    suspend fun batchCount(userId: Long, namespaceId: Long, batchId: String): Int

    @Query("UPDATE local_inbound_sessions SET snapshotAt = 0, productCacheAt = 0 WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun invalidateInbound(userId: Long, namespaceId: Long, sessionId: Long)

    @Query("UPDATE receiving_sessions SET remoteSyncedAt = 0 WHERE userId = :userId AND namespaceId = :namespaceId AND remoteId = :sessionId")
    suspend fun invalidateReceiving(userId: Long, namespaceId: Long, sessionId: Long)

    @Query("DELETE FROM cross_order_scans WHERE id = :id AND userId = :userId AND namespaceId = :namespaceId")
    suspend fun delete(id: String, userId: Long, namespaceId: Long)
}

class InboundReviewOffer internal constructor(val task: CrossOrderScanEntity, val location: String) : Exception()

class CrossOrderScanPending(val task: CrossOrderScanEntity) : Exception(task.message)

internal fun JsonObject.crossText(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
internal fun JsonObject.crossID(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0

fun CrossOrderScanEntity.confirmation(): JsonObject? =
    runCatching { Json.parseToJsonElement(confirmationPayload).jsonObject }.getOrNull()

internal fun offlineCrossOrderConfirmation(catalog: JsonObject, box: JsonObject, operation: String, locationId: Long, locationCode: String): JsonObject {
    val snapshot = buildJsonObject {
        put("source_session_id", catalog.crossID("source_session_id"))
        put("source_order_id", catalog.crossID("source_order_id"))
        put("physical_box_plan_id", box.crossID("box_plan_id"))
        put("box_plan_id", box.crossID("box_plan_id"))
        put("target_order_id", box.crossID("target_order_id"))
        put("warehouse_id", catalog.crossID("warehouse_id"))
        put("operation", operation)
        put("quantity", box.crossID("quantity"))
        put("actual_quantity", box.crossID("quantity"))
        put("location_id", locationId)
        put("receiving_cycle", box.crossID("receiving_cycle"))
        put("inbound_cycle", box.crossID("inbound_cycle"))
    }
    return JsonObject(
        catalog.filterKeys { it != "items" } + box + mapOf(
            "confirmation_type" to JsonPrimitive("cross_order_receiving"),
            "operation" to JsonPrimitive(operation),
            "location_code" to JsonPrimitive(locationCode),
            "cross_order_snapshot" to JsonPrimitive(snapshot.toString()),
        ),
    )
}

private suspend fun FscmDatabase.catalogMatches(header: JsonObject?, user: Long, namespace: Long, codes: List<String>): List<JsonObject> {
    if (header == null) return emptyList()
    val keys = codes.filter(String::isNotBlank).map { it.uppercase(Locale.ROOT) }.distinct()
    return crossOrderCatalogDao().matches(user, namespace, header.crossID("warehouse_id"), keys)
        .map { Json.parseToJsonElement(it.payload).jsonObject }
}

internal suspend fun FscmDatabase.resolveInboundCarton(
    batch: LocalInboundBatchEntity,
    raw: String,
    onCrossOrder: suspend (JsonObject) -> Unit,
): LocalInboundSnapshotEntity? = withTransaction {
    val user = batch.userId
    val namespace = batch.namespaceId
    val sourceId = batch.sessionId
    val code = raw.trimScannerSuffix()
    val parsed = runCatching { parseBoxLabelQr(code) }.getOrNull()
    val inbound = localInboundDao()
    val codes = listOfNotNull(code, parsed?.labelCode).distinct()
    val direct = inbound.scanCandidates(user, namespace, sourceId, codes)
    val mapping = boxLabelMappingDao().captureMapping(user, namespace, codes, code)
    val mapped = mapping?.toResolvedPayload(code)?.labelCode
    val keys = if (mapped != null && mapped !in codes) codes + mapped else codes
    val local = if (keys == codes) direct else inbound.scanCandidates(user, namespace, sourceId, keys)
    val catalog = offlineCacheDao().find(user, namespace, "cross-order:$sourceId")
        ?.let { Json.parseToJsonElement(it.payload).jsonObject }
    val matches = catalogMatches(catalog, user, namespace, keys)
    val snapshot = local.singleOrNull()
    val plans = local.map { it.boxPlanId } + matches.map { it.crossID("box_plan_id") } + listOfNotNull(mapping?.supplierOrderBoxPlanId)
    if (plans.distinct().size > 1) throw InboundClientException(InboundClientError.BoxOwnershipConflict)
    val box = matches.singleOrNull()
    if (box == null || catalog == null || box.crossID("target_order_id") == catalog.crossID("source_order_id")) {
        val targetSession = box?.crossID("target_session_id") ?: 0L
        if (targetSession > 0 && targetSession != sourceId) throw InboundClientException(InboundClientError.BoxOwnershipConflict)
        return@withTransaction snapshot ?: throw InboundClientException(InboundClientError.BoxOfflineNotFound, sourceId.toString(), code)
    }
    if (snapshot != null) throw InboundClientException(InboundClientError.BoxOwnershipConflict)
    onCrossOrder(offlineCrossOrderConfirmation(catalog, box, "inbound", batch.warehouseLocationId, batch.locationCode))
    null
}

internal suspend fun FscmDatabase.stageOriginalScan(offer: InboundReviewOffer): Boolean = withTransaction {
    val task = offer.task
    val dao = crossOrderScanDao()
    val batch = localInboundDao().batch(task.batchId)
    check(
        batch != null && batch.userId == task.userId && batch.namespaceId == task.namespaceId &&
            batch.sessionId == task.sourceSessionId && batch.status == InboundBatchStatus.Draft.value &&
            batch.locationCode == offer.location && batch.warehouseLocationId == Json.parseToJsonElement(task.requestPayload).jsonObject.crossID("warehouse_location_id"),
    ) { "采集批次或库位已变化，请重新扫描" }
    val existing = dao.outstanding(task.userId, task.namespaceId, task.sourceSessionId, task.rawCode, task.operation)
    if (existing != null || dao.get(task.userId, task.namespaceId, task.id) != null) return@withTransaction false
    dao.save(task.copy(message = "原扫描已保存，待提交中心处理"))
    true
}

/** Only a center preview of the original scan may establish an adjustment alias for capture. */
internal suspend fun FscmDatabase.previewMissingInboundCarton(
    batch: LocalInboundBatchEntity,
    raw: String,
    preview: suspend () -> JsonObject,
): LocalInboundSnapshotEntity {
    val missing = InboundClientException(InboundClientError.BoxNotInSession, batch.sessionId.toString(), raw)
    val response = try {
        preview()
    } catch (error: FscmApiException) {
        val root = error.payload as? JsonObject
        val data = root?.get("data") as? JsonObject ?: root
        if (data?.crossText("reason_code") == "physical_identity_unverified") {
            throw InboundClientException(InboundClientError.PhysicalIdentityUnverified, batch.sessionId.toString(), raw)
        }
        throw error
    }
    val item = response["item"] as? JsonObject ?: throw InboundClientException(InboundClientError.BoxNotEligible)
    if (item.crossText("status") == "unknown") throw missing
    if (item.crossID("receiving_session_id") != batch.sessionId ||
        item.crossText("status") != "normal" || item.crossText("exception_reason").isNotBlank() ||
        response.crossText("virtual_recovery_required") == "true"
    ) {
        throw InboundClientException(InboundClientError.BoxNotEligible)
    }
    val code = item.crossText("label_code")
    if (item.crossText("resolution_type") != "adjustment_alias" || item.crossID("source_box_plan_id") <= 0 || code.isBlank()) throw missing
    val snapshot = resolveInboundCarton(batch, code) { throw InboundClientException(InboundClientError.BoxOwnershipConflict) }
    if (snapshot == null || snapshot.boxPlanId != item.crossID("box_plan_id")) throw InboundClientException(InboundClientError.BoxOwnershipConflict)
    return snapshot
}

// Cross-order attempts retain their source batch for operation history, while all
// remote facts and progress belong to effectiveSessionId. They never become A's boxes.
@Singleton
class CrossOrderReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val database: FscmDatabase,
    private val sessionStore: SessionStore,
    private val scheduler: LocalInboundSyncScheduler,
    private val deviceIdentityManager: DeviceIdentityManager,
) {
    private val mutex = Mutex()
    private val prepareMutex = Mutex()
    private val dao get() = database.crossOrderScanDao()

    suspend fun observe(sourceId: Long): Flow<List<CrossOrderScanEntity>> {
        val session = sessionStore.current()
        return dao.observe(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, sourceId)
    }

    suspend fun batchCount(batchId: String): Int {
        val session = sessionStore.current()
        return dao.batchCount(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, batchId)
    }

    suspend fun prepare(sourceId: Long, force: Boolean = false) = prepareMutex.withLock {
        prepareLocked(sourceId, force)
    }

    private suspend fun prepareLocked(sourceId: Long, force: Boolean) {
        val session = sessionStore.current()
        if (session.isOffline) return
        val user = session.user?.id ?: 0
        val namespace = session.currentNamespace?.id ?: 0
        val cache = database.offlineCacheDao()
        val now = System.currentTimeMillis()
        val existing = cache.find(user, namespace, "cross-order:$sourceId")
        if (!force && existing != null && now - existing.updatedAt < 300_000) return
        var catalog: JsonObject = api.get("receiving-sessions/$sourceId/cross-order-catalog", mapOf("after_id" to "0"), headers = inboundScopeHeaders(user, namespace))
        val header = JsonObject(catalog.filterKeys { it != "items" })
        val warehouse = header.crossID("warehouse_id")
        check(warehouse > 0 && header.crossID("source_session_id") == sourceId)
        val warehouseKey = "cross-order-wh:$warehouse"
        val shared = cache.find(user, namespace, warehouseKey)
        val rows = mutableListOf<CrossOrderCatalogBox>()
        val replace = force || shared == null || now - shared.updatedAt >= 300_000
        if (replace) {
            var cursor = 0L
            while (true) {
                check(catalog.crossID("warehouse_id") == warehouse && catalog.crossID("source_session_id") == sourceId)
                val page = catalog.getValue("items").jsonArray.map { it.jsonObject.toCatalogBox(user, namespace, warehouse) }
                var lastId = cursor
                for (box in page) {
                    check(box.boxPlanId > lastId) { "跨单目录箱计划重复或乱序" }
                    lastId = box.boxPlanId
                }
                rows += page
                val next = catalog.crossID("next_after_id")
                if (next == 0L) break
                check(next == lastId && next > cursor) { "跨单目录分页异常" }
                cursor = next
                checkScope(user, namespace)
                catalog = api.get("receiving-sessions/$sourceId/cross-order-catalog", mapOf("after_id" to cursor.toString()), headers = inboundScopeHeaders(user, namespace))
            }
        }
        database.withTransaction {
            checkScope(user, namespace)
            if (replace) {
                database.crossOrderCatalogDao().clear(user, namespace, warehouse)
                database.crossOrderCatalogDao().insert(rows)
                cache.upsert(OfflineCacheEntity(userId = user, namespaceId = namespace, cacheKey = warehouseKey, payload = "{}", updatedAt = now))
            }
            cache.upsert(OfflineCacheEntity(userId = user, namespaceId = namespace, cacheKey = "cross-order:$sourceId", payload = header.toString(), updatedAt = now))
        }
    }

    // A normal same-order preview falls through to the established pipeline.
    // Every unresolved/cross-order attempt is durable before it leaves this method.
    suspend fun intercept(sourceId: Long, raw: String, operation: String, batch: LocalInboundBatchEntity? = null, previewMissing: Boolean = false): LocalInboundSnapshotEntity? = mutex.withLock {
        val session = sessionStore.current()
        session.requireOfflineOperationPermission("receiving")
        val user = session.user?.id ?: 0
        val namespace = session.currentNamespace?.id ?: 0
        if (batch != null) check(batch.userId == user && batch.namespaceId == namespace && batch.sessionId == sourceId)
        val batchId = batch?.id.orEmpty()
        val locationId = batch?.warehouseLocationId ?: 0L
        val locationCode = batch?.locationCode.orEmpty()
        val outstanding = dao.outstanding(user, namespace, sourceId, raw, operation)
        if (outstanding != null) throw CrossOrderScanPending(outstanding)
        val event = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("raw_code", raw)
            put("cross_order_policy", "confirm")
            put("device_id", deviceIdentityManager.deviceId())
            put("scan_source", if (session.isOffline) "mobile_offline" else "mobile_online")
            put("client_event_id", event)
            put("request_id", event)
            if (operation == "inbound") {
                put("operation_mode", "scan")
                put("warehouse_location_id", locationId)
                put("location_code", locationCode)
            } else {
                put("tally_count", true)
            }
        }
        var task = CrossOrderScanEntity(event, user, namespace, sourceId, batchId, operation, raw, request.toString())
        if (operation == "inbound") {
            val inboundBatch = requireNotNull(batch)
            suspend fun saveConfirmation(confirmation: JsonObject) {
                checkScope(user, namespace)
                task = task.copy(confirmationPayload = confirmation.toString(), state = "awaiting_confirmation", message = "此箱属于 ${confirmation.crossText("target_order_code")}，请确认")
                dao.save(task)
            }
            val localSnapshot = try {
                database.resolveInboundCarton(inboundBatch, raw, ::saveConfirmation)
            } catch (error: InboundClientException) {
                if (error.reason != InboundClientError.BoxOfflineNotFound || !previewMissing || session.isOffline) throw error
                try {
                    database.previewMissingInboundCarton(inboundBatch, raw) {
                        api.post<JsonObject>(
                            "receiving-sessions/$sourceId/scan-preview",
                            headers = inboundScopeHeaders(user, namespace),
                            body = JsonObject(request + ("operation" to JsonPrimitive(operation))),
                        ).also { checkScope(user, namespace) }
                    }
                } catch (remote: FscmApiException) {
                    val confirmation = remote.crossConfirmation() ?: throw remote
                    saveConfirmation(confirmation)
                    throw CrossOrderScanPending(task)
                } catch (rejection: InboundClientException) {
                    if (rejection.reason != InboundClientError.PhysicalIdentityUnverified) throw rejection
                    throw InboundReviewOffer(task, locationCode)
                }
            }
            if (localSnapshot == null) throw CrossOrderScanPending(task)
            return@withLock localSnapshot
        }

        dao.save(task)
        if (!session.isOffline) {
            try {
                api.post<JsonObject>("receiving-sessions/$sourceId/scan-preview", JsonObject(request + ("operation" to JsonPrimitive(operation))))
                dao.delete(task.id, user, namespace)
                return@withLock null
            } catch (error: FscmApiException) {
                val confirmation = error.crossConfirmation()
                if (confirmation != null) {
                    task = task.copy(confirmationPayload = confirmation.toString(), state = "awaiting_confirmation", message = "此箱属于 ${confirmation.crossText("target_order_code")}，请确认")
                } else {
                    dao.delete(task.id, user, namespace)
                    throw error
                }
            } catch (error: java.io.IOException) {
                // Fall through to cached ownership, preserving the original request.
            }
        }
        if (task.state == "pending") {
            val cached = database.offlineCacheDao().find(user, namespace, "cross-order:$sourceId")
            val catalog = cached?.let { Json.parseToJsonElement(it.payload).jsonObject }
            val code = runCatching { parseBoxLabelQr(raw).labelCode }.getOrDefault(raw).trim()
            val matches = database.catalogMatches(catalog, user, namespace, listOf(code))
            if (catalog != null && matches.size == 1) {
                val box = matches.single()
                if (box.crossID("target_order_id") == catalog.crossID("source_order_id")) {
                    dao.delete(task.id, user, namespace)
                    return@withLock null
                }
                val confirmation = offlineCrossOrderConfirmation(catalog, box, operation, locationId, locationCode)
                task = task.copy(confirmationPayload = confirmation.toString(), state = "awaiting_confirmation", message = "离线识别为 ${box.crossText("target_order_code")}，确认后待同步")
            }
        }
        dao.save(task)
        scheduler.enqueueSoon()
        throw CrossOrderScanPending(task)
    }

    suspend fun confirm(id: String) = mutex.withLock {
        var task = scopedTask(id)
        check(task.state == "awaiting_confirmation")
        val confirmation = task.confirmation() ?: error("确认信息缺失，请联网重新识别")
        val previous = Json.parseToJsonElement(task.requestPayload).jsonObject
        val event = UUID.randomUUID().toString()
        val request = JsonObject(
            previous + buildJsonObject {
                put("client_event_id", event)
                put("request_id", event)
                put("confirm_cross_order", true)
                put("expected_target_order_id", confirmation.crossID("target_order_id"))
                put("expected_box_plan_id", confirmation.crossID("box_plan_id"))
                put("cross_order_snapshot", confirmation.crossText("cross_order_snapshot"))
                val receipt = confirmation["scan_receipt"]?.jsonObject?.crossID("id") ?: 0
                if (receipt > 0) put("parent_scan_receipt_id", receipt)
            },
        )
        task = task.copy(requestPayload = request.toString(), state = "confirmed_pending", message = "已确认，待联网同步", updatedAt = System.currentTimeMillis())
        dao.save(task)
        scheduler.enqueueSoon()
        if (task.operation != "inbound" && !sessionStore.current().isOffline) execute(task)
    }

    suspend fun submitOriginalScan(offer: InboundReviewOffer) = mutex.withLock {
        val task = offer.task
        checkScope(task.userId, task.namespaceId)
        if (!database.stageOriginalScan(offer)) return@withLock
        scheduler.enqueueSoon()
        execute(task)
        Unit
    }

    suspend fun retry(id: String) = mutex.withLock {
        var task = scopedTask(id)
        if (task.state == "needs_identification") {
            val event = UUID.randomUUID().toString()
            val old = Json.parseToJsonElement(task.requestPayload).jsonObject
            val payload = JsonObject(old.filterKeys { it !in setOf("confirm_cross_order", "expected_target_order_id", "expected_box_plan_id", "cross_order_snapshot", "parent_scan_receipt_id") } + mapOf("client_event_id" to JsonPrimitive(event), "request_id" to JsonPrimitive(event)))
            task = task.copy(requestPayload = payload.toString(), state = "pending", confirmationPayload = "")
            dao.save(task)
        }
        if (task.state != "awaiting_confirmation" && task.state != "completed") execute(task)
    }

    suspend fun hasPendingInbound(sourceSessionId: Long): Boolean {
        val session = sessionStore.current()
        return dao.pending(session.user?.id ?: 0, session.currentNamespace?.id ?: 0)
            .any { it.sourceSessionId == sourceSessionId && it.operation == "inbound" }
    }

    suspend fun sync(sourceSessionId: Long? = null, operation: String? = null): Boolean = mutex.withLock {
        val session = sessionStore.current()
        if (session.isOffline || session.user == null) return@withLock false
        var complete = true
        val rows = dao.pending(session.user.id, session.currentNamespace?.id ?: 0)
            .filter { (sourceSessionId == null || it.sourceSessionId == sourceSessionId) && (operation == null || it.operation == operation) }
        // Grouping affects upload order only; each immutable event is acknowledged independently.
        rows.groupBy { it.confirmation()?.crossID("target_order_id") ?: 0 }.values.forEach { group ->
            group.forEach { if (!execute(it)) complete = false }
        }
        complete
    }

    private suspend fun execute(task: CrossOrderScanEntity): Boolean {
        checkScope(task.userId, task.namespaceId)
        if (sessionStore.current().isOffline) return false
        try {
            val result: JsonObject = api.post("receiving-sessions/${task.sourceSessionId}/${if (task.operation == "inbound") "inbound-scan" else "scan"}", body = Json.parseToJsonElement(task.requestPayload), headers = inboundScopeHeaders(task.userId, task.namespaceId))
            val receipt = result["scan_receipt"]?.jsonObject
            check(receipt?.get("persisted")?.jsonPrimitive?.content == "true") { "服务端尚未确认保存，保留原事件待重试" }
            val completed = receipt.crossText("state") in setOf("completed", "duplicate")
            val target = result.crossID("effective_session_id").takeIf { it > 0 } ?: task.sourceSessionId
            dao.save(task.copy(state = if (completed) "completed" else "blocked", effectiveSessionId = target, resultPayload = result.toString(), message = if (completed) "${if (task.operation == "inbound") "库位已同步，采购入库待批次完成" else "已收货"}：${result.crossText("effective_order_code")}" else "扫描已保存，业务尚未完成", updatedAt = System.currentTimeMillis()))
            if (completed) {
                dao.invalidateInbound(task.userId, task.namespaceId, target)
                dao.invalidateReceiving(task.userId, task.namespaceId, target)
                scheduler.enqueueCacheRefresh(target, userId = task.userId, namespaceId = task.namespaceId)
            }
            return true
        } catch (error: FscmApiException) {
            val confirmation = error.crossConfirmation()
            val data = (error.payload as? JsonObject)?.get("data") as? JsonObject
            val receipt = data?.get("scan_receipt") as? JsonObject
            val reviewId = receipt?.takeIf { it.crossText("persisted") == "true" && it.crossText("state") in setOf("awaiting_assignment", "needs_review") }?.crossID("id") ?: 0
            dao.save(
                task.copy(
                    state = if (confirmation != null) {
                        "awaiting_confirmation"
                    } else if ((error.payload as? JsonObject)?.crossID("code") == 40977L) {
                        "needs_identification"
                    } else if (error.statusCode >= 500) {
                        task.state
                    } else {
                        "blocked"
                    },
                    confirmationPayload = confirmation?.toString() ?: task.confirmationPayload,
                    resultPayload = data?.toString() ?: task.resultPayload,
                    message = if (reviewId > 0) "中心扫码留痕 #$reviewId：${error.serverMessage.orEmpty()}。请在网页仓库收货的「扫码留痕」处理。" else error.serverMessage ?: "同步失败，请处理",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return error.statusCode < 500
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            dao.save(task.copy(message = "待同步：${error.message.orEmpty()}", updatedAt = System.currentTimeMillis()))
            return false
        }
    }

    private suspend fun scopedTask(id: String): CrossOrderScanEntity {
        val session = sessionStore.current()
        session.requireOfflineOperationPermission("receiving")
        return dao.get(session.user?.id ?: 0, session.currentNamespace?.id ?: 0, id) ?: error("当前账号没有此扫描记录")
    }

    private suspend fun checkScope(user: Long, namespace: Long) {
        val session = sessionStore.current()
        check(session.user?.id == user && session.currentNamespace?.id == namespace) { "账号或工作空间已变化，扫描保留在原账号" }
        session.requireOfflineOperationPermission("receiving")
    }
}

private fun FscmApiException.crossConfirmation(): JsonObject? {
    val root = payload as? JsonObject ?: return null
    val data = root["data"] as? JsonObject ?: root
    return data.takeIf { it.crossText("confirmation_type") == "cross_order_receiving" }
}
