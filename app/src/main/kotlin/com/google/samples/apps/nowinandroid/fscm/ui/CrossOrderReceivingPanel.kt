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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.fscm.data.CrossOrderReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.CrossOrderScanEntity
import com.google.samples.apps.nowinandroid.fscm.data.CrossOrderScanPending
import com.google.samples.apps.nowinandroid.fscm.data.confirmation
import com.google.samples.apps.nowinandroid.fscm.data.crossText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareAlertDialog as AlertDialog

class CrossOrderReceivingController(private val scope: CoroutineScope, private val repository: CrossOrderReceivingRepository, private val feedback: com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback) {
    val tasks = MutableStateFlow<List<CrossOrderScanEntity>>(emptyList())
    val selected = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var observation: Job? = null
    val isBlocking get() = selected.value != null || busy.value

    fun load(sourceId: Long, refreshRemote: Boolean = true) {
        observation?.cancel()
        selected.value = null
        observation = scope.launch {
            var initialized = false
            repository.observe(sourceId).collect { rows ->
                if (initialized && rows.any { row -> row.state == "completed" && tasks.value.any { it.id == row.id && it.state != "completed" } }) feedback.success()
                tasks.value = rows
                initialized = true
            }
        }
        if (refreshRemote) {
            scope.launch {
                runCatching { repository.prepare(sourceId) }
                runCatching { repository.sync() }.onFailure { error.value = it.message }
            }
        }
    }

    fun handle(failure: Throwable): Boolean {
        if (failure !is CrossOrderScanPending) return false
        feedback.failure()
        if (failure.task.state == "awaiting_confirmation") selected.value = failure.task.id
        return true
    }

    fun dismiss() {
        if (!busy.value) selected.value = null
    }
    fun open(id: String) {
        if (!busy.value) selected.value = id
    }
    fun confirm(id: String) = scope.launch {
        if (busy.value) return@launch
        busy.value = true
        runCatching { repository.confirm(id) }.onFailure { error.value = it.message }
        busy.value = false
        selected.value = null
    }
    fun retry(id: String) = scope.launch {
        if (busy.value) return@launch
        busy.value = true
        runCatching { repository.retry(id) }.onFailure { error.value = it.message }
        busy.value = false
    }
}

@Composable
fun CrossOrderReceivingPanel(controller: CrossOrderReceivingController) {
    val tasks by controller.tasks.collectAsStateWithLifecycle()
    val selected by controller.selected.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    var listOpen by remember { mutableStateOf(false) }
    if (tasks.isNotEmpty() || error != null) {
        Column {
            error?.let { Text(it) }
            TextButton(onClick = { listOpen = true }) {
                Text("跨单／待识别扫描 ${tasks.count { it.state != "completed" }} 条待处理")
            }
            tasks.firstOrNull()?.let { Text(it.message) }
        }
    }
    if (listOpen) {
        AlertDialog(
            onDismissRequest = { listOpen = false },
            title = { Text("扫描记录（保存不代表已收货）") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        Column {
                            Text(task.rawCode)
                            Text(task.message)
                            if (task.state == "awaiting_confirmation") {
                                TextButton(enabled = !busy, onClick = {
                                    listOpen = false
                                    controller.open(task.id)
                                }) { Text("继续确认") }
                            } else if (task.state != "completed") {
                                TextButton(enabled = !busy, onClick = { controller.retry(task.id) }) { Text(if (task.state == "needs_identification") "重新识别并处理" else "重试") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listOpen = false }) { Text("关闭") } },
        )
    }
    val task = tasks.firstOrNull { it.id == selected }
    val confirmation = task?.confirmation()
    if (task != null && confirmation != null) {
        AlertDialog(
            modifier = Modifier.onPreviewKeyEvent { it.key == Key.Enter || it.key == Key.NumPadEnter },
            onDismissRequest = controller::dismiss,
            title = { Text("确认跨订单收货") },
            text = {
                Column {
                    Text("当前订单：${confirmation.crossText("source_order_code")}")
                    Text("此箱属于：${confirmation.crossText("target_order_code")}")
                    Text("确认后计入箱唛原订单，仓库：${confirmation.crossText("warehouse_name")}")
                    Text("箱唛：${confirmation.crossText("label_code")}")
                    Text("SKU：${confirmation.crossText("sku_code")}，数量：${confirmation.crossText("quantity")}")
                    if (task.operation == "inbound") Text("库位：${confirmation.crossText("location_code")}")
                    Text("离线确认仅保存待同步，联网重新校验通过后才完成收货。")
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { controller.confirm(task.id) }) { Text(if (task.operation == "inbound") "为原订单收货并入库" else "为原订单收货") } },
            dismissButton = { TextButton(enabled = !busy, onClick = controller::dismiss) { Text("暂不处理") } },
        )
    }
}
