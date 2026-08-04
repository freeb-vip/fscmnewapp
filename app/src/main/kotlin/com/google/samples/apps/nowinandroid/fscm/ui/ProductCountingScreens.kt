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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSuccess
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmWarning
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountEntryEntity
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountSyncStatus
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountTaskStatus
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountTaskSummary
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountUploadStatus
import com.google.samples.apps.nowinandroid.fscm.device.PdaPermissions
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmCard
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmStatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProductCountingTaskListScreen(
    onOpenTask: (String) -> Unit,
    viewModel: ProductCountingListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.openTask.collect(onOpenTask) }
    Box(Modifier.fillMaxSize().background(FscmPageTop)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ProductCountingFilters(
                    selected = state.filter,
                    activeCount = state.tasks.count { it.task.status == ProductCountTaskStatus.InProgress.value },
                    completedCount = state.tasks.count { it.task.status == ProductCountTaskStatus.Completed.value },
                    onSelected = viewModel::setFilter,
                )
            }
            state.error?.let { error ->
                item { ProductCountingNotice(error.resolve(), danger = true, onRetry = viewModel::refresh) }
            }
            if (state.loading && state.tasks.isEmpty()) {
                item { ProductCountingLoading() }
            } else if (state.visibleTasks.isEmpty()) {
                item {
                    ProductCountingEmpty(
                        stringResource(
                            if (state.filter == ProductCountingTaskFilter.InProgress) {
                                R.string.product_counting_no_active_tasks
                            } else {
                                R.string.product_counting_no_completed_tasks
                            },
                        ),
                    )
                }
            } else {
                items(state.visibleTasks, key = { it.task.clientTaskId }) { task ->
                    ProductCountingTaskCard(task, onClick = { onOpenTask(task.task.clientTaskId) })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = viewModel::createTask,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = FscmBrandMain,
            contentColor = Color.White,
            icon = {
                if (state.creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            },
            text = { Text(stringResource(R.string.product_counting_new_task)) },
        )
    }
}

