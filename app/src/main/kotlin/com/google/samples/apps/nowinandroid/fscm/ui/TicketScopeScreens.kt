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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption
import com.google.samples.apps.nowinandroid.fscm.data.TicketScopeItem
import com.google.samples.apps.nowinandroid.fscm.data.TicketScopeRepository
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceTicket
import com.google.samples.apps.nowinandroid.fscm.data.ticketScopeAttributes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TicketScopeState(
    val options: List<TicketReferenceOption> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Int = 0,
)

@Composable
fun TicketScopeFilters(state: TicketsUiState, onApply: (List<TicketReferenceOption>, String, String, TicketReferenceOption?, TicketReferenceOption?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var selectedSku by remember { mutableStateOf(state.scopeSku) }
    var selectedProduct by remember { mutableStateOf(state.scopeProduct) }
    var shops by remember { mutableStateOf(state.scopeShops) }
    var sku by remember { mutableStateOf(state.skuCode) }
    var product by remember { mutableStateOf(state.productCode) }
    TextButton(onClick = {
        shops = state.scopeShops
        selectedSku = state.scopeSku
        selectedProduct = state.scopeProduct
        sku = state.skuCode
        product = state.productCode
        open = true
    }, enabled = !state.loading) { Text(stringResource(R.string.ticket_scope_filters)) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.ticket_scope_filters)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { choosing = "shop" }) { Text(stringResource(R.string.ticket_scope_shop)) }
                    shops.forEach { shop -> TextButton(onClick = { shops = shops.filterNot { it.id == shop.id } }) { Text(shop.label + " ×") } }
                    OutlinedTextField(sku, {
                        sku = it
                        selectedSku = null
                    }, label = { Text(stringResource(R.string.ticket_scope_sku)) })
                    OutlinedTextField(product, {
                        product = it
                        selectedProduct = null
                    }, label = { Text(stringResource(R.string.ticket_scope_product)) })
                    TextButton(onClick = { choosing = "sku" }) { Text(selectedSku?.label ?: stringResource(R.string.ticket_scope_choose_sku)) }
                    TextButton(onClick = { choosing = "product" }) { Text(selectedProduct?.label ?: stringResource(R.string.ticket_scope_choose_product)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onApply(shops, sku, product, selectedSku, selectedProduct)
                    open = false
                }) { Text(stringResource(R.string.ticket_scope_search)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    shops = emptyList()
                    sku = ""
                    product = ""
                    selectedSku = null
                    selectedProduct = null
                    onApply(shops, sku, product, null, null)
                    open = false
                }) { Text(stringResource(R.string.ticket_scope_clear)) }
            },
        )
    }
    choosing?.let { kind ->
        TicketReferenceDialog(kind, { option ->
            when (kind) {
                "shop" -> if (shops.none { it.id == option.id }) shops = shops + option
                "sku" -> {
                    selectedSku = option
                    sku = ""
                }
                else -> {
                    selectedProduct = option
                    product = ""
                }
            }
            choosing = null
        }, { choosing = null })
    }
}

@HiltViewModel
class TicketScopeViewModel @Inject constructor(private val repository: TicketScopeRepository) : ViewModel() {
    private val mutable = MutableStateFlow(TicketScopeState())
    val state = mutable.asStateFlow()
    private var search: Job? = null
    private var generation = 0

    fun search(kind: String, query: String, next: Boolean = false) {
        search?.cancel()
        val epoch = ++generation
        val page = if (next) mutable.value.page + 1 else 1
        mutable.value = mutable.value.copy(busy = true, error = null, options = if (next) mutable.value.options else emptyList())
        search = viewModelScope.launch {
            runCatching { repository.references(kind, query, page) }
                .onSuccess { result ->
                    if (epoch == generation) mutable.value = mutable.value.copy(options = if (next) mutable.value.options + result.items else result.items, total = result.total, page = page, busy = false)
                }
                .onFailure { if (epoch == generation) mutable.value = mutable.value.copy(error = it.message, busy = false) }
        }
    }

    fun save(id: Long, version: Long, items: List<TicketScopeItem>) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { repository.save(id, version, items) }
                .onSuccess { mutable.value = mutable.value.copy(saving = false, saved = mutable.value.saved + 1) }
                .onFailure { mutable.value = mutable.value.copy(saving = false, error = it.message) }
        }
    }
}

@Composable
fun ticketScopeLabel(item: TicketScopeItem): String = listOf(
    item.shopName.ifBlank { stringResource(R.string.ticket_scope_unrestricted) },
    item.skuCode.ifBlank { item.inventorySku.ifBlank { item.shopSku.ifBlank { item.productCode } } },
    item.productName.ifBlank { item.sourceItemName },
    item.spec.ifBlank { item.sourceSpec },
    if (item.productId != null && item.skuId == null && item.orderLineId == null) stringResource(R.string.ticket_scope_whole_product) else "",
).filter(String::isNotBlank).joinToString(" · ")

