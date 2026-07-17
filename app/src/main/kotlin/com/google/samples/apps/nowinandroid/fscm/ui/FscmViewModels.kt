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

import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.core.model.data.DisplayMode
import com.google.samples.apps.nowinandroid.core.model.data.UserData
import com.google.samples.apps.nowinandroid.fscm.data.AuthRepository
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelQrFormatException
import com.google.samples.apps.nowinandroid.fscm.data.BusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.CartonRepository
import com.google.samples.apps.nowinandroid.fscm.data.DatabaseRecoveryNoticeStore
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeOption
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeStatus
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintTemplateUnavailableException
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTerminalRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchRepository
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchResponse
import com.google.samples.apps.nowinandroid.fscm.data.LegacyOfflineDataAdopter
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsage
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsageStore
import com.google.samples.apps.nowinandroid.fscm.data.MobileRepository
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncRepository
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.data.Product
import com.google.samples.apps.nowinandroid.fscm.data.ProductRepository
import com.google.samples.apps.nowinandroid.fscm.data.ProfitOverview
import com.google.samples.apps.nowinandroid.fscm.data.ProfitRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SearchDestination
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.Sku
import com.google.samples.apps.nowinandroid.fscm.data.parseBoxLabelQr
import com.google.samples.apps.nowinandroid.fscm.data.toBusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.toLocalBoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.todayRange
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.toScannerConfig
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.withScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class FscmRootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val databaseRecoveryNoticeStore: DatabaseRecoveryNoticeStore,
    private val userDataRepository: UserDataRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val legacyOfflineDataAdopter: LegacyOfflineDataAdopter,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    private val scannerManager: ScannerManager,
    offlineSyncRepository: OfflineSyncRepository,
) : ViewModel() {
    val session: StateFlow<SessionState> = authRepository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionState(),
    )
    private val _bootstrapping = MutableStateFlow(true)
    val bootstrapping = _bootstrapping.asStateFlow()
    private val _message = MutableStateFlow<UiText?>(null)
    val message = _message.asStateFlow()
    val pendingSyncCount: StateFlow<Int> = offlineSyncRepository.pendingCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    private val _globalScanResult = MutableStateFlow<String?>(null)
    val globalScanResult = _globalScanResult.asStateFlow()

    val userData: StateFlow<UserData> = userDataRepository.userData.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserData(emptySet(), emptySet(), emptySet(), com.google.samples.apps.nowinandroid.core.model.data.ThemeBrand.DEFAULT, com.google.samples.apps.nowinandroid.core.model.data.DarkThemeConfig.FOLLOW_SYSTEM, false, false),
    )

    init {
        viewModelScope.launch {
            if (databaseRecoveryNoticeStore.consumeLocalDataReset()) {
                _message.value = UiText.Resource(R.string.local_offline_data_reset)
            }
            authRepository.bootstrap()
            val restoredSession = authRepository.session.first()
            runCatching { legacyOfflineDataAdopter.adoptFor(restoredSession) }
            if (restoredSession.token.isNotBlank()) offlineSyncScheduler.enqueue()
            if (restoredSession.isOffline) {
                runCatching { edgePrintRepository.discoverLocalNodes(includeSubnetScan = false) }
            }
            runCatching { edgeTerminalRepository.registerCurrentTerminal() }
            _bootstrapping.value = false
        }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner) {
        scannerManager.pushOnScanListener { barcode ->
            _globalScanResult.value = barcode
        }
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }

    fun validateActiveSession() {
        viewModelScope.launch {
            if (!authRepository.validateActiveOfflineSession()) {
                _message.value = UiText.Resource(R.string.session_expired)
            }
        }
    }

    fun consumeGlobalScan() {
        _globalScanResult.value = null
    }

    fun consumeBackEventAfterScan(): Boolean = scannerManager.consumeBackEventAfterScan()

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _message.value = null
            runCatching { authRepository.loginOrOffline(username, password) }
                .onSuccess {
                    runCatching { legacyOfflineDataAdopter.adoptFor(authRepository.session.first()) }
                    offlineSyncScheduler.enqueue()
                    launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                }
                .onFailure { _message.value = it.toUiText(R.string.login_failed) }
        }
    }

    fun switchNamespace(namespaceId: Long) {
        viewModelScope.launch {
            runCatching { authRepository.switchNamespace(namespaceId) }
                .onSuccess {
                    runCatching { legacyOfflineDataAdopter.adoptFor(authRepository.session.first()) }
                    offlineSyncScheduler.enqueue()
                    launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                }
                .onFailure { _message.value = it.toUiText(R.string.switch_namespace_failed) }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun updateDisplayMode(displayMode: DisplayMode) {
        viewModelScope.launch {
            userDataRepository.setDisplayMode(displayMode)
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class LoadState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: UiText? = null,
)

@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val mobileRepository: MobileRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
    private val globalSearchRepository: GlobalSearchRepository,
    private val mobileAppUsageStore: MobileAppUsageStore,
    scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<MobileWorkbenchOverview>())
    val state = _state.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow(LoadState<GlobalSearchResponse>())
    val searchState = _searchState.asStateFlow()
    private val searchNavigationChannel = Channel<SearchDestination>(Channel.BUFFERED)
    val searchNavigation = searchNavigationChannel.receiveAsFlow()
    private var workbenchSearchJob: Job? = null
    private var mobileAppUsageJob: Job? = null
    private var loadedSessionKey: String? = null
    private val _mobileAppUsage = MutableStateFlow(MobileAppUsage())
    val mobileAppUsage = _mobileAppUsage.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )

    fun load(session: SessionState, force: Boolean = false) {
        val sessionKey = buildString {
            append(session.user?.id ?: 0)
            append(':')
            append(session.currentNamespace?.id ?: 0)
            append(':')
            append(session.sessionMode.name)
        }
        if (!force && loadedSessionKey == sessionKey) return
        loadedSessionKey = sessionKey
        viewModelScope.launch {
            mobileAppUsageStore.initializeDefaultCommonApps(session)
        }
        mobileAppUsageJob?.cancel()
        mobileAppUsageJob = viewModelScope.launch {
            mobileAppUsageStore.usage(session).collect { usage -> _mobileAppUsage.value = usage }
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { mobileRepository.workbenchOverview() }
                .onSuccess { overview ->
                    _state.value = LoadState(data = overview)
                    viewModelScope.launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                }
                .onFailure { error -> _state.value = LoadState(error = error.toUiText(R.string.load_home_failed)) }
        }
    }

    fun refresh(session: SessionState) = load(session, force = true)

    fun recordMobileAppUsage(session: SessionState, appKey: String) {
        viewModelScope.launch { mobileAppUsageStore.recordUsage(session, appKey) }
    }

    fun togglePinnedMobileApp(session: SessionState, appKey: String) {
        viewModelScope.launch { mobileAppUsageStore.togglePinned(session, appKey) }
    }

    fun setMobileAppHidden(session: SessionState, appKey: String, hidden: Boolean) {
        viewModelScope.launch { mobileAppUsageStore.setHidden(session, appKey, hidden) }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun search(query: String) {
        _searchQuery.value = query
        workbenchSearchJob?.cancel()
        if (query.isBlank()) {
            _searchState.value = LoadState()
            return
        }
        workbenchSearchJob = viewModelScope.launch {
            _searchState.update { it.copy(loading = true, error = null) }
            runCatching { globalSearchRepository.searchApplications(query, _state.value.data) }
                .onSuccess { response ->
                    val exactHit = response.hits.singleOrNull()?.takeIf { it.matchKind == "exact" }
                    if (exactHit != null) {
                        _searchState.value = LoadState()
                        searchNavigationChannel.send(globalSearchRepository.destinationFor(exactHit, _state.value.data))
                    } else {
                        _searchState.value = LoadState(data = response)
                    }
                }
                .onFailure { error -> _searchState.value = LoadState(error = error.toUiText(R.string.search_failed)) }
        }
    }

    fun clearSearch() {
        workbenchSearchJob?.cancel()
        _searchQuery.value = ""
        _searchState.value = LoadState()
    }

    fun selectSearchHit(hit: com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchHit) {
        viewModelScope.launch {
            _searchState.value = LoadState()
            searchNavigationChannel.send(globalSearchRepository.destinationFor(hit, _state.value.data))
        }
    }
}

data class ProfitUiState(
    val loading: Boolean = false,
    val data: ProfitOverview? = null,
    val error: UiText? = null,
    val audience: String = "employee",
    val platform: String = "all",
    val metric: String = "profit",
    val startDate: String = todayRange().first,
    val endDate: String = todayRange().second,
)

@HiltViewModel
class ProfitViewModel @Inject constructor(
    private val profitRepository: ProfitRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfitUiState())
    val state = _state.asStateFlow()

    fun load(permissions: List<String>) {
        val audience = if ("profit-report:boss" in permissions) "boss" else "employee"
        if ("profit-report:boss" !in permissions && "profit-report:employee" !in permissions) {
            _state.value = ProfitUiState(error = UiText.Resource(R.string.no_permission_profit))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, audience = audience) }
            val current = _state.value
            runCatching {
                profitRepository.overview(
                    audience = audience,
                    startDate = current.startDate,
                    endDate = current.endDate,
                    platform = current.platform,
                    metric = current.metric,
                )
            }.onSuccess { data ->
                _state.update { it.copy(loading = false, data = data) }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.toUiText(R.string.load_profit_failed)) }
            }
        }
    }

    fun setMetric(metric: String, permissions: List<String>) {
        _state.update { it.copy(metric = metric) }
        load(permissions)
    }

    fun setPlatform(platform: String, permissions: List<String>) {
        _state.update { it.copy(platform = platform) }
        load(permissions)
    }
}

