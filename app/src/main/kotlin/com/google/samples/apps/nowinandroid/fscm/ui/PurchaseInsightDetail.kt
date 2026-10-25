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

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmStrokeSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSuccess
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmWarning
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistory
import com.google.samples.apps.nowinandroid.fscm.data.insightHistoryPoints
import com.google.samples.apps.nowinandroid.fscm.data.insightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightNumber
import com.google.samples.apps.nowinandroid.fscm.data.insightStatuses
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurchaseInsightDetail(state: PurchaseInsightState, vm: PurchaseInsightViewModel, modifier: Modifier = Modifier) {
    val row = state.detail ?: return
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    var dataExpanded by rememberSaveable(row.key) { mutableStateOf(false) }
    var copied by remember(row.key) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    LazyColumn(modifier.fillMaxSize().testTag("insight-detail"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(row.sku, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                coroutineScope.launch {
                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("SKU", row.sku)))
                    copied = true
                }
            }) { Text(if (copied) "已复制 SKU" else "复制 SKU") }
            Text(listOf(row.productCode, row.productName).filter { it.isNotBlank() }.joinToString(" · "))
            if (row.localSku.isNotBlank()) Text("本地 SKU：${row.localSku}", style = MaterialTheme.typography.bodySmall)
            val statuses = row.dataStatuses.mapNotNull { insightStatuses[it] }
            if (statuses.isNotEmpty()) Text(statuses.joinToString(" · "), color = FscmDanger)
            Text("当前指标范围：${state.warehouseLabel}", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
            Text("快照：${state.data?.fetchedAt ?: state.data?.sourceTime ?: "未知"} · 单位：${row.unit.ifBlank { "未提供" }}", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
            if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.detailError?.let { InsightError(it) { vm.openDetail(row) } }
        }
        insightMetrics.groupBy { it.group }.forEach { (group, metrics) ->
            item {
                Text(group, fontWeight = FontWeight.Bold)
                InsightMetricGrid(row, metrics.map { it.key })
            }
        }
        item {
            HorizontalDivider()
            Text("销量曲线 · 单仓滚动销量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("每个日期对应当时近 7／15／30 天累计销量，并非当天销量。缺失日期留空，不补零。", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
            InsightChoice("曲线仓库", state.selectedWarehouses.map { it.id to it.name }, state.historyWarehouse) { id -> if (id != null) vm.historyWarehouse(id) }
            InsightChoice("查看日期范围", listOf(7, 30, 90).map { it to "最近${it}天" }, state.historyDays, vm::historyDays)
            Text("曲线指标（可多选）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 15, 30).forEach { window ->
                    FilterChip(selected = window in state.historyWindows, onClick = { vm.historyWindow(window) }, label = { Text("近${window}天累计") })
                }
            }
            if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.historyError?.let { InsightError(it, vm::retryHistory) }
            val history = state.history
            if (!state.historyLoading && history != null) {
                val sources = history.records.map { it.sourceSku }.distinct().sorted()
                if (sources.size > 1) InsightChoice("存在多个来源编码，请选择", sources.map { it to it }, state.historySource, vm::historySource)
                if (sources.isEmpty()) {
                    Text("所选仓库暂无该 SKU 的完整销量曲线数据", Modifier.padding(vertical = 12.dp))
                } else if (state.historySource.isBlank()) {
                    Text("请选择来源 SKU 后查看曲线", Modifier.padding(vertical = 12.dp))
                } else {
                    Text("来源 SKU：${state.historySource}", style = MaterialTheme.typography.bodySmall)
                    val unit = history.records.filter { it.sourceSku == state.historySource }.map { it.unit }.distinct()
                    if (unit.size > 1) {
                        Text("历史单位发生变化，无法将不同单位绘制为同一曲线", color = FscmDanger)
                    } else {
                        InsightSalesChart(history, state.historySource, state.historyWindows)
                    }
                    val latest = history.coverage.maxByOrNull { it.observedAt }
                    latest?.let { Text("历史采集：${it.observedAt} · 业务时区：${it.timezone}", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft) }
                    if (history.records.filter { it.sourceSku == state.historySource }.groupBy { it.day.take(10) }.any { it.value.size > 1 }) Text("存在同日重复身份记录，对应日期留空，请核对来源映射", color = FscmDanger)
                    TextButton(onClick = { dataExpanded = !dataExpanded }) { Text(if (dataExpanded) "收起逐日数据" else "查看逐日数据") }
                }
                history.warnings.forEach { Text(it, color = FscmDanger, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (dataExpanded && state.history != null && state.historySource.isNotBlank()) {
            items(state.history.dates, key = { it }) { date ->
                val daily = state.history.records.filter { it.day.take(10) == date && it.sourceSku == state.historySource }.singleOrNull()
                Column(Modifier.fillMaxWidth().background(FscmSurfaceRaised).padding(8.dp)) {
                    Text(date, fontWeight = FontWeight.SemiBold)
                    Text(state.historyWindows.sorted().joinToString(" · ") { "${it}天累计 ${insightNumber(daily?.value(it))}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起更多明细" else "更多明细 · 在途订单、提示与口径") }
            if (expanded) {
                row.warnings.forEach { Text(it, color = FscmDanger) }
                Text("总在途 = BigSeller 在途 + 本地在途未入仓。已关联的集运数量由服务端去重。含在途理论天数不考虑到货日期，不代表到货前不会断货。", style = MaterialTheme.typography.bodySmall)
                Text("单仓未知值显示 —；多仓合并按服务端口径累计，并保留数据不完整提示。", style = MaterialTheme.typography.bodySmall)
                if (row.productionOrders.isEmpty()) Text("暂无本地在途关联订单", color = FscmInkSoft)
            }
        }
        if (expanded) {
            items(row.productionOrders) { order ->
                Column(Modifier.fillMaxWidth().background(FscmSurfaceRaised).padding(12.dp)) {
                    Text(order.consolidationOrderCode.ifBlank { order.supplierOrderCode }, fontWeight = FontWeight.Bold)
                    if (order.consolidationOrderCode.isNotBlank() && order.supplierOrderCode.isNotBlank()) Text("厂家订单：${order.supplierOrderCode}")
                    Text("下单 ${insightNumber(order.orderedQty)} · 已发 ${insightNumber(order.shippedQty)} · 未入仓 ${insightNumber(order.productionQty)}")
                    order.warnings.forEach { Text(it, color = FscmDanger, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
internal fun InsightSalesChart(history: InsightHistory, source: String, windows: Set<Int>) {
    val dates = history.dates
    if (dates.isEmpty()) return
    val series = remember(history, source, windows) {
        windows.sorted().associateWith { insightHistoryPoints(history.records, dates, source, it) }
    }
    if (series.values.all { points -> points.all { it == null } }) {
        Text("暂无完整销量曲线数据")
        return
    }
    var selected by remember(history, source) { mutableIntStateOf(dates.lastIndex) }
    val colors = mapOf(7 to FscmSuccess, 15 to FscmWarning, 30 to FscmBrandMain)
    val maximum = series.values.flatten().filterNotNull().maxOrNull()?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
    val density = LocalDensity.current
    val left = with(density) { 48.dp.toPx() }
    val right = with(density) { 12.dp.toPx() }
    val textSize = with(density) { 10.dp.toPx() }
    val labelColor = FscmInkSoft.toArgb()
    Column(Modifier.fillMaxWidth().background(FscmSurfaceRaised).padding(8.dp)) {
        Text(dates[selected], fontWeight = FontWeight.SemiBold)
        series.forEach { (window, points) -> Text("近${window}天累计：${insightNumber(points[selected])}", color = colors.getValue(window), style = MaterialTheme.typography.bodySmall) }
        Text("点击或拖动查看日期", style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
        Canvas(
            Modifier.fillMaxWidth().height(220.dp).testTag("insight-chart")
                .semantics { contentDescription = "滚动销量曲线；缺失日期断开。可使用下方逐日数据查看准确数值。" }
                .pointerInput(dates, left) {
                    detectTapGestures { point -> selected = (((point.x - left) / (size.width - left - right).coerceAtLeast(1f)) * dates.lastIndex).roundToInt().coerceIn(0, dates.lastIndex) }
                }
                .pointerInput(dates, left) {
                    detectDragGestures(onDragStart = { point -> selected = (((point.x - left) / (size.width - left - right).coerceAtLeast(1f)) * dates.lastIndex).roundToInt().coerceIn(0, dates.lastIndex) }) { change, _ ->
                        change.consume()
                        selected = (((change.position.x - left) / (size.width - left - right).coerceAtLeast(1f)) * dates.lastIndex).roundToInt().coerceIn(0, dates.lastIndex)
                    }
                },
        ) {
            val top = 12.dp.toPx()
            val bottom = size.height - 12.dp.toPx()
            val plotWidth = (size.width - left - right).coerceAtLeast(1f)
            fun point(index: Int, value: Long): Offset = Offset(left + index.toFloat() / dates.lastIndex.coerceAtLeast(1) * plotWidth, bottom - (value.toDouble() / maximum * (bottom - top)).toFloat())
            repeat(3) { tick ->
                val y = bottom - (bottom - top) * tick / 2
                drawLine(FscmStrokeSoft, Offset(left, y), Offset(size.width - right, y), 1.dp.toPx())
                drawIntoCanvas { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = labelColor
                        this.textSize = textSize
                        textAlign = Paint.Align.RIGHT
                    }
                    val value = maximum * tick / 2
                    val label = if (value >= 10000) String.format(java.util.Locale.ROOT, "%.1f万", value / 10000) else String.format(java.util.Locale.ROOT, "%.0f", value)
                    canvas.nativeCanvas.drawText(label, left - 4.dp.toPx(), y + textSize / 3, paint)
                }
            }
            series.forEach { (window, values) ->
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    val next = value?.let { point(index, it) }
                    if (next != null) {
                        previous?.let { drawLine(colors.getValue(window), it, next, 2.dp.toPx()) }
                        drawCircle(colors.getValue(window), if (index == selected) 4.dp.toPx() else 2.dp.toPx(), next)
                    }
                    previous = next
                }
            }
            val x = left + selected.toFloat() / dates.lastIndex.coerceAtLeast(1) * plotWidth
            drawLine(FscmInkSoft.copy(alpha = 0.35f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dates.first().substring(5), style = MaterialTheme.typography.labelSmall)
            Text(dates[dates.size / 2].substring(5), style = MaterialTheme.typography.labelSmall)
            Text(dates.last().substring(5), style = MaterialTheme.typography.labelSmall)
        }
    }
}
