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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.google.samples.apps.nowinandroid.core.designsystem.component.NiaTab
import com.google.samples.apps.nowinandroid.core.designsystem.component.NiaTabRow
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.InboundDraftBoxDetail
import com.google.samples.apps.nowinandroid.fscm.data.InboundItemStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.InboundWorkspace
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundItemEntity
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundSnapshotEntity
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductBusiness
import com.google.samples.apps.nowinandroid.fscm.data.calculateInboundDraftMetrics
import com.google.samples.apps.nowinandroid.fscm.data.canStartInboundBatch
import com.google.samples.apps.nowinandroid.fscm.data.collectionBoxes
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
    val visibleSessions = state.visibleSessions
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(InboundBackground),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { error -> item { InboundNotice(error.resolve(), true) } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.inbound_select_session), color = InboundInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::load, enabled = !state.loading && !state.preparingOffline) {
                    if (state.preparingOffline) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.common_retry), tint = InboundBrand)
                    }
                }
            }
        }
        if (state.preparingOffline) {
            item { InboundNotice(stringResource(R.string.inbound_preparing_offline_data), false) }
        }
        item {
            InboundSessionFilters(
                selected = state.filter,
                activeCount = state.sessions.count { !it.isEffectivelyComplete() },
                completeCount = state.sessions.count(InboundSessionSummary::isEffectivelyComplete),
                onSelected = viewModel::setFilter,
            )
        }
        if (state.loading && visibleSessions.isEmpty()) {
            item { InboundLoading() }
        }
        if (!state.loading && visibleSessions.isEmpty() && state.error == null) {
            item {
                InboundEmpty(
                    stringResource(
                        when (state.filter) {
                            InboundSessionFilter.Active -> R.string.inbound_no_active_sessions
                            InboundSessionFilter.Complete -> R.string.inbound_no_completed_sessions
                        },
                    ),
                )
            }
        }
        items(visibleSessions, key = { it.sessionId }) { session ->
            InboundSessionCard(session = session, onClick = { onOpenSession(session.sessionId) })
        }
    }
}

