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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeQuantity
import kotlinx.coroutines.delay

// Quantity entry accepts the touch keyboard, never characters from a PDA scanner wedge.
private fun Modifier.stocktakeQuantityInput(): Modifier = onPreviewKeyEvent { event ->
    event.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
        (event.nativeKeyEvent.unicodeChar > 0 || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
}

@Composable
fun LocationStocktakeScreen(session: SessionState, viewModel: LocationStocktakeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val codeStyle = SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    var scanInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(session) { viewModel.enter(session) }
    LaunchedEffect(state.stage, state.location, state.sku) { scanInput = "" }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            viewModel.tick()
        }
    }
    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner)
        onDispose(viewModel::unregisterScanner)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.camera(true) }
    val openCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.camera(true)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.context?.workspace?.warehouseName?.ifBlank { null } ?: stringResource(R.string.st_warehouse), fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.st_current_location, state.location.ifBlank { "—" }), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        TextButton(onClick = viewModel::changeLocation, enabled = state.allowed && !state.locked) { Text(stringResource(R.string.st_change_location)) }
                    }
                }
            }
        }
        state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
        state.notice?.let { notice -> item { Text(stringResource(notice), color = MaterialTheme.colorScheme.primary) } }
        if (state.context?.workspace?.status != "configured" && state.context != null) item { Text(stringResource(R.string.st_config_required)) }
        if (state.context?.writeEnabled == false) item { Text(stringResource(R.string.st_write_blocked), color = MaterialTheme.colorScheme.error) }
        if (state.busy) item { CircularProgressIndicator() }
        if (state.error != null && state.context == null && state.allowed && !state.locked) item { OutlinedButton(onClick = viewModel::retry) { Text(stringResource(R.string.st_retry)) } }
        if (state.pending != null && state.confirmation == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.st_pending))
                        Text(
                            buildAnnotatedString {
                                withStyle(codeStyle) { append("${state.location} · ${state.sku}") }
                                append(" · ${state.pending!!.countedQuantity}")
                            },
                        )
                        Button(onClick = { viewModel.submit() }, enabled = !state.submitting && state.allowed && state.canWrite) { Text(stringResource(R.string.st_recover)) }
                    }
                }
            }
        } else if (state.stage != StocktakeStage.Count) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (state.stage == StocktakeStage.Location) R.string.st_scan_location else R.string.st_scan_sku), fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = scanInput,
                            onValueChange = { value ->
                                if (value.contains('\n') || value.contains('\r')) {
                                    viewModel.scan(value)
                                    scanInput = ""
                                } else {
                                    scanInput = value
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canScan,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold),
                            label = { Text(stringResource(R.string.st_scan_or_type)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                viewModel.scan(scanInput)
                                scanInput = ""
                            }),
                            trailingIcon = { IconButton(onClick = openCamera, enabled = state.canScan) { Icon(Icons.Rounded.CameraAlt, stringResource(R.string.st_camera)) } },
                        )
                        Button(onClick = {
                            viewModel.scan(scanInput)
                            scanInput = ""
                        }, enabled = state.canScan && scanInput.isNotBlank()) { Text(stringResource(R.string.st_query)) }
                    }
                }
            }
        }
        state.lookup?.stock?.takeIf { state.stage == StocktakeStage.Count }?.let { stock ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stock.sku, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        stock.name?.let { Text(it) }
                        Text(stringResource(R.string.st_current_quantity, stock.onHand.toString(), stock.unit.orEmpty()))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.quantity,
                                onValueChange = { viewModel.editQuantity(it) },
                                label = { Text(stringResource(R.string.st_actual_quantity)) },
                                singleLine = true,
                                enabled = !state.locked,
                                isError = stocktakeQuantity(state.quantity) == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                modifier = Modifier.weight(1f).stocktakeQuantityInput(),
                            )
                            OutlinedButton(onClick = { viewModel.editQuantity("0", zero = true) }, enabled = !state.locked) { Text(stringResource(R.string.st_set_zero)) }
                        }
                        val delta = stocktakeQuantity(state.quantity)?.minus(stock.onHand ?: 0)
                        Text(stringResource(R.string.st_delta, delta?.let { if (it > 0) "+$it" else "$it" } ?: "—"), color = MaterialTheme.colorScheme.error)
                        if (state.expired) {
                            Text(stringResource(R.string.st_expired), color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = viewModel::requery, enabled = !state.locked) { Text(stringResource(R.string.st_query_again)) }
                        }
                        Button(onClick = { viewModel.submit() }, enabled = state.readyToSubmit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (state.submitting) R.string.st_submitting else R.string.st_finish)) }
                        TextButton(onClick = viewModel::changeSku, enabled = !state.locked) { Text(stringResource(R.string.st_change_sku)) }
                    }
                }
            }
        }
        if (state.sku.isNotBlank()) item { Text(stringResource(R.string.st_other_locations), fontWeight = FontWeight.Bold) }
        items(state.locations, key = { it.externalId }) { stock ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stock.locationCode, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Text("${stock.onHand ?: "—"} ${stock.unit.orEmpty()}")
                        if ((stock.onHand ?: 0) < 0) Text(stringResource(R.string.st_negative_stock), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { viewModel.switchLocation(stock) }, enabled = (stock.onHand ?: 0) > 0 && !state.locked) { Text(stringResource(R.string.st_switch)) }
                }
            }
        }
        if (state.listBusy) item { CircularProgressIndicator() }
        if (state.listError) {
            item { TextButton(onClick = viewModel::loadLocations) { Text(stringResource(R.string.st_alternatives_failed)) } }
        } else if (state.hasMore) {
            item { TextButton(onClick = viewModel::loadLocations, enabled = !state.listBusy) { Text(stringResource(R.string.st_load_more)) } }
        }
        state.lastRecord?.let { record ->
            item {
                Text(stringResource(R.string.st_last_result, stringResource(stocktakeStatusResource(record.status))))
                Text(
                    buildAnnotatedString {
                        withStyle(codeStyle) { append("${record.locationCode} · ${record.sku}") }
                        append(" · ${record.beforeQuantity} → ${record.countedQuantity}")
                    },
                )
            }
        }
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.st_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${confirmation.record.locationCode} · ${confirmation.record.sku}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text("${confirmation.record.beforeQuantity} → ${confirmation.record.countedQuantity}")
                    Text(stringResource(R.string.st_delta, confirmation.record.delta.toString()), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = state.confirmationInput,
                        onValueChange = viewModel::confirmInput,
                        label = { Text(stringResource(R.string.st_reenter)) },
                        enabled = !state.submitting,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().stocktakeQuantityInput(),
                    )
                }
            },
            confirmButton = { Button(onClick = { viewModel.submit(confirm = true) }, enabled = !state.submitting && stocktakeQuantity(state.confirmationInput) == confirmation.record.countedQuantity) { Text(stringResource(R.string.st_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelConfirmation, enabled = !state.submitting) { Text(stringResource(R.string.st_cancel)) } },
        )
    }
    if (state.switchTarget != null || state.changingLocation || state.changingSku) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSwitch,
            title = { Text(stringResource(R.string.st_discard_title)) },
            text = { Text(stringResource(R.string.st_discard_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmSwitch) { Text(stringResource(R.string.st_switch)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelSwitch) { Text(stringResource(R.string.st_cancel)) } },
        )
    }
    if (state.camera) {
        Dialog(onDismissRequest = { viewModel.camera(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                BarcodeScanner(onBarcodeDetected = viewModel::scan, modifier = Modifier.fillMaxSize(), showSelectionDialogForMultiple = true)
                Button(onClick = { viewModel.camera(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text(stringResource(R.string.st_cancel)) }
            }
        }
    }
}

internal fun stocktakeStatusResource(status: String): Int = when (status) {
    "succeeded" -> R.string.st_success
    "awaiting_confirmation" -> R.string.st_awaiting_confirmation
    "expired" -> R.string.st_expired
    "blocked_evidence" -> R.string.st_write_blocked
    "failed", "cancelled" -> R.string.st_submit_failed
    else -> R.string.st_pending
}
