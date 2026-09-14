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
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelMappingRepository
import com.google.samples.apps.nowinandroid.fscm.data.CrossOrderReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientError
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientException
import com.google.samples.apps.nowinandroid.fscm.data.InboundItemStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundRebindConfirmation
import com.google.samples.apps.nowinandroid.fscm.data.InboundRebindConfirmationRequired
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionStatus
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.InboundWorkspace
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundRepository
import com.google.samples.apps.nowinandroid.fscm.data.LocalWarehouseLocationEntity
import com.google.samples.apps.nowinandroid.fscm.data.inboundStandardCode
import com.google.samples.apps.nowinandroid.fscm.data.isCompletedPhysicalInbound
import com.google.samples.apps.nowinandroid.fscm.data.isKnownMappedBoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.isPendingPhysicalInbound
import com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboundSessionListUiState(
    val loading: Boolean = false,
    val preparingOffline: Boolean = false,
    val sessions: List<InboundSessionSummary> = emptyList(),
    val filter: InboundSessionFilter = InboundSessionFilter.Active,
    val error: UiText? = null,
) {
    val visibleSessions: List<InboundSessionSummary>
        get() = filterInboundSessions(sessions, filter)
}

enum class InboundSessionFilter {
    Active,
    Complete,
}

internal fun filterInboundSessions(
    sessions: List<InboundSessionSummary>,
    filter: InboundSessionFilter,
): List<InboundSessionSummary> = sessions.filter { session ->
    val complete = session.isEffectivelyComplete()
    when (filter) {
        InboundSessionFilter.Active -> !complete
        InboundSessionFilter.Complete -> complete
    }
}

internal fun InboundSessionSummary.isEffectivelyComplete(): Boolean =
    // The inbound stage status is authoritative for list classification.
    // Local snapshots may not be downloaded yet for historical sessions.
    status == InboundSessionStatus.Complete.value

@HiltViewModel
class InboundSessionListViewModel @Inject constructor(
    private val repository: LocalInboundRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InboundSessionListUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.sessionSummaries(refreshRemote = true) }
            .onSuccess { sessions ->
                _state.update {
                    it.copy(
                        loading = false,
                        preparingOffline = true,
                        sessions = sessions,
                    )
                }
                runCatching { repository.prepareOfflineData() }
                    .onSuccess { prepared ->
                        _state.update {
                            it.copy(
                                loading = false,
                                preparingOffline = false,
                                sessions = prepared,
                                error = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update { it.copy(preparingOffline = false, error = error.inboundUiText()) }
                    }
            }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.inboundUiText()) } }
    }

    fun setFilter(filter: InboundSessionFilter) = _state.update { it.copy(filter = filter) }
}

data class PendingInboundRebind(
    val rawCode: String,
    val confirmation: InboundRebindConfirmation,
)

data class InboundSessionUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val refreshingSnapshot: Boolean = false,
    val referenceSyncErrors: List<String> = emptyList(),
    val referenceSyncing: Boolean = false,
    val syncing: Boolean = false,
    val backgroundSyncing: Boolean = false,
    val syncProcessed: Int = 0,
    val syncTotal: Int = 0,
    val syncPhase: String = "",
    val workspace: InboundWorkspace? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    val pendingRebind: PendingInboundRebind? = null,
    val changingLocation: Boolean = false,
    val locationInput: String = "",
    val candidateLocation: LocalWarehouseLocationEntity? = null,
    val cameraOpen: Boolean = false,
    val cameraResumeToken: Long = 0,
    val finishConfirmationOpen: Boolean = false,
    val cancelConfirmationOpen: Boolean = false,
    val conflictDialogOpen: Boolean = false,
    val issuesOpen: Boolean = false,
    val pendingBoxesOpen: Boolean = false,
    val completedBoxesOpen: Boolean = false,
    val syncDetailsOpen: Boolean = false,
    val knownJifengCodes: Set<String> = emptySet(),
    val knownLocationCodes: Set<String> = emptySet(),
)