@Composable
private fun InboundSessionFilters(
    selected: InboundSessionFilter,
    activeCount: Int,
    completeCount: Int,
    onSelected: (InboundSessionFilter) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InboundSessionFilter.entries.forEach { filter ->
            val count = when (filter) {
                InboundSessionFilter.Active -> activeCount
                InboundSessionFilter.Complete -> completeCount
            }
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = {
                    Text(
                        "${stringResource(
                            when (filter) {
                                InboundSessionFilter.Active -> R.string.inbound_status_active
                                InboundSessionFilter.Complete -> R.string.inbound_status_complete
                            },
                        )} ($count)",
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundSessionScreen(
    sessionId: Long,
    viewModel: InboundSessionViewModel = hiltViewModel(),
) {
    val crossOrderTasks by viewModel.crossOrder.tasks.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    LaunchedEffect(state.cameraResumeToken, state.changingLocation) { input = "" }
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
    val collection = workspace?.collectionBoxes.orEmpty()
    val summary = workspace?.summary?.let { original ->
        if (!original.snapshotComplete) {
            original
        } else {
            original.copy(
                totalCount = collection.count { it.eligible },
                inboundedCount = collection.count { it.collected },
                centerPendingCount = collection.count { it.eligible && !it.collected },
                exceptionCount = collection.count { it.exception },
            )
        }
    }
    val draftBatch = workspace?.draftBatch
    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = viewModel::manualSync) {
        if (workspace != null && draftBatch != null) {
            Column(Modifier.fillMaxSize().background(InboundBackground)) {
                CrossOrderReceivingPanel(viewModel.crossOrder)
                InboundSyncStatus(state, viewModel::updateOfflineReferences)
                state.error?.let { error ->
                    Box(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                        InboundNotice(error.resolve(), true, viewModel::consumeNotice)
                    }
                }
                state.message?.let { message ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        InboundNotice(message.resolve(), false, viewModel::consumeNotice)
                    }
                }
                if (state.referenceSyncing) InboundNotice(stringResource(R.string.inbound_reference_syncing), false)
                if (state.referenceSyncErrors.isNotEmpty()) InboundNotice(state.referenceSyncErrors.joinToString("；"), true)
                InboundActiveBatchContent(
                    crossOrderCount = crossOrderTasks.count { it.batchId == draftBatch.id },
                    workspace = workspace,
                    input = input,
                    loading = state.submitting || state.changingLocation || state.pendingRebind != null,
                    broadcastMode = scannerState.effectiveMode == ScannerInputMode.IntentBroadcast,
                    onValueChange = { value ->
                        input = value
                        if (value.hasScanTerminator()) viewModel.scan(value.cleanScanInput())
                    },
                    onSubmit = { viewModel.scan(input.cleanScanInput()) },
                    onCamera = openCamera,
                    onFinish = viewModel::requestFinish,
                    onRequestCancel = viewModel::requestCancelDraft,
                    onChangeLocation = viewModel::requestChangeLocation,
                    onDelete = viewModel::deleteDraftBox,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(InboundBackground),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { CrossOrderReceivingPanel(viewModel.crossOrder) }
                item { InboundSyncStatus(state, viewModel::updateOfflineReferences) }
                state.error?.let { error -> item { InboundNotice(error.resolve(), true, viewModel::consumeNotice) } }
                state.message?.let { message -> item { InboundNotice(message.resolve(), false, viewModel::consumeNotice) } }
                if (state.referenceSyncing) {
                    item { InboundNotice(stringResource(R.string.inbound_reference_syncing), false) }
                }
                if (state.referenceSyncErrors.isNotEmpty()) {
                    item { InboundNotice(state.referenceSyncErrors.joinToString("；"), true) }
                }
                if (state.loading && workspace == null) item { InboundLoading() }
                summary?.let { current ->
                    when {
                        current.canStartInboundBatch() -> {
                            if (current.rebindableCount > 0) {
                                item { InboundVirtualRebindBand(current.rebindableCount) }
                            }
                            item {
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
                        }
                        current.status == InboundSessionStatus.Complete.value -> item { InboundCompleteBand() }
                        else -> item { InboundWaitingBand() }
                    }
                    item {
                        FscmProgressOverview(
                            title = "库位采集进度",
                            totalLabel = stringResource(R.string.inbound_total),
                            pendingLabel = stringResource(R.string.collection_uncollected),
                            completedLabel = stringResource(R.string.collection_collected),
                            exceptionLabel = stringResource(R.string.inbound_metric_exception),
                            metrics = inboundProgress(current),
                            onPendingClick = viewModel::openPendingBoxes,
                            onCompletedClick = viewModel::openCompletedBoxes,
                            onExceptionClick = viewModel::openIssues,
                        )
                    }
                    item {
                        InboundSyncBand(
                            summary = current,
                            submitting = state.syncing || state.backgroundSyncing,
                            refreshingSnapshot = state.refreshingSnapshot,
                            syncProcessed = state.syncProcessed,
                            syncTotal = state.syncTotal,
                            onSync = viewModel::manualSync,
                            onOpenDetails = viewModel::openSyncDetails,
                        )
                    }
                    item {
                        Text(stringResource(R.string.collection_center_count, collection.count { it.centerConfirmed }), color = InboundMuted)
                        val drafts = collection.count { it.draft }
                        if (drafts > 0) Text(stringResource(R.string.collection_draft_count, drafts), color = InboundWarning)
                    }
                    item { PurchaseReceivingCard(sessionId) }
                }
            }
        }
    }

    state.pendingRebind?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRebind,
            title = { Text(stringResource(R.string.inbound_rebind_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.inbound_rebind_confirmation,
                        pending.confirmation.labelCode,
                        pending.confirmation.sourceLocationCode,
                        pending.confirmation.targetLocationCode,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRebind, enabled = !state.submitting) {
                    Text(stringResource(R.string.inbound_rebind_confirm), color = InboundDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRebind) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (state.changingLocation && workspace != null && draftBatch != null) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissChangeLocation) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.inbound_change_location), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.inbound_change_location_current, draftBatch.locationCode, workspace.draftItems.size))
                state.error?.let { Text(it.resolve(), color = InboundDanger) }
                InboundScanInput(
                    title = stringResource(R.string.inbound_new_location),
                    value = state.locationInput,
                    loading = state.submitting,
                    broadcastMode = false,
                    onValueChange = { value ->
                        viewModel.setLocationInput(value)
                        if (value.hasScanTerminator()) viewModel.scan(value.cleanScanInput())
                    },
                    onSubmit = { viewModel.scan(state.locationInput.cleanScanInput()) },
                    onCamera = openCamera,
                )
                state.candidateLocation?.let { location ->
                    Text(location.code, color = InboundBrand, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(location.warehouseName, location.zoneName, location.name).filter(String::isNotBlank).joinToString(" · "))
                    Text(
                        stringResource(
                            if (location.locationId == draftBatch.warehouseLocationId) R.string.inbound_same_location else R.string.inbound_change_location_hint,
                            workspace.draftItems.size,
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = viewModel::dismissChangeLocation, enabled = !state.submitting, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = viewModel::confirmChangeLocation,
                        enabled = !state.submitting && state.candidateLocation != null && state.candidateLocation?.locationId != draftBatch.warehouseLocationId,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.inbound_confirm_change_location))
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
                Text(stringResource(R.string.inbound_finish_confirmation, workspace?.draftBatch?.locationCode.orEmpty(), workspace?.draftItems?.size ?: 0))
            },
            confirmButton = { Button(onClick = viewModel::finishBatch, enabled = !state.submitting) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissFinish) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (state.cancelConfirmationOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCancelDraft,
            title = { Text(stringResource(R.string.inbound_cancel_location)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.inbound_cancel_confirmation,
                            workspace?.draftBatch?.locationCode.orEmpty(),
                            workspace?.draftItems?.size ?: 0,
                        ),
                    )
                    Text("跨单记录保留在待处理列表；已确认或已收货的跨单箱不随当前批次取消。")
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::cancelDraft) {
                    Text(stringResource(R.string.inbound_cancel_location), color = InboundDanger)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCancelDraft) { Text(stringResource(R.string.common_cancel)) } },
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

    if (state.issuesOpen && workspace != null) {
        InboundExceptionsSheet(
            workspace = workspace,
            onDismiss = viewModel::dismissIssues,
        )
    }

    if (state.pendingBoxesOpen && workspace != null) {
        InboundBoxDetailsSheet(
            workspace = workspace,
            completed = false,
            onDismiss = viewModel::dismissPendingBoxes,
        )
    }

    if (state.completedBoxesOpen && workspace != null) {
        InboundBoxDetailsSheet(
            workspace = workspace,
            completed = true,
            onDismiss = viewModel::dismissCompletedBoxes,
        )
    }

    if (state.syncDetailsOpen && workspace != null) {
        InboundSyncDetailsSheet(
            workspace = workspace,
            submitting = state.syncing || state.backgroundSyncing,
            syncProcessed = state.syncProcessed,
            syncTotal = state.syncTotal,
            onSync = viewModel::manualSync,
            onUseLocal = { requestId ->
                viewModel.dismissSyncDetails()
                viewModel.overrideRemote(requestId)
            },
            onUseRemote = { requestId ->
                viewModel.dismissSyncDetails()
                viewModel.acceptRemote(requestId)
            },
            onDismiss = viewModel::dismissSyncDetails,
        )
    }

    if (state.cameraOpen) {
        Dialog(onDismissRequest = viewModel::closeCamera, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = viewModel::scan,
                    modifier = Modifier.fillMaxSize(),
                    showSelectionDialogForMultiple = true,
                    resumeToken = state.cameraResumeToken,
                    selectionTitle = stringResource(if (workspace?.draftBatch == null || state.changingLocation) R.string.inbound_choose_location else R.string.inbound_choose_box),
                    selectionPrompt = stringResource(R.string.inbound_multiple_codes),
                    preferredBarcodeMatcher = if (workspace?.draftBatch != null && !state.changingLocation) {
                        viewModel::isKnownBoxLabelCode
                    } else {
                        viewModel::isKnownLocationCode
                    },
                    preferredBarcodeWaitMillis = 800L,
                    allowNonPreferredBarcodeFallback = workspace?.draftBatch == null || state.changingLocation,
                    noPreferredBarcodeTitle = stringResource(
                        if (workspace?.draftBatch == null || state.changingLocation) {
                            R.string.inbound_location_scan_not_detected
                        } else {
                            R.string.box_label_scan_not_detected
                        },
                    ),
                    noPreferredBarcodePrompt = stringResource(
                        if (workspace?.draftBatch == null || state.changingLocation) {
                            R.string.inbound_location_scan_retry_prompt
                        } else {
                            R.string.box_label_scan_retry_prompt
                        },
                    ),
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
private fun InboundActiveBatchContent(
    crossOrderCount: Int,
    workspace: InboundWorkspace,
    input: String,
    loading: Boolean,
    broadcastMode: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCamera: () -> Unit,
    onFinish: () -> Unit,
    onRequestCancel: () -> Unit,
    onChangeLocation: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val batch = workspace.draftBatch ?: return
    val details = workspace.draftBoxDetails.ifEmpty {
        workspace.draftItems.map { item -> InboundDraftBoxDetail(item = item, skuItems = emptyList()) }
    }
    val metrics = calculateInboundDraftMetrics(details)
    val listState = rememberLazyListState()
    LaunchedEffect(details.firstOrNull()?.item?.requestId) {
        if (details.isNotEmpty()) listState.scrollToItem(0)
    }
    Column(modifier.padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        InboundActiveLocationHeader(
            locationCode = batch.locationCode,
            locationName = batch.locationName,
            zoneName = batch.zoneName,
            boxCount = metrics.boxCount,
            skuCount = metrics.skuCount,
            totalQuantity = metrics.totalQuantity,
            quantityComplete = metrics.quantityComplete,
            finishEnabled = (details.isNotEmpty() || crossOrderCount > 0) && !loading,
            loading = loading,
            onFinish = onFinish,
            onRequestCancel = onRequestCancel,
            onChangeLocation = onChangeLocation,
        )
        Spacer(Modifier.height(8.dp))
        InboundCompactScanInput(
            value = input,
            loading = loading,
            broadcastMode = broadcastMode,
            onValueChange = onValueChange,
            onSubmit = onSubmit,
            onCamera = onCamera,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.inbound_current_boxes),
            color = InboundInk,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        if (details.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = InboundBrand, modifier = Modifier.size(30.dp))
                    Text(stringResource(R.string.inbound_empty_location_prompt), color = InboundMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(details, key = { it.item.requestId }) { detail ->
                    InboundActiveBoxRow(
                        detail = detail,
                        latest = detail.item.requestId == details.first().item.requestId,
                        onDelete = { onDelete(detail.item.requestId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InboundActiveLocationHeader(
    locationCode: String,
    locationName: String,
    zoneName: String,
    boxCount: Int,
    skuCount: Int,
    totalQuantity: Int,
    quantityComplete: Boolean,
    finishEnabled: Boolean,
    loading: Boolean,
    onFinish: () -> Unit,
    onRequestCancel: () -> Unit,
    onChangeLocation: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, InboundStroke),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = InboundBrand, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        locationCode,
                        color = InboundInk,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val locationDescription = listOf(zoneName, locationName).filter(String::isNotBlank).joinToString(" · ")
                    if (locationDescription.isNotBlank()) {
                        Text(
                            locationDescription,
                            color = InboundDeep,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = !loading) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more), tint = InboundMuted)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbound_cancel_location), color = InboundDanger) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = InboundDanger) },
                            onClick = {
                                menuExpanded = false
                                onRequestCancel()
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onChangeLocation, enabled = !loading) {
                    Text(stringResource(R.string.inbound_change_location))
                }
                Button(onClick = onFinish, enabled = finishEnabled, modifier = Modifier.height(40.dp)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(5.dp))
                        Text(stringResource(R.string.inbound_finish_short))
                    }
                }
            }
            HorizontalDivider(color = InboundStroke)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.inbound_box_count_short, boxCount), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.inbound_sku_count_short, skuCount), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (quantityComplete || boxCount == 0) {
                        stringResource(R.string.inbound_quantity_short, totalQuantity)
                    } else {
                        stringResource(R.string.inbound_quantity_pending)
                    },
                    color = if (quantityComplete || boxCount == 0) InboundMuted else InboundWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InboundCompactScanInput(
    value: String,
    loading: Boolean,
    broadcastMode: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCamera: () -> Unit,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, InboundStroke),
    ) {
        if (broadcastMode) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = InboundBrand, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(if (loading) R.string.inbound_processing else R.string.inbound_waiting_scanner),
                    color = InboundMuted,
                    modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = onCamera, enabled = !loading) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.inbound_camera_scan), tint = InboundBrand)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.inbound_scan_box)) },
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
                IconButton(onClick = onCamera, enabled = !loading) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.inbound_camera_scan), tint = InboundBrand)
                }
            }
        }
    }
}

