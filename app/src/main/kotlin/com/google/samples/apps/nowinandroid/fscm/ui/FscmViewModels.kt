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
import com.google.samples.apps.nowinandroid.fscm.data.AuthRepository
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.MobileRepository
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncRepository
import com.google.samples.apps.nowinandroid.fscm.data.ProfitOverview
import com.google.samples.apps.nowinandroid.fscm.data.ProfitRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.todayRange
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FscmRootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
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

    init {
        viewModelScope.launch {
            authRepository.bootstrap()
            _bootstrapping.value = false
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _message.value = null
            runCatching { authRepository.loginOrOffline(username, password) }
                .onSuccess { result ->
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
) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<MobileWorkbenchOverview>())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { mobileRepository.workbenchOverview() }
                .onSuccess { overview -> _state.value = LoadState(data = overview) }
                .onFailure { error -> _state.value = LoadState(error = error.userMessage("暂时无法加载首页")) }
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
        scannerManager.setOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.clearOnScanListener()
        scannerManager.unregister()
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
