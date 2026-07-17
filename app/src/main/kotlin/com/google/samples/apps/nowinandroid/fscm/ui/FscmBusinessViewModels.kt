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

import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.ApprovalAdjustment
import com.google.samples.apps.nowinandroid.fscm.data.ApprovalValidationError
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelQrFormatException
import com.google.samples.apps.nowinandroid.fscm.data.BusinessFilter
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceipt
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptBoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptScanResult
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptStatistics
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseApproval
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseApprovalDetail
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseApprovalRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingQuickScanResponse
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SupplierOrder
import com.google.samples.apps.nowinandroid.fscm.data.SupplierOrderRepository
import com.google.samples.apps.nowinandroid.fscm.data.allowedSupplierOrderStatuses
import com.google.samples.apps.nowinandroid.fscm.data.validateApprovalInput
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

data class WarehouseReceivingUiState(
    val loading: Boolean = false,
    val filter: BusinessFilter = BusinessFilter.Pending,
    val sessions: List<ReceivingSession> = emptyList(),
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class WarehouseReceivingViewModel @Inject constructor(
    private val repository: ReceivingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WarehouseReceivingUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.sessions() }
            .onSuccess { rows -> _state.update { it.copy(loading = false, sessions = rows) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.load_receiving_failed)) } }
    }

    fun setFilter(filter: BusinessFilter) = _state.update { it.copy(filter = filter) }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }
}

data class CentralReceivingUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val filter: BusinessFilter = BusinessFilter.Pending,
    val receipts: List<CentralReceipt> = emptyList(),
    val statistics: CentralReceiptStatistics = CentralReceiptStatistics(),
    val recentScans: List<CentralReceiptScanResult> = emptyList(),
    val preview: CentralReceiptScanResult? = null,
    val previewRawCode: String = "",
    val quantity: String = "",
    val unrecognized: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class CentralReceivingViewModel @Inject constructor(
    private val repository: CentralReceivingRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CentralReceivingUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )

    fun load() = viewModelScope.launch {
        val filter = _state.value.filter
        _state.update { it.copy(loading = true, error = null) }
        runCatching { Triple(repository.receipts(filter), repository.statistics(), repository.pendingScans()) }
            .onSuccess { (rows, statistics, pending) ->
                _state.update { it.copy(loading = false, receipts = rows, statistics = statistics, recentScans = pending + it.recentScans.filterNot { scan -> scan.status == "pending_sync" }) }
            }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_central_load_failed)) } }
    }

    fun setFilter(filter: BusinessFilter) {
        _state.update { it.copy(filter = filter) }
        load()
    }

    fun preview(rawCode: String) {
        if (rawCode.isBlank() || _state.value.submitting || _state.value.preview != null) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, message = null, unrecognized = false) }
            runCatching { repository.preview(null, rawCode.trim()) }
                .onSuccess { result ->
                    _state.update {
                        if (result.isUnrecognized()) {
                            it.copy(submitting = false, previewRawCode = rawCode.trim(), unrecognized = true)
                        } else {
                            it.copy(
                                submitting = false,
                                preview = result,
                                previewRawCode = rawCode.trim(),
                                quantity = result.item.defaultReceivedQuantity().toString(),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (error.isUnrecognizedBoxLabel()) {
                            it.copy(submitting = false, previewRawCode = rawCode.trim(), unrecognized = true)
                        } else {
                            it.copy(submitting = false, error = error.businessMessage(R.string.scan_failed))
                        }
                    }
                }
        }
    }

    fun changeQuantity(value: String) = _state.update { it.copy(quantity = value.filter(Char::isDigit).take(8)) }

    fun confirm() {
        val current = _state.value
        val preview = current.preview ?: return
        val quantity = current.quantity.toIntOrNull()?.takeIf { it > 0 } ?: return
        if (current.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { repository.scan(preview.centralReceiptId.takeIf { it > 0 }, current.previewRawCode, quantity) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            preview = null,
                            previewRawCode = "",
                            quantity = "",
                            recentScans = (listOf(result) + it.recentScans).take(8),
                            message = UiText.Resource(
                                if (result.localFallback) {
                                    if (result.duplicate) R.string.business_offline_duplicate else R.string.business_offline_saved
                                } else if (result.duplicate) {
                                    R.string.business_box_already_received
                                } else {
                                    R.string.business_receiving_success_continue
                                },
                            ),
                        )
                    }
                    load()
                }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.businessMessage(R.string.business_confirm_receiving_failed)) } }
        }
    }

    fun clearPreview() = _state.update { it.copy(preview = null, previewRawCode = "", quantity = "", unrecognized = false) }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }
    fun retryPending(id: Long) = updatePending(id, retry = true)
    fun cancelPending(id: Long) = updatePending(id, retry = false)
    private fun updatePending(id: Long, retry: Boolean) = viewModelScope.launch {
        runCatching { if (retry) repository.retryPending(id) else repository.cancelPending(id) }
            .onSuccess { load() }
            .onFailure { error -> _state.update { it.copy(error = error.businessMessage(R.string.submit_failed)) } }
    }
    fun registerScanner(owner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(owner)
    }
    fun unregisterScanner() = scannerManager.popOnScanListener()
}

