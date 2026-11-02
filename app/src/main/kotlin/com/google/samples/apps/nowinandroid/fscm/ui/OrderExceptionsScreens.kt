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
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionFile
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionOperation
import kotlinx.coroutines.CancellationException

internal fun exceptionProblemLabel(code: String): String = when (code) {
    "operation" -> "作业问题"
    "courier" -> "快递问题"
    "process" -> "流程问题"
    "person" -> "作业人员"
    else -> "其它"
}

private fun WarehouseExceptionOperation.displayName() = name.ifBlank { operator }.ifBlank { "未记录" }
private fun WarehouseExceptionOperation.stageName() = operation.ifBlank {
    when (code.uppercase()) {
        "PICKING" -> "拣货"
        "PACKING" -> "打包"
        "INSPECTION" -> "验货"
        else -> code
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderExceptionsListScreen(scopeKey: String, onOpen: (Long) -> Unit) {
    val viewModel: OrderExceptionsListViewModel = hiltViewModel(key = "exceptions-$scopeKey")
    val state by viewModel.state.collectAsStateWithLifecycle()
    var order by rememberSaveable(scopeKey) { mutableStateOf("") }
    var tracking by rememberSaveable(scopeKey) { mutableStateOf("") }
    var sku by rememberSaveable(scopeKey) { mutableStateOf("") }
    var employee by rememberSaveable(scopeKey) { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    state.grants?.let { options ->
        ScanAwareAlertDialog(
            onDismissRequest = viewModel::closeGrants,
            title = { Text("异常订单仓库授权") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (state.grantUser == null) {
                        item { Text("选择员工，再设置其可访问的发货仓") }
                        items(options.members, key = { it.id }) { member -> TextButton(onClick = { viewModel.grantOptions(member.id) }, enabled = !state.grantBusy) { Text("${member.name} · ${member.code}") } }
                    } else {
                        item { Text(options.members.firstOrNull { it.id == state.grantUser }?.name.orEmpty()) }
                        if (options.warehouses.isEmpty()) item { Text("暂无已识别的自有发货仓，请等待订单仓库资料同步") }
                        items(options.warehouses.distinctBy { it.key }, key = { it.key }) { warehouse ->
                            Row {
                                Checkbox(checked = warehouse.key in state.selectedGrants, enabled = !state.grantBusy, onCheckedChange = { viewModel.toggleGrant(warehouse.key, it) })
                                Text("${warehouse.sourceName} · ${warehouse.name}", Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { if (state.grantUser != null) TextButton(onClick = viewModel::saveGrants, enabled = !state.grantBusy) { Text("保存授权") } },
            dismissButton = { TextButton(onClick = viewModel::closeGrants, enabled = !state.grantBusy) { Text("关闭") } },
        )
    }
    Column(Modifier.fillMaxSize().background(FscmPageTop)) {
        PrimaryTabRow(selectedTabIndex = if (state.resolved) 1 else 0) {
            Tab(selected = !state.resolved, onClick = { viewModel.select(false) }, text = { Text("待处理 (${state.open})") })
            Tab(selected = state.resolved, onClick = { viewModel.select(true) }, text = { Text("已处理 (${state.completed})") })
        }
        TextButton(onClick = { filtersOpen = !filtersOpen }) { Text(if (filtersOpen) "收起查询条件" else "按订单号、运单号、SKU、工号查询") }
        if (state.canManage) TextButton(onClick = { viewModel.grantOptions() }, enabled = !state.grantBusy) { Text("仓库授权") }
        if (filtersOpen) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(order, { order = it }, label = { Text("订单号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tracking, { tracking = it }, label = { Text("运单号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sku, { sku = it }, label = { Text("SKU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(employee, { employee = it }, label = { Text("员工工号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.search(mapOf("order_no" to order, "tracking_no" to tracking, "sku" to sku, "employee_code" to employee))
                        filtersOpen = false
                    }) { Text("查询") }
                    TextButton(onClick = {
                        order = ""
                        tracking = ""
                        sku = ""
                        employee = ""
                        viewModel.search(emptyMap())
                    }) { Text("清空") }
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                    }
                }
                if (!state.loading && state.error == null && state.rows.isEmpty()) item { Text("暂无符合条件的异常订单", Modifier.padding(16.dp)) }
                items(state.rows, key = { it.id }) { report ->
                    Card(onClick = { onOpen(report.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(report.order.number, fontWeight = FontWeight.Bold)
                            Text(report.types.joinToString("、") { it.name }, color = MaterialTheme.colorScheme.primary)
                            Text("运单：${report.order.tracking.joinToString("、").ifBlank { "未记录" }}")
                            Text("发货仓：${report.warehouse.name.ifBlank { "未记录" }}")
                            Text("登记：${report.createdAt}")
                            Text(if (report.status == "resolved") "已处理" else "待处理")
                        }
                    }
                }
                if (state.rows.size < state.total) item { OutlinedButton(onClick = { viewModel.refresh(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            }
        }
    }
}

@Composable
private fun ExceptionPhotos(files: List<WarehouseExceptionFile>, viewModel: OrderExceptionDetailViewModel) {
    files.forEach { file ->
        var bytes by remember(file.key) { mutableStateOf<ByteArray?>(null) }
        var failed by remember(file.key) { mutableStateOf(false) }
        var attempt by remember(file.key) { mutableStateOf(0) }
        LaunchedEffect(file.key, attempt) {
            failed = false
            try {
                bytes = viewModel.photo(file.key)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
        }
        if (failed) TextButton(onClick = { attempt++ }) { Text("图片加载失败，点击重试") }
        bytes?.let { AsyncImage(model = it, contentDescription = file.name, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
    }
}

@Composable
fun OrderExceptionDetailScreen(id: Long, scopeKey: String) {
    val viewModel: OrderExceptionDetailViewModel = hiltViewModel(key = "exception-$scopeKey-$id")
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cameraUri by rememberSaveable(id, scopeKey) { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri?.let(Uri::parse)
        if (success && uri != null) viewModel.upload(uri)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraUri?.let { camera.launch(Uri.parse(it)) } else cameraError = "请允许相机权限后拍照，也可以从相册选择图片"
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.upload(uri) }
    var handling by rememberSaveable(id, scopeKey) { mutableStateOf(false) }
    LaunchedEffect(id, scopeKey) { viewModel.load(id) }
    val detail = state.detail
    val editable = !state.busy && !state.pending
    LazyColumn(Modifier.fillMaxSize().background(FscmPageTop), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cameraError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (!state.pending) TextButton(onClick = { viewModel.load(id) }, enabled = !state.busy) { Text(if (detail == null) "重试" else "重新加载详情") }
            }
        }
        if (detail != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(detail.order.number, style = MaterialTheme.typography.titleLarge)
                        Text(if (detail.status == "resolved") "已处理" else "待处理", color = MaterialTheme.colorScheme.primary)
                        Text("发货仓：${detail.warehouse.name.ifBlank { "未记录" }}")
                        Text("运单号：${detail.order.tracking.joinToString("、").ifBlank { "未记录" }}")
                        Text("SKU：${detail.skus.joinToString("、").ifBlank { "未记录" }}")
                        Text("异常类型：${detail.types.joinToString("、") { it.name }}")
                        if (detail.description.isNotBlank()) Text(detail.description)
                        detail.ticket?.let {
                            Text(
                                "关联工单：${it.number} · ${when (it.status) {
                                    "open" -> "待处理"
                                    "in_progress" -> "处理中"
                                    "pending" -> "挂起"
                                    "resolved" -> "已解决"
                                    "closed" -> "已关闭"
                                    else -> it.status
                                }}",
                            )
                        }
                        ExceptionPhotos(detail.attachments, viewModel)
                    }
                }
            }
            item { Text("订单作业人员", style = MaterialTheme.typography.titleMedium) }
            items(detail.operations, key = { it.id }) { operation ->
                Text("${operation.displayName()} · ${operation.employeeCode.ifBlank { "工号未记录" }}\n${operation.stageName()} · ${operation.occurredAt ?: "${operation.date}（操作时间未记录）"}")
            }
            detail.resolutions.forEach { result ->
                item(key = "resolution-${result.id}") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("处理结果 · ${exceptionProblemLabel(result.problemType)}", fontWeight = FontWeight.Bold)
                            Text("责任归属：${if (result.responsibilityType == "person") result.participants.joinToString("、") { it.displayName() } else result.responsibilityNote}")
                            if (result.measures.isNotBlank()) Text("整改措施：${result.measures}")
                            Text("处理人：${result.actor} · ${result.createdAt}")
                            ExceptionPhotos(result.attachments, viewModel)
                        }
                    }
                }
            }
            if (detail.resolutions.isEmpty() && detail.handlingNote.isNotBlank()) item { Text("历史处理说明：${detail.handlingNote}") }
            if (detail.status == "open" && detail.canResolve) {
                if (!handling && !state.pending) item { Button(onClick = { handling = true }, modifier = Modifier.fillMaxWidth()) { Text("处理异常") } }
                if (handling || state.pending) {
                    item { Text("问题类型（必填）", fontWeight = FontWeight.Bold) }
                    items(listOf("operation", "courier", "process", "other")) { code ->
                        Row {
                            RadioButton(selected = state.form.problemType == code, enabled = editable, onClick = { viewModel.edit { it.copy(problemType = code) } })
                            Text(exceptionProblemLabel(code), Modifier.padding(top = 12.dp))
                        }
                    }
                    item { Text("责任归属（必填）", fontWeight = FontWeight.Bold) }
                    items(listOf("person", "courier", "process", "other")) { code ->
                        Row {
                            RadioButton(selected = state.form.responsibilityType == code, enabled = editable, onClick = { viewModel.edit { it.copy(responsibilityType = code, participantIds = emptyList(), responsibilityNote = "") } })
                            Text(if (code == "process") "流程／部门" else exceptionProblemLabel(code), Modifier.padding(top = 12.dp))
                        }
                    }
                    if (state.form.responsibilityType == "person") {
                        items(detail.operations, key = { "choose-${it.id}" }) { person ->
                            Row {
                                Checkbox(checked = person.id in state.form.participantIds, enabled = editable, onCheckedChange = { checked -> viewModel.edit { it.copy(participantIds = if (checked) it.participantIds + person.id else it.participantIds - person.id) } })
                                Text("${person.displayName()} · ${person.stageName()}", Modifier.padding(top = 12.dp))
                            }
                        }
                    } else {
                        item { OutlinedTextField(state.form.responsibilityNote, { text -> if (text.length <= 500) viewModel.edit { it.copy(responsibilityNote = text) } }, enabled = editable, label = { Text("责任对象或说明（必填）") }, modifier = Modifier.fillMaxWidth()) }
                    }
                    item { OutlinedTextField(state.form.measures, { text -> if (text.length <= 2000) viewModel.edit { it.copy(measures = text) } }, enabled = editable, label = { Text("整改措施（可选，最多2000字）") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                    item {
                        Text("整改照片（可选，最多9张）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = editable && state.form.attachmentKeys.size < 9, onClick = {
                                val file = viewModel.cameraFile()
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                cameraUri = uri.toString()
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(uri) else permission.launch(Manifest.permission.CAMERA)
                            }) { Text("拍照") }
                            OutlinedButton(enabled = editable && state.form.attachmentKeys.size < 9, onClick = { gallery.launch("image/*") }) { Text("相册") }
                        }
                    }
                    items(state.form.attachmentKeys, key = { "draft-$it" }) { key ->
                        Row {
                            Text("已上传照片 ${state.form.attachmentKeys.indexOf(key) + 1}", Modifier.weight(1f))
                            TextButton(enabled = editable, onClick = { viewModel.edit { it.copy(attachmentKeys = it.attachmentKeys - key) } }) { Text("移除") }
                        }
                    }
                    state.failedUpload?.let { uri ->
                        item {
                            Row {
                                TextButton(onClick = { viewModel.upload(uri) }, enabled = !state.busy) { Text("重试上传") }
                                TextButton(onClick = viewModel::removeFailedUpload, enabled = !state.busy) { Text("移除失败图片") }
                            }
                        }
                    }
                    if (detail.pendingCollaborators.isNotEmpty()) {
                        item {
                            Text("关联工单有 ${detail.pendingCollaborators.size} 名未完成协作人员。提交后将终止这些协作并关闭工单。")
                            Text(detail.pendingCollaborators.joinToString("、") { it.user?.let { user -> user.name.ifBlank { user.username } } ?: "未记录" })
                            Row {
                                Checkbox(checked = state.form.terminateCollaborations, enabled = editable, onCheckedChange = { checked -> viewModel.edit { it.copy(terminateCollaborations = checked) } })
                                Text("确认统一收尾", Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    item {
                        Button(onClick = viewModel::submit, enabled = !state.busy && state.failedUpload == null && (state.pending || detail.pendingCollaborators.isEmpty() || state.form.terminateCollaborations), modifier = Modifier.fillMaxWidth()) { Text(if (state.pending) "确认处理结果" else "提交处理并完结关联工单") }
                    }
                }
            }
        }
    }
}
