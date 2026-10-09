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
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyFilter
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyOverview
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPage
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyPreferences
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailyShop
import com.google.samples.apps.nowinandroid.fscm.data.OperatingDailySource
import com.google.samples.apps.nowinandroid.fscm.data.SessionExpiredException
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.operatingDailyScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OperatingDailyUiState(
    val scope: String = "",
    val allowed: Boolean = false,
    val initialized: Boolean = false,
    val filter: OperatingDailyFilter = OperatingDailyFilter(),
    val range: Pair<String, String>? = null,
    val ranking: Boolean = false,
    val rankMetric: String = "totalOrders",
    val data: OperatingDailyOverview? = null,
    val directory: List<OperatingDailyShop> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val saving: Boolean = false,
    val page: Int = 1,
    val error: Int? = null,
    val pageError: Boolean = false,
    val filterError: Int? = null,
    val selectionInvalid: Boolean = false,
    val selectedShop: OperatingDailyShop? = null,
    val detail: OperatingDailyPage? = null,
    val detailLoading: Boolean = false,
    val detailLoadingMore: Boolean = false,
    val detailPage: Int = 1,
    val detailError: Int? = null,
) {
    val sort: String get() = if (ranking) rankMetric else "profit"
}

@HiltViewModel
class OperatingDailyViewModel @Inject constructor(
    private val source: OperatingDailySource,
    private val preferences: OperatingDailyPreferences,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OperatingDailyUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var detailRequest: Job? = null
    private var preferenceRequest: Job? = null
    private var generation = 0
    private var detailGeneration = 0

    fun enter(session: SessionState) {
        val scope = operatingDailyScope(session)
        val allowed = "mobile-app:profit" in session.permissions && "operating-profit-daily" in session.permissions
        if (scope != state.value.scope || allowed != state.value.allowed) {
            generation++
            detailGeneration++
            request?.cancel()
            detailRequest?.cancel()
            preferenceRequest?.cancel()
            mutableState.value = OperatingDailyUiState(scope = scope, allowed = allowed)
        }
        if (!allowed) {
            mutableState.update { it.copy(error = R.string.od_no_permission) }
            return
        }
        if (state.value.initialized) {
            refresh()
            if (state.value.selectedShop != null) loadDetail()
            return
        }
        preferenceRequest?.cancel()
        preferenceRequest = viewModelScope.launch {
            try {
                val saved = preferences.read(scope)
                if (scope != state.value.scope) return@launch
                mutableState.update { it.copy(filter = saved, initialized = true) }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (scope == state.value.scope) mutableState.update { it.copy(error = R.string.od_preferences_failed) }
            }
        }
    }

    fun apply(filter: OperatingDailyFilter, onApplied: () -> Unit) {
        val current = state.value
        val error = when {
            filter.range() == null -> R.string.od_invalid_date
            !filter.selectionValid(current.directory) -> R.string.od_invalid_selection
            else -> null
        }
        if (error != null) {
            mutableState.update { it.copy(filterError = error) }
            return
        }
        if (current.saving) return
        preferenceRequest = viewModelScope.launch {
            mutableState.update { it.copy(saving = true, filterError = null) }
            try {
                preferences.save(current.scope, filter)
                if (state.value.scope != current.scope) return@launch
                mutableState.update { it.copy(filter = filter, initialized = true, saving = false, data = null, selectionInvalid = false, range = null) }
                onApplied()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (state.value.scope == current.scope) mutableState.update { it.copy(saving = false, filterError = R.string.od_preferences_failed) }
            }
        }
    }

    fun clearFilterError() {
        mutableState.update { it.copy(filterError = null) }
    }

    fun selectTab(ranking: Boolean) {
        if (ranking == state.value.ranking) return
        mutableState.update { it.copy(ranking = ranking, data = it.data?.copy(items = emptyList())) }
        refresh()
    }

    fun selectMetric(metric: String) {
        if (metric !in listOf("totalOrders", "saleAmount", "profit") || metric == state.value.rankMetric) return
        mutableState.update { it.copy(rankMetric = metric, data = it.data?.copy(items = emptyList())) }
        refresh()
    }

    fun refresh() {
        load(false)
    }
    fun loadMore() {
        load(true)
    }

    private fun load(append: Boolean) {
        val current = state.value
        if (!current.allowed || !current.initialized || current.saving) return
        if (append && (current.loading || current.loadingMore || current.selectionInvalid || current.data == null || current.data.items.size >= current.data.total)) return
        val range = current.filter.range()
        if (range == null || (!current.filter.allShops && current.filter.shopIds.isEmpty()) || (!current.filter.allPlatforms && current.filter.platforms.isEmpty())) {
            mutableState.update { it.copy(data = null, error = if (range == null) R.string.od_invalid_date else R.string.od_invalid_selection) }
            return
        }
        val version = ++generation
        request?.cancel()
        if (current.range != range) {
            detailGeneration++
            detailRequest?.cancel()
        }
        val page = if (append) current.page + 1 else 1
        mutableState.update {
            it.copy(
                range = range,
                data = if (it.range != range) null else it.data,
                detail = if (it.range != range) null else it.detail,
                loading = !append,
                loadingMore = append,
                error = null,
                pageError = false,
            )
        }
        request = viewModelScope.launch {
            try {
                val result = source.overview(current.filter, range, page, current.sort)
                if (version != generation) return@launch
                val invalid = !current.filter.selectionValid(result.shops)
                mutableState.update {
                    it.copy(
                        data = if (invalid) null else result.copy(items = if (append) (it.data?.items.orEmpty() + result.items).distinctBy { shop -> shop.id } else result.items),
                        directory = result.shops,
                        selectionInvalid = invalid,
                        error = if (invalid) R.string.od_selection_expired else null,
                        page = page,
                        loading = false,
                        loadingMore = false,
                    )
                }
                val detailShop = state.value.selectedShop
                if (detailShop != null && result.shops.none { it.id == detailShop.id }) {
                    detailGeneration++
                    detailRequest?.cancel()
                    mutableState.update { it.copy(detail = null, detailLoading = false, detailLoadingMore = false, detailError = R.string.od_no_permission) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version != generation) return@launch
                if (error.isDailyAccessError()) {
                    clearAccess()
                } else {
                    mutableState.update { it.copy(loading = false, loadingMore = false, error = if (append) null else R.string.od_load_failed, pageError = append) }
                }
            }
        }
    }

    fun openShop(shop: OperatingDailyShop) {
        mutableState.update { it.copy(selectedShop = shop, detail = null, detailPage = 1, detailError = null) }
        loadDetail()
    }

    fun closeShop() {
        detailGeneration++
        detailRequest?.cancel()
        mutableState.update { it.copy(selectedShop = null, detail = null, detailLoading = false, detailLoadingMore = false) }
    }

    fun loadDetail(append: Boolean = false) {
        val current = state.value
        val shop = current.selectedShop ?: return
        val range = current.range ?: return
        if (!current.allowed) return
        if (append && (current.detailLoading || current.detailLoadingMore || current.detail == null || current.detail.items.size >= current.detail.total)) return
        val version = ++detailGeneration
        detailRequest?.cancel()
        val page = if (append) current.detailPage + 1 else 1
        mutableState.update { it.copy(detailLoading = !append, detailLoadingMore = append, detailError = null) }
        detailRequest = viewModelScope.launch {
            try {
                val result = source.days(shop.id, range, page)
                if (version != detailGeneration) return@launch
                mutableState.update {
                    it.copy(
                        detail = result.copy(items = if (append) (it.detail?.items.orEmpty() + result.items).distinctBy { day -> day.reportDate } else result.items),
                        detailPage = page,
                        detailLoading = false,
                        detailLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version != detailGeneration) return@launch
                if (error.isDailyAccessError()) {
                    mutableState.update { it.copy(detail = null, detailLoading = false, detailLoadingMore = false, detailError = R.string.od_no_permission) }
                    refresh()
                } else {
                    mutableState.update { it.copy(detailLoading = false, detailLoadingMore = false, detailError = R.string.od_load_failed) }
                }
            }
        }
    }

    private fun clearAccess() {
        detailGeneration++
        detailRequest?.cancel()
        mutableState.update {
            it.copy(data = null, directory = emptyList(), detail = null, loading = false, loadingMore = false, detailLoading = false, detailLoadingMore = false, error = R.string.od_no_permission, detailError = R.string.od_no_permission)
        }
    }
}

private fun Exception.isDailyAccessError(): Boolean = this is SessionExpiredException || this is FscmApiException && statusCode in listOf(401, 403)
