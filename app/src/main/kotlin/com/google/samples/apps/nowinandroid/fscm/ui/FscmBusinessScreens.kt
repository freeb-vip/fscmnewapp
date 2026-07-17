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

package com.google.samples.apps.nowinandroid.fscm.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.ApprovalAdjustment
import com.google.samples.apps.nowinandroid.fscm.data.BusinessFilter
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceipt
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptBoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptScanResult
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseApproval
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingQuickScanResponse
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SupplierOrder
import com.google.samples.apps.nowinandroid.fscm.data.isLikelyBoxLabelQr
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode

private val BusinessBrand = Color(0xFF1677FF)
private val BusinessDeep = Color(0xFF0958D9)
private val BusinessBackground = Color(0xFFF5F8FF)
private val BusinessStroke = Color(0xFFDCE7F5)
private val BusinessInk = Color(0xFF172033)
private val BusinessMuted = Color(0xFF66758A)
private val BusinessSuccess = Color(0xFF389E0D)
private val BusinessWarning = Color(0xFFD48806)
private val BusinessDanger = Color(0xFFCF1322)

@Composable
fun WarehouseReceivingScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: WarehouseReceivingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val sessions = state.sessions.filter { session ->
        when (state.filter) {
            BusinessFilter.Pending -> session.status !in setOf("completed", "cancelled")
            BusinessFilter.Completed -> session.status in setOf("completed", "cancelled")
            BusinessFilter.All -> true
        }
    }
    BusinessPage {
        item { Notice(state.error, state.message, viewModel::consumeNotice) }
        item {
            MetricRow(
                listOf(
                    stringResource(R.string.business_metric_pending) to state.sessions.count { it.status !in setOf("completed", "cancelled") }.toString(),
                    stringResource(R.string.business_metric_completed) to state.sessions.count { it.status in setOf("completed", "cancelled") }.toString(),
                    stringResource(R.string.business_metric_exception_boxes) to state.sessions.sumOf { it.exceptionBoxCount }.toString(),
                ),
            )
        }
        item { BusinessFilters(state.filter, viewModel::setFilter) }
        if (state.loading && sessions.isEmpty()) item { LoadingRow() }
        if (!state.loading && sessions.isEmpty()) item { EmptyState(stringResource(R.string.business_no_warehouse_receipts)) }
        items(sessions, key = { it.id }) { session ->
            ReceivingSessionCard(
                session = session,
                onClick = { onOpenDetail(session.id) },
            )
        }
    }
}

@Composable
fun CentralReceivingScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: CentralReceivingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.load() }
    DisposableEffect(owner) {
        viewModel.registerScanner(owner, viewModel::preview)
        onDispose(viewModel::unregisterScanner)
    }
    BusinessPage {
        item {
            BusinessScanPanel(
                title = stringResource(R.string.business_quick_receiving),
                description = stringResource(R.string.business_quick_central_receiving_description),
                loading = state.submitting,
                broadcastMode = scannerState.configuredMode == ScannerInputMode.IntentBroadcast,
                lastScannedCode = state.previewRawCode,
                onScan = viewModel::preview,
            )
        }
        if (state.unrecognized) {
            item { UnrecognizedBoxLabelCard() }
        }
        state.preview?.let { preview ->
            item {
                CentralQuantityConfirmCard(
                    preview = preview,
                    quantity = state.quantity,
                    submitting = state.submitting,
                    onQuantityChange = viewModel::changeQuantity,
                    onCancel = viewModel::clearPreview,
                    onConfirm = viewModel::confirm,
                )
            }
        }
        item { Notice(state.error, state.message, viewModel::consumeNotice) }
        item {
            MetricRow(
                listOf(
                    stringResource(R.string.business_metric_total_boxes) to state.statistics.totalBoxCount.toString(),
                    stringResource(R.string.business_metric_pending) to state.statistics.pendingBoxCount.toString(),
                    stringResource(R.string.business_metric_received) to state.statistics.receivedBoxCount.toString(),
                    stringResource(R.string.business_metric_exception) to state.statistics.exceptionBoxCount.toString(),
                ),
            )
        }
        if (state.recentScans.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.business_recent_scans)) }
            items(state.recentScans.take(8)) { result ->
                CentralScanRow(
                    result = result,
                    onRetry = result.pendingOperationId.takeIf { it > 0 && result.syncStatus == "failed" }?.let { id -> { viewModel.retryPending(id) } },
                    onCancel = result.pendingOperationId.takeIf { it > 0 }?.let { id -> { viewModel.cancelPending(id) } },
                )
            }
        }
        item { BusinessFilters(state.filter, viewModel::setFilter) }
        if (state.loading && state.receipts.isEmpty()) item { LoadingRow() }
        if (!state.loading && state.receipts.isEmpty()) item { EmptyState(stringResource(R.string.business_no_central_receipts)) }
        items(state.receipts, key = { it.id }) { receipt -> CentralReceiptCard(receipt) { onOpenDetail(receipt.id) } }
    }
}

