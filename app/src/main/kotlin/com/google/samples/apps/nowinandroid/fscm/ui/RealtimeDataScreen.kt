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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmPageTop
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSuccess
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmSurfaceRaised
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeProduct
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeShop

@Composable
internal fun RealtimeHomeCard(state: RealtimeUiState, onOpen: () -> Unit, onSelect: (String) -> Unit, onRetry: () -> Unit) {
    if (!state.allowed) return
    RealtimeCard(onClick = onOpen, prominent = true) {
        RealtimeHeader(state, onSelect, showDetails = true)
        RealtimeMetrics(state, expanded = false)
        RealtimeNotices(state, onRetry)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RealtimeDataScreen(state: RealtimeUiState, onSelect: (String) -> Unit, onRefresh: () -> Unit) {
    if (!state.allowed) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.rt_access_lost))
        }
        return
    }
    key(state.scope, state.selectedId) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val shopsScroll = rememberLazyListState()
        val productsScroll = rememberLazyListState()
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh) {
            LazyColumn(
                state = if (tab == 0) shopsScroll else productsScroll,
                modifier = Modifier.fillMaxSize().background(FscmPageTop).testTag("realtime-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "today") {
                    RealtimeCard(prominent = true) {
                        RealtimeHeader(state, onSelect)
                        RealtimeMetrics(state, expanded = true)
                        RealtimeNotices(state, onRefresh)
                    }
                }
                stickyHeader(key = "tabs") {
                    TabRow(selectedTabIndex = tab, containerColor = FscmSurfaceRaised, contentColor = FscmBrandMain) {
                        listOf(R.string.rt_shops, R.string.rt_products).forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                        }
                    }
                }
                item(key = "basis") {
                    Text(
                        stringResource(
                            if (tab == 0) {
                                R.string.rt_shop_basis
                            } else if (state.sales?.productBasis == "product_link") {
                                R.string.rt_product_link_basis
                            } else {
                                R.string.rt_product_basis
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = FscmInkSoft,
                    )
                    val module = state.sales?.modules?.get(if (tab == 0) "shops" else "products")
                    if (module != null && module.status != "ready") {
                        Text(stringResource(if (module.status == "delayed") R.string.rt_delayed else R.string.rt_waiting), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                    }
                }
                if (tab == 0) {
                    itemsIndexed(state.sales?.shops.orEmpty(), key = { _, shop -> "shop:${shop.id}" }) { index, shop -> RealtimeShopRow(index + 1, shop) }
                } else {
                    itemsIndexed(state.sales?.products.orEmpty(), key = { _, product -> "product:${product.shopId}:${product.id}:${product.sku}" }) { index, product -> RealtimeProductRow(index + 1, product) }
                }
                if (if (tab == 0) state.sales?.shops.isNullOrEmpty() else state.sales?.products.isNullOrEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.rt_no_ranking), modifier = Modifier.padding(vertical = 24.dp), color = FscmInkSoft) }
                }
            }
        }
    }
}

@Composable
private fun RealtimeHeader(state: RealtimeUiState, onSelect: (String) -> Unit, showDetails: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.rt_today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            RealtimeStatus(state)
        }
        RealtimeAccountSelector(state, onSelect)
        if (showDetails) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.rt_view_details))
    }
}

