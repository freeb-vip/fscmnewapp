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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmStrokeSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSuccess
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.OrderProductPreview
import com.google.samples.apps.nowinandroid.fscm.data.ShippingTask
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseException

@Composable
internal fun ExceptionOrderListCard(report: WarehouseException, onOpen: () -> Unit) {
    val completed = report.status == "resolved"
    OrderListCard(
        number = report.order.number,
        status = stringResource(if (completed) R.string.oe_resolved else R.string.oe_open),
        completed = completed,
        product = report.productPreview,
        onOpen = onOpen,
    ) {
        if (report.types.isNotEmpty()) Text(report.types.joinToString(" · ") { it.name }, color = FscmBrandDeep, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        OrderCardMetadata(stringResource(R.string.order_card_warehouse, report.warehouse.name.ifBlank { stringResource(R.string.oe_not_recorded) }))
        OrderCardMetadata(stringResource(R.string.oe_tracking_value, report.order.tracking.joinToString(", ").ifBlank { stringResource(R.string.oe_not_recorded) }))
        if (report.createdAt.isNotBlank()) OrderCardMetadata(stringResource(R.string.order_card_registered, report.createdAt))
    }
}

@Composable
internal fun ShippingOrderListCard(task: ShippingTask, onOpen: () -> Unit) {
    OrderListCard(
        number = task.orderNo,
        status = stringResource(shippingStageLabel(task.stage)),
        completed = task.stage == "shipped",
        product = task.productPreview,
        onOpen = onOpen,
    ) {
        Text(stringResource(R.string.shipping_task_counts, task.skuCount, task.quantity), color = FscmBrandDeep, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        OrderCardMetadata(stringResource(R.string.order_card_warehouse, task.warehouse.ifBlank { stringResource(R.string.oe_not_recorded) }))
        if (task.createdAt.isNotBlank()) OrderCardMetadata(stringResource(R.string.order_card_registered, task.createdAt))
    }
}

@Composable
private fun OrderListCard(number: String, status: String, completed: Boolean, product: OrderProductPreview?, onOpen: () -> Unit, footer: @Composable ColumnScope.() -> Unit) {
    val title = product?.title?.takeIf(String::isNotBlank) ?: product?.sku?.takeIf(String::isNotBlank) ?: stringResource(R.string.order_card_product_missing)
    val statusColor = if (completed) FscmSuccess else FscmBrandDeep
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised),
        border = BorderStroke(1.dp, FscmStrokeSoft),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(number, Modifier.weight(1f), color = FscmBrandMain, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, Modifier.widthIn(max = 132.dp).clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 6.dp), color = statusColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                OrderProductImage(product?.imageUrl.orEmpty(), title)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, color = FscmInkStrong, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    product?.sku?.takeIf(String::isNotBlank)?.let { Text(it, color = FscmInkSoft, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (product != null && product.itemCount > 1) Text(stringResource(R.string.order_card_products, product.itemCount), color = FscmBrandDeep, style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = FscmStrokeSoft)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), content = footer)
        }
    }
}

@Composable
private fun OrderProductImage(url: String, title: String) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).background(FscmBrandSoft), contentAlignment = Alignment.Center) {
        if (!loaded) Icon(Icons.Outlined.Inventory2, contentDescription = stringResource(R.string.order_card_image_missing), tint = FscmBrandDeep, modifier = Modifier.size(30.dp))
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable
private fun OrderCardMetadata(value: String) {
    Text(value, color = FscmInkSoft, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
