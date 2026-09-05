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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.CreateManufacturerBillPaymentRequest
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillBucket
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillDetail
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillGroup
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillQuery
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillSummary
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentBillRepository
import com.google.samples.apps.nowinandroid.fscm.data.PaymentAttachment
import com.google.samples.apps.nowinandroid.fscm.data.PaymentConfigOption
import com.google.samples.apps.nowinandroid.fscm.data.ReviewManufacturerBillRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ManufacturerPaymentBillsUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val bucket: ManufacturerBillBucket = ManufacturerBillBucket.PendingPayment,
    val query: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val showFilters: Boolean = false,
    val groups: List<ManufacturerBillGroup> = emptyList(),
    val summary: ManufacturerBillSummary = ManufacturerBillSummary(),
    val page: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
class ManufacturerPaymentBillsViewModel @Inject constructor(
    private val repository: ManufacturerPaymentBillRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ManufacturerPaymentBillsUiState())
    val state = _state.asStateFlow()

    fun load(reset: Boolean = true) {
        val current = _state.value
        if (current.loading || current.loadingMore) return
        val nextPage = if (reset) 1 else current.page + 1
        _state.update { it.copy(loading = reset, loadingMore = !reset, error = null) }
        viewModelScope.launch {
            val snapshot = _state.value
            runCatching {
                repository.groups(
                    ManufacturerBillQuery(
                        bucket = snapshot.bucket,
                        keyword = snapshot.query,
                        startDate = snapshot.startDate,
                        endDate = snapshot.endDate,
                        page = nextPage,
                    ),
                )
            }.onSuccess { page ->
                _state.update { state ->
                    val groups = if (reset) page.items else (state.groups + page.items).distinctBy { it.supplierOrder.id }
                    state.copy(
                        loading = false,
                        loadingMore = false,
                        groups = groups,
                        summary = page.summary,
                        page = page.page,
                        total = page.total,
                        hasMore = groups.size < page.total,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = error.paymentBillMessage(R.string.manufacturer_bills_load_failed),
                    )
                }
            }
        }
    }

    fun setBucket(bucket: ManufacturerBillBucket) {
        _state.update { it.copy(bucket = bucket) }
        load(true)
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun openFilters() = _state.update { it.copy(showFilters = true) }

    fun closeFilters() = _state.update { it.copy(showFilters = false) }

    fun applyFilters(startDate: String, endDate: String) {
        _state.update {
            it.copy(
                startDate = startDate.trim(),
                endDate = endDate.trim(),
                showFilters = false,
            )
        }
        load(true)
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

enum class ManufacturerBillAction {
    Approve,
    Reject,
    Pay,
}

data class ManufacturerPaymentBillDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val uploading: Boolean = false,
    val detail: ManufacturerBillDetail? = null,
    val payments: List<com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillPayment> = emptyList(),
    val action: ManufacturerBillAction? = null,
    val paymentInfoId: Long? = null,
    val partialPayment: Boolean = false,
    val amount: String = "",
    val paymentMethod: String = "",
    val paymentSource: String = "",
    val chengyisheOrderNo: String = "",
    val paymentMethods: List<PaymentConfigOption> = emptyList(),
    val paymentSources: List<PaymentConfigOption> = emptyList(),
    val payer: String = "",
    val paymentDate: String = todayDate(),
    val remark: String = "",
    val pendingAttachments: List<PaymentAttachment> = emptyList(),
    val confirmSubmit: Boolean = false,
    val confirmDiscard: Boolean = false,
    val discardNavigation: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
) {
    val hasUnsubmittedAttachments: Boolean get() = pendingAttachments.isNotEmpty()
}

@HiltViewModel
class ManufacturerPaymentBillDetailViewModel @Inject constructor(
    private val repository: ManufacturerPaymentBillRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ManufacturerPaymentBillDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            repository.detail(id)
        }.onSuccess { detail ->
            _state.update {
                it.copy(
                    loading = false,
                    detail = detail,
                    payments = detail.bill.payments,
                )
            }
            runCatching { repository.payments(id) }.onSuccess { payments ->
                _state.update { it.copy(payments = payments.ifEmpty { detail.bill.payments }) }
            }.onFailure { error ->
                _state.update { it.copy(error = error.paymentBillMessage(R.string.manufacturer_bill_detail_load_failed)) }
            }
            if (detail.bill.approvalProvider == "local" && detail.bill.status in setOf("submitted", "partial_paid")) {
                runCatching {
                    repository.paymentOptions("payment_method") to repository.paymentOptions("payment_source")
                }.onSuccess { (methods, sources) ->
                    _state.update { it.copy(paymentMethods = methods, paymentSources = sources) }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.paymentBillMessage(R.string.manufacturer_bill_detail_load_failed)) }
                }
            }
        }.onFailure { error ->
            _state.update {
                it.copy(loading = false, error = error.paymentBillMessage(R.string.manufacturer_bill_detail_load_failed))
            }
        }
    }

    fun openApprove() {
        val detail = _state.value.detail ?: return
        val bill = detail.bill
        if (bill.status != "submitted" || bill.approvalProvider != "local" || detail.paymentExecution?.canExecute == false) return
        val infos = detail.counterparty.paymentInfos.filterNot { it.type == "chengyishe" }
        _state.update {
            it.copy(
                action = ManufacturerBillAction.Approve,
                paymentInfoId = bill.manufacturerPaymentInfoId ?: infos.firstOrNull { info -> info.isDefault }?.id ?: infos.firstOrNull()?.id,
                partialPayment = false,
                amount = bill.totalAmount.moneyInput(),
                paymentMethod = preferredPaymentOption(bill.paymentMethod, it.paymentMethods),
                paymentSource = preferredPaymentOption(bill.paymentSource, it.paymentSources),
                chengyisheOrderNo = "",
                remark = "",
                pendingAttachments = emptyList(),
                error = null,
            )
        }
    }

    fun openReject() {
        val bill = _state.value.detail?.bill ?: return
        if (bill.status != "submitted" || bill.approvalProvider != "local") return
        _state.update {
            it.copy(
                action = ManufacturerBillAction.Reject,
                remark = "",
                pendingAttachments = emptyList(),
                error = null,
            )
        }
    }

    fun openPayment() {
        val detail = _state.value.detail ?: return
        val bill = detail.bill
        if (bill.status != "partial_paid" || bill.paymentStrategy != "deposit_payment" || detail.paymentExecution?.canExecute == false) return
        val infos = detail.counterparty.paymentInfos.filterNot { it.type == "chengyishe" }
        _state.update {
            it.copy(
                action = ManufacturerBillAction.Pay,
                paymentInfoId = bill.manufacturerPaymentInfoId ?: infos.firstOrNull { info -> info.isDefault }?.id ?: infos.firstOrNull()?.id,
                amount = bill.remainingAmount.moneyInput(),
                paymentMethod = preferredPaymentOption(bill.paymentMethod, it.paymentMethods),
                paymentSource = preferredPaymentOption(bill.paymentSource, it.paymentSources),
                payer = "",
                paymentDate = todayDate(),
                remark = "",
                pendingAttachments = emptyList(),
                error = null,
            )
        }
    }

    fun closeAction() {
        if (_state.value.hasUnsubmittedAttachments) {
            _state.update { it.copy(confirmDiscard = true, discardNavigation = false) }
        } else {
            clearAction()
        }
    }

    fun requestDiscardNavigation() = _state.update { it.copy(confirmDiscard = true, discardNavigation = true) }

    fun cancelDiscard() = _state.update { it.copy(confirmDiscard = false, discardNavigation = false) }

    fun discardAttachmentsAndCloseAction() = clearAction()

    fun setPaymentInfo(id: Long) = _state.update { it.copy(paymentInfoId = id) }

    fun setPartialPayment(partial: Boolean) {
        val remaining = _state.value.detail?.bill?.remainingAmount ?: 0.0
        _state.update { it.copy(partialPayment = partial, amount = if (partial) "" else remaining.moneyInput()) }
    }

    fun setAmount(value: String) = _state.update { it.copy(amount = value.filter { char -> char.isDigit() || char == '.' }) }

    fun setPaymentMethod(value: String) = _state.update { state ->
        val creditInfo = state.detail?.counterparty?.paymentInfos?.firstOrNull { it.type == "chengyishe" }
        state.copy(
            paymentMethod = value,
            paymentInfoId = if (value == "诚意赊") creditInfo?.id ?: state.paymentInfoId else state.paymentInfoId,
            partialPayment = if (value == "诚意赊") false else state.partialPayment,
            amount = if (value == "诚意赊") state.detail?.bill?.remainingAmount?.moneyInput().orEmpty() else state.amount,
            chengyisheOrderNo = if (value == "诚意赊") state.chengyisheOrderNo else "",
        )
    }

    fun setPaymentSource(value: String) = _state.update { it.copy(paymentSource = value) }

    fun setChengyisheOrderNo(value: String) = _state.update { it.copy(chengyisheOrderNo = value.take(120)) }

    fun setPayer(value: String) = _state.update { it.copy(payer = value) }

    fun setPaymentDate(value: String) = _state.update { it.copy(paymentDate = value) }

    fun setRemark(value: String) = _state.update { it.copy(remark = value) }

    fun upload(uri: Uri) {
        if (_state.value.pendingAttachments.size >= 5 || _state.value.uploading) return
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.uploadAttachment(uri) }
                .onSuccess { attachment ->
                    _state.update {
                        it.copy(
                            uploading = false,
                            pendingAttachments = (it.pendingAttachments + attachment).distinctBy { item -> item.objectKey.ifBlank { item.url } },
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(uploading = false, error = error.paymentBillMessage(R.string.manufacturer_bill_attachment_failed))
                    }
                }
        }
    }

    fun removeAttachment(attachment: PaymentAttachment) = _state.update {
        it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.objectKey == attachment.objectKey })
    }

    fun requestSubmit() {
        val state = _state.value
        val detail = state.detail ?: return
        val error = validateManufacturerBillAction(state, detail)
        if (error != null) {
            _state.update { it.copy(error = UiText.Dynamic(error)) }
        } else {
            _state.update { it.copy(confirmSubmit = true, error = null) }
        }
    }

    fun cancelSubmit() = _state.update { it.copy(confirmSubmit = false) }

    fun confirmSubmit(id: Long) {
        val state = _state.value
        val action = state.action ?: return
        val detail = state.detail ?: return
        if (validateManufacturerBillAction(state, detail) != null || state.submitting) return
        _state.update { it.copy(submitting = true, confirmSubmit = false, error = null) }
        viewModelScope.launch {
            val amount = state.amount.toDoubleOrNull()
            val result = runCatching {
                when (action) {
                    ManufacturerBillAction.Reject -> repository.review(
                        id,
                        ReviewManufacturerBillRequest(approve = false, remark = state.remark),
                    )
                    ManufacturerBillAction.Approve -> repository.review(
                        id,
                        ReviewManufacturerBillRequest(
                            approve = true,
                            remark = state.remark,
                            amount = amount,
                            paymentStrategy = if (state.partialPayment) "deposit_payment" else "standard_payment",
                            manufacturerPaymentInfoId = state.paymentInfoId,
                            paymentMethod = state.paymentMethod,
                            paymentSource = state.paymentSource,
                            chengyisheOrderNo = state.chengyisheOrderNo,
                            attachments = state.pendingAttachments,
                        ),
                    )
                    ManufacturerBillAction.Pay -> repository.createPayment(
                        id,
                        CreateManufacturerBillPaymentRequest(
                            amount = requireNotNull(amount),
                            currency = detail.bill.currency,
                            paymentMethod = state.paymentMethod,
                            paymentSource = state.paymentSource,
                            manufacturerPaymentInfoId = requireNotNull(state.paymentInfoId),
                            attachments = state.pendingAttachments,
                            payer = state.payer,
                            paymentDate = state.paymentDate,
                            remark = state.remark,
                        ),
                    )
                }
            }
            result.onSuccess {
                _state.update {
                    ManufacturerPaymentBillDetailUiState(
                        detail = it.detail,
                        message = UiText.Resource(
                            when (action) {
                                ManufacturerBillAction.Reject -> R.string.manufacturer_bill_rejected
                                ManufacturerBillAction.Approve -> R.string.manufacturer_bill_approved
                                ManufacturerBillAction.Pay -> R.string.manufacturer_bill_payment_recorded
                            },
                        ),
                    )
                }
                load(id)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        submitting = false,
                        error = error.paymentBillMessage(R.string.manufacturer_bill_action_failed),
                    )
                }
                if (error.message.orEmpty().let { message -> message.contains("状态已变化") || message.contains("剩余应付金额") }) {
                    load(id)
                }
            }
        }
    }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }

    private fun clearAction() = _state.update {
        it.copy(
            action = null,
            pendingAttachments = emptyList(),
            confirmDiscard = false,
            discardNavigation = false,
            confirmSubmit = false,
            remark = "",
        )
    }
}