@HiltViewModel
class InboundSessionViewModel @Inject constructor(
    private val crossOrderRepository: CrossOrderReceivingRepository,
    private val repository: LocalInboundRepository,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
    private val scannerManager: ScannerManager,
    private val scanFeedback: ReceivingScanFeedback,
) : ViewModel() {
    val crossOrder = CrossOrderReceivingController(viewModelScope, crossOrderRepository, scanFeedback)
    private val _state = MutableStateFlow(InboundSessionUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )
    private var sessionId: Long = 0
    private var scanRevision: Long = 0
    private var productCacheJob: Job? = null

    fun load(id: Long) {
        crossOrder.load(id, refreshRemote = false)
        sessionId = id
        productCacheJob?.cancel()
        productCacheJob = viewModelScope.launch {
            launch {
                repository.downloadProgress.collect { progress ->
                    if (progress != null && progress.sessionId == sessionId && repository.currentScopeKey() == (progress.userId to progress.namespaceId)) {
                        _state.update { if (it.syncPhase in setOf("download", "recognition") || it.loading) it.copy(syncProcessed = progress.processed, syncTotal = progress.total, syncPhase = progress.phase) else it }
                    }
                }
            }
            launch {
                repository.referenceSyncReport.collect { report ->
                    val actor = repository.currentScopeKey()
                    if (actor == (report.userId to report.namespaceId)) {
                        val locations = if (report.completedAt > 0) repository.knownLocationCodes(id) else _state.value.knownLocationCodes
                        val aliases = if (report.completedAt > 0) boxLabelMappingRepository.knownJifengAliases() else _state.value.knownJifengCodes
                        _state.update { it.copy(referenceSyncErrors = report.errors, referenceSyncing = report.running, knownLocationCodes = locations, knownJifengCodes = aliases) }
                    } else {
                        _state.update { it.copy(referenceSyncErrors = emptyList(), referenceSyncing = false) }
                    }
                }
            }
            launch {
                repository.observeSyncActivity(id).collect { active ->
                    if (sessionId == id) _state.update { it.copy(backgroundSyncing = active) }
                }
            }
            repository.observeProductCache(id).collectLatest { cache ->
                _state.update { state ->
                    if (sessionId != id) return@update state
                    if (cache.session == null) return@update state.copy(workspace = null)
                    val workspace = state.workspace ?: return@update state
                    val pendingCount = cache.localSyncItems.count {
                        it.status in setOf(InboundItemStatus.Pending.value, InboundItemStatus.Syncing.value)
                    }
                    val conflicts = cache.localSyncItems.filter { it.status == InboundItemStatus.ConflictPending.value }
                    val rejected = cache.localSyncItems.filter { it.status == InboundItemStatus.Rejected.value }
                    val session = cache.session
                    state.copy(
                        workspace = workspace.copy(
                            pendingProducts = cache.items,
                            collectionItems = cache.collectionItems,
                            snapshotItems = cache.snapshotItems,
                            localSyncItems = cache.localSyncItems,
                            conflicts = conflicts,
                            syncIssues = conflicts + rejected,
                            summary = workspace.summary.copy(
                                productCacheComplete = cache.complete,
                                productCacheAt = cache.updatedAt,
                                totalCount = session?.totalCount?.takeIf { it > 0 } ?: workspace.summary.totalCount,
                                inboundedCount = if (session?.snapshotComplete == true) {
                                    cache.snapshotItems.count { it.isCompletedPhysicalInbound() }
                                } else {
                                    session?.inboundedCount ?: workspace.summary.inboundedCount
                                },
                                centerPendingCount = if (session?.snapshotComplete == true) {
                                    cache.snapshotItems.count { it.isPendingPhysicalInbound() }
                                } else {
                                    session?.unassignedCount ?: workspace.summary.centerPendingCount
                                },
                                exceptionCount = session?.exceptionCount?.takeIf { it >= 0 } ?: workspace.summary.exceptionCount,
                                pendingCount = pendingCount,
                                conflictCount = conflicts.size,
                                rejectedCount = rejected.size,
                                lastSyncAt = session?.lastSyncSuccessAt ?: workspace.summary.lastSyncAt,
                                status = session?.status ?: workspace.summary.status,
                            ),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, workspace = null, error = null) }
            runCatching { repository.prepareSession(id) }
                .onSuccess { workspace ->
                    val aliases = boxLabelMappingRepository.knownJifengAliases()
                    val locations = repository.knownLocationCodes(id)
                    _state.update {
                        it.copy(
                            loading = false,
                            workspace = workspace,
                            knownJifengCodes = aliases,
                            knownLocationCodes = locations,
                        )
                    }
                    refreshSnapshotIfNeeded(id)
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.inboundUiText()) } }
        }
    }

    fun scan(rawCode: String) {
        if (_state.value.pendingRebind != null || crossOrder.isBlocking) return
        submitScan(rawCode)
    }

    fun dismissRebind() {
        _state.update { it.copy(pendingRebind = null, cameraOpen = false, cameraResumeToken = it.cameraResumeToken + 1) }
    }

    fun confirmRebind() {
        if (_state.value.submitting) return
        val pending = _state.value.pendingRebind ?: return
        submitScan(pending.rawCode, pending.confirmation)
    }

    private fun submitScan(rawCode: String, confirmedRebind: InboundRebindConfirmation? = null) {
        val currentState = _state.value
        if (rawCode.isBlank() || sessionId == 0L || currentState.loading || currentState.submitting) return
        if (currentState.pendingBoxesOpen || currentState.completedBoxesOpen) return
        if (currentState.changingLocation) {
            previewLocation(rawCode)
            return
        }
        if (currentState.finishConfirmationOpen || currentState.cancelConfirmationOpen) return
        val workspace = currentState.workspace
        val scanningLocation = workspace?.draftBatch == null
        scanRevision++
        _state.update { it.copy(submitting = true, error = null, message = null, pendingRebind = null) }
        viewModelScope.launch {
            val actor = repository.currentScopeKey()
            val requestedSession = sessionId
            val result = runCatching {
                if (scanningLocation) {
                    repository.startBatch(sessionId, rawCode)
                    repository.workspace(sessionId)
                } else {
                    crossOrderRepository.intercept(sessionId, rawCode, "inbound", workspace.draftBatch.id, workspace.draftBatch.warehouseLocationId, workspace.draftBatch.locationCode)
                    repository.addBox(workspace.draftBatch.id, rawCode, confirmedRebind)
                    repository.workspace(sessionId)
                }
            }
            if (sessionId != requestedSession || repository.currentScopeKey() != actor) return@launch
            result.onSuccess { refreshed ->
                scanFeedback.success()
                val knownLocations = if (scanningLocation) {
                    _state.value.knownLocationCodes + refreshed.draftBatch?.locationCode.orEmpty()
                } else {
                    _state.value.knownLocationCodes
                }
                _state.update {
                    it.copy(
                        submitting = false,
                        workspace = refreshed,
                        message = UiText.Resource(
                            if (scanningLocation) R.string.inbound_location_locked else R.string.inbound_box_saved,
                        ),
                        cameraOpen = false,
                        knownLocationCodes = knownLocations.filter(String::isNotBlank).toSet(),
                    )
                }
                repository.requestBackgroundSync(refreshed.summary.sessionId)
            }.onFailure { error ->
                if (crossOrder.handle(error)) {
                    _state.update { it.copy(submitting = false, cameraOpen = false, cameraResumeToken = it.cameraResumeToken + 1) }
                    return@onFailure
                }
                scanFeedback.failure()
                _state.update {
                    it.copy(
                        submitting = false,
                        error = if (error is InboundRebindConfirmationRequired) null else error.inboundUiText(),
                        pendingRebind = (error as? InboundRebindConfirmationRequired)?.let { required ->
                            PendingInboundRebind(rawCode, required.confirmation)
                        },
                        cameraOpen = if (error is InboundRebindConfirmationRequired) false else it.cameraOpen,
                        cameraResumeToken = it.cameraResumeToken + 1,
                    )
                }
            }
        }
    }

    fun requestChangeLocation() {
        if (_state.value.submitting || _state.value.pendingRebind != null || _state.value.workspace?.draftBatch == null) return
        _state.update { it.copy(changingLocation = true, locationInput = "", candidateLocation = null, error = null, message = null, cameraOpen = false) }
    }

    fun dismissChangeLocation() {
        if (_state.value.submitting) return
        _state.update { it.copy(changingLocation = false, candidateLocation = null, locationInput = "", error = null, cameraOpen = false) }
    }

    fun setLocationInput(value: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(locationInput = value, candidateLocation = null, error = null) }
    }

    private fun previewLocation(rawCode: String) {
        val batch = _state.value.workspace?.draftBatch ?: return
        _state.update { it.copy(submitting = true, candidateLocation = null, locationInput = rawCode.trim(), error = null) }
        viewModelScope.launch {
            runCatching { repository.resolveDraftLocation(batch.id, rawCode) }
                .onSuccess { location ->
                    scanFeedback.success()
                    _state.update {
                        it.copy(submitting = false, candidateLocation = location, locationInput = location.code, cameraOpen = false)
                    }
                }
                .onFailure { error ->
                    scanFeedback.failure()
                    _state.update { it.copy(submitting = false, error = error.inboundUiText(), cameraResumeToken = it.cameraResumeToken + 1) }
                }
        }
    }

    fun confirmChangeLocation() {
        val current = _state.value
        if (current.submitting || !current.changingLocation) return
        val batch = current.workspace?.draftBatch ?: return
        val location = current.candidateLocation ?: return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.changeDraftLocation(batch.id, location.code) }
                .onSuccess { workspace ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            changingLocation = false,
                            candidateLocation = null,
                            workspace = workspace,
                            message = UiText.Resource(R.string.inbound_location_changed, listOf(location.code, workspace.draftItems.size)),
                        )
                    }
                    repository.requestBackgroundSync(workspace.summary.sessionId)
                }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.inboundUiText()) } }
        }
    }

    fun isKnownBoxLabelCode(code: String): Boolean =
        isKnownMappedBoxLabel(code, _state.value.knownJifengCodes)

    fun isKnownLocationCode(code: String): Boolean =
        runCatching { com.google.samples.apps.nowinandroid.fscm.data.normalizeInboundLocation(code) }
            .getOrNull() in _state.value.knownLocationCodes

    fun requestFinish() {
        if (_state.value.submitting || _state.value.changingLocation || _state.value.pendingRebind != null || crossOrder.isBlocking) return
        _state.update { it.copy(finishConfirmationOpen = true, cameraOpen = false) }
    }

    fun dismissFinish() = _state.update { it.copy(finishConfirmationOpen = false) }

    fun requestCancelDraft() {
        if (_state.value.submitting || _state.value.changingLocation || _state.value.pendingRebind != null || crossOrder.isBlocking) return
        _state.update { it.copy(cancelConfirmationOpen = true, cameraOpen = false) }
    }

    fun dismissCancelDraft() = _state.update { it.copy(cancelConfirmationOpen = false) }

    fun finishBatch() {
        if (_state.value.submitting || _state.value.changingLocation || _state.value.pendingRebind != null || crossOrder.isBlocking) return
        val batch = _state.value.workspace?.draftBatch ?: return
        val count = _state.value.workspace?.draftItems?.size ?: 0
        _state.update { it.copy(submitting = true, finishConfirmationOpen = false, error = null) }
        viewModelScope.launch {
            runCatching { repository.finishBatch(batch.id) }
                .onSuccess { workspace ->
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

    fun deleteDraftBox(requestId: String) {
        if (_state.value.submitting || _state.value.changingLocation || _state.value.pendingRebind != null || crossOrder.isBlocking) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            runCatching {
                repository.deleteDraftBox(requestId)
                reloadWorkspace()
            }.onFailure { error -> _state.update { it.copy(error = error.inboundUiText()) } }
            _state.update { it.copy(submitting = false) }
        }
    }

    fun cancelDraft() {
        if (_state.value.submitting || _state.value.changingLocation || _state.value.pendingRebind != null || crossOrder.isBlocking) return
        val batch = _state.value.workspace?.draftBatch ?: return
        _state.update { it.copy(submitting = true, cancelConfirmationOpen = false, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.cancelDraft(batch.id)
                repository.workspace(sessionId)
            }.onSuccess { workspace ->
                _state.update {
                    it.copy(
                        submitting = false,
                        workspace = workspace,
                        message = UiText.Resource(R.string.inbound_batch_cancelled),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(submitting = false, error = error.inboundUiText()) }
            }
        }
    }

    fun updateOfflineReferences() {
        viewModelScope.launch { repository.updateOfflineReferences(sessionId) }
    }

    fun manualSync() {
        if (_state.value.syncing) return
        val requestedSession = sessionId
        val requestedRevision = scanRevision
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val actor = repository.currentScopeKey()
            _state.update {
                it.copy(syncing = true, syncProcessed = 0, syncTotal = 0, error = null, message = null)
            }
            runCatching {
                repository.syncSessionManually(requestedSession, onPhase = { phase ->
                    _state.update { it.copy(syncPhase = phase) }
                }) { processed, total ->
                    _state.update { it.copy(syncProcessed = processed, syncTotal = total) }
                }
            }
                .onSuccess { workspace ->
                    if (repository.currentScopeKey() != actor) return@onSuccess
                    _state.update {
                        if (sessionId != requestedSession) {
                            it
                        } else {
                            it.copy(
                                syncing = false,
                                syncPhase = "",
                                syncProcessed = 0,
                                syncTotal = 0,
                                conflictDialogOpen = it.workspace?.draftBatch == null && !it.issuesOpen && workspace.conflicts.isNotEmpty(),
                                message = if (scanRevision != requestedRevision) {
                                    it.message
                                } else if (workspace.syncIssues.isEmpty()) {
                                    UiText.Resource(R.string.inbound_sync_finished)
                                } else {
                                    UiText.Resource(
                                        R.string.inbound_sync_finished_with_issues,
                                        listOf(
                                            workspace.syncIssues.count { it.status == "conflict_pending" },
                                            workspace.syncIssues.count { it.status == "rejected" },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (repository.currentScopeKey() != actor) return@onFailure
                    _state.update {
                        if (sessionId != requestedSession) {
                            it
                        } else {
                            it.copy(
                                syncing = false,
                                syncPhase = "",
                                syncProcessed = 0,
                                syncTotal = 0,
                                error = if (scanRevision == requestedRevision) error.inboundUiText() else it.error,
                            )
                        }
                    }
                }
        }
    }

    fun acceptRemote(requestId: String) = resolveConflict {
        repository.acceptRemote(requestId)
    }

    fun overrideRemote(requestId: String) = resolveConflict {
        repository.overrideRemote(requestId)
    }

    fun dismissConflictDialog() = _state.update { it.copy(conflictDialogOpen = false) }

    fun openIssues() = _state.update { it.copy(issuesOpen = true) }

    fun dismissIssues() = _state.update { it.copy(issuesOpen = false) }

    fun openPendingBoxes() = _state.update { it.copy(pendingBoxesOpen = true) }

    fun dismissPendingBoxes() = _state.update { it.copy(pendingBoxesOpen = false) }

    fun openCompletedBoxes() = _state.update { it.copy(completedBoxesOpen = true) }

    fun dismissCompletedBoxes() = _state.update { it.copy(completedBoxesOpen = false) }

    fun openSyncDetails() = _state.update { it.copy(syncDetailsOpen = true) }

    fun dismissSyncDetails() = _state.update { it.copy(syncDetailsOpen = false) }

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

    private fun refreshSnapshotIfNeeded(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(refreshingSnapshot = true) }
            runCatching { repository.refreshSessionIfStale(id) }
                .onSuccess { refreshed ->
                    if (refreshed != null) _state.update { it.copy(workspace = refreshed) }
                }
            _state.update { it.copy(refreshingSnapshot = false) }
        }
    }
}

internal fun Throwable.inboundUiText(): UiText = when (this) {
    is InboundClientException -> UiText.Resource(
        when (reason) {
            InboundClientError.SnapshotUnavailable -> R.string.inbound_snapshot_required
            InboundClientError.InvalidLocation -> R.string.inbound_invalid_location
            InboundClientError.LocationCacheUnavailable -> R.string.inbound_location_cache_unavailable
            InboundClientError.LocationNotFound -> R.string.inbound_location_not_found
            InboundClientError.LocationInactive -> R.string.inbound_location_inactive
            InboundClientError.SkuCacheMiss -> R.string.inbound_sku_cache_miss
            InboundClientError.LocationWarehouseMismatch -> R.string.inbound_location_warehouse_mismatch
            InboundClientError.TargetWarehouseMissing -> R.string.inbound_target_warehouse_missing
            InboundClientError.SessionComplete -> R.string.inbound_session_already_complete
            InboundClientError.BatchNotFound -> R.string.inbound_batch_not_found
            InboundClientError.EmptyBatch -> R.string.inbound_empty_batch
            InboundClientError.InvalidBoxLabel -> R.string.inbound_invalid_box_label
            InboundClientError.MappingCacheMiss -> R.string.box_label_mapping_cache_miss
            InboundClientError.BoxNotInSession -> R.string.inbound_box_not_in_session
            InboundClientError.BoxNotEligible -> R.string.inbound_box_not_eligible
            InboundClientError.BoxOfflineNotFound -> R.string.inbound_box_offline_not_found
            InboundClientError.BoxScannedInCurrentBatch -> R.string.inbound_box_scanned_current
            InboundClientError.BoxDuplicateRecord -> R.string.inbound_box_duplicate_record
            InboundClientError.BoxManualReceived -> R.string.inbound_box_manual_received
            InboundClientError.BoxVoided -> R.string.inbound_box_voided
            InboundClientError.BoxCancelled -> R.string.inbound_box_cancelled
            InboundClientError.BoxShorted -> R.string.inbound_box_shorted
            InboundClientError.BoxAdjustedOut -> R.string.inbound_box_adjusted_out
            InboundClientError.BoxException -> R.string.inbound_box_exception
            InboundClientError.BoxAlreadyAssigned -> if (detail.isBlank()) R.string.inbound_box_already_assigned else R.string.inbound_box_assigned_location
            InboundClientError.BoxAlreadyScanned -> if (detail.isBlank()) R.string.inbound_box_already_scanned else R.string.inbound_box_scanned_location
            InboundClientError.ConflictNotFound -> R.string.inbound_conflict_not_found
            InboundClientError.OfflineSyncUnavailable -> R.string.inbound_sync_offline
        },
        args = listOf(detail),
    )
    is FscmApiException -> when (inboundStandardCode()) {
        42244 -> UiText.Resource(R.string.inbound_location_not_found)
        42245 -> UiText.Resource(R.string.inbound_location_inactive)
        42246 -> UiText.Resource(R.string.inbound_location_warehouse_mismatch)
        else -> UiText.Dynamic(serverMessage ?: message.orEmpty())
    }
    else -> UiText.Dynamic(message ?: "Unknown error")
}