@HiltViewModel
class ReceivingListViewModel @Inject constructor(
    private val receivingRepository: ReceivingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<List<ReceivingSession>>())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { receivingRepository.sessions() }
                .onSuccess { rows -> _state.value = LoadState(data = rows) }
                .onFailure { error -> _state.value = LoadState(error = error.toUiText(R.string.load_receiving_failed)) }
        }
    }
}

data class ReceivingDetailUiState(
    val loading: Boolean = false,
    val session: ReceivingSession? = null,
    val preview: ReceivingScanItem? = null,
    val previewRawCode: String = "",
    val duplicate: Boolean = false,
    val error: UiText? = null,
    val submitting: Boolean = false,
)

@HiltViewModel
class ReceivingDetailViewModel @Inject constructor(
    private val receivingRepository: ReceivingRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ReceivingDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { receivingRepository.session(id) }
                .onSuccess { session -> _state.update { it.copy(loading = false, session = session) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.toUiText(R.string.load_receiving_detail_failed)) } }
        }
    }

    fun preview(id: Long, rawCode: String) {
        if (rawCode.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, preview = null) }
            runCatching { receivingRepository.preview(id, rawCode.trim()) }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            preview = response.data,
                            previewRawCode = rawCode.trim(),
                            duplicate = response.duplicate,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(submitting = false, error = error.toUiText(R.string.scan_failed)) }
                }
        }
    }

    fun confirm(id: Long, damaged: Boolean = false, actualQty: Int? = null) {
        val rawCode = _state.value.previewRawCode
        if (rawCode.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { receivingRepository.scan(id, rawCode, damaged, actualQty) }
                .onSuccess {
                    _state.update { it.copy(submitting = false, preview = null, previewRawCode = "", duplicate = false) }
                    load(id)
                }
                .onFailure { error ->
                    _state.update { it.copy(submitting = false, error = error.toUiText(R.string.submit_failed)) }
                }
        }
    }

    fun voidScan(id: Long, item: ReceivingScanItem) {
        viewModelScope.launch {
            runCatching { receivingRepository.voidScan(id, item) }
                .onSuccess { load(id) }
                .onFailure { error -> _state.update { it.copy(error = error.toUiText(R.string.cancel_scan_failed)) } }
        }
    }

    fun complete(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            runCatching { receivingRepository.complete(id) }
                .onSuccess { load(id) }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.toUiText(R.string.finish_receiving_failed)) } }
        }
    }

    fun clearPreview() {
        _state.update { it.copy(preview = null, previewRawCode = "", duplicate = false) }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }
}

