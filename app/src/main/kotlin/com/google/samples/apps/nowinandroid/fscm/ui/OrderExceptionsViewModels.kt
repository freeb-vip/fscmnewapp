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

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionListSource
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionScope
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.OrderExceptionsRepository
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseException
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionForm
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionType
import com.google.samples.apps.nowinandroid.fscm.data.requiresMeasures
import com.google.samples.apps.nowinandroid.fscm.data.restoreWarehouseExceptionForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

private fun Throwable.exceptionMessage(): UiText = UiText.Resource(
    when {
        message == "exception_photo_large" -> R.string.oe_photo_large
        message == "exception_photo_size" -> R.string.oe_photo_size
        (this as? FscmApiException)?.statusCode == 409 -> R.string.oe_conflict
        (this as? FscmApiException)?.statusCode in listOf(401, 403) -> R.string.oe_permission_error
        (this as? FscmApiException)?.statusCode == 400 -> R.string.oe_invalid_input
        else -> R.string.oe_request_error
    },
)

data class ExceptionsListState(
    val timezone: String = "",
    val filterTypes: List<WarehouseExceptionType> = emptyList(),
    val rows: List<WarehouseException> = emptyList(),
    val resolved: Boolean = false,
    val total: Long = 0,
    val open: Long = 0,
    val completed: Long = 0,
    val page: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val countsLoaded: Boolean = false,
    val snapshotAt: String? = null,
    val refreshStatus: String = "",
    val snapshotVersion: Long = 0,
    val retryMore: Boolean = false,
    val draft: Map<String, String> = emptyMap(),
    val error: UiText? = null,
)

@HiltViewModel
class OrderExceptionsListViewModel internal constructor(private val repository: ExceptionListSource, private val now: () -> Long) : ViewModel() {
    @Inject constructor(repository: OrderExceptionsRepository) : this(repository, SystemClock::elapsedRealtime)
    private val mutable = MutableStateFlow(ExceptionsListState())
    val state = mutable.asStateFlow()
    private var scope: ExceptionScope? = null
    private var filters: Map<String, String> = emptyMap()
    private var job: Job? = null
    private var generation = 0
    private var loadedAt = 0L
    private var dirty = false
    var scrollIndex = 0
        private set
    var scrollOffset = 0
        private set

    init {
        viewModelScope.launch {
            repository.scopes.collect { active ->
                if (scope != null && scope != active) {
                    job?.cancel()
                    generation++
                    scope = null
                    filters = emptyMap()
                    loadedAt = 0L
                    dirty = false
                    saveScroll(0, 0)
                    mutable.value = ExceptionsListState()
                }
            }
        }
        viewModelScope.launch {
            repository.changes.collect { change ->
                if (change != null && change.scope == scope) {
                    dirty = true
                    job?.cancel()
                    generation++
                    mutable.update { it.copy(loading = false, loadingMore = false, refreshing = false) }
                    val previous = state.value.rows.find { it.id == change.detail.id }
                    if (previous != null && previous.status == "open" && change.detail.status == "resolved") {
                        mutable.update { it.copy(rows = it.rows.filterNot { row -> row.id == previous.id }, total = (it.total - 1).coerceAtLeast(0), open = (it.open - 1).coerceAtLeast(0), completed = it.completed + 1) }
                    }
                }
            }
        }
    }

    fun saveScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }
    fun editFilter(key: String, value: String) {
        mutable.update { it.copy(draft = it.draft + (key to value)) }
    }
    fun resume() {
        if (!state.value.loading && (dirty || loadedAt == 0L || now() - loadedAt >= 60_000)) refresh()
    }
    fun refreshIfStale() {
        if (!state.value.loading && state.value.refreshStatus in listOf("refreshing", "building")) {
            refresh()
        } else {
            resume()
        }
    }
    fun select(resolved: Boolean) {
        if (state.value.resolved == resolved) return
        mutable.update { it.copy(resolved = resolved, rows = emptyList(), page = 0, total = if (resolved) it.completed else it.open) }
        saveScroll(0, 0)
        refresh()
    }
    fun search(clear: Boolean = false) {
        if (clear) mutable.update { it.copy(draft = emptyMap()) }
        filters = state.value.draft.mapValues { it.value.trim() }.filterValues { it.isNotBlank() }
        mutable.update { it.copy(rows = emptyList(), page = 0, total = 0, countsLoaded = false) }
        saveScroll(0, 0)
        refresh()
    }
    fun retry() = refresh(more = state.value.retryMore)
    fun refresh(more: Boolean = false, userInitiated: Boolean = false) {
        if (more && (state.value.loading || state.value.rows.size >= state.value.total)) return
        job?.cancel()
        val epoch = ++generation
        val before = state.value
        val requestFilters = filters.toMap()
        val page = if (more) before.page + 1 else 1
        mutable.update { it.copy(loading = true, loadingMore = more, refreshing = userInitiated, error = null, retryMore = more) }
        job = viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                val result = repository.list(actor, before.resolved, requestFilters, page)
                if (epoch == generation && more && before.snapshotVersion > 0 && result.snapshotVersion != before.snapshotVersion) {
                    refresh()
                    return@launch
                }
                if (epoch == generation) {
                    loadedAt = now()
                    dirty = false
                    mutable.update { it.copy(timezone = result.timezone, filterTypes = if (more) it.filterTypes else result.filterTypes, rows = if (more) (it.rows + result.items).distinctBy(WarehouseException::id) else result.items.distinctBy(WarehouseException::id), total = result.total, open = result.open, completed = result.resolved, page = page, loading = false, loadingMore = false, refreshing = false, countsLoaded = true, snapshotAt = result.snapshotAt, refreshStatus = result.refreshStatus, snapshotVersion = result.snapshotVersion) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) mutable.update { it.copy(loading = false, loadingMore = false, refreshing = false, error = error.exceptionMessage()) }
            }
        }
    }
}

