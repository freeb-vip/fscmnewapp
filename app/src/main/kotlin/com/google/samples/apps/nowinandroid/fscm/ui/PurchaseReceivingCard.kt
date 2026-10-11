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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.data.util.NetworkMonitor
import com.google.samples.apps.nowinandroid.fscm.data.AutoPurchaseReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.AutoReceiptScanSnapshot
import com.google.samples.apps.nowinandroid.fscm.data.AutoReceiptSubmissionEntity
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundBatchEntity
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseReceivingState
import com.google.samples.apps.nowinandroid.fscm.data.ReceiptAttempt
import com.google.samples.apps.nowinandroid.fscm.data.ReceiptLineDetail
import com.google.samples.apps.nowinandroid.fscm.data.ReceiptProductProgress
import com.google.samples.apps.nowinandroid.fscm.data.ReceiptRecord
import com.google.samples.apps.nowinandroid.fscm.data.SessionStore
import com.google.samples.apps.nowinandroid.fscm.data.receiptTaskActive
import com.google.samples.apps.nowinandroid.fscm.data.receiptTaskRetryable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareAlertDialog as AlertDialog

data class PurchaseReceivingUiState(
    val data: PurchaseReceivingState? = null,
    val crossRecord: ReceiptRecord? = null,
    val attemptJob: String = "",
    val attempts: List<ReceiptAttempt> = emptyList(),
    val attemptTotal: Long = 0,
    val attemptPage: Int = 0,
    val local: List<AutoReceiptSubmissionEntity> = emptyList(),
    val scans: List<AutoReceiptScanSnapshot> = emptyList(),
    val records: List<ReceiptRecord> = emptyList(),
    val history: List<LocalInboundBatchEntity> = emptyList(),
    val historyOpen: Boolean = false,
    val detailsOpen: Boolean = false,
    val selectedTab: Int = 0,
    val page: Int = 0,
    val total: Long = 0,
    val busy: Boolean = false,
    val recordsLoading: Boolean = false,
    val canReceive: Boolean = false,
    val offline: Boolean = false,
    val message: String = "",
) {
    val active: Boolean get() = local.any { receiptTaskActive(it.status) } || data?.jobs.orEmpty().any { receiptTaskActive(it.status) }
}

