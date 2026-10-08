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
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AutoReceiptScanRef(val requestId: String, val crossOrder: Boolean = false)

@Entity(tableName = "auto_receipt_intents", indices = [Index(value = ["userId", "namespaceId", "sessionId"])])
data class AutoReceiptIntentEntity(
    @PrimaryKey val batchId: String,
    val userId: Long,
    val namespaceId: Long,
    val sessionId: Long,
    val scanRefsJson: String,
    val createdAt: Long,
)

@Entity(tableName = "auto_receipt_submissions", indices = [Index(value = ["userId", "namespaceId", "sourceSessionId"])])
data class AutoReceiptSubmissionEntity(
    @PrimaryKey val requestId: String,
    val userId: Long,
    val namespaceId: Long,
    val sourceSessionId: Long,
    val sessionId: Long,
    val batchId: String,
    val scanRefsJson: String,
    val payload: String,
    val jobId: String = "",
    val status: String = "pending",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface AutoReceiptDao {
    @Query("SELECT * FROM auto_receipt_scan_items WHERE userId=:user AND namespaceId=:namespace AND sessionId=:session ORDER BY batchId,requestId")
    fun observeScans(user: Long, namespace: Long, session: Long): Flow<List<AutoReceiptScanSnapshot>>

    @Query("SELECT * FROM auto_receipt_scan_items WHERE id=:id")
    suspend fun scan(id: String): AutoReceiptScanSnapshot?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScan(row: AutoReceiptScanSnapshot)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIntent(intent: AutoReceiptIntentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubmission(submission: AutoReceiptSubmissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSubmission(submission: AutoReceiptSubmissionEntity)

    @Query("SELECT * FROM auto_receipt_intents WHERE userId=:user AND namespaceId=:namespace ORDER BY createdAt,batchId")
    suspend fun intents(user: Long, namespace: Long): List<AutoReceiptIntentEntity>

    @Query("SELECT * FROM auto_receipt_submissions WHERE userId=:user AND namespaceId=:namespace ORDER BY createdAt,requestId")
    suspend fun submissions(user: Long, namespace: Long): List<AutoReceiptSubmissionEntity>

    @Query("SELECT * FROM auto_receipt_submissions WHERE userId=:user AND namespaceId=:namespace AND sourceSessionId=:session ORDER BY createdAt DESC")
    fun observeSubmissions(user: Long, namespace: Long, session: Long): Flow<List<AutoReceiptSubmissionEntity>>

    @Query("UPDATE auto_receipt_submissions SET status='running',errorMessage='' WHERE userId=:user AND namespaceId=:namespace AND jobId=:job")
    suspend fun resumeJob(user: Long, namespace: Long, job: String)
}

@Serializable
data class AutoReceiptItem(
    @SerialName("box_plan_id") val boxPlanId: Long,
    @SerialName("scan_request_id") val scanRequestId: String,
    @SerialName("scan_ack_id") val scanAckId: String = "",
)

@Serializable
data class AutoReceiptRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("source_batch_id") val sourceBatchId: String,
    val items: List<AutoReceiptItem>,
)

@Serializable
data class ReceiptIssue(
    @SerialName("box_plan_id") val boxPlanId: Long = 0,
    @SerialName("label_code") val labelCode: String = "",
    val code: String = "",
    val message: String = "",
)

@Serializable
data class ReceiptLineDetail(
    @SerialName("box_plan_id") val boxPlanId: Long = 0,
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("product_id") val productId: Long = 0,
    val sku: String = "",
    val quantity: Long = 0,
    @SerialName("location_code") val locationCode: String = "",
    @SerialName("external_number") val externalNumber: String = "",
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("job_id") val jobId: String = "",
    val status: String = "pending",
)

@Serializable
data class ReceiptProductProgress(
    @SerialName("product_id") val productId: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_codes") val skuCodes: List<String> = emptyList(),
    @SerialName("total_quantity") val totalQuantity: Long = 0,
    @SerialName("received_quantity") val receivedQuantity: Long = 0,
    @SerialName("pending_quantity") val pendingQuantity: Long = 0,
    @SerialName("failed_quantity") val failedQuantity: Long = 0,
    @SerialName("processing_quantity") val processingQuantity: Long = 0,
    @SerialName("unknown_quantity") val unknownQuantity: Long = 0,
    @SerialName("pending_move_quantity") val pendingMoveQuantity: Long = 0,
    @SerialName("virtual_quantity") val virtualQuantity: Long = 0,
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("received_box_count") val receivedBoxCount: Int = 0,
    @SerialName("pending_box_count") val pendingBoxCount: Int = 0,
    @SerialName("failed_box_count") val failedBoxCount: Int = 0,
    val items: List<ReceiptLineDetail> = emptyList(),
)

@Serializable
data class ReceiptRecord(
    @SerialName("waiting_reason") val waitingReason: PurchaseWaitReason? = null,
    @SerialName("last_attempt_status") val lastAttemptStatus: String = "",
    @SerialName("last_attempt_error") val lastAttemptError: String = "",
    @SerialName("attempt_issues") val attemptIssues: List<ReceiptIssue> = emptyList(),
    @SerialName("retry_job_count") val retryJobCount: Int = 0,
    @SerialName("retry_box_count") val retryBoxCount: Int = 0,
    val id: String = "",
    @SerialName("session_id") val sessionId: Long = 0,
    @SerialName("source_batch_id") val sourceBatchId: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("external_numbers") val externalNumbers: List<String> = emptyList(),
    @SerialName("location_codes") val locationCodes: List<String> = emptyList(),
    @SerialName("received_quantity") val receivedQuantity: Long = 0,
    @SerialName("pending_quantity") val pendingQuantity: Long = 0,
    @SerialName("failed_quantity") val failedQuantity: Long = 0,
    @SerialName("processing_quantity") val processingQuantity: Long = 0,
    @SerialName("unknown_quantity") val unknownQuantity: Long = 0,
    val issues: List<ReceiptIssue> = emptyList(),
    val items: List<ReceiptLineDetail> = emptyList(),
)

@Serializable
data class ReceiptRecordPage(val items: List<ReceiptRecord> = emptyList(), val total: Long = 0, val page: Int = 1)

@Serializable
data class ReceiptAttempt(val id: Long, val status: String, @SerialName("created_at") val createdAt: String, val details: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) {
    fun reason(): String {
        val direct = details.crossText("error")
        if (direct.isNotBlank()) return direct
        val result = details["result"] as? kotlinx.serialization.json.JsonObject
        val issues = (details["issues"] ?: result?.get("issues")) as? kotlinx.serialization.json.JsonArray
        return issues?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.crossText("message") }?.distinct()?.joinToString("；").orEmpty()
    }
}

@Serializable
data class ReceiptAttemptPage(val items: List<ReceiptAttempt> = emptyList(), val total: Long = 0, val page: Int = 1)

internal fun receiptTaskActive(status: String): Boolean = status in setOf("pending", "submitting", "queued", "running", "waiting_purchase")

internal fun receiptTaskRetryable(status: String): Boolean = status in setOf("failed", "blocked_data", "blocked_config", "partial_failed", "cancelled")

internal fun autoReceiptRequestId(batchId: String, sessionId: Long, refs: List<AutoReceiptScanRef>): String =
    UUID.nameUUIDFromBytes((batchId + ":" + sessionId + ":" + refs.map { "${it.crossOrder}:${it.requestId}" }.sorted().joinToString(",")).toByteArray()).toString()

@Singleton
class AutoPurchaseReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val database: FscmDatabase,
    private val sessionStore: SessionStore,
    private val scheduler: LocalInboundSyncScheduler,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val dao get() = database.autoReceiptDao()

    private suspend fun scope(): Pair<Long, Long> {
        val session = sessionStore.current()
        return requireNotNull(session.user).id to requireNotNull(session.currentNamespace).id
    }

    private fun headers(scope: Pair<Long, Long>) = mapOf("X-FSCM-Expected-User-ID" to scope.first.toString(), "X-FSCM-Expected-Namespace-ID" to scope.second.toString())

    suspend fun observe(sessionId: Long): Flow<List<AutoReceiptSubmissionEntity>> {
        val (user, namespace) = scope()
        return dao.observeSubmissions(user, namespace, sessionId)
    }

    suspend fun history(sessionId: Long): List<LocalInboundBatchEntity> {
        val (user, namespace) = scope()
        val queued = dao.intents(user, namespace).map { it.batchId }.toSet()
        return database.localInboundDao().batches(user, namespace, sessionId).filter { it.completedAt > 0 && it.id !in queued }
    }

    suspend fun queueHistory(batchId: String) {
        val current = scope()
        database.withTransaction {
            val batch = requireNotNull(database.localInboundDao().batch(batchId))
            check(batch.userId == current.first && batch.namespaceId == current.second && batch.completedAt > 0)
            database.localInboundDao().saveAutoReceiptIntent(batchId)
        }
        scheduler.enqueueSoon()
    }

    suspend fun sync(): Boolean = mutex.withLock {
        val session = sessionStore.current()
        if (session.isOffline || session.user == null || session.currentNamespace == null) return@withLock false
        val current = scope()
        val (user, namespace) = current
        val canReceive = session.effectiveRole == "admin" || "receiving_purchase" in session.permissions
        var complete = true
        val saved = dao.submissions(user, namespace)
        val covered = saved.flatMap { json.decodeFromString<List<AutoReceiptScanRef>>(it.scanRefsJson) }.toSet()
        for (intent in dao.intents(user, namespace)) {
            check(scope() == current) { "账号或工作空间已切换" }
            val ready = mutableListOf<Triple<Long, AutoReceiptScanRef, AutoReceiptItem>>()
            for (ref in json.decodeFromString<List<AutoReceiptScanRef>>(intent.scanRefsJson).filter { it !in covered }) {
                val snapshot = dao.scan("${intent.batchId}:${ref.crossOrder}:${ref.requestId}")
                    ?: AutoReceiptScanSnapshot("${intent.batchId}:${ref.crossOrder}:${ref.requestId}", intent.batchId, user, namespace, intent.sessionId, ref.requestId, ref.crossOrder, "")
                try {
                    val ack = resolveAck(snapshot, current)
                    if (ack != null) ready += Triple(ack.sessionId, ref, AutoReceiptItem(ack.boxPlanId, ack.requestId, ack.id))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val latest = dao.scan(snapshot.id) ?: snapshot
                    dao.saveScan(latest.copy(errorMessage = error.message.orEmpty()))
                    if (error is java.io.IOException || (error as? FscmApiException)?.statusCode?.let { it >= 500 } == true) complete = false
                }
            }
            for ((target, scans) in ready.groupBy { it.first }) {
                for (chunk in scans.sortedBy { it.third.boxPlanId }.chunked(100)) {
                    val refs = chunk.map { it.second }
                    val id = autoReceiptRequestId(intent.batchId, target, refs)
                    val request = AutoReceiptRequest(id, intent.batchId, chunk.map { it.third }.distinctBy { it.boxPlanId })
                    dao.insertSubmission(AutoReceiptSubmissionEntity(id, user, namespace, intent.sessionId, target, intent.batchId, json.encodeToString(refs), json.encodeToString(request)))
                }
            }
        }
        for (savedRequest in dao.submissions(user, namespace)) {
            if (savedRequest.jobId.isNotBlank() && savedRequest.status == "succeeded") continue
            check(scope() == current) { "账号或工作空间已切换" }
            if (savedRequest.jobId.isBlank() && !canReceive) {
                dao.saveSubmission(savedRequest.copy(status = "permission_denied", errorMessage = "扫码已同步；没有采购入库权限，尚未采购入库"))
                continue
            }
            runCatching {
                val job = resumeAutoReceiptSubmission(
                    savedRequest,
                    validateScope = { check(scope() == current) { "账号或工作空间已切换" } },
                    persist = dao::saveSubmission,
                    submit = { saved -> api.post("receiving-purchases/sessions/${saved.sessionId}/auto-receipts", headers = headers(current), body = json.parseToJsonElement(saved.payload)) },
                    query = { id -> api.get("receiving-purchases/jobs/$id", headers = headers(current)) },
                )
                if (receiptTaskActive(job.status)) complete = false
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val forbidden = (error as? FscmApiException)?.statusCode == 403
                dao.saveSubmission(savedRequest.copy(status = if (forbidden) "permission_denied" else "pending", errorMessage = error.message.orEmpty()))
                if (!forbidden) complete = false
            }
        }
        complete
    }

    suspend fun observeScans(sessionId: Long): Flow<List<AutoReceiptScanSnapshot>> {
        val (user, namespace) = scope()
        return dao.observeScans(user, namespace, sessionId)
    }

    private suspend fun resolveAck(snapshot: AutoReceiptScanSnapshot, current: Pair<Long, Long>): InboundScanAck? {
        check(scope() == current)
        if (snapshot.ackJson.isNotBlank()) return json.decodeFromString(snapshot.ackJson)
        var request = snapshot.requestId
        var target = snapshot.sessionId
        var inlineAck: InboundScanAck? = null
        if (snapshot.scanJson.isNotBlank()) {
            if (snapshot.crossOrder) {
                val scan = json.decodeFromString<CrossOrderScanEntity>(snapshot.scanJson)
                if (scan.state != "completed") return null
                target = scan.effectiveSessionId
                request = json.parseToJsonElement(scan.requestPayload).jsonObject.crossText("request_id")
                inlineAck = scan.resultPayload.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it).jsonObject["scan_ack"] }?.takeIf { it is kotlinx.serialization.json.JsonObject }?.let { json.decodeFromJsonElement(InboundScanAck.serializer(), it) }
            } else {
                val scan = json.decodeFromString<LocalInboundItemEntity>(snapshot.scanJson)
                if (scan.status in setOf("accept_remote_pending", "remote_accepted")) {
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("request_id", kotlinx.serialization.json.JsonPrimitive("accept-${scan.requestId}"))
                        put("warehouse_location_id", kotlinx.serialization.json.JsonPrimitive(scan.remoteWarehouseLocationId))
                        put("location_code", kotlinx.serialization.json.JsonPrimitive(scan.remoteLocationCode))
                    }
                    inlineAck = api.post("receiving-sessions/${scan.sessionId}/inbound-items/${scan.boxPlanId}/accept-location", headers = headers(current), body = payload)
                    check(scope() == current)
                    val accepted = scan.copy(status = "remote_accepted", errorMessage = "", syncedAt = System.currentTimeMillis())
                    database.withTransaction {
                        database.localInboundDao().updateItemResult(scan.requestId, accepted.status, scan.remoteLocationCode, scan.remoteWarehouseLocationId, scan.boxPlanId, 0, "", accepted.syncedAt, ackJson = json.encodeToString(inlineAck))
                        database.localInboundDao().updateSnapshotLocation(scan.userId, scan.namespaceId, scan.sessionId, scan.boxPlanId, scan.remoteLocationCode, scan.remoteWarehouseLocationId)
                        dao.saveScan(snapshot.copy(scanJson = json.encodeToString(accepted), ackJson = json.encodeToString(inlineAck), errorMessage = ""))
                    }
                    return inlineAck
                }
                if (scan.status !in setOf("synced", "remote_overridden")) return null
                request = scan.correctionRequestId.ifBlank { scan.requestId }
            }
        }
        val ack = inlineAck ?: run {
            val payload = kotlinx.serialization.json.buildJsonObject { put("request_ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(request)))) }
            val rows: List<ResolvedInboundAck> = api.post("receiving-sessions/$target/inbound-scan-acks/resolve", headers = headers(current), body = payload)
            val row = rows.singleOrNull { it.requestId == request }
            row?.ack ?: error(row?.error ?: "扫描依据缺失，请重新确认")
        }
        check(scope() == current)
        dao.saveScan(snapshot.copy(ackJson = json.encodeToString(ack), errorMessage = ""))
        return ack
    }

    suspend fun confirmSavedLocation(id: String) {
        val current = scope()
        val row = requireNotNull(dao.scan(id))
        check(row.userId == current.first && row.namespaceId == current.second)
        check(!row.crossOrder && row.scanJson.isNotBlank()) { "原扫描数据不足，请在原订单核实箱唛" }
        val scan = json.decodeFromString<LocalInboundItemEntity>(row.scanJson)
        val pending = scan.copy(status = "accept_remote_pending", remoteLocationCode = scan.remoteLocationCode.ifBlank { scan.localLocationCode }, remoteWarehouseLocationId = scan.remoteWarehouseLocationId.takeIf { it > 0 } ?: scan.localWarehouseLocationId)
        dao.saveScan(row.copy(scanJson = json.encodeToString(pending), ackJson = "", errorMessage = ""))
        scheduler.enqueueSoon()
    }

    suspend fun state(sessionId: Long, offline: Boolean = false): PurchaseReceivingState {
        val current = scope()
        val cacheKey = "purchase-receipts:$sessionId"
        if (offline || sessionStore.current().isOffline) {
            val cache = database.offlineCacheDao().find(current.first, current.second, cacheKey)
            return cache?.let { json.decodeFromString<PurchaseReceivingState>(it.payload).copy(cached = true) } ?: PurchaseReceivingState(cached = true)
        }
        val result = try {
            api.get<PurchaseReceivingState>("receiving-purchases/sessions/$sessionId", headers = headers(current))
        } catch (error: java.io.IOException) {
            check(scope() == current)
            val cache = database.offlineCacheDao().find(current.first, current.second, cacheKey) ?: throw error
            return json.decodeFromString<PurchaseReceivingState>(cache.payload).copy(cached = true)
        }
        check(scope() == current) { "账号或工作空间已切换" }
        database.offlineCacheDao().upsert(OfflineCacheEntity(userId = current.first, namespaceId = current.second, cacheKey = cacheKey, payload = json.encodeToString(result), updatedAt = System.currentTimeMillis()))
        return result
    }

    suspend fun record(jobId: String): ReceiptRecord? {
        val current = scope()
        val job: PurchaseReceiptJob = api.get("receiving-purchases/jobs/$jobId", headers = headers(current))
        check(scope() == current) { "账号或工作空间已切换" }
        return job.receipt
    }

    suspend fun records(sessionId: Long, page: Int): ReceiptRecordPage {
        val current = scope()
        val result: ReceiptRecordPage = api.get("receiving-purchases/sessions/$sessionId/receipt-records", mapOf("page" to page.toString(), "page_size" to "20"), headers = headers(current))
        check(scope() == current) { "账号或工作空间已切换" }
        return result
    }

    suspend fun attempts(jobId: String, page: Int): ReceiptAttemptPage {
        val current = scope()
        val result: ReceiptAttemptPage = api.get("receiving-purchases/jobs/$jobId/attempts", mapOf("page" to page.toString(), "page_size" to "20"), headers = headers(current))
        check(scope() == current)
        return result
    }

    suspend fun retry(jobId: String) {
        val current = scope()
        val request = PurchaseReceiptRequest()
        api.post<kotlinx.serialization.json.JsonElement>("receiving-purchases/jobs/$jobId/retry", headers = headers(current), body = json.encodeToJsonElement(request))
        check(scope() == current)
        dao.resumeJob(current.first, current.second, jobId)
        scheduler.enqueueSoon()
    }
}

/** Replays an uncertain send with the exact saved body; a known job is only queried. */
internal suspend fun resumeAutoReceiptSubmission(
    saved: AutoReceiptSubmissionEntity,
    validateScope: suspend () -> Unit,
    persist: suspend (AutoReceiptSubmissionEntity) -> Unit,
    submit: suspend (AutoReceiptSubmissionEntity) -> PurchaseReceiptJob,
    query: suspend (String) -> PurchaseReceiptJob,
): PurchaseReceiptJob {
    validateScope()
    val job = if (saved.jobId.isBlank()) {
        persist(saved.copy(status = "submitting"))
        validateScope()
        submit(saved)
    } else {
        query(saved.jobId)
    }
    require(job.id.isNotBlank()) { "后台未返回入库任务标识，保留原请求等待重试" }
    validateScope()
    persist(saved.copy(jobId = job.id, status = job.status, errorMessage = job.errorMessage))
    return job
}