@Composable
private fun RealtimeCard(onClick: (() -> Unit)? = null, prominent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (prominent) FscmBrandDeep else FscmSurfaceRaised, contentColor = if (prominent) Color.White else FscmInkStrong),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (prominent) Modifier.background(Brush.linearGradient(listOf(FscmBrandMain, FscmBrandDeep))) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun RealtimeStatus(state: RealtimeUiState) {
    val delayed = state.salesError != null || state.workError != null || state.sales?.modules?.values?.any { it.status == "delayed" } == true ||
        state.showWork && state.work?.today?.status != "ready"
    Text(
        stringResource(
            if (state.loading) {
                R.string.rt_updating
            } else if (delayed) {
                R.string.rt_delayed
            } else {
                R.string.rt_live
            },
        ),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(FscmBrandSoft).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (delayed) FscmDanger else FscmBrandMain,
        style = MaterialTheme.typography.labelSmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealtimeAccountSelector(state: RealtimeUiState, onSelect: (String) -> Unit) {
    var open by rememberSaveable(state.scope) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = state.accounts.isNotEmpty(), modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).testTag("realtime-account-switch")) {
        Icon(Icons.Rounded.SwapHoriz, stringResource(R.string.rt_switch_account))
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = FscmSurfaceRaised) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { Text(stringResource(R.string.rt_select_account), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(state.accounts, key = { "account:${it.id}" }) { account ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            open = false
                            onSelect(account.id)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = account.id == state.selectedId, onClick = null)
                        Text(account.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RealtimeMetrics(state: RealtimeUiState, expanded: Boolean) {
    val sales = state.sales
    val yesterday = sales?.yesterdayTotal()
    val comparison = stringResource(if (sales?.comparison?.mode == "current_time") R.string.rt_change_time else R.string.rt_change_period)
    RealtimeColumns(
        stacked = realtimeNumber(sales?.summary?.amount).length > 12 || realtimeNumber(yesterday?.amount).length > 12,
        first = {
            RealtimeMetric(
                stringResource(R.string.rt_sales),
                sales?.summary?.amount,
                "CNY",
                if (expanded) 32 else 24,
                2,
                realtimeChange(sales?.comparison?.current?.amount, sales?.comparison?.previous?.amount),
                listOf(stringResource(R.string.rt_yesterday_total) to realtimeNumber(yesterday?.amount)),
                prominent = true,
                comparisonLabel = comparison,
            )
        },
        second = {
            RealtimeMetric(
                stringResource(R.string.rt_orders),
                sales?.summary?.orders?.toString(),
                stringResource(R.string.rt_order_unit),
                if (expanded) 32 else 24,
                0,
                realtimeChange(sales?.comparison?.current?.orders?.toString(), sales?.comparison?.previous?.orders?.toString()),
                listOf(stringResource(R.string.rt_yesterday_total) to realtimeNumber(yesterday?.orders?.toString(), 0)),
                prominent = true,
                comparisonLabel = comparison,
            )
        },
    )
    if (state.showWork) {
        val work = requireNotNull(state.work)
        val workComparison = work.comparison?.takeIf { it.available }
        val workLabel = stringResource(R.string.rt_work_comparison, work.comparison?.cutoff?.ifBlank { "—" } ?: "—")
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
        RealtimeColumns(
            first = {
                RealtimeMetric(
                    stringResource(R.string.rt_work),
                    work.today.count?.toString(),
                    stringResource(R.string.rt_work_unit),
                    if (expanded) 24 else 20,
                    0,
                    realtimeChange(workComparison?.current?.count?.toString(), workComparison?.previous?.count?.toString()),
                    listOf(stringResource(R.string.rt_previous_period) to realtimeNumber(workComparison?.previous?.count?.toString(), 0)),
                    prominent = true,
                    comparisonLabel = workLabel,
                )
            },
            second = {
                RealtimeMetric(
                    stringResource(if (work.today.basis == "distinct_orders") R.string.rt_order_efficiency else R.string.rt_efficiency),
                    work.today.efficiency?.toString(),
                    stringResource(if (work.today.basis == "distinct_orders") R.string.rt_order_efficiency_unit else R.string.rt_efficiency_unit),
                    if (expanded) 24 else 20,
                    2,
                    realtimeChange(workComparison?.current?.efficiency?.toString(), workComparison?.previous?.efficiency?.toString()),
                    listOf(stringResource(R.string.rt_previous_period) to realtimeNumber(workComparison?.previous?.efficiency?.toString())),
                    prominent = true,
                    comparisonLabel = workLabel,
                )
            },
        )
    }
}

@Composable
private fun RealtimeColumns(stacked: Boolean = false, first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    if (stacked || LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = first)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = second)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = first)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = second)
        }
    }
}

