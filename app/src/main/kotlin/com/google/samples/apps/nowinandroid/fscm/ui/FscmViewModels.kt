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

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.fscm.data.AuthRepository
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.BusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.CartonRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeOption
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeStatus
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTerminalRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchRepository
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchResponse
import com.google.samples.apps.nowinandroid.fscm.data.MobileRepository
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncRepository
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
import com.google.samples.apps.nowinandroid.fscm.data.toBusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.todayRange
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
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
import javax.inject.Inject

@HiltViewModel
class FscmRootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
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
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val pendingSyncCount: StateFlow<Int> = offlineSyncRepository.pendingCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    private val _globalScanResult = MutableStateFlow<String?>(null)
    val globalScanResult = _globalScanResult.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.bootstrap()
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

    fun consumeGlobalScan() {
        _globalScanResult.value = null
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _message.value = null
            runCatching { authRepository.loginOrOffline(username, password) }
                .onSuccess { result ->
                    launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                    if (result.acceptedFromLocalCredential) {
                        launch {
                            val stillAllowed = authRepository.validateLocalLoginOnline(username, password)
                            if (!stillAllowed) {
                                _message.value = "账号或密码已失效，请重新联网登录"
                            }
                        }
                    }
                }
                .onFailure { _message.value = it.userMessage("登录失败") }
        }
    }

    fun switchNamespace(namespaceId: Long) {
        viewModelScope.launch {
            runCatching { authRepository.switchNamespace(namespaceId) }
                .onSuccess { launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } } }
                .onFailure { _message.value = it.userMessage("切换工作空间失败") }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class LoadState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
)

@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val mobileRepository: MobileRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
    private val globalSearchRepository: GlobalSearchRepository,
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
    private var loadedSessionKey: String? = null
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
            _state.update { it.copy(loading = true, error = null) }
            runCatching { mobileRepository.workbenchOverview() }
                .onSuccess { overview ->
                    _state.value = LoadState(data = overview)
                    viewModelScope.launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                }
                .onFailure { error -> _state.value = LoadState(error = error.userMessage("暂时无法加载首页")) }
        }
    }

    fun refresh(session: SessionState) = load(session, force = true)

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
            runCatching { globalSearchRepository.search(query, _state.value.data) }
                .onSuccess { response ->
                    val exactHit = response.hits.singleOrNull()?.takeIf { it.matchKind == "exact" }
                    if (exactHit != null) {
                        _searchState.value = LoadState()
                        searchNavigationChannel.send(globalSearchRepository.destinationFor(exactHit, _state.value.data))
                    } else {
                        _searchState.value = LoadState(data = response)
                    }
                }
                .onFailure { error -> _searchState.value = LoadState(error = error.userMessage("搜索失败")) }
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
    val error: String? = null,
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
            _state.value = ProfitUiState(error = "当前账号暂无利润报表权限")
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
                _state.update { it.copy(loading = false, error = error.userMessage("利润看板加载失败")) }
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
                .onFailure { error -> _state.value = LoadState(error = error.userMessage("收货单加载失败")) }
        }
    }
}

data class ReceivingDetailUiState(
    val loading: Boolean = false,
    val session: ReceivingSession? = null,
    val preview: ReceivingScanItem? = null,
    val previewRawCode: String = "",
    val duplicate: Boolean = false,
    val error: String? = null,
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
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.userMessage("收货详情加载失败")) } }
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
                    _state.update { it.copy(submitting = false, error = error.userMessage("箱唛识别失败")) }
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
                    _state.update { it.copy(submitting = false, error = error.userMessage("提交收货失败")) }
                }
        }
    }

    fun voidScan(id: Long, itemId: Long) {
        viewModelScope.launch {
            runCatching { receivingRepository.voidScan(id, itemId) }
                .onSuccess { load(id) }
                .onFailure { error -> _state.update { it.copy(error = error.userMessage("取消扫描失败")) } }
        }
    }

    fun complete(id: Long) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            runCatching { receivingRepository.complete(id) }
                .onSuccess { load(id) }
                .onFailure { error -> _state.update { it.copy(submitting = false, error = error.userMessage("完成收货失败")) } }
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
    val error: String? = null,
    val hasMore: Boolean = true,
    val page: Int = 1,
)

@HiltViewModel
class ProductManagementViewModel @Inject constructor(
    private val repository: ProductRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductManagementUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        load(reset = true)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            load(reset = true)
        }
    }

    fun switchView(isSkuView: Boolean) {
        if (_state.value.isSkuView == isSkuView) return
        _state.update { it.copy(isSkuView = isSkuView) }
        load(reset = true)
    }

    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        load(reset = false)
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
                if (current.isSkuView) {
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
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }
}

