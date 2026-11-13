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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.InsightDemandSettings
import com.google.samples.apps.nowinandroid.fscm.data.InsightFilter
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistory
import com.google.samples.apps.nowinandroid.fscm.data.InsightHistoryScope
import com.google.samples.apps.nowinandroid.fscm.data.InsightPage
import com.google.samples.apps.nowinandroid.fscm.data.InsightPreferences
import com.google.samples.apps.nowinandroid.fscm.data.InsightRow
import com.google.samples.apps.nowinandroid.fscm.data.InsightWarehouse
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseInsightPreferences
import com.google.samples.apps.nowinandroid.fscm.data.PurchaseInsightSource
import com.google.samples.apps.nowinandroid.fscm.data.SessionExpiredException
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.canAccessPurchaseInsights
import com.google.samples.apps.nowinandroid.fscm.data.defaultInsightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.insightHistoryDates
import com.google.samples.apps.nowinandroid.fscm.data.insightSorts
import com.google.samples.apps.nowinandroid.fscm.data.normalizedInsightMetrics
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class PurchaseInsightState(
    val canConfigure: Boolean = false,
    val demandSettings: InsightDemandSettings? = null,
    val demandLoading: Boolean = false,
    val demandError: Boolean = false,
    val demandConflict: Boolean = false,
    val expandedGroup: String = "",
    val groupData: InsightPage? = null,
    val groupLoading: Boolean = false,

    val scope: String = "",
    val allowed: Boolean = false,
    val initialized: Boolean = false,
    val selectAllByDefault: Boolean = true,
    val warehouses: List<InsightWarehouse> = emptyList(),
    val catalogWarnings: List<String> = emptyList(),
    val catalogPending: Boolean = false,
    val filter: InsightFilter = InsightFilter(),
    val metrics: List<String> = defaultInsightMetrics,
    val product: String = "",
    val appliedProduct: String = "",
    val sort: String = "sales30",
    val ascending: Boolean = false,
    val data: InsightPage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val preferenceError: String? = null,
    val detail: InsightRow? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val historyScopes: List<InsightHistoryScope> = emptyList(),
    val historyWarehouse: Long? = null,
    val history: InsightHistory? = null,
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val historyDays: Int = 30,
    val historyWindows: Set<Int> = setOf(30),
    val historySource: String = "",
    val listRevision: Int = 0,
) {
    val selectedWarehouses: List<InsightWarehouse> get() = warehouses.filter { it.id in filter.warehouses.orEmpty() }.sortedBy { it.name }
    val warehouseLabel: String get() = selectedWarehouses.joinToString("、") { it.name }
}