data class ProductManagementUiState(
    val query: String = "",
    val isSkuView: Boolean = false,
    val products: List<Product> = emptyList(),
    val skus: List<Sku> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null,
    val hasMore: Boolean = true,
    val page: Int = 1,
    val isBroadcastMode: Boolean = false,
    val defaultSkuPrintNodeId: Long? = null,
    val skuLabelPrefix: String = "T",
    val printingSkuId: Long? = null,
    val printMessage: UiText? = null,
)

@HiltViewModel
class ProductManagementViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductManagementUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    private var preferencesLoaded = false

    init {
        viewModelScope.launch {
            userDataRepository.userData.collect { userData ->
                val isBroadcastMode = userData.scannerAction.toScannerConfig().configuredMode ==
                    ScannerInputMode.IntentBroadcast
                val modeChanged = _state.value.isBroadcastMode != isBroadcastMode
                _state.update {
                    it.copy(
                        isBroadcastMode = isBroadcastMode,
                        isSkuView = if (isBroadcastMode) true else it.isSkuView,
                        defaultSkuPrintNodeId = userData.defaultSkuPrintNodeId,
                        skuLabelPrefix = userData.skuLabelPrefix,
                    )
                }
                if (!preferencesLoaded || modeChanged) {
                    preferencesLoaded = true
                    load(reset = true)
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, printMessage = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            load(reset = true)
        }
    }

    fun switchView(isSkuView: Boolean) {
        if (_state.value.isBroadcastMode) return
        if (_state.value.isSkuView == isSkuView) return
        _state.update { it.copy(isSkuView = isSkuView) }
        load(reset = true)
    }

    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        load(reset = false)
    }

    fun scanSku(code: String) {
        val normalized = code.trimEnd('\r', '\n', '\t').trim()
        if (normalized.isBlank()) return
        searchJob?.cancel()
        _state.update {
            it.copy(
                query = normalized,
                isSkuView = true,
                printMessage = null,
            )
        }
        load(reset = true)
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner) {
        scannerManager.pushOnScanListener(::scanSku)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }

    fun printSku(sku: Sku) {
        val current = _state.value
        val defaultNodeId = current.defaultSkuPrintNodeId
        if (current.printingSkuId != null) return
        if (defaultNodeId == null) {
            _state.update { it.copy(error = UiText.Resource(R.string.device_default_sku_node_required)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(printingSkuId = sku.id, error = null, printMessage = null) }
            runCatching {
                val option = edgePrintRepository.availableNodes()
                    .firstOrNull { it.node.id == defaultNodeId }
                    ?: throw ClientUiException(UiText.Resource(R.string.device_default_sku_node_unavailable))
                edgePrintRepository.printSku(
                    option = option,
                    sku = sku,
                    copies = 1,
                    labelPrefix = current.skuLabelPrefix,
                )
            }.onSuccess { submission ->
                _state.update {
                    it.copy(
                        printingSkuId = null,
                        printMessage = UiText.Resource(
                            if (submission.local) R.string.device_local_print_submitted else R.string.device_remote_print_submitted,
                            listOf(submission.nodeName),
                        ),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        printingSkuId = null,
                        error = error.toUiText(R.string.device_sku_print_failed),
                    )
                }
            }
        }
    }

    fun initForProduct(productId: Long) {
        _state.update {
            it.copy(
                isSkuView = true,
                query = "productId:$productId", // A simple way to signal repository, or we can add a specific field
                products = emptyList(),
                skus = emptyList(),
                page = 1,
                hasMore = true,
            )
        }
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        val current = _state.value
        val nextPage = if (reset) 1 else current.page + 1

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (current.isSkuView || current.isBroadcastMode) {
                    val result = repository.skus(current.query, nextPage)
                    val rows = result.rows()
                    _state.update {
                        it.copy(
                            skus = if (reset) rows else it.skus + rows,
                            page = nextPage,
                            hasMore = rows.size >= 20,
                            loading = false,
                        )
                    }
                } else {
                    val result = repository.products(current.query, nextPage)
                    val rows = result.rows()
                    _state.update {
                        it.copy(
                            products = if (reset) rows else it.products + rows,
                            page = nextPage,
                            hasMore = rows.size >= 20,
                            loading = false,
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUiText(R.string.product_load_failed)) }
            }
        }
    }
}

