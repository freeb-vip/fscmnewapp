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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductBoxDetail
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductBusiness
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductDocumentHeader
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductProgress
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductProgressRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductWorkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val ProductBrand = Color(0xFF1677FF)
private val ProductDeep = Color(0xFF0958D9)
private val ProductBackground = Color(0xFFF5F8FF)
private val ProductStroke = Color(0xFFDCE7F5)
private val ProductInk = Color(0xFF172033)
private val ProductMuted = Color(0xFF66758A)
private val ProductSuccess = Color(0xFF389E0D)
private val ProductWarning = Color(0xFFD48806)
private val ProductDanger = Color(0xFFCF1322)

enum class ReceivingProductFilter {
    All,
    Pending,
    Exception,
    Completed,
}

data class ReceivingProductProgressUiState(
    val business: ReceivingProductBusiness = ReceivingProductBusiness.Warehouse,
    val documentId: Long = 0,
    val header: ReceivingProductDocumentHeader = ReceivingProductDocumentHeader(),
    val items: List<ReceivingProductProgress> = emptyList(),
    val query: String = "",
    val filter: ReceivingProductFilter = ReceivingProductFilter.Pending,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val cacheComplete: Boolean = false,
    val cacheUpdatedAt: Long = 0,
    val error: String = "",
    val selectedProduct: ReceivingProductProgress? = null,
    val boxDetails: List<ReceivingProductBoxDetail> = emptyList(),
    val boxDetailsLoading: Boolean = false,
) {
    val visibleItems: List<ReceivingProductProgress>
        get() = filterReceivingProducts(items, query, filter)
}

@HiltViewModel
class ReceivingProductProgressViewModel @Inject constructor(
    private val repository: ReceivingProductProgressRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReceivingProductProgressUiState())
    val state = _state.asStateFlow()
    private var observeJob: Job? = null

    fun load(business: ReceivingProductBusiness, documentId: Long) {
        if (_state.value.documentId == documentId && _state.value.business == business && observeJob != null) return
        observeJob?.cancel()
        _state.value = ReceivingProductProgressUiState(business = business, documentId = documentId, loading = true)
        observeJob = viewModelScope.launch {
            launch {
                runCatching { repository.documentHeader(business, documentId) }
                    .onSuccess { header ->
                        _state.update {
                            it.copy(
                                header = header,
                                filter = if (header.status in setOf("completed", "cancelled", "complete")) {
                                    ReceivingProductFilter.All
                                } else {
                                    ReceivingProductFilter.Pending
                                },
                            )
                        }
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message.orEmpty()) } }
            }
            launch {
                repository.observe(business, documentId).collect { snapshot ->
                    _state.update {
                        it.copy(
                            items = snapshot.items,
                            cacheComplete = snapshot.complete,
                            cacheUpdatedAt = snapshot.updatedAt,
                            loading = false,
                        )
                    }
                }
            }
            launch {
                _state.update { it.copy(refreshing = true) }
                repository.refresh(business, documentId)
                _state.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setFilter(value: ReceivingProductFilter) = _state.update { it.copy(filter = value) }

    fun openProduct(product: ReceivingProductProgress) {
        _state.update { it.copy(selectedProduct = product, boxDetails = emptyList(), boxDetailsLoading = true) }
        viewModelScope.launch {
            runCatching { repository.boxDetails(product) }
                .onSuccess { rows -> _state.update { it.copy(boxDetails = rows, boxDetailsLoading = false) } }
                .onFailure { error -> _state.update { it.copy(boxDetailsLoading = false, error = error.message.orEmpty()) } }
        }
    }

    fun closeProduct() = _state.update { it.copy(selectedProduct = null, boxDetails = emptyList()) }
}

