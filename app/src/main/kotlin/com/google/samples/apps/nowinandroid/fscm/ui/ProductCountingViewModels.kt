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

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountEntryEntity
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountResolvedLabel
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountScanResult
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountTaskStatus
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountTaskSummary
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingMixedProductException
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingOfflineMappingMissingException
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingOnlineRequiredException
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingProductMissingException
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingRepository
import com.google.samples.apps.nowinandroid.fscm.data.ProductCountingUnrecognizedException
import com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProductCountingTaskFilter {
    InProgress,
    Completed,
}

data class ProductCountingListUiState(
    val tasks: List<ProductCountTaskSummary> = emptyList(),
    val filter: ProductCountingTaskFilter = ProductCountingTaskFilter.InProgress,
    val loading: Boolean = true,
    val creating: Boolean = false,
    val error: UiText? = null,
) {
    val visibleTasks: List<ProductCountTaskSummary>
        get() = tasks.filter {
            when (filter) {
                ProductCountingTaskFilter.InProgress -> it.task.status == ProductCountTaskStatus.InProgress.value
                ProductCountingTaskFilter.Completed -> it.task.status == ProductCountTaskStatus.Completed.value
            }
        }
}

@HiltViewModel
class ProductCountingListViewModel @Inject constructor(
    private val repository: ProductCountingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductCountingListUiState())
    val state = _state.asStateFlow()
    private val _openTask = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openTask = _openTask.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.observeTasks() }
                .onSuccess { flow ->
                    flow.collect { tasks -> _state.update { it.copy(tasks = tasks, loading = false) } }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.asUiText()) }
                }
        }
        refresh()
    }

    fun setFilter(filter: ProductCountingTaskFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            runCatching { repository.refresh() }
                .onFailure { error ->
                    if (_state.value.tasks.isEmpty()) _state.update { it.copy(error = error.asUiText(), loading = false) }
                }
        }
    }

    fun createTask() {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            runCatching { repository.createTask() }
                .onSuccess { taskId ->
                    _state.update { it.copy(creating = false) }
                    _openTask.emit(taskId)
                }
                .onFailure { error -> _state.update { it.copy(creating = false, error = error.asUiText()) } }
        }
    }
}

data class ProductCountingDetailUiState(
    val task: ProductCountTaskSummary? = null,
    val entries: List<ProductCountEntryEntity> = emptyList(),
    val loading: Boolean = true,
    val processing: Boolean = false,
    val editable: Boolean = false,
    val error: UiText? = null,
    val success: UiText? = null,
    val pendingDuplicate: ProductCountResolvedLabel? = null,
    val resetConfirmationVisible: Boolean = false,
)

