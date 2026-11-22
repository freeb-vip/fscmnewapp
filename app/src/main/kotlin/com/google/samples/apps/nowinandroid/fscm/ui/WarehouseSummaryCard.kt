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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseWorkbench
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WarehouseSummaryCard(state: LoadState<WarehouseWorkbench>) {
    val data = state.data
    val today = data?.metric
    val count = today?.count?.takeIf { today.available == true }
    val efficiency = today?.efficiency?.takeIf { today.efficiencyAvailable == true && it.isFinite() }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("warehouse_summary"),
        colors = CardDefaults.cardColors(containerColor = FscmBrandDeep, contentColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(FscmBrandMain, FscmBrandDeep))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.warehouse_summary_title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!data?.date.isNullOrBlank()) Text(data.date.takeLast(5), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WarehouseMetricValue(
                    label = stringResource(R.string.warehouse_summary_count),
                    value = count?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—",
                    modifier = Modifier.weight(1f).testTag("warehouse_count"),
                )
                WarehouseMetricValue(
                    label = stringResource(if (data?.basis == "distinct_orders") R.string.rt_order_efficiency else R.string.warehouse_summary_efficiency),
                    value = efficiency?.let { "%.2f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f).testTag("warehouse_efficiency"),
                )
            }
        }
    }
}

@Composable
private fun WarehouseMetricValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

internal fun warehouseSummaryTime(value: String, timezone: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(timezone)).format(Instant.parse(value))
}.getOrDefault("")

@Composable
internal fun waveCollectionNotice(value: com.google.samples.apps.nowinandroid.fscm.data.WaveCollectionStatus?, timezone: String): String? {
    if (value == null) return null
    val status = when {
        value.status == "pending_finalization" -> stringResource(R.string.rt_wave_pending, value.completed, value.total)
        value.status == "finalized" && value.basis == "orders_and_replenishments" -> stringResource(R.string.rt_wave_final, warehouseSummaryTime(value.finalizedAt.orEmpty(), timezone).orEmpty())
        else -> stringResource(R.string.rt_wave_reference)
    }
    val checked = value.reconciledAt?.let { stringResource(R.string.rt_wave_reconciled, warehouseSummaryTime(it, timezone).orEmpty()) }
    return listOfNotNull(status, checked, value.reason.takeIf { it.isNotBlank() }?.let { stringResource(R.string.rt_wave_waiting) }).joinToString(" · ")
}