internal fun filterReceivingProducts(
    items: List<ReceivingProductProgress>,
    query: String,
    filter: ReceivingProductFilter,
): List<ReceivingProductProgress> {
    val normalized = query.trim().lowercase()
    return items.asSequence()
        .filter { item ->
            normalized.isBlank() || item.productCode.lowercase().contains(normalized) || item.productName.lowercase().contains(normalized)
        }
        .filter { item ->
            when (filter) {
                ReceivingProductFilter.All -> true
                ReceivingProductFilter.Pending -> item.pendingBoxCount > 0 && item.workStatus == ReceivingProductWorkStatus.Pending.value
                ReceivingProductFilter.Exception -> item.exceptionBoxCount > 0 || item.workStatus == ReceivingProductWorkStatus.ClosedWithException.value
                ReceivingProductFilter.Completed -> item.workStatus in setOf(
                    ReceivingProductWorkStatus.Completed.value,
                    ReceivingProductWorkStatus.ClosedWithException.value,
                )
            }
        }
        .sortedWith(
            compareByDescending<ReceivingProductProgress> { it.exceptionBoxCount > 0 }
                .thenByDescending { it.pendingBoxCount }
                .thenByDescending { it.lastOperatedAt }
                .thenBy { it.productCode },
        )
        .toList()
}

@Composable
fun ReceivingProductProgressScreen(
    business: ReceivingProductBusiness,
    documentId: Long,
    viewModel: ReceivingProductProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(business, documentId) { viewModel.load(business, documentId) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ProductBackground),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ReceivingProductDocumentSummary(state) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.receiving_product_search)) },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReceivingProductFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(
                                stringResource(
                                    when (filter) {
                                        ReceivingProductFilter.All -> R.string.receiving_product_filter_all
                                        ReceivingProductFilter.Pending -> R.string.receiving_product_filter_pending
                                        ReceivingProductFilter.Exception -> R.string.receiving_product_filter_exception
                                        ReceivingProductFilter.Completed -> R.string.receiving_product_filter_completed
                                    },
                                ),
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!state.cacheComplete) {
            item {
                Text(
                    stringResource(
                        if (state.items.isEmpty()) R.string.receiving_product_cache_preparing else R.string.receiving_product_cache_partial,
                    ),
                    color = ProductWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (state.cacheUpdatedAt > 0) {
            item {
                Text(
                    stringResource(R.string.receiving_product_cache_time, formatReceivingProductTime(state.cacheUpdatedAt)),
                    color = ProductMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.loading && state.items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProductBrand)
                }
            }
        } else if (state.visibleItems.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.receiving_product_empty),
                    color = ProductMuted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
            }
        } else {
            items(state.visibleItems, key = { it.productKey }) { product ->
                ReceivingProductProgressRow(
                    product = product,
                    completedLabel = stringResource(
                        if (business == ReceivingProductBusiness.Inbound) {
                            R.string.receiving_product_inbounded
                        } else {
                            R.string.receiving_product_received
                        },
                    ),
                    onClick = { viewModel.openProduct(product) },
                )
            }
        }
    }

    state.selectedProduct?.let { product ->
        ReceivingProductBoxesSheet(
            product = product,
            rows = state.boxDetails,
            loading = state.boxDetailsLoading,
            onDismiss = viewModel::closeProduct,
        )
    }
}