@Composable
private fun InboundActiveBoxRow(
    detail: InboundDraftBoxDetail,
    latest: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (latest) Color(0xFFEEF6FF) else Color.White,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (latest) Color(0xFF91CAFF) else InboundStroke),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 9.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            detail.item.labelCode,
                            color = InboundInk,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (latest) {
                            Spacer(Modifier.size(7.dp))
                            Surface(color = InboundBrand.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    stringResource(R.string.inbound_latest_scan),
                                    color = InboundDeep,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.inbound_scanned_at, formatInboundTime(detail.item.scannedAt)),
                        color = InboundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete), tint = InboundDanger)
                }
            }
            if (detail.skuItems.isEmpty()) {
                Text(stringResource(R.string.inbound_sku_pending), color = InboundWarning, style = MaterialTheme.typography.bodySmall)
            } else {
                detail.skuItems.forEach { sku ->
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(sku.skuCode, color = InboundDeep, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val description = listOf(sku.skuName.ifBlank { sku.productName }, sku.spec).filter(String::isNotBlank).joinToString(" · ")
                            if (description.isNotBlank()) Text(description, color = InboundMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            sku.quantity?.let { stringResource(R.string.inbound_sku_quantity, it) }
                                ?: stringResource(R.string.inbound_sku_quantity_pending),
                            color = if (sku.quantity == null) InboundWarning else InboundInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                Column(Modifier.weight(1f)) {
                    Text(
                        session.consolidationOrderCode.ifBlank { session.consolidationOrderName }.ifBlank { "集运订单" },
                        color = InboundInk,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (session.consolidationOrderCode.isNotBlank() && session.consolidationOrderName.isNotBlank()) {
                        Text(
                            session.consolidationOrderName,
                            color = InboundDeep,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "入库单：${session.sessionCode}",
                        color = InboundMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InboundStatusPill(
                        if (session.isEffectivelyComplete()) {
                            InboundSessionStatus.Complete.value
                        } else {
                            session.status
                        },
                    )
                    if (session.snapshotComplete && session.locationCacheComplete) {
                        Text(
                            stringResource(R.string.inbound_offline_ready),
                            color = InboundBrand,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.inbound_progress_value, session.inboundedCount + session.localAssignedCount, session.totalCount), color = InboundDeep)
                Text(stringResource(R.string.inbound_remaining_value, session.remainingCount), color = if (session.remainingCount == 0) InboundSuccess else InboundWarning)
            }
            if (!session.snapshotComplete) Text(stringResource(R.string.inbound_snapshot_not_ready), color = InboundWarning, style = MaterialTheme.typography.bodySmall)
            if (!session.locationCacheComplete) {
                Text(stringResource(R.string.inbound_location_cache_not_ready), color = InboundWarning, style = MaterialTheme.typography.bodySmall)
            }
            if (session.pendingCount > 0 || session.conflictCount > 0) {
                Text(stringResource(R.string.inbound_pending_conflict_value, session.pendingCount, session.conflictCount), color = InboundMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (session.exceptionCount > 0) {
                Text(
                    stringResource(R.string.inbound_center_exception_count, session.exceptionCount),
                    color = InboundDanger,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun InboundSyncBand(
    summary: InboundSessionSummary,
    submitting: Boolean,
    refreshingSnapshot: Boolean,
    syncProcessed: Int,
    syncTotal: Int,
    onSync: () -> Unit,
    onOpenDetails: () -> Unit,
) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Sync, contentDescription = null, tint = InboundBrand)
        Spacer(Modifier.size(8.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpenDetails)
                .padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.inbound_sync_status), color = InboundInk, fontWeight = FontWeight.Bold)
            Text(
                when {
                    submitting && syncTotal > 0 -> "$syncProcessed / $syncTotal"
                    refreshingSnapshot -> stringResource(R.string.inbound_snapshot_refreshing)
                    summary.snapshotComplete && summary.locationCacheComplete -> stringResource(
                        R.string.inbound_offline_data_updated,
                        formatInboundTime(maxOf(summary.lastSyncAt, summary.locationCacheRefreshedAt)),
                    )
                    summary.snapshotComplete -> stringResource(R.string.inbound_location_cache_not_ready)
                    summary.lastSyncAt > 0 -> stringResource(R.string.inbound_last_sync, formatInboundTime(summary.lastSyncAt))
                    else -> stringResource(R.string.inbound_never_synced)
                },
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
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenDetails),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.inbound_sync_counts, summary.pendingCount, summary.conflictCount, summary.rejectedCount),
                color = if (summary.conflictCount > 0 || summary.rejectedCount > 0) InboundWarning else InboundMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = InboundWarning, modifier = Modifier.size(18.dp))
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboundExceptionsSheet(
    workspace: InboundWorkspace,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.inbound_center_exceptions),
                color = InboundInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                InboundIssueSectionTitle(
                    stringResource(R.string.inbound_center_exceptions),
                    workspace.summary.exceptionCount,
                    InboundDanger,
                )
            }
            if (workspace.centerExceptions.isEmpty()) {
                item { InboundIssueEmpty(stringResource(R.string.inbound_no_center_exceptions)) }
            } else {
                items(workspace.centerExceptions, key = { "center-${it.boxPlanId}" }) { item ->
                    InboundCenterExceptionRow(item)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboundBoxDetailsSheet(
    workspace: InboundWorkspace,
    completed: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            ReceivingProductProgressScreen(
                business = ReceivingProductBusiness.Inbound,
                documentId = workspace.summary.sessionId,
                viewModel = hiltViewModel(key = "collection-${workspace.summary.sessionId}-$completed"),
                initialFilter = if (completed) ReceivingProductFilter.All else ReceivingProductFilter.Pending,
            )
        }
    }
}
internal enum class InboundSyncDetailsTab {
    Pending,
    Conflict,
    Failed,
}

internal fun localSyncItemsForTab(
    items: List<LocalInboundItemEntity>,
    tab: InboundSyncDetailsTab,
): List<LocalInboundItemEntity> = items.filter { item ->
    when (tab) {
        InboundSyncDetailsTab.Pending -> item.status in setOf(InboundItemStatus.Pending.value, InboundItemStatus.Syncing.value)
        InboundSyncDetailsTab.Conflict -> item.status == InboundItemStatus.ConflictPending.value
        InboundSyncDetailsTab.Failed -> item.status == InboundItemStatus.Rejected.value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboundSyncDetailsSheet(
    workspace: InboundWorkspace,
    submitting: Boolean,
    syncProcessed: Int,
    syncTotal: Int,
    onSync: () -> Unit,
    onUseLocal: (String) -> Unit,
    onUseRemote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = InboundSyncDetailsTab.entries
    val selectedTab = tabs[selectedTabIndex]
    val visibleItems = localSyncItemsForTab(workspace.localSyncItems, selectedTab)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.inbound_local_sync_data),
                    color = InboundInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (submitting && syncTotal > 0) {
                    Text(
                        stringResource(R.string.inbound_sync_progress, syncProcessed, syncTotal),
                        color = InboundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(onClick = onSync, enabled = !submitting) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(5.dp))
                Text(stringResource(R.string.inbound_manual_sync))
            }
        }
        NiaTabRow(selectedTabIndex = selectedTabIndex, modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, tab ->
                val count = localSyncItemsForTab(workspace.localSyncItems, tab).size
                NiaTab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            stringResource(
                                when (tab) {
                                    InboundSyncDetailsTab.Pending -> R.string.inbound_sync_tab_pending
                                    InboundSyncDetailsTab.Conflict -> R.string.inbound_sync_tab_conflict
                                    InboundSyncDetailsTab.Failed -> R.string.inbound_sync_tab_failed
                                },
                                count,
                            ),
                        )
                    },
                )
            }
        }
        PullToRefreshBox(isRefreshing = submitting, onRefresh = onSync) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (visibleItems.isEmpty()) {
                    item { InboundIssueEmpty(stringResource(R.string.inbound_no_sync_items_for_status)) }
                } else {
                    items(visibleItems, key = { it.requestId }) { item ->
                        InboundLocalSyncIssueRow(
                            item = item,
                            submitting = submitting,
                            onUseLocal = { onUseLocal(item.requestId) },
                            onUseRemote = { onUseRemote(item.requestId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboundIssueSectionTitle(title: String, count: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().background(InboundBackground).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = InboundInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count.coerceAtLeast(0).toString(), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InboundCenterExceptionRow(item: LocalInboundSnapshotEntity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.labelCode.ifBlank { item.boxNo.ifBlank { item.boxUid } },
                color = InboundDeep,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(localizedStatusText(item.status), color = InboundDanger, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            item.exceptionReason.ifBlank { localizedStatusText(item.status) },
            color = InboundInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.receiptScanStatus.isNotBlank()) {
            InboundIssueMeta(stringResource(R.string.inbound_receipt_scan_status), localizedStatusText(item.receiptScanStatus))
        }
        if (item.locationCode.isNotBlank()) {
            InboundIssueMeta(stringResource(R.string.inbound_remote_location), item.locationCode)
        }
        if (item.remoteScannedBy.isNotBlank()) {
            InboundIssueMeta(stringResource(R.string.inbound_scanned_by), item.remoteScannedBy)
        }
        if (item.remoteScannedAt > 0) {
            InboundIssueMeta(stringResource(R.string.inbound_exception_time), formatInboundTime(item.remoteScannedAt))
        }
    }
    HorizontalDivider(color = InboundStroke)
}

@Composable
private fun InboundLocalSyncIssueRow(
    item: LocalInboundItemEntity,
    submitting: Boolean,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
) {
    val conflict = item.status == InboundItemStatus.ConflictPending.value
    val pending = item.status in setOf(InboundItemStatus.Pending.value, InboundItemStatus.Syncing.value)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.labelCode,
                color = InboundDeep,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    when {
                        pending -> R.string.inbound_status_pending_sync
                        conflict -> R.string.inbound_status_conflict
                        else -> R.string.inbound_status_failed
                    },
                ),
                color = when {
                    pending -> InboundBrand
                    conflict -> InboundWarning
                    else -> InboundDanger
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (item.errorMessage.isNotBlank()) {
            Text(item.errorMessage, color = InboundInk, style = MaterialTheme.typography.bodyMedium)
        }
        if (item.errorCode != 0) {
            InboundIssueMeta(stringResource(R.string.inbound_error_code), item.errorCode.toString())
        }
        InboundIssueMeta(stringResource(R.string.inbound_local_location), item.localLocationCode)
        InboundIssueMeta(stringResource(R.string.inbound_scanned_time), formatInboundTime(item.scannedAt))
        InboundIssueMeta(stringResource(R.string.inbound_batch_id), item.batchId)
        if (item.remoteLocationCode.isNotBlank()) {
            InboundIssueMeta(stringResource(R.string.inbound_remote_location), item.remoteLocationCode)
        }
        if (conflict) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUseRemote, enabled = !submitting, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.inbound_use_remote_location))
                }
                Button(onClick = onUseLocal, enabled = !submitting, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.inbound_use_local_location))
                }
            }
        }
    }
    HorizontalDivider(color = InboundStroke)
}

