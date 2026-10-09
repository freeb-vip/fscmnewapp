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
import com.google.samples.apps.nowinandroid.fscm.data.LocationStocktakeDraftStore
import com.google.samples.apps.nowinandroid.fscm.data.LocationStocktakeRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class StocktakeStage { Location, Sku, Count }

data class LocationStocktakeUiState(
    val scope: String = "",
    val allowed: Boolean = false,
    val canWrite: Boolean = false,
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
    val busy: Boolean = false,
    val listBusy: Boolean = false,
    val listError: Boolean = false,
    val pending: StocktakeSubmissionInput? = null,
    val confirmation: StocktakeSubmission? = null,
    val confirmationInput: String = "",
    val submitting: Boolean = false,
    val camera: Boolean = false,
    val switchTarget: StocktakeStock? = null,
    val changingLocation: Boolean = false,
    val changingSku: Boolean = false,
    val lastRecord: StocktakeRecord? = null,
    val error: Int? = null,
    val notice: Int? = null,
    val expired: Boolean = false,
) {
    val dirty: Boolean get() = lookup?.stock?.onHand?.let { quantity != it.toString() } ?: false
    val locked: Boolean get() = busy || submitting || pending != null || confirmation != null
    val readyToSubmit: Boolean get() = allowed && canWrite && context?.writeEnabled == true &&
        lookup?.usable() == true && stocktakeQuantity(quantity) != null && !locked && !expired
    val canScan: Boolean get() = allowed && context?.workspace?.status == "configured" &&
        !locked && stage != StocktakeStage.Count && !changingLocation && !changingSku && switchTarget == null
}

