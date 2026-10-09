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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.ShippingItem
import com.google.samples.apps.nowinandroid.fscm.data.ShippingTask
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode

internal fun shippingStageLabel(stage: String): Int = when (stage) {
    "packing" -> R.string.shipping_packing
    "shipping" -> R.string.shipping_dispatch
    "shipped" -> R.string.shipping_shipped
    else -> R.string.shipping_picking
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineShippingListScreen(scopeKey: String, onOpen: (Long) -> Unit) {
    val viewModel: OfflineShippingListViewModel = hiltViewModel(key = "shipping-list-$scopeKey")
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyword by rememberSaveable(scopeKey) { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(Modifier.fillMaxSize().background(FscmPageTop)) {
        PrimaryTabRow(selectedTabIndex = if (state.shipped) 1 else 0) {
            Tab(selected = !state.shipped, enabled = !state.loading, onClick = { viewModel.select(false) }, text = { Text(stringResource(R.string.shipping_pending)) })
            Tab(selected = state.shipped, enabled = !state.loading, onClick = { viewModel.select(true) }, text = { Text(stringResource(R.string.shipping_shipped)) })
        }
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            singleLine = true,
            label = { Text(stringResource(R.string.shipping_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(keyword) }),
            trailingIcon = { TextButton(onClick = { viewModel.search(keyword) }, enabled = !state.loading) { Text(stringResource(R.string.shipping_find)) } },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { item { ShippingErrorNotice(it) { viewModel.refresh() } } }
                if (!state.loading && state.rows.isEmpty()) item { Text(stringResource(R.string.shipping_empty), Modifier.padding(16.dp)) }
                items(state.rows, key = ShippingTask::orderId) { task ->
                    Card(onClick = { onOpen(task.orderId) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(task.orderNo, fontWeight = FontWeight.Bold)
                            Text(task.warehouse)
                            Text(stringResource(R.string.shipping_task_counts, task.skuCount, task.quantity))
                            Text(stringResource(shippingStageLabel(task.stage)), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.rows.size < state.total) {
                    item {
                        OutlinedButton(onClick = { viewModel.refresh(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shipping_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShippingErrorNotice(error: Int, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(stringResource(R.string.shipping_retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineShippingDetailScreen(orderId: Long, scopeKey: String) {
    val viewModel: OfflineShippingDetailViewModel = hiltViewModel(key = "shipping-detail-$scopeKey-$orderId")
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanner by viewModel.scannerState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var scanText by rememberSaveable(orderId) { mutableStateOf("") }
    var cameraScan by rememberSaveable { mutableStateOf(false) }
    var photoCaptured by rememberSaveable(orderId) { mutableStateOf(false) }
    var carrier by rememberSaveable(orderId) { mutableStateOf("") }
    var tracking by rememberSaveable(orderId) { mutableStateOf("") }
    var logistics by rememberSaveable(orderId) { mutableStateOf(false) }
    var showPicked by rememberSaveable(orderId) { mutableStateOf(false) }
    var showPacking by rememberSaveable(orderId) { mutableStateOf(false) }
    var permissionAction by remember { mutableStateOf("") }
    val photoCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) photoCaptured = true
    }
    fun launchPhoto() {
        viewModel.preparePhoto { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            photoCamera.launch(uri)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            viewModel.cameraDenied()
        } else if (permissionAction == "scan") {
            cameraScan = true
        } else {
            launchPhoto()
        }
    }
    fun openCamera(action: String) {
        permissionAction = action
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        } else if (action == "scan") {
            cameraScan = true
        } else {
            launchPhoto()
        }
    }
    LaunchedEffect(orderId) { viewModel.load(orderId) }
    LaunchedEffect(photoCaptured, state.busy, state.task?.orderId) {
        // A restored camera result can arrive before this task finishes loading.
        if (photoCaptured && !state.busy && state.task?.orderId == orderId) {
            photoCaptured = false
            viewModel.captured()
        }
    }
    val picking = state.task?.stage == "picking" && !state.pending
    DisposableEffect(owner, picking, cameraScan) {
        if (picking && !cameraScan) viewModel.registerScanner(owner)
        onDispose { if (picking && !cameraScan) viewModel.unregisterScanner() }
    }
    val keyboardScan = scanner.effectiveMode != ScannerInputMode.IntentBroadcast
    LaunchedEffect(picking, state.busy, state.scan, keyboardScan, cameraScan) {
        if (picking && !state.busy && state.scan == null && keyboardScan && !cameraScan) runCatching { focus.requestFocus() }
    }
    if (cameraScan) {
        Dialog(onDismissRequest = { cameraScan = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                BarcodeScanner(onBarcodeDetected = {
                    cameraScan = false
                    viewModel.scan(it)
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
    state.scan?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::dismissScan,
            title = { Text(stringResource(R.string.shipping_confirm_pick)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShippingItemContent(item)
                    Text(stringResource(R.string.shipping_pick_quantity, item.quantity, item.unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmPick, enabled = !state.busy) { Text(stringResource(R.string.shipping_confirm_pick)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissScan, enabled = !state.busy) { Text(stringResource(R.string.shipping_cancel)) } },
        )
    }
    state.preview?.let { bytes ->
        Dialog(onDismissRequest = viewModel::dismissPreview) {
            Card {
                Column {
                    AsyncImage(model = bytes, contentDescription = stringResource(R.string.shipping_photo), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
                    TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.shipping_close)) }
                }
            }
        }
    }
    PullToRefreshBox(isRefreshing = state.busy, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().background(FscmPageTop)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ShippingErrorNotice(it, viewModel::refresh) } }
            if (state.pending) item { ShippingErrorNotice(R.string.shipping_pending_request, viewModel::refresh) }
            val task = state.task
            if (task == null && state.busy) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (task != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.orderNo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(task.warehouse)
                        Text(stringResource(R.string.shipping_task_counts, task.skuCount, task.quantity))
                        if (task.recipient.name.isNotBlank()) Text(task.recipient.name)
                        if (task.recipient.phone.isNotBlank()) Text(task.recipient.phone)
                        if (task.recipient.address.isNotBlank()) Text(task.recipient.address)
                    }
                }
                if (task.stage == "shipped") {
                    item {
                        Text(stringResource(R.string.shipping_finished), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(task.shippedAt?.let(::shortShippingTime), task.carrier.takeIf(String::isNotBlank), task.trackingNo.takeIf(String::isNotBlank)).joinToString(" · "))
                    }
                }
                item {
                    TextButton(onClick = { showPicked = !showPicked }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shipping_pick_progress, task.items.count { it.picked }, task.items.size))
                    }
                }
                if (task.stage == "picking") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = scanText,
                                onValueChange = { value ->
                                    if (value.contains('\n') || value.contains('\r') || value.contains('\t')) {
                                        viewModel.scan(value)
                                        scanText = ""
                                    } else {
                                        scanText = value
                                    }
                                },
                                label = { Text(stringResource(R.string.shipping_scan)) },
                                singleLine = true,
                                enabled = !state.busy && !state.pending,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    viewModel.scan(scanText)
                                    scanText = ""
                                }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openCamera("scan") }, enabled = !state.busy && !state.pending, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.shipping_camera_scan)) }
                                OutlinedButton(onClick = viewModel::toggleScanner, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(if (keyboardScan) R.string.shipping_use_pda else R.string.shipping_use_keyboard)) }
                            }
                            Text(stringResource(R.string.shipping_shortage_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (task.stage == "picking" || showPicked) {
                    items(task.items, key = { "sku-${it.itemId}" }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                ShippingItemContent(item)
                                Text(stringResource(if (item.picked) R.string.shipping_picked else R.string.shipping_to_pick, item.quantity, item.unit), fontWeight = FontWeight.Bold, color = if (item.picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                item {
                    val packingActive = task.stage == "packing"
                    if (packingActive || task.stage in listOf("shipping", "shipped")) {
                        TextButton(onClick = { showPacking = !showPacking }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (packingActive) R.string.shipping_packing else R.string.shipping_packed)) }
                        if (packingActive || showPacking) ShippingPhotos(task, "packing", state, viewModel, onCamera = { openCamera("photo") })
                    }
                }
                if (task.stage in listOf("shipping", "shipped")) {
                    item {
                        Text(stringResource(R.string.shipping_dispatch), style = MaterialTheme.typography.titleMedium)
                        ShippingPhotos(task, "shipping", state, viewModel, onCamera = { openCamera("photo") })
                        if (task.stage == "shipping") {
                            TextButton(onClick = { logistics = !logistics }) { Text(stringResource(R.string.shipping_logistics_optional)) }
                            if (logistics) {
                                OutlinedTextField(value = carrier, onValueChange = { carrier = it.take(100) }, label = { Text(stringResource(R.string.shipping_carrier)) }, enabled = !state.busy && !state.pending, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = tracking, onValueChange = { tracking = it.take(120) }, label = { Text(stringResource(R.string.shipping_tracking)) }, enabled = !state.busy && !state.pending, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (task.stage in listOf("packing", "shipping")) {
                    item {
                        Button(
                            onClick = { viewModel.complete(carrier, tracking) },
                            enabled = !state.busy && !state.pending && task.photos.any { it.stage == task.stage } && state.localPhotos.none { it.name.startsWith(task.stage + "_") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text(stringResource(if (task.stage == "packing") R.string.shipping_complete_pack else R.string.shipping_confirm_ship)) }
                    }
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun ShippingItemContent(item: ShippingItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.imageUrl.isNotBlank()) AsyncImage(model = item.imageUrl, contentDescription = item.skuCode, modifier = Modifier.size(64.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.skuCode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (item.name.isNotBlank()) Text(item.name)
            if (item.spec.isNotBlank()) Text(item.spec)
            if (item.locations.isNotEmpty()) Text(stringResource(R.string.shipping_locations, item.locations.joinToString(" / ")))
        }
    }
}

@Composable
private fun ShippingPhotos(task: ShippingTask, stage: String, state: ShippingDetailState, viewModel: OfflineShippingDetailViewModel, onCamera: () -> Unit) {
    val active = task.stage == stage
    val photos = task.photos.filter { it.stage == stage }
    val local = state.localPhotos.filter { it.name.startsWith(stage + "_") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { index, photo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.preview(photo) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.shipping_view_photo, index + 1)) }
                if (active && !photo.confirmed) TextButton(onClick = { viewModel.removePhoto(photo) }, enabled = !state.busy && !state.pending) { Text(stringResource(R.string.shipping_remove)) }
            }
        }
        local.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = file, contentDescription = stringResource(R.string.shipping_photo), modifier = Modifier.size(64.dp))
                TextButton(onClick = { viewModel.retryUpload(file) }, enabled = active && !state.busy && !state.pending) { Text(stringResource(R.string.shipping_retry_upload)) }
                TextButton(onClick = { viewModel.removeLocal(file) }, enabled = !state.busy && !state.pending) { Text(stringResource(R.string.shipping_remove)) }
            }
        }
        if (active) {
            Text(stringResource(R.string.shipping_photo_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCamera, enabled = !state.busy && !state.pending && photos.size + local.size < 5, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.shipping_take_photo)) }
        }
    }
}

private fun shortShippingTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))
}.getOrDefault(value)
