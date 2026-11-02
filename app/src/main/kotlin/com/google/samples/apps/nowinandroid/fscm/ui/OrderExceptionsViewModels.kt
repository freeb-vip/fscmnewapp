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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionGrant
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionGrantOptions
import com.google.samples.apps.nowinandroid.fscm.data.ExceptionScope
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.OrderExceptionsRepository
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseException
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseExceptionForm
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

private fun Throwable.exceptionMessage(): String = (this as? FscmApiException)?.serverMessage
    ?: message?.takeIf { this is IllegalArgumentException }
    ?: "请求失败，请检查网络或访问权限后重试"

data class ExceptionsListState(
    val rows: List<WarehouseException> = emptyList(),
    val resolved: Boolean = false,
    val total: Long = 0,
    val open: Long = 0,
    val completed: Long = 0,
    val page: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val canManage: Boolean = false,
    val grants: ExceptionGrantOptions? = null,
    val grantUser: Long? = null,
    val selectedGrants: Set<String> = emptySet(),
    val grantBusy: Boolean = false,
)

@HiltViewModel
class OrderExceptionsListViewModel @Inject constructor(private val repository: OrderExceptionsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ExceptionsListState())
    val state = mutable.asStateFlow()
    private var scope: ExceptionScope? = null
    private var filters: Map<String, String> = emptyMap()
    private var job: Job? = null
    private var generation = 0

    fun select(resolved: Boolean) {
        if (state.value.resolved == resolved) return
        mutable.value = ExceptionsListState(resolved = resolved)
        refresh()
    }

    fun search(values: Map<String, String>) {
        filters = values.mapValues { it.value.trim() }
        refresh()
    }

    fun refresh(more: Boolean = false) {
        if (more && state.value.loading) return
        job?.cancel()
        val epoch = ++generation
        val before = state.value
        val page = if (more) before.page + 1 else 1
        mutable.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                val result = repository.list(actor, before.resolved, filters, page)
                if (epoch == generation) mutable.update { it.copy(rows = if (more) (it.rows + result.items).distinctBy(WarehouseException::id) else result.items, total = result.total, open = result.open, completed = result.resolved, page = page, loading = false, canManage = result.canManage) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) mutable.update { it.copy(loading = false, rows = emptyList(), error = error.exceptionMessage()) }
            }
        }
    }

    fun grantOptions(user: Long? = null) {
        if (state.value.grantBusy) return
        mutable.update { it.copy(grantBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                val result = repository.grants(actor, user)
                mutable.update { it.copy(grants = result, grantUser = user, grantBusy = false, selectedGrants = result.grants.map { g -> "${g.source}:${g.warehouse}" }.toSet()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(grantBusy = false, error = error.exceptionMessage()) }
            }
        }
    }

    fun toggleGrant(key: String, checked: Boolean) {
        mutable.update { it.copy(selectedGrants = if (checked) it.selectedGrants + key else it.selectedGrants - key) }
    }
    fun closeGrants() {
        if (!state.value.grantBusy) mutable.update { it.copy(grants = null) }
    }
    fun saveGrants() {
        val before = state.value
        val user = before.grantUser ?: return
        if (before.grantBusy) return
        mutable.update { it.copy(grantBusy = true) }
        viewModelScope.launch {
            try {
                val grants = before.grants?.warehouses.orEmpty().filter { it.key in before.selectedGrants }.map { ExceptionGrant(it.source, it.warehouse) }
                repository.saveGrants(requireNotNull(scope), user, grants)
                mutable.update { it.copy(grantBusy = false, grants = null) }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(grantBusy = false, error = error.exceptionMessage()) }
            }
        }
    }
}

data class ExceptionDetailState(
    val detail: WarehouseException? = null,
    val form: WarehouseExceptionForm = WarehouseExceptionForm(),
    val busy: Boolean = false,
    val error: String? = null,
    val pending: Boolean = false,
    val failedUpload: Uri? = null,
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
        mutable.update { it.copy(form = form, error = null) }
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

    fun submit() {
        val current = state.value
        if (current.busy || current.failedUpload != null) return
        val form = saved.get<String>("pending")?.let { json.decodeFromString(WarehouseExceptionForm.serializer(), it) } ?: current.form
        if (form.problemType.isEmpty() || (form.responsibilityType == "person" && form.participantIds.isEmpty()) || (form.responsibilityType != "person" && form.responsibilityNote.isBlank())) {
            mutable.update { it.copy(error = "请选择问题类型并填写责任归属") }
            return
        }
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
                mutable.update { it.copy(busy = false, pending = !definite, error = if (definite) error.exceptionMessage() else "尚未确认提交结果，请点击“确认处理结果”使用原请求重试") }
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
