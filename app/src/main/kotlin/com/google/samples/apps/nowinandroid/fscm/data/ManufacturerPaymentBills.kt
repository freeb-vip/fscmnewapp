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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class ManufacturerBillBucket(val wireValue: String) {
    Draft("draft"),
    PendingPayment("pending"),
    Paid("paid"),
}

data class ManufacturerBillQuery(
    val bucket: ManufacturerBillBucket = ManufacturerBillBucket.PendingPayment,
    val keyword: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val page: Int = 1,
    val pageSize: Int = 20,
) {
    fun toQueryParams(): Map<String, String> = buildMap {
        put("approval_provider", "local")
        put("review_bucket", bucket.wireValue)
        put("page", page.coerceAtLeast(1).toString())
        put("page_size", pageSize.coerceIn(1, 100).toString())
        keyword.trim().takeIf(String::isNotEmpty)?.let { put("keyword", it) }
        startDate.trim().takeIf(String::isNotEmpty)?.let { put("start_date", it) }
        endDate.trim().takeIf(String::isNotEmpty)?.let { put("end_date", it) }
    }
}

@Serializable
data class PaymentAttachment(
    val url: String = "",
    @SerialName("object_key") val objectKey: String = "",
    val name: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

data class ManufacturerPaymentBill(
    val id: Long = 0,
    val supplierOrderId: Long? = null,
    val billNo: String = "",
    val billType: String = "",
    val sourceType: String = "",
    val sourceId: Long = 0,
    val totalAmount: Double = 0.0,
    val paidTotalAmount: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val currency: String = "CNY",
    val paymentTerms: String = "",
    val status: String = "",
    val approvalProvider: String = "",
    val approvalException: Boolean = false,
    val approvalExceptionReason: String = "",
    val approvedAt: String = "",
    val approverName: String = "",
    val approvalRemark: String = "",
    val paymentMethod: String = "",
    val paymentSource: String = "",
    val payer: String = "",
    val chengyisheOrderNo: String = "",
    val paymentDate: String = "",
    val paidAt: String = "",
    val manufacturerPaymentInfoId: Long? = null,
    val paymentInfoType: String = "",
    val paymentStrategy: String = "",
    val createdAt: String = "",
    val attachments: List<PaymentAttachment> = emptyList(),
    val payments: List<ManufacturerBillPayment> = emptyList(),
)

@Serializable
internal data class ManufacturerPaymentBillWire(
    val id: Long = 0,
    @SerialName("supplier_order_id") val supplierOrderId: Long? = null,
    @SerialName("bill_no") val billNo: String = "",
    @SerialName("bill_type") val billType: String = "",
    @SerialName("source_type") val sourceType: String = "",
    @SerialName("source_id") val sourceId: Long = 0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("paid_total_amount") val paidTotalAmount: Double = 0.0,
    @SerialName("remaining_amount") val remainingAmount: Double? = null,
    val currency: String = "CNY",
    @SerialName("payment_terms") val paymentTerms: String = "",
    val status: String = "",
    @SerialName("approval_provider") val approvalProvider: String = "",
    @SerialName("approval_exception") val approvalException: Boolean = false,
    @SerialName("approval_exception_reason") val approvalExceptionReason: String = "",
    @SerialName("approved_at") val approvedAt: String = "",
    @SerialName("approver_name") val approverName: String = "",
    @SerialName("approval_remark") val approvalRemark: String = "",
    @SerialName("payment_method") val paymentMethod: String = "",
    @SerialName("payment_source") val paymentSource: String = "",
    val payer: String = "",
    @SerialName("chengyishe_order_no") val chengyisheOrderNo: String = "",
    @SerialName("payment_date") val paymentDate: String = "",
    @SerialName("paid_at") val paidAt: String = "",
    @SerialName("manufacturer_payment_info_id") val manufacturerPaymentInfoId: Long? = null,
    @SerialName("payment_info_type") val paymentInfoType: String = "",
    @SerialName("payment_strategy") val paymentStrategy: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("payment_attachments") val paymentAttachments: JsonElement = JsonArray(emptyList()),
    @SerialName("payment_images") val paymentImages: JsonElement = JsonArray(emptyList()),
    val payments: List<ManufacturerBillPaymentWire> = emptyList(),
)

data class ManufacturerBillPayment(
    val id: Long = 0,
    val amount: Double = 0.0,
    val currency: String = "CNY",
    val paymentMethod: String = "",
    val paymentSource: String = "",
    val payer: String = "",
    val paymentDate: String = "",
    val remark: String = "",
    val createdAt: String = "",
    val attachments: List<PaymentAttachment> = emptyList(),
)

@Serializable
internal data class ManufacturerBillPaymentWire(
    val id: Long = 0,
    val amount: Double = 0.0,
    val currency: String = "CNY",
    @SerialName("payment_method") val paymentMethod: String = "",
    @SerialName("payment_source") val paymentSource: String = "",
    val payer: String = "",
    @SerialName("payment_date") val paymentDate: String = "",
    val remark: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("payment_attachments") val paymentAttachments: JsonElement = JsonArray(emptyList()),
)

data class ManufacturerBillListItem(
    val bill: ManufacturerPaymentBill,
    val crossConsolidation: Boolean = false,
    val consolidationOrderCount: Int = 0,
)

data class ManufacturerBillGroup(
    val supplierOrder: SupplierOrder = SupplierOrder(),
    val productRefs: List<SupplierOrderProductRef> = emptyList(),
    val billCount: Int = 0,
    val statusSummary: ManufacturerBillStatusSummary = ManufacturerBillStatusSummary(),
    val currencyAmounts: Map<String, Double> = emptyMap(),
    val bills: List<ManufacturerBillListItem> = emptyList(),
)

@Serializable
data class ManufacturerBillStatusSummary(
    val pending: Int = 0,
    val reviewed: Int = 0,
    val paid: Int = 0,
)

@Serializable
data class ManufacturerBillSummary(
    val pending: Long = 0,
    @SerialName("pending_amount") val pendingAmount: Double = 0.0,
    val paid: Long = 0,
    @SerialName("paid_amount") val paidAmount: Double = 0.0,
)

data class ManufacturerBillPage(
    val items: List<ManufacturerBillGroup> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val summary: ManufacturerBillSummary = ManufacturerBillSummary(),
)

@Serializable
internal data class ManufacturerBillListItemWire(
    val bill: ManufacturerPaymentBillWire = ManufacturerPaymentBillWire(),
    @SerialName("cross_consolidation") val crossConsolidation: Boolean = false,
    @SerialName("consolidation_order_count") val consolidationOrderCount: Int = 0,
)

@Serializable
internal data class ManufacturerBillGroupWire(
    @SerialName("supplier_order") val supplierOrder: SupplierOrder = SupplierOrder(),
    @SerialName("product_refs") val productRefs: List<SupplierOrderProductRef> = emptyList(),
    @SerialName("bill_count") val billCount: Int = 0,
    @SerialName("status_summary") val statusSummary: ManufacturerBillStatusSummary = ManufacturerBillStatusSummary(),
    @SerialName("currency_amounts") val currencyAmounts: Map<String, Double> = emptyMap(),
    val bills: List<ManufacturerBillListItemWire> = emptyList(),
)

@Serializable
internal data class ManufacturerBillPageWire(
    val items: List<ManufacturerBillGroupWire> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    val summary: ManufacturerBillSummary = ManufacturerBillSummary(),
)

@Serializable
data class BillManufacturer(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    @SerialName("short_name") val shortName: String = "",
)

@Serializable
data class ManufacturerPaymentInfo(
    val id: Long = 0,
    val type: String = "",
    @SerialName("custom_type_name") val customTypeName: String = "",
    @SerialName("account_name") val accountName: String = "",
    @SerialName("account_no") val accountNo: String = "",
    @SerialName("bank_name") val bankName: String = "",
    @SerialName("bank_branch") val bankBranch: String = "",
    @SerialName("qr_image_url") val qrImageUrl: String = "",
    val remark: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class ManufacturerBillCounterparty(
    val manufacturer: BillManufacturer? = null,
    @SerialName("payment_infos") val paymentInfos: List<ManufacturerPaymentInfo> = emptyList(),
)

@Serializable
data class ManufacturerBillSourceItem(
    @SerialName("supplier_order_item_id") val supplierOrderItemId: Long = 0,
    @SerialName("product_id") val productId: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_id") val skuId: Long = 0,
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("sku_name") val skuName: String = "",
    @SerialName("case_spec_name") val caseSpecName: String = "",
    @SerialName("box_count") val boxCount: Int = 0,
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val currency: String = "CNY",
)

@Serializable
data class ManufacturerBillSourceDetail(
    @SerialName("detail_type") val detailType: String = "",
    val title: String = "",
    val summary: Map<String, JsonElement> = emptyMap(),
    val items: List<ManufacturerBillSourceItem> = emptyList(),
)

@Serializable
data class ManufacturerBillRelatedOrder(
    val id: Long = 0,
    val code: String = "",
    val status: String = "",
    @SerialName("purchase_order_id") val purchaseOrderId: Long = 0,
    @SerialName("purchase_order_code") val purchaseOrderCode: String = "",
    @SerialName("purchase_order_name") val purchaseOrderName: String = "",
    @SerialName("manufacturer_name") val manufacturerName: String = "",
    @SerialName("purchase_approval_passed") val purchaseApprovalPassed: Boolean = false,
)

@Serializable
data class ManufacturerBillExecutionState(
    @SerialName("can_execute") val canExecute: Boolean = true,
    @SerialName("blocked_reason") val blockedReason: String = "",
    @SerialName("related_supplier_orders") val relatedSupplierOrders: List<ManufacturerBillRelatedOrder> = emptyList(),
)

data class ManufacturerBillDetail(
    val bill: ManufacturerPaymentBill = ManufacturerPaymentBill(),
    val counterparty: ManufacturerBillCounterparty = ManufacturerBillCounterparty(),
    val sourceDetail: ManufacturerBillSourceDetail? = null,
    val paymentExecution: ManufacturerBillExecutionState? = null,
)

@Serializable
internal data class ManufacturerBillDetailWire(
    val bill: ManufacturerPaymentBillWire = ManufacturerPaymentBillWire(),
    val counterparty: ManufacturerBillCounterparty = ManufacturerBillCounterparty(),
    @SerialName("source_detail") val sourceDetail: ManufacturerBillSourceDetail? = null,
    @SerialName("payment_execution") val paymentExecution: ManufacturerBillExecutionState? = null,
)

data class ReviewManufacturerBillRequest(
    val approve: Boolean,
    val remark: String,
    val amount: Double? = null,
    val paymentStrategy: String = "",
    val manufacturerPaymentInfoId: Long? = null,
    val paymentMethod: String = "",
    val paymentSource: String = "",
    val chengyisheOrderNo: String = "",
    val attachments: List<PaymentAttachment> = emptyList(),
)

data class CreateManufacturerBillPaymentRequest(
    val amount: Double,
    val currency: String,
    val paymentMethod: String,
    val paymentSource: String,
    val manufacturerPaymentInfoId: Long,
    val attachments: List<PaymentAttachment> = emptyList(),
    val payer: String = "",
    val paymentDate: String = "",
    val remark: String = "",
)

@Serializable
data class PaymentConfigOption(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
internal data class PaymentConfigOptionsResponse(
    val data: List<PaymentConfigOption> = emptyList(),
)

@Singleton
class ManufacturerPaymentBillRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson private val json: Json,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun paymentOptions(type: String): List<PaymentConfigOption> {
        require(type == "payment_method" || type == "payment_source") { "不支持的付款配置类型" }
        return api.get<PaymentConfigOptionsResponse>("system-configs", mapOf("type" to type)).data
            .filter { it.isActive && it.name.isNotBlank() }
    }

    suspend fun groups(query: ManufacturerBillQuery): ManufacturerBillPage {
        val wire: ManufacturerBillPageWire = api.get("manufacturer-payment-bills/by-supplier-order", query.toQueryParams())
        return ManufacturerBillPage(
            items = wire.items.map { group ->
                ManufacturerBillGroup(
                    supplierOrder = group.supplierOrder,
                    productRefs = group.productRefs,
                    billCount = group.billCount,
                    statusSummary = group.statusSummary,
                    currencyAmounts = group.currencyAmounts,
                    bills = group.bills.map { item ->
                        ManufacturerBillListItem(
                            bill = item.bill.toModel(json),
                            crossConsolidation = item.crossConsolidation,
                            consolidationOrderCount = item.consolidationOrderCount,
                        )
                    },
                )
            },
            total = wire.total,
            page = wire.page,
            pageSize = wire.pageSize,
            summary = wire.summary,
        )
    }

    suspend fun detail(id: Long): ManufacturerBillDetail {
        require(id > 0) { "厂家账单 ID 无效" }
        val wire: ManufacturerBillDetailWire = api.get("payment-bills/$id")
        return ManufacturerBillDetail(
            bill = wire.bill.toModel(json),
            counterparty = wire.counterparty,
            sourceDetail = wire.sourceDetail,
            paymentExecution = wire.paymentExecution,
        )
    }

    suspend fun review(id: Long, request: ReviewManufacturerBillRequest) {
        api.post<Unit>(
            "payment-bills/$id/review",
            buildJsonObject {
                put("action", if (request.approve) "approve" else "reject")
                put("remark", request.remark.trim())
                if (request.approve) {
                    request.amount?.let { put("paid_total_amount", it) }
                    put("payment_strategy", request.paymentStrategy)
                    request.manufacturerPaymentInfoId?.let { put("manufacturer_payment_info_id", it) }
                    put("payment_method", request.paymentMethod.trim())
                    put("payment_source", request.paymentSource.trim())
                    request.chengyisheOrderNo.trim().takeIf(String::isNotEmpty)?.let { put("chengyishe_order_no", it) }
                    put("payment_attachments", json.encodeToJsonElement(request.attachments))
                }
            },
        )
    }

    suspend fun createPayment(id: Long, request: CreateManufacturerBillPaymentRequest) {
        api.post<Unit>(
            "payment-bills/$id/payments",
            buildJsonObject {
                put("amount", request.amount)
                put("payment_strategy", "deposit_payment")
                put("currency", request.currency)
                put("payment_method", request.paymentMethod.trim())
                put("payment_source", request.paymentSource.trim())
                put("manufacturer_payment_info_id", request.manufacturerPaymentInfoId)
                put("payment_attachments", json.encodeToJsonElement(request.attachments))
                request.payer.trim().takeIf(String::isNotEmpty)?.let { put("payer", it) }
                request.paymentDate.trim().takeIf(String::isNotEmpty)?.let { put("payment_date", it) }
                request.remark.trim().takeIf(String::isNotEmpty)?.let { put("remark", it) }
            },
        )
    }

    suspend fun payments(id: Long): List<ManufacturerBillPayment> {
        val rows: List<ManufacturerBillPaymentWire> = api.get("payment-bills/$id/payments")
        return rows.map { it.toModel(json) }
    }

    suspend fun uploadAttachment(uri: Uri): PaymentAttachment {
        val resolver = context.contentResolver
        val name = resolver.displayName(uri)
        val declaredSize = resolver.declaredSize(uri)
        validatePaymentAttachment(name, declaredSize)
        val bytes = resolver.openInputStream(uri)?.use(::readPaymentAttachmentBytes)
            ?: error("无法读取附件")
        validatePaymentAttachment(name, bytes.size.toLong())
        val mediaType = paymentAttachmentMediaType(name).toMediaType()
        val part = MultipartBody.Part.createFormData("file", name, bytes.toRequestBody(mediaType))
        return api.upload("upload/payment-attachment", part)
    }
}

internal fun ManufacturerPaymentBillWire.toModel(json: Json) = ManufacturerPaymentBill(
    id = id,
    supplierOrderId = supplierOrderId,
    billNo = billNo,
    billType = billType,
    sourceType = sourceType,
    sourceId = sourceId,
    totalAmount = totalAmount,
    paidTotalAmount = paidTotalAmount,
    remainingAmount = remainingAmount ?: if (status == "paid") 0.0 else (totalAmount - paidTotalAmount).coerceAtLeast(0.0),
    currency = currency.ifBlank { "CNY" },
    paymentTerms = paymentTerms,
    status = status,
    approvalProvider = approvalProvider,
    approvalException = approvalException,
    approvalExceptionReason = approvalExceptionReason,
    approvedAt = approvedAt,
    approverName = approverName,
    approvalRemark = approvalRemark,
    paymentMethod = paymentMethod,
    paymentSource = paymentSource,
    payer = payer,
    chengyisheOrderNo = chengyisheOrderNo,
    paymentDate = paymentDate,
    paidAt = paidAt,
    manufacturerPaymentInfoId = manufacturerPaymentInfoId,
    paymentInfoType = paymentInfoType,
    paymentStrategy = paymentStrategy,
    createdAt = createdAt,
    attachments = (paymentAttachments.decodeAttachments(json) + paymentImages.decodeLegacyPaymentImages(json))
        .distinctBy { it.objectKey.ifBlank { it.url } },
    payments = payments.map { it.toModel(json) },
)

private fun ManufacturerBillPaymentWire.toModel(json: Json) = ManufacturerBillPayment(
    id = id,
    amount = amount,
    currency = currency.ifBlank { "CNY" },
    paymentMethod = paymentMethod,
    paymentSource = paymentSource,
    payer = payer,
    paymentDate = paymentDate,
    remark = remark,
    createdAt = createdAt,
    attachments = paymentAttachments.decodeAttachments(json),
)

private fun JsonElement.decodeAttachments(json: Json): List<PaymentAttachment> = runCatching {
    when (this) {
        is JsonArray -> json.decodeFromJsonElement(this)
        is JsonPrimitive -> if (isString && content.isNotBlank()) {
            json.decodeFromString<List<PaymentAttachment>>(content)
        } else {
            emptyList()
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

private fun JsonElement.decodeLegacyPaymentImages(json: Json): List<PaymentAttachment> {
    val values = when (this) {
        is JsonArray -> this
        is JsonPrimitive -> {
            if (!isString || content.isBlank()) return emptyList()
            runCatching { json.parseToJsonElement(content) as? JsonArray }.getOrNull()
                ?: JsonArray(content.split(',').map { JsonPrimitive(it.trim()) })
        }
        else -> return emptyList()
    }
    return values.mapNotNull { value ->
        val url = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        PaymentAttachment(url = url, name = url.substringBefore('?').substringAfterLast('/').ifBlank { "付款凭据" })
    }
}

internal fun validatePaymentAttachment(fileName: String, sizeBytes: Long) {
    require(fileName.substringAfterLast('.', "").lowercase() in PAYMENT_ATTACHMENT_EXTENSIONS) {
        "仅支持 JPG、JPEG、PNG、GIF、WEBP、PDF"
    }
    require(sizeBytes <= MAX_PAYMENT_ATTACHMENT_BYTES) { "附件大小不能超过 10MB" }
    require(sizeBytes != 0L) { "附件内容不能为空" }
}

private fun paymentAttachmentMediaType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

private fun ContentResolver.displayName(uri: Uri): String {
    val fromCursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    return fromCursor?.takeIf(String::isNotBlank) ?: "payment-${System.currentTimeMillis()}.jpg"
}

private fun ContentResolver.declaredSize(uri: Uri): Long =
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
    } ?: -1L

private fun readPaymentAttachmentBytes(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= MAX_PAYMENT_ATTACHMENT_BYTES) { "附件大小不能超过 10MB" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private val PAYMENT_ATTACHMENT_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "pdf")
private const val MAX_PAYMENT_ATTACHMENT_BYTES = 10L * 1024L * 1024L