@HiltViewModel
class ProductCountingDetailViewModel @Inject constructor(
    private val repository: ProductCountingRepository,
    private val scannerManager: ScannerManager,
    private val scanFeedback: ReceivingScanFeedback,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductCountingDetailUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )
    private val scanQueue = Channel<String>(capacity = 32)
    private var loadedTaskId = ""
    private var lastSubmittedScanCode = ""
    private var lastSubmittedScanAtMillis = 0L
    private var taskJob: Job? = null
    private var entriesJob: Job? = null

    init {
        viewModelScope.launch {
            for (rawCode in scanQueue) processScan(rawCode)
        }
    }

    fun load(taskId: String) {
        if (taskId == loadedTaskId) return
        loadedTaskId = taskId
        lastSubmittedScanCode = ""
        lastSubmittedScanAtMillis = 0L
        taskJob?.cancel()
        taskJob = viewModelScope.launch {
            runCatching { repository.observeTask(taskId) }
                .onSuccess { flow ->
                    flow.collect { task ->
                        _state.update {
                            it.copy(
                                task = task,
                                loading = task == null,
                                editable = task?.let(repository::isCurrentEditor) == true &&
                                    task.task.status == ProductCountTaskStatus.InProgress.value,
                            )
                        }
                        val generation = task?.task?.resetGeneration ?: return@collect
                        entriesJob?.cancel()
                        entriesJob = launch {
                            repository.observeEntries(taskId, generation).collect { entries ->
                                _state.update { it.copy(entries = entries) }
                            }
                        }
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.asUiText()) } }
        }
        viewModelScope.launch { repository.refreshTask(taskId) }
    }

    fun submit(rawCode: String) {
        val scanCode = rawCode.trim()
        if (scanCode.isBlank()) return
        val now = System.currentTimeMillis()
        if (scanCode == lastSubmittedScanCode && now - lastSubmittedScanAtMillis in 0..SCAN_EVENT_DEDUP_WINDOW_MS) {
            return
        }
        lastSubmittedScanCode = scanCode
        lastSubmittedScanAtMillis = now
        val current = _state.value
        if (!current.editable) {
            scanFeedback.failure()
            _state.update { it.copy(error = UiText.Resource(R.string.product_counting_read_only_error)) }
            return
        }
        if (current.pendingDuplicate != null) {
            scanFeedback.failure()
            _state.update { it.copy(error = UiText.Resource(R.string.product_counting_resolve_duplicate_first)) }
            return
        }
        if (!scanQueue.trySend(rawCode).isSuccess) {
            scanFeedback.failure()
            _state.update { it.copy(error = UiText.Resource(R.string.product_counting_queue_full)) }
        }
    }

    fun confirmDuplicate() {
        val duplicate = _state.value.pendingDuplicate ?: return
        val taskId = loadedTaskId
        viewModelScope.launch {
            _state.update { it.copy(processing = true, error = null) }
            runCatching { repository.confirmDuplicate(taskId, duplicate) }
                .onSuccess(::handleScanResult)
                .onFailure(::handleFailure)
        }
    }

    fun cancelDuplicate() {
        _state.update { it.copy(pendingDuplicate = null, error = null) }
    }

    fun requestReset() {
        if (_state.value.editable && !_state.value.processing) {
            _state.update { it.copy(resetConfirmationVisible = true) }
        }
    }

    fun dismissReset() {
        _state.update { it.copy(resetConfirmationVisible = false) }
    }

    fun confirmReset() {
        _state.update { it.copy(resetConfirmationVisible = false) }
        resetTask()
    }

    private fun resetTask() {
        val taskId = loadedTaskId
        viewModelScope.launch {
            _state.update { it.copy(processing = true, error = null, success = null, pendingDuplicate = null) }
            runCatching { repository.reset(taskId) }
                .onSuccess { changed ->
                    _state.update {
                        it.copy(
                            processing = false,
                            entries = if (changed) emptyList() else it.entries,
                            success = if (changed) UiText.Resource(R.string.product_counting_reset_done) else null,
                            error = if (changed) null else UiText.Resource(R.string.product_counting_reset_failed),
                        )
                    }
                }
                .onFailure(::handleFailure)
        }
    }

    fun complete() {
        if ((_state.value.task?.boxCount ?: 0) <= 0) return
        changeStatus(repository::complete, R.string.product_counting_completed_message)
    }

    fun upload() {
        val taskId = loadedTaskId
        viewModelScope.launch {
            _state.update { it.copy(processing = true, error = null, success = null) }
            runCatching { repository.upload(taskId) }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            processing = false,
                            success = UiText.Resource(
                                if (response.validationWarnings.isEmpty()) {
                                    R.string.product_counting_upload_success
                                } else {
                                    R.string.product_counting_upload_warning
                                },
                            ),
                        )
                    }
                }
                .onFailure(::handleFailure)
        }
    }

    fun reopen() {
        changeStatus(repository::reopen, R.string.product_counting_reopened_message)
    }

    fun clearMessage() {
        _state.update { it.copy(error = null, success = null) }
    }

    fun togglePdaMode() {
        scannerManager.applyInputMode(if (scannerManager.scannerState.value.effectiveMode == com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode.IntentBroadcast) com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode.Keyboard else com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode.IntentBroadcast)
    }

    fun registerScanner(owner: LifecycleOwner) {
        scannerManager.pushOnScanListener(::submit)
        scannerManager.register(owner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }

    private suspend fun processScan(rawCode: String) {
        _state.update { it.copy(processing = true, error = null, success = null) }
        runCatching { repository.scan(loadedTaskId, rawCode) }
            .onSuccess(::handleScanResult)
            .onFailure(::handleFailure)
    }

    private fun handleScanResult(result: ProductCountScanResult) {
        when (result) {
            is ProductCountScanResult.Accepted -> {
                scanFeedback.success()
                _state.update {
                    it.copy(
                        processing = false,
                        pendingDuplicate = null,
                        error = null,
                        success = UiText.Resource(R.string.product_counting_scan_accepted, listOf(result.boxCount)),
                    )
                }
            }
            is ProductCountScanResult.Duplicate -> {
                scanFeedback.failure()
                _state.update { it.copy(processing = false, pendingDuplicate = result.resolved, error = null) }
            }
            is ProductCountScanResult.ProductMismatch -> {
                scanFeedback.failure()
                _state.update {
                    it.copy(
                        processing = false,
                        error = UiText.Resource(
                            R.string.product_counting_product_mismatch,
                            listOf(result.expectedProduct, result.actualProduct),
                        ),
                    )
                }
            }
            ProductCountScanResult.TaskCompleted -> failWith(R.string.product_counting_task_completed_error)
            ProductCountScanResult.NotEditor -> failWith(R.string.product_counting_not_editor_error)
            ProductCountScanResult.MissingTask -> failWith(R.string.product_counting_task_missing_error)
        }
    }

    private fun handleFailure(error: Throwable) {
        scanFeedback.failure()
        _state.update { it.copy(processing = false, error = error.asUiText()) }
    }

    private fun failWith(message: Int) {
        scanFeedback.failure()
        _state.update { it.copy(processing = false, error = UiText.Resource(message)) }
    }

    private companion object {
        // A keyboard-wedge scan may emit both a terminator text change and IME Done.
        const val SCAN_EVENT_DEDUP_WINDOW_MS = 600L
    }

    private fun changeStatus(operation: suspend (String) -> Boolean, successMessage: Int) {
        val taskId = loadedTaskId
        viewModelScope.launch {
            _state.update { it.copy(processing = true, error = null, success = null) }
            runCatching { operation(taskId) }
                .onSuccess { changed ->
                    _state.update {
                        it.copy(
                            processing = false,
                            success = if (changed) UiText.Resource(successMessage) else null,
                            error = if (changed) null else UiText.Resource(R.string.product_counting_status_change_failed),
                        )
                    }
                }
                .onFailure(::handleFailure)
        }
    }
}

private fun Throwable.asUiText(): UiText = when (this) {
    is ProductCountingOfflineMappingMissingException -> UiText.Resource(R.string.product_counting_offline_mapping_missing)
    is ProductCountingMixedProductException -> UiText.Resource(R.string.product_counting_mixed_product)
    is ProductCountingUnrecognizedException -> UiText.Resource(R.string.product_counting_unrecognized_label)
    is ProductCountingProductMissingException -> UiText.Resource(R.string.product_counting_product_mapping_missing)
    is ProductCountingOnlineRequiredException -> UiText.Resource(R.string.product_counting_reopen_online_required)
    else -> UiText.Dynamic(message?.takeIf(String::isNotBlank) ?: "Product counting failed")
}
