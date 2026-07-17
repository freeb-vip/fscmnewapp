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
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientError
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientException
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.InboundWorkspace
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundRepository
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboundSessionListUiState(
    val loading: Boolean = false,
    val sessions: List<InboundSessionSummary> = emptyList(),
    val error: UiText? = null,
)

@HiltViewModel
class InboundSessionListViewModel @Inject constructor(
    private val repository: LocalInboundRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InboundSessionListUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.sessionSummaries() }
            .onSuccess { sessions -> _state.value = InboundSessionListUiState(sessions = sessions) }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.inboundUiText()) } }
    }
}

data class InboundSessionUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val workspace: InboundWorkspace? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    val cameraOpen: Boolean = false,
    val cameraResumeToken: Long = 0,
    val finishConfirmationOpen: Boolean = false,
    val conflictDialogOpen: Boolean = false,
)

@HiltViewModel
class InboundSessionViewModel @Inject constructor(
    private val repository: LocalInboundRepository,
    private val syncScheduler: LocalInboundSyncScheduler,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(InboundSessionUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )
    private var sessionId: Long = 0

    fun load(id: Long) {
        sessionId = id
        viewModelScope.launch {
            _state.update { it.copy(loading = true, workspace = null, error = null) }
            runCatching { repository.prepareSession(id) }
                .onSuccess { workspace -> _state.update { it.copy(loading = false, workspace = workspace) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.inboundUiText()) } }
        }
    }

    fun scan(rawCode: String) {
        if (rawCode.isBlank() || sessionId == 0L || _state.value.loading || _state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, message = null) }
            val workspace = _state.value.workspace
            val result = runCatching {
                if (workspace?.draftBatch == null) {
                    repository.startBatch(sessionId, rawCode)
                    repository.workspace(sessionId)
                } else {
                    repository.addBox(workspace.draftBatch.id, rawCode)
                    repository.workspace(sessionId)
                }
            }
            result.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        submitting = false,
                        workspace = refreshed,
                        message = UiText.Resource(
                            if (workspace?.draftBatch == null) R.string.inbound_location_locked else R.string.inbound_box_saved,
                        ),
                        cameraResumeToken = it.cameraResumeToken + 1,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        submitting = false,
                        error = error.inboundUiText(),
                        cameraResumeToken = it.cameraResumeToken + 1,
                    )
                }
            }
        }
    }

    fun requestFinish() = _state.update { it.copy(finishConfirmationOpen = true) }

    fun dismissFinish() = _state.update { it.copy(finishConfirmationOpen = false) }

    fun finishBatch() {
        val batch = _state.value.workspace?.draftBatch ?: return
        val count = _state.value.workspace?.draftItems?.size ?: 0
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, finishConfirmationOpen = false, error = null) }
            runCatching { repository.finishBatch(batch.id) }
                .onSuccess { workspace ->
                    syncScheduler.enqueueSoon()
                    _state.update {
                        it.copy(
                            submitting = false,
                            workspace = workspace,
                            message = UiText.Resource(R.string.inbound_location_batch_finished, listOf(count)),
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.inboundUiText()) } }
        }
    }

    fun deleteDraftBox(requestId: String) = viewModelScope.launch {
        repository.deleteDraftBox(requestId)
        reloadWorkspace()
    }

    fun cancelDraft() {
        val batch = _state.value.workspace?.draftBatch ?: return
        viewModelScope.launch {
            repository.cancelDraft(batch.id)
            _state.update { it.copy(workspace = repository.workspace(sessionId), message = UiText.Resource(R.string.inbound_batch_cancelled)) }
        }
    }

    fun manualSync() {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, message = null) }
            runCatching { repository.syncSessionManually(sessionId) }
                .onSuccess { workspace ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            workspace = workspace,
                            conflictDialogOpen = workspace.conflicts.isNotEmpty(),
                            message = if (workspace.conflicts.isEmpty()) UiText.Resource(R.string.inbound_sync_finished) else null,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.inboundUiText()) } }
        }
    }

    fun acceptRemote(requestId: String) = resolveConflict {
        repository.acceptRemote(requestId)
    }

    fun overrideRemote(requestId: String) = resolveConflict {
        repository.overrideRemote(requestId)
    }

    fun dismissConflictDialog() = _state.update { it.copy(conflictDialogOpen = false) }

    fun openCamera() = _state.update { it.copy(cameraOpen = true, cameraResumeToken = it.cameraResumeToken + 1) }

    fun closeCamera() = _state.update { it.copy(cameraOpen = false) }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }

    fun registerScanner(owner: LifecycleOwner) {
        scannerManager.pushOnScanListener(::scan)
        scannerManager.register(owner)
    }

    fun unregisterScanner() = scannerManager.popOnScanListener()

    private fun resolveConflict(block: suspend () -> InboundWorkspace) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { block() }
                .onSuccess { workspace ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            workspace = workspace,
                            conflictDialogOpen = workspace.conflicts.isNotEmpty(),
                            message = if (workspace.conflicts.isEmpty()) UiText.Resource(R.string.inbound_conflicts_resolved) else null,
                        )
                    }
                }
                .onFailure { error ->
                    val refreshed = runCatching { repository.workspace(sessionId) }.getOrNull()
                    _state.update {
                        it.copy(
                            submitting = false,
                            workspace = refreshed ?: it.workspace,
                            conflictDialogOpen = true,
                            error = error.inboundUiText(),
                        )
                    }
                }
        }
    }

    private suspend fun reloadWorkspace() {
        _state.update { it.copy(workspace = repository.workspace(sessionId)) }
    }
}

private fun Throwable.inboundUiText(): UiText = when (this) {
    is InboundClientException -> UiText.Resource(
        when (reason) {
            InboundClientError.SnapshotUnavailable -> R.string.inbound_snapshot_required
            InboundClientError.InvalidLocation -> R.string.inbound_invalid_location
            InboundClientError.SessionComplete -> R.string.inbound_session_already_complete
            InboundClientError.BatchNotFound -> R.string.inbound_batch_not_found
            InboundClientError.EmptyBatch -> R.string.inbound_empty_batch
            InboundClientError.InvalidBoxLabel -> R.string.inbound_invalid_box_label
            InboundClientError.BoxNotInSession -> R.string.inbound_box_not_in_session
            InboundClientError.BoxNotEligible -> R.string.inbound_box_not_eligible
            InboundClientError.BoxAlreadyAssigned -> R.string.inbound_box_already_assigned
            InboundClientError.BoxAlreadyScanned -> R.string.inbound_box_already_scanned
            InboundClientError.ConflictNotFound -> R.string.inbound_conflict_not_found
            InboundClientError.OfflineSyncUnavailable -> R.string.inbound_sync_offline
        },
    )
    is FscmApiException -> UiText.Dynamic(serverMessage ?: message.orEmpty())
    else -> UiText.Dynamic(message ?: "Unknown error")
}