@HiltViewModel
class PurchaseReceivingViewModel @Inject constructor(
    private val repository: PurchaseReceivingRepository,
    private val automatic: AutoPurchaseReceivingRepository,
    private val sessionStore: SessionStore,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {
    private val mutable = MutableStateFlow(PurchaseReceivingUiState())
    val state = mutable.asStateFlow()

    private var networkOnline = true

    suspend fun follow(id: Long) {
        val original = sessionStore.current()
        combine(sessionStore.session.map { Triple(it.user?.id, it.currentNamespace?.id, it.isOffline) }.distinctUntilChanged(), networkMonitor.isOnline.distinctUntilChanged()) { scope, online -> scope to online }
            .collectLatest { (scope, online) ->
                networkOnline = online
                if (scope.first != original.user?.id || scope.second != original.currentNamespace?.id) {
                    mutable.value = PurchaseReceivingUiState(message = "账号或工作空间已切换，请重新进入当前作业")
                    return@collectLatest
                }
                combine(automatic.observe(id).distinctUntilChanged(), automatic.observeScans(id).distinctUntilChanged()) { rows, scans -> rows to scans }
                    .collectLatest { (rows, scans) ->
                        mutable.update { it.copy(local = rows, scans = scans) }
                        load(id)
                        while (mutable.value.active && !mutable.value.offline) {
                            delay(5000)
                            load(id)
                        }
                    }
            }
    }

    fun confirmScan(id: String) = viewModelScope.launch {
        runCatching { automatic.confirmSavedLocation(id) }.onFailure { error ->
            if (error is CancellationException) throw error
            mutable.update { it.copy(message = error.message.orEmpty()) }
        }
    }

    private suspend fun load(id: Long) {
        val offline = sessionStore.current().isOffline || !networkOnline
        mutable.update { it.copy(canReceive = repository.canReceive(), offline = offline) }
        runCatching {
            if (!offline) {
                repository.resumePending()
                automatic.sync()
            }
            automatic.state(id, offline)
        }.onSuccess { data -> mutable.update { it.copy(data = data, message = "", offline = offline || data.cached, canReceive = it.canReceive && !data.cached) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.message.orEmpty()) }
            }
        if (mutable.value.detailsOpen && mutable.value.selectedTab == 1 && !offline) loadRecords(id, reset = true, refresh = true)
    }

    fun openDetails(id: Long, tab: Int = 0) {
        mutable.update { it.copy(detailsOpen = true, selectedTab = tab) }
        if (tab == 1) viewModelScope.launch { loadRecords(id, reset = true) }
    }

    fun showCrossRecord(jobId: String) = viewModelScope.launch {
        runCatching { automatic.record(jobId) }
            .onSuccess { record -> mutable.update { it.copy(crossRecord = record) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.message.orEmpty()) }
            }
    }

    fun showAttempts(job: String, more: Boolean = false) = viewModelScope.launch {
        val page = if (more) mutable.value.attemptPage + 1 else 1
        runCatching { automatic.attempts(job, page) }.onSuccess { result ->
            mutable.update { it.copy(attemptJob = job, attempts = if (more) (it.attempts + result.items).distinctBy { row -> row.id } else result.items, attemptPage = result.page, attemptTotal = result.total) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            mutable.update { it.copy(message = error.message.orEmpty()) }
        }
    }
    fun closeAttempts() = mutable.update { it.copy(attemptJob = "") }

    fun closeCrossRecord() = mutable.update { it.copy(crossRecord = null) }

    fun closeDetails() = mutable.update { it.copy(detailsOpen = false) }

    fun nextPage(id: Long) = viewModelScope.launch { loadRecords(id, reset = false) }

    private suspend fun loadRecords(id: Long, reset: Boolean, refresh: Boolean = false) {
        if (mutable.value.recordsLoading) return
        if (mutable.value.offline) {
            mutable.update { it.copy(records = it.data?.recentReceipts.orEmpty(), total = it.data?.recentReceipts?.size?.toLong() ?: 0) }
            return
        }
        val page = if (refresh) {
            mutable.value.page.coerceAtLeast(1)
        } else if (reset) {
            1
        } else {
            mutable.value.page + 1
        }
        mutable.update { it.copy(recordsLoading = true) }
        try {
            runCatching {
                if (refresh) {
                    val pages = (1..page).map { automatic.records(id, it) }
                    pages.last().copy(items = pages.flatMap { it.items }.distinctBy { it.id })
                } else {
                    automatic.records(id, page)
                }
            }
                .onSuccess { result -> mutable.update { it.copy(records = if (reset) result.items else (it.records + result.items).distinctBy { row -> row.id }, page = result.page, total = result.total) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(message = error.message.orEmpty()) }
                }
        } finally {
            mutable.update { it.copy(recordsLoading = false) }
        }
    }

    fun retry(id: Long, jobId: String) = viewModelScope.launch {
        mutable.update { it.copy(busy = true) }
        runCatching { automatic.retry(jobId) }.onFailure { error ->
            if (error is CancellationException) throw error
            mutable.update { it.copy(message = error.message.orEmpty()) }
        }
        mutable.update { it.copy(busy = false) }
        load(id)
    }

    fun showHistory(id: Long) = viewModelScope.launch {
        runCatching { automatic.history(id) }
            .onSuccess { history -> mutable.update { it.copy(history = history, historyOpen = true) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.message.orEmpty()) }
            }
    }

    fun closeHistory() = mutable.update { it.copy(historyOpen = false) }

    fun submitHistory(id: Long, batchId: String) = viewModelScope.launch {
        mutable.update { it.copy(busy = true) }
        runCatching { automatic.queueHistory(batchId) }
            .onSuccess { mutable.update { it.copy(historyOpen = false) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.message.orEmpty()) }
            }
        mutable.update { it.copy(busy = false) }
        load(id)
    }
}

