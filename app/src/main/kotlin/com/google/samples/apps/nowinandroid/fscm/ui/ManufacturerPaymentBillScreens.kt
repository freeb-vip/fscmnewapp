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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillBucket
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillGroup
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentBill
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentInfo
import com.google.samples.apps.nowinandroid.fscm.data.PaymentAttachment
import com.google.samples.apps.nowinandroid.fscm.data.PaymentConfigOption
import java.io.File
import java.util.Locale

private val BillBackground = Color(0xFFF5F8FF)
private val BillStroke = Color(0xFFDCE8FA)
private val BillInk = Color(0xFF102A43)
private val BillMuted = Color(0xFF6B7C93)
private val BillBlue = Color(0xFF1677FF)
private val BillDeepBlue = Color(0xFF0958D9)
private val BillSuccess = Color(0xFF389E0D)
private val BillWarning = Color(0xFFD48806)
private val BillDanger = Color(0xFFCF1322)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManufacturerPaymentBillsScreen(
    onOpenBill: (Long) -> Unit,
    onOpenSupplierOrder: (Long) -> Unit,
    viewModel: ManufacturerPaymentBillsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(Unit) { viewModel.load(true) }

    if (state.showFilters) {
        ManufacturerBillFilterSheet(
            initialStartDate = state.startDate,
            initialEndDate = state.endDate,
            onDismiss = viewModel::closeFilters,
            onApply = viewModel::applyFilters,
        )
    }

    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { viewModel.load(true) },
        modifier = Modifier.fillMaxSize().background(BillBackground),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ManufacturerBillSummaryCard(state.summary)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("厂家、产品编码、订单或账单") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.load(true) }),
                    )
                    IconButton(onClick = viewModel::openFilters) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "筛选", tint = BillBlue)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ManufacturerBillBucket.entries.forEach { bucket ->
                        FilterChip(
                            selected = state.bucket == bucket,
                            onClick = { viewModel.setBucket(bucket) },
                            label = {
                                Text(
                                    when (bucket) {
                                        ManufacturerBillBucket.Draft -> "草稿"
                                        ManufacturerBillBucket.PendingPayment -> "待付款"
                                        ManufacturerBillBucket.Paid -> "已付款"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            state.error?.let { error ->
                item {
                    BillNotice(error.resolve(), BillDanger) {
                        TextButton(onClick = {
                            viewModel.consumeError()
                            viewModel.load(true)
                        }) { Text("重试") }
                    }
                }
            }
            if (!state.loading && state.groups.isEmpty()) {
                item { BillEmpty("暂无符合条件的厂家账单") }
            }
            items(state.groups, key = { it.supplierOrder.id }) { group ->
                val isExpanded = expanded[group.supplierOrder.id] ?: (group.billCount == 1)
                ManufacturerBillGroupCard(
                    group = group,
                    expanded = isExpanded,
                    onToggle = { expanded[group.supplierOrder.id] = !isExpanded },
                    onOpenBill = onOpenBill,
                    onOpenSupplierOrder = onOpenSupplierOrder,
                )
            }
            if (state.hasMore) {
                item {
                    OutlinedButton(
                        onClick = { viewModel.load(false) },
                        enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManufacturerBillSummaryCard(summary: com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillSummary) {
    BillCard(containerColor = Color(0xFFEEF6FF)) {
        Text("待付款金额", color = BillMuted, style = MaterialTheme.typography.labelLarge)
        Text(
            money("CNY", summary.pendingAmount),
            color = BillDeepBlue,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("待付款 ${summary.pending} 笔", color = BillWarning, fontWeight = FontWeight.SemiBold)
            Text("已付 ${summary.paid} 笔 · ${money("CNY", summary.paidAmount)}", color = BillMuted)
        }
    }
}

@Composable
private fun ManufacturerBillGroupCard(
    group: ManufacturerBillGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenBill: (Long) -> Unit,
    onOpenSupplierOrder: (Long) -> Unit,
) {
    val order = group.supplierOrder
    BillCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    order.manufacturer?.shortName.orEmpty().ifBlank { order.manufacturer?.name.orEmpty().ifBlank { "未绑定厂家" } },
                    color = BillInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onOpenSupplierOrder(order.id) }, contentPadding = PaddingValues(0.dp)) {
                    Text(order.code.ifBlank { "厂家订单 #${order.id}" }, color = BillBlue)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "收起账单" else "展开账单")
            }
        }
        val purchaseCode = order.purchaseOrder?.code.orEmpty()
        if (purchaseCode.isNotBlank()) Text("采购单：$purchaseCode", color = BillMuted)
        Text(
            "产品：" + group.productRefs.take(2).joinToString("、") { it.code }.ifBlank { "-" } +
                if (group.productRefs.size > 2) " 等 ${group.productRefs.size - 2} 个" else "",
            color = BillMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BillStatusPill("草稿 ${group.bills.count { it.bill.status == "draft" }}", BillMuted)
            BillStatusPill("待付款 ${group.bills.count { it.bill.status in pendingPaymentStatuses }}", BillWarning)
            BillStatusPill("已付款 ${group.bills.count { it.bill.status == "paid" }}", BillSuccess)
        }
        group.currencyAmounts.toSortedMap().forEach { (currency, amount) ->
            Text(money(currency, amount), color = BillDeepBlue, fontWeight = FontWeight.Bold)
        }
        if (expanded) {
            HorizontalDivider(color = BillStroke)
            group.bills.forEachIndexed { index, item ->
                ManufacturerBillRow(item.bill, onOpenBill)
                if (index < group.bills.lastIndex) HorizontalDivider(color = BillStroke.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun ManufacturerBillRow(bill: ManufacturerPaymentBill, onOpenBill: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenBill(bill.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bill.billNo.ifBlank { "账单 #${bill.id}" }, color = BillInk, fontWeight = FontWeight.SemiBold)
                BillStatusPill(paymentBillStatusText(bill.status), paymentBillStatusColor(bill.status))
            }
            Text(
                "总额 ${money(bill.currency, bill.totalAmount)} · 已付 ${money(bill.currency, bill.paidTotalAmount)}",
                color = BillMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("剩余 ${money(bill.currency, bill.remainingAmount)}", color = BillDeepBlue, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "查看详情", tint = BillBlue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManufacturerBillFilterSheet(
    initialStartDate: String,
    initialEndDate: String,
    onDismiss: () -> Unit,
    onApply: (String, String) -> Unit,
) {
    var startDate by remember(initialStartDate) { mutableStateOf(initialStartDate) }
    var endDate by remember(initialEndDate) { mutableStateOf(initialEndDate) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("筛选厂家账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(startDate, { startDate = it }, label = { Text("开始日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(endDate, { endDate = it }, label = { Text("结束日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onApply(startDate, endDate) }, modifier = Modifier.fillMaxWidth()) { Text("应用筛选") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManufacturerPaymentBillDetailScreen(
    id: Long,
    onOpenSupplierOrder: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ManufacturerPaymentBillDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    val bill = detail?.bill
    var showAllItems by remember { mutableStateOf(false) }
    LaunchedEffect(id) { viewModel.load(id) }

    BackHandler(enabled = state.hasUnsubmittedAttachments) { viewModel.requestDiscardNavigation() }

    if (state.action != null) {
        ManufacturerBillActionSheet(state, viewModel)
    }
    if (state.confirmSubmit) {
        val actionText = when (state.action) {
            ManufacturerBillAction.Reject -> "确认驳回厂家账单？"
            ManufacturerBillAction.Approve -> "确认通过并支付 ${money(bill?.currency.orEmpty(), state.amount.toDoubleOrNull() ?: 0.0)}？"
            ManufacturerBillAction.Pay -> "确认登记本次付款 ${money(bill?.currency.orEmpty(), state.amount.toDoubleOrNull() ?: 0.0)}？"
            null -> "确认操作？"
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelSubmit,
            title = { Text(actionText) },
            text = {
                if (state.action != ManufacturerBillAction.Reject) {
                    val remaining = ((bill?.remainingAmount ?: 0.0) - (state.amount.toDoubleOrNull() ?: 0.0)).coerceAtLeast(0.0)
                    Text("厂家：${detail?.counterparty?.manufacturer?.name.orEmpty().ifBlank { "-" }}\n操作后剩余：${money(bill?.currency.orEmpty(), remaining)}")
                } else {
                    Text(state.remark)
                }
            },
            confirmButton = { Button(onClick = { viewModel.confirmSubmit(id) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = viewModel::cancelSubmit) { Text("取消") } },
        )
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDiscard,
            title = { Text("附件尚未提交") },
            text = { Text("已上传附件还没有关联到付款记录，确定放弃吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val navigate = state.discardNavigation
                        viewModel.discardAttachmentsAndCloseAction()
                        if (navigate) onBack()
                    },
                ) { Text("放弃") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDiscard) { Text("继续编辑") } },
        )
    }

    Box(Modifier.fillMaxSize().background(BillBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error -> item { BillNotice(error.resolve(), BillDanger) { TextButton(onClick = { viewModel.load(id) }) { Text("重试") } } } }
            state.message?.let { message -> item { BillNotice(message.resolve(), BillSuccess) { TextButton(onClick = viewModel::consumeNotice) { Text("知道了") } } } }
            if (state.loading && bill == null) item { BillLoading("正在加载厂家账单") }
            bill?.let { current ->
                item { BillAmountCard(current) }
                detail.paymentExecution?.takeIf { !it.canExecute }?.let { execution ->
                    item { BillNotice(execution.blockedReason.ifBlank { "当前账单不能执行付款" }, BillDanger) }
                }
                if (current.approvalProvider != "local") {
                    item { BillNotice("该账单由企业微信审批，App 仅提供查看", BillWarning) }
                }
                if (current.status == "credit_pending_repayment") {
                    item { BillNotice("诚意赊账单请到 Web 端处理兑付", BillWarning) }
                }
                item { ManufacturerAndOrderCard(detail, onOpenSupplierOrder) }
                item { BillPaymentInformationCard(current, detail.counterparty.paymentInfos) }
                detail.sourceDetail?.takeIf { it.items.isNotEmpty() }?.let { source ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("产品明细", color = BillInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (source.items.size > 3) TextButton(onClick = { showAllItems = !showAllItems }) { Text(if (showAllItems) "收起" else "展开全部 ${source.items.size} 项") }
                        }
                    }
                    items(if (showAllItems) source.items else source.items.take(3), key = { it.supplierOrderItemId }) { item ->
                        BillCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.productCode.ifBlank { item.skuCode.ifBlank { "产品" } }, color = BillInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(money(item.currency, item.totalAmount), color = BillDeepBlue, fontWeight = FontWeight.Bold)
                            }
                            Text(item.productName.ifBlank { item.skuName.ifBlank { "未命名产品" } }, color = BillMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("SKU ${item.skuCode.ifBlank { "-" }} · ${item.boxCount} 箱 · ${item.totalQty} 件", color = BillMuted)
                            Text("单价 ${money(item.currency, item.unitPrice)}", color = BillMuted)
                        }
                    }
                }
                item { BillAttachmentHistoryCard(current.attachments, state.payments) }
                if (current.approverName.isNotBlank() || current.approvalRemark.isNotBlank()) {
                    item {
                        BillCard {
                            Text("付款处理信息", color = BillInk, fontWeight = FontWeight.Bold)
                            if (current.approverName.isNotBlank()) Text("处理人：${current.approverName}", color = BillMuted)
                            if (current.approvedAt.isNotBlank()) Text("处理时间：${current.approvedAt.displayDateTime()}", color = BillMuted)
                            if (current.approvalRemark.isNotBlank()) Text("备注：${current.approvalRemark}", color = BillMuted)
                            if (current.approvalException) Text(current.approvalExceptionReason.ifBlank { "付款处理状态异常" }, color = BillDanger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        bill?.let { current ->
            val isLocal = current.approvalProvider == "local"
            val canExecutePayment = detail.paymentExecution?.canExecute != false
            when {
                isLocal && current.status == "submitted" -> {
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.White, shadowElevation = 10.dp) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = viewModel::openReject, enabled = !state.submitting, modifier = Modifier.weight(1f)) { Text("驳回", color = BillDanger) }
                            Button(onClick = viewModel::openApprove, enabled = !state.submitting && canExecutePayment, modifier = Modifier.weight(1f)) { Text("付款并审核") }
                        }
                    }
                }
                isLocal && canExecutePayment && current.status == "partial_paid" && current.paymentStrategy == "deposit_payment" -> {
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.White, shadowElevation = 10.dp) {
                        Button(onClick = viewModel::openPayment, enabled = !state.submitting, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("继续付款") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillAmountCard(bill: ManufacturerPaymentBill) {
    BillCard(containerColor = Color(0xFFEEF6FF)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(bill.billNo.ifBlank { "厂家账单 #${bill.id}" }, color = BillInk, fontWeight = FontWeight.Bold)
            BillStatusPill(paymentBillStatusText(bill.status), paymentBillStatusColor(bill.status))
        }
        Text(money(bill.currency, if (bill.status == "paid") bill.paidTotalAmount else bill.remainingAmount), color = BillDeepBlue, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(if (bill.status == "paid") "实付金额" else "剩余应付", color = BillMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("账单 ${money(bill.currency, bill.totalAmount)}", color = BillMuted)
            Text("已付 ${money(bill.currency, bill.paidTotalAmount)}", color = BillSuccess)
        }
    }
}

@Composable
private fun ManufacturerAndOrderCard(
    detail: com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillDetail,
    onOpenSupplierOrder: (Long) -> Unit,
) {
    val manufacturer = detail.counterparty.manufacturer
    val related = detail.paymentExecution?.relatedSupplierOrders?.firstOrNull()
    BillCard {
        Text("厂家与订单", color = BillInk, fontWeight = FontWeight.Bold)
        Text(manufacturer?.shortName.orEmpty().ifBlank { manufacturer?.name.orEmpty().ifBlank { "未绑定厂家" } }, color = BillInk, style = MaterialTheme.typography.titleMedium)
        manufacturer?.code?.takeIf(String::isNotBlank)?.let { Text("厂家编码：$it", color = BillMuted) }
        related?.let { order ->
            HorizontalDivider(color = BillStroke)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSupplierOrder(order.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("厂家订单：${order.code.ifBlank { "#${order.id}" }}", color = BillBlue, fontWeight = FontWeight.SemiBold)
                    if (order.purchaseOrderCode.isNotBlank()) Text("采购单：${order.purchaseOrderCode}", color = BillMuted)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "查看厂家订单", tint = BillBlue)
            }
        }
    }
}

@Composable
private fun BillPaymentInformationCard(bill: ManufacturerPaymentBill, infos: List<ManufacturerPaymentInfo>) {
    val selectedInfo = infos.firstOrNull { it.id == bill.manufacturerPaymentInfoId }
    BillCard {
        Text("付款信息", color = BillInk, fontWeight = FontWeight.Bold)
        Text("实付金额：${money(bill.currency, bill.paidTotalAmount)}", color = BillDeepBlue, fontWeight = FontWeight.SemiBold)
        Text("付款方式：${bill.paymentMethod.ifBlank { "未记录" }}", color = BillInk)
        Text("付款来源：${bill.paymentSource.ifBlank { "未记录" }}", color = BillInk)
        Text("付款人：${bill.payer.ifBlank { "未记录" }}", color = BillMuted)
        Text("付款时间：${bill.paymentDate.ifBlank { bill.paidAt }.displayDateTime().ifBlank { "未记录" }}", color = BillMuted)
        if (bill.chengyisheOrderNo.isNotBlank()) Text("诚意赊订单号：${bill.chengyisheOrderNo}", color = BillMuted)
        HorizontalDivider(color = BillStroke)
        Text("厂家收款信息", color = BillInk, fontWeight = FontWeight.SemiBold)
        if (selectedInfo == null) {
            Text(if (bill.manufacturerPaymentInfoId == null) "未选择厂家收款信息" else "原收款信息已不可用", color = BillMuted)
        } else {
            Text(selectedInfo.displayLabel(), color = BillMuted)
            if (selectedInfo.accountNo.isNotBlank()) Text("账号：${selectedInfo.accountNo}", color = BillInk)
            if (selectedInfo.bankBranch.isNotBlank()) Text("开户支行：${selectedInfo.bankBranch}", color = BillMuted)
            if (selectedInfo.qrImageUrl.isNotBlank()) AttachmentLink(PaymentAttachment(url = selectedInfo.qrImageUrl, name = "查看收款二维码"))
            if (selectedInfo.remark.isNotBlank()) Text(selectedInfo.remark, color = BillMuted)
        }
    }
}

@Composable
private fun BillAttachmentHistoryCard(
    billAttachments: List<PaymentAttachment>,
    payments: List<com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillPayment>,
) {
    BillCard {
        Text("付款凭据与记录", color = BillInk, fontWeight = FontWeight.Bold)
        Text(if (billAttachments.isEmpty()) "暂无付款凭据" else "付款凭据 · ${billAttachments.size} 个（点击查看）", color = BillMuted)
        billAttachments.forEach { attachment -> AttachmentLink(attachment) }
        payments.forEachIndexed { index, payment ->
            HorizontalDivider(color = BillStroke)
            Text("第 ${index + 1} 笔实付 · ${money(payment.currency, payment.amount)}", color = BillDeepBlue, fontWeight = FontWeight.Bold)
            Text("${payment.paymentDate.ifBlank { payment.createdAt }.displayDateTime()} · ${payment.payer.ifBlank { "未填写付款人" }}", color = BillMuted)
            Text("付款方式：${payment.paymentMethod.ifBlank { "未记录" }}", color = BillMuted)
            Text("付款来源：${payment.paymentSource.ifBlank { "未记录" }}", color = BillMuted)
            if (payment.remark.isNotBlank()) Text(payment.remark, color = BillMuted)
            payment.attachments.forEach { AttachmentLink(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManufacturerBillActionSheet(
    state: ManufacturerPaymentBillDetailUiState,
    viewModel: ManufacturerPaymentBillDetailViewModel,
) {
    val detail = state.detail ?: return
    val bill = detail.bill
    val action = state.action ?: return
    val infos = if (action == ManufacturerBillAction.Approve && state.paymentMethod == "诚意赊") {
        detail.counterparty.paymentInfos.filter { it.type == "chengyishe" }
    } else {
        detail.counterparty.paymentInfos.filterNot { it.type == "chengyishe" }
    }
    ModalBottomSheet(onDismissRequest = viewModel::closeAction) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    when (action) {
                        ManufacturerBillAction.Approve -> "付款并审核账单"
                        ManufacturerBillAction.Reject -> "驳回厂家账单"
                        ManufacturerBillAction.Pay -> "登记后续付款"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            state.error?.let { error -> item { BillNotice(error.resolve(), BillDanger) } }
            if (action == ManufacturerBillAction.Reject) {
                item {
                    OutlinedTextField(state.remark, viewModel::setRemark, label = { Text("驳回原因（必填）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            } else {
                item {
                    Text("厂家付款信息", fontWeight = FontWeight.SemiBold)
                    if (infos.isEmpty()) Text("该厂家没有可用的付款信息", color = BillDanger)
                }
                items(infos, key = { it.id }) { info ->
                    FilterChip(
                        selected = state.paymentInfoId == info.id,
                        onClick = { viewModel.setPaymentInfo(info.id) },
                        label = { Text(info.displayLabel()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action == ManufacturerBillAction.Approve && state.paymentMethod != "诚意赊") {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !state.partialPayment, onClick = { viewModel.setPartialPayment(false) }, label = { Text("一次付清") })
                            FilterChip(selected = state.partialPayment, onClick = { viewModel.setPartialPayment(true) }, label = { Text("预付定金") })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.amount,
                        onValueChange = viewModel::setAmount,
                        enabled = !state.submitting && state.paymentMethod != "诚意赊",
                        label = { Text("实付金额（${bill.currency}）") },
                        supportingText = { Text("账单金额 ${money(bill.currency, bill.totalAmount)} · 剩余 ${money(bill.currency, bill.remainingAmount)}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    PaymentOptionSelector(
                        label = "付款方式（必选）",
                        value = state.paymentMethod,
                        options = state.paymentMethods,
                        onSelect = viewModel::setPaymentMethod,
                    )
                }
                if (state.paymentMethod == "诚意赊") {
                    item {
                        OutlinedTextField(
                            value = state.chengyisheOrderNo,
                            onValueChange = viewModel::setChengyisheOrderNo,
                            label = { Text("诚意赊订单号（必填）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    PaymentOptionSelector(
                        label = "付款来源（必选）",
                        value = state.paymentSource,
                        options = state.paymentSources,
                        onSelect = viewModel::setPaymentSource,
                    )
                }
                if (action == ManufacturerBillAction.Pay) {
                    item { OutlinedTextField(state.payer, viewModel::setPayer, label = { Text("付款人（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(state.paymentDate, viewModel::setPaymentDate, label = { Text("付款日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                }
                item { OutlinedTextField(state.remark, viewModel::setRemark, label = { Text("备注（选填）") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
                item { PendingAttachmentPicker(state, viewModel) }
            }
            item {
                Button(
                    onClick = viewModel::requestSubmit,
                    enabled = !state.submitting && !state.uploading && (action == ManufacturerBillAction.Reject || infos.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(if (action == ManufacturerBillAction.Reject) "确认驳回" else "核对并确认")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentOptionSelector(
    label: String,
    value: String,
    options: List<PaymentConfigOption>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it && options.isNotEmpty() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = options.isNotEmpty(),
            label = { Text(label) },
            placeholder = { Text(if (options.isEmpty()) "后台暂无可用选项" else "请选择") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onSelect(option.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentPicker(
    state: ManufacturerPaymentBillDetailUiState,
    viewModel: ManufacturerPaymentBillDetailViewModel,
) {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::upload) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(viewModel::upload)
    }
    fun launchCamera() {
        val directory = File(context.cacheDir, "payment-attachments").apply { mkdirs() }
        val file = File(directory, "payment-${System.currentTimeMillis()}.jpg")
        cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        camera.launch(requireNotNull(cameraUri))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("付款凭据（最多 5 个）", fontWeight = FontWeight.SemiBold)
        state.pendingAttachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AttachmentLink(attachment, Modifier.weight(1f))
                TextButton(onClick = { viewModel.removeAttachment(attachment) }) { Text("移除") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                enabled = !state.uploading && state.pendingAttachments.size < 5,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("选择文件")
            }
            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera()
                    } else {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = !state.uploading && state.pendingAttachments.size < 5,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("拍照")
            }
        }
        if (state.uploading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在上传凭据", color = BillMuted)
            }
        }
        Text("支持 JPG、PNG、GIF、WEBP、PDF，单个不超过 10MB", color = BillMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AttachmentLink(attachment: PaymentAttachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var imagePreview by remember { mutableStateOf(false) }
    var imageFailed by remember(attachment.url) { mutableStateOf(false) }
    val isPdf = attachment.mimeType == "application/pdf" || attachment.name.endsWith(".pdf", true) || Uri.parse(attachment.url).path.orEmpty().endsWith(".pdf", true)
    Row(
        modifier = modifier.fillMaxWidth().clickable {
            if (attachment.url.isBlank()) {
                Toast.makeText(context, "附件链接不可用，请重新进入账单详情后重试", Toast.LENGTH_SHORT).show()
            } else if (!isPdf) {
                imagePreview = true
            } else if (attachment.url.isNotBlank()) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(attachment.url)).apply {
                            setDataAndType(Uri.parse(attachment.url), attachment.mimeType.ifBlank { "application/pdf" })
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.url))) }
                        .onFailure { Toast.makeText(context, "没有可用的文件查看器", Toast.LENGTH_SHORT).show() }
                }
            }
        }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (isPdf) Icons.Rounded.PictureAsPdf else Icons.Rounded.AttachFile, contentDescription = null, tint = BillBlue)
        Spacer(Modifier.width(8.dp))
        Text(attachment.name.ifBlank { "查看付款凭据" }, color = BillBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (imagePreview) {
        AlertDialog(
            onDismissRequest = { imagePreview = false },
            confirmButton = { TextButton(onClick = { imagePreview = false }) { Text("关闭") } },
            text = {
                if (imageFailed) {
                    Text("图片加载失败，请关闭后重新进入账单详情重试")
                } else {
                    AsyncImage(
                        model = attachment.url,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                        contentScale = ContentScale.Fit,
                        onError = { imageFailed = true },
                    )
                }
            },
        )
    }
}

@Composable
private fun BillCard(
    containerColor: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, BillStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun BillStatusPill(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun BillNotice(text: String, color: Color, action: @Composable (() -> Unit)? = null) {
    Surface(modifier = Modifier.fillMaxWidth(), color = color.copy(alpha = 0.09f), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = color, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
private fun BillLoading(text: String) = BillCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = BillMuted)
    }
}

@Composable
private fun BillEmpty(text: String) = BillCard {
    Text(text, color = BillMuted, modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
}

private fun paymentBillStatusText(status: String): String = when (status.lowercase()) {
    "draft" -> "草稿"
    "submitted", "approved", "partial_paid", "credit_pending_repayment" -> "待付款"
    "paid" -> "已付款"
    "rejected" -> "已驳回"
    "voided" -> "已作废"
    else -> status.ifBlank { "未知" }
}

private fun paymentBillStatusColor(status: String): Color = when (status.lowercase()) {
    "paid" -> BillSuccess
    "rejected", "voided" -> BillDanger
    "draft", "submitted", "credit_pending_repayment" -> BillWarning
    else -> BillBlue
}

private val pendingPaymentStatuses = setOf("submitted", "approved", "partial_paid", "credit_pending_repayment")

private fun money(currency: String, amount: Double): String {
    val code = currency.ifBlank { "CNY" }.uppercase()
    val symbol = if (code == "CNY") "¥" else "$code "
    return "$symbol${String.format(Locale.US, "%,.2f", amount)}"
}

private fun ManufacturerPaymentInfo.displayLabel(): String {
    val typeName = when (type) {
        "bank_card" -> bankName.ifBlank { "银行卡" }
        "alipay_qr" -> "支付宝"
        "wechat_qr" -> "微信"
        "coral_payment" -> "珊瑚支付"
        "chengyishe" -> "诚意赊"
        else -> customTypeName.ifBlank { "其他付款方式" }
    }
    val tail = accountNo.takeLast(4).takeIf(String::isNotBlank)?.let { " · 尾号 $it" }.orEmpty()
    val owner = accountName.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    return "$typeName$owner$tail"
}

private fun String.displayDateTime(): String = replace('T', ' ').take(19)
