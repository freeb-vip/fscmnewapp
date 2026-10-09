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

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSuccess
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.DailyDateMode
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyCost
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyDay
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyFilter
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyShop
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySummary
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyScope
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyToday
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmCard
import kotlinx.coroutines.delay
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun OperatingDailyScreen(session: SessionState, onBack: () -> Unit, viewModel: OperatingDailyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = operatingDailyScope(session)
    val allowed = "mobile-app:profit" in session.permissions && "operating-profit-daily" in session.permissions
    LaunchedEffect(lifecycle, scope, session.permissions) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.enter(session)
            var date = operatingDailyToday()
            while (true) {
                delay(60_000)
                val next = operatingDailyToday()
                if (date != next) {
                    date = next
                    viewModel.enter(session)
                }
            }
        }
    }
    val visible = if (state.scope == scope && state.allowed == allowed) state else OperatingDailyUiState(scope = scope, allowed = allowed)
    OperatingDailyContent(visible, viewModel, onBack, onRetry = { viewModel.enter(session) })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun OperatingDailyContent(state: OperatingDailyUiState, viewModel: OperatingDailyViewModel, onBack: () -> Unit, onRetry: () -> Unit) {
    var filtersOpen by rememberSaveable(state.scope) { mutableStateOf(false) }
    val overviewScroll = rememberLazyListState()
    val rankingScroll = rememberLazyListState()
    val mainScroll = if (state.ranking) rankingScroll else overviewScroll
    val shop = state.selectedShop
    BackHandler(enabled = shop != null && !filtersOpen) { viewModel.closeShop() }
    LaunchedEffect(state.filter, state.range, state.rankMetric, state.scope) {
        overviewScroll.scrollToItem(0)
        rankingScroll.scrollToItem(0)
    }
    Scaffold(
        containerColor = FscmPageTop,
        topBar = {
            TopAppBar(
                title = { Text(shop?.name ?: stringResource(R.string.od_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { if (shop != null) viewModel.closeShop() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.od_back))
                    }
                },
                actions = {
                    if (shop == null && state.allowed) {
                        IconButton(onClick = {
                            viewModel.clearFilterError()
                            filtersOpen = true
                        }, modifier = Modifier.size(48.dp).testTag("daily_filter")) {
                            Icon(Icons.Rounded.FilterList, stringResource(R.string.od_filters), tint = FscmBrandMain)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FscmSurfaceRaised),
            )
        },
    ) { padding ->
        if (shop != null) {
            DailyShopDetail(state, viewModel, Modifier.padding(padding))
        } else {
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRetry, modifier = Modifier.padding(padding).fillMaxSize()) {
                LazyColumn(
                    state = mainScroll,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("daily_list"),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(dateModeLabel(state.filter.dateMode)), fontWeight = FontWeight.Bold)
                            Text(state.range?.let { "${it.first} — ${it.second}" } ?: "—", color = FscmInkSoft)
                            Text(
                                (if (state.filter.allPlatforms) stringResource(R.string.od_all_platforms) else state.filter.platforms.sorted().joinToString(" / ") { it.uppercase() }) + " · " +
                                    (if (state.filter.allShops) stringResource(R.string.od_all_shops) else stringResource(R.string.od_selected_shops, state.filter.visibleShops(state.directory).count { it.id in state.filter.shopIds })),
                                color = FscmInkSoft,
                            )
                        }
                    }
                    state.error?.let { error -> item { DailyError(error, if (state.selectionInvalid) null else onRetry) } }
                    if (state.loading && state.data == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.data?.let { data ->
                        item { DailySummaryCards(data.summary) }
                        item {
                            Column {
                                val partial = data.summary.days < data.summary.expectedDays || data.summary.orderDays < data.summary.expectedDays || data.summary.profitDays < data.summary.expectedDays
                                val delayed = state.directory.filter { state.filter.includesPlatform(it.platform) && (state.filter.allShops || it.id in state.filter.shopIds) }
                                    .any { it.status in listOf("同步失败", "等待重新授权", "等待重新校验") } ||
                                    (state.range?.second == operatingDailyToday().minusDays(1).toString() && dailyTimeDelayed(data.summary.lastCheckedAt))
                                Text(
                                    stringResource(
                                        if (data.total == 0) {
                                            R.string.od_no_data
                                        } else if (delayed) {
                                            R.string.od_delayed
                                        } else if (partial) {
                                            R.string.od_partial
                                        } else {
                                            R.string.od_updated
                                        },
                                    ),
                                    color = FscmInkSoft,
                                )
                                Text(stringResource(R.string.od_source_updated, dailyTime(data.summary.sourceUpdatedAt)), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                            }
                        }
                    }
                    stickyHeader {
                        Column(Modifier.fillMaxWidth().background(FscmPageTop)) {
                            TabRow(selectedTabIndex = if (state.ranking) 1 else 0, containerColor = FscmSurfaceRaised) {
                                Tab(selected = !state.ranking, onClick = { viewModel.selectTab(false) }, text = { Text(stringResource(R.string.od_overview)) })
                                Tab(selected = state.ranking, onClick = { viewModel.selectTab(true) }, text = { Text(stringResource(R.string.od_ranking)) })
                            }
                            if (state.ranking) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("totalOrders" to R.string.od_rank_orders, "saleAmount" to R.string.od_rank_sales, "profit" to R.string.od_rank_profit).forEach { (metric, label) ->
                                        FilterChip(selected = state.rankMetric == metric, onClick = { viewModel.selectMetric(metric) }, label = { Text(stringResource(label)) })
                                    }
                                }
                            }
                        }
                    }
                    state.data?.let { data ->
                        item { Text(stringResource(R.string.od_shop_count, data.total), color = FscmInkSoft) }
                        itemsIndexed(data.items, key = { _, item -> item.id }) { index, item ->
                            if (state.ranking) {
                                DailyRankRow(item, state.rankMetric, index + 1) { viewModel.openShop(item) }
                            } else {
                                DailyShopCard(item) { viewModel.openShop(item) }
                            }
                        }
                        if (data.items.isEmpty() && !state.loading) item { Text(stringResource(R.string.od_no_data)) }
                        item {
                            if (state.pageError) {
                                DailyError(R.string.od_load_failed, viewModel::loadMore)
                            } else if (data.items.size < data.total) {
                                LaunchedEffect(data.items.size) { viewModel.loadMore() }
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else if (data.items.isNotEmpty()) {
                                Text(stringResource(R.string.od_all_loaded), color = FscmInkSoft)
                            }
                        }
                    }
                }
            }
        }
    }
    if (filtersOpen) {
        DailyFilterSheet(state, onDismiss = { filtersOpen = false }, onApply = { viewModel.apply(it) { filtersOpen = false } }, onChange = viewModel::clearFilterError)
    }
}

