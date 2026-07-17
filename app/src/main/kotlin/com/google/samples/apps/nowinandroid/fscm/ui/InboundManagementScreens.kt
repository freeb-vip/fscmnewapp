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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundItemEntity
import com.google.samples.apps.nowinandroid.fscm.data.isLikelyBoxLabelQr
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val InboundBrand = Color(0xFF1677FF)
private val InboundDeep = Color(0xFF0958D9)
private val InboundBackground = Color(0xFFF5F8FF)
private val InboundStroke = Color(0xFFDCE7F5)
private val InboundInk = Color(0xFF172033)
private val InboundMuted = Color(0xFF66758A)
private val InboundSuccess = Color(0xFF389E0D)
private val InboundWarning = Color(0xFFD48806)
private val InboundDanger = Color(0xFFCF1322)

@Composable
fun InboundSessionListScreen(
    onOpenSession: (Long) -> Unit,
    viewModel: InboundSessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(InboundBackground),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { error -> item { InboundNotice(error.resolve(), true) } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.inbound_select_session), color = InboundInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::load, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.common_retry), tint = InboundBrand)
                }
            }
        }
        if (state.loading && state.sessions.isEmpty()) {
            item { InboundLoading() }
        }
        if (!state.loading && state.sessions.isEmpty() && state.error == null) {
            item { InboundEmpty(stringResource(R.string.inbound_no_sessions)) }
        }
        items(state.sessions, key = { it.sessionId }) { session ->
            InboundSessionCard(session = session, onClick = { onOpenSession(session.sessionId) })
        }
    }
}

