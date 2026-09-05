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

import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyCentralReceivingRepository @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val offlineCacheDao: OfflineCacheDao,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    @param:FscmJson private val json: Json,
) {
    suspend fun receipts(filter: BusinessFilter): List<CentralReceipt> {
        val cacheKey = "central_receipts:${filter.name}"
        if (sessionStore.current().isOffline) return cached(cacheKey)
        val params = when (filter) {
            BusinessFilter.Pending -> mapOf("status_group" to "active")
            BusinessFilter.Completed -> mapOf("status_group" to "completed")
            BusinessFilter.All -> emptyMap()
        }
        return runCatching {
            decodeBusinessList<CentralReceipt>(api.get("central-receipts", params), json)
        }.onSuccess { cache(cacheKey, it) }
            .getOrElse { error -> cached<CentralReceipt>(cacheKey).takeIf { it.isNotEmpty() && error !is FscmApiException } ?: throw error }
    }

    suspend fun statistics(): CentralReceiptStatistics {
        val cacheKey = "central_receipt_statistics"
        if (sessionStore.current().isOffline) return cachedObject(cacheKey) ?: CentralReceiptStatistics()
        return runCatching {
            decodeBusinessObject<CentralReceiptStatistics>(api.get("central-receipts/statistics"), json)
        }.onSuccess { cacheObject(cacheKey, it) }
            .getOrElse { error -> cachedObject<CentralReceiptStatistics>(cacheKey).takeIf { it != null && error !is FscmApiException } ?: throw error }
    }

    suspend fun receipt(id: Long): CentralReceipt {
        val cacheKey = "central_receipt:$id"
        if (sessionStore.current().isOffline) return cachedObject(cacheKey) ?: CentralReceipt(id = id)
        return runCatching {
            decodeBusinessObject<CentralReceipt>(api.get("central-receipts/$id"), json)
        }.onSuccess { cacheObject(cacheKey, it) }
            .getOrElse { error -> cachedObject<CentralReceipt>(cacheKey).takeIf { it != null && error !is FscmApiException } ?: throw error }
    }

    suspend fun boxLabels(id: Long): List<CentralReceiptBoxLabel> {
        val cacheKey = "central_receipt_box_labels:$id"
        if (sessionStore.current().isOffline) return cached(cacheKey)
        return runCatching {
            val payload: JsonElement = api.get(
                "central-receipts/box-labels",
                mapOf("central_receipt_id" to id.toString(), "page" to "1", "pageSize" to "100"),
            )
            decodeBusinessList<CentralReceiptBoxLabel>(payload, json)
        }.onSuccess { cache(cacheKey, it) }
            .getOrElse { error -> cached<CentralReceiptBoxLabel>(cacheKey).takeIf { it.isNotEmpty() && error !is FscmApiException } ?: throw error }
    }

    suspend fun preview(id: Long?, rawCode: String): CentralReceiptScanResult {
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) return offlineResult(parsed)
        return runCatching {
            val payload: JsonElement = api.post(
                if (id == null) "central-receipts/box-labels/scan-preview" else "central-receipts/$id/box-labels/scan-preview",
                scanBody(parsed),
            )
            decodeBusinessObject<CentralReceiptScanResult>(payload, json).copy(parsedPayload = parsed)
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            offlineResult(parsed)
        }
    }

    suspend fun scan(id: Long?, rawCode: String, actualBoxQty: Int? = null): CentralReceiptScanResult {
        val parsed = parseBoxLabelQr(rawCode)
        if (sessionStore.current().isOffline) return enqueue(id, parsed, actualBoxQty)
        val path = if (id == null) "central-receipts/box-labels/scan" else "central-receipts/$id/box-labels/scan"
        Log.i("FscmCentralScan", "scan path=$path inputId=$id rawCode=${parsed.originalRawCode}")
        return runCatching {
            decodeBusinessObject<CentralReceiptScanResult>(api.post(path, scanBody(parsed, actualBoxQty)), json)
                .copy(parsedPayload = parsed).also { result ->
                    Log.i("FscmCentralScan", "resolved centralReceiptId=${result.centralReceiptId} labelId=${result.item?.id}")
                }
        }.getOrElse { error ->
            if (error is FscmApiException) throw error
            enqueue(id, parsed, actualBoxQty)
        }
    }

    suspend fun pendingScans(): List<CentralReceiptScanResult> =
        offlineSyncRepository.pendingOperations("central_receiving").mapNotNull { operation ->
            runCatching {
                val payload = json.decodeFromString<CentralReceivingScanOperationPayload>(operation.payload)
                val parsed = runCatching { parseBoxLabelQr(payload.originalRawCode) }.getOrElse {
                    BoxLabelQrPayload(payload.rawCode, payload.rawCode, payload.items, BoxLabelQrFormat.ShortCode)
                }
                offlineResult(parsed).copy(
                    message = operation.lastError.ifBlank {
                        if (operation.status == PendingSyncStatus.Failed.value) "同步失败" else "待联网同步"
                    },
                    pendingOperationId = operation.id,
                    syncStatus = operation.status,
                )
            }.getOrNull()
        }

    suspend fun retryPending(id: Long) {
        offlineSyncRepository.retryPending(id)
        offlineSyncScheduler.enqueue()
    }

    suspend fun cancelPending(id: Long) = offlineSyncRepository.cancelPending(id)

    private suspend fun enqueue(
        id: Long?,
        parsed: BoxLabelQrPayload,
        actualBoxQty: Int? = null,
    ): CentralReceiptScanResult {
        val inserted = offlineSyncRepository.enqueueCentralReceivingScan(
            CentralReceivingScanOperationPayload(
                receiptId = id,
                rawCode = parsed.labelCode,
                actualBoxQty = actualBoxQty,
                originalRawCode = parsed.originalRawCode,
                items = parsed.items,
            ),
        )
        offlineSyncScheduler.enqueue()
        return offlineResult(parsed).copy(
            duplicate = !inserted,
            message = if (inserted) "已保存本地，联网后同步" else "本地重复，已在待同步队列",
        )
    }

    private fun offlineResult(parsed: BoxLabelQrPayload) = CentralReceiptScanResult(
        item = CentralReceiptBoxLabel(
            labelCode = parsed.labelCode,
            boxNo = parsed.labelCode,
            skuCode = parsed.items.joinToString(",") { it.skuCode },
            qtyPerCarton = parsed.totalQuantity,
            actualBoxQty = parsed.totalQuantity,
            qrCodeText = parsed.originalRawCode,
            status = "pending_sync",
        ),
        status = "pending_sync",
        message = "当前离线，可保存待同步",
        parsedPayload = parsed,
        localFallback = true,
    )

    private fun scanBody(parsed: BoxLabelQrPayload, actualBoxQty: Int? = null) = buildJsonObject {
        put("raw_code", parsed.labelCode)
        actualBoxQty?.let { put("actual_box_qty", it) }
    }

    private suspend inline fun <reified T> cached(key: String): List<T> {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        return offlineCacheDao.find(session.user?.id ?: 0, namespaceId, key)?.payload?.let {
            runCatching { json.decodeFromString<List<T>>(it) }.getOrNull()
        }.orEmpty()
    }

    private suspend inline fun <reified T> cache(key: String, value: List<T>) {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        offlineCacheDao.upsert(OfflineCacheEntity(userId = session.user?.id ?: 0, namespaceId = namespaceId, cacheKey = key, payload = json.encodeToString(value), updatedAt = System.currentTimeMillis()))
    }

    private suspend inline fun <reified T> cachedObject(key: String): T? {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        return offlineCacheDao.find(session.user?.id ?: 0, namespaceId, key)?.payload?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    }

    private suspend inline fun <reified T> cacheObject(key: String, value: T) {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: 0
        offlineCacheDao.upsert(OfflineCacheEntity(userId = session.user?.id ?: 0, namespaceId = namespaceId, cacheKey = key, payload = json.encodeToString(value), updatedAt = System.currentTimeMillis()))
    }
}