@Composable
fun CentralReceiptDetailScreen(
    id: Long,
    viewModel: CentralReceiptDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(id) { viewModel.load(id) }
    DisposableEffect(owner, id) {
        viewModel.registerScanner(owner) { viewModel.preview(id, it) }
        onDispose(viewModel::unregisterScanner)
    }
    BusinessPage {
        item { Notice(state.error, state.message, viewModel::consumeNotice) }
        state.receipt?.let { receipt ->
            item {
                BusinessCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(receipt.code.ifBlank { "CR-${receipt.id}" }, fontWeight = FontWeight.Bold, color = BusinessInk)
                        StatusPill(businessStatusText(receipt.status), statusColor(receipt.status))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(receipt.sourceName.ifBlank { receipt.externalOrderNo.ifBlank { stringResource(R.string.business_unbound_source) } }, color = BusinessMuted)
                    Text(receipt.warehouseName.ifBlank { receipt.warehouseCode.ifBlank { stringResource(R.string.business_central_warehouse) } }, color = BusinessMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { BusinessScanPanel(stringResource(R.string.business_scan_in_document), stringResource(R.string.business_scan_then_confirm), state.submitting) { viewModel.preview(id, it) } }
        state.preview?.let { preview ->
            item {
                BusinessCard {
                    Text(preview.item?.labelCode.orEmpty().ifBlank { state.previewRawCode }, fontWeight = FontWeight.Bold)
                    Text(
                        if (preview.localFallback) stringResource(R.string.business_offline_pending) else preview.message.ifBlank { businessStatusText(preview.status) },
                        color = statusColor(preview.status),
                    )
                    preview.item?.skuCode?.takeIf { it.isNotBlank() }?.let { Text("SKU $it", color = BusinessMuted) }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::clearPreview, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
                        Button(
                            onClick = { viewModel.confirm(id) },
                            enabled = !preview.duplicate && preview.status !in setOf("wrong_order", "unknown", "exception"),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(if (preview.status == "pending_sync") R.string.business_save_pending_sync else R.string.scan_receiving)) }
                    }
                }
            }
        }
        if (state.receipt?.items?.isNotEmpty() == true) {
            item { SectionTitle(stringResource(R.string.business_sku_receiving_progress)) }
            items(state.receipt!!.items, key = { it.id }) { item ->
                BusinessCard {
                    Text(item.skuCode.ifBlank { item.productName.ifBlank { "SKU-${item.id}" } }, fontWeight = FontWeight.SemiBold)
                    Text(item.productName.ifBlank { item.skuName }, color = BusinessMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.business_received_expected_boxes, item.receivedBoxCount, item.expectedBoxCount), color = BusinessDeep)
                }
            }
        }
        if (state.boxLabels.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.business_box_label_details)) }
            items(state.boxLabels, key = { it.id }) { label ->
                BusinessCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label.labelCode.ifBlank { label.boxNo }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StatusPill(businessStatusText(label.status), statusColor(label.status))
                    }
                    Text(listOf(label.skuCode, label.caseSpecName).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { stringResource(R.string.business_no_sku_info) }, color = BusinessMuted)
                }
            }
        }
        if (state.loading) item { LoadingRow() }
    }
}

