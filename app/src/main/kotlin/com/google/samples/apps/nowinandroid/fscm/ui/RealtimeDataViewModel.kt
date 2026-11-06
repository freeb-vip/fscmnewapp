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
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeDataRepository
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeDataSource
import com.google.samples.apps.nowinandroid.fscm.data.RealtimeWorkSummary
import com.google.samples.apps.nowinandroid.fscm.data.SalesAccount
import com.google.samples.apps.nowinandroid.fscm.data.SalesOverview
import com.google.samples.apps.nowinandroid.fscm.data.SessionExpiredException
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.canAccessRealtime
import com.google.samples.apps.nowinandroid.fscm.data.realtimeScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

internal const val REALTIME_INTERVAL = 180_000L

internal data class RealtimeUiState(
    val scope: String = "",
    val allowed: Boolean = false,
    val accounts: List<SalesAccount> = emptyList(),
    val selectedId: String = "",
    val accountsLoaded: Boolean = false,
    val sales: SalesOverview? = null,
    val work: RealtimeWorkSummary? = null,
    val salesLoading: Boolean = false,
    val workLoading: Boolean = false,
    val salesError: UiText? = null,
    val workError: UiText? = null,
) {
    val loading: Boolean get() = salesLoading || workLoading
    val showWork: Boolean get() = work?.today?.let { (it.count ?: 0) > 0 || (it.efficiency ?: 0.0) > 0 } == true
}

@HiltViewModel
internal class RealtimeDataViewModel @Inject constructor(repository: RealtimeDataRepository) : ViewModel() {
    private val controller = RealtimeDataController(repository, viewModelScope)
    val state = controller.state
    fun bind(session: SessionState) = controller.bind(session)
    suspend fun follow() = controller.follow()
    fun refresh() = controller.refresh()
    fun select(id: String) = controller.select(id)
}