@Composable
private fun InboundIssueMeta(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = InboundMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(12.dp))
        Text(
            value.ifBlank { "-" },
            color = InboundInk,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InboundIssueEmpty(text: String) {
    Text(
        text,
        color = InboundMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
    )
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
        Text("库位采集完成，采购结果请查看下方记录", color = InboundSuccess, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InboundVirtualRebindBand(rebindableCount: Int) = InboundCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = InboundBrand)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                stringResource(R.string.inbound_virtual_rebind_available, rebindableCount),
                color = InboundBrand,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.inbound_virtual_rebind_hint),
                color = InboundMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
        InboundSessionStatus.Complete.value -> "库位已采集" to InboundSuccess
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

@Composable
private fun InboundSyncStatus(state: InboundSessionUiState, onUpdateReferences: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (state.syncing || state.loading) {
            Text(
                stringResource(
                    when (state.syncPhase) {
                        "upload" -> R.string.inbound_phase_upload
                        "recognition" -> R.string.inbound_phase_recognition
                        else -> R.string.inbound_phase_download
                    },
                ) + if (state.syncTotal > 0) " ${state.syncProcessed} / ${state.syncTotal}" else "",
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = onUpdateReferences, enabled = !state.referenceSyncing) {
            Text(stringResource(R.string.inbound_update_offline_references))
        }
    }
}