data class ExceptionDetailState(
    val detail: WarehouseException? = null,
    val form: WarehouseExceptionForm = WarehouseExceptionForm(),
    val busy: Boolean = false,
    val error: UiText? = null,
    val pending: Boolean = false,
    val failedUpload: Uri? = null,
    val measuresError: Boolean = false,
    val uploadedNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class OrderExceptionDetailViewModel @Inject constructor(
    private val repository: OrderExceptionsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(ExceptionDetailState())
    val state = mutable.asStateFlow()
    private var scope: ExceptionScope? = null
    private var reportId: Long = 0

    fun load(id: Long) {
        if (state.value.busy) return
        reportId = id
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                val detail = repository.detail(actor, id)
                val restored = saved.get<String>("form")?.let { runCatching { json.decodeFromString(WarehouseExceptionForm.serializer(), it) }.getOrNull() }
                val committed = restored != null && detail.resolutions.any { it.requestId == restored.requestId }
                if (committed || detail.status != "open") {
                    saved.remove<String>("pending")
                    saved.remove<String>("failed_upload")
                }
                val form = restoreWarehouseExceptionForm(detail, restored)
                mutable.update { it.copy(detail = detail, form = form, busy = false, pending = saved.get<String>("pending") != null, failedUpload = saved.get<String>("failed_upload")?.let(Uri::parse)) }
                if (detail.status == "open") persist(form) else saved.remove<String>("form")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(detail = null, busy = false, error = error.exceptionMessage()) }
            }
        }
    }

    private fun persist(form: WarehouseExceptionForm) {
        saved["form"] = json.encodeToString(WarehouseExceptionForm.serializer(), form)
    }

    fun edit(change: (WarehouseExceptionForm) -> WarehouseExceptionForm) {
        if (state.value.busy || state.value.pending) return
        val form = change(state.value.form)
        persist(form)
        mutable.update { it.copy(form = form, error = null, measuresError = false) }
    }

    fun upload(uri: Uri) {
        if (state.value.busy || state.value.pending || state.value.form.attachmentKeys.size >= 9) return
        mutable.update { it.copy(busy = true, failedUpload = null, error = null) }
        viewModelScope.launch {
            try {
                saved["failed_upload"] = uri.toString()
                val photo = repository.upload(requireNotNull(scope), reportId, state.value.form.requestId, uri)
                val form = state.value.form.copy(attachmentKeys = state.value.form.attachmentKeys + photo.key)
                persist(form)
                saved.remove<String>("failed_upload")
                mutable.update { it.copy(form = form, busy = false, uploadedNames = it.uploadedNames + (photo.key to photo.name)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, failedUpload = uri, error = error.exceptionMessage()) }
            }
        }
    }

    fun removeFailedUpload() {
        saved.remove<String>("failed_upload")
        mutable.update { it.copy(failedUpload = null, error = null) }
    }
    fun cameraFile(): File = repository.cameraFile()
    suspend fun photo(key: String): ByteArray = repository.photo(requireNotNull(scope), reportId, key)

    fun validateForConfirmation(): Boolean {
        val form = state.value.form
        if (form.problemType.isEmpty()) {
            mutable.update { it.copy(error = UiText.Resource(R.string.oe_select_problem)) }
            return false
        }
        if (form.requiresMeasures()) {
            mutable.update { it.copy(measuresError = true) }
            return false
        }
        return true
    }

    fun submit() {
        val current = state.value
        if (current.busy || current.failedUpload != null) return
        val form = saved.get<String>("pending")?.let { json.decodeFromString(WarehouseExceptionForm.serializer(), it) } ?: current.form
        if (form.problemType.isEmpty()) {
            mutable.update { it.copy(error = UiText.Resource(R.string.oe_select_problem)) }
            return
        }
        if (!current.pending && !validateForConfirmation()) return
        saved["pending"] = json.encodeToString(WarehouseExceptionForm.serializer(), form)
        mutable.update { it.copy(busy = true, pending = true, error = null) }
        viewModelScope.launch {
            try {
                val detail = repository.resolve(requireNotNull(scope), reportId, form)
                saved.remove<String>("pending")
                saved.remove<String>("form")
                mutable.value = ExceptionDetailState(detail = detail, form = WarehouseExceptionForm(version = detail.version, collaborationVersion = detail.collaborationVersion))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A 4xx response requires review of the latest report; ambiguous failures keep the original request frozen.
                val responseStatus = (error as? FscmApiException)?.statusCode
                val definite = responseStatus != null && responseStatus in 400..499
                if (definite) saved.remove<String>("pending")
                mutable.update { it.copy(busy = false, pending = !definite, error = if (definite) error.exceptionMessage() else UiText.Resource(R.string.oe_pending_result)) }
                if ((error as? FscmApiException)?.statusCode == 409) {
                    try {
                        val latest = repository.detail(requireNotNull(scope), reportId)
                        val next = restoreWarehouseExceptionForm(latest, current.form)
                        if (latest.status == "open") persist(next) else saved.remove<String>("form")
                        mutable.update { it.copy(detail = latest, form = next) }
                    } catch (refresh: Exception) {
                        if (refresh is CancellationException) throw refresh
                    }
                }
            }
        }
    }
}
