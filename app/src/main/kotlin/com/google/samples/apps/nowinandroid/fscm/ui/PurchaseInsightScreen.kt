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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmStrokeSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.InsightFilter
import com.google.samples.apps.nowinandroid.fscm.data.InsightRow
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.canAccessPurchaseInsights
import com.google.samples.apps.nowinandroid.fscm.data.defaultInsightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightNumber
import com.google.samples.apps.nowinandroid.fscm.data.insightSorts
import com.google.samples.apps.nowinandroid.fscm.data.insightStatuses
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareBackHandler as BackHandler

@Composable
internal fun PurchaseInsightScreen(session: SessionState, onBack: () -> Unit, viewModel: PurchaseInsightViewModel = hiltViewModel()) {
    val raw by viewModel.state.collectAsStateWithLifecycle()
    val scope = operatingDailyScope(session)
    val state = raw.takeIf { it.scope == scope && canAccessPurchaseInsights(session) }
        ?: PurchaseInsightState(scope = scope, allowed = canAccessPurchaseInsights(session), loading = canAccessPurchaseInsights(session))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(scope, session.permissions, session.sessionMode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.enter(session)
            try {
                awaitCancellation()
            } finally {
                viewModel.stop()
            }
        }
    }
    PurchaseInsightContent(state, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PurchaseInsightContent(state: PurchaseInsightState, vm: PurchaseInsightViewModel, onBack: () -> Unit) {
    var sheet by rememberSaveable(state.scope) { mutableStateOf("") }
    var forecastRow by remember(state.scope, state.filter.warehouses) { mutableStateOf<InsightRow?>(null) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.listRevision) { listState.scrollToItem(0) }
    LaunchedEffect(listState, state.detail, state.data?.items?.size, state.loading, state.loadingMore, state.error) {
        if (state.detail == null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
                val s = currentState
                if (last >= listState.layoutInfo.totalItemsCount - 4 && s.error == null && !s.loading && !s.loadingMore && s.data != null && s.data.items.size < s.data.total) vm.more()
            }
        }
    }
    BackHandler(state.detail != null) { vm.closeDetail() }
    Scaffold(
        containerColor = FscmPageTop,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.detail == null) R.string.pi_title else R.string.pi_detail_title)) },
                navigationIcon = { IconButton(onClick = { if (state.detail != null) vm.closeDetail() else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pi_back)) } },
                actions = { if (state.detail == null) IconButton(onClick = { sheet = "info" }) { Icon(Icons.Rounded.Info, stringResource(R.string.pi_data_info)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FscmPageTop),
            )
        },
    ) { padding ->
        if (!state.allowed) {
            Text("暂无采购洞察权限，或当前处于离线模式", Modifier.padding(padding).padding(16.dp))
        } else if (state.detail != null) {
            PurchaseInsightDetail(state, vm, Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = state.product, onValueChange = { vm.product(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("insight-search"),
                    singleLine = true, placeholder = { Text(stringResource(R.string.pi_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (state.product.isNotEmpty()) IconButton(onClick = { vm.product("", true) }) { Icon(Icons.Rounded.Close, stringResource(R.string.pi_clear_search)) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        vm.product(state.product, true)
                    }),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = state.filter.count(state.warehouses.size) > 0, onClick = { sheet = "filter" }, enabled = state.initialized, modifier = Modifier.testTag("insight-filter"), label = { Text(stringResource(R.string.pi_filters, state.filter.count(state.warehouses.size))) })
                    FilterChip(selected = false, onClick = { sheet = "metrics" }, modifier = Modifier.testTag("insight-metrics"), label = { Text(stringResource(R.string.pi_metrics, state.metrics.size)) })
                    FilterChip(selected = true, onClick = { vm.ranking(dimension = if (state.filter.dimension == "product") "sku" else "product") }, label = { Text(if (state.filter.dimension == "product") stringResource(R.string.pi_rank_products) else "SKU") })
                    listOf(0, 30, 50).forEach { limit ->
                        FilterChip(selected = state.filter.topLimit == limit, onClick = { vm.ranking(top = limit) }, label = { Text(if (limit == 0) stringResource(R.string.pi_rank_all) else "TOP$limit") })
                    }
                    FilterChip(selected = false, onClick = { sheet = "sort" }, label = { Text("${insightMetricLabel(state.sort, insightSorts[state.sort].orEmpty())} ${if (state.ascending) "↑" else "↓"}") })
                }
                PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("insight-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (state.data == null && state.loading) stringResource(R.string.pi_loading) else stringResource(R.string.pi_rank_items, state.data?.total ?: 0), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                                if (state.data?.totals != null) TextButton(onClick = { sheet = "totals" }, modifier = Modifier.testTag("insight-totals")) { Text(stringResource(R.string.pi_totals)) }
                            }
                        }
                        state.error?.let { item { InsightError(stringResource(R.string.pi_load_error), if (state.data == null || state.data.items.size >= state.data.total) vm::retry else vm::more) } }
                        if (!state.loading && state.data?.items.isNullOrEmpty() && state.error == null) {
                            item {
                                Text(
                                    stringResource(
                                        when {
                                            state.catalogPending || state.data?.freshness == "loading" -> R.string.pi_preparing
                                            state.filter.warehouses.isNullOrEmpty() -> R.string.pi_select_warehouses
                                            else -> R.string.pi_empty
                                        },
                                    ),
                                    Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(state.data?.items.orEmpty(), key = { it.key }) { row ->
                            var rowActions by remember(row.key) { mutableStateOf(false) }
                            Card(
                                onClick = { if (row.groupKey.isNotBlank()) vm.expandGroup(row) else vm.openDetail(row) },
                                modifier = Modifier.fillMaxWidth().testTag("insight-row-${row.key}"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised),
                                border = BorderStroke(1.dp, FscmStrokeSoft),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(row.sku, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = FscmBrandDeep, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (row.productName.isNotBlank()) Text(row.productName, style = MaterialTheme.typography.bodySmall, color = FscmInkSoft, maxLines = 2)
                                            if (row.groupKey.isNotBlank()) Text(stringResource(if (state.expandedGroup == row.groupKey) R.string.pi_rank_collapse else R.string.pi_rank_sku_count, row.skuCount), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                                            if (row.incomplete || row.forecastIncomplete) Text(stringResource(R.string.pi_partial), style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
                                        }
                                        Column {
                                            IconButton(onClick = { rowActions = true }, modifier = Modifier.testTag("insight-row-actions-${row.key}")) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.pi_row_actions)) }
                                            DropdownMenu(expanded = rowActions, onDismissRequest = { rowActions = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.pi_demand_basis)) }, onClick = {
                                                    rowActions = false
                                                    forecastRow = row
                                                })
                                                if (state.canConfigure && state.filter.warehouses?.size == 1 && row.productId > 0) {
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.pi_demand_product_manual)) }, onClick = {
                                                        rowActions = false
                                                        vm.loadDemandSettings(row.productId)
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    InsightMetricGrid(row, state.metrics)
                                    if (row.groupKey.isNotBlank() && state.expandedGroup == row.groupKey) {
                                        if (state.groupLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        state.groupData?.items.orEmpty().forEach { child ->
                                            HorizontalDivider(color = FscmStrokeSoft)
                                            Column(Modifier.fillMaxWidth().clickable { vm.openDetail(child) }.padding(vertical = 8.dp)) {
                                                Text(child.sku, color = FscmBrandDeep, fontWeight = FontWeight.SemiBold)
                                                InsightMetricGrid(child, state.metrics)
                                            }
                                        }
                                        state.groupData?.let { group ->
                                            if (group.items.size < group.total) TextButton(onClick = { vm.expandGroup(row, true) }, enabled = !state.groupLoading) { Text(stringResource(R.string.pi_rank_more)) }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            if (state.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.data?.items?.isNotEmpty() == true) {
                                Text(
                                    if (state.data.items.size >= state.data.total) stringResource(R.string.pi_all_loaded, state.data.total) else stringResource(R.string.pi_scroll_more),
                                    color = FscmInkSoft,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    forecastRow?.let { InsightDemandForecastSheet(it) { forecastRow = null } }
    if (state.demandSettings != null || state.demandLoading || state.demandError) InsightDemandSettingsSheet(state, vm)
    when (sheet) {
        "info" -> InsightSheet(stringResource(R.string.pi_data_info), { sheet = "" }) {
            Text(state.warehouseLabel.ifBlank { stringResource(R.string.pi_select_warehouses) }, color = FscmInkSoft)
            state.data?.let { Text(stringResource(R.string.pi_updated_at, it.fetchedAt ?: it.sourceTime.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall) }
            if (state.catalogPending || state.data?.freshness == "loading") Text(stringResource(R.string.pi_preparing))
            val notices = state.catalogWarnings.size + state.data?.warnings.orEmpty().size + if (state.preferenceError != null) 1 else 0
            if (notices > 0) Text(stringResource(R.string.pi_data_notices, notices))
            if (state.canConfigure && state.filter.warehouses?.size == 1) {
                TextButton(onClick = {
                    sheet = ""
                    vm.loadDemandSettings()
                }) { Text(stringResource(R.string.pi_demand_settings)) }
            }
        }
        "metrics" -> InsightMetricSheet(state.metrics, { sheet = "" }) {
            vm.metrics(it)
            sheet = ""
        }
        "filter" -> InsightFilterSheet(state, { sheet = "" }) {
            vm.applyFilter(it)
            sheet = ""
        }
        "sort" -> InsightSheet(stringResource(R.string.pi_sort), { sheet = "" }) {
            InsightChoice(stringResource(R.string.pi_direction), listOf(false to stringResource(R.string.pi_desc), true to stringResource(R.string.pi_asc)), state.ascending) {
                vm.sort(state.sort, it)
                sheet = ""
            }
            insightSorts.forEach { (key, label) ->
                TextButton(onClick = {
                    vm.sort(key, state.ascending)
                    sheet = ""
                }, modifier = Modifier.fillMaxWidth()) { Text(insightMetricLabel(key, label) + if (key == state.sort) " ✓" else "") }
            }
        }
        "totals" -> InsightSheet(stringResource(R.string.pi_totals), { sheet = "" }) {
            state.data?.totals?.let { totals ->
                insightMetrics.filter { it.additive }.groupBy { it.group }.forEach { (group, metrics) ->
                    Text(group, fontWeight = FontWeight.Bold)
                    InsightMetricGrid(totals, metrics.map { it.key }, true)
                }
            }
            Text(stringResource(R.string.pi_totals_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun InsightMetricGrid(row: InsightRow, keys: List<String>, total: Boolean = false) {
    val columns = if (LocalDensity.current.fontScale > 1.15f) 2 else 3
    val metrics = keys.mapNotNull { key -> insightMetrics.find { it.key == key } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metrics.chunked(columns).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                line.forEach { metric ->
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(insightMetricLabel(metric.key, metric.label), style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
                        Text(
                            if (metric.key == "coverage" && row.coverageBeyond || metric.key == "transitCoverage" && row.transitCoverageBeyond) {
                                stringResource(R.string.pi_demand_beyond)
                            } else if (total && metric.key == "demand" && row.forecastIncomplete) {
                                stringResource(R.string.pi_demand_partial, insightNumber(row.demand))
                            } else if (total && !metric.additive) {
                                stringResource(R.string.pi_not_additive)
                            } else {
                                insightNumber(row.metric(metric.key))
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (metric.key.startsWith("sales") || metric.key in listOf("available", "demand")) FscmBrandDeep else FscmInkStrong,
                        )
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun InsightError(error: String, retry: () -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Text(error, color = FscmDanger)
        TextButton(onClick = retry) { Text(stringResource(R.string.pi_retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightSheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = FscmSurfaceRaised) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = dismiss) { Text(stringResource(R.string.pi_close)) }
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun InsightMetricSheet(selected: List<String>, dismiss: () -> Unit, apply: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf(selected) }
    InsightSheet("展示项 · 已选 ${draft.size}/6", dismiss) {
        Text("按选择顺序排列，至少 1 项，最多 6 项。", style = MaterialTheme.typography.bodySmall)
        insightMetrics.groupBy { it.group }.forEach { (group, metrics) ->
            Text(group, fontWeight = FontWeight.Bold)
            metrics.forEach { metric ->
                val checked = metric.key in draft
                InsightCheck(insightMetricLabel(metric.key, metric.label) + if (checked) " · ${draft.indexOf(metric.key) + 1}" else "", checked, checked || draft.size < 6) {
                    draft = if (checked) draft - metric.key else draft + metric.key
                }
            }
        }
        TextButton(onClick = { draft = defaultInsightMetrics }) { Text("恢复默认") }
        Button(onClick = { apply(draft) }, enabled = draft.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("应用") }
    }
}

@Composable
internal fun InsightCheck(label: String, checked: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { changed(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), color = if (enabled) FscmInkStrong else FscmInkSoft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> InsightChoice(label: String, choices: List<Pair<T, String>>, selected: T, change: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (value, text) -> FilterChip(selected = value == selected, onClick = { change(value) }, label = { Text(text) }) }
    }
}

@Composable
private fun InsightFilterSheet(state: PurchaseInsightState, dismiss: () -> Unit, apply: (InsightFilter) -> Unit) {
    var draft by remember { mutableStateOf(state.filter) }
    InsightSheet("筛选", dismiss) {
        Text("目标仓库", fontWeight = FontWeight.Bold)
        Row {
            TextButton(onClick = { draft = draft.copy(warehouses = state.warehouses.map { it.id }) }) { Text("全选") }
            TextButton(onClick = { draft = draft.copy(warehouses = emptyList()) }) { Text("清空") }
        }
        state.warehouses.forEach { warehouse ->
            InsightCheck("${warehouse.name} · ${warehouse.connection}", warehouse.id in draft.warehouses.orEmpty()) { checked ->
                draft = draft.copy(warehouses = if (checked) draft.warehouses.orEmpty() + warehouse.id else draft.warehouses.orEmpty() - warehouse.id)
            }
        }
        OutlinedTextField(draft.sku, { draft = draft.copy(sku = it) }, label = { Text("SKU 编码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        InsightChoice("SKU 匹配", listOf(false to "包含", true to "精确"), draft.exact) { draft = draft.copy(exact = it) }
        OutlinedTextField(draft.batch, { draft = draft.copy(batch = it) }, label = { Text("批量 SKU，逗号或换行分隔") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        InsightChoice("数据状态", insightStatuses.toList(), draft.status) { draft = draft.copy(status = it) }
        InsightCheck("有销量", draft.hasSales) { draft = draft.copy(hasSales = it) }
        if (draft.hasSales) InsightChoice("销量过滤周期", listOf(3, 7, 30, 60).map { it to "${it}天" }, draft.salesDays) { draft = draft.copy(salesDays = it) }
        InsightCheck("有本地在途数量", draft.producing) { draft = draft.copy(producing = it) }
        InsightCheck("仅看异常", draft.anomalies) { draft = draft.copy(anomalies = it) }
        TextButton(onClick = { draft = InsightFilter(warehouses = state.warehouses.map { it.id }) }) { Text("重置筛选") }
        Button(onClick = { apply(draft) }, modifier = Modifier.fillMaxWidth()) { Text("应用") }
    }
}

@Composable
private fun insightMetricLabel(key: String, fallback: String): String = when (key) {
    "demand", "forecast30" -> stringResource(R.string.pi_demand_next30)
    "salestoday" -> stringResource(R.string.pi_rank_today)
    "salesyesterday" -> stringResource(R.string.pi_rank_yesterday)
    "sales3", "sales7", "sales30", "sales60" -> stringResource(R.string.pi_sales_days, key.removePrefix("sales").toInt())
    "onHand" -> stringResource(R.string.pi_on_hand)
    "allocated" -> stringResource(R.string.pi_allocated)
    "available" -> stringResource(R.string.pi_available)
    "externalTransit" -> stringResource(R.string.pi_external_transit)
    "production" -> stringResource(R.string.pi_production)
    "transit" -> stringResource(R.string.pi_transit)
    "forecast" -> stringResource(R.string.pi_daily_forecast)
    "reference" -> stringResource(R.string.pi_daily_reference)
    "coverage" -> stringResource(R.string.pi_coverage)
    "transitCoverage" -> stringResource(R.string.pi_transit_coverage)
    else -> fallback
}
