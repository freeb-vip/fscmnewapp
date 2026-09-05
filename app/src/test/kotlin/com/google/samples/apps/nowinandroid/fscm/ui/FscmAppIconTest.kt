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

import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import org.junit.Test
import kotlin.test.assertEquals

class FscmAppIconTest {
    @Test
    fun supportedKeysUseStableBusinessGlyphs() {
        val expected = mapOf(
            "todos" to AppGlyph.Tasks,
            "notes" to AppGlyph.Notes,
            "tickets" to AppGlyph.Tickets,
            "documents" to AppGlyph.Documents,
            "receiving" to AppGlyph.Receiving,
            "inbound_management" to AppGlyph.InboundManagement,
            "central_receiving" to AppGlyph.CentralReceiving,
            "profit" to AppGlyph.Profit,
            "product_management" to AppGlyph.Products,
            "box_labels" to AppGlyph.BoxLabels,
            "supplier_orders" to AppGlyph.SupplierOrders,
            "manufacturer_payment_bills" to AppGlyph.ManufacturerPaymentBills,
            "purchase_approvals" to AppGlyph.PurchaseApprovals,
            "label_print" to AppGlyph.LabelPrint,
            "edge_nodes" to AppGlyph.EdgeNodes,
            "pda_settings" to AppGlyph.PdaSettings,
        )

        expected.forEach { (key, glyph) ->
            assertEquals(glyph, resolveAppGlyph(MobileAppEntry(key = key)))
        }
    }

    @Test
    fun legacyKeysAndControlledTokensUseMappedGlyphs() {
        assertEquals(AppGlyph.EdgeNodes, resolveAppGlyph(MobileAppEntry(key = "printer_settings")))
        assertEquals(AppGlyph.PdaSettings, resolveAppGlyph(MobileAppEntry(key = "scanner_settings")))
        assertEquals(AppGlyph.LabelPrint, resolveAppGlyph(MobileAppEntry(key = "custom_label_print")))
        assertEquals(AppGlyph.Receiving, resolveAppGlyph(MobileAppEntry(key = "custom", icon = "warehouse")))
        assertEquals(AppGlyph.PurchaseApprovals, resolveAppGlyph(MobileAppEntry(key = "custom", icon = "fact-check")))
    }

    @Test
    fun unknownOrCharacterIconNeverFallsBackToTitleText() {
        assertEquals(AppGlyph.Generic, resolveAppGlyph(MobileAppEntry(key = "custom", title = "应用", icon = "应")))
        assertEquals(AppGlyph.Generic, resolveAppGlyph(MobileAppEntry(key = "custom", title = "Unknown", icon = "U")))
    }

    @Test
    fun localizedTitlesDoNotChangeGlyph() {
        val titles = listOf("Receiving", "仓库收货", "รับสินค้า", "ကုန်ပစ္စည်းလက်ခံခြင်း")

        titles.forEach { title ->
            assertEquals(AppGlyph.Receiving, resolveAppGlyph(MobileAppEntry(key = "receiving", title = title)))
        }
    }
}
