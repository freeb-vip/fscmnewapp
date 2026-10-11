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
import com.google.samples.apps.nowinandroid.fscm.data.CountTask
import com.google.samples.apps.nowinandroid.fscm.data.CountTaskDetail
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.LocationStocktakeDraftStore
import com.google.samples.apps.nowinandroid.fscm.data.LocationStocktakeRepository
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeOption
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeResults
import com.google.samples.apps.nowinandroid.fscm.data.OfflineCodeSearch
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeContext
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeDraft
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeLookup
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeRecord
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeStock
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeSubmission
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeSubmissionInput
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeQuantity
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeScope
import com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

enum class StocktakeStage { Location, Sku, Count }

data class StocktakeManualSearch(
    val text: String = "",
    val result: OfflineCodeResults = OfflineCodeResults(),
    val searching: Boolean = false,
    val failed: Boolean = false,
)

data class LocationStocktakeUiState(
    val manualSearch: StocktakeManualSearch = StocktakeManualSearch(),
    val referencesRefreshing: Boolean = false,
    val referencesFailed: Boolean = false,
    val scope: String = "",
    val userId: Long = 0,
    val allowed: Boolean = false,
    val canWrite: Boolean = false,
    val showTasks: Boolean = true,
    val tasks: List<CountTask> = emptyList(),
    val taskTab: String = "pending",
    val mine: Boolean = true,
    val taskPage: Int = 0,
    val tasksMore: Boolean = false,
    val tasksBusy: Boolean = false,
    val task: CountTask? = null,
    val detail: CountTaskDetail? = null,
    val stage: StocktakeStage = StocktakeStage.Location,
    val context: StocktakeContext? = null,
    val location: String = "",
    val sku: String = "",
    val quantity: String = "",
    val inputMethod: String = "manual",
    val lookup: StocktakeLookup? = null,
    val locations: List<StocktakeStock> = emptyList(),
    val hasMore: Boolean = false,
    val page: Int = 0,
    val shelf: List<StocktakeStock> = emptyList(),
    val shelfPage: Int = 0,
    val shelfMore: Boolean = false,
    val busy: Boolean = false,
    val listBusy: Boolean = false,
    val listError: Boolean = false,
    val pending: StocktakeSubmissionInput? = null,
    val confirmation: StocktakeSubmission? = null,
    val confirmationInput: String = "",
    val review: Boolean = false,
    val submitting: Boolean = false,
    val camera: Boolean = false,
    val switchTarget: StocktakeStock? = null,
    val changingLocation: Boolean = false,
    val releasePrompt: Boolean = false,
    val lastRecord: StocktakeRecord? = null,
    val error: Int? = null,
    val expired: Boolean = false,
    val progressFailed: Boolean = false,
    val quickPending: Boolean = false,
    val draftConflict: StocktakeDraft? = null,
) {
    val own: Boolean get() = task?.claimedBy == userId
    val editable: Boolean get() = own && task?.status == "in_progress" && !showTasks
    val dirty: Boolean get() = lookup?.stock?.onHand?.let { quantity != it.toString() } ?: false
    val locked: Boolean get() = busy || submitting || pending != null || confirmation != null || draftConflict != null
    val readyToSubmit: Boolean get() = editable && allowed && canWrite && context?.writeEnabled == true &&
        lookup?.usable() == true && stocktakeQuantity(quantity) != null && !locked && !expired && !progressFailed
    val canScan: Boolean get() = editable && allowed && context?.workspace?.status == "configured" &&
        !locked && !review && stage != StocktakeStage.Count && !changingLocation && switchTarget == null && !releasePrompt
    val canQuick: Boolean get() = allowed && canWrite && context?.workspace?.status == "configured" &&
        !busy && !submitting && pending == null && confirmation == null && !review && !progressFailed && draftConflict == null
}