internal fun preferredPaymentOption(current: String, options: List<PaymentConfigOption>): String =
    current.takeIf { value -> options.any { option -> option.name == value } }
        ?: options.firstOrNull { it.isDefault }?.name
        ?: options.firstOrNull()?.name.orEmpty()

internal fun validateManufacturerBillAction(
    state: ManufacturerPaymentBillDetailUiState,
    detail: ManufacturerBillDetail,
): String? {
    val action = state.action ?: return "请选择操作"
    val bill = detail.bill
    // The confirmation dialog may outlive a refresh, so re-check server-owned state before submission.
    if (bill.approvalProvider != "local") return "企微审批账单不能在 App 内处理"
    if (action == ManufacturerBillAction.Reject) {
        if (bill.status != "submitted") return "仅待付款账单可驳回"
        return if (state.remark.isBlank()) "请填写驳回原因" else null
    }
    if (action == ManufacturerBillAction.Approve && bill.status != "submitted") return "仅待付款账单可付款"
    if (action == ManufacturerBillAction.Pay && (bill.status != "partial_paid" || bill.paymentStrategy != "deposit_payment")) {
        return "当前账单不支持继续分次付款"
    }
    if (detail.paymentExecution?.canExecute == false) return detail.paymentExecution.blockedReason.ifBlank { "当前账单不能执行付款" }
    if (state.paymentInfoId == null) return "请选择厂家付款信息"
    if (state.paymentMethod.isBlank()) return "请选择付款方式"
    if (state.paymentSource.isBlank()) return "请选择付款来源"
    if (state.paymentMethod == "诚意赊") {
        val selectedInfo = detail.counterparty.paymentInfos.firstOrNull { it.id == state.paymentInfoId }
        if (selectedInfo?.type != "chengyishe") return "厂家未配置诚意赊付款信息"
        if (state.chengyisheOrderNo.isBlank()) return "请填写诚意赊订单号"
    }
    val amount = state.amount.toDoubleOrNull() ?: return "请输入有效付款金额"
    if (!amount.isFinite() || amount <= 0) return "实付金额必须大于 0"
    if (amount > bill.remainingAmount + 0.001) return "付款金额不能超过剩余应付金额"
    if (action == ManufacturerBillAction.Approve && state.partialPayment && amount >= bill.remainingAmount - 0.001) {
        return "预付定金必须小于剩余应付金额"
    }
    if (action == ManufacturerBillAction.Approve && !state.partialPayment && kotlin.math.abs(amount - bill.remainingAmount) > 0.001) {
        return "标准付款必须一次付清"
    }
    if (action == ManufacturerBillAction.Pay && !state.paymentDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
        return "付款日期格式应为 YYYY-MM-DD"
    }
    return null
}

private fun Throwable.paymentBillMessage(fallback: Int): UiText =
    (this as? FscmApiException)?.serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
        ?: message?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
        ?: UiText.Resource(fallback)

private fun Double.moneyInput(): String = "%.2f".format(Locale.US, this)

private fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