@Composable
fun TicketScopeRows(items: List<TicketScopeItem>, compact: Boolean = false) {
    val visible = if (compact && items.any { it.matched }) items.filter { it.matched } else items
    if (visible.isEmpty()) Text(stringResource(R.string.ticket_scope_empty))
    if (compact && visible.any { it.matched }) Text(stringResource(R.string.ticket_scope_matches))
    visible.take(if (compact) 2 else visible.size).forEach { item ->
        Text(ticketScopeLabel(item), style = MaterialTheme.typography.bodyMedium)
        if (!compact && item.unavailable) Text(stringResource(R.string.ticket_scope_unavailable))
        if (!compact && item.orderLineId != null && item.productId == null) Text(stringResource(R.string.ticket_scope_unmatched))
        if (!compact) item.sourceItems.forEach { source -> Text(listOf(source.name, source.spec, source.shopSku, source.inventorySku).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
        if (!compact) {
            val attributes = ticketScopeAttributes(item.attributes)
            if (attributes.isNotBlank()) Text(attributes, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (compact && visible.size > 2) Text(stringResource(R.string.ticket_scope_more, visible.size - 2))
}

@Composable
fun TicketReferenceDialog(kind: String, onSelect: (TicketReferenceOption) -> Unit, onDismiss: () -> Unit, viewModel: TicketScopeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember(kind) { mutableStateOf("") }
    LaunchedEffect(kind) { viewModel.search(kind, "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind == "shop") {
                        R.string.ticket_scope_shop
                    } else if (kind == "sku") {
                        R.string.ticket_scope_sku
                    } else {
                        R.string.ticket_scope_product
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.ticket_scope_search)) })
                TextButton(onClick = { viewModel.search(kind, query) }, enabled = !state.busy) { Text(stringResource(R.string.ticket_scope_search)) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.options.forEach { option -> TextButton(onClick = { onSelect(option) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(option.label) } }
                if (state.options.size < state.total) TextButton(onClick = { viewModel.search(kind, query, true) }, enabled = !state.busy) { Text(stringResource(R.string.common_load_more)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun TicketScopeCard(ticket: WorkplaceTicket, onSaved: () -> Unit, viewModel: TicketScopeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember(ticket.id) { mutableStateOf(false) }
    var draft by remember(ticket.id) { mutableStateOf(ticket.scopeItems) }
    var version by remember(ticket.id) { mutableStateOf(ticket.scopeVersion) }
    var shop by remember { mutableStateOf<TicketReferenceOption?>(null) }
    var good by remember { mutableStateOf<TicketReferenceOption?>(null) }
    var goodKind by remember { mutableStateOf("sku") }
    var choosing by remember { mutableStateOf<String?>(null) }
    val savedAtOpen = remember { mutableStateOf(state.saved) }
    LaunchedEffect(state.saved) {
        if (state.saved > savedAtOpen.value) {
            savedAtOpen.value = state.saved
            editing = false
            onSaved()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ticket_scope_title))
            TicketScopeRows(ticket.scopeItems)
            if (ticket.permissions.editScope) {
                TextButton(onClick = {
                    draft = ticket.scopeItems
                    version = ticket.scopeVersion
                    editing = true
                }) { Text(stringResource(R.string.ticket_scope_edit)) }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!state.saving) editing = false },
            title = { Text(stringResource(R.string.ticket_scope_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ticket_scope_edit_hint))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { choosing = "shop" }, enabled = !state.saving) { Text(shop?.label ?: stringResource(R.string.ticket_scope_shop)) }
                    if (shop != null) TextButton(onClick = { shop = null }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_clear_shop)) }
                    Row {
                        TextButton(onClick = { choosing = "sku" }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_sku)) }
                        TextButton(onClick = { choosing = "product" }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_product)) }
                    }
                    good?.let {
                        Text(it.label)
                        TextButton(onClick = { good = null }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_clear_product)) }
                    }
                    TextButton(
                        onClick = {
                            val row = TicketScopeItem(shopId = shop?.id, shopName = shop?.name.orEmpty(), productId = if (goodKind == "product") good?.id else good?.productId, productName = if (goodKind == "product") good?.name.orEmpty() else good?.productName.orEmpty(), productCode = if (goodKind == "product") good?.code.orEmpty() else good?.productCode.orEmpty(), skuId = if (goodKind == "sku") good?.id else null, skuCode = if (goodKind == "sku") good?.code.orEmpty() else "", spec = good?.spec.orEmpty())
                            if (draft.none { it.identity() == row.identity() }) draft = draft + row
                            good = null
                        },
                        enabled = !state.saving && (shop != null || good != null) && draft.size < 500,
                    ) { Text(stringResource(R.string.ticket_scope_add)) }
                    draft.forEach { row ->
                        Text(ticketScopeLabel(row))
                        TextButton(onClick = { draft = draft.filterNot { it === row } }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_remove)) }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.save(ticket.id, version, draft) }, enabled = !state.saving) { Text(stringResource(R.string.ticket_scope_save)) } },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !state.saving) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    choosing?.let { kind ->
        TicketReferenceDialog(kind, {
            if (kind == "shop") {
                shop = it
            } else {
                good = it
                goodKind = kind
            }
            choosing = null
        }, { choosing = null }, viewModel)
    }
}