@Composable
fun SupplierOrdersScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: SupplierOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    BusinessPage {
        item { SearchBar(state.query, stringResource(R.string.business_search_supplier_orders), viewModel::setQuery, viewModel::load) }
        item { Notice(state.error, state.message, viewModel::consumeNotice) }
        item {
            MetricRow(
                listOf(
                    stringResource(R.string.business_metric_orders) to state.total.toString(),
                    stringResource(R.string.business_metric_boxes) to state.orders.sumOf { it.totalBoxCount }.toString(),
                    stringResource(R.string.business_metric_volume) to "%.3f m³".format(state.orders.sumOf { it.totalVolume }),
                ),
            )
        }
        item { BusinessFilters(state.filter, viewModel::setFilter) }
        if (state.loading && state.orders.isEmpty()) item { LoadingRow() }
        if (!state.loading && state.orders.isEmpty()) item { EmptyState(stringResource(R.string.business_no_supplier_orders)) }
        items(state.orders, key = { it.id }) { order -> SupplierOrderCard(order) { onOpenDetail(order.id) } }
    }
}

@Composable
fun SupplierOrderDetailScreen(
    id: Long,
    viewModel: SupplierOrderDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { viewModel.load(id) }
    BusinessPage {
        if (state.loading) item { LoadingRow() }
        state.error?.let { error -> item { Notice(error, null, null) } }
        state.order?.let { order ->
            item {
                BusinessCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(order.code.ifBlank { "SO-${order.id}" }, fontWeight = FontWeight.Bold, color = BusinessInk)
                        StatusPill(supplierStatusText(order.status), statusColor(order.status))
                    }
                    Text(
                        order.manufacturer?.shortName.orEmpty().ifBlank { order.manufacturer?.name ?: stringResource(R.string.business_unbound_manufacturer) },
                        color = BusinessInk,
                    )
                    Text(order.purchaseOrder?.code.orEmpty().ifBlank { stringResource(R.string.business_unbound_purchase_order) }, color = BusinessMuted)
                    Text(stringResource(R.string.business_amount_boxes_quantity, order.totalAmount, order.totalBoxCount, order.totalQty), color = BusinessDeep)
                    if (order.remark.isNotBlank()) Text(order.remark, color = BusinessMuted)
                }
            }
            if (order.productRefs.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.business_related_products)) }
                items(order.productRefs, key = { it.id }) { product ->
                    BusinessCard { Text(product.code.ifBlank { stringResource(R.string.business_product_number, product.id) }, color = BusinessInk) }
                }
            }
        }
    }
}

@Composable
fun PurchaseApprovalsScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: PurchaseApprovalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load(true) }
    BusinessPage {
        item { SearchBar(state.query, stringResource(R.string.business_search_approvals), viewModel::setQuery) { viewModel.load(true) } }
        state.error?.let { item { Notice(it, null, null) } }
        item {
            MetricRow(
                listOf(
                    stringResource(R.string.business_metric_total) to state.total.toString(),
                    stringResource(R.string.business_metric_pending_review) to state.approvals.count { it.status == "pending_quote" }.toString(),
                    stringResource(R.string.business_metric_amount) to "%.2f".format(state.approvals.sumOf { it.totalAmount }),
                ),
            )
        }
        item { BusinessFilters(state.filter, viewModel::setFilter) }
        if (state.loading && state.approvals.isEmpty()) item { LoadingRow() }
        if (!state.loading && state.approvals.isEmpty()) item { EmptyState(stringResource(R.string.business_no_approvals)) }
        items(state.approvals, key = { it.id }) { approval -> PurchaseApprovalCard(approval) { onOpenDetail(approval.id) } }
        if (state.hasMore) item { OutlinedButton(onClick = { viewModel.load(false) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_load_more)) } }
    }
}

