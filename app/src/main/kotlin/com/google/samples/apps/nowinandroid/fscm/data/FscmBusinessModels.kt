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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ReceivingQuickScanResponse(
    val data: ReceivingScanItem = ReceivingScanItem(),
    val duplicate: Boolean = false,
    @SerialName("receiving_session_id") val receivingSessionId: Long = 0,
    @SerialName("receiving_session_code") val receivingSessionCode: String = "",
    val message: String = "",
    @Transient val parsedPayload: BoxLabelQrPayload? = null,
    @Transient val pendingOperationId: Long = 0,
    @Transient val syncStatus: String = "",
    @Transient val localFallback: Boolean = false,
)

@Serializable
data class CentralReceiptItem(
    val id: Long = 0,
    @SerialName("product_id") val productCode: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("sku_name") val skuName: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("product_image") val productImage: String = "",
    @SerialName("expected_box_count") val expectedBoxCount: Int = 0,
    @SerialName("received_box_count") val receivedBoxCount: Int = 0,
    @SerialName("pending_box_count") val pendingBoxCount: Int = 0,
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("received_qty") val receivedQty: Int = 0,
    val status: String = "",
    val remark: String = "",
)

@Serializable
data class CentralReceipt(
    val id: Long = 0,
    val code: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("external_order_no") val externalOrderNo: String = "",
    @SerialName("warehouse_code") val warehouseCode: String = "",
    @SerialName("warehouse_name") val warehouseName: String = "",
    val status: String = "",
    @SerialName("received_at") val receivedAt: String = "",
    val receiver: String = "",
    val remark: String = "",
    val items: List<CentralReceiptItem> = emptyList(),
)

@Serializable
data class CentralReceiptStatistics(
    @SerialName("receipt_count") val receiptCount: Int = 0,
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("total_receipt_box_count") val totalBoxCount: Int = 0,
    @SerialName("box_received_count") val receivedBoxCount: Int = 0,
    @SerialName("box_pending_count") val pendingBoxCount: Int = 0,
    @SerialName("exception_box_count") val exceptionBoxCount: Int = 0,
)

@Serializable
data class CentralReceiptBoxLabel(
    val id: Long = 0,
    @SerialName("central_receipt_item_id") val centralReceiptItemId: Long = 0,
    @SerialName("box_uid") val boxUid: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("box_no") val boxNo: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("case_spec_name") val caseSpecName: String = "",
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("actual_box_qty") val actualBoxQty: Int = 0,
    val status: String = "",
    @SerialName("sign_status") val signStatus: String = "",
    @SerialName("qr_code_text") val qrCodeText: String = "",
    @SerialName("central_received_at") val centralReceivedAt: String = "",
)

@Serializable
data class CentralReceiptScanResult(
    val item: CentralReceiptBoxLabel? = null,
    @SerialName("central_receipt_id") val centralReceiptId: Long = 0,
    @SerialName("central_receipt_code") val centralReceiptCode: String = "",
    val duplicate: Boolean = false,
    val confirmed: Boolean = false,
    val status: String = "",
    val message: String = "",
    @Transient val parsedPayload: BoxLabelQrPayload? = null,
    @Transient val pendingOperationId: Long = 0,
    @Transient val syncStatus: String = "",
    @Transient val localFallback: Boolean = false,
)

@Serializable
data class ManufacturerRef(
    val id: Long = 0,
    val name: String = "",
    @SerialName("short_name") val shortName: String = "",
)

@Serializable
data class PurchaseOrderRef(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    val status: String = "",
)

@Serializable
data class SupplierOrderProductRef(
    val id: Long = 0,
    val code: String = "",
)

@Serializable
data class SupplierOrderItem(
    val id: Long = 0,
    @SerialName("sku_id") val skuId: Long = 0,
    @SerialName("box_count") val boxCount: Int = 0,
    @SerialName("display_box_count") val displayBoxCount: Int = 0,
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val currency: String = "CNY",
    val remark: String = "",
    val sku: Sku = Sku(),
)

