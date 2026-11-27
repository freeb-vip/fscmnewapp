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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.FbsDetail
import com.google.samples.apps.nowinandroid.fscm.data.FbsOrder
import com.google.samples.apps.nowinandroid.fscm.data.FbsRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.ShippingScope
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

data class FbsState(
    val detail: FbsDetail? = null,
    val orders: List<FbsOrder> = emptyList(),
    val total: Int = 0,
    val busy: Boolean = false,
    val packageDraft: String = "",
    val message: Int? = null,
)

@HiltViewModel
class FbsViewModel @Inject constructor(
    private val repository: FbsRepository,
    private val scanner: ScannerManager,
) : ViewModel() {
    private val mutable = MutableStateFlow(FbsState())
    val state = mutable.asStateFlow()
    private var actor: ShippingScope? = null

    private fun work(block: suspend (ShippingScope) -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val current = repository.scope()
                check(actor == null || actor == current)
                actor = current
                block(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val api = e as? FscmApiException
                val message = when (api?.serverMessage) {
                    "wrong_sku" -> R.string.fbs_wrong_sku
                    "already_picked" -> R.string.fbs_already_picked
                    "invalid_packages" -> R.string.fbs_invalid_packages
                    "missing_packing" -> R.string.fbs_photo_pack
                    "missing_signed" -> R.string.fbs_signed
                    "missing_vehicle" -> R.string.fbs_vehicle
                    "stage_changed" -> R.string.fbs_stage_changed
                    else -> if (api?.statusCode == 409) R.string.fbs_stage_changed else R.string.fbs_error
                }
                mutable.update { it.copy(message = message) }
                val id = mutable.value.detail?.order?.id
                if (id != null && api?.statusCode == 409) actor?.let { runCatching { reload(it, id) } }
                if (api?.statusCode in listOf(401, 403, 404)) mutable.update { it.copy(detail = null, orders = emptyList()) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun list(search: String, page: Int, completed: Boolean) = work { scope ->
        val result = repository.list(scope, search, page, completed)
        mutable.update { it.copy(orders = result.rows, total = result.total) }
    }

    private suspend fun reload(scope: ShippingScope, id: Long): FbsDetail =
        repository.detail(scope, id).also { detail ->
            if (detail.order.stage != "packing") repository.savePackageDraft(scope, id, "")
            mutable.update { it.copy(detail = detail, packageDraft = repository.packageDraft(scope, id)) }
        }

    fun changePackages(value: String) {
        val order = mutable.value.detail?.order ?: return
        val scope = actor ?: return
        if (order.stage != "packing" || value.length > 7 || value.any { !it.isDigit() }) return
        repository.savePackageDraft(scope, order.id, value)
        mutable.update { it.copy(packageDraft = value) }
    }

    fun detail(id: Long) = work { scope ->
        val task = reload(scope, id)
        val photos = repository.pending(scope, id)
        for (photo in photos) repository.upload(scope, task.order, photo)
        if (photos.isNotEmpty()) reload(scope, id)
    }

    fun action(action: String, barcode: String = "", packages: Int = 0) = work { scope ->
        val order = requireNotNull(mutable.value.detail).order
        repository.act(
            scope,
            order.id,
            action,
            buildJsonObject {
                put("version", order.version)
                put("barcode", barcode)
                put("packages", packages)
            },
        )
        reload(scope, order.id)
    }

    fun cameraFile(id: Long, kind: String): File = repository.newPhoto(requireNotNull(actor), id, kind)

    fun savePdf(fileId: String, uri: Uri) = work { scope ->
        val task = requireNotNull(mutable.value.detail)
        repository.download(scope, task.order, task.files.first { it.id == fileId }, uri)
        mutable.update { it.copy(message = R.string.fbs_saved) }
    }

    fun register(owner: LifecycleOwner, scanned: (String) -> Unit) {
        scanner.pushOnScanListener(scanned)
        scanner.register(owner)
    }

    fun unregister() = scanner.popOnScanListener()
    fun denied() = mutable.update { it.copy(message = R.string.fbs_no_camera) }
}