@Composable
private fun ProductCountingFilters(
    selected: ProductCountingTaskFilter,
    activeCount: Int,
    completedCount: Int,
    onSelected: (ProductCountingTaskFilter) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProductCountingTaskFilter.entries.forEach { filter ->
            val count = if (filter == ProductCountingTaskFilter.InProgress) activeCount else completedCount
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = {
                    Text(
                        stringResource(
                            if (filter == ProductCountingTaskFilter.InProgress) {
                                R.string.product_counting_in_progress
                            } else {
                                R.string.product_counting_completed
                            },
                        ) + " ($count)",
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProductCountingTaskCard(task: ProductCountTaskSummary, onClick: () -> Unit) {
    FscmCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(FscmBrandSoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = FscmBrandMain)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    task.task.productName.ifBlank {
                        task.task.productCode.ifBlank { stringResource(R.string.product_counting_waiting_product) }
                    },
                    color = FscmInkStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.product_counting_task_time, formatProductCountingTime(task.task.createdAt)),
                    color = FscmInkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(task.boxCount.toString(), color = FscmBrandDeep, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.product_counting_boxes_unit), color = FscmInkSoft, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.size(6.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = FscmInkSoft)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FscmStatusChip(
                text = stringResource(
                    if (task.task.status == ProductCountTaskStatus.Completed.value) {
                        R.string.product_counting_completed
                    } else {
                        R.string.product_counting_in_progress
                    },
                ),
                color = if (task.task.status == ProductCountTaskStatus.Completed.value) FscmSuccess else FscmBrandMain,
            )
            ProductCountingUploadChip(task.task.uploadStatus)
        }
    }
}

@Composable
fun ProductCountingTaskDetailScreen(
    taskId: String,
    viewModel: ProductCountingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val broadcastMode = scannerState.effectiveMode == ScannerInputMode.IntentBroadcast
    val context = LocalContext.current
    var showCamera by rememberSaveable { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> showCamera = granted }

    LaunchedEffect(taskId) { viewModel.load(taskId) }
    DisposableEffect(lifecycleOwner, state.editable) {
        if (state.editable) viewModel.registerScanner(lifecycleOwner)
        onDispose { if (state.editable) viewModel.unregisterScanner() }
    }
    LaunchedEffect(state.processing, state.pendingDuplicate, broadcastMode, state.editable) {
        if (!state.processing && state.pendingDuplicate == null && !broadcastMode && state.editable) {
            focusRequester.requestFocus()
        }
    }

    if (showCamera) {
        Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(onBarcodeDetected = { code ->
                    showCamera = false
                    viewModel.submit(code)
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(FscmPageTop)) {
        if (state.loading && state.task == null) {
            ProductCountingLoading(Modifier.align(Alignment.Center).padding(12.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    ProductCountSummaryCard(task = state.task)
                }
                state.error?.let { error -> item { ProductCountingNotice(error.resolve(), danger = true, onDismiss = viewModel::clearMessage) } }
                state.success?.let { message -> item { ProductCountingNotice(message.resolve(), danger = false, onDismiss = viewModel::clearMessage) } }
                item { ProductCountProductCard(state.task) }
                if (state.editable) {
                    item {
                        ProductCountingScanInput(
                            value = input,
                            processing = state.processing,
                            broadcastMode = broadcastMode,
                            focusRequester = focusRequester,
                            onValueChange = { value ->
                                input = value
                                if (value.hasProductCountingScanTerminator()) {
                                    viewModel.submit(value.cleanProductCountingScanInput())
                                    input = ""
                                }
                            },
                            onSubmit = {
                                viewModel.submit(input.cleanProductCountingScanInput())
                                input = ""
                            },
                            onOpenCamera = { if (PdaPermissions.hasCameraPermission(context)) showCamera = true else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                            onTogglePda = viewModel::togglePdaMode,
                        )
                    }
                } else {
                    item { ProductCountingNotice(stringResource(R.string.product_counting_read_only), danger = false) }
                }
                item {
                    Text(
                        stringResource(R.string.product_counting_recent_records),
                        color = FscmInkStrong,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.entries.isEmpty()) {
                    item { ProductCountingEmpty(stringResource(R.string.product_counting_no_records)) }
                } else {
                    items(state.entries, key = ProductCountEntryEntity::clientScanId) { entry -> ProductCountEntryRow(entry) }
                }
            }
            val completed = state.task?.task?.status == ProductCountTaskStatus.Completed.value
            val uploadStatus = state.task?.task?.uploadStatus.orEmpty()
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (completed) {
                    val canUpload = uploadStatus == ProductCountUploadStatus.Local.value ||
                        uploadStatus == ProductCountUploadStatus.Failed.value
                    if (canUpload) {
                        Button(
                            onClick = viewModel::upload,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !state.processing,
                            colors = ButtonDefaults.buttonColors(containerColor = FscmBrandMain),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (state.processing) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.product_counting_upload))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            ProductCountingUploadChip(uploadStatus)
                        }
                    }
                    TextButton(
                        onClick = viewModel::reopen,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        enabled = !state.processing && uploadStatus != ProductCountUploadStatus.Uploading.value,
                    ) { Text(stringResource(R.string.product_counting_reopen)) }
                } else {
                    Button(
                        onClick = viewModel::complete,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !state.processing && (state.task?.boxCount ?: 0) > 0 && state.editable,
                        colors = ButtonDefaults.buttonColors(containerColor = FscmBrandMain),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (state.processing) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.product_counting_finish))
                    }
                }
            }
        }
    }

    state.pendingDuplicate?.let { duplicate ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.product_counting_duplicate_title)) },
            text = { Text(stringResource(R.string.product_counting_duplicate_message, duplicate.labelCode)) },
            confirmButton = {
                Button(onClick = viewModel::confirmDuplicate) { Text(stringResource(R.string.product_counting_duplicate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDuplicate) { Text(stringResource(R.string.product_counting_duplicate_cancel)) }
            },
        )
    }
    if (state.resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReset,
            title = { Text(stringResource(R.string.product_counting_reset_title)) },
            text = { Text(stringResource(R.string.product_counting_reset_message, state.task?.boxCount ?: 0)) },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmReset,
                    colors = ButtonDefaults.buttonColors(containerColor = FscmDanger),
                ) { Text(stringResource(R.string.product_counting_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReset) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun ProductCountSummaryCard(task: ProductCountTaskSummary?) {
    FscmCard(modifier = Modifier.fillMaxWidth(), containerColor = FscmBrandDeep) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.product_counting_counted_boxes), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelLarge)
                Text((task?.boxCount ?: 0).toString(), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ProductCountProductCard(task: ProductCountTaskSummary?) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = FscmBrandMain, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    task?.task?.productName.orEmpty().ifBlank { stringResource(R.string.product_counting_waiting_product) },
                    color = FscmInkStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    task?.task?.productCode.orEmpty().ifBlank { stringResource(R.string.product_counting_first_scan_hint) },
                    color = FscmInkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            task?.let { ProductCountingUploadChip(it.task.uploadStatus) }
        }
    }
}