@Serializable
data class SupplierOrder(
    val id: Long = 0,
    val code: String = "",
    val status: String = "",
    val remark: String = "",
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("total_volume") val totalVolume: Double = 0.0,
    @SerialName("product_count") val productCount: Int = 0,
    @SerialName("product_refs") val productRefs: List<SupplierOrderProductRef> = emptyList(),
    val items: List<SupplierOrderItem> = emptyList(),
    @SerialName("warning_type") val warningType: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val manufacturer: ManufacturerRef? = null,
    @SerialName("purchase_order") val purchaseOrder: PurchaseOrderRef? = null,
)

data class SupplierOrderDetailSummary(
    val productCount: Int = 0,
    val skuCount: Int = 0,
    val totalBoxCount: Int = 0,
    val totalQty: Int = 0,
    val totalAmount: Double = 0.0,
)

fun SupplierOrderItem.calculatedAmount(): Double = when {
    boxCount <= 0 && totalQty <= 0 -> 0.0
    unitPrice < 0 -> totalAmount
    boxCount > 0 -> boxCount * unitPrice
    qtyPerCarton > 0 -> totalQty * unitPrice / qtyPerCarton
    else -> 0.0
}

fun SupplierOrder.detailSummary(): SupplierOrderDetailSummary {
    if (items.isEmpty()) return SupplierOrderDetailSummary(productCount, totalBoxCount = totalBoxCount, totalQty = totalQty, totalAmount = totalAmount)
    val productKeys = items.map { item ->
        val product = item.sku.product
        product?.id?.takeIf { it > 0 }?.toString()
            ?: product?.code?.takeIf(String::isNotBlank)
            ?: item.sku.productId.takeIf { it > 0 }?.toString()
            ?: item.sku.code
    }.filter(String::isNotBlank).toSet()
    val skuKeys = items.map { item -> item.sku.id.takeIf { it > 0 }?.toString() ?: item.sku.code }.filter(String::isNotBlank).toSet()
    return SupplierOrderDetailSummary(
        productCount = productKeys.size.takeIf { it > 0 } ?: productCount,
        skuCount = skuKeys.size,
        totalBoxCount = items.sumOf { it.displayBoxCount.takeIf { count -> count > 0 } ?: it.boxCount },
        totalQty = items.sumOf(SupplierOrderItem::totalQty),
        totalAmount = items.sumOf(SupplierOrderItem::calculatedAmount),
    )
}

@Serializable
data class SupplierOrderWarehouse(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
)

enum class SupplierOrderCategory(val statusGroup: String) {
    InProgress("in_progress"),
    Completed("completed"),
    All("all"),
}

data class SupplierOrderFilters(
    val warehouseCode: String = "",
    val productCode: String = "",
    val manufacturerName: String = "",
    val purchaseOrderCode: String = "",
) {
    val activeCount: Int
        get() = listOf(warehouseCode, productCode, manufacturerName, purchaseOrderCode)
            .count { it.isNotBlank() }

    fun normalized() = copy(
        warehouseCode = warehouseCode.trim(),
        productCode = productCode.trim(),
        manufacturerName = manufacturerName.trim(),
        purchaseOrderCode = purchaseOrderCode.trim(),
    )
}

