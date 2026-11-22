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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmStrokeSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionFile
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val listState = remember(scopeKey) { LazyListState(viewModel.scrollIndex, viewModel.scrollOffset) }
    val coroutine = rememberCoroutineScope()
    DisposableEffect(viewModel, listState) {
        onDispose { viewModel.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    key(scopeKey) { LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(scopeKey, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(15_000)
                viewModel.refreshIfStale()
            }
        }
    }
    Column(Modifier.fillMaxSize().background(FscmPageTop)) {
        PrimaryTabRow(selectedTabIndex = if (state.resolved) 1 else 0) {
            Tab(selected = !state.resolved, onClick = {
                viewModel.select(false)
                coroutine.launch { listState.scrollToItem(0) }
            }, text = { Text(if (state.countsLoaded) stringResource(R.string.oe_open_count, state.open) else stringResource(R.string.oe_open)) })
            Tab(selected = state.resolved, onClick = {
                viewModel.select(true)
                coroutine.launch { listState.scrollToItem(0) }
            }, text = { Text(if (state.countsLoaded) stringResource(R.string.oe_resolved_count, state.completed) else stringResource(R.string.oe_resolved)) })
        }
        ExceptionSnapshotStatus(state.snapshotAt, state.refreshStatus)
        ExceptionQuickFilters(state) { key, value ->
            viewModel.editFilter(key, value)
            viewModel.search()
            coroutine.launch { listState.scrollToItem(0) }
        }
        ExceptionSearchFields(state.draft, viewModel::editFilter, onSearch = { clear ->
            viewModel.search(clear)
            coroutine.launch { listState.scrollToItem(0) }
        })
        if (state.loading && !state.loadingMore && !state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.refresh(userInitiated = true) }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { message ->
                    item {
                        Text(message.resolve(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.oe_retry)) }
                    }
                }
                if (!state.loading && state.error == null && state.rows.isEmpty()) item { Text(stringResource(R.string.oe_empty), Modifier.padding(16.dp)) }
                items(state.rows, key = { it.id }) { report ->
                    ExceptionOrderListCard(report, state.timezone) { onOpen(report.id) }
                }
                if (state.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
        ExceptionMeasuresEditor(state.form.measures, { text -> viewModel.edit { it.copy(measures = text) } }, { editingMeasures = false }, required = state.form.problemType == "other")
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
                ExceptionSectionCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExceptionSectionTitle(stringResource(R.string.oe_order_details))
                        ExceptionCopyField(stringResource(R.string.oe_order), detail.order.number)
                        ExceptionOrderAmount(detail.order)
                        Text(if (detail.status == "resolved") stringResource(R.string.oe_resolved) else stringResource(R.string.oe_open), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.oe_warehouse_value, detail.warehouse.name.ifBlank { stringResource(R.string.oe_not_recorded) }))
                        detail.order.tracking.ifEmpty { listOf("") }.forEach { ExceptionCopyField(stringResource(R.string.oe_tracking), it) }
                        detail.skus.ifEmpty { listOf("") }.forEach { ExceptionCopyField(stringResource(R.string.oe_sku), it) }
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
            item { ExceptionSectionTitle(stringResource(R.string.oe_operations)) }
            if (detail.operations.isEmpty()) item { Text(stringResource(R.string.oe_no_operations), color = FscmInkSoft) }
            items(detail.operations, key = { "operation-${it.id}" }) { operation -> ExceptionOperationCard(operation) }
            item { ExceptionSectionTitle(stringResource(R.string.oe_rectification)) }
            if (detail.resolutions.isEmpty() && detail.handlingNote.isBlank() && (detail.status != "open" || !detail.canResolve)) item { Text(stringResource(R.string.oe_no_resolution), color = FscmInkSoft) }
            detail.resolutions.forEach { result ->
                item(key = "resolution-${result.id}") {
                    ExceptionSectionCard {
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
                            Text(stringResource(if (state.form.problemType == "other") R.string.oe_measures_required else R.string.oe_measures), fontWeight = FontWeight.Bold)
                            Card(onClick = { editingMeasures = true }, colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised), border = BorderStroke(1.dp, if (state.measuresError) MaterialTheme.colorScheme.error else FscmStrokeSoft), enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
                                Text(state.form.measures.ifBlank { stringResource(R.string.oe_measures_hint) }, Modifier.padding(16.dp), maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            if (state.measuresError) Text(stringResource(R.string.oe_measures_required_error), color = MaterialTheme.colorScheme.error)
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
                        Button(onClick = {
                            if (state.pending) {
                                viewModel.submit()
                            } else if (viewModel.validateForConfirmation()) {
                                confirming = true
                            }
                        }, enabled = !state.busy && state.failedUpload == null && (state.pending || detail.pendingCollaborators.isEmpty() || state.form.terminateCollaborations), modifier = Modifier.fillMaxWidth()) { Text(if (state.pending) stringResource(R.string.oe_check_result) else stringResource(R.string.oe_submit)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExceptionQuickFilters(state: ExceptionsListState, onSelect: (String, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("" to R.string.oe_all_dates, "yesterday" to R.string.oe_yesterday, "last_3_days" to R.string.oe_last_three).forEach { (key, label) ->
            FilterChip(
                selected = state.draft["date_range"].orEmpty() == key,
                onClick = { onSelect("date_range", key) },
                label = { Text(stringResource(label)) },
            )
        }
        Box {
            val selected = state.filterTypes.find { it.id.toString() == state.draft["type_id"] }
            FilterChip(selected = selected != null, onClick = { menu = true }, label = { Text(selected?.name ?: stringResource(R.string.oe_all_types)) })
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.oe_all_types)) }, onClick = {
                    menu = false
                    onSelect("type_id", "")
                })
                state.filterTypes.forEach { type ->
                    DropdownMenuItem(text = { Text(type.name) }, onClick = {
                        menu = false
                        onSelect("type_id", type.id.toString())
                    })
                }
            }
        }
    }
}

@Composable
internal fun ExceptionSearchFields(draft: Map<String, String>, onEdit: (String, String) -> Unit, onSearch: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        onSearch(false)
        keyboard?.hide()
        expanded = false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft["keyword"].orEmpty(), onValueChange = { onEdit("keyword", it) },
            label = { Text(stringResource(R.string.order_exceptions_title)) },
            placeholder = { Text(stringResource(R.string.oe_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FscmBrandMain),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { search() }) { Text(stringResource(R.string.oe_search)) }
            TextButton(onClick = {
                onSearch(true)
                keyboard?.hide()
            }) { Text(stringResource(R.string.oe_clear)) }
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.oe_hide_filters else R.string.oe_advanced_filters)) }
        }
        if (expanded) {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("order_no" to R.string.oe_order, "tracking_no" to R.string.oe_tracking, "sku" to R.string.oe_sku, "employee_code" to R.string.oe_employee).forEach { (key, label) ->
                    OutlinedTextField(draft[key].orEmpty(), { onEdit(key, it) }, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                }
            }
        }
    }
}

