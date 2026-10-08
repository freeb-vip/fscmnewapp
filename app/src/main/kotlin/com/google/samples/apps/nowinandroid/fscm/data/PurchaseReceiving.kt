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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PurchaseReceiptRequest(
    @SerialName("request_id") val requestId: String = UUID.randomUUID().toString(),
    val mode: String = "normal",
    val fingerprint: String = "",
)

@Serializable
data class PurchaseReceiptLine(
    @SerialName("label_code") val labelCode: String = "",
    val sku: String = "",
    val quantity: Long = 0,
    @SerialName("location_code") val locationCode: String = "",
)

@Serializable
data class PurchaseReceiptPreview(
    val items: List<PurchaseReceiptLine> = emptyList(),
    val skipped: List<String> = emptyList(),
    val fingerprint: String = "",
    @SerialName("box_count") val boxCount: Int = 0,
    val quantity: Long = 0,
)

@Serializable
data class PurchaseWaitReason(
    val code: String = "",
    val message: String = "",
)

@Serializable
data class PurchaseReceiptResult(
    @SerialName("waiting_reason") val waitingReason: PurchaseWaitReason? = null,
)

@Serializable
data class PurchaseReceiptJob(
    val result: PurchaseReceiptResult = PurchaseReceiptResult(),
    val receipt: ReceiptRecord? = null,
    val id: String = "",
    val kind: String = "",
    val status: String = "",
    @SerialName("error_message") val errorMessage: String = "",
)

@Serializable
data class PurchaseBoxState(
    val status: String = "",
    val quantity: Long = 0,
    @SerialName("received_quantity") val receivedQuantity: Long = 0,
)

@Serializable
data class PurchaseReceivingState(
    val cached: Boolean = false,
    @SerialName("recent_receipts") val recentReceipts: List<ReceiptRecord> = emptyList(),
    @SerialName("receipt_record_count") val receiptRecordCount: Long = 0,
    @SerialName("receipt_summary") val receiptSummary: ReceiptProductProgress = ReceiptProductProgress(),
    @SerialName("snapshot_at") val snapshotAt: String = "",
    val products: List<ReceiptProductProgress> = emptyList(),
    val enabled: Boolean = false,
    val boxes: Map<String, PurchaseBoxState> = emptyMap(),
    val jobs: List<PurchaseReceiptJob> = emptyList(),
)

@Serializable
internal data class InventoryWorkspaceConfig(
    val status: String = "unconfigured",
    val message: String = "",
    val version: Long = 0,
) {
    fun validateDraft(expectedVersion: Long? = null) {
        check(status == "configured") { message.ifBlank { "请管理员配置工作空间仓库" } }
        check(expectedVersion == null || expectedVersion == version) { "仓库配置已改变，请重新预览；已采集扫描数据仍保留" }
    }
}

@Serializable
internal data class SavedPurchaseReceipt(
    val sessionId: Long,
    val request: PurchaseReceiptRequest,
    val submitted: Boolean = false,
    val jobId: String = "",
    val status: String = "draft",
    val configVersion: Long = 0,
)

