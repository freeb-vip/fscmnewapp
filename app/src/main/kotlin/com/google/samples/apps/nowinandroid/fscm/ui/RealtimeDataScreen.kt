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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RealtimeHomeCard(state: RealtimeUiState, onOpen: () -> Unit, onSelect: (String) -> Unit, onRetry: () -> Unit) {
    if (!state.allowed) return
    RealtimeCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rt_today), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RealtimeStatus(state)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.rt_view_details), tint = FscmBrandMain)
        }
        RealtimeAccountSelector(state, onSelect)
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
                item(key = "account") { RealtimeAccountSelector(state, onSelect) }
                item(key = "today") {
                    RealtimeCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rt_today), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            RealtimeStatus(state)
                        }
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
                    if (module?.status != "ready") {
                        Text(module?.reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.rt_waiting), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                    }
                    module?.fetchedAt?.let { Text(stringResource(R.string.rt_updated, realtimeTime(it)), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft) }
                }
                if (tab == 0) {
                    itemsIndexed(state.sales?.shops.orEmpty(), key = { _, shop -> "shop:${shop.id}" }) { index, shop -> RealtimeShopRow(index + 1, shop) }
                } else {
                    itemsIndexed(state.sales?.products.orEmpty(), key = { _, product -> "product:${product.shopId}:${product.id}:${product.sku}" }) { index, product -> RealtimeProductRow(index + 1, product) }
                }
                if (if (tab == 0) state.sales?.shops.isNullOrEmpty() else state.sales?.products.isNullOrEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.rt_no_ranking), modifier = Modifier.padding(vertical = 24.dp), color = FscmInkSoft) }
                }
                item(key = "limits") {
                    state.sales?.let {
                        Text(stringResource(R.string.rt_source_limit, it.rankingLimit), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun RealtimeCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = FscmSurfaceRaised, contentColor = FscmInkStrong),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
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
    var open by remember { mutableStateOf(false) }
    val selected = state.accounts.firstOrNull { it.id == state.selectedId }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = state.accounts.isNotEmpty()) { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            selected?.let { "${realtimeProvider(it.provider)} · ${it.name}" }
                ?: stringResource(if (state.accountsLoaded && state.accounts.isEmpty()) R.string.rt_no_account else R.string.rt_select_account),
            modifier = Modifier.weight(1f),
            color = FscmInkSoft,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.accounts.isNotEmpty()) Icon(Icons.Rounded.ExpandMore, stringResource(R.string.rt_select_account), tint = FscmBrandMain)
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = FscmSurfaceRaised) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { Text(stringResource(R.string.rt_select_account), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                state.accounts.groupBy { it.provider }.forEach { (provider, accounts) ->
                    item(key = "provider:$provider") { Text(realtimeProvider(provider), modifier = Modifier.padding(vertical = 12.dp), color = FscmBrandMain) }
                    items(accounts, key = { "account:${it.id}" }) { account ->
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
}

@Composable
private fun realtimeProvider(provider: String): String = when (provider) {
    "xinjian", "" -> stringResource(R.string.rt_xinjian)
    "bigseller" -> "BigSeller"
    else -> provider
}

@Composable
private fun RealtimeMetrics(state: RealtimeUiState, expanded: Boolean) {
    val sales = state.sales
    val yesterday = sales?.yesterdayTotal()
    val comparison = stringResource(if (sales?.comparison?.mode == "current_time") R.string.rt_previous_time else R.string.rt_previous_period)
    RealtimeColumns(
        first = {
            RealtimeMetric(
                stringResource(R.string.rt_sales),
                sales?.summary?.amount,
                "CNY",
                if (expanded) 32 else 24,
                2,
                realtimeChange(sales?.comparison?.current?.amount, sales?.comparison?.previous?.amount),
                if (expanded) listOf(comparison to realtimeNumber(sales?.comparison?.previous?.amount), stringResource(R.string.rt_yesterday_total) to realtimeNumber(yesterday?.amount)) else emptyList(),
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
                if (expanded) listOf(comparison to realtimeNumber(sales?.comparison?.previous?.orders?.toString(), 0), stringResource(R.string.rt_yesterday_total) to realtimeNumber(yesterday?.orders?.toString(), 0)) else emptyList(),
            )
        },
    )
    Text(
        stringResource(R.string.rt_sales_context, sales?.date ?: realtimeDate(System.currentTimeMillis(), "Asia/Shanghai").orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = FscmInkSoft,
    )
    if (!expanded) Text(comparison, style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
    if (expanded) {
        sales?.modules?.get("summary")?.let { module ->
            module.fetchedAt?.let { Text(stringResource(R.string.rt_updated, realtimeTime(it)), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft) }
            if (module.status != "ready" && module.reason.isNotBlank()) Text(module.reason, style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
        }
    }
    if (state.showWork) {
        val work = requireNotNull(state.work)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RealtimeColumns(
            first = {
                RealtimeMetric(
                    stringResource(R.string.rt_work),
                    work.today.packed?.toString(),
                    stringResource(R.string.rt_order_unit),
                    if (expanded) 24 else 20,
                    0,
                    realtimeChange(work.today.packed?.toString(), work.yesterday.packed?.toString()),
                    if (expanded) listOf(stringResource(R.string.rt_yesterday_total) to realtimeNumber(work.yesterday.packed?.toString(), 0)) else emptyList(),
                )
                if (expanded) Text(stringResource(R.string.rt_updated, realtimeTime(work.today.packedAt, work.today.timezone)), style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
            },
            second = {
                RealtimeMetric(
                    stringResource(R.string.rt_efficiency),
                    work.today.efficiency?.toString(),
                    stringResource(R.string.rt_efficiency_unit),
                    if (expanded) 24 else 20,
                    2,
                    realtimeChange(work.today.efficiency?.toString(), work.yesterday.efficiency?.toString()),
                    if (expanded) listOf(stringResource(R.string.rt_yesterday_total) to realtimeNumber(work.yesterday.efficiency?.toString())) else emptyList(),
                )
                if (expanded) Text(stringResource(R.string.rt_updated, realtimeTime(work.today.efficiencyAt, work.today.timezone)), style = MaterialTheme.typography.labelSmall, color = FscmInkSoft)
            },
        )
        Text(stringResource(R.string.rt_work_context, work.today.date, work.today.timezone), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
        if (expanded) Text(stringResource(R.string.rt_work_basis), style = MaterialTheme.typography.bodySmall, color = FscmInkSoft)
    }
}

@Composable
private fun RealtimeColumns(first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    if (LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(content = first)
            Column(content = second)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = first)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = second)
        }
    }
}

@Composable
private fun RealtimeMetric(label: String, raw: String?, unit: String, size: Int, digits: Int, change: RealtimeChange, previous: List<Pair<String, String>>) {
    Text(label, color = FscmInkSoft, style = MaterialTheme.typography.bodySmall)
    Text(realtimeNumber(raw, digits), fontSize = size.sp, lineHeight = (size + 6).sp, fontWeight = FontWeight.Bold, color = FscmBrandMain)
    Text(unit, color = FscmInkSoft, style = MaterialTheme.typography.labelSmall)
    previous.forEach { (title, value) -> Text("$title  $value", style = MaterialTheme.typography.bodySmall, color = FscmInkSoft) }
    RealtimeChangeText(change)
}

@Composable
private fun RealtimeChangeText(change: RealtimeChange) {
    val text = change.text ?: when {
        change.isNew -> stringResource(R.string.rt_new)
        change.flat -> stringResource(R.string.rt_flat)
        else -> return
    }
    Text(
        text,
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
    listOfNotNull(state.salesError, state.workError).distinct().forEach { Text(it.resolve(), color = FscmDanger, style = MaterialTheme.typography.bodySmall) }
    if (state.salesError != null || state.workError != null) TextButton(onClick = onRetry, enabled = !state.loading) { Text(stringResource(R.string.rt_retry)) }
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

private fun realtimeTime(value: String?, timezone: String = "Asia/Shanghai"): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.parse(value).atZone(ZoneId.of(timezone)))
}.getOrDefault("—")