@HiltViewModel
class PurchaseInsightViewModel @Inject constructor(
    private val source: PurchaseInsightSource,
    private val preferences: PurchaseInsightPreferences,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PurchaseInsightState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var search: Job? = null
    private var catalogRequest: Job? = null
    private var detailRequest: Job? = null
    private var historyRequest: Job? = null
    private var catalogLoadedAt = 0L
    private val preferenceMutex = Mutex()
    private var demandGeneration = 0
    private var demandRequest: Job? = null
    private var generation = 0
    private var detailGeneration = 0
    private var historyGeneration = 0
    private var catalogGeneration = 0
    private var active = false

    fun enter(session: SessionState) {
        active = true
        val scope = operatingDailyScope(session)
        val allowed = canAccessPurchaseInsights(session)
        if (scope != state.value.scope || allowed != state.value.allowed) {
            stop()
            active = true
            catalogLoadedAt = 0L
            mutableState.value = PurchaseInsightState(scope = scope, allowed = allowed)
        }
        mutableState.update { it.copy(canConfigure = "purchase-insights-config" in session.permissions) }
        if (!allowed) return
        if (state.value.product.trim() != state.value.appliedProduct) {
            mutableState.update { it.copy(appliedProduct = it.product.trim(), data = null) }
        }
        if (!state.value.initialized || System.nanoTime() - catalogLoadedAt >= 300_000_000_000L) {
            loadCatalog()
        } else if (state.value.data == null && state.value.detail == null) {
            load(false)
        }
    }

    fun stop() {
        active = false
        closeDemandSettings()
        generation++
        detailGeneration++
        historyGeneration++
        catalogGeneration++
        request?.cancel()
        search?.cancel()
        catalogRequest?.cancel()
        detailRequest?.cancel()
        historyRequest?.cancel()
        mutableState.update { it.copy(loading = false, loadingMore = false, detailLoading = false, historyLoading = false) }
    }

    private fun loadCatalog(force: Boolean = false) {
        if (!state.value.allowed || !active) return
        val own = ++catalogGeneration
        catalogRequest?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        catalogRequest = viewModelScope.launch {
            try {
                val current = state.value
                val saved = if (current.initialized) InsightPreferences(current.metrics, current.filter.copy(warehouses = if (current.selectAllByDefault) null else current.filter.warehouses)) else preferences.read(current.scope)
                val catalog = source.catalog(current.scope, force)
                if (own != catalogGeneration) return@launch
                if (catalog.options.isEmpty() && catalog.errors.isNotEmpty() && !current.initialized) {
                    mutableState.update { it.copy(catalogPending = catalog.pending, catalogWarnings = catalog.errors, loading = false, error = "仓库目录暂不可用，请重试") }
                    return@launch
                }
                // While the catalog is still warming, keep the saved selection until it can be validated.
                if (catalog.pending && catalog.options.isEmpty()) {
                    mutableState.update { it.copy(catalogPending = true, catalogWarnings = catalog.errors, loading = false) }
                    return@launch
                }
                catalogLoadedAt = System.nanoTime()
                val ids = catalog.options.map { it.id }
                val selected = saved.filter.warehouses?.filter { it in ids } ?: ids
                val changed = selected != current.filter.warehouses
                mutableState.update {
                    it.copy(
                        initialized = true,
                        selectAllByDefault = saved.filter.warehouses == null,
                        warehouses = catalog.options,
                        catalogWarnings = catalog.errors,
                        catalogPending = catalog.pending,
                        filter = saved.filter.copy(warehouses = selected),
                        metrics = if (current.initialized) it.metrics else normalizedInsightMetrics(saved.metrics),
                        data = if (changed) null else it.data,
                        loading = false,
                    )
                }
                if (changed) closeDetail()
                if (saved.filter.warehouses != null && saved.filter.warehouses != selected) savePreferences()
                if (state.value.detail != null) {
                    openDetail(state.value.detail!!)
                } else {
                    load(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own == catalogGeneration) {
                    mutableState.update {
                        val denied = e is SessionExpiredException || e is FscmApiException && e.statusCode in listOf(401, 403)
                        it.copy(loading = false, initialized = if (denied) false else it.initialized, warehouses = if (denied) emptyList() else it.warehouses, data = if (denied) null else it.data, detail = if (denied) null else it.detail, history = if (denied) null else it.history, error = message(e))
                    }
                }
            }
        }
    }

    fun refresh() {
        search?.cancel()
        generation++
        request?.cancel()
        closeDetail()
        mutableState.update { it.copy(loadingMore = false, appliedProduct = it.product.trim()) }
        loadCatalog(force = true)
    }

    fun product(value: String, immediate: Boolean = false) {
        if (value == state.value.product && !immediate) return
        search?.cancel()
        request?.cancel()
        generation++
        mutableState.update { it.copy(product = value, data = null, error = null, loading = true, loadingMore = false) }
        search = viewModelScope.launch {
            if (!immediate) delay(400)
            mutableState.update { it.copy(appliedProduct = it.product.trim(), listRevision = it.listRevision + 1) }
            load(false)
        }
    }

    fun applyFilter(filter: InsightFilter) {
        closeDemandSettings()
        if (!state.value.initialized) return
        catalogGeneration++
        catalogRequest?.cancel()
        search?.cancel()
        closeDetail()
        val ids = state.value.warehouses.map { it.id }
        mutableState.update {
            it.copy(
                filter = filter.copy(warehouses = filter.warehouses.orEmpty().filter { id -> id in ids }),
                selectAllByDefault = false,
                appliedProduct = it.product.trim(),
                data = null,
                listRevision = it.listRevision + 1,
            )
        }
        savePreferences()
        load(false)
    }
    fun closeDemandSettings() {
        demandGeneration++
        demandRequest?.cancel()
        mutableState.update { it.copy(demandSettings = null, demandLoading = false, demandError = false, demandConflict = false) }
    }

    fun loadDemandSettings(product: Long = 0, year: Int = LocalDate.now().year, copyPrevious: Boolean = false) {
        val current = state.value
        val binding = current.filter.warehouses?.singleOrNull() ?: return
        if (!active || !current.canConfigure) return
        val own = ++demandGeneration
        demandRequest?.cancel()
        mutableState.update { it.copy(demandSettings = if (copyPrevious) current.demandSettings else InsightDemandSettings(bindingId = binding, productId = product, year = year), demandLoading = true, demandError = false, demandConflict = false) }
        demandRequest = viewModelScope.launch {
            try {
                val value = source.demandSettings(binding, product, if (copyPrevious) year - 1 else year)
                if (own == demandGeneration && state.value.scope == current.scope) {
                    mutableState.update { it.copy(demandSettings = if (copyPrevious) current.demandSettings?.copy(factors = value.factors) else value) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (own == demandGeneration) mutableState.update { it.copy(demandError = true) }
            } finally {
                if (own == demandGeneration) mutableState.update { it.copy(demandLoading = false) }
            }
        }
    }

    fun saveDemandSettings(factors: List<Double?>) {
        val current = state.value
        val value = current.demandSettings ?: return
        if (!active || !current.canConfigure || current.demandLoading || factors.size != 12 || factors.any { it != null && (!it.isFinite() || it <= 0 || it > 10) }) return
        if (current.filter.warehouses?.singleOrNull() != value.bindingId) return
        val own = ++demandGeneration
        mutableState.update { it.copy(demandLoading = true, demandError = false, demandConflict = false) }
        demandRequest = viewModelScope.launch {
            try {
                source.saveDemandSettings(value.copy(factors = factors))
                if (own == demandGeneration && state.value.scope == current.scope) {
                    closeDemandSettings()
                    refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own == demandGeneration) mutableState.update { it.copy(demandError = true, demandConflict = e is FscmApiException && e.statusCode == 409) }
            } finally {
                if (own == demandGeneration) mutableState.update { it.copy(demandLoading = false) }
            }
        }
    }

    fun metrics(keys: List<String>) {
        mutableState.update { it.copy(metrics = normalizedInsightMetrics(keys)) }
        savePreferences()
    }
    private fun savePreferences() {
        val current = state.value
        viewModelScope.launch {
            preferenceMutex.withLock {
                try {
                    preferences.save(current.scope, InsightPreferences(current.metrics, current.filter.copy(warehouses = if (current.selectAllByDefault) null else current.filter.warehouses)))
                    if (state.value.scope == current.scope) mutableState.update { it.copy(preferenceError = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (state.value.scope == current.scope) mutableState.update { it.copy(preferenceError = "偏好保存失败，本次选择仍有效") }
                }
            }
        }
    }
    fun ranking(dimension: String = state.value.filter.dimension, top: Int = state.value.filter.topLimit) {
        mutableState.update { it.copy(filter = it.filter.copy(dimension = dimension, topLimit = top), expandedGroup = "", groupData = null) }
        load(false)
    }

    fun expandGroup(row: InsightRow, more: Boolean = false) {
        val current = state.value
        if (!more && current.expandedGroup == row.groupKey) {
            mutableState.update { it.copy(expandedGroup = "", groupData = null) }
            return
        }
        val snapshot = current.data ?: return
        val group = row.groupKey
        val page = if (more) (current.groupData?.page ?: 0) + 1 else 1
        mutableState.update { it.copy(expandedGroup = group, groupLoading = true, groupData = if (more) it.groupData else null) }
        viewModelScope.launch {
            try {
                val result = source.query(current.filter.query(current.appliedProduct, page, current.sort, false, snapshot.snapshotId, snapshot.analysisSnapshotId, group))
                if (state.value.expandedGroup == group && state.value.data?.analysisSnapshotId == snapshot.analysisSnapshotId) {
                    mutableState.update { it.copy(groupLoading = false, groupData = result.copy(items = if (more) current.groupData.orEmptyItems() + result.items else result.items)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (state.value.expandedGroup == group) mutableState.update { it.copy(groupLoading = false, error = "SKU details unavailable") }
            }
        }
    }

    private fun InsightPage?.orEmptyItems(): List<InsightRow> = this?.items.orEmpty()

    fun sort(key: String, ascending: Boolean) {
        if (key !in insightSorts) return
        search?.cancel()
        mutableState.update { it.copy(sort = key, ascending = ascending, appliedProduct = it.product.trim(), data = null, listRevision = it.listRevision + 1) }
        load(false)
    }
    fun more() = load(true)
    fun retry() {
        if (!state.value.initialized) loadCatalog(force = true) else load(false)
    }

    private fun load(append: Boolean) {
        val current = state.value
        if (!active || !current.allowed || !current.initialized) return
        if (append && (current.loading || current.loadingMore || current.data == null || current.data.items.size >= current.data.total)) return
        val own = ++generation
        request?.cancel()
        if (current.filter.warehouses.isNullOrEmpty()) {
            mutableState.update { it.copy(data = null, loading = false, loadingMore = false, error = null) }
            return
        }
        val page = if (append) (current.data?.page ?: 0) + 1 else 1
        mutableState.update { it.copy(loading = !append, loadingMore = append, error = null) }
        request = viewModelScope.launch {
            try {
                val result = source.query(current.filter.query(current.appliedProduct, page, current.sort, current.ascending, if (append) current.data?.snapshotId else null, if (append) current.data?.analysisSnapshotId else null))
                if (own != generation) return@launch
                if (append && result.snapshotId != current.data?.snapshotId) {
                    mutableState.update { it.copy(data = null, loadingMore = false, error = "数据版本已更新，正在重新加载") }
                    load(false)
                    return@launch
                }
                check(!append || result.items.isNotEmpty() || current.data!!.items.size >= result.total) { "分页数据不完整，请重试" }
                val items = if (append) (current.data!!.items + result.items).distinctBy { it.key } else result.items
                check(!append || (result.page == page && (items.size > current.data!!.items.size || items.size >= result.total))) { "分页数据未前进，请重新查询" }
                mutableState.update { it.copy(data = result.copy(items = items), loading = false, loadingMore = false, expandedGroup = "", groupData = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own != generation) return@launch
                if (e is FscmApiException && e.statusCode == 409 && append) {
                    mutableState.update { it.copy(data = null, loadingMore = false) }
                    load(false)
                } else {
                    mutableState.update {
                        val denied = e is SessionExpiredException || e is FscmApiException && e.statusCode in listOf(401, 403)
                        it.copy(loading = false, loadingMore = false, initialized = if (denied) false else it.initialized, warehouses = if (denied) emptyList() else it.warehouses, data = if (denied) null else it.data, error = message(e))
                    }
                }
            }
        }
    }

    fun closeDetail() {
        detailGeneration++
        historyGeneration++
        detailRequest?.cancel()
        historyRequest?.cancel()
        mutableState.update { it.copy(detail = null, history = null, detailError = null, historyError = null, detailLoading = false, historyLoading = false) }
    }
    fun openDetail(row: InsightRow) {
        val current = state.value
        val snapshot = current.data?.snapshotId?.takeIf { it.isNotBlank() } ?: return
        val own = ++detailGeneration
        request?.cancel()
        generation++
        detailRequest?.cancel()
        historyRequest?.cancel()
        historyGeneration++
        mutableState.update {
            it.copy(
                detail = row, detailLoading = true, detailError = null, history = null, historyScopes = emptyList(),
                historyWarehouse = null, historyError = null, historyLoading = false, historyDays = 30, historySource = "", loading = false, loadingMore = false,
            )
        }
        detailRequest = viewModelScope.launch {
            try {
                val detail = source.detail(current.filter.warehouses.orEmpty(), snapshot, row.key)
                if (own != detailGeneration) return@launch
                mutableState.update { it.copy(detail = detail, detailLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own != detailGeneration) return@launch
                if (e is FscmApiException && e.statusCode == 409) {
                    closeDetail()
                    mutableState.update { it.copy(data = null, error = "快照已过期，正在重新加载") }
                    load(false)
                    return@launch
                }
                mutableState.update { it.copy(detailLoading = false, detailError = message(e)) }
                if (e is FscmApiException && e.statusCode in listOf(401, 403)) {
                    closeDetail()
                    mutableState.update { it.copy(data = null, error = message(e)) }
                    return@launch
                }
            }
            try {
                val scopes = source.scopes().filter { it.bindingId in current.filter.warehouses.orEmpty() }
                if (own != detailGeneration) return@launch
                mutableState.update { it.copy(historyScopes = scopes) }
                loadHistory(autoSelect = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own == detailGeneration) mutableState.update { it.copy(historyError = message(e)) }
            }
        }
    }
    fun historyWarehouse(id: Long) {
        mutableState.update { it.copy(historyWarehouse = id) }
        loadHistory()
    }
    fun historyDays(days: Int) {
        if (days !in listOf(7, 30, 90)) return
        mutableState.update { it.copy(historyDays = days) }
        loadHistory()
    }
    fun historyWindow(window: Int) {
        if (window !in listOf(7, 15, 30)) return
        mutableState.update {
            val next = if (window in it.historyWindows) it.historyWindows - window else it.historyWindows + window
            it.copy(historyWindows = next.ifEmpty { it.historyWindows })
        }
    }
    fun historySource(value: String) {
        mutableState.update { it.copy(historySource = value) }
    }
    fun retryHistory() {
        val row = state.value.detail ?: return
        if (state.value.historyScopes.isEmpty()) openDetail(row) else loadHistory()
    }

    private fun loadHistory(autoSelect: Boolean = false) {
        val current = state.value
        val row = current.detail ?: return
        val own = ++historyGeneration
        historyRequest?.cancel()
        mutableState.update { it.copy(historyLoading = true, history = null, historyError = null, historySource = "") }
        historyRequest = viewModelScope.launch {
            try {
                val candidates = if (autoSelect) current.selectedWarehouses else current.selectedWarehouses.filter { it.id == current.historyWarehouse }
                var selected: Long? = candidates.firstOrNull()?.id
                var history: InsightHistory? = null
                for (warehouse in candidates) {
                    val scope = current.historyScopes.firstOrNull { it.bindingId == warehouse.id } ?: continue
                    val sku = row.localSku.takeIf { row.localSkuId > 0 && it.isNotBlank() }
                        ?: row.sku.takeIf { current.filter.warehouses?.size == 1 }
                        ?: throw IllegalStateException("此 SKU 尚未匹配本地身份，请先选择单仓查询，避免混入同名 SKU")
                    // Merged snapshots have no timezone. Fetch a padded UTC range, then trim to
                    // the selected warehouse's timezone from historical coverage, never device time.
                    val padded = insightHistoryDates(LocalDate.now(ZoneId.of("UTC")).plusDays(1), current.historyDays + 2)
                    val raw = source.history(scope.id, sku, padded)
                    val zone = raw.coverage.filter { it.timezone.isNotBlank() }.maxByOrNull { it.day }?.timezone
                        ?: current.data?.sourceTimezone?.takeIf { it.isNotBlank() }
                    check(zone != null || raw.records.isEmpty()) { "历史缺少来源业务时区，暂时无法绘制曲线" }
                    val dates = if (zone != null) insightHistoryDates(LocalDate.now(ZoneId.of(zone)), current.historyDays) else emptyList()
                    val result = raw.copy(records = raw.records.filter { it.day.take(10) in dates }, coverage = raw.coverage.filter { it.day.take(10) in dates }, dates = dates)
                    selected = warehouse.id
                    history = result
                    if (!autoSelect || result.records.isNotEmpty()) break
                }
                if (own != historyGeneration) return@launch
                val sources = history?.records.orEmpty().map { it.sourceSku }.distinct()
                mutableState.update {
                    it.copy(
                        historyWarehouse = selected,
                        history = history,
                        historyLoading = false,
                        historySource = sources.singleOrNull().orEmpty(),
                        historyError = if (history == null) "所选仓库暂无历史数据，首次完整同步后开始留存" else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own == historyGeneration) mutableState.update { it.copy(historyLoading = false, historyError = message(e)) }
            }
        }
    }

    private fun message(e: Exception): String = when (e) {
        is SessionExpiredException -> "登录已失效，请重新登录"
        is FscmApiException -> if (e.statusCode == 403) "无权访问所选仓库或采购洞察" else e.message ?: "请求失败，请重试"
        else -> e.message ?: "加载失败，请重试"
    }
}