@Composable
private fun DailySummaryCards(summary: OperatingDailySummary) {
    DailyMetrics(summary.totalOrders, summary.saleAmount, summary.profit, summary.profitMargin, cards = true)
    Text(stringResource(R.string.od_average_currency, dailyMoney(summary.averageOrderValue)), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
}

@Composable
private fun DailyMetrics(orders: Long?, sales: String?, profit: String?, margin: String?, cards: Boolean = false) {
    val metrics = listOf(
        Triple(R.string.od_orders, dailyCount(orders), FscmInkStrong),
        Triple(R.string.od_sales, dailyMoney(sales), FscmBrandMain),
        Triple(R.string.od_profit, dailyMoney(profit), dailyProfitColor(profit)),
        Triple(R.string.od_margin, dailyPercent(margin), dailyProfitColor(margin)),
    )
    val columns = if (LocalConfiguration.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 340) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value, color) ->
                    if (cards) {
                        FscmCard(Modifier.weight(1f), padding = 12) { DailyMetric(label, value, color) }
                    } else {
                        Column(Modifier.weight(1f)) { DailyMetric(label, value, color) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyMetric(label: Int, value: String, color: Color) {
    Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
}

@Composable
private fun DailyShopCard(shop: OperatingDailyShop, onOpen: () -> Unit) {
    FscmCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(shop.name, fontWeight = FontWeight.Bold)
                Text("${shop.platform.uppercase()} · ${shop.site}", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.od_details), tint = FscmBrandMain)
        }
        if (shop.status != "已更新") Text(stringResource(dailyStatusLabel(shop.status)), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
        Box(Modifier.padding(top = 12.dp)) { DailyMetrics(shop.totalOrders, shop.saleAmount, shop.profit, shop.profitMargin) }
    }
}

@Composable
private fun DailyRankRow(shop: OperatingDailyShop, metric: String, rank: Int, onOpen: () -> Unit) {
    val value = if (shop.metric(metric) == null) {
        stringResource(R.string.od_unranked)
    } else if (metric == "totalOrders") {
        stringResource(R.string.od_order_value, dailyCount(shop.totalOrders))
    } else {
        dailyMoney(shop.metric(metric))
    }
    val stacked = LocalConfiguration.current.fontScale > 1.3f || value.length > 16
    FscmCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), padding = 12) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (shop.metric(metric) == null) "—" else rank.toString(), color = if (rank <= 3) FscmBrandMain else FscmInkSoft, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(shop.name, fontWeight = FontWeight.Bold)
                Text("${shop.platform.uppercase()} · ${shop.site}", color = FscmInkSoft, style = MaterialTheme.typography.bodySmall)
                if (shop.status != "已更新") Text(stringResource(dailyStatusLabel(shop.status)), color = FscmInkSoft, style = MaterialTheme.typography.bodySmall)
                if (stacked) {
                    Text(
                        value,
                        color = if (metric == "profit") dailyProfitColor(shop.profit) else FscmBrandMain,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (!stacked) Text(value, color = if (metric == "profit") dailyProfitColor(shop.profit) else FscmBrandMain, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.od_details), tint = FscmInkSoft)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyFilterSheet(state: OperatingDailyUiState, onDismiss: () -> Unit, onApply: (OperatingDailyFilter) -> Unit, onChange: () -> Unit) {
    var draft by remember { mutableStateOf(state.filter) }
    var search by rememberSaveable { mutableStateOf("") }
    val platforms = state.directory.map { it.platform }.distinct().sorted()
    val shops = draft.visibleShops(state.directory)
    val context = LocalContext.current
    val yesterday = operatingDailyToday().minusDays(1)
    fun edit(filter: OperatingDailyFilter) {
        draft = filter
        onChange()
    }
    fun pickDate(start: Boolean) {
        val date = runCatching { LocalDate.parse(if (start) draft.startDate else draft.endDate) }.getOrDefault(yesterday)
        DatePickerDialog(context, { _, year, month, day ->
            val value = LocalDate.of(year, month + 1, day).toString()
            edit(if (start) draft.copy(startDate = value) else draft.copy(endDate = value))
        }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
            datePicker.maxDate = yesterday.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }
    ModalBottomSheet(onDismissRequest = { if (!state.saving) onDismiss() }, modifier = Modifier.fillMaxHeight(0.94f).testTag("daily_filter_sheet"), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = FscmSurfaceRaised) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.od_filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.od_close)) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.od_date), fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DailyDateMode.entries.forEach { mode ->
                            FilterChip(selected = draft.dateMode == mode, onClick = {
                                edit(
                                    draft.copy(
                                        dateMode = mode,
                                        calendar = when (mode) {
                                            DailyDateMode.Month -> YearMonth.from(yesterday).toString()
                                            DailyDateMode.Year -> yesterday.year.toString()
                                            else -> draft.calendar
                                        },
                                        startDate = draft.startDate.ifBlank { yesterday.toString() },
                                        endDate = draft.endDate.ifBlank { yesterday.toString() },
                                    ),
                                )
                            }, label = { Text(stringResource(dateModeLabel(mode))) })
                        }
                    }
                    if (draft.dateMode == DailyDateMode.Month || draft.dateMode == DailyDateMode.Year) {
                        OutlinedTextField(value = draft.calendar, onValueChange = { edit(draft.copy(calendar = it)) }, label = { Text(stringResource(if (draft.dateMode == DailyDateMode.Month) R.string.od_month_input else R.string.od_year_input)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (draft.dateMode == DailyDateMode.Custom) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickDate(true) }) { Text(draft.startDate) }
                            OutlinedButton(onClick = { pickDate(false) }) { Text(draft.endDate) }
                        }
                    }
                    Text(draft.range()?.let { "${it.first} — ${it.second}" } ?: stringResource(R.string.od_invalid_date), color = FscmInkSoft)
                }
                item {
                    Text(stringResource(R.string.od_platforms), fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.allPlatforms, onClick = { edit(draft.copy(allPlatforms = !draft.allPlatforms, platforms = emptySet())) }, label = { Text(stringResource(R.string.od_all_platforms)) })
                        platforms.forEach { platform ->
                            FilterChip(selected = draft.includesPlatform(platform), onClick = {
                                val selected = if (draft.allPlatforms) platforms.toSet() else draft.platforms
                                val next = if (platform in selected) selected - platform else selected + platform
                                edit(draft.copy(allPlatforms = next == platforms.toSet(), platforms = next))
                            }, label = { Text(platform.uppercase()) })
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.od_shops), fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(stringResource(R.string.od_search_shop)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    DailyCheckRow(stringResource(R.string.od_select_all_shops, shops.size), draft.allShops) { edit(draft.copy(allShops = !draft.allShops, shopIds = emptySet())) }
                }
                items(shops.filter { search.isBlank() || "${it.name} ${it.id} ${it.platformShopId} ${it.sourceShopId}".contains(search, ignoreCase = true) }, key = { it.id }) { shop ->
                    DailyCheckRow("${shop.name}\n${shop.platform.uppercase()} · ${shop.site}", draft.allShops || shop.id in draft.shopIds) {
                        val selected = if (draft.allShops) shops.map { it.id }.toSet() else draft.shopIds
                        val next = if (shop.id in selected) selected - shop.id else selected + shop.id
                        edit(draft.copy(allShops = next == shops.map { it.id }.toSet(), shopIds = next))
                    }
                }
                if (shops.isEmpty()) item { Text(stringResource(R.string.od_no_shops)) }
            }
            state.filterError?.let { Text(stringResource(it), color = FscmDanger) }
            Text(stringResource(R.string.od_saved_locally), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                OutlinedButton(onClick = { edit(OperatingDailyFilter()) }, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.od_reset)) }
                Button(onClick = { onApply(draft) }, enabled = !state.saving && draft.range() != null && draft.selectionValid(state.directory), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.od_apply)) }
            }
        }
    }
}

