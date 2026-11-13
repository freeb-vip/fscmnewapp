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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.fscm.data.InsightRow
import com.google.samples.apps.nowinandroid.fscm.data.insightNumber

@Composable
internal fun InsightDemandForecastSheet(row: InsightRow, dismiss: () -> Unit) {
    InsightSheet(stringResource(R.string.pi_demand_basis), dismiss) {
        Text(stringResource(R.string.pi_demand_rules), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.pi_demand_next30) + ": " + insightNumber(row.demand))
        val model = row.forecastModel
        if (model == null) {
            Text(stringResource(R.string.pi_demand_unknown))
        } else {
            val models = model.components.ifEmpty { listOf(model) }
            var selected by remember(row) { mutableStateOf(0) }
            if (models.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = selected > 0, onClick = { selected-- }) { Text(stringResource(R.string.pi_demand_previous)) }
                    Text(stringResource(R.string.pi_demand_position, selected + 1, models.size))
                    TextButton(enabled = selected < models.lastIndex, onClick = { selected++ }) { Text(stringResource(R.string.pi_demand_next)) }
                }
            }
            models.getOrNull(selected)?.let { value ->
                Text(stringResource(R.string.pi_demand_component, selected + 1), style = MaterialTheme.typography.titleSmall)
                Text(value.start + " · " + value.timezone, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (value.method == "daily_damped") R.string.pi_demand_daily else R.string.pi_demand_rolling))
                Text(
                    stringResource(
                        if (value.confidence == "high") {
                            R.string.pi_demand_high
                        } else if (value.confidence == "medium") {
                            R.string.pi_demand_medium
                        } else {
                            R.string.pi_demand_low
                        },
                    ),
                )
                value.months.forEach { month ->
                    Text(stringResource(R.string.pi_demand_month_detail, month.month, insightNumber(month.totalRatio), insightNumber(month.dailyRatio)), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            when (month.source) {
                                "sku_history" -> R.string.pi_demand_sku_history
                                "product_history" -> R.string.pi_demand_product_history
                                "product_manual" -> R.string.pi_demand_product_manual
                                "warehouse_manual" -> R.string.pi_demand_warehouse_manual
                                else -> R.string.pi_demand_default
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun InsightDemandSettingsSheet(state: PurchaseInsightState, vm: PurchaseInsightViewModel) {
    InsightSheet(stringResource(R.string.pi_demand_settings), vm::closeDemandSettings) {
        Text(stringResource(R.string.pi_demand_settings_help), style = MaterialTheme.typography.bodySmall)
        if (state.demandLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.demandError) Text(stringResource(if (state.demandConflict) R.string.pi_demand_conflict else R.string.pi_demand_error), color = FscmDanger)
        state.demandSettings?.let { settings ->
            var draft by remember(settings) { mutableStateOf(settings.factors.map { it?.toString().orEmpty() }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !state.demandLoading && settings.year > 2000, onClick = { vm.loadDemandSettings(settings.productId, settings.year - 1) }) { Text(stringResource(R.string.pi_demand_previous)) }
                Text(settings.year.toString())
                TextButton(enabled = !state.demandLoading && settings.year < 2200, onClick = { vm.loadDemandSettings(settings.productId, settings.year + 1) }) { Text(stringResource(R.string.pi_demand_next)) }
            }
            TextButton(enabled = !state.demandLoading && settings.year > 2000, onClick = { vm.loadDemandSettings(settings.productId, settings.year, copyPrevious = true) }) { Text(stringResource(R.string.pi_demand_copy)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.forEachIndexed { index, text ->
                    val value = text.toDoubleOrNull()
                    OutlinedTextField(
                        value = text,
                        onValueChange = { next -> draft = draft.toMutableList().also { it[index] = next } },
                        label = { Text(stringResource(R.string.pi_demand_month, index + 1)) },
                        placeholder = { Text((if (settings.productId != 0L) settings.warehouseFactors.getOrNull(index) else null)?.toString() ?: "1") },
                        isError = text.isNotBlank() && (value == null || !value.isFinite() || value <= 0 || value > 10),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !state.demandLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = { vm.saveDemandSettings(draft.map { it.toDoubleOrNull() }) },
                enabled = !state.demandLoading && !state.demandError && !state.demandConflict && draft.all { it.isBlank() || it.toDoubleOrNull()?.let { n -> n.isFinite() && n > 0 && n <= 10 } == true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pi_demand_save)) }
            if (state.demandError) TextButton(onClick = { vm.loadDemandSettings(settings.productId, settings.year) }) { Text(stringResource(R.string.pi_demand_retry)) }
        }
    }
}
