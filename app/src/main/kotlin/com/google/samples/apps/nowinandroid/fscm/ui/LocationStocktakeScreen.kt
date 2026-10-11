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
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.CountTask
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeOption
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeStock
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeQuantity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareAlertDialog as AlertDialog
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareBackHandler as BackHandler
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareDialog as Dialog

private fun Modifier.stocktakeQuantityInput(): Modifier = onPreviewKeyEvent { event ->
    event.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
        (event.nativeKeyEvent.unicodeChar > 0 || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationStocktakeScreen(session: SessionState, viewModel: LocationStocktakeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScannerInputScope(state.canScan && !state.camera)
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(session) { viewModel.enter(session) }
    LaunchedEffect(state.stage, state.task?.id, state.location, state.showTasks) { viewModel.clearManualSearch() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            viewModel.tick()
        }
    }
    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && viewModel.state.value.showTasks) viewModel.loadTasks() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.unregisterScanner()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    BackHandler(enabled = !state.showTasks) { viewModel.backToTasks() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.camera(true) }
    val openCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.camera(true)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        StocktakeToolbar(state, viewModel::backToTasks) { viewModel.quick() }
        if (state.showTasks) {
            val tabs = listOf("pending", "active", "completed")
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(state.taskTab).coerceAtLeast(0)) {
                tabs.forEach { tab -> Tab(selected = state.taskTab == tab, onClick = { viewModel.selectTab(tab) }, text = { Text(stringResource(if (tab == "active") R.string.st_task_active else countTaskStatusResource(tab))) }) }
            }
            if (state.taskTab == "active") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.mine, onClick = { viewModel.selectTab("active", true) }, label = { Text(stringResource(R.string.st_mine)) })
                    FilterChip(selected = !state.mine, onClick = { viewModel.selectTab("active", false) }, label = { Text(stringResource(R.string.st_all)) })
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.showTasks && state.tasksBusy, onRefresh = { if (state.showTasks) viewModel.loadTasks() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(state.context?.workspace?.warehouseName?.ifBlank { null } ?: stringResource(R.string.st_warehouse), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.error?.let { error ->
                    item {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        if (state.progressFailed) {
                            TextButton(onClick = viewModel::retryProgress) { Text(stringResource(R.string.st_reopen_task)) }
                        } else if (state.showTasks) {
                            TextButton(onClick = {
                                viewModel.retry()
                                viewModel.loadTasks()
                            }) { Text(stringResource(R.string.st_retry)) }
                        }
                    }
                }
                if (state.context?.workspace?.status != "configured" && state.context != null) item { Text(stringResource(R.string.st_config_required)) }
                if (state.context?.writeEnabled == false) item { Text(stringResource(R.string.st_write_blocked)) }
                if (state.busy) item { CircularProgressIndicator() }
                if (state.showTasks) {
                    if (!state.tasksBusy && state.tasks.isEmpty()) item { Text(stringResource(R.string.st_tasks_empty)) }
                    items(state.tasks, key = CountTask::id) { task -> CountTaskCard(task, state.canWrite && task.status == "pending", !state.busy) { viewModel.openTask(task) } }
                    if (state.tasksMore) item { OutlinedButton(onClick = { viewModel.loadTasks(true) }, enabled = !state.tasksBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_load_more)) } }
                } else {
                    val task = state.task
                    if (task != null) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(countTaskStatusResource(task.status)), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                if (state.editable) TextButton(onClick = { viewModel.askRelease(true) }, enabled = !state.locked) { Text(stringResource(if (task.sku.isBlank()) R.string.st_cancel_task else R.string.st_release)) }
                            }
                            if (task.remark.isNotBlank()) Text(task.remark, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (task.status == "completed") {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(stringResource(R.string.st_task_completed), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        StocktakeIdentity(task.locationCode, task.sku)
                                        val record = state.lastRecord
                                        Text(stringResource(R.string.st_verified_quantity), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${record?.verifiedQuantity ?: "—"} ${task.unit}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.st_verified_time, stocktakeTime(record?.verifiedAt)), style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { viewModel.quick(keepLocation = true) }, enabled = state.canQuick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_continue_quick)) }
                                    }
                                }
                            }
                        } else if (!state.own || task.status == "cancelled") {
                            item { CountTaskCard(task, false, false) {} }
                        } else if (state.pending != null) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        StocktakeIdentity(state.location, state.sku)
                                        Text("${state.pending!!.countedQuantity} ${task.unit}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.st_pending))
                                        Button(onClick = { viewModel.submit() }, enabled = !state.submitting && state.canWrite, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (state.submitting) R.string.st_submitting else R.string.st_recover)) }
                                    }
                                }
                            }
                        } else if (state.editable) {
                            item { StocktakeIdentity(state.location, state.sku) }
                            if (state.stage != StocktakeStage.Count) {
                                item {
                                    Text(stringResource(if (state.stage == StocktakeStage.Location) R.string.st_scan_location else R.string.st_scan_or_select_sku), fontWeight = FontWeight.Bold)
                                    StocktakeManualCodeInput(
                                        search = state.manualSearch,
                                        enabled = state.canScan,
                                        refreshing = state.referencesRefreshing,
                                        refreshFailed = state.referencesFailed,
                                        onInput = viewModel::searchManualCode,
                                        onSubmit = viewModel::scan,
                                        onCamera = openCamera,
                                        onRefresh = viewModel::refreshOfflineCodes,
                                    )
                                }
                                items(state.manualSearch.result.items, key = { "offline:${it.code}" }) { option ->
                                    StocktakeOfflineOption(option, state.canScan) { viewModel.chooseManualCode(option) }
                                }
                                val choices = if (state.stage == StocktakeStage.Sku) state.shelf else state.locations
                                if (choices.isNotEmpty()) item { Text(stringResource(if (state.stage == StocktakeStage.Sku) R.string.st_shelf_skus else R.string.st_other_locations), fontWeight = FontWeight.Bold) }
                                items(choices, key = StocktakeStock::externalId) { stock -> StocktakeChoice(stock, state.stage == StocktakeStage.Sku, state.canScan) { viewModel.chooseStock(stock) } }
                                if (state.listBusy) item { CircularProgressIndicator() }
                                if (state.listError) {
                                    item { TextButton(onClick = { if (state.stage == StocktakeStage.Sku) viewModel.loadShelf() else viewModel.loadLocations() }) { Text(stringResource(R.string.st_alternatives_failed)) } }
                                } else if ((state.stage == StocktakeStage.Sku && state.shelfMore) || (state.stage == StocktakeStage.Location && state.hasMore)) {
                                    item { OutlinedButton(onClick = { if (state.stage == StocktakeStage.Sku) viewModel.loadShelf() else viewModel.loadLocations() }, enabled = !state.listBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_load_more)) } }
                                }
                            } else {
                                item {
                                    StocktakeQuantityPanel(state, { viewModel.editQuantity(it) }, { viewModel.editQuantity("0", zero = true) }, viewModel::requery, viewModel::review)
                                }
                            }
                            if (state.location.isNotBlank()) item { TextButton(onClick = viewModel::changeLocation, enabled = !state.locked) { Text(stringResource(R.string.st_change_location)) } }
                        }
                        item {
                            Text(task.number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.st_creator, task.creatorName, stocktakeTime(task.createdAt)), style = MaterialTheme.typography.bodySmall)
                            if (task.operatorName.isNotBlank()) Text(stringResource(R.string.st_operator, task.operatorName, stocktakeTime(task.claimedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!state.own) {
                            state.detail?.records?.let { records ->
                                items(records, key = { it.id }) { record ->
                                    Text("${record.locationCode} · ${record.sku}", fontWeight = FontWeight.Bold)
                                    Text("${record.beforeQuantity} → ${record.countedQuantity} ${record.unit}")
                                    Text(stringResource(stocktakeStatusResource(record.status)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.draftConflict?.let { draft ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.st_draft_conflict)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StocktakeIdentity(state.location, state.sku)
                    Text(stringResource(R.string.st_draft_values, draft.quantity, state.quantity))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.resolveDraft(true) }) { Text(stringResource(R.string.st_use_local)) } },
            dismissButton = { TextButton(onClick = { viewModel.resolveDraft(false) }) { Text(stringResource(R.string.st_use_server)) } },
        )
    }
    if (state.review) {
        AlertDialog(
            onDismissRequest = viewModel::closeReview,
            title = { Text(stringResource(R.string.st_check_result)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StocktakeIdentity(state.location, state.sku)
                    Text(stringResource(R.string.st_actual_quantity))
                    Text("${state.quantity} ${state.task?.unit.orEmpty()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.st_confirm_hint))
                }
            },
            confirmButton = { Button(onClick = { viewModel.submit() }, enabled = state.readyToSubmit) { Text(stringResource(R.string.st_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::closeReview) { Text(stringResource(R.string.st_edit_count)) } },
        )
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.st_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StocktakeIdentity(confirmation.record.locationCode, confirmation.record.sku)
                    Text("${confirmation.record.countedQuantity} ${confirmation.record.unit}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.st_delta, confirmation.record.delta.toString()))
                    OutlinedTextField(value = state.confirmationInput, onValueChange = viewModel::confirmInput, label = { Text(stringResource(R.string.st_reenter)) }, enabled = !state.submitting, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().stocktakeQuantityInput())
                }
            },
            confirmButton = { Button(onClick = { viewModel.submit(confirm = true) }, enabled = !state.submitting && !state.busy && stocktakeQuantity(state.confirmationInput) == confirmation.record.countedQuantity) { Text(stringResource(R.string.st_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelConfirmation, enabled = !state.submitting && !state.busy) { Text(stringResource(R.string.st_edit_count)) } },
        )
    }
    if (state.switchTarget != null || state.changingLocation) AlertDialog(onDismissRequest = viewModel::cancelSwitch, title = { Text(stringResource(R.string.st_discard_title)) }, text = { Text(stringResource(R.string.st_discard_text)) }, confirmButton = { TextButton(onClick = viewModel::confirmSwitch) { Text(stringResource(R.string.st_switch)) } }, dismissButton = { TextButton(onClick = viewModel::cancelSwitch) { Text(stringResource(R.string.st_cancel)) } })
    if (state.releasePrompt) AlertDialog(onDismissRequest = { viewModel.askRelease(false) }, title = { Text(stringResource(if (state.task?.sku.isNullOrBlank()) R.string.st_cancel_task else R.string.st_release)) }, text = { Text(stringResource(R.string.st_release_hint)) }, confirmButton = { TextButton(onClick = viewModel::release) { Text(stringResource(R.string.st_confirm_action)) } }, dismissButton = { TextButton(onClick = { viewModel.askRelease(false) }) { Text(stringResource(R.string.st_cancel)) } })
    if (state.camera) {
        Dialog(onDismissRequest = { viewModel.camera(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                BarcodeScanner(onBarcodeDetected = viewModel::scan, modifier = Modifier.fillMaxSize(), showSelectionDialogForMultiple = true)
                Button(onClick = { viewModel.camera(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text(stringResource(R.string.st_cancel)) }
            }
        }
    }
}

@Composable
internal fun StocktakeIdentity(location: String, sku: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.st_location_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(location.ifBlank { stringResource(R.string.st_choose_location) }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("SKU", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sku.ifBlank { stringResource(R.string.st_choose_sku) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CountTaskCard(task: CountTask, claim: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.sku.ifBlank { stringResource(R.string.st_choose_sku) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.locationCode.ifBlank { stringResource(R.string.st_choose_location) }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(task.draftQuantity?.let { "$it ${task.unit}" } ?: stringResource(R.string.st_to_count), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(countTaskStatusResource(task.status)), color = MaterialTheme.colorScheme.primary)
            if (task.productName.isNotBlank()) Text(task.productName, style = MaterialTheme.typography.bodySmall)
            if (task.operatorName.isNotBlank()) Text(stringResource(R.string.st_operator, task.operatorName, stocktakeTime(task.claimedAt)), style = MaterialTheme.typography.bodySmall)
            Text(stocktakeTime(task.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (claim) Text(stringResource(R.string.st_claim), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun StocktakeChoice(stock: StocktakeStock, showSku: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled && (stock.onHand ?: 0) > 0, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (showSku) stock.sku else stock.locationCode, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${stock.onHand ?: "—"} ${stock.unit.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (stock.supportsPicking == true) Text(stringResource(R.string.st_picking), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (showSku) stock.name?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if ((stock.onHand ?: 0) <= 0) {
                Text(
                    stringResource(
                        if (stock.onHand == null) {
                            R.string.st_unknown_stock
                        } else if (stock.onHand == 0L) {
                            R.string.st_zero_stock
                        } else {
                            R.string.st_negative_stock
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun countTaskStatusResource(status: String): Int = when (status) {
    "pending" -> R.string.st_task_pending
    "in_progress" -> R.string.st_task_active
    "processing" -> R.string.st_task_processing
    "needs_review" -> R.string.st_task_review
    "completed" -> R.string.st_task_completed
    "cancelled" -> R.string.st_task_cancelled
    else -> R.string.st_pending
}
internal fun stocktakeStatusResource(status: String): Int = when (status) {
    "succeeded" -> R.string.st_task_completed
    "awaiting_confirmation" -> R.string.st_awaiting_confirmation
    "expired" -> R.string.st_expired
    "blocked_evidence" -> R.string.st_write_blocked
    "failed", "cancelled" -> R.string.st_submit_failed
    else -> R.string.st_pending
}
private fun stocktakeTime(value: String?): String = value?.let { runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault(it) } ?: "—"

@Composable
internal fun StocktakeToolbar(state: LocationStocktakeUiState, onBack: () -> Unit, onQuick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.showTasks) TextButton(onClick = onBack, enabled = !state.busy && !state.submitting) { Text(stringResource(R.string.st_task_list)) }
        Button(onClick = onQuick, enabled = state.canQuick, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.padding(end = 8.dp))
            Text(stringResource(if (state.quickPending) R.string.st_quick_recover else R.string.st_quick))
        }
    }
}

@Composable
internal fun StocktakeQuantityPanel(state: LocationStocktakeUiState, onQuantity: (String) -> Unit, onZero: () -> Unit, onRequery: () -> Unit, onFinish: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.lookup?.stock?.let { stock ->
                stock.name?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(stringResource(R.string.st_current_quantity, stock.onHand.toString(), stock.unit.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.quantity, onValueChange = onQuantity, label = { Text(stringResource(R.string.st_actual_quantity)) }, enabled = !state.locked,
                    singleLine = true, textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), isError = stocktakeQuantity(state.quantity) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.weight(1f).stocktakeQuantityInput(),
                )
                OutlinedButton(onClick = onZero, enabled = !state.locked) { Text(stringResource(R.string.st_set_zero)) }
            }
            Text(state.task?.unit.orEmpty(), style = MaterialTheme.typography.labelLarge)
            val delta = state.lookup?.stock?.onHand?.let { before -> stocktakeQuantity(state.quantity)?.minus(before) }
            Text(stringResource(R.string.st_delta, delta?.let { if (it > 0) "+$it" else "$it" } ?: "—"), color = if (delta != null && delta != 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.expired) Text(stringResource(R.string.st_expired), color = MaterialTheme.colorScheme.error)
            if (state.expired || state.lookup == null) OutlinedButton(onClick = onRequery, enabled = !state.locked) { Text(stringResource(R.string.st_query_again)) }
            Button(onClick = onFinish, enabled = state.readyToSubmit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_finish)) }
        }
    }
}

@Composable
internal fun StocktakeManualCodeInput(
    search: StocktakeManualSearch,
    enabled: Boolean,
    refreshing: Boolean,
    refreshFailed: Boolean,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCamera: () -> Unit,
    onRefresh: () -> Unit,
) {
    val focus = LocalFocusManager.current
    fun submit(value: String) {
        focus.clearFocus()
        onSubmit(value)
    }
    OutlinedTextField(
        value = search.text,
        onValueChange = { value ->
            if (value.contains('\n') || value.contains('\r')) submit(value) else onInput(value)
        },
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(stringResource(R.string.st_prefix_input)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { submit(search.text) }),
        trailingIcon = {
            IconButton(onClick = {
                focus.clearFocus()
                onCamera()
            }, enabled = enabled) { Icon(Icons.Rounded.CameraAlt, stringResource(R.string.st_camera)) }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (search.text.isNotBlank()) {
            TextButton(onClick = { submit(search.text) }, enabled = enabled) { Text(stringResource(R.string.st_query)) }
        }
        TextButton(onClick = onRefresh, enabled = enabled && !refreshing) {
            Text(stringResource(if (refreshing) R.string.st_offline_refreshing else R.string.st_offline_refresh))
        }
    }
    if (refreshFailed) Text(stringResource(R.string.st_offline_refresh_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (search.text.isNotBlank()) {
        Text(
            stringResource(
                when {
                    search.searching -> R.string.st_offline_searching
                    search.failed -> R.string.st_offline_search_failed
                    search.result.items.isEmpty() -> R.string.st_offline_no_match
                    search.result.hasMore -> R.string.st_offline_more
                    else -> R.string.st_offline_matches
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StocktakeOfflineOption(option: OfflineCodeOption, enabled: Boolean, onClick: () -> Unit) {
    val focus = LocalFocusManager.current
    Card(onClick = {
        focus.clearFocus()
        onClick()
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