@Composable
private fun DailyCheckRow(label: String, selected: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DailyShopDetail(state: OperatingDailyUiState, viewModel: OperatingDailyViewModel, modifier: Modifier) {
    val shop = state.selectedShop ?: return
    val scroll = rememberLazyListState()
    PullToRefreshBox(isRefreshing = state.detailLoading, onRefresh = { viewModel.loadDetail() }, modifier = modifier.fillMaxSize()) {
        LazyColumn(state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("${shop.platform.uppercase()} · ${shop.site}", color = FscmInkSoft)
                Text(state.range?.let { "${it.first} — ${it.second}" } ?: "—", color = FscmInkSoft)
            }
            state.detailError?.let { error -> item { DailyError(error) { viewModel.loadDetail() } } }
            state.detail?.let { detail ->
                item { DailySummaryCards(detail.summary) }
                item { Text(stringResource(R.string.od_coverage, detail.summary.reportDays, detail.summary.expectedDays, detail.summary.orderDays, detail.summary.profitDays), color = FscmInkSoft, style = MaterialTheme.typography.bodySmall) }
                item {
                    FscmCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.od_costs), fontWeight = FontWeight.Bold)
                        DailyCosts(detail.summary.productCost, detail.summary.adSpend, detail.summary.packingCost, detail.summary.platformCommission)
                        Text(stringResource(R.string.od_cost_basis), color = FscmInkSoft, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(stringResource(R.string.od_daily_details), fontWeight = FontWeight.Bold) }
                items(detail.items, key = { it.reportDate }) { day -> DailyDayCard(day) }
                if (detail.items.isEmpty()) item { Text(stringResource(R.string.od_no_data)) }
                item {
                    if (detail.items.size < detail.total && state.detailError == null) {
                        LaunchedEffect(detail.items.size) { viewModel.loadDetail(append = true) }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (detail.items.isNotEmpty() && detail.items.size >= detail.total) {
                        Text(stringResource(R.string.od_all_loaded), color = FscmInkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyDayCard(day: OperatingDailyDay) {
    var expanded by rememberSaveable(day.shopId, day.reportDate) { mutableStateOf(false) }
    FscmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.reportDate.take(10), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.od_collapse else R.string.od_expand)) }
        }
        DailyMetrics(day.totalOrders, day.saleAmount, day.profit, day.profitMargin)
        Text(stringResource(dailyStatusLabel(day.status)) + " · " + stringResource(if (day.settlementStatus == "settled") R.string.od_settled else R.string.od_pending), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(stringResource(R.string.od_average_currency, dailyMoney(day.averageOrderValue)))
            DailyCosts(day.productCost, day.adSpend, day.packingCost, day.platformCommission)
        }
    }
}

@Composable
private fun DailyCosts(product: OperatingDailyCost, ads: OperatingDailyCost, packing: OperatingDailyCost, commission: OperatingDailyCost) {
    listOf(R.string.od_product_cost to product, R.string.od_ads to ads, R.string.od_packing to packing, R.string.od_commission to commission).forEach { (label, cost) ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(stringResource(label), color = FscmInkSoft, style = MaterialTheme.typography.bodySmall)
            Text("${dailyMoney(cost.amount)} / ${dailyPercent(cost.ratio)}", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DailyError(resource: Int, onRetry: (() -> Unit)? = null) {
    FscmCard(Modifier.fillMaxWidth()) {
        Text(stringResource(resource), color = FscmDanger)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.od_retry)) }
    }
}

internal fun dailyMoney(value: String?): String = value?.toBigDecimalOrNull()?.let {
    "¥" + NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }.format(it)
} ?: "—"

internal fun dailyPercent(value: String?): String = value?.toBigDecimalOrNull()?.multiply(100.toBigDecimal())?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.plus("%") ?: "—"
private fun dailyCount(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"
private fun dailyProfitColor(value: String?): Color = when (value?.toBigDecimalOrNull()?.signum()) {
    1 -> FscmSuccess
    -1 -> FscmDanger
    else -> FscmInkStrong
}
private fun dailyTime(value: String?): String = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(value)) }.getOrDefault("—")
private fun dailyTimeDelayed(value: String?): Boolean = runCatching { Instant.now().epochSecond - Instant.parse(value).epochSecond > 600 }.getOrDefault(true)
private fun dailyStatusLabel(status: String): Int = when (status) {
    "已更新" -> R.string.od_updated
    "同步失败" -> R.string.od_delayed
    "等待重新授权" -> R.string.od_reauthorize
    "等待重新校验" -> R.string.od_revalidate
    else -> R.string.od_partial
}

private fun dateModeLabel(mode: DailyDateMode): Int = when (mode) {
    DailyDateMode.Yesterday -> R.string.od_yesterday
    DailyDateMode.Last3 -> R.string.od_last3
    DailyDateMode.Last7 -> R.string.od_last7
    DailyDateMode.Last30 -> R.string.od_last30
    DailyDateMode.Month -> R.string.od_month
    DailyDateMode.Year -> R.string.od_year
    DailyDateMode.Custom -> R.string.od_custom
}
