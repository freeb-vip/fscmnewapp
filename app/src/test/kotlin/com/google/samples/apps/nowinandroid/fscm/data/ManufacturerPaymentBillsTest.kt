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

package com.google.samples.apps.nowinandroid.fscm.data

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManufacturerPaymentBillsTest {
    @Test
    fun paidBillPreservesActualPaymentAndSignedAttachments() {
        val json = Json { ignoreUnknownKeys = true }
        val bill = json.decodeFromString<ManufacturerPaymentBillWire>(
            """
            {"id":7,"status":"paid","total_amount":100,"paid_total_amount":98,"remaining_amount":0,
             "payment_method":"银行转账","payment_source":"公司账户","payer":"财务",
             "payment_date":"2026-09-05","chengyishe_order_no":"CYS-7",
             "payment_attachments":"[{\"object_key\":\"payment-attachments/1/proof.pdf\",\"name\":\"凭据.pdf\",\"mime_type\":\"application/pdf\",\"url\":\"https://example.com/proof.pdf?signature=abc\"}]",
             "payments":[{"id":1,"amount":98,"payment_method":"银行转账","payment_source":"公司账户",
                          "payment_attachments":[{"name":"凭据.pdf","url":"https://example.com/proof.pdf?signature=abc"}]}]}
            """.trimIndent(),
        ).toModel(json)

        assertEquals(98.0, bill.paidTotalAmount)
        assertEquals(0.0, bill.remainingAmount)
        assertEquals("银行转账", bill.paymentMethod)
        assertEquals("公司账户", bill.paymentSource)
        assertEquals("财务", bill.payer)
        assertEquals("CYS-7", bill.chengyisheOrderNo)
        assertEquals("application/pdf", bill.attachments.single().mimeType)
        assertEquals("https://example.com/proof.pdf?signature=abc", bill.attachments.single().url)
        assertEquals(98.0, bill.payments.single().amount)
        assertEquals(bill.attachments.single().url, bill.payments.single().attachments.single().url)
    }

    @Test
    fun legacyPaymentImagesDecodeCommaSeparatedSignedUrls() {
        val json = Json { ignoreUnknownKeys = true }
        val bill = json.decodeFromString<ManufacturerPaymentBillWire>(
            """{"payment_images":"https://example.com/a.jpg?signature=a,https://example.com/b.png?signature=b"}""",
        ).toModel(json)

        assertEquals(listOf("a.jpg", "b.png"), bill.attachments.map { it.name })
        assertEquals("https://example.com/b.png?signature=b", bill.attachments.last().url)
    }

    @Test
    fun paymentConfigOptionsDecodeLegacyDataWrapper() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<PaymentConfigOptionsResponse>(
            """{"data":[{"id":1,"code":"bank_transfer","name":"银行转账","is_active":true}]}""",
        )

        assertEquals(listOf("银行转账"), response.data.map { it.name })
    }

    @Test
    fun queryAlwaysRequestsLocalApprovalProviderAndUsesBackendPaginationNames() {
        val params = ManufacturerBillQuery(
            bucket = ManufacturerBillBucket.PendingPayment,
            keyword = " PROD-100 ",
            startDate = "2026-09-01",
            endDate = "2026-09-04",
            page = 3,
            pageSize = 30,
        ).toQueryParams()

        assertEquals("local", params["approval_provider"])
        assertEquals("pending", params["review_bucket"])
        assertEquals("PROD-100", params["keyword"])
        assertEquals("2026-09-01", params["start_date"])
        assertEquals("2026-09-04", params["end_date"])
        assertEquals("3", params["page"])
        assertEquals("30", params["page_size"])
    }

    @Test
    fun attachmentValidationMatchesBackendContract() {
        validatePaymentAttachment("voucher.JPG", 10L * 1024L * 1024L)
        validatePaymentAttachment("invoice.pdf", 1L)

        assertFailsWith<IllegalArgumentException> { validatePaymentAttachment("invoice.docx", 100L) }
        assertFailsWith<IllegalArgumentException> { validatePaymentAttachment("empty.png", 0L) }
        assertFailsWith<IllegalArgumentException> { validatePaymentAttachment("large.webp", 10L * 1024L * 1024L + 1L) }
    }

    @Test
    fun groupedBillsAndDetailDecodeBackendShape() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<ManufacturerBillPageWire>(
            """
            {
              "items": [{
                "supplier_order": {"id": 7, "code": "SO-7", "manufacturer": null},
                "product_refs": [{"id": 1, "code": "P-100"}, {"id": 2, "code": "P-200"}],
                "bill_count": 2,
                "status_summary": {"pending": 1, "reviewed": 1, "paid": 0},
                "currency_amounts": {"CNY": 120.5, "USD": 30},
                "bills": [
                  {"bill": {"id": 10, "bill_no": "PB-10", "total_amount": 120.5, "currency": "CNY", "approval_provider": "local"}},
                  {"bill": {"id": 11, "bill_no": "PB-11", "total_amount": 30, "currency": "USD", "approval_provider": "local"}}
                ]
              }],
              "total": 1,
              "page": 1,
              "page_size": 20,
              "summary": {"pending": 1, "pending_amount": 120.5, "paid": 0, "paid_amount": 0}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("P-100", "P-200"), page.items.single().productRefs.map { it.code })
        assertEquals(mapOf("CNY" to 120.5, "USD" to 30.0), page.items.single().currencyAmounts)
        assertEquals(listOf("PB-10", "PB-11"), page.items.single().bills.map { it.bill.billNo })

        val detail = json.decodeFromString<ManufacturerBillDetailWire>(
            """
            {
              "bill": {"id": 10, "bill_no": "PB-10", "status": "submitted", "approval_provider": "local"},
              "counterparty": {"manufacturer": null, "payment_infos": []},
              "source_detail": {
                "detail_type": "supplier",
                "title": "厂家订单",
                "summary": {"supplier_order_code": "SO-7"},
                "items": [{"supplier_order_item_id": 8, "product_id": 1, "product_code": "P-100", "sku_id": 9, "sku_code": "SKU-9"}]
              },
              "payment_execution": {"can_execute": false, "blocked_reason": "采购审核尚未通过", "related_supplier_orders": []}
            }
            """.trimIndent(),
        )
        assertEquals(9L, detail.sourceDetail?.items?.single()?.skuId)
        assertEquals("采购审核尚未通过", detail.paymentExecution?.blockedReason)
        assertEquals(null, detail.counterparty.manufacturer)
    }
}