data class CartonManagementUiState(
    val loading: Boolean = false,
    val boxLabel: BoxLabel? = null,
    val error: UiText? = null,
    val lastScannedCode: String = "",
    val unrecognized: Boolean = false,
    val loadingPrintOptions: Boolean = false,
    val printDialogVisible: Boolean = false,
    val boxMarkOptions: List<EdgePrintNodeOption> = emptyList(),
    val selectedBoxMarkOptionKey: String? = null,
    val boxMarkCopies: Int = 1,
    val defaultBoxMarkPrintNodeId: Long? = null,
    val printSubmitting: Boolean = false,
    val printMessage: UiText? = null,
    val printError: UiText? = null,
    val pendingPrintIdempotencyKey: String? = null,
)

data class PdaModeUiState(
    val loading: Boolean = false,
    val result: Any? = null, // Can be Sku or BoxLabel
    val error: UiText? = null,
    val query: String = "",
)

data class SearchDetailUiState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: UiText? = null,
)

data class LabelPrintUiState(
    val visible: Boolean = false,
    val loadingNodes: Boolean = false,
    val nodes: List<EdgePrintNodeOption> = emptyList(),
    val selectedNodeId: Long? = null,
    val copies: Int = 1,
    val labelPrefix: String = "",
    val skuCode: String = "",
    val submitting: Boolean = false,
    val message: UiText? = null,
    val error: UiText? = null,
)