@Composable
private fun ReceivingProductDocumentSummary(state: ReceivingProductProgressUiState) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(Color.White),
        border = BorderStroke(1.dp, ProductStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.header.code.ifBlank { "#${state.documentId}" }, color = ProductInk, fontWeight = FontWeight.Bold)
                    if (state.header.warehouse.isNotBlank()) {
                        Text(state.header.warehouse, color = ProductMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                ReceivingProductStatusPill(state.header.status)
            }
            Text(
                stringResource(
                    if (state.business == ReceivingProductBusiness.Inbound) {
                        R.string.receiving_product_document_inbound_progress
                    } else {
                        R.string.receiving_product_document_receiving_progress
                    },
                    state.header.completedBoxCount,
                    state.header.totalBoxCount,
                ),
                color = ProductDeep,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = {
                    if (state.header.totalBoxCount <= 0) 0f else state.header.completedBoxCount.toFloat().div(state.header.totalBoxCount).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                color = ProductBrand,
                trackColor = Color(0xFFE6F0FF),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.receiving_product_pending_count, state.header.pendingBoxCount), color = ProductWarning, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.receiving_product_exception_count, state.header.exceptionBoxCount), color = ProductDanger, style = MaterialTheme.typography.bodySmall)
            }
            if (state.header.localPendingCount > 0) {
                Text(
                    stringResource(R.string.receiving_product_local_pending_count, state.header.localPendingCount),
                    color = ProductWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReceivingProductProgressRow(
    product: ReceivingProductProgress,
    completedLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(Color.White),
        border = BorderStroke(1.dp, ProductStroke),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(6.dp)).background(ProductBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (product.productImage.isNotBlank()) {
                    AsyncImage(
                        model = product.productImage,
                        contentDescription = product.productName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = ProductMuted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        product.productCode.ifBlank { stringResource(R.string.receiving_product_unknown_code) },
                        color = ProductDeep,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ReceivingProductStatusPill(product.workStatus)
                }
                Text(product.productName, color = ProductInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.receiving_product_sku_count, product.skuCount), color = ProductMuted, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.receiving_product_progress_value, completedLabel, product.completedBoxCount, product.totalBoxCount),
                        color = ProductSuccess,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.receiving_product_pending_count, product.pendingBoxCount), color = ProductWarning, style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    progress = {
                        if (product.totalBoxCount <= 0) 0f else product.completedBoxCount.toFloat().div(product.totalBoxCount).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = ProductBrand,
                    trackColor = Color(0xFFE6F0FF),
                )
                if (product.exceptionBoxCount > 0) {
                    Text(stringResource(R.string.receiving_product_exception_count, product.exceptionBoxCount), color = ProductDanger, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceivingProductBoxesSheet(
    product: ReceivingProductProgress,
    rows: List<ReceivingProductBoxDetail>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(product.productCode, color = ProductDeep, fontWeight = FontWeight.Bold)
            Text(product.productName, color = ProductInk, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.receiving_product_box_progress, product.completedBoxCount, product.totalBoxCount),
                color = ProductMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProductBrand)
                    }
                }
            } else if (rows.isEmpty()) {
                item { Text(stringResource(R.string.receiving_product_no_boxes), color = ProductMuted, modifier = Modifier.padding(16.dp)) }
            } else {
                items(rows, key = { it.id }) { row -> ReceivingProductBoxRow(row) }
            }
        }
    }
}

@Composable
private fun ReceivingProductBoxRow(row: ReceivingProductBoxDetail) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.labelCode, color = ProductDeep, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ReceivingProductStatusPill(if (row.localPending) "local_pending" else row.status)
        }
        if (row.skuCode.isNotBlank()) Text(row.skuCode, color = ProductInk, style = MaterialTheme.typography.bodySmall)
        if (row.exceptionReason.isNotBlank()) Text(row.exceptionReason, color = ProductDanger, style = MaterialTheme.typography.bodySmall)
        if (row.locationCode.isNotBlank()) ReceivingProductBoxMeta(stringResource(R.string.receiving_product_location), row.locationCode)
        if (row.operator.isNotBlank()) ReceivingProductBoxMeta(stringResource(R.string.receiving_product_operator), row.operator)
        if (row.operatedAt > 0) ReceivingProductBoxMeta(stringResource(R.string.receiving_product_time), formatReceivingProductTime(row.operatedAt))
    }
    HorizontalDivider(color = ProductStroke)
}

@Composable
private fun ReceivingProductBoxMeta(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = ProductMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(8.dp))
        Text(value, color = ProductInk, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReceivingProductStatusPill(status: String) {
    val (text, color) = when (status) {
        "completed", "complete" -> stringResource(R.string.receiving_product_status_completed) to ProductSuccess
        "closed_with_exception", "cancelled" -> stringResource(R.string.receiving_product_status_closed_exception) to ProductWarning
        "local_pending", "pending_sync", "syncing" -> stringResource(R.string.receiving_product_status_local_pending) to ProductBrand
        "exception", "rejected", "failed" -> stringResource(R.string.receiving_product_status_exception) to ProductDanger
        else -> stringResource(R.string.receiving_product_status_pending) to ProductWarning
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
    }
}

private fun formatReceivingProductTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
