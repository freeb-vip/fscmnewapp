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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsage
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OperationsWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchModule
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchPresentation
import com.google.samples.apps.nowinandroid.fscm.data.commonMobileApps
import com.google.samples.apps.nowinandroid.fscm.data.orderedWarehouseHomeApps
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import java.math.BigDecimal
import java.text.NumberFormat

private val workbenchBlue = Color(0xFF1677FF)
private val workbenchBackground = Color(0xFFF5F8FF)

@Composable
internal fun WarehouseStyleScreen(
    session: SessionState,
    presentation: WorkbenchPresentation,
    state: LoadState<WarehouseWorkbench>,
    pendingSyncCount: Int,
    onProfile: () -> Unit,
    onDetail: () -> Unit,
    onRefresh: () -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
) {
    val catalog = visibleMobileApps(MobileWorkbenchOverview(apps = presentation.apps), session.permissions, session.sessionMode)
        .filterNot { it.key in setOf("todos", "notes", "tickets") }
    val pinned = orderedWarehouseHomeApps(catalog, presentation.homeAppKeys)
    val other = catalog.filterNot { app -> pinned.any { it.key == app.key } }
    val columns = if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f) 1 else 2
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier.background(workbenchBackground),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.currentNamespace?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(session.user?.username.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onProfile) {
                        Icon(Icons.Rounded.Person, contentDescription = stringResource(R.string.profile))
                    }
                }
            }
            item {
                StyleCard(onClick = onDetail) {
                    Text(stringResource(R.string.style_my_work), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    val metric = state.data?.metric
                    if (metric == null) {
                        Text(
                            if (state.loading) {
                                stringResource(R.string.connecting_fscm)
                            } else if (state.data?.status == "missing") {
                                stringResource(R.string.style_no_work_data)
                            } else {
                                stringResource(R.string.style_data_unavailable)
                            },
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StyleMetric(stringResource(R.string.style_work_count), if (metric.available == true) metric.count.toString() else "—", Modifier.weight(1f))
                            StyleMetric(stringResource(R.string.style_efficiency), if (metric.efficiencyAvailable == true) "%.1f".format(metric.efficiency) else "—", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${stringResource(R.string.style_person_hours)} · ${"%.1f".format(metric.personHours)} h", style = MaterialTheme.typography.bodySmall)
                    }
                    state.data?.let { Text("${it.date} · ${it.timezone}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (pendingSyncCount > 0) {
                item { Text(stringResource(R.string.style_pending_sync, pendingSyncCount), color = MaterialTheme.colorScheme.error) }
            }
            item { Text(stringResource(R.string.style_common_work), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(pinned.chunked(columns)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { app -> WarehouseAppTile(app, Modifier.weight(1f), onOpenApp) }
                    if (columns == 2 && pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (other.isNotEmpty()) {
                item { Text(stringResource(R.string.style_other_apps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(other.chunked(columns)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { app -> WarehouseAppTile(app, Modifier.weight(1f), onOpenApp) }
                        if (columns == 2 && pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WarehouseAppTile(app: MobileAppEntry, modifier: Modifier, onOpen: (MobileAppEntry) -> Unit) {
    Card(
        onClick = { onOpen(app) },
        modifier = modifier.heightIn(min = 108.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FscmAppIcon(app, size = 48)
            Text(localizedAppTitle(app), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun WarehouseDetailScreen(state: LoadState<WarehouseWorkbench>) {
    val data = state.data
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            StyleCard {
                Text(stringResource(R.string.style_personal_detail), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${data?.date.orEmpty()} · ${data?.timezone.orEmpty()}")
                data?.metric?.let { metric ->
                    StyleMetric(stringResource(R.string.style_work_count), if (metric.available == true) metric.count.toString() else "—")
                    StyleMetric(stringResource(R.string.style_efficiency), if (metric.efficiencyAvailable == true) "%.1f".format(metric.efficiency) else "—")
                } ?: Text(stringResource(R.string.style_no_work_data))
            }
        }
        items(data?.operations?.toList().orEmpty()) { (code, metric) ->
            StyleCard { StyleMetric(code, if (metric.available) metric.count.toString() else "—") }
        }
    }
}

@Composable
internal fun OperationsStyleScreen(
    session: SessionState,
    presentation: WorkbenchPresentation,
    usage: MobileAppUsage,
    state: LoadState<OperationsWorkbench>,
    realtimeState: RealtimeUiState,
    onRealtime: () -> Unit,
    onRealtimeSelect: (String) -> Unit,
    onSearch: () -> Unit,
    onDaily: () -> Unit,
    onTodos: () -> Unit,
    onTodo: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
) {
    val data = state.data
    val apps = commonMobileApps(visibleMobileApps(MobileWorkbenchOverview(apps = presentation.apps), session.permissions, session.sessionMode), usage).take(4)
    val columns = if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f) 1 else 2
    PullToRefreshBox(isRefreshing = state.loading || realtimeState.loading, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier.background(workbenchBackground),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(session.currentNamespace?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.common_search), tint = workbenchBlue) }
                }
            }
            if (realtimeState.allowed) {
                item { RealtimeHomeCard(realtimeState, onRealtime, onRealtimeSelect, onRefresh) }
            }
            if (data == null || data.daily.status != "no_permission") {
                item {
                    StyleCard(onClick = onDaily) {
                        Text(stringResource(R.string.style_yesterday_daily), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val daily = data?.daily
                        ModuleNotice(daily)
                        daily?.data?.let { report ->
                            Text(report.date, style = MaterialTheme.typography.bodySmall)
                            StyleMetric(stringResource(R.string.style_operating_profit), money(report.summary.profit), prominent = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StyleMetric(stringResource(R.string.style_sales_orders), report.summary.totalOrders?.toString() ?: "—", Modifier.weight(1f))
                                StyleMetric(stringResource(R.string.style_profit_margin), percent(report.summary.profitMargin), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (data == null || data.todos.status != "no_permission") {
                item {
                    StyleCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.style_todos), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onTodos, enabled = data?.todos?.status == "ready") { Text(stringResource(R.string.style_view_all)) }
                        }
                        val todos = data?.todos?.data.orEmpty().take(3)
                        if (todos.isEmpty()) ModuleNotice(data?.todos, emptyMessage = stringResource(R.string.style_no_todos))
                        todos.forEach { todo ->
                            Text(todo.title, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onTodo(todo.id) }.padding(vertical = 10.dp), maxLines = 1)
                        }
                    }
                }
            }
            if (apps.isNotEmpty()) {
                item { Text(stringResource(R.string.style_common_work), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(apps.chunked(columns)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { app -> WarehouseAppTile(app, Modifier.weight(1f), onOpenApp) }
                        if (columns == 2 && pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DailyDetailScreen(state: LoadState<OperationsWorkbench>) {
    val daily = state.data?.daily?.data
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            StyleCard {
                Text(stringResource(R.string.style_yesterday_daily), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (daily == null) {
                    Text(stringResource(R.string.style_data_unavailable))
                } else {
                    Text(daily.date)
                    StyleMetric(stringResource(R.string.style_operating_profit), money(daily.summary.profit), prominent = true)
                    StyleMetric(stringResource(R.string.style_sales_amount), money(daily.summary.saleAmount))
                    StyleMetric(stringResource(R.string.style_sales_orders), daily.summary.totalOrders?.toString() ?: "—")
                    StyleMetric(stringResource(R.string.style_profit_margin), percent(daily.summary.profitMargin))
                }
            }
        }
    }
}

@Composable
private fun <T> ModuleNotice(module: WorkbenchModule<T>?, emptyMessage: String? = null) {
    val message = when (module?.status) {
        "ready" -> return
        "no_permission" -> stringResource(R.string.style_no_access)
        "not_configured" -> stringResource(R.string.style_no_source)
        else -> module?.reason?.takeIf(String::isNotBlank) ?: emptyMessage ?: stringResource(R.string.style_data_unavailable)
    }
    Text(message, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StyleCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun StyleMetric(label: String, value: String, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = workbenchBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun money(raw: String?): String = raw?.toBigDecimalOrNull()?.let {
    NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance("CNY") }.format(it)
} ?: "—"

private fun percent(raw: String?): String = raw?.toBigDecimalOrNull()?.let {
    "${it.multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)}%"
} ?: "—"