@Composable
fun PurchaseApprovalDetailScreen(
    id: Long,
    viewModel: PurchaseApprovalDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val approval = state.detail.approval
    LaunchedEffect(id) { viewModel.load(id) }
    BusinessPage(bottomPadding = 100) {
        item { Notice(state.error, state.message, viewModel::consumeNotice) }
        approval?.let { row ->
            item {
                BusinessCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.code.ifBlank { "SO-${row.id}" }, fontWeight = FontWeight.Bold, color = BusinessInk)
                        StatusPill(supplierStatusText(row.status), statusColor(row.status))
                    }
                    Text("${row.currency} %.2f".format(row.totalAmount), style = MaterialTheme.typography.headlineSmall, color = BusinessDeep, fontWeight = FontWeight.Bold)
                    Text("${row.manufacturer?.shortName.orEmpty().ifBlank { row.manufacturer?.name ?: stringResource(R.string.business_unbound_manufacturer) }} · ${row.purchaseOrder?.code.orEmpty().ifBlank { row.approvalSourceCode }}", color = BusinessMuted)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.business_approval_summary, state.detail.summary.skuCount, state.detail.summary.totalBoxCount, state.detail.summary.totalQty))
                    if (state.detail.summary.missingPriceCount > 0) Text(stringResource(R.string.business_missing_prices_block, state.detail.summary.missingPriceCount), color = BusinessDanger, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (state.detail.items.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.business_review_details)) }
            items(state.detail.items, key = { it.id }) { item ->
                BusinessCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.productCode.ifBlank { item.skuCode.ifBlank { "ITEM-${item.id}" } }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${item.currency} %.2f".format(item.totalAmount), color = BusinessDeep)
                    }
                    Text(item.skuName.ifBlank { item.productName.ifBlank { stringResource(R.string.business_unnamed_product) } }, color = BusinessMuted, maxLines = 2)
                    Text(stringResource(R.string.business_item_summary, item.displayBoxCount.takeIf { it > 0 } ?: item.boxCount, item.totalQty, item.boxUnitPrice))
                    if (item.missingPrice) Text(stringResource(R.string.business_missing_price), color = BusinessDanger, fontWeight = FontWeight.Bold)
                    if (item.remark.isNotBlank()) Text(stringResource(R.string.business_remark_value, item.remark), color = BusinessMuted)
                }
            }
        }
        if (approval?.status == "pending_quote") {
            item { SectionTitle(stringResource(R.string.business_review_action)) }
            item {
                BusinessCard {
                    Text(stringResource(R.string.business_adjustment_type), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ApprovalAdjustment.entries.forEach { option ->
                            FilterChip(
                                selected = state.adjustment == option,
                                onClick = { viewModel.setAdjustment(option) },
                                label = {
                                    Text(
                                        when (option) {
                                            ApprovalAdjustment.None -> stringResource(R.string.business_original_price)
                                            ApprovalAdjustment.Amount -> stringResource(R.string.business_reduction)
                                            ApprovalAdjustment.Rate -> stringResource(R.string.business_discount)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (state.adjustment != ApprovalAdjustment.None) {
                        OutlinedTextField(
                            value = state.adjustmentValue,
                            onValueChange = viewModel::setAdjustmentValue,
                            label = { Text(stringResource(if (state.adjustment == ApprovalAdjustment.Amount) R.string.business_reduction_amount else R.string.business_discount_rate)) },
                            keyboardOptions = KeyboardOptions.Default,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = state.remark,
                        onValueChange = viewModel::setRemark,
                        label = { Text(stringResource(R.string.business_review_remark)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::requestReject, enabled = !state.submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.business_reject), color = BusinessDanger) }
                        Button(onClick = viewModel::requestApprove, enabled = !state.submitting && state.detail.summary.missingPriceCount == 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.business_approve)) }
                    }
                }
            }
        }
        if (state.detail.records.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.business_action_history)) }
            items(state.detail.records, key = { it.id }) { record ->
                BusinessCard {
                    Text(stringResource(if (record.actionType == "reject") R.string.business_rejected else R.string.business_approved), fontWeight = FontWeight.SemiBold, color = if (record.actionType == "reject") BusinessDanger else BusinessSuccess)
                    Text("${record.operator.ifBlank { stringResource(R.string.business_system) }} · ${record.remark.ifBlank { record.rejectReason.ifBlank { stringResource(R.string.business_no_remark) } }}", color = BusinessMuted)
                    Text(record.createdAt.businessTime(), style = MaterialTheme.typography.bodySmall, color = BusinessMuted)
                }
            }
        }
        if (state.loading) item { LoadingRow() }
    }
    state.confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(if (action == "approve") R.string.business_confirm_approve_title else R.string.business_confirm_reject_title)) },
            text = { Text(stringResource(if (action == "approve") R.string.business_confirm_approve_text else R.string.business_confirm_reject_text)) },
            confirmButton = { Button(onClick = { viewModel.confirm(id) }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun BusinessPage(bottomPadding: Int = 24, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BusinessBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomPadding.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun BusinessCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, BusinessStroke),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content) }
}