@Singleton
class SupplierOrderRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson private val json: Json,
) {
    suspend fun orders(query: SupplierOrderQuery): BusinessPage<SupplierOrder> =
        decodeBusinessPage(api.get("supplier-orders", query.toQueryParams()), json)

    suspend fun warehouses(): List<SupplierOrderWarehouse> =
        api.get("warehouses", mapOf("is_active" to "true"))

    suspend fun order(id: Long): SupplierOrder {
        require(id > 0) { "厂家订单 ID 无效" }
        return decodeBusinessObject(api.get("supplier-orders/$id"), json)
    }

    suspend fun updateStatus(id: Long, status: String, remark: String = "") {
        require(id > 0 && status.isNotBlank())
        api.put<JsonElement>(
            "supplier-orders/$id/status",
            buildJsonObject {
                put("status", status)
                remark.trim().takeIf(String::isNotEmpty)?.let { put("remark", it) }
            },
        )
    }
}

@Singleton
class PurchaseApprovalRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson private val json: Json,
) {
    suspend fun approvals(filter: BusinessFilter, keyword: String, page: Int, pageSize: Int = 20): BusinessPage<PurchaseApproval> {
        val params = mutableMapOf(
            "status" to when (filter) {
                BusinessFilter.Pending -> "pending"
                BusinessFilter.Completed -> "approved"
                BusinessFilter.All -> "all"
            },
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        )
        keyword.trim().takeIf { it.isNotEmpty() }?.let { params["keyword"] = it }
        return decodeBusinessPage(api.get("purchase-approvals", params), json)
    }

    suspend fun detail(id: Long): PurchaseApprovalDetail =
        decodeBusinessObject(api.get("purchase-approvals/$id/detail"), json)

    suspend fun approve(id: Long, adjustment: ApprovalAdjustment, value: Double?, remark: String) {
        api.post<JsonElement>(
            "purchase-approvals/$id/approve",
            buildJsonObject {
                put("adjustment_type", adjustment.wireValue)
                put("remark", remark.trim())
                when (adjustment) {
                    ApprovalAdjustment.Amount -> value?.let { put("discount_amount", it) }
                    ApprovalAdjustment.Rate -> value?.let { put("discount_rate", it) }
                    ApprovalAdjustment.None -> Unit
                }
            },
        )
    }

    suspend fun reject(id: Long, remark: String) {
        api.post<JsonElement>(
            "purchase-approvals/$id/reject",
            buildJsonObject { put("remark", remark.trim()) },
        )
    }
}

internal inline fun <reified T> decodeBusinessObject(payload: JsonElement, json: Json): T {
    val candidate = (payload as? JsonObject)?.get("data") ?: payload
    return json.decodeFromJsonElement(candidate)
}

internal inline fun <reified T> decodeBusinessList(payload: JsonElement, json: Json): List<T> {
    val array = when (payload) {
        is JsonArray -> payload
        is JsonObject -> listOf("data", "items", "list", "records")
            .firstNotNullOfOrNull { key -> payload[key] as? JsonArray }
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return json.decodeFromJsonElement(array)
}

internal inline fun <reified T> decodeBusinessPage(payload: JsonElement, json: Json): BusinessPage<T> {
    val obj = payload as? JsonObject
    val rows = decodeBusinessList<T>(payload, json)
    return BusinessPage(
        rows = rows,
        total = obj?.get("total")?.jsonPrimitive?.intOrNull ?: rows.size,
        page = obj?.get("page")?.jsonPrimitive?.intOrNull ?: 1,
        pageSize = obj?.get("pageSize")?.jsonPrimitive?.intOrNull ?: rows.size.coerceAtLeast(20),
    )
}

internal fun encodeBusinessPath(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")
