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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.CaptureShot
import com.google.samples.apps.nowinandroid.fscm.data.CaptureTask
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.ProductCaptureRepository
import com.google.samples.apps.nowinandroid.fscm.data.ShippingScope
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ProductCaptureState(
    val rows: List<CaptureTask> = emptyList(),
    val task: CaptureTask? = null,
    val page: Int = 1,
    val total: Int = 0,
    val pending: Map<String, Boolean> = emptyMap(),
    val submitted: Boolean = false,
    val working: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class ProductCaptureViewModel @Inject constructor(
    private val repo: ProductCaptureRepository,
    private val scanner: ScannerManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProductCaptureState())
    val state = mutableState.asStateFlow()
    val scannerState = scanner.scannerState
    private var actor: ShippingScope? = null

    private fun run(onError: Int = R.string.capture_operation_error, action: suspend () -> Unit) {
        if (state.value.working) return
        mutableState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val wrongSku = error is FscmApiException && error.serverMessage == "capture_wrong_sku"
                mutableState.update { it.copy(error = if (wrongSku) R.string.capture_wrong_sku else onError) }
            } finally {
                mutableState.update { it.copy(working = false) }
            }
        }
    }

    fun list(status: String, page: Int = 1) = run { refreshList(status, page) }

    private suspend fun refreshList(status: String, page: Int) {
        val result = repo.tasks(status, page)
        mutableState.update { it.copy(rows = result.items.orEmpty(), page = page, total = result.total) }
    }

    suspend fun watchRetakes() {
        while (true) {
            delay(20_000)
            if (state.value.working || state.value.page != 1) continue
            try {
                refreshList("mine", 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next refresh keeps the visible list available during network loss.
            }
        }
    }

    private suspend fun refresh(id: Long) {
        val scope = repo.scope()
        val task = repo.detail(id)
        actor = scope
        repo.resume(scope, task)
        mutableState.update { it.copy(task = task, pending = repo.pending(scope, task), submitted = repo.submitted(scope, task)) }
    }

    fun open(id: Long) = run { refresh(id) }

    suspend fun poll(id: Long) {
        while (true) {
            delay(3500)
            val snapshot = state.value
            if (snapshot.task?.id == id && !snapshot.working && (snapshot.pending.values.any { !it } || ((snapshot.submitted || snapshot.task.submitRequested) && snapshot.task.status in setOf("capturing", "retake")) || snapshot.task.status == "review")) {
                try {
                    refresh(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { }
            }
        }
    }

    fun claim(id: Long) = run {
        repo.action(id, "claim")
        refresh(id)
    }

    fun scan(id: Long, value: String) = run {
        repo.action(id, "scan", value)
        refresh(id)
    }

    fun listen(owner: androidx.lifecycle.LifecycleOwner, id: Long) {
        scanner.pushOnScanListener { scan(id, it) }
        scanner.register(owner)
    }

    fun stopListening() {
        scanner.popOnScanListener()
    }

    fun draft(task: CaptureTask, shot: CaptureShot): File? = actor?.let { repo.draft(it, task, shot) }

    fun save(task: CaptureTask, shot: CaptureShot, file: File, saved: () -> Unit) = run(R.string.capture_upload_error) {
        val scope = requireNotNull(actor)
        repo.confirm(scope, task, shot, file)
        mutableState.update { it.copy(pending = repo.pending(scope, task)) }
        saved()
    }

    fun submit(task: CaptureTask) = run {
        repo.submit(requireNotNull(actor), task)
        mutableState.update { it.copy(submitted = true) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProductCaptureListScreen(onOpen: (Long) -> Unit, scopeKey: String) {
    val model: ProductCaptureViewModel = hiltViewModel(key = "capture-list-$scopeKey")
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("mine") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tab, lifecycle) {
        model.list(tab)
        if (tab == "mine") lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchRetakes() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("available" to R.string.capture_available, "mine" to R.string.capture_mine, "completed" to R.string.capture_completed).forEach { (key, label) ->
                FilterChip(selected = tab == key, enabled = !state.working, onClick = { tab = key }, label = { Text(stringResource(label)) })
            }
        }
        PullToRefreshBox(isRefreshing = state.working, onRefresh = { model.list(tab) }, modifier = Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                if (state.error != null) item { CaptureRetry(state.error!!) { model.list(tab, state.page) } }
                if (state.rows.isEmpty() && !state.working && state.error == null) item { Text(stringResource(R.string.capture_empty), Modifier.padding(16.dp)) }
                items(state.rows, key = { it.id }) { task ->
                    Card(onClick = { onOpen(task.id) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(task.imageUrl, contentDescription = null, modifier = Modifier.size(80.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.productName, style = MaterialTheme.typography.titleMedium)
                                Text(task.sku, color = MaterialTheme.colorScheme.primary)
                                Text(listOf(task.color, task.spec, task.warehouse).filter(String::isNotBlank).joinToString(" · "))
                                val groups = task.shots.filter { it.kind == "image" }.distinctBy { it.group }.size
                                Text(stringResource(R.string.capture_amount, groups, task.shots.count { it.kind == "video" }))
                                if (task.status == "retake") {
                                    val count = task.activeShots.distinctBy { it.group }.size
                                    Text(stringResource(R.string.capture_retake_count, count), color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text(stringResource(captureStatus(task.status)))
                                }
                            }
                        }
                    }
                }
                if (state.page > 1 || state.page * 30 < state.total) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.page > 1) OutlinedButton(onClick = { model.list(tab, state.page - 1) }) { Text(stringResource(R.string.capture_prev)) }
                            if (state.page * 30 < state.total) OutlinedButton(onClick = { model.list(tab, state.page + 1) }) { Text(stringResource(R.string.capture_next)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProductCaptureDetailScreen(id: Long, scopeKey: String) {
    val model: ProductCaptureViewModel = hiltViewModel(key = "capture-detail-$scopeKey-$id")
    val state by model.state.collectAsStateWithLifecycle()
    val task = state.task?.takeIf { it.id == id }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(id, owner) {
        model.open(id)
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.poll(id) }
    }
    val language = LocalConfiguration.current.locales[0].language
    var cameraScan by rememberSaveable(id) { mutableStateOf(false) }
    var cameraOpen by rememberSaveable(id) { mutableStateOf(false) }
    var cameraAction by remember { mutableStateOf("") }
    var preview by rememberSaveable(id) { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraFailure by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) {
            if (cameraAction == "scan") cameraScan = true else cameraOpen = true
        }
    }
    fun requestCamera(action: String) {
        cameraAction = action
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        } else if (action == "scan") {
            cameraScan = true
        } else {
            cameraOpen = true
        }
    }
    val scannerState by model.scannerState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val scanBuffer = remember(id) { StringBuilder() }
    val scanning = task?.status in setOf("capturing", "retake") && task?.scanned == false && !cameraScan
    val keyboardScan = scanning && scannerState.effectiveMode == ScannerInputMode.Keyboard
    ScannerInputScope(scanning && !state.working)
    LaunchedEffect(keyboardScan, state.working) {
        if (keyboardScan && !state.working) {
            focus.requestFocus()
        } else {
            scanBuffer.clear()
        }
    }
    DisposableEffect(scanning, owner, id) {
        if (scanning) model.listen(owner, id)
        onDispose { if (scanning) model.stopListening() }
    }
    if (cameraScan) {
        Dialog(onDismissRequest = { cameraScan = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BarcodeScanner(onBarcodeDetected = {
                cameraScan = false
                model.scan(id, it)
            }, modifier = Modifier.fillMaxSize())
        }
    }
    viewer?.let { (url, video) -> CapturePreview(url, video) { viewer = null } }
    if (task == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.error != null) CaptureRetry(state.error!!) { model.open(id) } else CircularProgressIndicator()
        }
        return
    }

    val complete = task.files.orEmpty().filter { it.round == task.round }.map { it.shot }.toSet()
    val active = task.activeShots
    val index = active.indexOfFirst { it.key !in complete && state.pending[it.key] != false }
    val shot = active.getOrNull(index)
    val draft = shot?.let { model.draft(task, it) }
    val instruction = shot?.instruction(language)
    if (cameraOpen && shot != null && draft != null) {
        ProductCaptureCamera(
            draft,
            shot.kind == "video",
            instruction?.steps.orEmpty(),
            onReady = {
                cameraOpen = false
                preview = true
            },
            onFailure = {
                cameraOpen = false
                cameraFailure = true
            },
            onClose = { cameraOpen = false },
        )
    }
    val scanInput = if (keyboardScan) {
        Modifier.onPreviewKeyEvent { key ->
            val event = key.nativeKeyEvent
            if (event.action != android.view.KeyEvent.ACTION_DOWN || state.working) return@onPreviewKeyEvent false
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_TAB -> {
                    if (scanBuffer.isEmpty()) return@onPreviewKeyEvent false
                    model.scan(id, scanBuffer.toString())
                    scanBuffer.clear()
                    true
                }
                else -> {
                    val character = event.unicodeChar.toChar()
                    if (character.isISOControl() || scanBuffer.length >= 100) return@onPreviewKeyEvent false
                    scanBuffer.append(character)
                    true
                }
            }
        }.focusRequester(focus).focusable()
    } else {
        Modifier
    }
    val showingPreview = preview && draft?.exists() == true
    Column(Modifier.fillMaxSize().then(scanInput).padding(horizontal = 16.dp)) {
        key(task.round, shot?.key, showingPreview) {
            if (showingPreview && shot?.kind == "video" && draft != null) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.capture_video_preview), style = MaterialTheme.typography.titleMedium)
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    ProductCaptureVideoPlayer(Uri.fromFile(draft), modifier = Modifier.weight(1f), expand = true)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(task.productName, style = MaterialTheme.typography.titleLarge)
                        Text(listOf(task.sku, task.color, task.spec).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                        Text(task.warehouse)
                        Text(stringResource(captureStatus(task.status)))
                    }
                    state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
                    if (permissionDenied) item { Text(stringResource(R.string.capture_permission), color = MaterialTheme.colorScheme.error) }
                    if (cameraFailure) item { Text(stringResource(R.string.capture_camera_error), color = MaterialTheme.colorScheme.error) }

                    if (task.status == "capturing" || task.status == "retake") {
                        when {
                            !task.scanned -> item { Text(stringResource(R.string.capture_scan_help, task.sku)) }
                            shot != null && instruction != null && draft != null -> {
                                val thisGroup = active.filter { it.group == shot.group }
                                val groups = active.distinctBy { it.group }
                                val previous = task.files.orEmpty().firstOrNull { it.shot == shot.key && it.round < task.round }
                                item {
                                    Text(stringResource(R.string.capture_group, groups.indexOfFirst { it.group == shot.group } + 1, groups.size, thisGroup.indexOf(shot) + 1, thisGroup.size))
                                    Text(instruction.name, style = MaterialTheme.typography.titleLarge)
                                    task.retakes[shot.key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (previous?.url != null) OutlinedButton(onClick = { viewer = previous.url to (shot.kind == "video") }) { Text(stringResource(R.string.capture_previous_file)) }
                                    if (state.pending[shot.key] == true) Text(stringResource(R.string.capture_upload_error), color = MaterialTheme.colorScheme.error)
                                }
                                item {
                                    if (showingPreview) {
                                        val previewUri = Uri.fromFile(draft).toString()
                                        AsyncImage(draft, null, Modifier.fillMaxWidth().height(200.dp).clickable { viewer = previewUri to false })
                                    } else {
                                        CaptureReference(task, shot, instruction) { example -> viewer = example to (shot.kind == "video") }
                                    }
                                }
                            }
                            state.submitted || task.submitRequested -> item { Text(stringResource(R.string.capture_background)) }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            when (task.status) {
                "available" -> CapturePrimary(R.string.capture_claim, !state.working) { model.claim(id) }
                "capturing", "retake" -> when {
                    !task.scanned -> CapturePrimary(R.string.capture_scan, !state.working) { requestCamera("scan") }
                    shot != null && draft != null -> {
                        if (showingPreview) {
                            CapturePrimary(if (shot.kind == "video") R.string.capture_use_video else R.string.capture_use_next, !state.working) {
                                model.save(task, shot, draft) { preview = false }
                            }
                            OutlinedButton(
                                onClick = {
                                    preview = false
                                    requestCamera("capture")
                                },
                                enabled = !state.working,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.capture_reshoot)) }
                        } else {
                            CapturePrimary(if (shot.kind == "video") R.string.capture_record else R.string.capture_photo, !state.working) {
                                cameraFailure = false
                                requestCamera("capture")
                            }
                        }
                    }
                    !state.submitted && !task.submitRequested -> CapturePrimary(R.string.capture_submit, !state.working) { model.submit(task) }
                }
            }
        }
    }
}

@Composable
private fun CaptureReference(
    task: CaptureTask,
    shot: CaptureShot,
    instructions: com.google.samples.apps.nowinandroid.fscm.data.CaptureInstructions,
    openExample: (String) -> Unit,
) {
    val key = shot.exampleKey.ifBlank { shot.key }
    val isVideo = shot.kind == "video"
    val extension = if (isVideo) "mp4" else "png"
    val example = shot.referenceUrl.ifBlank { "file:///android_asset/product-capture/${task.template}/$key.$extension" }
    if (isVideo) {
        OutlinedButton(onClick = { openExample(example) }) { Text(stringResource(R.string.capture_watch)) }
    } else {
        AsyncImage(example, stringResource(R.string.capture_example), Modifier.fillMaxWidth().height(200.dp).clickable { openExample(example) })
    }
    Text(instructions.action)
    Text(instructions.check, style = MaterialTheme.typography.bodyMedium)
    instructions.steps.forEachIndexed { i, step -> Text(stringResource(R.string.capture_step, i * 3, i * 3 + 3, step)) }
}

@Composable
private fun CapturePreview(url: String, video: Boolean, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (video) {
                    ProductCaptureVideoPlayer(Uri.parse(url))
                } else {
                    var scale by remember { mutableFloatStateOf(1f) }
                    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 5f) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).transformable(transform), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            url,
                            null,
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                }
                OutlinedButton(onClick = dismiss) { Text(stringResource(R.string.capture_close)) }
            }
        }
    }
}

@Composable
private fun CapturePrimary(label: Int, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(label)) }
}

@Composable
private fun CaptureRetry(error: Int, retry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text(stringResource(R.string.capture_retry)) }
    }
}

private fun captureStatus(status: String): Int = when (status) {
    "available" -> R.string.capture_available
    "capturing" -> R.string.capture_in_progress
    "retake" -> R.string.capture_retake
    "review" -> R.string.capture_review
    "completed" -> R.string.capture_completed
    else -> R.string.capture_title
}