@Composable
private fun BusinessScanPanel(
    title: String,
    description: String,
    loading: Boolean,
    broadcastMode: Boolean = false,
    compactBroadcast: Boolean = false,
    showLastScanned: Boolean = true,
    lastScannedCode: String = "",
    preferBoxLabelCamera: Boolean = false,
    onScan: (String) -> Unit,
) {
    BusinessCard {
        if (!broadcastMode || !compactBroadcast) {
            Text(title, fontWeight = FontWeight.Bold, color = BusinessInk)
            Text(description, color = BusinessMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (broadcastMode) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = if (compactBroadcast) 6.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(if (compactBroadcast) 30.dp else 64.dp), tint = BusinessBrand)
                Spacer(Modifier.height(if (compactBroadcast) 4.dp else 10.dp))
                Text(stringResource(if (loading) R.string.business_identifying_label else R.string.business_waiting_for_label), color = BusinessInk, fontWeight = FontWeight.Bold)
                if (!compactBroadcast) Text(stringResource(R.string.business_use_pda_scanner), color = BusinessMuted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            ScanInput(loading, preferBoxLabelCamera, onScan)
        }
        if (showLastScanned && lastScannedCode.isNotBlank()) {
            Text(stringResource(R.string.business_last_scan_characters), color = BusinessMuted, style = MaterialTheme.typography.labelSmall)
            Text(lastScannedCode, color = BusinessDeep, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UnrecognizedBoxLabelCard() = BusinessCard {
    Text(stringResource(R.string.business_unrecognized_label), color = BusinessDanger, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.business_reenter_label), color = BusinessMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReceivingQuantityConfirmCard(
    preview: ReceivingQuickScanResponse,
    quantity: String,
    submitting: Boolean,
    onQuantityChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) = BusinessCard {
    val item = preview.data
    Text(stringResource(R.string.business_label_identified), color = BusinessSuccess, fontWeight = FontWeight.Bold)
    BusinessCopyableValueRow(
        label = stringResource(R.string.receiving_label_code),
        value = item.labelCode.ifBlank { item.boxNo.ifBlank { item.rawCode } },
    )
    val skuItems = preview.parsedPayload?.items.orEmpty()
    if (skuItems.isEmpty()) {
        BusinessCopyableValueRow(label = "SKU", value = item.skuCode)
        BusinessValueRow(
            label = stringResource(R.string.device_quantity),
            value = (item.qtyPerCarton.takeIf { it > 0 } ?: item.boxQty).takeIf { it > 0 }?.toString().orEmpty(),
        )
    } else {
        skuItems.forEach { parsedItem ->
            BusinessCopyableValueRow(label = "SKU", value = parsedItem.skuCode)
            BusinessValueRow(label = stringResource(R.string.device_quantity), value = parsedItem.quantity.toString())
        }
    }
    val qrQuantity = preview.parsedPayload?.totalQuantity ?: 0
    val serverQuantity = item.qtyPerCarton.takeIf { it > 0 } ?: item.boxQty
    if (!preview.localFallback && qrQuantity > 0 && serverQuantity > 0 && qrQuantity != serverQuantity) {
        Text(stringResource(R.string.business_qr_quantity_mismatch, qrQuantity, serverQuantity), color = BusinessWarning)
    }
    OutlinedTextField(
        value = quantity,
        onValueChange = onQuantityChange,
        label = { Text(stringResource(R.string.business_confirm_quantity)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
        Button(
            onClick = onConfirm,
            enabled = quantity.toIntOrNull()?.let { it > 0 } == true && !submitting,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.scan_receiving)) }
    }
}

@Composable
private fun CentralQuantityConfirmCard(
    preview: CentralReceiptScanResult,
    quantity: String,
    submitting: Boolean,
    onQuantityChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) = BusinessCard {
    val item: CentralReceiptBoxLabel? = preview.item
    Text(stringResource(R.string.business_label_identified), color = BusinessSuccess, fontWeight = FontWeight.Bold)
    BusinessCopyableValueRow(
        label = stringResource(R.string.receiving_label_code),
        value = item?.labelCode.orEmpty().ifBlank { item?.boxNo.orEmpty() },
    )
    val skuItems = preview.parsedPayload?.items.orEmpty()
    if (skuItems.isEmpty()) {
        BusinessCopyableValueRow(label = "SKU", value = item?.skuCode.orEmpty())
        BusinessValueRow(
            label = stringResource(R.string.device_quantity),
            value = item?.qtyPerCarton?.takeIf { it > 0 }?.toString().orEmpty(),
        )
    } else {
        skuItems.forEach { parsedItem ->
            BusinessCopyableValueRow(label = "SKU", value = parsedItem.skuCode)
            BusinessValueRow(label = stringResource(R.string.device_quantity), value = parsedItem.quantity.toString())
        }
    }
    val qrQuantity = preview.parsedPayload?.totalQuantity ?: 0
    val serverQuantity = item?.qtyPerCarton ?: 0
    if (!preview.localFallback && qrQuantity > 0 && serverQuantity > 0 && qrQuantity != serverQuantity) {
        Text(stringResource(R.string.business_qr_quantity_mismatch, qrQuantity, serverQuantity), color = BusinessWarning)
    }
    OutlinedTextField(
        value = quantity,
        onValueChange = onQuantityChange,
        label = { Text(stringResource(R.string.business_confirm_quantity)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
        Button(
            onClick = onConfirm,
            enabled = quantity.toIntOrNull()?.let { it > 0 } == true && !submitting,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.scan_receiving)) }
    }
}

@Composable
private fun BusinessCopyableValueRow(label: String, value: String) {
    val clipboardManager = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = BusinessMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Text(
            text = value.ifBlank { "-" },
            color = BusinessInk,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(value)) },
            enabled = value.isNotBlank(),
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.common_copy), tint = BusinessBrand)
        }
    }
}

@Composable
private fun BusinessValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = BusinessMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Text(value.ifBlank { "-" }, color = BusinessInk, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScanInput(
    loading: Boolean,
    preferBoxLabelCamera: Boolean,
    onScan: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var cameraOpen by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) cameraOpen = true }
    fun submit(code: String = value) {
        if (code.isBlank() || loading) return
        value = ""
        onScan(code.trim())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(stringResource(R.string.business_label_or_qr)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            enabled = !loading,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { permission.launch(Manifest.permission.CAMERA) }, enabled = !loading, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.business_camera_scan), tint = BusinessBrand)
        }
        Button(onClick = { submit() }, enabled = value.isNotBlank() && !loading, modifier = Modifier.height(48.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_confirm))
        }
    }
    if (cameraOpen) {
        Dialog(onDismissRequest = { cameraOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = {
                        cameraOpen = false
                        submit(it)
                    },
                    modifier = Modifier.fillMaxSize(),
                    showSelectionDialogForMultiple = preferBoxLabelCamera,
                    preferredBarcodeMatcher = if (preferBoxLabelCamera) ::isLikelyBoxLabelQr else null,
                    preferredBarcodeWaitMillis = BOX_LABEL_CANDIDATE_WAIT_MILLIS,
                    noPreferredBarcodeTitle = stringResource(R.string.box_label_scan_not_detected),
                    noPreferredBarcodePrompt = stringResource(R.string.box_label_scan_retry_prompt),
                    retryScanLabel = stringResource(R.string.box_label_scan_retry),
                )
                IconButton(onClick = { cameraOpen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.White, RoundedCornerShape(8.dp))) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.business_close_camera))
                }
            }
        }
    }
}