@Composable
private fun ProductCountingScanInput(
    value: String,
    processing: Boolean,
    broadcastMode: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenCamera: () -> Unit,
    onTogglePda: () -> Unit,
) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.product_counting_scan_box), color = FscmInkStrong, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FscmStatusChip(
                text = stringResource(if (broadcastMode) R.string.device_broadcast_mode else R.string.device_keyboard_mode),
                color = if (broadcastMode) FscmSuccess else FscmWarning,
            )
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onTogglePda) { Text(stringResource(if (broadcastMode) R.string.device_keyboard_mode else R.string.device_broadcast_mode)) }
        if (broadcastMode) {
            Row(
                Modifier.fillMaxWidth().height(58.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (processing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = FscmBrandMain, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (processing) R.string.product_counting_processing else R.string.product_counting_waiting_scanner), color = FscmInkSoft)
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                enabled = !processing,
                singleLine = true,
                label = { Text(stringResource(R.string.product_counting_box_label)) },
                trailingIcon = {
                    IconButton(onClick = onOpenCamera, enabled = !processing) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.common_confirm))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
        }
    }
}

@Composable
private fun ProductCountEntryRow(entry: ProductCountEntryEntity) {
    val syncFailed = entry.syncStatus == ProductCountSyncStatus.Conflict.value ||
        entry.syncStatus == ProductCountSyncStatus.Rejected.value
    FscmCard(modifier = Modifier.fillMaxWidth(), padding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (syncFailed) Icons.Rounded.Error else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = when {
                    syncFailed -> FscmDanger
                    entry.duplicateConfirmed -> FscmWarning
                    else -> FscmSuccess
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.labelCode, color = FscmInkStrong, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatProductCountingTime(entry.scannedAt), color = FscmInkSoft, style = MaterialTheme.typography.labelSmall)
                if (syncFailed && entry.syncError.isNotBlank()) {
                    Text(
                        entry.syncError,
                        color = FscmDanger,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (syncFailed) {
                FscmStatusChip(stringResource(R.string.product_counting_sync_error), FscmDanger)
            } else if (entry.duplicateConfirmed) {
                FscmStatusChip(stringResource(R.string.product_counting_duplicate_confirmed), FscmWarning)
            }
        }
    }
}

@Composable
private fun ProductCountingUploadChip(status: String) {
    val (label, color) = when (status) {
        ProductCountUploadStatus.Uploaded.value -> R.string.product_counting_uploaded to FscmSuccess
        ProductCountUploadStatus.UploadedWithWarnings.value -> R.string.product_counting_uploaded_warning to FscmWarning
        ProductCountUploadStatus.Uploading.value -> R.string.product_counting_uploading to FscmBrandMain
        ProductCountUploadStatus.Failed.value -> R.string.product_counting_upload_failed to FscmDanger
        else -> R.string.product_counting_not_uploaded to FscmWarning
    }
    FscmStatusChip(stringResource(label), color)
}

@Composable
private fun ProductCountingSyncChip(status: String) {
    val (label, color) = when (status) {
        ProductCountSyncStatus.Synced.value -> R.string.product_counting_synced to FscmSuccess
        ProductCountSyncStatus.Conflict.value, ProductCountSyncStatus.Rejected.value -> R.string.product_counting_sync_error to FscmDanger
        else -> R.string.product_counting_pending_sync to FscmWarning
    }
    FscmStatusChip(stringResource(label), color)
}

@Composable
private fun ProductCountingNotice(
    message: String,
    danger: Boolean,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val color = if (danger) FscmDanger else FscmSuccess
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = if (danger) FscmDanger else FscmInkStrong, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.common_retry)) } }
        onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.common_close)) } }
    }
}

@Composable
private fun ProductCountingEmpty(message: String) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Text(message, color = FscmInkSoft, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp))
    }
}

@Composable
private fun ProductCountingLoading(modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(modifier.height(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FscmBrandMain)
    }
}

private fun formatProductCountingTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun String.hasProductCountingScanTerminator(): Boolean = contains('\n') || contains('\r')
private fun String.cleanProductCountingScanInput(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