data class EdgeNodesUiState(
    val loading: Boolean = false,
    val nodes: List<EdgePrintNodeStatus> = emptyList(),
    val quickPrintOptions: List<EdgePrintNodeOption> = emptyList(),
    val selectedNodeKey: String? = null,
    val defaultSkuPrintNodeId: Long? = null,
    val boxMarkOptions: List<EdgePrintNodeOption> = emptyList(),
    val defaultBoxMarkPrintNodeId: Long? = null,
    val skuLabelPrefix: String = "T",
    val error: UiText? = null,
)

data class LabelPrintAppUiState(
    val content: String = "",
    val loadingOptions: Boolean = false,
    val options: List<EdgePrintNodeOption> = emptyList(),
    val selectedOptionKey: String? = null,
    val copies: Int = 1,
    val submitting: Boolean = false,
    val message: UiText? = null,
    val error: UiText? = null,
)

@HiltViewModel
class LabelPrintAppViewModel @Inject constructor(
    private val edgePrintRepository: EdgePrintRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LabelPrintAppUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )

    init {
        loadOptions()
    }

    fun loadOptions() = loadOptions(errorAfterLoad = null)

    private fun loadOptions(errorAfterLoad: UiText?) {
        if (_state.value.loadingOptions) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOptions = true, error = null) }
            runCatching { edgePrintRepository.availableTemplateOptions() }
                .onSuccess { options ->
                    _state.update {
                        it.copy(
                            loadingOptions = false,
                            options = options,
                            selectedOptionKey = it.selectedOptionKey
                                ?.takeIf { key -> options.any { option -> option.selectionKey == key } }
                                ?: options.firstOrNull()?.selectionKey,
                            error = errorAfterLoad ?: if (options.isEmpty()) UiText.Resource(R.string.device_no_template_options) else null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loadingOptions = false,
                            error = error.toUiText(R.string.device_query_templates_failed),
                        )
                    }
                }
        }
    }

    fun updateContent(content: String) {
        val normalized = content.trimEnd('\r', '\n', '\t').take(512)
        _state.update { it.copy(content = normalized, message = null, error = null) }
    }

    fun selectOption(key: String) {
        _state.update { it.copy(selectedOptionKey = key, message = null, error = null) }
    }

    fun changeCopies(delta: Int) {
        _state.update { it.copy(copies = (it.copies + delta).coerceIn(1, 100)) }
    }

    fun submit() {
        val current = _state.value
        val option = current.options.firstOrNull { it.selectionKey == current.selectedOptionKey }
        if (current.content.isBlank() || option == null || current.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, message = null, error = null) }
            runCatching { edgePrintRepository.printLabel(option, current.content, current.copies) }
                .onSuccess { submission ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            message = UiText.Resource(
                                if (submission.local) R.string.device_local_print_submitted else R.string.device_remote_print_submitted,
                                listOf(submission.nodeName),
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    val message = error.toUiText(R.string.device_label_print_failed)
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = message,
                        )
                    }
                    if (error is EdgePrintTemplateUnavailableException) loadOptions(errorAfterLoad = message)
                }
        }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner) {
        scannerManager.pushOnScanListener(::updateContent)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }
}

@HiltViewModel
class EdgeNodesViewModel @Inject constructor(
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EdgeNodesUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            userDataRepository.userData.collect { userData ->
                _state.update {
                    it.copy(
                        defaultSkuPrintNodeId = userData.defaultSkuPrintNodeId,
                        defaultBoxMarkPrintNodeId = userData.defaultBoxMarkPrintNodeId,
                        skuLabelPrefix = userData.skuLabelPrefix,
                    )
                }
            }
        }
        load()
    }

    fun load() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                Triple(
                    edgePrintRepository.registeredNodes(),
                    edgePrintRepository.availableNodes(),
                    edgePrintRepository.availableBoxMarkOptions(),
                )
            }.onSuccess { (nodes, options, boxMarkOptions) ->
                _state.update {
                    it.copy(
                        loading = false,
                        nodes = nodes,
                        quickPrintOptions = options,
                        boxMarkOptions = boxMarkOptions,
                        selectedNodeKey = null,
                    )
                }
            }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.toUiText(R.string.device_query_edge_nodes_failed),
                        )
                    }
                }
        }
    }

    fun selectNode(key: String) {
        _state.update { it.copy(selectedNodeKey = key) }
    }

    fun closeNodeDetails() {
        _state.update { it.copy(selectedNodeKey = null) }
    }

    fun selectQuickPrintNode(nodeId: Long) {
        _state.update { it.copy(defaultSkuPrintNodeId = nodeId) }
        saveQuickPrintPreferences(nodeId, _state.value.skuLabelPrefix)
    }

    fun updateSkuLabelPrefix(prefix: String) {
        val normalized = prefix.filterNot { it.isISOControl() || it.isWhitespace() }.take(16)
        _state.update { it.copy(skuLabelPrefix = normalized) }
        saveQuickPrintPreferences(_state.value.defaultSkuPrintNodeId, normalized)
    }

    fun selectBoxMarkPrintNode(nodeId: Long) {
        _state.update { it.copy(defaultBoxMarkPrintNodeId = nodeId) }
        viewModelScope.launch { userDataRepository.setDefaultBoxMarkPrintNode(nodeId) }
    }

    private fun saveQuickPrintPreferences(nodeId: Long?, prefix: String) {
        viewModelScope.launch {
            userDataRepository.setSkuQuickPrintPreferences(nodeId, prefix)
        }
    }
}