private const val BOX_LABEL_CANDIDATE_WAIT_MILLIS = 800L

@Composable
private fun SearchBar(value: String, placeholder: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.business_search_refresh), tint = BusinessBrand) }
    }
}

@Composable
private fun BusinessFilters(selected: BusinessFilter, onSelected: (BusinessFilter) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BusinessFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = {
                    Text(
                        when (filter) {
                            BusinessFilter.Pending -> stringResource(R.string.status_pending)
                            BusinessFilter.Completed -> stringResource(R.string.status_completed)
                            BusinessFilter.All -> stringResource(R.string.common_all)
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricRow(metrics: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        metrics.forEach { (label, value) ->
            Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, BusinessStroke)) {
                Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, fontWeight = FontWeight.Bold, color = BusinessDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = BusinessMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReceivingSessionCard(
    session: ReceivingSession,
    onClick: () -> Unit,
) = BusinessCard(onClick) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(session.code.ifBlank { "RS-${session.id}" }, fontWeight = FontWeight.Bold, color = BusinessInk, modifier = Modifier.weight(1f))
        StatusPill(businessStatusText(session.status), statusColor(session.status))
    }
    Text(listOf(session.warehouseName, session.containerNo).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { stringResource(R.string.business_unbound_warehouse) }, color = BusinessMuted)
    Text(stringResource(R.string.business_receipt_progress, session.talliedBoxCount.takeIf { it > 0 } ?: session.scannedBoxCount, session.expectedBoxCount, session.exceptionBoxCount), color = BusinessDeep)
}

@Composable
private fun CentralReceiptCard(receipt: CentralReceipt, onClick: () -> Unit) = BusinessCard(onClick) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(receipt.code.ifBlank { "CR-${receipt.id}" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusPill(businessStatusText(receipt.status), statusColor(receipt.status))
    }
    Text(receipt.sourceName.ifBlank { receipt.externalOrderNo.ifBlank { stringResource(R.string.business_unbound_source) } }, color = BusinessMuted)
    val total = receipt.items.sumOf { it.expectedBoxCount }
    val received = receipt.items.sumOf { it.receivedBoxCount }
    Text(stringResource(R.string.business_central_summary, receipt.items.size, (total - received).coerceAtLeast(0), received, total), color = BusinessDeep)
    Text(receipt.warehouseName.ifBlank { receipt.warehouseCode.ifBlank { stringResource(R.string.business_central_warehouse) } }, style = MaterialTheme.typography.bodySmall, color = BusinessMuted)
}

@Composable
private fun SupplierOrderCard(order: SupplierOrder, onClick: () -> Unit) = BusinessCard(onClick) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(order.code, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusPill(supplierStatusText(order.status), statusColor(order.status))
    }
    Text(order.manufacturer?.shortName.orEmpty().ifBlank { order.manufacturer?.name ?: stringResource(R.string.business_unbound_manufacturer) }, color = BusinessInk)
    Text(order.purchaseOrder?.code.orEmpty().ifBlank { stringResource(R.string.business_unbound_purchase_order) }, color = BusinessMuted)
    Text(order.productRefs.take(3).joinToString(" · ") { it.code }.ifBlank { stringResource(R.string.business_no_product_code) }, color = BusinessMuted, maxLines = 2)
    Text(stringResource(R.string.business_order_amount_volume, order.totalAmount, order.totalBoxCount, order.totalVolume), color = BusinessDeep)
}

@Composable
private fun PurchaseApprovalCard(approval: PurchaseApproval, onClick: () -> Unit) = BusinessCard(onClick) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(approval.code.ifBlank { "SO-${approval.id}" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusPill(supplierStatusText(approval.status), statusColor(approval.status))
    }
    Text(approval.manufacturer?.shortName.orEmpty().ifBlank { approval.manufacturer?.name ?: stringResource(R.string.business_unbound_manufacturer) }, color = BusinessInk)
    Text(stringResource(R.string.business_approval_card_summary, approval.currency, approval.totalAmount, approval.skuCount, approval.totalBoxCount), color = BusinessDeep)
    if (approval.missingPriceCount > 0) Text(stringResource(R.string.business_missing_price_count, approval.missingPriceCount), color = BusinessDanger, fontWeight = FontWeight.SemiBold)
    if (approval.warningText.isNotBlank()) Text(approval.warningText, color = BusinessWarning, maxLines = 2)
}

@Composable
private fun CentralScanRow(
    result: CentralReceiptScanResult,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) = ScanResultRow(
    code = result.item?.labelCode.orEmpty().ifBlank { result.item?.boxNo.orEmpty() },
    message = centralResultMessage(result),
    success = (result.confirmed || result.status in setOf("normal", "received")) && !result.duplicate,
    onRetry = onRetry,
    onCancel = onCancel,
)

@Composable
private fun receivingResultMessage(result: ReceivingQuickScanResponse): String =
    if (result.localFallback) {
        stringResource(if (result.duplicate) R.string.business_offline_duplicate else R.string.business_offline_saved)
    } else {
        result.message.ifBlank { businessStatusText(result.data.status) }
    }

@Composable
private fun centralResultMessage(result: CentralReceiptScanResult): String =
    if (result.localFallback) {
        stringResource(if (result.duplicate) R.string.business_offline_duplicate else R.string.business_offline_saved)
    } else {
        result.message.ifBlank { businessStatusText(result.status) }
    }

@Composable
private fun ScanResultRow(
    code: String,
    message: String,
    success: Boolean,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) = BusinessCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(code.ifBlank { stringResource(R.string.business_unrecognized_box_label) }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        StatusPill(stringResource(if (success) R.string.common_success else R.string.status_pending), if (success) BusinessSuccess else BusinessWarning)
    }
    Text(message, color = BusinessMuted)
    if (onRetry != null || onCancel != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            onRetry?.let { retry -> TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) } }
            onCancel?.let { cancel -> TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel)) } }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun Notice(error: UiText?, message: UiText?, onDismiss: (() -> Unit)?) {
    val text = (error ?: message)?.resolve() ?: return
    val color = if (error != null) BusinessDanger else BusinessSuccess
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, modifier = Modifier.weight(1f))
        if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.business_close_notice), tint = color) }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.Bold, color = BusinessInk, modifier = Modifier.padding(top = 4.dp))