@HiltViewModel
class LocationStocktakeViewModel @Inject constructor(
    private val offlineCodes: OfflineCodeSearch,
    private val repository: LocationStocktakeRepository,
    private val drafts: LocationStocktakeDraftStore,
    private val scannerManager: ScannerManager,
    private val feedback: ReceivingScanFeedback,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocationStocktakeUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var listGeneration = 0
    private var queryJob: Job? = null
    private var taskListJob: Job? = null
    private var alternativesJob: Job? = null
    private var submitJob: Job? = null
    private var manualSearchJob: Job? = null
    private var referenceJob: Job? = null
    private var manualSearchVersion = 0
    private val progressMutex = Mutex()
    private val progressSignals = Channel<Unit>(Channel.CONFLATED)
    private var lastScan = ""
    private var lastScanAt = 0L

    init {
        viewModelScope.launch {
            for (signal in progressSignals) {
                delay(450)
                val seq = generation
                try {
                    saveProgress(seq)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (seq == generation) mutableState.update { it.copy(progressFailed = true, error = R.string.st_progress_failed) }
                }
            }
        }
    }

    fun enter(session: SessionState) {
        val scope = stocktakeScope(session)
        val allowed = !session.isOffline && "mobile-app:location-stocktakes" in session.permissions && "location_stocktakes" in session.permissions
        val write = "location_stocktakes_submit" in session.permissions
        if (scope == state.value.scope && allowed == state.value.allowed && write == state.value.canWrite) return
        generation++
        listGeneration++
        clearManualSearch()
        referenceJob?.cancel()
        queryJob?.cancel()
        alternativesJob?.cancel()
        submitJob?.cancel()
        taskListJob?.cancel()
        mutableState.value = LocationStocktakeUiState(scope = scope, userId = session.user?.id ?: 0, allowed = allowed, canWrite = write)
        if (!allowed) {
            mutableState.update { it.copy(error = if (session.isOffline) R.string.st_online_required else R.string.st_no_permission) }
            return
        }
        retry()
        loadTasks()
    }

    fun retry() {
        val seq = generation
        viewModelScope.launch {
            try {
                val context = repository.context()
                val quickDraft = drafts.read(state.value.scope + ":quick")
                if (seq == generation) mutableState.update { it.copy(context = context, quickPending = quickDraft.createRequestId.isNotBlank(), error = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_load_failed) }
            }
        }
    }

    fun selectTab(tab: String, mine: Boolean = state.value.mine) {
        mutableState.update { it.copy(taskTab = tab, mine = mine) }
        loadTasks()
    }
    fun loadTasks(more: Boolean = false) {
        if (!state.value.allowed) return
        val seq = ++listGeneration
        val s = state.value
        val page = if (more) s.taskPage + 1 else 1
        taskListJob?.cancel()
        mutableState.update { it.copy(tasksBusy = true, error = null) }
        taskListJob = viewModelScope.launch {
            try {
                val result = repository.countTasks(s.taskTab, s.taskTab == "active" && s.mine, page)
                if (seq == listGeneration) mutableState.update { it.copy(tasks = if (more) (it.tasks + result.items).distinctBy(CountTask::id) else result.items, taskPage = page, tasksMore = result.hasMore) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == listGeneration) mutableState.update { it.copy(error = R.string.st_tasks_failed) }
            } finally {
                if (seq == listGeneration) mutableState.update { it.copy(tasksBusy = false) }
            }
        }
    }

    fun openTask(task: CountTask) {
        if (state.value.busy || state.value.submitting) return
        val seq = ++generation
        queryJob?.cancel()
        alternativesJob?.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        queryJob = viewModelScope.launch {
            try {
                if (seq != generation) return@launch
                if (task.status == "pending" && state.value.canWrite) repository.changeTask(task, "claim")
                val detail = repository.countTask(task.id)
                if (seq != generation) return@launch
                val current = detail.task
                val draft = drafts.read(state.value.scope + ":" + current.id)
                val record = detail.records.lastOrNull { it.id == current.lastRecordId }
                val pending = restoreStocktakeRequest(current, detail.records, draft)
                val owned = current.claimedBy == state.value.userId
                val local = draft.takeIf { owned && it.taskVersion == current.version }
                val conflict = draft.takeIf { owned && pending == null && current.status == "in_progress" && it.taskVersion != current.version && it.location == current.locationCode && it.sku == current.sku && stocktakeQuantity(it.quantity) != null && it.quantity != current.draftQuantity?.toString() }
                mutableState.update {
                    it.copy(
                        showTasks = false, task = current, detail = detail, location = current.locationCode, sku = current.sku,
                        quantity = local?.quantity ?: current.draftQuantity?.toString().orEmpty(), inputMethod = local?.inputMethod ?: current.inputMethod,
                        lookup = null, locations = emptyList(), page = 0, hasMore = false, shelf = emptyList(), shelfPage = 0, shelfMore = false,
                        pending = pending.takeIf { owned }, confirmation = null, review = false, confirmationInput = "", lastRecord = record,
                        stage = if (current.locationCode.isBlank()) {
                            StocktakeStage.Location
                        } else if (current.sku.isBlank()) {
                            StocktakeStage.Sku
                        } else {
                            StocktakeStage.Count
                        },
                        progressFailed = false, expired = false, busy = false, listBusy = false, camera = false, draftConflict = conflict,
                    )
                }
                if (owned && current.status == "in_progress" && pending == null && conflict == null) {
                    if (current.locationCode.isNotBlank()) {
                        lookup(current.locationCode, current.sku, preserveQuantity = true)
                    } else if (current.sku.isNotBlank()) {
                        loadLocations()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) {
                    mutableState.update { it.copy(error = R.string.st_task_changed) }
                    loadTasks()
                }
            } finally {
                if (seq == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun resolveDraft(keepLocal: Boolean) {
        val s = state.value
        val draft = s.draftConflict ?: return
        mutableState.update { it.copy(draftConflict = null, quantity = if (keepLocal) draft.quantity else it.quantity, inputMethod = if (keepLocal) draft.inputMethod else it.inputMethod) }
        requery()
    }

    fun quick(keepLocation: Boolean = false) {
        if (!state.value.canQuick) return
        val s = state.value
        val seq = generation
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                saveProgress(seq)
                if (seq != generation) return@launch
                val key = s.scope + ":quick"
                val saved = drafts.read(key)
                val request = saved.createRequestId.ifBlank { UUID.randomUUID().toString() }
                val location = if (saved.createRequestId.isNotBlank()) {
                    saved.location
                } else if (keepLocation) {
                    s.location
                } else {
                    ""
                }
                if (seq != generation) return@launch
                drafts.save(key, StocktakeDraft(location = location, createRequestId = request))
                if (seq != generation) return@launch
                val task = repository.quick(request)
                drafts.save(key, StocktakeDraft())
                if (seq != generation) return@launch
                generation++
                alternativesJob?.cancel()
                mutableState.update { LocationStocktakeUiState(scope = s.scope, userId = s.userId, allowed = s.allowed, canWrite = s.canWrite, context = s.context, showTasks = false, task = task, location = location) }
                if (location.isNotBlank()) lookup(location, "")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_quick_failed, quickPending = true) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun backToTasks() {
        if (state.value.busy || state.value.submitting || state.value.draftConflict != null) return
        val seq = generation
        viewModelScope.launch {
            var syncFailed = false
            try {
                saveProgress(seq)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq != generation) return@launch
                val s = state.value
                try {
                    s.task?.let { drafts.save(s.scope + ":" + it.id, s.draft()) }
                    syncFailed = true
                } catch (storageError: Exception) {
                    if (storageError is CancellationException) throw storageError
                    mutableState.update { it.copy(error = R.string.st_save_failed) }
                    return@launch
                }
            }
            if (seq != generation) return@launch
            persistLocal()
            generation++
            alternativesJob?.cancel()
            queryJob?.cancel()
            mutableState.update { it.copy(showTasks = true, task = null, detail = null, pending = null, confirmation = null, review = false, camera = false, lookup = null, lastRecord = null, error = if (syncFailed) R.string.st_local_saved else null, listBusy = false, progressFailed = false) }
            loadTasks()
            if (syncFailed) mutableState.update { it.copy(error = R.string.st_local_saved) }
        }
    }

    fun registerScanner(owner: LifecycleOwner) {
        scannerManager.pushOnScanListener(::scan)
        scannerManager.register(owner)
    }
    fun unregisterScanner() = scannerManager.popOnScanListener()
    fun camera(open: Boolean) {
        mutableState.update { it.copy(camera = open && it.canScan) }
    }
    fun clearManualSearch() {
        manualSearchVersion++
        manualSearchJob?.cancel()
        mutableState.update { it.copy(manualSearch = StocktakeManualSearch()) }
    }

    fun searchManualCode(text: String) {
        if (!state.value.canScan || text.length > 200) return
        val version = ++manualSearchVersion
        manualSearchJob?.cancel()
        val s = state.value
        val seq = generation
        mutableState.update { it.copy(manualSearch = StocktakeManualSearch(text = text, searching = text.isNotBlank())) }
        if (text.isBlank()) return
        manualSearchJob = viewModelScope.launch {
            delay(150)
            try {
                val result = offlineCodes.search(text, s.stage == StocktakeStage.Location)
                if (seq == generation && version == manualSearchVersion) {
                    mutableState.update { it.copy(manualSearch = StocktakeManualSearch(text = text, result = result)) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (seq == generation && version == manualSearchVersion) {
                    mutableState.update { it.copy(manualSearch = StocktakeManualSearch(text = text, failed = true)) }
                }
            }
        }
    }

    fun chooseManualCode(option: OfflineCodeOption) {
        if (!state.value.canScan || option !in state.value.manualSearch.result.items) return
        scan(option.code)
    }

    fun refreshOfflineCodes() {
        if (!state.value.canScan || referenceJob?.isActive == true) return
        val scope = state.value.scope
        mutableState.update { it.copy(referencesRefreshing = true, referencesFailed = false) }
        referenceJob = viewModelScope.launch {
            try {
                offlineCodes.refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (scope == state.value.scope) mutableState.update { it.copy(referencesFailed = true) }
            } finally {
                if (scope == state.value.scope) {
                    mutableState.update { it.copy(referencesRefreshing = false) }
                    searchManualCode(state.value.manualSearch.text)
                }
            }
        }
    }

    fun scan(raw: String) {
        val s = state.value
        if (!s.canScan) return
        val code = raw.trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
        if (code.isBlank() || code.length > 200) return
        val identity = "${s.task?.id}|${s.stage}|${s.location}|$code"
        val now = System.currentTimeMillis()
        if (identity == lastScan && now - lastScanAt < 1200) return
        lastScan = identity
        lastScanAt = now
        if (s.stage == StocktakeStage.Location) lookup(code, s.task?.sku.orEmpty()) else lookup(s.location, code)
    }

    private fun lookup(location: String, sku: String, preserveQuantity: Boolean = false) {
        val s = state.value
        val seq = ++generation
        val savedQuantity = if (preserveQuantity) s.quantity else ""
        alternativesJob?.cancel()
        clearManualSearch()
        mutableState.update { it.copy(busy = true, camera = false, error = null, listBusy = false, listError = false, lookup = null, locations = emptyList(), page = 0, shelf = emptyList(), shelfPage = 0) }
        queryJob = viewModelScope.launch {
            try {
                val result = repository.lookup(location, sku)
                if (seq != generation) return@launch
                if (result.status != "location_ready" && !result.usable()) {
                    mutableState.update { it.copy(error = if (result.status == "location_not_found") R.string.st_location_missing else R.string.st_sku_missing) }
                    feedback.failure()
                    return@launch
                }
                progressMutex.withLock {
                    if (seq != generation) return@withLock
                    val task = state.value.task ?: return@withLock
                    val quantity = if (result.usable()) stocktakeQuantity(savedQuantity) ?: result.stock?.onHand else null
                    val method = if (preserveQuantity) s.inputMethod else "manual"
                    val next = repository.changeTask(task, "progress", result.queryId.ifBlank { result.snapshotId }, quantity, method)
                    if (seq == generation) mutableState.update { it.copy(task = next, location = location, sku = next.sku, quantity = quantity?.toString().orEmpty(), inputMethod = method, lookup = result, stage = if (result.usable()) StocktakeStage.Count else StocktakeStage.Sku, expired = false, progressFailed = false) }
                }
                persistLocal()
                feedback.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) {
                    mutableState.update { it.copy(error = R.string.st_query_failed) }
                    feedback.failure()
                }
            } finally {
                if (seq == generation) {
                    mutableState.update { it.copy(busy = false) }
                    if (state.value.stage == StocktakeStage.Sku) {
                        loadShelf()
                    } else if (state.value.stage == StocktakeStage.Location && state.value.sku.isNotBlank()) {
                        loadLocations()
                    }
                }
            }
        }
    }

    fun loadLocations() = loadChoices(false)
    fun loadShelf() = loadChoices(true)
    private fun loadChoices(shelf: Boolean) {
        val s = state.value
        if (s.listBusy || (shelf && s.location.isBlank()) || (!shelf && s.sku.isBlank())) return
        val seq = generation
        val page = (if (shelf) s.shelfPage else s.page) + 1
        mutableState.update { it.copy(listBusy = true, listError = false) }
        alternativesJob = viewModelScope.launch {
            try {
                val result = if (shelf) repository.shelf(s.location, page) else repository.locations(s.sku, page)
                if (seq == generation) mutableState.update { if (shelf) it.copy(shelf = (it.shelf + result.items).distinctBy(StocktakeStock::externalId), shelfPage = page, shelfMore = result.hasMore) else it.copy(locations = (it.locations + result.items).distinctBy(StocktakeStock::externalId), page = page, hasMore = result.hasMore) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(listError = true) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(listBusy = false) }
            }
        }
    }

    fun chooseStock(stock: StocktakeStock) {
        if (!state.value.editable || state.value.locked || (stock.onHand ?: 0) <= 0) return
        if (state.value.dirty) mutableState.update { it.copy(switchTarget = stock) } else lookup(stock.locationCode, stock.sku)
    }
    fun changeLocation() {
        if (!state.value.editable || state.value.locked) return
        if (state.value.dirty) mutableState.update { it.copy(changingLocation = true) } else clearLocation()
    }
    fun confirmSwitch() {
        val target = state.value.switchTarget
        mutableState.update { it.copy(switchTarget = null, changingLocation = false) }
        if (target != null) lookup(target.locationCode, target.sku) else clearLocation()
    }
    fun cancelSwitch() {
        mutableState.update { it.copy(switchTarget = null, changingLocation = false) }
    }
    private fun clearLocation() {
        val seq = generation
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                progressMutex.withLock {
                    if (seq != generation) return@withLock
                    val task = state.value.task ?: return@withLock
                    val next = repository.changeTask(task, "progress", clearTarget = true)
                    if (seq == generation) mutableState.update { it.copy(task = next, location = "", sku = next.sku, lookup = null, quantity = "", stage = StocktakeStage.Location, locations = emptyList(), page = 0, shelf = emptyList(), shelfPage = 0, error = null, progressFailed = false) }
                }
                persistLocal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_task_changed) }
            } finally {
                if (seq == generation) {
                    mutableState.update { it.copy(busy = false) }
                    loadLocations()
                }
            }
        }
    }

    fun editQuantity(value: String, zero: Boolean = false) {
        if (state.value.locked || !state.value.editable || state.value.stage != StocktakeStage.Count || value.length > 16 || value.any { !it.isDigit() }) return
        mutableState.update { it.copy(quantity = value, inputMethod = if (zero) "zero" else "manual") }
        persistLocal()
        progressSignals.trySend(Unit)
    }
    private fun persistLocal() {
        val s = state.value
        val task = s.task ?: return
        viewModelScope.launch {
            try {
                drafts.save(s.scope + ":" + task.id, s.draft())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (s.scope == state.value.scope) mutableState.update { it.copy(error = R.string.st_save_failed) }
            }
        }
    }
    private fun LocationStocktakeUiState.draft() = StocktakeDraft(location, sku, quantity, pending, lookup, context?.workspace?.version ?: 0, inputMethod, task?.version ?: 0)
    private suspend fun saveProgress(seq: Int) = progressMutex.withLock {
        if (seq != generation) return@withLock
        val s = state.value
        val task = s.task ?: return@withLock
        if (!s.editable || s.pending != null || s.confirmation != null || s.review || s.draftConflict != null) return@withLock
        drafts.save(s.scope + ":" + task.id, s.draft())
        val quantity = stocktakeQuantity(s.quantity) ?: return@withLock
        if (task.draftQuantity == quantity && task.inputMethod == s.inputMethod) return@withLock
        val next = repository.changeTask(task, "progress", quantity = quantity, inputMethod = s.inputMethod)
        if (seq == generation) {
            mutableState.update { it.copy(task = next, progressFailed = false) }
            drafts.save(s.scope + ":" + task.id, state.value.draft())
        }
    }
    fun retryProgress() {
        val task = state.value.task ?: return
        openTask(task)
    }
    fun requery() {
        if (!state.value.locked && state.value.location.isNotBlank()) lookup(state.value.location, state.value.sku, preserveQuantity = true)
    }
    fun tick() {
        mutableState.update { it.copy(expired = it.lookup?.let { lookup -> lookup.status == "ready" && !lookup.usable() } ?: false) }
    }
    fun review() {
        if (state.value.readyToSubmit) mutableState.update { it.copy(review = true, camera = false) }
    }
    fun closeReview() {
        if (!state.value.submitting) mutableState.update { it.copy(review = false) }
    }
    fun confirmInput(value: String) {
        if (!state.value.submitting && value.length <= 16 && value.all(Char::isDigit)) mutableState.update { it.copy(confirmationInput = value) }
    }
    fun cancelConfirmation() {
        val s = state.value
        if (s.submitting || s.busy) return
        val seq = generation
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                progressMutex.withLock {
                    if (seq != generation) return@withLock
                    val task = state.value.task ?: return@withLock
                    val next = repository.changeTask(task, "progress", quantity = stocktakeQuantity(s.quantity), inputMethod = s.inputMethod)
                    if (seq == generation) mutableState.update { it.copy(task = next, confirmation = null, pending = null, review = false, confirmationInput = "") }
                }
                if (seq == generation) persistLocal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(confirmation = null, error = R.string.st_recover_required) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun askRelease(open: Boolean) {
        if (!state.value.locked) mutableState.update { it.copy(releasePrompt = open) }
    }
    fun release() {
        val s = state.value
        val task = s.task ?: return
        if (!s.editable || s.locked) return
        mutableState.update { it.copy(busy = true, releasePrompt = false) }
        val seq = generation
        viewModelScope.launch {
            try {
                progressMutex.withLock {
                    if (seq != generation) return@launch
                    repository.changeTask(state.value.task ?: task, if (task.sku.isBlank()) "cancel" else "release")
                }
                drafts.save(s.scope + ":" + task.id, StocktakeDraft())
                if (seq == generation) {
                    generation++
                    alternativesJob?.cancel()
                    mutableState.update { it.copy(showTasks = true, task = null, detail = null, pending = null, confirmation = null, error = null, busy = false) }
                    loadTasks()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_task_changed) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun submit(confirm: Boolean = false) {
        val s = state.value
        val task = s.task ?: return
        if (!s.own || !s.allowed || !s.canWrite || s.submitting || s.busy) return
        if (s.pending == null && (!s.readyToSubmit || !s.review)) return
        var input = s.pending ?: StocktakeSubmissionInput(s.lookup!!.snapshotId, UUID.randomUUID().toString(), stocktakeQuantity(s.quantity)!!, s.inputMethod, countTaskId = task.id, countTaskVersion = task.version)
        if (confirm) {
            val quantity = stocktakeQuantity(s.confirmationInput)
            if (s.confirmation?.confirmationToken == null || quantity != input.countedQuantity) return
            input = input.copy(confirmationToken = s.confirmation.confirmationToken, confirmedQuantity = quantity)
        }
        val accepted = input
        val seq = generation
        mutableState.update { it.copy(submitting = true, pending = accepted, review = false, error = null) }
        submitJob = viewModelScope.launch {
            try {
                progressMutex.withLock {
                    if (seq != generation) return@withLock
                    val request = if (s.pending == null) accepted.copy(countTaskVersion = state.value.task?.version) else accepted
                    mutableState.update { it.copy(pending = request) }
                    drafts.save(s.scope + ":" + task.id, state.value.draft())
                    if (seq != generation) return@withLock
                    val result = repository.submit(request)
                    if (seq != generation) return@withLock
                    val latest = repository.countTask(task.id)
                    if (seq != generation) return@withLock
                    mutableState.update { it.copy(task = latest.task, detail = latest, lastRecord = result.record) }
                    if (result.record.status == "awaiting_confirmation") {
                        mutableState.update { it.copy(confirmation = result, confirmationInput = "") }
                    } else {
                        mutableState.update { it.copy(confirmation = null) }
                        observe(result.record, seq)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation && e is FscmApiException && e.statusCode in setOf(400, 403, 404, 409, 422)) {
                    try {
                        val detail = repository.countTask(task.id)
                        if (seq == generation && detail.records.none { it.requestId == accepted.requestId }) {
                            // The server rejected this request before creating a record. Reopening obtains a fresh version and snapshot.
                            mutableState.update { it.copy(pending = null, confirmation = null, progressFailed = true, error = R.string.st_task_changed) }
                            drafts.save(s.scope + ":" + task.id, state.value.draft())
                            return@launch
                        }
                    } catch (recoveryError: Exception) {
                        if (recoveryError is CancellationException) throw recoveryError
                    }
                }
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_recover_required) }
            } finally {
                if (seq == generation) {
                    mutableState.update { it.copy(submitting = false) }
                    persistLocal()
                }
            }
        }
    }
    private suspend fun observe(initial: StocktakeRecord, seq: Int) {
        var record = initial
        repeat(80) {
            if (seq != generation) return
            mutableState.update { it.copy(lastRecord = record) }
            when (record.status) {
                "succeeded" -> {
                    val detail = repository.countTask(state.value.task!!.id)
                    if (seq != generation) return
                    mutableState.update { it.copy(task = detail.task, detail = detail, pending = null, confirmation = null, lookup = null, error = null) }
                    feedback.success()
                    return
                }
                "failed", "expired", "cancelled", "blocked_evidence" -> {
                    val detail = repository.countTask(state.value.task!!.id)
                    if (seq != generation) return
                    mutableState.update { it.copy(task = detail.task, detail = detail, pending = null, expired = true, error = if (record.status == "blocked_evidence") R.string.st_write_blocked else R.string.st_submit_failed) }
                    feedback.failure()
                    return
                }
                "result_unknown", "blocked_auth", "blocked_config" -> {
                    mutableState.update { it.copy(error = R.string.st_recover_required) }
                    return
                }
            }
            delay(1500)
            record = repository.record(record.id)
        }
        mutableState.update { it.copy(error = R.string.st_recover_required) }
    }
}

// A terminal server result takes precedence over a request left on disk by a crash.
internal fun restoreStocktakeRequest(task: CountTask, records: List<StocktakeRecord>, draft: StocktakeDraft): StocktakeSubmissionInput? {
    if (task.status !in setOf("in_progress", "processing", "needs_review")) return null
    val active = setOf("awaiting_confirmation", "queued", "running", "result_unknown", "blocked_auth", "blocked_config")
    draft.pending?.let { remembered ->
        if (records.none { it.requestId == remembered.requestId && it.status !in active }) return remembered
    }
    return records.lastOrNull { it.id == task.lastRecordId && it.status in active }?.let {
        StocktakeSubmissionInput(it.snapshotId, it.requestId, it.countedQuantity, it.inputMethod, countTaskId = task.id, countTaskVersion = task.version)
    }
}
