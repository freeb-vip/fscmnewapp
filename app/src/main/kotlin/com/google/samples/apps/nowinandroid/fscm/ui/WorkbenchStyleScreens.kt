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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Assignment
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileTodo
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OperationsWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchModule
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchPresentation
import com.google.samples.apps.nowinandroid.fscm.data.isTodoCompleted
import com.google.samples.apps.nowinandroid.fscm.data.orderedWarehouseHomeApps
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import java.math.BigDecimal
import java.text.NumberFormat

private val workbenchBlue = FscmBrandMain
private val workbenchBackground = FscmPageTop

@Composable
internal fun WarehouseStyleScreen(
    session: SessionState,
    presentation: WorkbenchPresentation,
    state: LoadState<WarehouseWorkbench>,
    onProfile: () -> Unit,
    onRefresh: () -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
) {
    val catalog = visibleMobileApps(MobileWorkbenchOverview(apps = presentation.apps), session.permissions, session.sessionMode)
        .filterNot { it.key in setOf("todos", "notes") }
    val pinned = orderedWarehouseHomeApps(catalog, presentation.homeAppKeys)
    val other = catalog.filterNot { app -> pinned.any { it.key == app.key } }
    val apps = (pinned + other).distinctBy { it.key }
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(workbenchBackground),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.currentNamespace?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FscmInkStrong)
                        Text(session.user?.username.orEmpty(), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                    }
                    IconButton(onClick = onProfile, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(FscmBrandSoft)) {
                        Icon(Icons.Rounded.Person, contentDescription = stringResource(R.string.profile), tint = FscmBrandMain)
                    }
                }
            }
            item {
                WarehouseSummaryCard(state, session.isOffline, onRefresh)
            }
            items(apps.chunked(2), key = { pair -> pair.joinToString("|") { it.key } }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { app -> WarehouseAppTile(app, Modifier.weight(1f), onOpenApp, large = true) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WarehouseAppTile(app: MobileAppEntry, modifier: Modifier, onOpen: (MobileAppEntry) -> Unit, large: Boolean = false) {
    Card(
        onClick = { onOpen(app) },
        modifier = modifier.heightIn(min = if (large) 120.dp else 108.dp),
        colors = CardDefaults.cardColors(containerColor = FscmBrandSoft, contentColor = FscmBrandDeep),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FscmAppIcon(app, size = if (large) 56 else 48)
            Text(
                localizedAppTitle(app),
                maxLines = if (large) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (large) 20.sp else MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun OperationsStyleScreen(
    session: SessionState,
    state: LoadState<OperationsWorkbench>,
    realtimeState: RealtimeUiState,
    onRealtime: () -> Unit,
    onRealtimeSelect: (String) -> Unit,
    onSearch: () -> Unit,
    onDaily: () -> Unit,
    onTodos: () -> Unit,
    onTodo: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val data = state.data
    val columns = if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f) 1 else 2
    PullToRefreshBox(isRefreshing = state.loading || realtimeState.loading, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(workbenchBackground).testTag("operations-home"),
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
                    StyleCard(onClick = onDaily, blue = true) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.style_yesterday_daily), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.rt_view_details), tint = FscmBrandDeep)
                        }
                        val daily = data?.daily
                        ModuleNotice(daily)
                        daily?.data?.let { report ->
                            StyleMetric(stringResource(R.string.style_operating_profit), money(report.summary.profit), prominent = true)
                            if (columns == 1) {
                                StyleMetric(stringResource(R.string.style_sales_orders), report.summary.totalOrders?.toString() ?: "—")
                                StyleMetric(stringResource(R.string.style_profit_margin), percent(report.summary.profitMargin))
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StyleMetric(stringResource(R.string.style_sales_orders), report.summary.totalOrders?.toString() ?: "—", Modifier.weight(1f))
                                    StyleMetric(stringResource(R.string.style_profit_margin), percent(report.summary.profitMargin), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (data == null || data.todos.status != "no_permission") {
                item {
                    StyleCard(blue = true) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.style_todos), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onTodos, enabled = data?.todos?.status == "ready") { Text(stringResource(R.string.style_view_all)) }
                        }
                        val todos = data?.todos?.data.orEmpty().take(3)
                        if (todos.isEmpty()) {
                            if (data?.todos?.status == "ready") {
                                Text(stringResource(R.string.style_no_todos), color = FscmInkSoft, modifier = Modifier.padding(vertical = 12.dp))
                            } else {
                                ModuleNotice(data?.todos)
                            }
                        }
                        todos.forEach { todo ->
                            OperationsTodoRow(todo, onOpen = { onTodo(todo.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationsTodoRow(todo: MobileTodo, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FscmSurfaceRaised.copy(alpha = 0.65f))
            .clickable(onClick = onOpen).heightIn(min = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Assignment, null, tint = FscmBrandMain, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(todo.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = FscmInkStrong)
            val summary = todo.description.ifBlank { todo.content }
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = FscmInkSoft)
            Text(stringResource(if (isTodoCompleted(todo.status)) R.string.status_completed else R.string.status_pending), style = MaterialTheme.typography.labelSmall, color = FscmBrandDeep)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = FscmBrandMain, modifier = Modifier.size(20.dp))
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
private fun <T> ModuleNotice(module: WorkbenchModule<T>?) {
    val message = when (module?.status) {
        "ready" -> return
        "no_permission" -> stringResource(R.string.style_no_access)
        "not_configured" -> stringResource(R.string.style_no_source)
        else -> module?.reason?.takeIf(String::isNotBlank) ?: stringResource(R.string.style_data_unavailable)
    }
    Text(message, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StyleCard(onClick: (() -> Unit)? = null, blue: Boolean = false, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (blue) FscmBrandSoft else FscmSurfaceRaised, contentColor = FscmInkStrong),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun StyleMetric(label: String, value: String, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = if (prominent) MaterialTheme.typography.headlineMedium.fontSize else MaterialTheme.typography.headlineSmall.fontSize, stepSize = 1.sp),
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