@HiltViewModel
class LocationStocktakeViewModel @Inject constructor(
    private val repository: LocationStocktakeRepository,
    private val drafts: LocationStocktakeDraftStore,
    private val scannerManager: ScannerManager,
    private val feedback: ReceivingScanFeedback,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocationStocktakeUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var queryJob: Job? = null
    private var listJob: Job? = null
    private var submitJob: Job? = null
    private var draftJob: Job? = null
    private var lastScan = ""
    private var lastScanAt = 0L

    fun enter(session: SessionState) {
        val scope = stocktakeScope(session)
        val allowed = !session.isOffline && "mobile-app:location-stocktakes" in session.permissions && "location_stocktakes" in session.permissions
        val canWrite = "location_stocktakes_submit" in session.permissions
        if (scope == state.value.scope && allowed == state.value.allowed && canWrite == state.value.canWrite) return
        generation++
        queryJob?.cancel()
        listJob?.cancel()
        submitJob?.cancel()
        draftJob?.cancel()
        mutableState.value = LocationStocktakeUiState(scope = scope, allowed = allowed, canWrite = canWrite)
        if (!allowed) {
            mutableState.update { it.copy(error = if (session.isOffline) R.string.st_online_required else R.string.st_no_permission) }
            return
        }
        val seq = generation
        queryJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            try {
                val draft = drafts.read(scope)
                val context = repository.context()
                if (seq != generation) return@launch
                val savedLookup = draft.lookup.takeIf { draft.workspaceVersion == context.workspace.version }
                mutableState.update {
                    it.copy(
                        context = context, location = draft.location, sku = draft.sku, quantity = draft.quantity,
                        pending = draft.pending, lookup = savedLookup, inputMethod = draft.inputMethod,
                        stage = if (savedLookup?.status == "ready") {
                            StocktakeStage.Count
                        } else if (draft.location.isBlank()) {
                            StocktakeStage.Location
                        } else {
                            StocktakeStage.Sku
                        },
                        expired = savedLookup?.let { saved -> !saved.usable() } ?: false,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_load_failed) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun retry() {
        if (!state.value.allowed || state.value.locked) return
        val seq = generation
        queryJob = viewModelScope.launch {
            try {
                val context = repository.context()
                if (seq == generation) mutableState.update { it.copy(context = context, error = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_load_failed) }
            }
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

    fun scan(raw: String) {
        val s = state.value
        if (!s.canScan) return
        val code = raw.trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
        if (code.isBlank() || code.length > 200) return
        val identity = "${s.stage}|${s.location}|$code"
        val now = System.currentTimeMillis()
        if (identity == lastScan && now - lastScanAt < 1200) return
        lastScan = identity
        lastScanAt = now
        if (s.stage == StocktakeStage.Location) lookup(code, "") else lookup(s.location, code)
    }

    private fun lookup(location: String, sku: String) {
        val seq = ++generation
        queryJob?.cancel()
        listJob?.cancel()
        mutableState.update { it.copy(location = location, sku = sku, lookup = null, quantity = "", busy = true, camera = false, locations = emptyList(), page = 0, hasMore = false, error = null, notice = null, confirmation = null, expired = false, listBusy = false, listError = false) }
        queryJob = viewModelScope.launch {
            try {
                val result = repository.lookup(location, sku)
                if (seq != generation) return@launch
                if (result.status == "location_ready") {
                    mutableState.update { it.copy(stage = StocktakeStage.Sku, lookup = result) }
                    feedback.success()
                } else if (result.status == "ready" && result.stock?.onHand != null && result.stock.onHand > 0) {
                    mutableState.update { it.copy(stage = StocktakeStage.Count, lookup = result, quantity = result.stock.onHand.toString(), inputMethod = "manual") }
                    feedback.success()
                } else {
                    mutableState.update { it.copy(stage = if (sku.isBlank()) StocktakeStage.Location else StocktakeStage.Sku, lookup = result, notice = if (result.status == "location_not_found") R.string.st_location_missing else R.string.st_sku_missing) }
                    feedback.failure()
                }
                persist()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) {
                    mutableState.update { it.copy(error = R.string.st_query_failed) }
                    feedback.failure()
                }
            } finally {
                if (seq == generation) {
                    mutableState.update { it.copy(busy = false) }
                    if (sku.isNotBlank()) loadLocations()
                }
            }
        }
    }

    fun loadLocations() {
        val s = state.value
        if (s.sku.isBlank() || s.listBusy) return
        val seq = generation
        val next = s.page + 1
        mutableState.update { it.copy(listBusy = true, listError = false) }
        listJob = viewModelScope.launch {
            try {
                val result = repository.locations(s.sku, next)
                if (seq == generation) mutableState.update { it.copy(locations = (it.locations + result.items).distinctBy(StocktakeStock::externalId), page = next, hasMore = result.hasMore) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(listError = true) }
            } finally {
                if (seq == generation) mutableState.update { it.copy(listBusy = false) }
            }
        }
    }

    fun editQuantity(value: String, zero: Boolean = false) {
        if (state.value.locked || state.value.stage != StocktakeStage.Count) return
        if (value.length > 16 || value.any { !it.isDigit() }) return
        mutableState.update { it.copy(quantity = value, inputMethod = if (zero) "zero" else "manual") }
        persist()
    }

    fun changeLocation() {
        if (state.value.locked) return
        if (state.value.dirty) mutableState.update { it.copy(changingLocation = true) } else clearTarget(true)
    }
    fun changeSku() {
        if (state.value.locked) return
        if (state.value.dirty) mutableState.update { it.copy(changingSku = true) } else clearTarget(false)
    }
    fun switchLocation(stock: StocktakeStock) {
        if (state.value.locked || (stock.onHand ?: 0) <= 0) return
        if (state.value.dirty) mutableState.update { it.copy(switchTarget = stock) } else lookup(stock.locationCode, stock.sku)
    }
    fun confirmSwitch() {
        val s = state.value
        mutableState.update { it.copy(switchTarget = null, changingLocation = false, changingSku = false) }
        if (s.switchTarget != null) lookup(s.switchTarget.locationCode, s.switchTarget.sku) else clearTarget(s.changingLocation)
    }
    fun cancelSwitch() {
        mutableState.update { it.copy(switchTarget = null, changingLocation = false, changingSku = false) }
    }
    private fun clearTarget(location: Boolean) {
        generation++
        queryJob?.cancel()
        listJob?.cancel()
        mutableState.update { it.copy(location = if (location) "" else it.location, sku = "", quantity = "", lookup = null, stage = if (location) StocktakeStage.Location else StocktakeStage.Sku, locations = emptyList(), page = 0, hasMore = false, error = null, notice = null, busy = false, listBusy = false, listError = false, expired = false) }
        persist()
    }

    fun confirmInput(value: String) {
        if (!state.value.submitting && value.length <= 16 && value.all(Char::isDigit)) mutableState.update { it.copy(confirmationInput = value) }
    }
    fun cancelConfirmation() {
        if (!state.value.submitting) {
            mutableState.update { it.copy(confirmation = null, pending = null, confirmationInput = "") }
            persist()
        }
    }
    fun tick() {
        mutableState.update { it.copy(expired = it.lookup?.let { lookup -> lookup.status == "ready" && !lookup.usable() } ?: false) }
    }
    fun requery() {
        if (!state.value.locked && state.value.location.isNotBlank() && state.value.sku.isNotBlank()) lookup(state.value.location, state.value.sku)
    }

    fun submit(confirm: Boolean = false) {
        val s = state.value
        if (!s.allowed || !s.canWrite || s.submitting || s.busy) return
        var input = s.pending
        if (input == null) {
            if (!s.readyToSubmit) return
            input = StocktakeSubmissionInput(s.lookup!!.snapshotId, UUID.randomUUID().toString(), stocktakeQuantity(s.quantity)!!, s.inputMethod)
        }
        if (confirm) {
            val quantity = stocktakeQuantity(s.confirmationInput)
            if (s.confirmation?.confirmationToken == null || quantity != input.countedQuantity) return
            input = input.copy(confirmationToken = s.confirmation.confirmationToken, confirmedQuantity = quantity)
        }
        val acceptedInput = input
        val seq = generation
        mutableState.update { it.copy(submitting = true, pending = acceptedInput, error = null) }
        submitJob = viewModelScope.launch {
            try {
                draftJob?.cancel()
                drafts.save(s.scope, s.draft(acceptedInput))
                val result = repository.submit(acceptedInput)
                if (seq != generation) return@launch
                mutableState.update { it.copy(lastRecord = result.record) }
                if (result.record.status == "awaiting_confirmation") {
                    mutableState.update { it.copy(confirmation = result, confirmationInput = "") }
                } else {
                    mutableState.update { it.copy(confirmation = null) }
                    observe(result.record, seq)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (seq == generation) mutableState.update { it.copy(error = R.string.st_recover_required) }
            } finally {
                if (seq == generation) {
                    mutableState.update { it.copy(submitting = false) }
                    persist()
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
                    mutableState.update { it.copy(pending = null, stage = StocktakeStage.Sku, sku = "", quantity = "", lookup = null, locations = emptyList(), notice = R.string.st_success) }
                    feedback.success()
                    return
                }
                "failed", "expired", "cancelled", "blocked_evidence" -> {
                    mutableState.update { it.copy(pending = null, error = if (record.status == "blocked_evidence") R.string.st_write_blocked else R.string.st_submit_failed) }
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

    private fun persist() {
        val s = state.value
        if (s.scope.isBlank()) return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            try {
                drafts.save(s.scope, s.draft(s.pending))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (s.scope == state.value.scope) mutableState.update { it.copy(error = R.string.st_save_failed) }
            }
        }
    }

    private fun LocationStocktakeUiState.draft(input: StocktakeSubmissionInput?) =
        StocktakeDraft(location, sku, quantity, input, lookup, context?.workspace?.version ?: 0, inputMethod)
}