@HiltViewModel
class SkuDetailViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchDetailUiState<Sku>())
    val state = _state.asStateFlow()
    private val _printState = MutableStateFlow(LabelPrintUiState())
    val printState = _printState.asStateFlow()

    fun load(idOrCode: String) {
        viewModelScope.launch {
            _state.value = SearchDetailUiState(loading = true)
            runCatching { productRepository.sku(idOrCode) }
                .onSuccess { _state.value = SearchDetailUiState(data = it) }
                .onFailure { _state.value = SearchDetailUiState(error = it.toUiText(R.string.device_load_sku_failed)) }
        }
    }

    fun openPrintDialog() {
        _printState.value = LabelPrintUiState(
            visible = true,
            loadingNodes = true,
            skuCode = _state.value.data?.code.orEmpty(),
        )
        loadPrintNodes(errorAfterLoad = null)
    }

    private fun loadPrintNodes(errorAfterLoad: UiText?) {
        _printState.update { it.copy(loadingNodes = true) }
        viewModelScope.launch {
            runCatching {
                userDataRepository.userData.first() to edgePrintRepository.availableNodes()
            }
                .onSuccess { (userData, nodes) ->
                    _printState.update {
                        it.copy(
                            loadingNodes = false,
                            nodes = nodes,
                            selectedNodeId = it.selectedNodeId
                                ?.takeIf { selectedId -> nodes.any { option -> option.node.id == selectedId } }
                                ?: nodes.firstOrNull { option -> option.node.id == userData.defaultSkuPrintNodeId }?.node?.id
                                ?: nodes.firstOrNull()?.node?.id,
                            labelPrefix = userData.skuLabelPrefix,
                            error = errorAfterLoad ?: if (nodes.isEmpty()) UiText.Resource(R.string.device_no_print_nodes_available) else null,
                        )
                    }
                }
                .onFailure { error ->
                    _printState.update { it.copy(loadingNodes = false, error = error.toUiText(R.string.device_query_print_nodes_failed)) }
                }
        }
    }

    fun closePrintDialog() {
        if (!_printState.value.submitting) _printState.value = LabelPrintUiState()
    }

    fun selectPrintNode(nodeId: Long) {
        _printState.update { it.copy(selectedNodeId = nodeId, message = null, error = null) }
    }

    fun changePrintCopies(delta: Int) {
        _printState.update { it.copy(copies = (it.copies + delta).coerceIn(1, 100)) }
    }

    fun setPrintPrefix(prefix: String) {
        _printState.update {
            it.copy(
                labelPrefix = prefix.filterNot { character -> character.isISOControl() || character.isWhitespace() }.take(16),
                message = null,
                error = null,
            )
        }
    }

    fun submitPrint() {
        val sku = _state.value.data ?: return
        val printState = _printState.value
        val option = printState.nodes.firstOrNull { it.node.id == printState.selectedNodeId }
        if (option == null || printState.submitting) return
        viewModelScope.launch {
            _printState.update { it.copy(submitting = true, message = null, error = null) }
            runCatching {
                edgePrintRepository.printSku(
                    option = option,
                    sku = sku,
                    copies = printState.copies,
                    labelPrefix = printState.labelPrefix,
                )
            }
                .onSuccess { submission ->
                    _printState.update {
                        it.copy(
                            submitting = false,
                            message = UiText.Resource(
                                if (submission.local) R.string.device_local_print_submitted else R.string.device_remote_print_submitted,
                                listOf(submission.nodeName),
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    val message = error.toUiText(R.string.device_label_print_failed)
                    _printState.update { it.copy(submitting = false, error = message) }
                    if (error is EdgePrintTemplateUnavailableException) loadPrintNodes(errorAfterLoad = message)
                }
        }
    }
}

@HiltViewModel
class BoxLabelDetailViewModel @Inject constructor(
    private val cartonRepository: CartonRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchDetailUiState<BoxLabel>())
    val state = _state.asStateFlow()

    fun load(code: String) {
        viewModelScope.launch {
            _state.value = SearchDetailUiState(loading = true)
            runCatching { cartonRepository.resolveBoxLabel(code) }
                .onSuccess { _state.value = SearchDetailUiState(data = it) }
                .onFailure { _state.value = SearchDetailUiState(error = it.toUiText(R.string.device_load_box_label_failed)) }
        }
    }
}

@HiltViewModel
class PdaModeViewModel @Inject constructor(
    private val globalSearchRepository: GlobalSearchRepository,
    private val productRepository: ProductRepository,
    private val cartonRepository: CartonRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(PdaModeUiState())
    val state = _state.asStateFlow()

    fun resolve(code: String) {
        if (code.isBlank()) return
        _state.update { it.copy(query = code, loading = true, error = null, result = null) }
        viewModelScope.launch {
            runCatching {
                val search = globalSearchRepository.search(code, includeLocal = false)
                val hit = search.hits.firstOrNull()
                    ?: throw ClientUiException(UiText.Resource(R.string.device_no_matching_result, listOf(code)))
                when (hit.businessType.toBusinessTypeCode()) {
                    BusinessTypeCode.SkuDetail -> productRepository.sku(hit.targetId.ifBlank { hit.targetCode })
                    BusinessTypeCode.BoxLabelDetail -> cartonRepository.resolveBoxLabel(hit.targetCode.ifBlank { code })
                    else -> throw ClientUiException(UiText.Resource(R.string.business_type_unsupported, listOf(hit.businessType)))
                }
            }.onSuccess { detail ->
                _state.update { it.copy(loading = false, result = detail) }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.toUiText(R.string.device_identify_failed)) }
            }
        }
    }

    fun clear() {
        _state.value = PdaModeUiState()
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }
}