@Composable
fun InboundSessionScreen(
    sessionId: Long,
    viewModel: InboundSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    LaunchedEffect(state.cameraResumeToken) { input = "" }
    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner)
        onDispose(viewModel::unregisterScanner)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openCamera()
    }
    val openCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.openCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val workspace = state.workspace
    val summary = workspace?.summary
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(InboundBackground),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { error -> item { InboundNotice(error.resolve(), true, viewModel::consumeNotice) } }
        state.message?.let { message -> item { InboundNotice(message.resolve(), false, viewModel::consumeNotice) } }
        if (state.loading && workspace == null) item { InboundLoading() }
        summary?.let { current ->
            item { InboundProgressHeader(current) }
            item {
                InboundMetrics(
                    total = current.totalCount,
                    inbounded = current.inboundedCount + current.localAssignedCount,
                    remaining = current.remainingCount,
                    conflicts = current.conflictCount,
                )
            }
            item {
                InboundSyncBand(
                    summary = current,
                    submitting = state.submitting,
                    onSync = viewModel::manualSync,
                )
            }
            when {
                current.status == InboundSessionStatus.Complete.value -> item { InboundCompleteBand() }
                workspace.draftBatch == null && current.remainingCount <= 0 -> item { InboundWaitingBand() }
                workspace.draftBatch == null -> item {
                    InboundScanInput(
                        title = stringResource(R.string.inbound_scan_location),
                        value = input,
                        loading = state.submitting,
                        broadcastMode = scannerState.effectiveMode == ScannerInputMode.IntentBroadcast,
                        onValueChange = { value ->
                            input = value
                            if (value.hasScanTerminator()) viewModel.scan(value.cleanScanInput())
                        },
                        onSubmit = { viewModel.scan(input.cleanScanInput()) },
                        onCamera = openCamera,
                    )
                }
                else -> {
                    item {
                        InboundLocationBand(
                            locationCode = workspace.draftBatch.locationCode,
                            boxCount = workspace.draftItems.size,
                            onCancel = viewModel::cancelDraft,
                        )
                    }
                    item {
                        InboundScanInput(
                            title = stringResource(R.string.inbound_scan_box),
                            value = input,
                            loading = state.submitting,
                            broadcastMode = scannerState.effectiveMode == ScannerInputMode.IntentBroadcast,
                            onValueChange = { value ->
                                input = value
                                if (value.hasScanTerminator()) viewModel.scan(value.cleanScanInput())
                            },
                            onSubmit = { viewModel.scan(input.cleanScanInput()) },
                            onCamera = openCamera,
                        )
                    }
                    if (workspace.draftItems.isNotEmpty()) {
                        item { Text(stringResource(R.string.inbound_current_boxes), color = InboundInk, fontWeight = FontWeight.Bold) }
                        items(workspace.draftItems, key = { it.requestId }) { item ->
                            InboundBoxRow(item = item, removable = true, onDelete = { viewModel.deleteDraftBox(item.requestId) })
                        }
                    }
                    item {
                        Button(
                            onClick = viewModel::requestFinish,
                            enabled = workspace.draftItems.isNotEmpty() && !state.submitting,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.inbound_finish_location))
                        }
                    }
                }
            }
        }
    }

    if (state.finishConfirmationOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFinish,
            title = { Text(stringResource(R.string.inbound_finish_location)) },
            text = {
                Text(
                    stringResource(
                        R.string.inbound_finish_confirmation,
                        workspace?.draftBatch?.locationCode.orEmpty(),
                        workspace?.draftItems?.size ?: 0,
                    ),
                )
            },
            confirmButton = { Button(onClick = viewModel::finishBatch) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissFinish) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (state.conflictDialogOpen) {
        workspace?.conflicts?.firstOrNull()?.let { conflict ->
            InboundConflictDialog(
                item = conflict,
                submitting = state.submitting,
                onUseLocal = { viewModel.overrideRemote(conflict.requestId) },
                onUseRemote = { viewModel.acceptRemote(conflict.requestId) },
                onDismiss = viewModel::dismissConflictDialog,
            )
        }
    }

    if (state.cameraOpen) {
        Dialog(onDismissRequest = viewModel::closeCamera, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = viewModel::scan,
                    modifier = Modifier.fillMaxSize(),
                    showSelectionDialogForMultiple = true,
                    resumeToken = state.cameraResumeToken,
                    selectionTitle = stringResource(if (workspace?.draftBatch == null) R.string.inbound_choose_location else R.string.inbound_choose_box),
                    selectionPrompt = stringResource(R.string.inbound_multiple_codes),
                    preferredBarcodeMatcher = if (workspace?.draftBatch != null) ::isLikelyBoxLabelQr else null,
                    preferredBarcodeWaitMillis = 800L,
                    noPreferredBarcodeTitle = stringResource(R.string.box_label_scan_not_detected),
                    noPreferredBarcodePrompt = stringResource(R.string.box_label_scan_retry_prompt),
                    retryScanLabel = stringResource(R.string.box_label_scan_retry),
                )
                IconButton(
                    onClick = viewModel::closeCamera,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun InboundSessionCard(session: InboundSessionSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, InboundStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warehouse, contentDescription = null, tint = InboundBrand)
                Spacer(Modifier.size(8.dp))
                Text(session.sessionCode, color = InboundInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InboundStatusPill(session.status)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.inbound_progress_value, session.inboundedCount + session.localAssignedCount, session.totalCount), color = InboundDeep)
                Text(stringResource(R.string.inbound_remaining_value, session.remainingCount), color = if (session.remainingCount == 0) InboundSuccess else InboundWarning)
            }
            if (!session.snapshotComplete) Text(stringResource(R.string.inbound_snapshot_not_ready), color = InboundWarning, style = MaterialTheme.typography.bodySmall)
            if (session.pendingCount > 0 || session.conflictCount > 0) {
                Text(stringResource(R.string.inbound_pending_conflict_value, session.pendingCount, session.conflictCount), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InboundProgressHeader(summary: InboundSessionSummary) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Warehouse, contentDescription = null, tint = InboundBrand)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(summary.sessionCode, color = InboundInk, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.inbound_session_progress), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
        }
        InboundStatusPill(summary.status)
    }
}