@Composable private fun LoadingRow() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable private fun EmptyState(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = BusinessMuted) }

@Composable
internal fun businessStatusText(status: String): String = businessStatusTextRes(status)
    ?.let { stringResource(it) }
    ?: status.ifBlank { stringResource(R.string.status_pending) }

@StringRes
internal fun businessStatusTextRes(status: String): Int? = when (status.lowercase()) {
    "draft" -> R.string.status_draft
    "pending", "tally" -> R.string.business_metric_pending
    "partial" -> R.string.business_status_partial
    "scanning" -> R.string.business_status_scanning
    "normal" -> R.string.business_status_receivable
    "received" -> R.string.status_received
    "completed" -> R.string.status_completed
    "cancelled" -> R.string.status_cancelled
    "duplicate" -> R.string.status_duplicate
    "wrong_order" -> R.string.business_status_wrong_order
    "unknown" -> R.string.common_unknown
    "damaged" -> R.string.status_damaged
    "voided" -> R.string.status_voided
    "pending_sync", "complete_pending_sync" -> R.string.business_save_pending_sync
    else -> null
}

@Composable
internal fun supplierStatusText(status: String): String = supplierStatusTextRes(status)
    ?.let { stringResource(it) }
    ?: status.ifBlank { stringResource(R.string.status_pending) }

@StringRes
internal fun supplierStatusTextRes(status: String): Int? = when (status.lowercase()) {
    "draft" -> R.string.status_draft
    "pending_quote" -> R.string.business_status_pending_review
    "pending_price_approval" -> R.string.business_status_historical_price_review
    "ready_to_order" -> R.string.business_status_ready_to_order
    "producing" -> R.string.business_status_producing
    "shipped" -> R.string.business_status_shipped
    "same_day_delivery" -> R.string.business_status_same_day_delivery
    "arrived_central_warehouse" -> R.string.business_status_arrived_central
    "completed" -> R.string.status_completed
    "archived" -> R.string.status_archived
    else -> null
}

private fun statusColor(status: String): Color = when (status.lowercase()) {
    "completed", "received", "normal", "ready_to_order", "arrived_central_warehouse", "archived" -> BusinessSuccess
    "damaged", "wrong_order", "unknown", "exception", "cancelled", "voided" -> BusinessDanger
    "partial", "pending_sync", "pending_quote", "pending_price_approval" -> BusinessWarning
    else -> BusinessBrand
}

private fun String.businessTime(): String = takeIf { it.isNotBlank() }?.take(16)?.replace('T', ' ') ?: "-"
