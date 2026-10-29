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

import android.content.Context
import android.os.SystemClock
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
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelMappingCacheMissException
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelMappingRepository
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelQrFormatException
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelSearchInfo
import com.google.samples.apps.nowinandroid.fscm.data.BoxMarkPrintAvailability
import com.google.samples.apps.nowinandroid.fscm.data.BusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.CartonRepository
import com.google.samples.apps.nowinandroid.fscm.data.CatalogSource
import com.google.samples.apps.nowinandroid.fscm.data.CrossOrderReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.DatabaseRecoveryNoticeStore
import com.google.samples.apps.nowinandroid.fscm.data.EdgeCatalogClient
import com.google.samples.apps.nowinandroid.fscm.data.EdgeConnectionPolicy
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeOption
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeStatus
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintTemplateUnavailableException
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTerminalRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTicketProvider
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchRepository
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchResponse
import com.google.samples.apps.nowinandroid.fscm.data.LegacyOfflineDataAdopter
import com.google.samples.apps.nowinandroid.fscm.data.LoginSecurityException
import com.google.samples.apps.nowinandroid.fscm.data.LoginSecurityState
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsage
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsageStore
import com.google.samples.apps.nowinandroid.fscm.data.MobileRepository
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncRepository
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.data.OperationsWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.ProductRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingRepository
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SearchDestination
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.Sku
import com.google.samples.apps.nowinandroid.fscm.data.WarehouseWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchPresentation
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchStyleRepository
import com.google.samples.apps.nowinandroid.fscm.data.boxMarkPrintAvailability
import com.google.samples.apps.nowinandroid.fscm.data.defaultPrintOption
import com.google.samples.apps.nowinandroid.fscm.data.exactNavigationId
import com.google.samples.apps.nowinandroid.fscm.data.isDuplicateReceipt
import com.google.samples.apps.nowinandroid.fscm.data.isDuplicateReceivingFailure
import com.google.samples.apps.nowinandroid.fscm.data.isKnownMappedBoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.isReceivingComplete
import com.google.samples.apps.nowinandroid.fscm.data.samePrintContent
import com.google.samples.apps.nowinandroid.fscm.data.toBusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.withReceivedScan
import com.google.samples.apps.nowinandroid.fscm.data.withoutPendingScan
import com.google.samples.apps.nowinandroid.fscm.device.ReceivingScanFeedback
import com.google.samples.apps.nowinandroid.fscm.device.ScanAudioDiagnostics
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.toScannerConfig
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager.Companion.withScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindService
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindState
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindStatusStore
import com.google.samples.apps.nowinandroid.fscm.device.isSuccessfulProductScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LoginUiState(
    val currentUsername: String = "",
    val challengeUsername: String = "",
    val submitting: Boolean = false,
    val refreshingCaptcha: Boolean = false,
    val security: LoginSecurityState? = null,
) {
    val requiresCaptcha: Boolean
        get() = security?.let { it.requireCaptcha || it.captcha != null } == true

    val isLocked: Boolean
        get() = (security?.lockRemainingSeconds ?: 0) > 0
}

internal fun normalizeLoginUsername(username: String): String = username.trim().lowercase()

internal fun LoginUiState.withUsername(username: String): LoginUiState {
    val normalized = normalizeLoginUsername(username)
    if (normalized == currentUsername) return this
    return if (challengeUsername.isNotBlank() && challengeUsername != normalized) {
        LoginUiState(currentUsername = normalized)
    } else {
        copy(currentUsername = normalized)
    }
}

internal fun isLoginSubmissionEnabled(
    username: String,
    password: String,
    captchaAnswer: String,
    state: LoginUiState,
): Boolean = username.isNotBlank() &&
    password.isNotBlank() &&
    !state.submitting &&
    !state.isLocked &&
    (!state.requiresCaptcha || captchaAnswer.isNotBlank())

internal fun remainingLoginLockSeconds(deadlineElapsedRealtime: Long, nowElapsedRealtime: Long): Int =
    ((deadlineElapsedRealtime - nowElapsedRealtime + 999L) / 1_000L).coerceAtLeast(0L).toInt()

sealed interface FscmStartupUiState {
    data object Restoring : FscmStartupUiState

    data class Ready(val session: SessionState) : FscmStartupUiState
}