internal fun purchaseReceiptStatus(status: String): String = when (status) {
    "pending" -> "待上传提交"
    "submitting" -> "正在提交"
    "queued" -> "等待后台校验"
    "waiting_purchase" -> "等待采购就绪，将自动继续"
    "running" -> "入库处理中"
    "succeeded" -> "采购入库成功"
    "partial_failed" -> "部分未入库"
    "result_unknown" -> "结果待核实"
    "permission_denied" -> "缺少采购入库权限"
    "blocked_auth" -> "等待恢复平台登录"
    "blocked_config" -> "配置需处理"
    "blocked_data", "validation_failed" -> "校验失败"
    "failed", "cancelled" -> "入库失败"
    else -> "待入库"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseReceivingCard(sessionId: Long) {
    val viewModel: PurchaseReceivingViewModel = hiltViewModel(key = "purchase-receiving-$sessionId")
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(sessionId, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.follow(sessionId) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("采购入库", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.openDetails(sessionId) }) { Text("详情") }
            }
            val summary = state.data?.receiptSummary
            if (summary != null && state.data?.enabled == true) {
                Text("已入库 ${summary.receivedQuantity} · 未入库 ${summary.pendingQuantity} · 其中失败 ${summary.failedQuantity}")
                Text("数量单位：基础单位；完成库位批次后自动提交", style = MaterialTheme.typography.bodySmall)
            } else if (state.data == null) {
                Text("正在加载采购入库状态…")
            } else {
                Text("尚未关联采购单，请在集运订单详情完成采购关联。")
            }
            if (state.offline) Text("离线缓存 · ${state.data?.snapshotAt.orEmpty().receiptTime()}", style = MaterialTheme.typography.bodySmall)
            if (!state.canReceive && !state.offline) Text("当前账号仅可采集扫码数据，采购入库需要独立权限。")
            if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error)
            val recent = state.data?.recentReceipts.orEmpty()
            recent.forEach { record ->
                Column(Modifier.fillMaxWidth().clickable { viewModel.openDetails(sessionId, 1) }.padding(vertical = 6.dp)) {
                    ReceiptRecordSummary(record)
                }
            }
            state.local.filter { row -> row.jobId.isBlank() || row.sessionId != sessionId }.take(3).forEach { row ->
                Text("${purchaseReceiptStatus(row.status)} · 批次 ${row.batchId.take(8)}")
                if (row.sessionId != sessionId) TextButton(onClick = { viewModel.showCrossRecord(row.jobId) }, enabled = row.jobId.isNotBlank() && !state.offline) { Text("查看原订单入库详情（作业 ${row.sessionId}）") }
                if (row.errorMessage.isNotBlank()) Text(row.errorMessage, color = if (row.status == "waiting_purchase") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (row.jobId.isNotBlank() && receiptTaskRetryable(row.status)) TextButton(onClick = { viewModel.retry(sessionId, row.jobId) }, enabled = state.canReceive && !state.busy) { Text("重试失败部分") }
            }
            state.scans.filter { it.errorMessage.isNotBlank() }.forEach { scan ->
                Text("批次 ${scan.batchId.take(8)}：${scan.errorMessage}", color = MaterialTheme.colorScheme.error)
                if (!scan.crossOrder && scan.scanJson.isNotBlank()) {
                    TextButton(onClick = { viewModel.confirmScan(scan.id) }, enabled = !state.offline) { Text("确认原库位并恢复") }
                } else {
                    Text("原始信息不足，请在原订单核实扫描记录。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (recent.isEmpty() && state.local.isEmpty()) Text("暂无采购入库记录", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.showHistory(sessionId) }, enabled = !state.busy && state.canReceive) { Text("选择历史批次补交") }
        }
    }
    if (state.detailsOpen) {
        var query by rememberSaveable { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = viewModel::closeDetails) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("采购入库详情", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.selectedTab == 0, onClick = { viewModel.openDetails(sessionId, 0) }, label = { Text("产品进度") })
                    FilterChip(selected = state.selectedTab == 1, onClick = { viewModel.openDetails(sessionId, 1) }, label = { Text("入库记录") })
                }
                Text("未入库包含失败、处理中和待核实；扫码同步不代表采购入库成功。", style = MaterialTheme.typography.bodySmall)
                if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error)
                if (state.selectedTab == 0) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("产品名称 / 编码 / SKU") })
                    val products = state.data?.products.orEmpty().filter { product -> (listOf(product.productCode, product.productName) + product.skuCodes).any { it.contains(query.trim(), ignoreCase = true) } }
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(products, key = { it.productId }) { product -> ReceiptProductRow(product) }
                        if (products.isEmpty()) item { Text("暂无匹配产品", Modifier.padding(vertical = 16.dp)) }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(state.records, key = { it.id }) { record ->
                            ReceiptRecordRow(record, state.canReceive && !state.busy, onAttempts = { viewModel.showAttempts(record.id) }, onRetry = { viewModel.retry(sessionId, it) })
                        }
                        if (state.records.isEmpty()) item { Text("暂无入库记录", Modifier.padding(vertical = 16.dp)) }
                        if (state.records.size < state.total) item { TextButton(onClick = { viewModel.nextPage(sessionId) }, enabled = !state.recordsLoading) { Text(if (state.recordsLoading) "加载中…" else "加载更多") } }
                    }
                }
            }
        }
    }
    state.crossRecord?.let { record ->
        ModalBottomSheet(onDismissRequest = viewModel::closeCrossRecord) {
            LazyColumn(Modifier.padding(horizontal = 16.dp).heightIn(max = 520.dp)) {
                item { Text("原订单采购入库详情", style = MaterialTheme.typography.titleLarge) }
                item { ReceiptRecordSummary(record) }
                items(record.items, key = { "${it.boxPlanId}:${it.sku}" }) { ReceiptLineRow(it) }
                items(record.issues) { Text(it.message, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (state.attemptJob.isNotBlank()) {
        ModalBottomSheet(onDismissRequest = viewModel::closeAttempts) {
            LazyColumn(Modifier.padding(16.dp).heightIn(max = 520.dp)) {
                item { Text("历史处理记录", style = MaterialTheme.typography.titleLarge) }
                items(state.attempts, key = { it.id }) { attempt ->
                    Text("${attempt.createdAt.receiptTime()} · ${purchaseReceiptStatus(attempt.status)}", Modifier.padding(top = 10.dp))
                    if (attempt.reason().isNotBlank()) Text(attempt.reason(), style = MaterialTheme.typography.bodySmall)
                }
                if (state.attempts.size < state.attemptTotal) item { TextButton(onClick = { viewModel.showAttempts(state.attemptJob, true) }) { Text("加载更多") } }
            }
        }
    }
    if (state.historyOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeHistory,
            title = { Text("选择历史批次补交") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { Text("仅提交选定批次已同步的箱唛；后台校验后自动入库。已成功数量不会重复提交。") }
                    items(state.history, key = { it.id }) { batch ->
                        TextButton(onClick = { viewModel.submitHistory(sessionId, batch.id) }, enabled = !state.busy) { Text("补交 ${batch.locationCode} · ${batch.id.take(8)}") }
                    }
                    if (state.history.isEmpty()) item { Text("没有可补交的历史批次") }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeHistory) { Text("关闭") } },
        )
    }
}

@Composable
private fun ReceiptProductRow(product: ReceiptProductProgress) {
    var expanded by rememberSaveable(product.productId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${product.productCode} ${product.productName}", style = MaterialTheme.typography.titleSmall)
        Text("应入库 ${product.totalQuantity} · 已入库 ${product.receivedQuantity} · 未入库 ${product.pendingQuantity}")
        Text("${product.totalBoxCount} 箱 · 已完成 ${product.receivedBoxCount} 箱 · 未完成 ${product.pendingBoxCount} 箱", style = MaterialTheme.typography.bodySmall)
        Text("其中失败 ${product.failedQuantity}（${product.failedBoxCount} 箱） · 处理中 ${product.processingQuantity} · 待核实 ${product.unknownQuantity}", color = if (product.failedQuantity > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (product.virtualQuantity > 0 || product.pendingMoveQuantity > 0) Text("虚拟入库 ${product.virtualQuantity} · 待移货 ${product.pendingMoveQuantity}", style = MaterialTheme.typography.bodySmall)
        Text(if (expanded) "收起箱唛 / SKU" else "展开箱唛 / SKU", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        if (expanded) product.items.forEach { ReceiptLineRow(it) }
    }
    HorizontalDivider()
}

@Composable
private fun ReceiptRecordSummary(record: ReceiptRecord) {
    Text("${record.createdAt.receiptTime()} · ${record.waitingReason?.message ?: purchaseReceiptStatus(record.status)}", style = MaterialTheme.typography.titleSmall)
    Text("采购单 ${record.externalNumbers.joinToString().ifBlank { "待关联" }} · 库位 ${record.locationCodes.joinToString().ifBlank { "待校验" }}", style = MaterialTheme.typography.bodySmall)
    Text("成功 ${record.receivedQuantity} · 处理中 ${record.processingQuantity} · 失败 ${record.failedQuantity} · 待核实 ${record.unknownQuantity}", style = MaterialTheme.typography.bodySmall)
    if (record.errorMessage.isNotBlank() && record.waitingReason == null) Text(record.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReceiptRecordRow(record: ReceiptRecord, canRetry: Boolean, onAttempts: () -> Unit, onRetry: (String) -> Unit) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) { ReceiptRecordSummary(record) }
        if (record.status == "result_unknown") Text("请管理员核实原任务，不能重复提交。", style = MaterialTheme.typography.bodySmall)
        if (record.retryJobCount > 0) Text("原任务失败部分：${record.retryJobCount} 个任务，涉及 ${record.retryBoxCount} 箱（可能关联其他批次）", style = MaterialTheme.typography.bodySmall)
        if (receiptTaskRetryable(record.status)) TextButton(onClick = { onRetry(record.id) }, enabled = canRetry) { Text("重试原任务失败部分") }
        if (expanded) {
            if (record.lastAttemptError.isNotBlank() && record.lastAttemptError != record.errorMessage) Text("历史尝试：${record.lastAttemptError}", style = MaterialTheme.typography.bodySmall)
            record.attemptIssues.filter { it !in record.issues }.forEach { Text("历史校验：${it.labelCode} ${it.message}", style = MaterialTheme.typography.bodySmall) }
            record.issues.forEach { Text("${it.labelCode.ifBlank { it.boxPlanId.toString() }}：${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            record.items.forEach { ReceiptLineRow(it) }
            TextButton(onClick = onAttempts) { Text("查看历史尝试") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ReceiptLineRow(line: ReceiptLineDetail) {
    Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
        Text("${line.labelCode} · ${line.sku} × ${line.quantity}", style = MaterialTheme.typography.bodySmall)
        Text("${purchaseReceiptStatus(line.status)} · ${line.locationCode} · ${line.externalNumber}", style = MaterialTheme.typography.bodySmall)
        if (line.errorMessage.isNotBlank()) Text(line.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun String.receiptTime(): String = runCatching {
    Instant.parse(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}.getOrElse { replace('T', ' ').take(19).ifBlank { "尚未同步" } }
