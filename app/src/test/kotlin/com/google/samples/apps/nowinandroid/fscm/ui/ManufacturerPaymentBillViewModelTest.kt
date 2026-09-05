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

import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillCounterparty
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillDetail
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerBillExecutionState
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentBill
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentInfo
import com.google.samples.apps.nowinandroid.fscm.data.PaymentConfigOption
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManufacturerPaymentBillViewModelTest {
    @Test
    fun chengyishePayment_requiresCreditInfoAndOrderNumber() {
        val detail = detail(status = "submitted", remainingAmount = 100.0).copy(
            counterparty = ManufacturerBillCounterparty(
                paymentInfos = listOf(ManufacturerPaymentInfo(id = 9, type = "chengyishe")),
            ),
        )
        val missingOrder = approveState(amount = "100.00").copy(
            paymentInfoId = 9,
            paymentMethod = "诚意赊",
        )

        assertEquals("请填写诚意赊订单号", validateManufacturerBillAction(missingOrder, detail))
        assertNull(validateManufacturerBillAction(missingOrder.copy(chengyisheOrderNo = "CYS-2026-001"), detail))
    }

    @Test
    fun paymentOption_usesSupportedCurrentThenBackendDefault() {
        val options = listOf(
            PaymentConfigOption(name = "现金"),
            PaymentConfigOption(name = "银行转账", isDefault = true),
        )

        assertEquals("现金", preferredPaymentOption("现金", options))
        assertEquals("银行转账", preferredPaymentOption("旧的手填值", options))
        assertEquals("", preferredPaymentOption("", emptyList()))
    }

    @Test
    fun standardPayment_requiresFullRemainingAmount() {
        val detail = detail(status = "submitted", remainingAmount = 100.0)

        assertNull(validateManufacturerBillAction(approveState(amount = "100.00"), detail))
        assertEquals(
            "标准付款必须一次付清",
            validateManufacturerBillAction(approveState(amount = "99.00"), detail),
        )
    }

    @Test
    fun depositPayment_requiresAmountBelowRemaining() {
        val detail = detail(status = "submitted", remainingAmount = 100.0)

        assertNull(validateManufacturerBillAction(approveState(amount = "30.00", partial = true), detail))
        assertEquals(
            "预付定金必须小于剩余应付金额",
            validateManufacturerBillAction(approveState(amount = "100.00", partial = true), detail),
        )
    }

    @Test
    fun laterPayment_requiresDepositPartialPaidBill() {
        val valid = detail(status = "partial_paid", remainingAmount = 70.0, strategy = "deposit_payment")
        val invalid = detail(status = "approved", remainingAmount = 70.0, strategy = "standard_payment")
        val state = approveState(amount = "70.00").copy(
            action = ManufacturerBillAction.Pay,
            paymentDate = "2026-09-04",
        )

        assertNull(validateManufacturerBillAction(state, valid))
        assertEquals("当前账单不支持继续分次付款", validateManufacturerBillAction(state, invalid))
    }

    @Test
    fun blockedExecution_disablesApprovalButAllowsReject() {
        val detail = detail(status = "submitted", remainingAmount = 100.0).copy(
            paymentExecution = ManufacturerBillExecutionState(
                canExecute = false,
                blockedReason = "采购审核未通过",
            ),
        )

        assertEquals("采购审核未通过", validateManufacturerBillAction(approveState(amount = "100.00"), detail))
        assertNull(
            validateManufacturerBillAction(
                ManufacturerPaymentBillDetailUiState(action = ManufacturerBillAction.Reject, remark = "金额有误"),
                detail,
            ),
        )
    }

    @Test
    fun nonLocalBill_isNeverActionable() {
        val detail = detail(status = "submitted", remainingAmount = 100.0, approvalProvider = "wecom")

        assertEquals(
            "企微审批账单不能在 App 内处理",
            validateManufacturerBillAction(approveState(amount = "100.00"), detail),
        )
    }

    private fun approveState(amount: String, partial: Boolean = false) = ManufacturerPaymentBillDetailUiState(
        action = ManufacturerBillAction.Approve,
        paymentInfoId = 8,
        partialPayment = partial,
        amount = amount,
        paymentMethod = "bank_transfer",
        paymentSource = "company_account",
    )

    private fun detail(
        status: String,
        remainingAmount: Double,
        strategy: String = "",
        approvalProvider: String = "local",
    ) = ManufacturerBillDetail(
        bill = ManufacturerPaymentBill(
            id = 1,
            status = status,
            remainingAmount = remainingAmount,
            paymentStrategy = strategy,
            approvalProvider = approvalProvider,
        ),
    )
}