@Composable
internal fun ExceptionSectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised), border = BorderStroke(1.dp, FscmStrokeSoft)) { content() }
}

@Composable
internal fun ExceptionSectionTitle(title: String) {
    Text(title, color = FscmBrandDeep, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
internal fun ExceptionOperationCard(operation: WarehouseExceptionOperation) {
    ExceptionSectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(operation.stageName().ifBlank { stringResource(R.string.oe_not_recorded) }, color = FscmBrandDeep, fontWeight = FontWeight.Bold, modifier = Modifier.background(FscmBrandSoft, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            Text(operation.displayName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.oe_employee_value, operation.employeeCode.ifBlank { stringResource(R.string.oe_not_recorded) }), color = FscmInkSoft, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.oe_operation_time, operation.occurredAt?.takeIf { it.isNotBlank() } ?: stringResource(R.string.oe_time_missing, operation.date)), color = FscmInkSoft, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun formatExceptionSnapshotTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))
}.getOrDefault(value)

@Composable
internal fun ExceptionSnapshotStatus(snapshotAt: String?, refreshStatus: String) {
    snapshotAt?.let { timestamp ->
        Text(
            stringResource(R.string.oe_snapshot_updated, formatExceptionSnapshotTime(timestamp)),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FscmInkSoft,
        )
    }
    if (refreshStatus in listOf("refreshing", "building", "stale")) {
        Text(
            stringResource(if (refreshStatus == "stale") R.string.oe_snapshot_stale else R.string.oe_snapshot_refreshing),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FscmInkSoft,
        )
    }
}