@HiltViewModel
class FscmRootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val databaseRecoveryNoticeStore: DatabaseRecoveryNoticeStore,
    private val userDataRepository: UserDataRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val edgeCatalogClient: EdgeCatalogClient,
    private val legacyOfflineDataAdopter: LegacyOfflineDataAdopter,
    private val offlineSyncScheduler: OfflineSyncScheduler,
    private val scannerManager: ScannerManager,
    private val edgeTicketProvider: EdgeTicketProvider,
    terminalFindStatusStore: TerminalFindStatusStore,
    @param:ApplicationContext private val applicationContext: Context,
    offlineSyncRepository: OfflineSyncRepository,
) : ViewModel() {
    val terminalFindState: StateFlow<TerminalFindState> = terminalFindStatusStore.state
    private val _startupState = MutableStateFlow<FscmStartupUiState>(FscmStartupUiState.Restoring)
    val startupState = _startupState.asStateFlow()
    private val _message = MutableStateFlow<UiText?>(null)
    val message = _message.asStateFlow()
    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState = _loginState.asStateFlow()
    private var loginLockJob: Job? = null
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
        }
        viewModelScope.launch {
            val restoredSession = authRepository.restoreLocalSession()
            _startupState.value = FscmStartupUiState.Ready(restoredSession)
            launch {
                authRepository.session.collect { session ->
                    _startupState.value = FscmStartupUiState.Ready(session)
                }
            }
            if (!restoredSession.isLoggedIn) return@launch

            launch { authRepository.refreshStoredSession() }
            launch { TerminalFindService.start(applicationContext) }
            launch { runCatching { legacyOfflineDataAdopter.adoptFor(restoredSession) } }
            if (restoredSession.token.isNotBlank()) {
                launch { offlineSyncScheduler.enqueue() }
            }
            launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
            launch { runCatching { edgeCatalogClient.prewarm() } }
        }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner) {
        scannerManager.pushOnScanListener { barcode ->
            _globalScanResult.value = barcode
        }
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.clearInputProtection()
        scannerManager.popOnScanListener()
    }

    fun refreshEdgeConnection() {
        if ((_startupState.value as? FscmStartupUiState.Ready)?.session?.isLoggedIn == true) TerminalFindService.start(applicationContext)
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

    fun onLoginUsernameChanged(username: String) {
        val current = _loginState.value
        val updated = current.withUsername(username)
        if (current.challengeUsername != updated.challengeUsername) loginLockJob?.cancel()
        _loginState.value = updated
    }

    fun refreshLoginCaptcha(username: String) {
        val normalizedUsername = normalizeLoginUsername(username)
        if (normalizedUsername.isBlank()) return
        onLoginUsernameChanged(normalizedUsername)
        val current = _loginState.value
        if (current.isLocked || current.refreshingCaptcha) return
        _loginState.value = current.copy(
            challengeUsername = normalizedUsername,
            refreshingCaptcha = true,
        )
        viewModelScope.launch {
            try {
                val captcha = authRepository.refreshLoginCaptcha(normalizedUsername)
                if (_loginState.value.currentUsername == normalizedUsername) {
                    val security = _loginState.value.security ?: LoginSecurityState()
                    applyLoginSecurity(
                        username = normalizedUsername,
                        security = security.copy(requireCaptcha = true, captcha = captcha),
                    )
                }
            } catch (error: Throwable) {
                _message.value = error.toUiText(R.string.login_captcha_refresh_failed)
            } finally {
                _loginState.update { state ->
                    if (state.currentUsername == normalizedUsername) {
                        state.copy(refreshingCaptcha = false)
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun login(username: String, password: String, captchaAnswer: String = "") {
        val normalizedUsername = normalizeLoginUsername(username)
        onLoginUsernameChanged(normalizedUsername)
        val current = _loginState.value
        if (current.submitting || current.isLocked) return
        val security = current.security.takeIf { current.challengeUsername == normalizedUsername }
        if (current.requiresCaptcha && captchaAnswer.isBlank()) {
            _message.value = UiText.Resource(R.string.login_captcha_required)
            return
        }
        _loginState.value = current.copy(
            challengeUsername = normalizedUsername,
            submitting = true,
        )
        viewModelScope.launch {
            _message.value = null
            try {
                authRepository.loginOrOffline(
                    username = normalizedUsername,
                    password = password,
                    captchaId = security?.captcha?.captchaId.orEmpty(),
                    captchaAnswer = captchaAnswer,
                )
                loginLockJob?.cancel()
                _loginState.value = LoginUiState(currentUsername = normalizedUsername)
                TerminalFindService.start(applicationContext)
                runCatching { legacyOfflineDataAdopter.adoptFor(authRepository.session.first()) }
                offlineSyncScheduler.enqueue()
                launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                launch {
                    edgeCatalogClient.invalidate()
                    runCatching { edgeCatalogClient.prewarm() }
                }
            } catch (error: LoginSecurityException) {
                if (_loginState.value.currentUsername == normalizedUsername) {
                    applyLoginSecurity(normalizedUsername, error.security)
                }
                _message.value = error.serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
                    ?: UiText.Resource(R.string.login_failed)
            } catch (error: Throwable) {
                _message.value = error.toUiText(R.string.login_failed)
            } finally {
                _loginState.update { state ->
                    if (state.currentUsername == normalizedUsername) state.copy(submitting = false) else state
                }
            }
        }
    }

    private fun applyLoginSecurity(username: String, security: LoginSecurityState) {
        loginLockJob?.cancel()
        _loginState.update { state ->
            if (state.currentUsername == username) {
                state.copy(challengeUsername = username, security = security)
            } else {
                state
            }
        }
        if (security.lockRemainingSeconds <= 0) return
        val deadline = SystemClock.elapsedRealtime() + security.lockRemainingSeconds * 1_000L
        loginLockJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val remaining = remainingLoginLockSeconds(deadline, SystemClock.elapsedRealtime())
                val state = _loginState.value
                if (state.challengeUsername != username) break
                if (remaining <= 0) {
                    _loginState.value = LoginUiState(currentUsername = state.currentUsername)
                    break
                }
                _loginState.update { currentState ->
                    if (currentState.challengeUsername == username) {
                        currentState.copy(
                            security = currentState.security?.copy(lockRemainingSeconds = remaining),
                        )
                    } else {
                        currentState
                    }
                }
            }
        }
    }

    fun switchNamespace(namespaceId: Long) {
        viewModelScope.launch {
            runCatching { authRepository.switchNamespace(namespaceId) }
                .onSuccess {
                    TerminalFindService.start(applicationContext)
                    runCatching { legacyOfflineDataAdopter.adoptFor(authRepository.session.first()) }
                    offlineSyncScheduler.enqueue()
                    launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
                    launch {
                        edgeCatalogClient.invalidate()
                        runCatching { edgeCatalogClient.prewarm() }
                    }
                }
                .onFailure { _message.value = it.toUiText(R.string.switch_namespace_failed) }
        }
    }

    fun logout() {
        scannerManager.clearInputProtection()
        loginLockJob?.cancel()
        _loginState.value = LoginUiState()
        edgeCatalogClient.invalidate()
        TerminalFindService.stop(applicationContext)
        viewModelScope.launch {
            edgeTicketProvider.clear()
            authRepository.logout()
        }
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

internal fun workbenchLoadingState(
    cached: MobileWorkbenchOverview?,
    isOffline: Boolean,
): LoadState<MobileWorkbenchOverview> = LoadState(
    loading = !isOffline,
    data = cached ?: if (isOffline) MobileWorkbenchOverview() else null,
)

internal fun workbenchRefreshFailureState(
    current: LoadState<MobileWorkbenchOverview>,
    error: UiText,
): LoadState<MobileWorkbenchOverview> = current.copy(loading = false, error = error)

@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val mobileRepository: MobileRepository,
    private val styleRepository: WorkbenchStyleRepository,
    private val edgeTerminalRepository: EdgeTerminalRepository,
    private val globalSearchRepository: GlobalSearchRepository,
    private val mobileAppUsageStore: MobileAppUsageStore,
    scannerManager: ScannerManager,
) : ViewModel() {
    private val _presentation = MutableStateFlow(WorkbenchPresentation())
    val presentation = _presentation.asStateFlow()
    private val _presentationReady = MutableStateFlow(false)
    val presentationReady = _presentationReady.asStateFlow()
    private val _warehouse = MutableStateFlow(LoadState<WarehouseWorkbench>())
    val warehouse = _warehouse.asStateFlow()
    private val _operations = MutableStateFlow(LoadState<OperationsWorkbench>())
    val operations = _operations.asStateFlow()
    private var styleSessionKey: String? = null
    private var styleLoadJob: Job? = null

    fun loadStyle(session: SessionState, force: Boolean = false) {
        val key = "${session.user?.id}:${session.currentNamespace?.id}:${session.sessionMode}"
        if (!force && styleSessionKey == key) return
        styleLoadJob?.cancel()
        val changed = styleSessionKey != key
        styleSessionKey = key
        if (changed) {
            _presentationReady.value = false
            _presentation.value = WorkbenchPresentation()
            _warehouse.value = LoadState()
            _operations.value = LoadState()
        }
        styleLoadJob = viewModelScope.launch {
            val presentation = styleRepository.presentation(session)
            if (styleSessionKey != key) return@launch
            _presentation.value = presentation
            _presentationReady.value = true
            when (presentation.style) {
                "warehouse" -> loadWarehouse(key, session)
                "operations" -> if (!session.isOffline) loadOperations(key)
            }
        }
    }

    private suspend fun loadWarehouse(key: String, session: SessionState) {
        try {
            val cached = styleRepository.cachedWarehouse(session)
            if (styleSessionKey != key) return
            _warehouse.value = LoadState(loading = !session.isOffline, data = cached)
            if (session.isOffline) return
            val data = styleRepository.warehouse(session)
            if (styleSessionKey == key) _warehouse.value = LoadState(data = data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (styleSessionKey == key) _warehouse.value = _warehouse.value.copy(loading = false, error = error.toUiText(R.string.load_home_failed))
        }
    }

    private suspend fun loadOperations(key: String) {
        _operations.value = _operations.value.copy(loading = true)
        try {
            val data = styleRepository.operations()
            if (styleSessionKey == key) _operations.value = LoadState(data = data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (styleSessionKey == key) _operations.value = LoadState(error = error.toUiText(R.string.load_home_failed))
        }
    }

    private val _state = MutableStateFlow(LoadState<MobileWorkbenchOverview>())
    val state = _state.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow(LoadState<GlobalSearchResponse>())
    val searchState = _searchState.asStateFlow()
    private val searchNavigationChannel = Channel<SearchDestination>(Channel.BUFFERED)
    val searchNavigation = searchNavigationChannel.receiveAsFlow()
    private var workbenchSearchJob: Job? = null
    private var workbenchLoadJob: Job? = null
    private var mobileAppUsageJob: Job? = null
    private var mobileAppUsageSessionKey: String? = null
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
        val sessionChanged = loadedSessionKey != sessionKey
        if (!force && !sessionChanged) return
        workbenchLoadJob?.cancel()
        loadedSessionKey = sessionKey
        if (sessionChanged) _state.value = LoadState()
        loadUsage(session)
        workbenchLoadJob = viewModelScope.launch {
            val existing = _state.value.data.takeUnless { sessionChanged }
            val cached = existing ?: try {
                mobileRepository.cachedWorkbenchOverview(session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (loadedSessionKey != sessionKey) return@launch
            _state.value = workbenchLoadingState(cached, session.isOffline)
            if (session.isOffline) return@launch

            try {
                val overview = mobileRepository.refreshWorkbenchOverview(session)
                if (loadedSessionKey != sessionKey) return@launch
                _state.value = LoadState(data = overview)
                launch { runCatching { edgeTerminalRepository.registerCurrentTerminal() } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (loadedSessionKey != sessionKey) return@launch
                _state.value = workbenchRefreshFailureState(
                    current = _state.value,
                    error = error.toUiText(R.string.load_home_failed),
                )
            }
        }
    }

    fun loadUsage(session: SessionState) {
        val key = "${session.user?.id}:${session.currentNamespace?.id}:${session.sessionMode}"
        if (mobileAppUsageSessionKey == key) return
        mobileAppUsageSessionKey = key
        mobileAppUsageJob?.cancel()
        _mobileAppUsage.value = MobileAppUsage()
        mobileAppUsageJob = viewModelScope.launch {
            mobileAppUsageStore.initializeDefaultCommonApps(session)
            mobileAppUsageStore.usage(session).collect { usage -> _mobileAppUsage.value = usage }
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
                        globalSearchRepository.destinationFor(exactHit, _state.value.data)?.let {
                            searchNavigationChannel.send(it)
                        }
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
            globalSearchRepository.destinationFor(hit, _state.value.data)?.let {
                searchNavigationChannel.send(it)
            }
        }
    }
}

@HiltViewModel
class ReceivingListViewModel @Inject constructor(
    private val receivingRepository: ReceivingRepository,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<List<ReceivingSession>>())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { boxLabelMappingRepository.refreshIfStale() }
            runCatching { receivingRepository.sessions() }
                .onSuccess { rows -> _state.value = LoadState(data = rows) }
                .onFailure { error -> _state.value = LoadState(error = error.toUiText(R.string.load_receiving_failed)) }
        }
    }
}

data class ReceivingDetailUiState(
    val loading: Boolean = false,
    val session: ReceivingSession? = null,
    val error: UiText? = null,
    val submitting: Boolean = false,
    val syncing: Boolean = false,
    val knownJifengCodes: Set<String> = emptySet(),
)

@HiltViewModel
class ReceivingDetailViewModel @Inject constructor(
    private val crossOrderRepository: CrossOrderReceivingRepository,
    private val receivingRepository: ReceivingRepository,
    private val boxLabelMappingRepository: BoxLabelMappingRepository,
    private val scannerManager: ScannerManager,
    private val scanFeedback: ReceivingScanFeedback,
) : ViewModel() {
    val crossOrder = CrossOrderReceivingController(viewModelScope, crossOrderRepository, scanFeedback)
    private val _state = MutableStateFlow(ReceivingDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) {
        crossOrder.load(id)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { boxLabelMappingRepository.refreshIfStale() }
            _state.update { it.copy(knownJifengCodes = boxLabelMappingRepository.knownJifengAliases()) }
            runCatching { receivingRepository.session(id) }
                .onSuccess { session -> _state.update { it.copy(loading = false, session = session) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.toUiText(R.string.load_receiving_detail_failed)) } }
        }
    }

    fun scanAndReceive(id: Long, rawCode: String) {
        val normalized = rawCode.trim()
        if (normalized.isBlank() || _state.value.submitting || crossOrder.isBlocking) return
        if (_state.value.session?.isReceivingComplete() == true) {
            scanFeedback.failure()
            _state.update { it.copy(error = UiText.Resource(R.string.receiving_already_complete_scan_error)) }
            return
        }
        if (!isKnownBoxLabelCode(normalized)) {
            scanFeedback.failure()
            _state.update { it.copy(error = UiText.Resource(R.string.box_label_scan_retry_prompt)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching {
                crossOrderRepository.intercept(id, normalized, "receiving")
                receivingRepository.scan(id, normalized, damaged = false)
            }
                .onSuccess { response ->
                    if (response.isDuplicateReceipt()) {
                        scanFeedback.failure()
                        _state.update {
                            it.copy(
                                submitting = false,
                                error = UiText.Resource(R.string.receiving_duplicate_scan_error),
                            )
                        }
                    } else {
                        val updatedSession = _state.value.session?.withReceivedScan(response.data, damaged = false)
                        _state.update { it.copy(session = updatedSession) }
                        scanFeedback.success()
                        if (updatedSession?.isReceivingComplete() == true) {
                            runCatching { receivingRepository.complete(id) }
                                .onSuccess { completedSession ->
                                    _state.update {
                                        it.copy(
                                            submitting = false,
                                            session = completedSession,
                                        )
                                    }
                                }
                                .onFailure { error ->
                                    _state.update {
                                        it.copy(
                                            submitting = false,
                                            error = error.toUiText(R.string.submit_failed),
                                        )
                                    }
                                }
                        } else {
                            _state.update { it.copy(submitting = false) }
                        }
                    }
                }
                .onFailure { error ->
                    if (crossOrder.handle(error)) {
                        _state.update { it.copy(submitting = false) }
                        return@onFailure
                    }
                    scanFeedback.failure()
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = if (error.isDuplicateReceivingFailure()) {
                                UiText.Resource(R.string.receiving_duplicate_scan_error)
                            } else {
                                error.toUiText(R.string.submit_failed)
                            },
                        )
                    }
                }
        }
    }

    fun voidScan(id: Long, item: ReceivingScanItem) {
        viewModelScope.launch {
            runCatching { receivingRepository.voidScan(id, item) }
                .onSuccess {
                    _state.update { state -> state.copy(session = state.session?.withoutPendingScan(item)) }
                }
                .onFailure { error -> _state.update { it.copy(error = error.toUiText(R.string.cancel_scan_failed)) } }
        }
    }

    fun sync(id: Long) {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            runCatching { receivingRepository.sync(id) }
                .onSuccess { session ->
                    _state.update { it.copy(syncing = false, session = session) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            syncing = false,
                            error = error.toUiText(R.string.load_receiving_detail_failed),
                        )
                    }
                }
        }
    }

    fun registerScanner(lifecycleOwner: LifecycleOwner, onScan: (String) -> Unit) {
        scannerManager.pushOnScanListener(onScan)
        scannerManager.register(lifecycleOwner)
    }

    fun unregisterScanner() {
        scannerManager.popOnScanListener()
    }

    fun isKnownBoxLabelCode(code: String): Boolean =
        isKnownMappedBoxLabel(code, _state.value.knownJifengCodes)
}

data class ProductManagementUiState(
    val query: String = "",
    val skus: List<Sku> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null,
    val hasMore: Boolean = true,
    val page: Int = 1,
    val exactSearch: Boolean = false,
    val isBroadcastMode: Boolean = false,
    val defaultEdgePrintNodeId: Long? = null,
    val defaultSkuPrintTemplateCode: String = "",
    val skuLabelPrefix: String = "T",
    val printingSkuId: Long? = null,
    val printMessage: UiText? = null,
    val catalogSource: CatalogSource? = null,
    val catalogNodeName: String = "",
    val catalogStale: Boolean = false,
)

@HiltViewModel
class ProductManagementViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
    private val scannerManager: ScannerManager,
    private val scanFeedback: ReceivingScanFeedback,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductManagementUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var pendingScanQuery: String? = null

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
                        defaultEdgePrintNodeId = userData.defaultEdgePrintNodeId,
                        defaultSkuPrintTemplateCode = userData.defaultSkuPrintTemplateCode,
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
        pendingScanQuery = null
        _state.update { it.copy(query = query, printMessage = null) }
        searchJob?.cancel()
        loadJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            load(reset = true)
        }
    }

    fun toggleExactSearch() {
        searchJob?.cancel()
        loadJob?.cancel()
        _state.update { it.copy(exactSearch = !it.exactSearch) }
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
        loadJob?.cancel()
        pendingScanQuery = normalized
        _state.update {
            it.copy(
                query = normalized,
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
        val defaultNodeId = current.defaultEdgePrintNodeId
        if (current.printingSkuId != null) return
        if (defaultNodeId == null) {
            _state.update { it.copy(error = UiText.Resource(R.string.device_default_sku_node_required)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(printingSkuId = sku.id, error = null, printMessage = null) }
            runCatching {
                val option = edgePrintRepository.availableTemplateOptions()
                    .defaultPrintOption(defaultNodeId, current.defaultSkuPrintTemplateCode)
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
                query = "productId:$productId", // A simple way to signal repository, or we can add a specific field
                skus = emptyList(),
                page = 1,
                hasMore = true,
                exactSearch = false,
            )
        }
        load(reset = true)
    }

    fun load(reset: Boolean = false) {
        val current = _state.value
        val nextPage = if (reset) 1 else current.page + 1
        val scannedQuery = pendingScanQuery?.takeIf { reset && it == current.query }

        if (reset) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    catalogSource = CatalogSource.Center,
                    catalogNodeName = "",
                    catalogStale = false,
                )
            }
            runCatching {
                val result = repository.skusResult(
                    keyword = current.query,
                    page = nextPage,
                    exactMatch = current.exactSearch,
                )
                val rows = result.data.rows()
                if (scannedQuery != null) pendingScanQuery = null
                _state.update {
                    it.copy(
                        skus = if (reset) rows else it.skus + rows,
                        page = nextPage,
                        hasMore = rows.size >= 20,
                        loading = false,
                        catalogSource = result.source,
                        catalogNodeName = result.nodeName.ifBlank { result.nodeId },
                        catalogStale = result.isStale,
                        error = if (scannedQuery != null && !isSuccessfulProductScanResult(rows.size)) {
                            UiText.Resource(R.string.product_no_match)
                        } else {
                            null
                        },
                    )
                }
                if (scannedQuery != null) {
                    if (isSuccessfulProductScanResult(rows.size)) scanFeedback.success() else scanFeedback.failure()
                }
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                if (scannedQuery != null) {
                    pendingScanQuery = null
                    scanFeedback.failure()
                }
                _state.update { it.copy(loading = false, error = e.toUiText(R.string.product_load_failed)) }
            }
        }
    }
}