/** Persists user-confirmed submissions separately from scan uploads. */
@Singleton
class PurchaseReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    @param:ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences("purchase_receipt_submissions", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private fun scopeHeaders(key: String): Map<String, String> {
        val parts = key.split(':')
        return mapOf("X-FSCM-Expected-User-ID" to parts[0], "X-FSCM-Expected-Namespace-ID" to parts[1])
    }

    private suspend fun prefix(): String {
        val session = sessionStore.current()
        check(session.user != null && session.currentNamespace != null) { "请先登录并选择工作空间" }
        return "${session.user.id}:${session.currentNamespace.id}:"
    }

    suspend fun state(sessionId: Long): PurchaseReceivingState =
        api.get("receiving-purchases/sessions/$sessionId")

    suspend fun canReceive(): Boolean {
        val session = sessionStore.current()
        return !session.isOffline && (session.effectiveRole == "admin" || "receiving_purchase" in session.permissions)
    }

    suspend fun retry(jobId: String) {
        check(canReceive()) { "没有采购入库权限，或当前处于离线模式" }
        api.post<kotlinx.serialization.json.JsonElement>("receiving-purchases/jobs/$jobId/retry", headers = scopeHeaders(prefix()), body = json.parseToJsonElement("""{"request_id":"${UUID.randomUUID()}"}"""))
    }

    suspend fun preview(sessionId: Long): PurchaseReceiptPreview = mutex.withLock {
        check(!sessionStore.current().isOffline) { "请联网并上传扫描数据后，再提交采购入库" }
        val key = prefix() + sessionId
        val old = read(key)
        check(old == null || !old.submitted || old.status == "succeeded") { "已有提交正在处理，请先查看原批次结果" }
        val config: InventoryWorkspaceConfig = api.get("inventory-management/config")
        config.validateDraft()
        check(key.startsWith(prefix())) { "工作空间已切换，请重新预览" }
        val request = PurchaseReceiptRequest()
        val preview: PurchaseReceiptPreview = api.post("receiving-purchases/sessions/$sessionId/preview", headers = scopeHeaders(key), body = json.encodeToJsonElement(request))
        check(preview.items.isNotEmpty()) { preview.skipped.joinToString("；").ifBlank { "没有已上传且可采购入库的数据" } }
        check(key.startsWith(prefix())) { "工作空间已切换，请重新预览" }
        save(key, SavedPurchaseReceipt(sessionId, request.copy(fingerprint = preview.fingerprint), configVersion = config.version))
        preview
    }

    suspend fun submit(sessionId: Long): PurchaseReceiptJob = mutex.withLock {
        val key = prefix() + sessionId
        val saved = requireNotNull(read(key)) { "请先预览采购收货明细" }
        if (!saved.submitted) {
            val config: InventoryWorkspaceConfig = api.get("inventory-management/config")
            config.validateDraft(saved.configVersion)
            check(key.startsWith(prefix())) { "工作空间已切换，请重新预览" }
        }
        save(key, saved.copy(submitted = true, status = "submitting"))
        resume(key, saved.copy(submitted = true))
    }

    suspend fun resumePending(): Boolean = mutex.withLock {
        if (sessionStore.current().isOffline) return@withLock false
        val scope = prefix()
        var complete = true
        for (key in preferences.all.keys.filter { it.startsWith(scope) }) {
            val saved = read(key) ?: continue
            if (!saved.submitted || saved.status == "succeeded") continue
            runCatching { resume(key, saved) }
                .onSuccess { if (it.status in setOf("queued", "running")) complete = false }
                .onFailure { complete = false }
        }
        complete
    }

    private suspend fun resume(key: String, saved: SavedPurchaseReceipt): PurchaseReceiptJob {
        check(key.startsWith(prefix())) { "采购批次属于其他账号或工作空间" }
        val job: PurchaseReceiptJob = if (saved.jobId.isBlank()) {
            api.post("receiving-purchases/sessions/${saved.sessionId}/receipts", headers = scopeHeaders(key), body = json.encodeToJsonElement(saved.request))
        } else {
            api.get("receiving-purchases/jobs/${saved.jobId}", headers = scopeHeaders(key))
        }
        check(key.startsWith(prefix())) { "账号或工作空间已切换，请返回原账号查询批次" }
        save(key, saved.copy(jobId = job.id, status = job.status, submitted = true))
        return job
    }

    private fun read(key: String): SavedPurchaseReceipt? =
        preferences.getString(key, null)?.let { json.decodeFromString<SavedPurchaseReceipt>(it) }

    private fun save(key: String, value: SavedPurchaseReceipt) {
        check(preferences.edit().putString(key, json.encodeToString(value)).commit()) { "无法保存采购入库批次，请检查设备存储" }
    }
}