data class SupplierOrderQuery(
    val category: SupplierOrderCategory = SupplierOrderCategory.InProgress,
    val keyword: String = "",
    val filters: SupplierOrderFilters = SupplierOrderFilters(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val searchByProductCode: Boolean = false,
) {
    fun toQueryParams(): Map<String, String> = buildMap {
        put("status_group", category.statusGroup)
        put("page", page.coerceAtLeast(1).toString())
        put("pageSize", pageSize.coerceAtLeast(1).toString())
        keyword.trim().takeIf(String::isNotEmpty)?.let {
            put(if (searchByProductCode) "product_code" else "keyword", it)
        }
        filters.normalized().let { values ->
            values.warehouseCode.takeIf(String::isNotEmpty)?.let { put("warehouse_code", it) }
            values.productCode.takeIf { it.isNotEmpty() && !containsKey("product_code") }?.let { put("product_code", it) }
            values.manufacturerName.takeIf(String::isNotEmpty)?.let { put("manufacturer_name", it) }
            values.purchaseOrderCode.takeIf(String::isNotEmpty)?.let { put("purchase_order_code", it) }
        }
    }
}

@Serializable
data class PurchaseApprovalLog(
    val id: Long = 0,
    @SerialName("action_type") val actionType: String = "",
    val operator: String = "",
    val remark: String = "",
    @SerialName("reject_reason") val rejectReason: String = "",
    @SerialName("adjustment_type") val adjustmentType: String = "",
    @SerialName("approved_amount") val approvedAmount: Double = 0.0,
    val currency: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PurchaseApproval(
    val id: Long = 0,
    val code: String = "",
    val status: String = "",
    @SerialName("approval_type_text") val approvalTypeText: String = "",
    @SerialName("approval_source_code") val approvalSourceCode: String = "",
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val currency: String = "CNY",
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("product_count") val productCount: Int = 0,
    @SerialName("product_codes") val productCodes: List<String> = emptyList(),
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("missing_price_count") val missingPriceCount: Int = 0,
    @SerialName("warning_text") val warningText: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val manufacturer: ManufacturerRef? = null,
    @SerialName("purchase_order") val purchaseOrder: PurchaseOrderRef? = null,
)

@Serializable
data class PurchaseApprovalItem(
    val id: Long = 0,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("sku_name") val skuName: String = "",
    @SerialName("display_box_count") val displayBoxCount: Int = 0,
    @SerialName("box_count") val boxCount: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("box_unit_price") val boxUnitPrice: Double = 0.0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val currency: String = "CNY",
    @SerialName("missing_price") val missingPrice: Boolean = false,
    val remark: String = "",
)

@Serializable
data class PurchaseApprovalSummary(
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("approved_count") val approvedCount: Int = 0,
    @SerialName("overdue_count") val overdueCount: Int = 0,
)

@Serializable
data class PurchaseApprovalDetailSummary(
    @SerialName("product_count") val productCount: Int = 0,
    @SerialName("sku_count") val skuCount: Int = 0,
    @SerialName("total_box_count") val totalBoxCount: Int = 0,
    @SerialName("total_qty") val totalQty: Int = 0,
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    val currency: String = "CNY",
    @SerialName("missing_price_count") val missingPriceCount: Int = 0,
)

@Serializable
data class PurchaseApprovalDetail(
    val approval: PurchaseApproval? = null,
    val items: List<PurchaseApprovalItem> = emptyList(),
    val records: List<PurchaseApprovalLog> = emptyList(),
    val summary: PurchaseApprovalDetailSummary = PurchaseApprovalDetailSummary(),
    @SerialName("missing_price_item_ids") val missingPriceItemIds: List<Long> = emptyList(),
)

@Serializable
data class BusinessPage<T>(
    val rows: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

enum class BusinessFilter {
    Pending,
    Completed,
    All,
}

enum class ApprovalAdjustment(val wireValue: String) {
    None("none"),
    Amount("amount"),
    Rate("rate"),
}

fun allowedSupplierOrderStatuses(status: String): List<String> = when (status.lowercase()) {
    "draft" -> listOf("pending_quote")
    "pending_price_approval" -> listOf("pending_quote")
    "ready_to_order" -> listOf("producing")
    "producing" -> listOf("shipped", "same_day_delivery")
    "shipped" -> listOf("same_day_delivery")
    "same_day_delivery" -> listOf("shipped")
    else -> emptyList()
}

fun validateApprovalInput(
    adjustment: ApprovalAdjustment,
    value: Double?,
    remark: String,
    totalAmount: Double,
): ApprovalValidationError? = when (adjustment) {
    ApprovalAdjustment.None -> null
    ApprovalAdjustment.Amount -> when {
        value == null || value <= 0 -> ApprovalValidationError.AmountMustBePositive
        value > totalAmount -> ApprovalValidationError.AmountExceedsTotal
        remark.isBlank() -> ApprovalValidationError.AmountRemarkRequired
        else -> null
    }
    ApprovalAdjustment.Rate -> when {
        value == null || value <= 0 || value >= 1 -> ApprovalValidationError.RateOutOfRange
        remark.isBlank() -> ApprovalValidationError.RateRemarkRequired
        else -> null
    }
}

enum class ApprovalValidationError {
    AmountMustBePositive,
    AmountExceedsTotal,
    AmountRemarkRequired,
    RateOutOfRange,
    RateRemarkRequired,
}