data class CartonManagementUiState(
    val loading: Boolean = false,
    val boxLabel: BoxLabel? = null,
    val error: String? = null,
)

data class PdaModeUiState(
    val loading: Boolean = false,
    val result: Any? = null, // Can be Sku or BoxLabel
    val error: String? = null,
    val query: String = "",
)

data class SearchDetailUiState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
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
    val message: String? = null,
    val error: String? = null,
)

data class EdgeNodesUiState(
    val loading: Boolean = false,
    val nodes: List<EdgePrintNodeStatus> = emptyList(),
    val selectedNodeKey: String? = null,
    val error: String? = null,
)

data class LabelPrintAppUiState(
    val content: String = "",
    val loadingOptions: Boolean = false,
    val options: List<EdgePrintNodeOption> = emptyList(),
    val selectedOptionKey: String? = null,
    val copies: Int = 1,
    val submitting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
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

    fun loadOptions() {
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
                            error = if (options.isEmpty()) "没有可用的边缘节点标签模板" else null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loadingOptions = false,
                            error = error.userMessage("查询标签模板失败"),
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
                    val mode = if (submission.local) "本地直打" else "远程任务"
                    _state.update {
                        it.copy(
                            submitting = false,
                            message = "$mode 已提交到 ${submission.nodeName}",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = error.userMessage("标签打印失败"),
                        )
                    }
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
) : ViewModel() {
    private val _state = MutableStateFlow(EdgeNodesUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { edgePrintRepository.registeredNodes() }
                .onSuccess { nodes ->
                    _state.update { it.copy(loading = false, nodes = nodes, selectedNodeKey = null) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.userMessage("查询边缘节点失败"),
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
}

@HiltViewModel
class SkuDetailViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val edgePrintRepository: EdgePrintRepository,
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
                .onFailure { _state.value = SearchDetailUiState(error = it.userMessage("获取 SKU 详情失败")) }
        }
    }

    fun openPrintDialog() {
        _printState.value = LabelPrintUiState(
            visible = true,
            loadingNodes = true,
            skuCode = _state.value.data?.code.orEmpty(),
        )
        viewModelScope.launch {
            runCatching { edgePrintRepository.availableNodes() }
                .onSuccess { nodes ->
                    _printState.update {
                        it.copy(
                            loadingNodes = false,
                            nodes = nodes,
                            selectedNodeId = nodes.firstOrNull()?.node?.id,
                            error = if (nodes.isEmpty()) "没有可用的边缘打印节点" else null,
                        )
                    }
                }
                .onFailure { error ->
                    _printState.update { it.copy(loadingNodes = false, error = error.userMessage("查询打印节点失败")) }
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
                    val mode = if (submission.local) "本地直打" else "远程任务"
                    _printState.update {
                        it.copy(
                            submitting = false,
                            message = "$mode 已提交到 ${submission.nodeName}",
                        )
                    }
                }
                .onFailure { error ->
                    _printState.update { it.copy(submitting = false, error = error.userMessage("标签打印失败")) }
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
                .onFailure { _state.value = SearchDetailUiState(error = it.userMessage("获取箱唛详情失败")) }
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
                val hit = search.hits.firstOrNull() ?: throw Exception("未找到匹配的结果: $code")
                when (hit.businessType.toBusinessTypeCode()) {
                    BusinessTypeCode.SkuDetail -> productRepository.sku(hit.targetId.ifBlank { hit.targetCode })
                    BusinessTypeCode.BoxLabelDetail -> cartonRepository.resolveBoxLabel(hit.targetCode.ifBlank { code })
                    else -> throw Exception("当前版本不支持该业务类型: ${hit.businessType}")
                }
            }.onSuccess { detail ->
                _state.update { it.copy(loading = false, result = detail) }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.userMessage("识别失败")) }
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
    private val scannerManager: ScannerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CartonManagementUiState())
    val state = _state.asStateFlow()

    fun resolve(rawCode: String) {
        if (rawCode.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, boxLabel = null) }
            runCatching { repository.resolveBoxLabel(rawCode) }
                .onSuccess { result ->
                    _state.update { it.copy(loading = false, boxLabel = result) }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.userMessage("识别箱唛失败")) }
                }
        }
    }

    fun clear() {
        _state.update { it.copy(boxLabel = null, error = null) }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }
}

private fun Throwable.userMessage(fallback: String): String {
    if (this is FscmApiException && message?.isNotBlank() == true) {
        // Use backend provided localized message if available
        return message!!
    }
    // TODO: Ideally we should use localized fallback strings from R.string
    return message?.takeIf { it.isNotBlank() } ?: fallback
}