@HiltViewModel
class PdaSettingsViewModel @Inject constructor(
    private val scannerManager: ScannerManager,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )

    fun clearScannerDiagnostics() {
        scannerManager.clearDiagnostics()
    }

    fun setInputMode(mode: ScannerInputMode) {
        viewModelScope.launch {
            val current = userDataRepository.userData.first().scannerAction
            userDataRepository.setScannerAction(current.withScannerInputMode(mode))
            scannerManager.applyInputMode(mode)
        }
    }
}

@HiltViewModel
class CartonManagementViewModel @Inject constructor(
    private val repository: CartonRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CartonManagementUiState())
    val state = _state.asStateFlow()
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )

    init {
        viewModelScope.launch {
            userDataRepository.userData.collect { userData ->
                _state.update { it.copy(defaultBoxMarkPrintNodeId = userData.defaultBoxMarkPrintNodeId) }
            }
        }
    }

    fun resolve(rawCode: String) {
        if (rawCode.isBlank()) return
        val localResult = runCatching { parseBoxLabelQr(rawCode).toLocalBoxLabel() }
            .getOrElse { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        boxLabel = null,
                        error = error.toUiText(R.string.device_identify_box_failed),
                        lastScannedCode = rawCode,
                        unrecognized = true,
                    )
                }
                return
            }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    boxLabel = localResult,
                    lastScannedCode = rawCode,
                    unrecognized = false,
                    printDialogVisible = false,
                    printMessage = null,
                    printError = null,
                    pendingPrintIdempotencyKey = null,
                )
            }
            runCatching { repository.resolveBoxLabel(rawCode) }
                .onSuccess { result ->
                    _state.update {
                        if (result.recognized) {
                            it.copy(loading = false, boxLabel = result)
                        } else {
                            it.copy(loading = false, unrecognized = true)
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (error.isBoxLabelNotFound()) {
                            it.copy(loading = false, unrecognized = true)
                        } else {
                            it.copy(loading = false, error = error.toUiText(R.string.device_identify_box_failed))
                        }
                    }
                }
        }
    }

    fun retry() = resolve(_state.value.lastScannedCode)

    fun requestBoxMarkPrint() {
        val label = _state.value.boxLabel ?: return
        when {
            label.localFallback -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_offline_disabled)) }
                return
            }
            !label.printable -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_not_printable)) }
                return
            }
            label.id <= 0 || label.printSnapshot == null -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_snapshot_missing)) }
                return
            }
        }
        if (_state.value.loadingPrintOptions || _state.value.printSubmitting) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingPrintOptions = true,
                    printMessage = null,
                    printError = null,
                )
            }
            runCatching { edgePrintRepository.availableBoxMarkOptions() }
                .onSuccess { options ->
                    val defaultOption = options.firstOrNull { option ->
                        option.node.id == _state.value.defaultBoxMarkPrintNodeId
                    }
                    _state.update {
                        it.copy(
                            loadingPrintOptions = false,
                            boxMarkOptions = options,
                            selectedBoxMarkOptionKey = defaultOption?.selectionKey
                                ?: options.firstOrNull()?.selectionKey,
                            printDialogVisible = defaultOption == null && options.isNotEmpty(),
                            printError = if (options.isEmpty()) {
                                UiText.Resource(R.string.device_no_box_mark_nodes)
                            } else {
                                null
                            },
                        )
                    }
                    if (defaultOption != null) submitBoxMark(defaultOption, copies = 1, saveDefault = false)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loadingPrintOptions = false,
                            printError = error.toUiText(R.string.device_query_print_nodes_failed),
                        )
                    }
                }
        }
    }

    fun selectBoxMarkOption(key: String) {
        _state.update { it.copy(selectedBoxMarkOptionKey = key) }
    }

    fun updateBoxMarkCopies(delta: Int) {
        _state.update { it.copy(boxMarkCopies = (it.boxMarkCopies + delta).coerceIn(1, 100)) }
    }

    fun confirmBoxMarkPrint() {
        val current = _state.value
        val option = current.boxMarkOptions.firstOrNull { it.selectionKey == current.selectedBoxMarkOptionKey }
        if (option == null) {
            _state.update { it.copy(printError = UiText.Resource(R.string.device_no_box_mark_nodes)) }
            return
        }
        viewModelScope.launch {
            userDataRepository.setDefaultBoxMarkPrintNode(option.node.id)
            submitBoxMark(option, current.boxMarkCopies, saveDefault = true)
        }
    }

    fun dismissBoxMarkPrintDialog() {
        if (_state.value.printSubmitting) return
        _state.update {
            it.copy(
                printDialogVisible = false,
                pendingPrintIdempotencyKey = null,
            )
        }
    }

    private suspend fun submitBoxMark(
        option: EdgePrintNodeOption,
        copies: Int,
        saveDefault: Boolean,
    ) {
        val label = _state.value.boxLabel ?: return
        val idempotencyKey = _state.value.pendingPrintIdempotencyKey
            ?: "mobile-box-mark-${UUID.randomUUID()}"
        _state.update {
            it.copy(
                printSubmitting = true,
                printDialogVisible = if (saveDefault) it.printDialogVisible else false,
                printMessage = null,
                printError = null,
                pendingPrintIdempotencyKey = idempotencyKey,
            )
        }
        runCatching { edgePrintRepository.printBoxMark(option, label, copies, idempotencyKey) }
            .onSuccess { submission ->
                _state.update {
                    it.copy(
                        printSubmitting = false,
                        printDialogVisible = false,
                        pendingPrintIdempotencyKey = null,
                        printMessage = UiText.Resource(
                            if (submission.local) R.string.device_local_print_submitted else R.string.device_remote_print_submitted,
                            listOf(submission.nodeName),
                        ),
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        printSubmitting = false,
                        printError = error.toUiText(R.string.device_box_mark_print_failed),
                    )
                }
            }
    }

    fun clear() {
        _state.update {
            it.copy(
                boxLabel = null,
                error = null,
                lastScannedCode = "",
                unrecognized = false,
                printDialogVisible = false,
                printMessage = null,
                printError = null,
                pendingPrintIdempotencyKey = null,
            )
        }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }
}

private fun Throwable.isBoxLabelNotFound(): Boolean {
    if (this !is FscmApiException) return false
    if (statusCode == 404) return true
    val message = serverMessage.orEmpty().lowercase()
    return listOf("不存在", "未找到", "无法识别", "未识别", "not found", "not_found").any(message::contains)
}

private fun Throwable.toUiText(@StringRes fallback: Int): UiText =
    when (this) {
        is ClientUiException -> uiText
        is BoxLabelQrFormatException -> UiText.Dynamic(message.orEmpty())
        is FscmApiException -> serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
            ?: UiText.Resource(fallback)
        else -> UiText.Resource(fallback)
    }

private class ClientUiException(val uiText: UiText) : IllegalStateException()