@Composable
private fun RealtimeMetric(
    label: String,
    raw: String?,
    unit: String,
    size: Int,
    digits: Int,
    change: RealtimeChange,
    previous: List<Pair<String, String>>,
    prominent: Boolean = false,
    comparisonLabel: String? = null,
) {
    val muted = if (prominent) Color.White.copy(alpha = 0.85f) else FscmInkSoft
    Text(label, color = muted, style = MaterialTheme.typography.bodySmall)
    Text(
        realtimeNumber(raw, digits),
        modifier = Modifier.fillMaxWidth(),
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = size.sp, stepSize = 1.sp),
        lineHeight = (size + 6).sp,
        maxLines = 1,
        fontWeight = FontWeight.Bold,
        color = if (prominent) Color.White else FscmBrandMain,
    )
    Text(unit, color = muted, style = MaterialTheme.typography.labelSmall)
    previous.forEach { (title, value) -> Text("$title  $value", style = MaterialTheme.typography.bodySmall, color = muted) }
    RealtimeChangeText(change, comparisonLabel, prominent)
}

@Composable
private fun RealtimeChangeText(change: RealtimeChange, label: String? = null, prominent: Boolean = false) {
    val text = change.text ?: when {
        change.isNew -> stringResource(R.string.rt_new)
        change.flat -> stringResource(R.string.rt_flat)
        else -> return
    }
    Text(
        listOfNotNull(label, text).joinToString("  "),
        modifier = if (prominent) Modifier.clip(RoundedCornerShape(8.dp)).background(FscmSurfaceRaised).padding(horizontal = 8.dp, vertical = 4.dp) else Modifier,
        color = if (change.direction > 0) {
            FscmSuccess
        } else if (change.direction < 0) {
            FscmDanger
        } else {
            FscmInkSoft
        },
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun RealtimeNotices(state: RealtimeUiState, onRetry: () -> Unit) {
    if (state.accountsLoaded && state.accounts.isEmpty()) Text(stringResource(R.string.rt_no_account), style = MaterialTheme.typography.bodySmall)
    listOfNotNull(state.salesError, state.workError).distinct().forEach { Text(it.resolve(), style = MaterialTheme.typography.bodySmall) }
    if (state.salesError != null || state.workError != null) TextButton(onClick = onRetry, enabled = !state.loading) { Text(stringResource(R.string.rt_retry), color = Color.White) }
}

@Composable
private fun RealtimeRankIdentity(rank: Int, title: String, subtitle: String, image: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString().padStart(2, '0'), color = if (rank <= 3) FscmBrandMain else FscmInkSoft, fontWeight = FontWeight.Bold)
        image?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
        }
    }
}

@Composable
private fun RealtimeShopRow(rank: Int, shop: RealtimeShop) {
    RealtimeCard {
        RealtimeRankIdentity(rank, shop.name.ifBlank { shop.id }, listOf(shop.platform, shop.site).filter { it.isNotBlank() }.joinToString(" · "))
        RealtimeColumns(
            first = { RealtimeMetric(stringResource(R.string.rt_orders), shop.orders?.toString(), stringResource(R.string.rt_order_unit), 20, 0, if (shop.orders != null) realtimeRatio(shop.ordersChangeRatio) else RealtimeChange(), emptyList()) },
            second = { RealtimeMetric(stringResource(R.string.rt_sales), shop.amount, "CNY", 20, 2, if (shop.amount != null) realtimeRatio(shop.amountChangeRatio) else RealtimeChange(), emptyList()) },
        )
    }
}

@Composable
private fun RealtimeProductRow(rank: Int, product: RealtimeProduct) {
    RealtimeCard {
        RealtimeRankIdentity(rank, product.sku.ifBlank { product.name.ifBlank { product.id } }, listOf(product.name.takeIf { product.sku.isNotBlank() }.orEmpty(), product.shopName).filter { it.isNotBlank() }.joinToString(" · ")) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(FscmBrandSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Image, null, tint = FscmInkSoft)
                AsyncImage(model = product.imageUrl.takeIf { it.isNotBlank() }, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        RealtimeColumns(
            first = { RealtimeMetric(stringResource(R.string.rt_quantity), product.quantity?.toString(), stringResource(R.string.rt_piece_unit), 20, 0, RealtimeChange(), emptyList()) },
            second = { RealtimeMetric(stringResource(R.string.rt_sales), product.amount, "CNY", 20, 2, RealtimeChange(), emptyList()) },
        )
    }
}
