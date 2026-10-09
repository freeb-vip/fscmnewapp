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
import com.google.samples.apps.nowinandroid.fscm.data.OfflineShippingRepository
import com.google.samples.apps.nowinandroid.fscm.data.SessionExpiredException
import com.google.samples.apps.nowinandroid.fscm.data.ShippingItem
import com.google.samples.apps.nowinandroid.fscm.data.ShippingPhoto
import com.google.samples.apps.nowinandroid.fscm.data.ShippingScope
import com.google.samples.apps.nowinandroid.fscm.data.ShippingTask
import com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

internal fun Throwable.shippingMessage(): Int {
    val api = this as? FscmApiException
    val reason = ((api?.payload as? JsonObject)?.get("data") as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
    return when {
        this is SessionExpiredException || api?.statusCode == 401 || api?.statusCode == 403 -> R.string.shipping_access_error
        api?.statusCode == 404 || reason == "task_unavailable" -> R.string.shipping_unavailable
        reason == "wrong_sku" -> R.string.shipping_wrong_sku
        reason == "already_picked" -> R.string.shipping_already_picked
        reason == "task_changed" || api?.statusCode == 409 -> R.string.shipping_changed
        reason == "photo_required" -> R.string.shipping_photo_required
        reason == "invalid_photo" -> R.string.shipping_invalid_photo
        else -> R.string.shipping_request_failed
    }
}

data class ShippingListState(
    val rows: List<ShippingTask> = emptyList(),
    val shipped: Boolean = false,
    val keyword: String = "",
    val total: Long = 0,
    val page: Int = 0,
    val loading: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class OfflineShippingListViewModel @Inject constructor(private val repository: OfflineShippingRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShippingListState())
    val state = _state.asStateFlow()
    private var scope: ShippingScope? = null
    init {
        refresh()
    }

    fun select(shipped: Boolean) {
        if (_state.value.loading || _state.value.shipped == shipped) return
        _state.update { ShippingListState(shipped = shipped, keyword = it.keyword) }
        refresh()
    }

    fun search(keyword: String) {
        if (_state.value.loading) return
        _state.update { it.copy(keyword = keyword.trim(), rows = emptyList(), page = 0) }
        refresh()
    }

    fun refresh(more: Boolean = false) {
        if (_state.value.loading) return
        val current = _state.value
        val page = if (more) current.page + 1 else 1
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                val result = repository.list(actor, current.shipped, current.keyword, page)
                _state.update { it.copy(rows = if (more) (it.rows + result.rows).distinctBy(ShippingTask::orderId) else result.rows, total = result.total, page = page, loading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val revoked = (error as? FscmApiException)?.statusCode in listOf(401, 403, 404) || error is IllegalStateException || error is SessionExpiredException
                _state.update { it.copy(loading = false, rows = if (revoked) emptyList() else it.rows, error = error.shippingMessage()) }
            }
        }
    }
}

data class ShippingDetailState(
    val task: ShippingTask? = null,
    val busy: Boolean = false,
    val error: Int? = null,
    val scan: ShippingItem? = null,
    val scanCode: String = "",
    val localPhotos: List<File> = emptyList(),
    val pending: Boolean = false,
    val preview: ByteArray? = null,
)

@HiltViewModel
class OfflineShippingDetailViewModel @Inject constructor(
    private val repository: OfflineShippingRepository,
    private val scanner: ScannerManager,
    private val feedback: ReceivingScanFeedback,
) : ViewModel() {
    private val _state = MutableStateFlow(ShippingDetailState())
    val state = _state.asStateFlow()
    val scannerState = scanner.scannerState
    private var scope: ShippingScope? = null
    private var orderId = 0L

    fun load(id: Long) {
        if (orderId == id) return
        orderId = id
        refresh()
    }

    private fun work(block: suspend (ShippingScope) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val actor = scope ?: repository.scope().also { scope = it }
                block(actor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                feedback.failure()
                val revoked = (error as? FscmApiException)?.statusCode in listOf(401, 403, 404) || error is IllegalStateException || error is SessionExpiredException
                _state.update { it.copy(error = error.shippingMessage(), scan = null, task = if (revoked) null else it.task, preview = null, localPhotos = if (revoked) emptyList() else it.localPhotos) }
                if ((error as? FscmApiException)?.statusCode == 409) {
                    scope?.let { actor -> runCatching { repository.detail(actor, orderId) }.onSuccess { task -> _state.update { it.copy(task = task) } } }
                }
            } finally {
                val pending = scope?.let { runCatching { repository.pending(it, orderId) }.getOrDefault(false) } ?: false
                _state.update { it.copy(busy = false, pending = pending) }
            }
        }
    }

    fun refresh() = work { actor ->
        repository.retry(actor, orderId)
        reload(actor)
    }

    private suspend fun reload(actor: ShippingScope) {
        val task = repository.detail(actor, orderId)
        val local = repository.localPhotos(actor, orderId)
        _state.update { it.copy(task = task, localPhotos = local) }
    }

    fun scan(raw: String) {
        val current = _state.value
        val code = raw.trim()
        if (code.isBlank() || current.busy || current.pending || current.scan != null || current.task?.stage != "picking") return
        work { actor ->
            val item = repository.scan(actor, orderId, code)
            feedback.success()
            _state.update { it.copy(scan = item, scanCode = code) }
        }
    }

    fun dismissScan() {
        if (!_state.value.busy) _state.update { it.copy(scan = null) }
    }
    fun confirmPick() {
        val current = _state.value
        val item = current.scan ?: return
        val task = current.task ?: return
        work { actor ->
            val updated = repository.act(
                actor,
                orderId,
                "pick",
                buildJsonObject {
                    put("expected_version", task.version)
                    put("sku", current.scanCode)
                    put("item_id", item.itemId)
                },
            )
            _state.update { it.copy(task = updated, scan = null) }
            feedback.success()
        }
    }

    fun complete(carrier: String, trackingNo: String) {
        val task = _state.value.task ?: return
        if (_state.value.pending || task.stage !in listOf("packing", "shipping")) return
        if (_state.value.localPhotos.any { it.name.startsWith(task.stage + "_") }) {
            _state.update { it.copy(error = R.string.shipping_upload_first) }
            return
        }
        val photos = task.photos.filter { it.stage == task.stage }
        if (photos.isEmpty()) {
            _state.update { it.copy(error = R.string.shipping_photo_required) }
            return
        }
        work { actor ->
            val updated = repository.act(
                actor,
                orderId,
                if (task.stage == "packing") "pack" else "ship",
                buildJsonObject {
                    put("expected_version", task.version)
                    put("photo_ids", JsonArray(photos.map { JsonPrimitive(it.id) }))
                    put("carrier", carrier.trim())
                    put("tracking_no", trackingNo.trim())
                },
            )
            _state.update { it.copy(task = updated) }
            feedback.success()
        }
    }

    fun preparePhoto(onReady: (File) -> Unit) {
        val task = _state.value.task ?: return
        if (_state.value.pending || task.stage !in listOf("packing", "shipping")) return
        work { actor -> onReady(repository.newPhoto(actor, orderId, task.stage)) }
    }

    fun captured() = work { actor ->
        val photos = repository.localPhotos(actor, orderId)
        _state.update { it.copy(localPhotos = photos) }
        for (file in photos) {
            val uploaded = repository.upload(actor, orderId, file)
            _state.update { current ->
                current.copy(localPhotos = current.localPhotos - file, task = current.task?.copy(photos = (current.task.photos + uploaded).distinctBy(ShippingPhoto::id)))
            }
        }
        reload(actor)
    }

    fun retryUpload(file: File) = work { actor ->
        repository.upload(actor, orderId, file)
        reload(actor)
    }
    fun removeLocal(file: File) = work { actor ->
        repository.removeLocal(actor, orderId, file)
        reload(actor)
    }
    fun removePhoto(photo: ShippingPhoto) = work { actor ->
        repository.removePhoto(actor, orderId, photo)
        reload(actor)
    }
    fun preview(photo: ShippingPhoto) = work { actor ->
        val bytes = repository.photo(actor, orderId, photo)
        _state.update { it.copy(preview = bytes) }
    }
    fun dismissPreview() {
        _state.update { it.copy(preview = null) }
    }
    fun cameraDenied() {
        _state.update { it.copy(error = R.string.shipping_camera_permission) }
    }
    fun registerScanner(owner: LifecycleOwner) {
        scanner.pushOnScanListener(::scan)
        scanner.register(owner)
    }
    fun unregisterScanner() {
        scanner.popOnScanListener()
    }
    fun toggleScanner() {
        scanner.applyInputMode(if (scanner.scannerState.value.effectiveMode == ScannerInputMode.IntentBroadcast) ScannerInputMode.Keyboard else ScannerInputMode.IntentBroadcast)
    }
}