data class CentralReceiptDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val receipt: CentralReceipt? = null,
    val boxLabels: List<CentralReceiptBoxLabel> = emptyList(),
    val preview: CentralReceiptScanResult? = null,
    val previewRawCode: String = "",
    val recentScans: List<CentralReceiptScanResult> = emptyList(),
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class CentralReceiptDetailViewModel @Inject constructor(
    private val repository: CentralReceivingRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CentralReceiptDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.receipt(id) to repository.boxLabels(id) }
            .onSuccess { (receipt, labels) -> _state.update { it.copy(loading = false, receipt = receipt, boxLabels = labels) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_central_detail_load_failed)) } }
    }

    fun preview(id: Long, rawCode: String) {
        if (rawCode.isBlank() || _state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { repository.preview(id, rawCode.trim()) }
                .onSuccess { result -> _state.update { it.copy(submitting = false, preview = result, previewRawCode = rawCode.trim()) } }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.businessMessage(R.string.scan_failed)) } }
        }
    }

    fun confirm(id: Long) {
        val code = _state.value.previewRawCode
        if (code.isBlank() || _state.value.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { repository.scan(id, code) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            preview = null,
                            previewRawCode = "",
                            recentScans = (listOf(result) + it.recentScans).take(12),
                            message = if (result.localFallback) {
                                UiText.Resource(if (result.duplicate) R.string.business_offline_duplicate else R.string.business_offline_saved)
                            } else {
                                result.message.toUiText(R.string.business_receiving_success)
                            },
                        )
                    }
                    load(id)
                }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.businessMessage(R.string.submit_failed)) } }
        }
    }

    fun clearPreview() = _state.update { it.copy(preview = null, previewRawCode = "") }
    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }
    fun registerScanner(owner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(owner)
    }
    fun unregisterScanner() = scannerManager.popOnScanListener()
}

data class SupplierOrdersUiState(
    val loading: Boolean = false,
    val filter: BusinessFilter = BusinessFilter.Pending,
    val query: String = "",
    val orders: List<SupplierOrder> = emptyList(),
    val total: Int = 0,
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class SupplierOrdersViewModel @Inject constructor(private val repository: SupplierOrderRepository) : ViewModel() {
    private val _state = MutableStateFlow(SupplierOrdersUiState())
    val state = _state.asStateFlow()

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun setFilter(filter: BusinessFilter) {
        _state.update { it.copy(filter = filter) }
        load()
    }
    fun load() = viewModelScope.launch {
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.orders(current.filter, current.query) }
            .onSuccess { page -> _state.update { it.copy(loading = false, orders = page.rows, total = page.total) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_supplier_load_failed)) } }
    }

    fun updateStatus(order: SupplierOrder, target: String) {
        if (target !in allowedSupplierOrderStatuses(order.status) || _state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.updateStatus(order.id, target) }
                .onSuccess {
                    _state.update { it.copy(message = UiText.Resource(R.string.business_status_updated)) }
                    load()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_status_update_failed)) } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }
}

data class SupplierOrderDetailUiState(
    val loading: Boolean = false,
    val order: SupplierOrder? = null,
    val error: UiText? = null,
)

@HiltViewModel
class SupplierOrderDetailViewModel @Inject constructor(
    private val repository: SupplierOrderRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SupplierOrderDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.value = SupplierOrderDetailUiState(loading = true)
        runCatching { repository.order(id) }
            .onSuccess { order -> _state.value = SupplierOrderDetailUiState(order = order) }
            .onFailure { error ->
                _state.value = SupplierOrderDetailUiState(
                    error = error.businessMessage(R.string.business_supplier_detail_load_failed),
                )
            }
    }
}

data class PurchaseApprovalsUiState(
    val loading: Boolean = false,
    val filter: BusinessFilter = BusinessFilter.Pending,
    val query: String = "",
    val approvals: List<PurchaseApproval> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
class PurchaseApprovalsViewModel @Inject constructor(private val repository: PurchaseApprovalRepository) : ViewModel() {
    private val _state = MutableStateFlow(PurchaseApprovalsUiState())
    val state = _state.asStateFlow()

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun setFilter(filter: BusinessFilter) {
        _state.update { it.copy(filter = filter) }
        load(true)
    }
    fun load(reset: Boolean = true) {
        if (_state.value.loading) return
        val current = _state.value
        val page = if (reset) 1 else current.page + 1
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.approvals(current.filter, current.query, page) }
                .onSuccess { result ->
                    val rows = if (reset) result.rows else current.approvals + result.rows
                    _state.update { it.copy(loading = false, approvals = rows, page = page, total = result.total, hasMore = rows.size < result.total) }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_approval_load_failed)) } }
        }
    }
}

