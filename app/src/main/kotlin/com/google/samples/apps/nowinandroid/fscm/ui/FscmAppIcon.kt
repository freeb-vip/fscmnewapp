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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.Factory
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.NoteAlt
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry

internal enum class AppGlyph {
    Tasks,
    Notes,
    Tickets,
    Documents,
    Receiving,
    InboundManagement,
    CentralReceiving,
    Profit,
    RealtimeData,
    Products,
    ProductCounting,
    OfflineShipping,
    BoxLabels,
    SupplierOrders,
    ManufacturerPaymentBills,
    PurchaseApprovals,
    LabelPrint,
    EdgeNodes,
    PdaSettings,
    Generic,
}

internal fun resolveAppGlyph(app: MobileAppEntry): AppGlyph =
    appGlyphForKey(app.key) ?: appGlyphForToken(app.icon) ?: AppGlyph.Generic

private fun appGlyphForKey(rawKey: String): AppGlyph? = when (rawKey.normalizedIconToken()) {
    "todos" -> AppGlyph.Tasks
    "notes" -> AppGlyph.Notes
    "tickets" -> AppGlyph.Tickets
    "documents" -> AppGlyph.Documents
    "receiving" -> AppGlyph.Receiving
    "inbound_management" -> AppGlyph.InboundManagement
    "central_receiving" -> AppGlyph.CentralReceiving
    "realtime_data" -> AppGlyph.RealtimeData
    "purchase_insights" -> AppGlyph.RealtimeData
    "profit" -> AppGlyph.Profit
    "product_management" -> AppGlyph.Products
    "offline_shipping" -> AppGlyph.OfflineShipping
    "product_counting" -> AppGlyph.ProductCounting
    "location_stocktakes" -> AppGlyph.ProductCounting
    "box_labels", "carton_management" -> AppGlyph.BoxLabels
    "supplier_orders" -> AppGlyph.SupplierOrders
    "manufacturer_payment_bills", "payment_bills" -> AppGlyph.ManufacturerPaymentBills
    "purchase_approvals" -> AppGlyph.PurchaseApprovals
    "label_print", "label_printing", "print_label", "custom_label_print" -> AppGlyph.LabelPrint
    "edge_nodes", "edge_node", "print_settings", "printer_settings", "label_print_settings", "edge_print_settings" -> AppGlyph.EdgeNodes
    "pda_settings", "pda_scanner_settings", "scanner_settings" -> AppGlyph.PdaSettings
    else -> null
}

private fun appGlyphForToken(rawToken: String): AppGlyph? = when (rawToken.normalizedIconToken()) {
    "task", "tasks", "todo", "checklist" -> AppGlyph.Tasks
    "note", "notes", "memo" -> AppGlyph.Notes
    "ticket", "tickets", "support" -> AppGlyph.Tickets
    "document", "documents", "file", "folder" -> AppGlyph.Documents
    "receiving", "warehouse" -> AppGlyph.Receiving
    "inbound_management", "inbound" -> AppGlyph.InboundManagement
    "central_receiving", "consolidation", "hub" -> AppGlyph.CentralReceiving
    "monitoring", "realtime_data" -> AppGlyph.RealtimeData
    "profit", "daily_report", "chart", "trending" -> AppGlyph.Profit
    "product", "products", "inventory" -> AppGlyph.Products
    "product_counting", "counting", "stocktake" -> AppGlyph.ProductCounting
    "box", "carton", "box_labels", "qr" -> AppGlyph.BoxLabels
    "supplier", "factory" -> AppGlyph.SupplierOrders
    "payment", "payments", "bill", "receipt", "receipt_long" -> AppGlyph.ManufacturerPaymentBills
    "approval", "audit", "fact_check" -> AppGlyph.PurchaseApprovals
    "label", "print" -> AppGlyph.LabelPrint
    "edge", "node", "device_hub" -> AppGlyph.EdgeNodes
    "pda", "scan", "scanner" -> AppGlyph.PdaSettings
    else -> null
}

private fun String.normalizedIconToken(): String =
    trim().lowercase().replace('-', '_').replace(' ', '_')

private fun AppGlyph.imageVector(): ImageVector = when (this) {
    AppGlyph.Tasks -> Icons.Rounded.Checklist
    AppGlyph.Notes -> Icons.Rounded.NoteAlt
    AppGlyph.Tickets -> Icons.Rounded.ConfirmationNumber
    AppGlyph.Documents -> Icons.Rounded.Folder
    AppGlyph.Receiving -> Icons.Rounded.Warehouse
    AppGlyph.InboundManagement -> Icons.Rounded.MoveToInbox
    AppGlyph.CentralReceiving -> Icons.Rounded.Hub
    AppGlyph.RealtimeData -> Icons.Rounded.QueryStats
    AppGlyph.Profit -> Icons.Rounded.Assessment
    AppGlyph.Products -> Icons.Rounded.Inventory2
    AppGlyph.OfflineShipping -> Icons.Rounded.LocalShipping
    AppGlyph.ProductCounting -> Icons.AutoMirrored.Rounded.FactCheck
    AppGlyph.BoxLabels -> Icons.Rounded.QrCode2
    AppGlyph.SupplierOrders -> Icons.Rounded.Factory
    AppGlyph.ManufacturerPaymentBills -> Icons.Rounded.ReceiptLong
    AppGlyph.PurchaseApprovals -> Icons.AutoMirrored.Rounded.FactCheck
    AppGlyph.LabelPrint -> Icons.Rounded.Print
    AppGlyph.EdgeNodes -> Icons.Rounded.DeviceHub
    AppGlyph.PdaSettings -> Icons.Rounded.QrCodeScanner
    AppGlyph.Generic -> Icons.Rounded.Apps
}

@Composable
internal fun FscmAppIcon(
    app: MobileAppEntry,
    size: Int = 44,
    modifier: Modifier = Modifier,
) {
    val color = runCatching { Color(android.graphics.Color.parseColor(app.color)) }
        .getOrNull() ?: Color(0xFF1677FF)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resolveAppGlyph(app).imageVector(),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}