/** One controller is owned by the main shell, so home/detail cannot double-poll. */
internal class RealtimeDataController(
    private val repository: RealtimeDataSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(RealtimeUiState())
    val state = mutable.asStateFlow()
    private var session = SessionState()
    private var active = false
    private var salesJob: Job? = null
    private var workJob: Job? = null
    private var salesVersion = 0
    private var workVersion = 0
    private var nextSales = 0L
    private var nextWork = 0L
    private var selectionRead = false

    fun bind(value: SessionState) {
        val allowed = value.isLoggedIn && canAccessRealtime(value.permissions, value.sessionMode)
        if (value.realtimeScope() != session.realtimeScope() || allowed != state.value.allowed) {
            cancelRequests()
            selectionRead = false
            nextSales = 0
            nextWork = 0
            mutable.value = RealtimeUiState(scope = value.realtimeScope(), allowed = allowed)
        }
        session = value
    }

    suspend fun follow() {
        if (!state.value.allowed) return
        active = true
        try {
            refresh()
            while (true) {
                delay(1_000)
                expireDates()
                if (now() >= nextSales) loadSales()
                if (now() >= nextWork) loadWork()
            }
        } finally {
            active = false
            cancelRequests()
        }
    }

    fun refresh() {
        if (!active || !state.value.allowed) return
        expireDates()
        loadSales()
        loadWork()
    }

    fun select(id: String) {
        if (!active || !state.value.allowed || id == state.value.selectedId || state.value.accounts.none { it.id == id }) return
        salesVersion++
        salesJob?.cancel()
        salesJob = null
        selectionRead = true
        mutable.update { it.copy(selectedId = id, sales = null, salesLoading = false, salesError = null) }
        loadSales(saveSelection = true)
    }

    private fun cancelRequests() {
        salesVersion++
        workVersion++
        salesJob?.cancel()
        workJob?.cancel()
        salesJob = null
        workJob = null
        mutable.update { it.copy(salesLoading = false, workLoading = false) }
    }

    private fun expireDates() {
        val data = state.value
        if (data.sales != null && data.sales.date != realtimeDate(now(), "Asia/Shanghai")) {
            salesVersion++
            salesJob?.cancel()
            salesJob = null
            mutable.update { it.copy(sales = null, salesLoading = false, salesError = null) }
            nextSales = 0
        }
        if (data.work != null && data.work.today.date != realtimeDate(now(), data.work.today.timezone)) {
            workVersion++
            workJob?.cancel()
            workJob = null
            mutable.update { it.copy(work = null, workLoading = false, workError = null) }
            nextWork = 0
        }
    }

    private fun loadSales(saveSelection: Boolean = false) {
        if (!active || !state.value.allowed || salesJob?.isActive == true) return
        val version = ++salesVersion
        val requestSession = session
        mutable.update { it.copy(salesLoading = true) }
        nextSales = now() + REALTIME_INTERVAL
        salesJob = scope.launch {
            try {
                if (!selectionRead) {
                    val saved = repository.selectedAccount(requestSession).orEmpty()
                    if (version != salesVersion) return@launch
                    selectionRead = true
                    mutable.update { it.copy(selectedId = saved) }
                }
                if (saveSelection) repository.selectAccount(requestSession, state.value.selectedId)
                val accounts = repository.accounts()
                if (version != salesVersion) return@launch
                val selected = state.value.selectedId.takeIf { id -> accounts.any { it.id == id } }
                    ?: accounts.firstOrNull()?.id.orEmpty()
                mutable.update {
                    it.copy(
                        accounts = accounts,
                        accountsLoaded = true,
                        selectedId = selected,
                        sales = it.sales.takeIf { old -> old?.connectionId == selected },
                        salesError = null,
                    )
                }
                if (selected.isBlank()) return@launch
                val result = repository.overview(selected)
                if (version != salesVersion) return@launch
                if (result.connectionId != selected || result.date != realtimeDate(now(), "Asia/Shanghai") ||
                    result.timezone.isNotBlank() && result.timezone != "Asia/Shanghai"
                ) {
                    mutable.update { it.copy(sales = null, salesError = UiText.Resource(R.string.rt_waiting)) }
                    nextSales = now() + 5_000
                    return@launch
                }
                mutable.update { it.copy(sales = result, salesError = null) }
                if (result.modules.filterKeys { it != "catalog" }.values.any { it.status == "pending" && it.fetchedAt == null }) {
                    nextSales = now() + 5_000
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version != salesVersion) return@launch
                val status = (error as? FscmApiException)?.statusCode
                val clear = status in listOf(401, 403, 422) || error is SessionExpiredException
                mutable.update {
                    it.copy(
                        sales = if (clear) null else it.sales,
                        accounts = if (status == 401 || status == 403) emptyList() else it.accounts,
                        salesError = UiText.Resource(
                            if (status == 403 || status == 401 || error is SessionExpiredException) {
                                R.string.rt_access_lost
                            } else if (it.sales != null && !clear) {
                                R.string.rt_sales_delayed
                            } else {
                                R.string.rt_sales_unavailable
                            },
                        ),
                    )
                }
            } finally {
                if (version == salesVersion) mutable.update { it.copy(salesLoading = false) }
            }
        }
    }

    private fun loadWork() {
        if (!active || !state.value.allowed || workJob?.isActive == true) return
        val version = ++workVersion
        mutable.update { it.copy(workLoading = true) }
        nextWork = now() + REALTIME_INTERVAL
        workJob = scope.launch {
            try {
                val result = repository.workSummary()
                if (version != workVersion) return@launch
                if (result.today.date != realtimeDate(now(), result.today.timezone)) {
                    mutable.update { it.copy(work = null, workError = UiText.Resource(R.string.rt_waiting)) }
                    nextWork = now() + 5_000
                    return@launch
                }
                val retained = retainCompleteWork(state.value.work, result)
                mutable.update {
                    it.copy(work = retained, workError = if (retained !== result) UiText.Resource(R.string.rt_work_incomplete) else null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version != workVersion) return@launch
                val status = (error as? FscmApiException)?.statusCode
                val clear = status == 401 || status == 403 || error is SessionExpiredException
                mutable.update {
                    it.copy(
                        work = if (clear) null else it.work,
                        workError = UiText.Resource(
                            if (clear) {
                                R.string.rt_access_lost
                            } else if (it.work != null) {
                                R.string.rt_work_delayed
                            } else {
                                R.string.rt_work_unavailable
                            },
                        ),
                    )
                }
            } finally {
                if (version == workVersion) mutable.update { it.copy(workLoading = false) }
            }
        }
    }
}

internal fun realtimeDate(millis: Long, timezone: String): String? = runCatching {
    Instant.ofEpochMilli(millis).atZone(ZoneId.of(timezone)).toLocalDate().toString()
}.getOrNull()

internal fun retainCompleteWork(previous: RealtimeWorkSummary?, fresh: RealtimeWorkSummary): RealtimeWorkSummary {
    val old = previous?.today ?: return fresh
    if (old.date != fresh.today.date || old.timezone != fresh.today.timezone ||
        fresh.today.scope.isBlank() || old.scope != fresh.today.scope || old.basis != fresh.today.basis
    ) {
        return fresh
    }
    var result = fresh
    if (old.count != null && old.efficiency != null && (fresh.today.count == null || fresh.today.efficiency == null)) {
        result = fresh.copy(today = old.copy(status = "delayed"))
    }
    if (previous.comparison?.available == true && fresh.comparison?.available != true &&
        previous.comparison.cutoff == fresh.comparison?.cutoff
    ) {
        result = result.copy(comparison = previous.comparison, today = result.today.copy(status = "delayed"))
    }
    return result
}

internal fun SalesOverview.yesterdayTotal() = runCatching {
    val date = LocalDate.parse(date).minusDays(1).toString()
    week.firstOrNull { it.date == date }
}.getOrNull()

internal fun realtimeNumber(raw: String?, digits: Int = 2): String {
    val value = raw?.toBigDecimalOrNull() ?: return "—"
    return DecimalFormat(if (digits == 0) "#,##0" else "#,##0.${"0".repeat(digits)}", DecimalFormatSymbols(Locale.US))
        .apply { roundingMode = RoundingMode.HALF_UP }.format(value)
}

internal data class RealtimeChange(val text: String? = null, val direction: Int = 0, val isNew: Boolean = false, val flat: Boolean = false)

internal fun realtimeChange(current: String?, previous: String?): RealtimeChange {
    val a = current?.toBigDecimalOrNull() ?: return RealtimeChange()
    val b = previous?.toBigDecimalOrNull() ?: return RealtimeChange()
    val delta = a - b
    if (delta.signum() == 0) return RealtimeChange(flat = true)
    if (b.signum() == 0) return RealtimeChange(direction = delta.signum(), isNew = true)
    val percent = delta.multiply(BigDecimal(100)).divide(b.abs(), 2, RoundingMode.DOWN).toPlainString()
    return RealtimeChange(text = "${if (delta.signum() > 0) "↑ +" else "↓ "}$percent%", direction = delta.signum())
}

internal fun realtimeRatio(raw: String?): RealtimeChange {
    val ratio = raw?.toBigDecimalOrNull() ?: return RealtimeChange()
    return RealtimeChange(
        text = "${if (ratio.signum() > 0) {
            "↑ +"
        } else if (ratio.signum() < 0) {
            "↓ "
        } else {
            ""
        }}${ratio.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)}%",
        direction = ratio.signum(),
    )
}