data class PurchaseApprovalDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val detail: PurchaseApprovalDetail = PurchaseApprovalDetail(),
    val adjustment: ApprovalAdjustment = ApprovalAdjustment.None,
    val adjustmentValue: String = "",
    val remark: String = "",
    val confirmAction: String? = null,
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class PurchaseApprovalDetailViewModel @Inject constructor(private val repository: PurchaseApprovalRepository) : ViewModel() {
    private val _state = MutableStateFlow(PurchaseApprovalDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.detail(id) }
            .onSuccess { detail -> _state.update { it.copy(loading = false, detail = detail) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.businessMessage(R.string.business_approval_detail_load_failed)) } }
    }

    fun setAdjustment(value: ApprovalAdjustment) = _state.update { it.copy(adjustment = value, adjustmentValue = "", error = null) }
    fun setAdjustmentValue(value: String) = _state.update { it.copy(adjustmentValue = value) }
    fun setRemark(value: String) = _state.update { it.copy(remark = value.take(300)) }

    fun requestApprove() {
        val state = _state.value
        val approval = state.detail.approval ?: return
        if (approval.status != "pending_quote") return
        if (state.detail.summary.missingPriceCount > 0 || approval.missingPriceCount > 0) {
            _state.update { it.copy(error = UiText.Resource(R.string.business_missing_quotes)) }
            return
        }
        val error = validateApprovalInput(state.adjustment, state.adjustmentValue.toDoubleOrNull(), state.remark, approval.totalAmount)
        _state.update { it.copy(error = error?.toUiText(), confirmAction = if (error == null) "approve" else null) }
    }

    fun requestReject() {
        if (_state.value.remark.isBlank()) {
            _state.update { it.copy(error = UiText.Resource(R.string.business_rejection_reason_required)) }
        } else {
            _state.update { it.copy(confirmAction = "reject", error = null) }
        }
    }

    fun dismissConfirmation() = _state.update { it.copy(confirmAction = null) }

    fun confirm(id: Long) {
        val current = _state.value
        val action = current.confirmAction ?: return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, confirmAction = null, error = null) }
            runCatching {
                if (action == "approve") {
                    repository.approve(id, current.adjustment, current.adjustmentValue.toDoubleOrNull(), current.remark)
                } else {
                    repository.reject(id, current.remark)
                }
            }.onSuccess {
                _state.update { it.copy(submitting = false, message = UiText.Resource(if (action == "approve") R.string.business_approval_succeeded else R.string.business_rejection_succeeded)) }
                load(id)
            }.onFailure { error -> _state.update { it.copy(submitting = false, error = error.businessMessage(R.string.business_approval_action_failed)) } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }
}

private fun ReceivingQuickScanResponse.isUnrecognized(): Boolean =
    data.labelCode.isBlank() || data.status in setOf("unknown", "wrong_order", "voided")

private fun CentralReceiptScanResult.isUnrecognized(): Boolean =
    item == null || status in setOf("unknown", "wrong_order", "exception")

private fun ReceivingScanItem.defaultReceivedQuantity(): Int =
    actualReceivedQty.takeIf { it > 0 } ?: qtyPerCarton.takeIf { it > 0 } ?: boxQty.takeIf { it > 0 } ?: 1

private fun CentralReceiptBoxLabel?.defaultReceivedQuantity(): Int =
    this?.actualBoxQty?.takeIf { it > 0 } ?: this?.qtyPerCarton?.takeIf { it > 0 } ?: 1

private fun Throwable.isUnrecognizedBoxLabel(): Boolean {
    if (this !is FscmApiException) return false
    if (statusCode == 404) return true
    val message = message.orEmpty().lowercase()
    return listOf("不存在", "未找到", "无法识别", "未识别", "not found", "not_found").any(message::contains)
}

private fun Throwable.businessMessage(@StringRes fallback: Int): UiText =
    (this as? BoxLabelQrFormatException)?.message?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
        ?: (this as? FscmApiException)?.serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
        ?: UiText.Resource(fallback)

private fun String.toUiText(@StringRes fallback: Int): UiText =
    takeIf(String::isNotBlank)?.let(UiText::Dynamic) ?: UiText.Resource(fallback)

private fun ApprovalValidationError.toUiText(): UiText.Resource = UiText.Resource(
    when (this) {
        ApprovalValidationError.AmountMustBePositive -> R.string.approval_amount_positive
        ApprovalValidationError.AmountExceedsTotal -> R.string.approval_amount_exceeds_total
        ApprovalValidationError.AmountRemarkRequired -> R.string.approval_amount_remark_required
        ApprovalValidationError.RateOutOfRange -> R.string.approval_rate_range
        ApprovalValidationError.RateRemarkRequired -> R.string.approval_rate_remark_required
    },
)
