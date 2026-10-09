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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxLabelManagementContent(
    state: CartonManagementUiState,
    viewModel: CartonManagementViewModel,
    onNavigate: (FeatureRoute) -> Unit,
    initialCode: String = "",
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState(state.listIndex, state.listOffset)
    DisposableEffect(listState) {
        onDispose { viewModel.rememberListPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    var previousQuery by remember { mutableStateOf(state.query) }
    LaunchedEffect(state.query) {
        if (previousQuery != state.query) listState.scrollToItem(0)
        previousQuery = state.query
    }
    var camera by remember { mutableStateOf(false) }
    var qrCode by rememberSaveable(state.detailId) { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val showQrCode: (String) -> Unit = { code ->
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        qrCode = code
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    DisposableEffect(lifecycleOwner, state.detailId, qrCode) {
        val ownsScanner = state.detailId == null || qrCode != null
        if (ownsScanner) {
            viewModel.registerScanner(lifecycleOwner) { code ->
                if (qrCode == null) viewModel.resolve(code)
            }
        }
        onDispose { if (ownsScanner) viewModel.unregisterScanner() }
    }
    LaunchedEffect(initialCode) { viewModel.loadInitialCode(initialCode) }
    BackHandler(enabled = state.detailId != null) { viewModel.closeDetail() }
    if (camera) {
        Dialog(onDismissRequest = { camera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BarcodeScanner(
                onBarcodeDetected = {
                    camera = false
                    viewModel.resolve(it)
                },
                modifier = Modifier.fillMaxSize(),
                showSelectionDialogForMultiple = true,
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.detailId != null) {
            TextButton(onClick = viewModel::closeDetail, enabled = !state.printSubmitting) { Text("返回箱唛列表") }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { item { FscmErrorCard(it, viewModel::retry) } }
                if (state.detailNotice.isNotBlank()) item { Text(state.detailNotice) }
                state.boxLabel?.let { label ->
                    item {
                        FscmCard(Modifier.fillMaxWidth()) {
                            BoxLabelCodeButton(label.labelCode, showQrCode, style = MaterialTheme.typography.titleLarge)
                            StatusChip(label.statusLabel.ifBlank { label.status }, MaterialTheme.colorScheme.primary)
                            if (state.lastScannedCode.isNotBlank() && state.searchInfo?.resolvedVia in listOf("adjustment_alias", "jifeng_mapping")) {
                                Text(if (state.searchInfo?.resolvedVia == "jifeng_mapping") "由极风码定位当前箱唛：${state.lastScannedCode}" else "由旧码解析至当前箱唛：${state.lastScannedCode}")
                            }
                            LabelValue("箱号", label.boxNo)
                            LabelValue("创建时间", label.createdAt)
                            LabelValue("箱规", label.caseSpecName)
                            LabelValue("箱规数量", label.qtyPerCarton.takeIf { it > 0 }?.toString().orEmpty())
                            LabelValue("长 × 宽 × 高", label.dimensionText())
                            LabelValue(
                                "尺寸来源",
                                when (label.dimensionsSource) {
                                    "mixed_case_spec" -> "混箱箱规"
                                    "box_case_spec" -> "该箱关联箱规"
                                    else -> "未维护"
                                },
                            )
                            LabelValue("计划箱内数量", label.plannedBoxQty.toString())
                            LabelValue("实际收货数量", label.receiving?.takeIf { it.status == "received" || it.status == "damaged" }?.actualReceivedQty?.toString().orEmpty())
                            LabelValue("仓库 / 库位", "${label.receiving?.warehouseCode.orEmpty()} / ${label.receiving?.locationCode.orEmpty()}")
                            LabelValue("血缘标识", label.lineageId.ifBlank { label.boxUid })
                        }
                    }
                    item { Text(if (label.isMixed) "混箱 SKU 明细" else "SKU 明细", fontWeight = FontWeight.Bold) }
                    items(label.skuItems) { sku ->
                        FscmCard(Modifier.fillMaxWidth()) {
                            LabelValue("SKU", sku.displaySkuCode)
                            Text(sku.skuName)
                            LabelValue("产品", "${sku.displayProductCode} ${sku.productName}")
                            LabelValue("箱内数量", sku.qtyPerBox.toString())
                        }
                    }
                    item { Text("上下游关系", fontWeight = FontWeight.Bold) }
                    items(label.relations) { relation ->
                        FscmCard(Modifier.fillMaxWidth()) {
                            if (relation.historical) {
                                Text("历史关系 · ${relation.boxLabelCode}", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("当前关系", style = MaterialTheme.typography.labelMedium)
                            }
                            LabelValue(relationName(relation.type), relation.code)
                            val route = when (relation.type) {
                                "supplier_order" -> FeatureRoute.SupplierOrderDetail(relation.id)
                                "central_receipt" -> FeatureRoute.CentralReceivingDetail(relation.id)
                                "receiving_session" -> FeatureRoute.ReceivingDetail(relation.id)
                                else -> null
                            }
                            if (route != null) TextButton(onClick = { onNavigate(route) }) { Text("查看单据") }
                        }
                    }
                    item { Text("映射与变更历史", fontWeight = FontWeight.Bold) }
                    if (label.aliasHistory.isEmpty() && label.lifecycleEvents.isEmpty()) item { Text("暂无变更记录") }
                    items(label.aliasHistory) { alias ->
                        FscmCard(Modifier.fillMaxWidth()) {
                            LabelValue("映射", "${alias.sourceLabelCode} → ${alias.targetLabelCode}")
                            Text("数量 ${alias.sourceQuantity} → ${alias.targetQuantity}")
                            Text(
                                when (alias.status) {
                                    "active" -> "可识别"
                                    "consumed" -> "已收货"
                                    "invalidated" -> "已失效"
                                    "unmatched" -> "待人工核对"
                                    else -> alias.status
                                },
                            )
                        }
                    }
                    items(label.lifecycleEvents.reversed()) { event ->
                        FscmCard(Modifier.fillMaxWidth()) {
                            Text("${lifecycleName(event.action)} · ${event.createdAt}")
                            Text("${event.fromStatus} → ${event.toStatus}")
                            LabelValue("操作人", event.operator)
                            LabelValue("原因", event.reason)
                            if (event.sourceId > 0) LabelValue("历史关联", "${relationName(event.sourceType)} ${event.sourceId}")
                        }
                    }
                }
            }
            state.boxLabel?.let { label ->
                Column(Modifier.padding(16.dp)) {
                    BoxMarkPrintAction(label, state.loadingPrintOptions, state.printSubmitting, state.printMessage, state.printError, viewModel::requestBoxMarkPrint)
                }
            }
        } else {
            PullToRefreshBox(isRefreshing = state.listLoading && state.page > 0, onRefresh = { viewModel.searchLabels() }) {
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { if (it.contains('\n') || it.contains('\r') || it.contains('\t')) viewModel.resolve(it) else viewModel.setQuery(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("箱唛码、产品编码或 SKU 编码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.resolve(state.query) }),
                        )
                        Row {
                            Button(onClick = { permission.launch(android.Manifest.permission.CAMERA) }) { Text("扫描箱唛 / SKU") }
                            if (state.query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text("清空") }
                        }
                        Text(if (state.query.isBlank()) "最新创建 · 共 ${state.total} 箱" else "搜索结果 · 共 ${state.total} 箱")
                        state.searchInfo?.let { info ->
                            Text(
                                "匹配：${info.kinds.joinToString("、") {
                                    when (it) {
                                        "box_label" -> "箱唛"
                                        "product" -> "产品"
                                        "sku" -> "SKU"
                                        else -> it
                                    }
                                }} · ${if (info.matchMode == "exact") "精确" else "前缀"}",
                            )
                        }
                        if (state.catalogNotice.isNotBlank()) Text(state.catalogNotice)
                    }
                    if (state.listLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let { item { FscmErrorCard(it, viewModel::retry) } }
                    items(state.labels, key = BoxLabel::id) { label ->
                        var expanded by remember(label.id) { mutableStateOf(false) }
                        FscmCard(Modifier.fillMaxWidth().clickable { viewModel.openDetail(label.id) }) {
                            BoxLabelCodeButton(label.labelCode, showQrCode)
                            StatusChip(label.statusLabel.ifBlank { label.status }, MaterialTheme.colorScheme.primary)
                            val skus = label.skuItems.sortedByDescending { it.displaySkuCode.startsWith(state.query, ignoreCase = true) || it.displayProductCode.startsWith(state.query, ignoreCase = true) }
                            if (label.isMixed) Text("混箱 · ${skus.size} 个 SKU")
                            (if (expanded) skus else skus.take(2)).forEach { Text("${it.displaySkuCode} · ${it.qtyPerBox} 件") }
                            if (skus.size > 2) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看全部 SKU") }
                            Text("箱规数量 ${label.qtyPerCarton.takeIf { it > 0 } ?: "未维护"} · 计划 ${label.plannedBoxQty} 件")
                            Text(label.dimensionText())
                            Text(label.createdAt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!state.listLoading && state.labels.isEmpty() && state.error == null) item { Text("未找到箱唛") }
                    if (state.labels.size < state.total && state.error == null) {
                        item {
                            LaunchedEffect(state.page, state.query) { viewModel.searchLabels(more = true) }
                        }
                    }
                }
            }
        }
    }
    qrCode?.let { code -> BoxLabelCodeDialog(code = code, onDismiss = { qrCode = null }) }
    if (state.printDialogVisible) {
        BoxMarkPrintDialog(
            state.boxMarkOptions,
            state.selectedBoxMarkOptionKey,
            state.boxMarkCopies,
            state.printSubmitting,
            viewModel::selectBoxMarkOption,
            viewModel::updateBoxMarkCopies,
            viewModel::confirmBoxMarkPrint,
            viewModel::dismissBoxMarkPrintDialog,
            label = state.boxLabel,
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    SelectionContainer { Text("$label：${value.ifBlank { "未维护" }}", modifier = Modifier.padding(vertical = 3.dp)) }
}

internal fun BoxLabel.dimensionText(): String = cartonSizeCm?.takeIf { it.length > 0 && it.width > 0 && it.height > 0 }
    ?.let { "${it.length} × ${it.width} × ${it.height} cm" } ?: "长 × 宽 × 高：未维护"

private fun relationName(type: String): String = mapOf(
    "purchase_order" to "采购订单", "purchase_order_item" to "采购明细", "supplier_order" to "厂家订单", "supplier_order_item" to "厂家明细",
    "shipment_batch" to "发货批次", "shipment_batch_item" to "发货明细", "central_receipt" to "集运收货单", "central_receipt_item" to "集运收货明细",
    "central_inventory_lot" to "库存批次", "consolidation_order" to "集运订单", "consolidation_order_item" to "集运明细", "consolidation_container" to "集运柜",
    "receiving_session" to "收货会话", "receiving_scan" to "扫描记录", "receiving_record" to "收货记录", "receiving_record_item" to "收货明细",
)[type] ?: type

private fun lifecycleName(action: String): String = mapOf(
    "created" to "生成箱唛", "alias_mapped" to "建立旧码映射", "mark" to "标记异常", "restore" to "恢复箱唛", "void" to "作废箱唛",
    "remap" to "重新映射", "shortage_marked" to "标记短少", "adjusted_out" to "调整退出", "retired_unlinked" to "解除退役关联", "receiving_session_bound" to "绑定收货会话",
)[action] ?: action
