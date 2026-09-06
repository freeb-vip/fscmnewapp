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

import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.ui.businessStatusTextRes
import com.google.samples.apps.nowinandroid.fscm.ui.supplierStatusTextRes
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FscmBusinessTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeBusinessList_acceptsRawArrayAndCommonEnvelopeKeys() {
        val raw = json.parseToJsonElement("""[{"id":1,"code":"SO-1"}]""")
        val data = json.parseToJsonElement("""{"data":[{"id":2,"code":"SO-2"}]}""")
        val items = json.parseToJsonElement("""{"items":[{"id":3,"code":"SO-3"}]}""")
        val records = json.parseToJsonElement("""{"records":[{"id":4,"code":"SO-4"}]}""")

        assertEquals("SO-1", decodeBusinessList<SupplierOrder>(raw, json).single().code)
        assertEquals("SO-2", decodeBusinessList<SupplierOrder>(data, json).single().code)
        assertEquals("SO-3", decodeBusinessList<SupplierOrder>(items, json).single().code)
        assertEquals("SO-4", decodeBusinessList<SupplierOrder>(records, json).single().code)
    }

    @Test
    fun decodeBusinessObject_acceptsDataEnvelopeAndDirectObject() {
        val wrapped = json.parseToJsonElement("""{"data":{"id":9,"code":"CR-9"}}""")
        val direct = json.parseToJsonElement("""{"id":10,"code":"CR-10"}""")

        assertEquals("CR-9", decodeBusinessObject<CentralReceipt>(wrapped, json).code)
        assertEquals("CR-10", decodeBusinessObject<CentralReceipt>(direct, json).code)
    }

    @Test
    fun decodeBusinessPage_preservesPagination() {
        val payload = json.parseToJsonElement(
            """{"data":[{"id":1,"code":"SO-1"}],"total":23,"page":2,"pageSize":10}""",
        )

        val page = decodeBusinessPage<SupplierOrder>(payload, json)

        assertEquals(23, page.total)
        assertEquals(2, page.page)
        assertEquals(10, page.pageSize)
        assertEquals(1, page.rows.size)
    }

    @Test
    fun supplierOrderTransitions_matchBackendStateMachine() {
        assertEquals(listOf("pending_quote"), allowedSupplierOrderStatuses("draft"))
        assertEquals(emptyList(), allowedSupplierOrderStatuses("pending_quote"))
        assertEquals(listOf("producing"), allowedSupplierOrderStatuses("ready_to_order"))
        assertEquals(listOf("shipped", "same_day_delivery"), allowedSupplierOrderStatuses("producing"))
        assertEquals(listOf("same_day_delivery"), allowedSupplierOrderStatuses("shipped"))
        assertEquals(listOf("shipped"), allowedSupplierOrderStatuses("same_day_delivery"))
        assertEquals(emptyList(), allowedSupplierOrderStatuses("arrived_central_warehouse"))
    }

    @Test
    fun supplierOrderQuery_mapsCategoryFiltersAndPagination() {
        val params = SupplierOrderQuery(
            category = SupplierOrderCategory.Completed,
            keyword = "  SO-100  ",
            filters = SupplierOrderFilters(
                warehouseCode = " WH-01 ",
                productCode = " P-100 ",
                manufacturerName = " Acme ",
                purchaseOrderCode = " PO-01 ",
            ),
            page = 3,
            pageSize = 20,
        ).toQueryParams()

        assertEquals("completed", params["status_group"])
        assertEquals("SO-100", params["keyword"])
        assertEquals("WH-01", params["warehouse_code"])
        assertEquals("P-100", params["product_code"])
        assertEquals("Acme", params["manufacturer_name"])
        assertEquals("PO-01", params["purchase_order_code"])
        assertEquals("3", params["page"])
        assertEquals("20", params["pageSize"])
    }

    @Test
    fun supplierOrderQuery_omitsBlankFiltersAndNormalizesPaging() {
        val filters = SupplierOrderFilters(productCode = "  ")
        val params = SupplierOrderQuery(category = SupplierOrderCategory.InProgress, filters = filters, page = 0, pageSize = 0).toQueryParams()

        assertEquals("in_progress", params["status_group"])
        assertEquals("1", params["page"])
        assertEquals("1", params["pageSize"])
        assertFalse(params.containsKey("keyword"))
        assertFalse(params.containsKey("warehouse_code"))
        assertFalse(params.containsKey("product_code"))
        assertEquals(0, filters.normalized().activeCount)
    }

    @Test
    fun supplierOrderProductSearch_usesProductFilterWithoutKeyword() {
        val params = SupplierOrderQuery(
            keyword = " P-100 ",
            searchByProductCode = true,
            filters = SupplierOrderFilters(warehouseCode = "WH-1", productCode = "OLD"),
            page = 2,
        ).toQueryParams()

        assertEquals("P-100", params["product_code"])
        assertFalse(params.containsKey("keyword"))
        assertEquals("WH-1", params["warehouse_code"])
        assertEquals("2", params["page"])
    }

    @Test
    fun supplierOrderDetailAmount_matchesListCalculationWithoutHeaderTotal() {
        val payload = json.parseToJsonElement(
            """{"data":{"id":1,"items":[
                {"id":1,"box_count":2,"total_qty":24,"qty_per_carton":12,"unit_price":60,"total_amount":0},
                {"id":2,"box_count":0,"total_qty":6,"qty_per_carton":12,"unit_price":60,"total_amount":0}
            ]}}""",
        )
        val order = decodeBusinessObject<SupplierOrder>(payload, json)

        assertEquals(150.0, order.detailSummary().totalAmount)
        assertEquals(120.0, order.items.first().calculatedAmount())
        assertEquals(45.0, SupplierOrder(totalAmount = 45.0).detailSummary().totalAmount)
    }

    @Test
    fun supplierOrderItemAmount_handlesZeroAndLegacyNegativePrice() {
        assertEquals(0.0, SupplierOrderItem(boxCount = 2, unitPrice = 0.0, totalAmount = 100.0).calculatedAmount())
        assertEquals(0.0, SupplierOrderItem(unitPrice = 60.0, totalAmount = 100.0).calculatedAmount())
        assertEquals(75.0, SupplierOrderItem(boxCount = 2, unitPrice = -1.0, totalAmount = 75.0).calculatedAmount())
    }

    @Test
    fun supplierOrderDetailSummary_usesItemProductsAndDistinctSkus() {
        val order = SupplierOrder(
            productCount = 99,
            items = listOf(
                SupplierOrderItem(id = 1, boxCount = 2, totalQty = 24, sku = Sku(id = 10, code = "SKU-1", product = Product(id = 1, code = "P-1"))),
                SupplierOrderItem(id = 2, displayBoxCount = 3, totalQty = 30, sku = Sku(id = 11, code = "SKU-2", product = Product(id = 1, code = "P-1"))),
            ),
        )

        assertEquals(SupplierOrderDetailSummary(productCount = 1, skuCount = 2, totalBoxCount = 5, totalQty = 54), order.detailSummary())
    }

    @Test
    fun approvalValidation_enforcesBackendRules() {
        assertNull(validateApprovalInput(ApprovalAdjustment.None, null, "", 100.0))
        assertEquals(ApprovalValidationError.AmountMustBePositive, validateApprovalInput(ApprovalAdjustment.Amount, 0.0, "原因", 100.0))
        assertEquals(ApprovalValidationError.AmountExceedsTotal, validateApprovalInput(ApprovalAdjustment.Amount, 101.0, "原因", 100.0))
        assertEquals(ApprovalValidationError.AmountRemarkRequired, validateApprovalInput(ApprovalAdjustment.Amount, 10.0, "", 100.0))
        assertNull(validateApprovalInput(ApprovalAdjustment.Amount, 10.0, "协商减免", 100.0))
        assertEquals(ApprovalValidationError.RateOutOfRange, validateApprovalInput(ApprovalAdjustment.Rate, 1.0, "原因", 100.0))
        assertEquals(ApprovalValidationError.RateRemarkRequired, validateApprovalInput(ApprovalAdjustment.Rate, 0.1, "", 100.0))
        assertNull(validateApprovalInput(ApprovalAdjustment.Rate, 0.1, "九折", 100.0))
    }

    @Test
    fun businessStatusLabels_coverReceivingAndPurchaseStates() {
        assertEquals(R.string.business_save_pending_sync, businessStatusTextRes("pending_sync"))
        assertEquals(R.string.business_status_wrong_order, businessStatusTextRes("wrong_order"))
        assertEquals(R.string.business_status_pending_review, supplierStatusTextRes("pending_quote"))
        assertEquals(R.string.business_status_arrived_central, supplierStatusTextRes("arrived_central_warehouse"))
    }
}
