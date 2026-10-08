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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BoxLabelManagementTest {
    @Test
    fun pageEnvelopePreservesPaginationAndSearchMetadata() {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.parseToJsonElement("""{"code":0,"msg":"ok","data":{"data":[{"id":8}],"total":21,"page":2,"contract_version":2,"search":{"kinds":["box_label"],"match_mode":"prefix"}}}""")
        val page = decodeBoxLabelSearchPage(response.unwrapStandardPayload(200), json)
        assertEquals(21L, page.total)
        assertEquals(2, page.page)
        assertEquals(8L, page.data.single().id)
        assertEquals("prefix", page.search?.matchMode)
        assertNull(page.exactNavigationId())
    }

    @Test
    fun onlyExactUnambiguousLabelNavigates() {
        val exact = BoxLabelSearchPage(search = BoxLabelSearchInfo(listOf("box_label"), "exact", 12))
        assertEquals(12L, exact.exactNavigationId())
        assertNull(exact.copy(search = exact.search!!.copy(matchMode = "prefix")).exactNavigationId())
        assertNull(exact.copy(search = exact.search!!.copy(kinds = listOf("sku"))).exactNavigationId())
        assertNull(exact.copy(search = exact.search!!.copy(kinds = listOf("box_label", "product"))).exactNavigationId())
        assertNull(BoxLabelSearchPage(data = listOf(BoxLabel(id = 12)), total = 1).exactNavigationId())
    }

    @Test
    fun completeDetailKeepsMixedSkuAndHistory() {
        val json = Json { ignoreUnknownKeys = true }
        val detail = json.decodeFromString<BoxLabel>(
            """{"id":8,"contract_version":2,"detail_complete":true,"is_mixed":true,
                "carton_size_cm":{"length":10,"width":20,"height":30},
                "sku_items":[{"sku_code":"SKU-A","product_code":"P-A","qty_per_box":3},{"sku_code":"SKU-B","qty_per_box":7}],
                "alias_history":[{"source_label_code":"OLD","target_label_code":"NEW","source_quantity":8,"target_quantity":10}],
                "lifecycle_events":[{"action":"remap","reason":"调整","operator":"仓管"}],
                "receiving":{"status":"received","actual_received_qty":9}}
            """.trimIndent(),
        )
        assertEquals(listOf(3, 7), detail.skuItems.map { it.qtyPerBox })
        assertEquals("P-A", detail.skuItems.first().productCode)
        assertEquals("OLD", detail.aliasHistory.single().sourceLabelCode)
        assertEquals("调整", detail.lifecycleEvents.single().reason)
        assertEquals(9, detail.receiving?.actualReceivedQty)
        assertEquals(30.0, detail.cartonSizeCm?.height)
    }

    @Test
    fun printPreflightDetectsSnapshotAndQuantityChanges() {
        val label = BoxLabel(id = 1, printable = true, plannedBoxQty = 10, printSnapshot = buildJsonObject { put("box_qr_payload", "ABC") })
        assertFalse(label.samePrintContent(label.copy(plannedBoxQty = 9)))
        assertFalse(label.samePrintContent(label.copy(printable = false)))
        assertFalse(label.samePrintContent(label.copy(id = 2)))
        assertFalse(label.samePrintContent(label.copy(printSnapshot = buildJsonObject { put("box_qr_payload", "NEW") })))
    }
}