data class CartonManagementUiState(
    val listIndex: Int = 0,
    val listOffset: Int = 0,
    val query: String = "",
    val labels: List<BoxLabel> = emptyList(),
    val page: Int = 0,
    val total: Long = 0,
    val listLoading: Boolean = false,
    val searchInfo: BoxLabelSearchInfo? = null,
    val detailId: Long? = null,
    val catalogNotice: String = "",
    val detailNotice: String = "",
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
    val defaultEdgePrintNodeId: Long? = null,
    val defaultBoxMarkPrintTemplateCode: String = "",
    val printSubmitting: Boolean = false,
    val printMessage: UiText? = null,
    val printError: UiText? = null,
    val pendingPrintIdempotencyKey: String? = null,
    val knownJifengCodes: Set<String> = emptySet(),
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
    val options: List<EdgePrintNodeOption> = emptyList(),
    val selectedOptionKey: String? = null,
    val copies: Int = 1,
    val labelPrefix: String = "",
    val skuCode: String = "",
    val submitting: Boolean = false,
    val message: UiText? = null,
    val error: UiText? = null,
)

data class LabelPrintAppUiState(
    val content: String = "",
    val loadingOptions: Boolean = false,
    val options: List<EdgePrintNodeOption> = emptyList(),
    val selectedOptionKey: String? = null,
    val copies: Int = 1,
    val confirmationVisible: Boolean = false,
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
        _state.update { state ->
            if (state.options.any { it.selectionKey == key }) state.copy(selectedOptionKey = key, copies = 1, confirmationVisible = true, message = null, error = null) else state
        }
    }

    fun dismissConfirmation() {
        _state.update { if (it.submitting) it else it.copy(confirmationVisible = false, error = null) }
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
                            confirmationVisible = false,
                            content = "",
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

data class EdgeNodesUiState(
    val loading: Boolean = false,
    val nodes: List<EdgePrintNodeStatus> = emptyList(),
    val discovering: Boolean = false,
    val discoveryMessage: String? = null,
    val discoveryCompleted: Int = 0,
    val quickPrintOptions: List<EdgePrintNodeOption> = emptyList(),
    val boxMarkOptions: List<EdgePrintNodeOption> = emptyList(),
    val defaultEdgePrintNodeId: Long? = null,
    val defaultSkuPrintTemplateCode: String = "",
    val defaultBoxMarkPrintTemplateCode: String = "",
    val skuLabelPrefix: String = "T",
    val error: UiText? = null,
)

@HiltViewModel
class EdgeNodesViewModel @Inject constructor(
    private val connectionPolicy: EdgeConnectionPolicy,
    private val edgePrintRepository: EdgePrintRepository,
    private val userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EdgeNodesUiState())
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch { userDataRepository.userData.collect { userData -> _state.update { it.copy(defaultEdgePrintNodeId = userData.defaultEdgePrintNodeId, defaultSkuPrintTemplateCode = userData.defaultSkuPrintTemplateCode, defaultBoxMarkPrintTemplateCode = userData.defaultBoxMarkPrintTemplateCode, skuLabelPrefix = userData.skuLabelPrefix) } } }
    }
    private var discoveryJob: Job? = null
    private var loadJob: Job? = null

    suspend fun observeConnectionScope() {
        var previousScope = ""
        connectionPolicy.changes.collect {
            val currentScope = connectionPolicy.scopeKey()
            if (currentScope != previousScope) {
                previousScope = currentScope
                loadJob?.cancel()
                _state.update { it.copy(loading = false, nodes = emptyList(), quickPrintOptions = emptyList(), boxMarkOptions = emptyList(), discoveryMessage = null) }
                loadNodes(false)
            }
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
    }

    fun discover() {
        if (discoveryJob?.isCompleted == false || _state.value.discovering || _state.value.loading) return
        discoveryJob = viewModelScope.launch {
            _state.update { it.copy(discovering = true, discoveryMessage = null, error = null) }
            try {
                val nodes = edgePrintRepository.probeLocalNetwork()
                _state.update { it.copy(discoveryMessage = if (nodes.isEmpty()) "未发现局域网边缘节点" else "发现 ${nodes.size} 个局域网边缘节点", discoveryCompleted = it.discoveryCompleted + 1) }
                loadNodes(false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(discoveryMessage = error.message ?: "局域网探测失败") }
            } finally {
                _state.update { it.copy(discovering = false) }
            }
        }
    }

    fun load() = loadNodes(true)

    private fun loadNodes(force: Boolean) {
        if (_state.value.loading) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (force) edgePrintRepository.refreshRegisteredNodes()
                Triple(edgePrintRepository.registeredNodes(), edgePrintRepository.availableTemplateOptions(), edgePrintRepository.availableBoxMarkOptions())
            }
                .onSuccess { (nodes, skuOptions, boxOptions) -> _state.update { it.copy(loading = false, nodes = nodes, quickPrintOptions = skuOptions, boxMarkOptions = boxOptions, discoveryCompleted = it.discoveryCompleted + if (force) 1 else 0) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(loading = false, error = error.toUiText(R.string.device_query_edge_nodes_failed)) }
                }
        }
    }
    fun setDefaultNode(nodeId: Long) {
        val current = _state.value
        if (current.nodes.none { it.node.id == nodeId }) return
        val sku = current.defaultSkuPrintTemplateCode.takeIf { code -> current.quickPrintOptions.any { it.node.id == nodeId && it.template.code == code } }.orEmpty()
        val box = current.defaultBoxMarkPrintTemplateCode.takeIf { code -> current.boxMarkOptions.any { it.node.id == nodeId && it.template.code == code } }.orEmpty()
        savePreferences(nodeId, sku, box, current.skuLabelPrefix)
    }
    fun selectSkuTemplate(nodeId: Long, code: String) {
        val current = _state.value
        if (nodeId == current.defaultEdgePrintNodeId && current.quickPrintOptions.any { it.node.id == nodeId && it.template.code == code }) savePreferences(nodeId, code, current.defaultBoxMarkPrintTemplateCode, current.skuLabelPrefix)
    }
    fun selectBoxMarkTemplate(nodeId: Long, code: String) {
        val current = _state.value
        if (nodeId == current.defaultEdgePrintNodeId && current.boxMarkOptions.any { it.node.id == nodeId && it.template.code == code }) savePreferences(nodeId, current.defaultSkuPrintTemplateCode, code, current.skuLabelPrefix)
    }
    fun updateSkuLabelPrefix(prefix: String) {
        val current = _state.value
        savePreferences(current.defaultEdgePrintNodeId, current.defaultSkuPrintTemplateCode, current.defaultBoxMarkPrintTemplateCode, prefix.filterNot { it.isISOControl() || it.isWhitespace() }.take(16))
    }
    private fun savePreferences(nodeId: Long?, sku: String, box: String, prefix: String) {
        viewModelScope.launch { userDataRepository.setEdgePrintPreferences(nodeId, sku, box, prefix) }
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
                userDataRepository.userData.first() to edgePrintRepository.availableTemplateOptions()
            }
                .onSuccess { (userData, options) ->
                    _printState.update {
                        val defaultOption = options.defaultPrintOption(
                            userData.defaultEdgePrintNodeId,
                            userData.defaultSkuPrintTemplateCode,
                        )
                        val savedDefaultUnavailable =
                            userData.defaultEdgePrintNodeId != null && defaultOption == null
                        val selectedKey = it.selectedOptionKey
                            ?.takeIf { key -> options.any { option -> option.selectionKey == key } }
                            ?: defaultOption?.selectionKey
                            ?: options.firstOrNull()
                                ?.takeUnless { savedDefaultUnavailable }
                                ?.selectionKey
                        it.copy(
                            loadingNodes = false,
                            options = options,
                            selectedOptionKey = selectedKey,
                            labelPrefix = userData.skuLabelPrefix,
                            error = errorAfterLoad ?: when {
                                options.isEmpty() -> UiText.Resource(R.string.device_no_print_nodes_available)
                                savedDefaultUnavailable -> UiText.Resource(R.string.device_saved_node_unavailable)
                                else -> null
                            },
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

    fun selectPrintOption(key: String) {
        _printState.update {
            if (it.options.any { option -> option.selectionKey == key }) {
                it.copy(selectedOptionKey = key, message = null, error = null)
            } else {
                it
            }
        }
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
        val option = printState.options.firstOrNull { it.selectionKey == printState.selectedOptionKey }
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
                    val message = if (error is EdgePrintTemplateUnavailableException) {
                        UiText.Resource(R.string.device_label_template_changed)
                    } else {
                        error.toUiText(R.string.device_label_print_failed)
                    }
                    _printState.update { it.copy(submitting = false, error = message) }
                    if (error is EdgePrintTemplateUnavailableException) loadPrintNodes(errorAfterLoad = message)
                }
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
    private val scanFeedback: ReceivingScanFeedback,
) : ViewModel() {
    val scannerState: StateFlow<ScannerRuntimeState> = scannerManager.scannerState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = scannerManager.scannerState.value,
    )
    val audioDiagnostics: StateFlow<ScanAudioDiagnostics> = scanFeedback.audioDiagnostics

    fun refreshAudioDiagnostics() = scanFeedback.refreshAudioDiagnostics()

    fun testSuccessFeedback() = scanFeedback.success()

    fun testFailureFeedback() = scanFeedback.failure()

    fun setEventRecording(enabled: Boolean) = scannerManager.setEventRecording(enabled)

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
    val scannerState = scannerManager.scannerState

    private var searchJob: Job? = null
    private var searchVersion = 0L
    private var detailJob: Job? = null
    private var initialCodeConsumed = false

    fun loadInitialCode(code: String) {
        if (code.isBlank() || initialCodeConsumed) return
        initialCodeConsumed = true
        resolve(code)
    }

    fun rememberListPosition(index: Int, offset: Int) {
        _state.update { it.copy(listIndex = index, listOffset = offset) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, labels = emptyList(), total = 0, page = 0, listLoading = true, error = null, listIndex = 0, listOffset = 0) }
        searchLabels(debounce = true, navigate = query.isNotBlank())
    }

    fun searchLabels(debounce: Boolean = false, navigate: Boolean = false, more: Boolean = false) {
        if (more && (_state.value.listLoading || _state.value.labels.size >= _state.value.total)) return
        val version = ++searchVersion
        searchJob?.cancel()
        val query = _state.value.query
        val page = if (more) _state.value.page + 1 else 1
        searchJob = viewModelScope.launch {
            if (debounce) delay(500)
            _state.update { it.copy(listLoading = true, error = null) }
            try {
                val result = repository.searchLabels(query, page)
                if (version != searchVersion) return@launch
                _state.update {
                    it.copy(
                        labels = (if (more) it.labels + result.data.data else result.data.data).distinctBy(BoxLabel::id),
                        page = page,
                        total = result.data.total,
                        listLoading = false,
                        searchInfo = result.data.search,
                        catalogNotice = if (result.source == CatalogSource.Center) {
                            ""
                        } else {
                            "边缘已同步数据 · ${result.nodeName} · ${result.syncedAt.ifBlank { "同步时间未知" }}，可能不完整"
                        },
                    )
                }
                if (navigate) result.data.exactNavigationId()?.let { openDetail(it, query) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == searchVersion) _state.update { it.copy(listLoading = false, error = UiText.Dynamic(error.message ?: "箱唛加载失败")) }
            }
        }
    }

    fun openDetail(id: Long, scannedCode: String = "") {
        detailJob?.cancel()
        _state.update { it.copy(detailId = id, boxLabel = null, loading = true, error = null, lastScannedCode = scannedCode, printMessage = null, printError = null) }
        detailJob = viewModelScope.launch {
            try {
                val result = repository.labelDetail(id)
                _state.update {
                    it.copy(
                        loading = false,
                        boxLabel = result.data,
                        detailNotice = if (result.source == CatalogSource.Center) "" else "边缘快照 · ${result.nodeName} · ${result.syncedAt.ifBlank { "同步时间未知" }}，历史可能不完整",
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loading = false, error = UiText.Dynamic(error.message ?: "详情加载失败")) }
            }
        }
    }

    fun closeDetail() {
        if (_state.value.printSubmitting) return
        detailJob?.cancel()
        _state.update { it.copy(detailId = null, boxLabel = null, loading = false, error = null, printDialogVisible = false) }
    }

    init {
        searchLabels()
        viewModelScope.launch { runCatching { edgePrintRepository.availableBoxMarkOptions() } }
        viewModelScope.launch {
            userDataRepository.userData.collect { userData ->
                _state.update {
                    it.copy(
                        defaultEdgePrintNodeId = userData.defaultEdgePrintNodeId,
                        defaultBoxMarkPrintTemplateCode = userData.defaultBoxMarkPrintTemplateCode,
                    )
                }
            }
        }
    }

    fun resolve(rawCode: String) {
        val code = rawCode.trim { it.isWhitespace() || it == '\u0000' || it == '\uFEFF' }
        if (code.isBlank() || _state.value.printSubmitting) return
        detailJob?.cancel()
        searchJob?.cancel()
        ++searchVersion
        _state.update { it.copy(query = code, lastScannedCode = code, detailId = null, boxLabel = null, labels = emptyList(), total = 0, page = 0, listLoading = true, error = null) }
        detailJob = viewModelScope.launch {
            try {
                val result = repository.resolveManagementLabel(code)
                if (result.data.recognized && result.data.id > 0) {
                    _state.update { it.copy(listLoading = false) }
                    openDetail(result.data.id, code)
                } else {
                    searchLabels(navigate = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is FscmApiException && error.statusCode in listOf(401, 403)) {
                    _state.update { it.copy(listLoading = false, error = UiText.Dynamic(error.message ?: "无权查询箱唛")) }
                } else {
                    searchLabels(navigate = true)
                }
            }
        }
    }

    fun retry() {
        _state.value.detailId?.let { openDetail(it, _state.value.lastScannedCode) } ?: searchLabels()
    }

    fun requestBoxMarkPrint() {
        val label = _state.value.boxLabel ?: return
        when (label.boxMarkPrintAvailability()) {
            BoxMarkPrintAvailability.OfflineFallback -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_offline_disabled)) }
                return
            }
            BoxMarkPrintAvailability.NotPrintable -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_not_printable)) }
                return
            }
            BoxMarkPrintAvailability.SnapshotMissing -> {
                _state.update { it.copy(printError = UiText.Resource(R.string.device_box_mark_snapshot_missing)) }
                return
            }
            BoxMarkPrintAvailability.Ready -> Unit
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
                    val defaultOption = options.defaultPrintOption(
                        _state.value.defaultEdgePrintNodeId,
                        _state.value.defaultBoxMarkPrintTemplateCode,
                    )
                    _state.update {
                        it.copy(
                            loadingPrintOptions = false,
                            boxMarkOptions = options,
                            selectedBoxMarkOptionKey = defaultOption?.selectionKey
                                ?: options.firstOrNull()?.selectionKey,
                            printDialogVisible = options.isNotEmpty(),
                            printError = if (options.isEmpty()) {
                                UiText.Resource(R.string.device_no_box_mark_nodes)
                            } else {
                                null
                            },
                        )
                    }
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
            userDataRepository.userData.first().let { preferences ->
                userDataRepository.setEdgePrintPreferences(option.node.id, preferences.defaultSkuPrintTemplateCode, option.template.code, preferences.skuLabelPrefix)
            }
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
        runCatching {
            edgePrintRepository.recoverBoxMarkAttempt(option, label.id)?.let { recovered ->
                if (recovered.status in setOf("failed", "interrupted", "cancelled", "canceled")) error("打印任务 ${recovered.jobId} 状态为 ${recovered.status}，请在原节点核对")
                return@runCatching recovered
            }
            val fresh = repository.labelDetail(label.id)
            if (fresh.isStale) error("边缘箱唛快照已过期，请同步后打印")
            if (!label.samePrintContent(fresh.data)) {
                _state.update { it.copy(boxLabel = fresh.data, pendingPrintIdempotencyKey = null) }
                error("箱唛状态或内容已变化，请核对新内容后重新打印")
            }
            edgePrintRepository.printBoxMark(option, fresh.data, copies, idempotencyKey).also {
                if (it.status in setOf("failed", "interrupted", "cancelled", "canceled")) {
                    error("打印任务 ${it.jobId} 状态为 ${it.status}，请在原节点检查任务记录")
                }
            }
        }
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
                        printError = UiText.Dynamic(error.message ?: "打印失败，请使用原节点重试确认任务状态"),
                    )
                }
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
        is BoxLabelMappingCacheMissException -> UiText.Resource(R.string.box_label_mapping_cache_miss)
        is BoxLabelQrFormatException -> UiText.Dynamic(message.orEmpty())
        is FscmApiException -> serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
            ?: UiText.Resource(fallback)
        else -> UiText.Resource(fallback)
    }

private class ClientUiException(val uiText: UiText) : IllegalStateException()