@Composable
private fun InboundMetrics(total: Int, inbounded: Int, remaining: Int, conflicts: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            stringResource(R.string.inbound_total) to total,
            stringResource(R.string.inbound_assigned) to inbounded.coerceAtMost(total),
            stringResource(R.string.inbound_remaining) to remaining,
            stringResource(R.string.inbound_conflicts) to conflicts,
        ).forEach { (label, value) ->
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, InboundStroke)) {
                Column(Modifier.padding(vertical = 9.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value.toString(), color = InboundDeep, fontWeight = FontWeight.Bold)
                    Text(label, color = InboundMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InboundSyncBand(summary: InboundSessionSummary, submitting: Boolean, onSync: () -> Unit) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Sync, contentDescription = null, tint = InboundBrand)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.inbound_sync_status), color = InboundInk, fontWeight = FontWeight.Bold)
            Text(
                if (summary.lastSyncAt > 0) stringResource(R.string.inbound_last_sync, formatInboundTime(summary.lastSyncAt)) else stringResource(R.string.inbound_never_synced),
                color = InboundMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onSync, enabled = !submitting) {
            if (submitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(5.dp))
            Text(stringResource(R.string.inbound_manual_sync))
        }
    }
    if (summary.pendingCount > 0 || summary.conflictCount > 0 || summary.rejectedCount > 0) {
        Text(
            stringResource(R.string.inbound_sync_counts, summary.pendingCount, summary.conflictCount, summary.rejectedCount),
            color = if (summary.conflictCount > 0 || summary.rejectedCount > 0) InboundWarning else InboundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InboundLocationBand(locationCode: String, boxCount: Int, onCancel: () -> Unit) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = InboundBrand)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(locationCode, color = InboundInk, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.inbound_location_box_count, boxCount), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = InboundDanger) }
    }
}

@Composable
private fun InboundScanInput(
    title: String,
    value: String,
    loading: Boolean,
    broadcastMode: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCamera: () -> Unit,
) = InboundCard {
    Text(title, color = InboundInk, fontWeight = FontWeight.Bold)
    if (broadcastMode) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = InboundBrand, modifier = Modifier.size(30.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (loading) R.string.inbound_processing else R.string.inbound_waiting_scanner), color = InboundMuted)
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !loading,
            trailingIcon = {
                IconButton(onClick = onSubmit, enabled = value.isNotBlank() && !loading) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.common_confirm), tint = InboundBrand)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
    }
    OutlinedButton(onClick = onCamera, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.CameraAlt, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.inbound_camera_scan))
    }
}

@Composable
private fun InboundBoxRow(item: LocalInboundItemEntity, removable: Boolean, onDelete: () -> Unit) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = InboundDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.labelCode, color = InboundInk, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.localLocationCode, color = InboundMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (removable) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete), tint = InboundDanger) }
    }
}

@Composable
private fun InboundConflictDialog(
    item: LocalInboundItemEntity,
    submitting: Boolean,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = InboundWarning)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.inbound_location_conflict), color = InboundInk, fontWeight = FontWeight.Bold)
                }
                Text(item.labelCode, color = InboundDeep, fontWeight = FontWeight.Bold)
                InboundValueRow(stringResource(R.string.inbound_local_location), item.localLocationCode)
                InboundValueRow(stringResource(R.string.inbound_remote_location), item.remoteLocationCode)
                Button(onClick = onUseLocal, enabled = !submitting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inbound_use_local_location))
                }
                OutlinedButton(onClick = onUseRemote, enabled = !submitting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.inbound_use_remote_location))
                }
                TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.inbound_resolve_later))
                }
            }
        }
    }
}

@Composable
private fun InboundValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = InboundMuted)
        Text(value.ifBlank { "-" }, color = InboundInk, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InboundCompleteBand() = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = InboundSuccess)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.inbound_session_complete), color = InboundSuccess, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InboundWaitingBand() = InboundCard {
    Text(stringResource(R.string.inbound_local_complete_waiting), color = InboundWarning, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.inbound_waiting_center_confirmation), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InboundNotice(text: String, error: Boolean, onDismiss: (() -> Unit)? = null) {
    val color = if (error) InboundDanger else InboundSuccess
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, modifier = Modifier.weight(1f))
        onDismiss?.let { IconButton(onClick = it, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = color) } }
    }
}

@Composable
private fun InboundCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, InboundStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun InboundStatusPill(status: String) {
    val (text, color) = when (status) {
        InboundSessionStatus.Complete.value -> stringResource(R.string.inbound_status_complete) to InboundSuccess
        InboundSessionStatus.ConflictPending.value -> stringResource(R.string.inbound_status_conflict) to InboundWarning
        InboundSessionStatus.LocalCompletePendingSync.value -> stringResource(R.string.inbound_status_pending_sync) to InboundWarning
        InboundSessionStatus.Failed.value -> stringResource(R.string.inbound_status_failed) to InboundDanger
        else -> stringResource(R.string.inbound_status_active) to InboundBrand
    }
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun InboundLoading() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun InboundEmpty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = InboundMuted) }

private fun String.hasScanTerminator(): Boolean = contains('\n') || contains('\r')

private fun String.cleanScanInput(): String = trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }

private fun formatInboundTime(value: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
