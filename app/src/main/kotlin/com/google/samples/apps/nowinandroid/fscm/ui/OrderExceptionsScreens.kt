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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionFile
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionOperation
import kotlinx.coroutines.CancellationException

@Composable
internal fun exceptionProblemLabel(code: String): String = stringResource(
    when (code) {
        "operation" -> R.string.oe_problem_operation
        "courier" -> R.string.oe_problem_courier
        "process" -> R.string.oe_problem_process
        else -> R.string.oe_problem_other
    },
)

@Composable
private fun WarehouseExceptionOperation.displayName() = name.ifBlank { operator }.ifBlank { stringResource(R.string.oe_not_recorded) }

@Composable
private fun WarehouseExceptionOperation.stageName() = when (code.uppercase()) {
    "PICKING" -> stringResource(R.string.oe_picking)
    "PACKING" -> stringResource(R.string.oe_packing)
    "INSPECTION" -> stringResource(R.string.oe_inspection)
    else -> operation.ifBlank { code }
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
    Column(Modifier.fillMaxSize().background(FscmPageTop)) {
        PrimaryTabRow(selectedTabIndex = if (state.resolved) 1 else 0) {
            Tab(selected = !state.resolved, onClick = { viewModel.select(false) }, text = { Text(stringResource(R.string.oe_open_count, state.open)) })
            Tab(selected = state.resolved, onClick = { viewModel.select(true) }, text = { Text(stringResource(R.string.oe_resolved_count, state.completed)) })
        }
        TextButton(onClick = { filtersOpen = !filtersOpen }) { Text(if (filtersOpen) stringResource(R.string.oe_hide_filters) else stringResource(R.string.oe_filters)) }
        if (filtersOpen) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(order, { order = it }, label = { Text(stringResource(R.string.oe_order)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tracking, { tracking = it }, label = { Text(stringResource(R.string.oe_tracking)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sku, { sku = it }, label = { Text("SKU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(employee, { employee = it }, label = { Text(stringResource(R.string.oe_employee)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.search(mapOf("order_no" to order, "tracking_no" to tracking, "sku" to sku, "employee_code" to employee))
                        filtersOpen = false
                    }) { Text(stringResource(R.string.oe_search)) }
                    TextButton(onClick = {
                        order = ""
                        tracking = ""
                        sku = ""
                        employee = ""
                        viewModel.search(emptyMap())
                    }) { Text(stringResource(R.string.oe_clear)) }
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { message ->
                    item {
                        Text(message.resolve(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.oe_retry)) }
                    }
                }
                if (!state.loading && state.error == null && state.rows.isEmpty()) item { Text(stringResource(R.string.oe_empty), Modifier.padding(16.dp)) }
                items(state.rows, key = { it.id }) { report ->
                    ExceptionOrderListCard(report) { onOpen(report.id) }
                }
                if (state.rows.size < state.total) item { OutlinedButton(onClick = { viewModel.refresh(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.oe_load_more)) } }
            }
        }
    }
}

@Composable
private fun ExceptionPhotos(files: List<WarehouseExceptionFile>, viewModel: OrderExceptionDetailViewModel) {
    files.forEach { file ->
        var bytes by remember(file.key) { mutableStateOf<ByteArray?>(null) }
        var failed by remember(file.key) { mutableStateOf(false) }
        var preview by remember(file.key) { mutableStateOf(false) }
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
        if (failed) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.oe_photo_retry)) }
        bytes?.let { image ->
            AsyncImage(model = image, contentDescription = stringResource(R.string.oe_photo_hint), modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clickable { preview = true })
            Text(stringResource(R.string.oe_photo_hint), style = MaterialTheme.typography.labelSmall)
            if (preview) ExceptionPhotoViewer(image, file.name) { preview = false }
        }
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
    var cameraError by remember { mutableStateOf<UiText?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraUri?.let { uri -> runCatching { camera.launch(Uri.parse(uri)) }.onFailure { cameraError = UiText.Resource(R.string.oe_camera_error) } } else cameraError = UiText.Resource(R.string.oe_camera_permission)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.upload(uri) }
    var handling by rememberSaveable(id, scopeKey) { mutableStateOf(false) }
    var editingMeasures by rememberSaveable(id, scopeKey) { mutableStateOf(false) }
    var confirming by rememberSaveable(id, scopeKey) { mutableStateOf(false) }
    if (editingMeasures && !state.pending) {
        ExceptionMeasuresEditor(state.form.measures, { text -> viewModel.edit { it.copy(measures = text) } }, { editingMeasures = false })
    }
    if (confirming && !state.busy && state.detail?.status == "open") {
        ExceptionCorrectionConfirmation(onConfirm = {
            confirming = false
            viewModel.submit()
        }, onDismiss = { confirming = false })
    }
    LaunchedEffect(id, scopeKey) { viewModel.load(id) }
    val detail = state.detail
    val editable = !state.busy && !state.pending
    LazyColumn(Modifier.fillMaxSize().background(FscmPageTop).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cameraError?.let { item { Text(it.resolve(), color = MaterialTheme.colorScheme.error) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message ->
            item {
                Text(message.resolve(), color = MaterialTheme.colorScheme.error)
                if (!state.pending) TextButton(onClick = { viewModel.load(id) }, enabled = !state.busy) { Text(if (detail == null) stringResource(R.string.oe_retry) else stringResource(R.string.oe_reload)) }
            }
        }
        if (detail != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(detail.order.number, style = MaterialTheme.typography.titleLarge)
                        Text(if (detail.status == "resolved") stringResource(R.string.oe_resolved) else stringResource(R.string.oe_open), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.oe_warehouse_value, detail.warehouse.name.ifBlank { stringResource(R.string.oe_not_recorded) }))
                        Text(stringResource(R.string.oe_tracking_value, detail.order.tracking.joinToString(", ").ifBlank { stringResource(R.string.oe_not_recorded) }))
                        Text("SKU：${detail.skus.joinToString("、").ifBlank { stringResource(R.string.oe_not_recorded) }}")
                        Text(stringResource(R.string.oe_types_value, detail.types.joinToString(", ") { it.name }))
                        TicketScopeRows(detail.scopeItems)
                        if (detail.description.isNotBlank()) Text(detail.description)
                        detail.ticket?.let {
                            Text(stringResource(R.string.oe_ticket_value, it.number, localizedStatusText(it.status)))
                        }
                        ExceptionPhotos(detail.attachments, viewModel)
                    }
                }
            }
            item { Text(stringResource(R.string.oe_operations), style = MaterialTheme.typography.titleMedium) }
            items(detail.operations, key = { it.id }) { operation ->
                Text("${operation.displayName()} · ${operation.employeeCode.ifBlank { stringResource(R.string.oe_employee_missing) }}\n${operation.stageName()} · ${operation.occurredAt ?: stringResource(R.string.oe_time_missing, operation.date)}")
            }
            detail.resolutions.forEach { result ->
                item(key = "resolution-${result.id}") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.oe_result, exceptionProblemLabel(result.problemType)), fontWeight = FontWeight.Bold)
                            if (result.measures.isNotBlank()) Text(stringResource(R.string.oe_measures_value, result.measures))
                            Text(stringResource(R.string.oe_actor_value, result.actor, result.createdAt))
                            ExceptionPhotos(result.attachments, viewModel)
                        }
                    }
                }
            }
            if (detail.resolutions.isEmpty() && detail.handlingNote.isNotBlank()) item { Text(stringResource(R.string.oe_history_value, detail.handlingNote)) }
            if (detail.status == "open" && detail.canResolve) {
                if (!handling && !state.pending) item { Button(onClick = { handling = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.oe_handle)) } }
                if (handling || state.pending) {
                    item { Text(stringResource(R.string.oe_problem_required), fontWeight = FontWeight.Bold) }
                    items(listOf("operation", "courier", "process", "other")) { code ->
                        Row {
                            RadioButton(selected = state.form.problemType == code, enabled = editable, onClick = { viewModel.edit { it.copy(problemType = code) } })
                            Text(exceptionProblemLabel(code), Modifier.padding(top = 12.dp))
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.oe_measures), fontWeight = FontWeight.Bold)
                            Card(onClick = { editingMeasures = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
                                Text(state.form.measures.ifBlank { stringResource(R.string.oe_measures_hint) }, Modifier.padding(16.dp), maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = { editingMeasures = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.oe_edit_measures)) }
                        }
                    }
                    item {
                        Text(stringResource(R.string.oe_photos))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = editable && state.form.attachmentKeys.size < 9, onClick = {
                                cameraError = null
                                try {
                                    val file = viewModel.cameraFile()
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    cameraUri = uri.toString()
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(uri) else permission.launch(Manifest.permission.CAMERA)
                                } catch (_: Exception) {
                                    cameraError = UiText.Resource(R.string.oe_camera_error)
                                }
                            }) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.oe_camera))
                            }
                            OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = editable && state.form.attachmentKeys.size < 9, onClick = { gallery.launch("image/*") }) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(stringResource(R.string.oe_gallery))
                            }
                        }
                    }
                    items(state.form.attachmentKeys, key = { "draft-$it" }) { key ->
                        Row {
                            Text(stringResource(R.string.oe_uploaded, state.form.attachmentKeys.indexOf(key) + 1), Modifier.weight(1f))
                            TextButton(enabled = editable, onClick = { viewModel.edit { it.copy(attachmentKeys = it.attachmentKeys - key) } }) { Text(stringResource(R.string.oe_remove)) }
                        }
                    }
                    state.failedUpload?.let { uri ->
                        item {
                            Row {
                                TextButton(onClick = { viewModel.upload(uri) }, enabled = !state.busy) { Text(stringResource(R.string.oe_retry_upload)) }
                                TextButton(onClick = viewModel::removeFailedUpload, enabled = !state.busy) { Text(stringResource(R.string.oe_remove_failed)) }
                            }
                        }
                    }
                    if (detail.pendingCollaborators.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.oe_collaboration_warning, detail.pendingCollaborators.size))
                            Text(detail.pendingCollaborators.map { it.user?.let { user -> user.name.ifBlank { user.username } } ?: stringResource(R.string.oe_not_recorded) }.joinToString(", "))
                            Row {
                                Checkbox(checked = state.form.terminateCollaborations, enabled = editable, onCheckedChange = { checked -> viewModel.edit { it.copy(terminateCollaborations = checked) } })
                                Text(stringResource(R.string.oe_collaboration_confirm), Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    item {
                        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { if (state.pending) viewModel.submit() else confirming = true }, enabled = !state.busy && state.failedUpload == null && (state.pending || detail.pendingCollaborators.isEmpty() || state.form.terminateCollaborations), modifier = Modifier.fillMaxWidth()) { Text(if (state.pending) stringResource(R.string.oe_check_result) else stringResource(R.string.oe_submit)) }
                    }
                }
            }
        }
    }
}
