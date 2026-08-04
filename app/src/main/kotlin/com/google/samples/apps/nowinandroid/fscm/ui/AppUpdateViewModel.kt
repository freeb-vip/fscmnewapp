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

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.BuildConfig
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.AppUpdateDownloadStatus
import com.google.samples.apps.nowinandroid.fscm.data.AppUpdateManager
import com.google.samples.apps.nowinandroid.fscm.data.AppUpdateRelease
import com.google.samples.apps.nowinandroid.fscm.data.AppUpdateRepository
import com.google.samples.apps.nowinandroid.fscm.data.PendingAppUpdateDownload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data class Checking(val manual: Boolean) : AppUpdateUiState
    data class Available(val release: AppUpdateRelease, val forceUpdate: Boolean) : AppUpdateUiState
    data class Downloading(
        val release: AppUpdateRelease,
        val forceUpdate: Boolean,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
    ) : AppUpdateUiState
    data class Ready(val release: AppUpdateRelease, val forceUpdate: Boolean) : AppUpdateUiState
    data class Failed(
        val release: AppUpdateRelease,
        val forceUpdate: Boolean,
        val message: UiText,
    ) : AppUpdateUiState
}

sealed interface AppUpdateEffect {
    data class OpenUnknownSourcesSettings(val intent: Intent) : AppUpdateEffect
    data class OpenInstaller(val intent: Intent) : AppUpdateEffect
}

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val manager: AppUpdateManager,
) : ViewModel() {
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state = _state.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message = _message.asStateFlow()

    private val effectsChannel = Channel<AppUpdateEffect>(Channel.BUFFERED)
    val effects = effectsChannel.receiveAsFlow()

    private var monitorJob: Job? = null

    init {
        viewModelScope.launch {
            val pending = manager.pendingDownload()
            if (pending != null && pending.release.versionCode > BuildConfig.VERSION_CODE) {
                monitor(pending)
            } else {
                if (pending != null) manager.cancel(pending.downloadId, pending.release)
                checkForUpdate(manual = false)
            }
        }
    }

    fun checkManually() {
        viewModelScope.launch { checkForUpdate(manual = true) }
    }

    fun startDownload() {
        val current = _state.value
        val release: AppUpdateRelease
        val forceUpdate: Boolean
        when (current) {
            is AppUpdateUiState.Available -> {
                release = current.release
                forceUpdate = current.forceUpdate
            }
            is AppUpdateUiState.Failed -> {
                release = current.release
                forceUpdate = current.forceUpdate
            }
            else -> return
        }
        viewModelScope.launch {
            runCatching { manager.enqueue(release, forceUpdate) }
                .onSuccess { downloadId ->
                    monitor(PendingAppUpdateDownload(downloadId, release, forceUpdate))
                }
                .onFailure {
                    _state.value = AppUpdateUiState.Failed(
                        release,
                        forceUpdate,
                        UiText.Resource(R.string.app_update_download_failed),
                    )
                }
        }
    }

    fun postpone() {
        val current = _state.value
        val release = when (current) {
            is AppUpdateUiState.Available -> current.release.takeUnless { current.forceUpdate }
            is AppUpdateUiState.Failed -> current.release.takeUnless { current.forceUpdate }
            else -> null
        } ?: return
        viewModelScope.launch {
            repository.snooze(release)
            _state.value = AppUpdateUiState.Idle
        }
    }

    fun cancelDownload() {
        val current = _state.value as? AppUpdateUiState.Downloading ?: return
        if (current.forceUpdate) return
        val pendingJob = monitorJob
        monitorJob = null
        pendingJob?.cancel()
        viewModelScope.launch {
            val pending = manager.pendingDownload()
            if (pending != null) manager.cancel(pending.downloadId, pending.release)
            repository.snooze(current.release)
            _state.value = AppUpdateUiState.Idle
        }
    }

    fun requestInstall() {
        val current = _state.value as? AppUpdateUiState.Ready ?: return
        viewModelScope.launch {
            if (manager.canRequestPackageInstalls()) {
                effectsChannel.send(AppUpdateEffect.OpenInstaller(manager.installIntent(current.release)))
            } else {
                effectsChannel.send(
                    AppUpdateEffect.OpenUnknownSourcesSettings(manager.unknownSourcesIntent()),
                )
            }
        }
    }

    fun onUnknownSourcesResult() {
        val current = _state.value as? AppUpdateUiState.Ready ?: return
        viewModelScope.launch {
            if (manager.canRequestPackageInstalls()) {
                effectsChannel.send(AppUpdateEffect.OpenInstaller(manager.installIntent(current.release)))
            } else {
                _message.value = UiText.Resource(R.string.app_update_install_permission_required)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun checkForUpdate(manual: Boolean) {
        if (_state.value is AppUpdateUiState.Downloading || _state.value is AppUpdateUiState.Ready) return
        _state.value = AppUpdateUiState.Checking(manual)
        runCatching { repository.check() }
            .onSuccess { result ->
                val release = result.latest
                if (!result.updateAvailable || release == null) {
                    _state.value = AppUpdateUiState.Idle
                    if (manual) _message.value = UiText.Resource(R.string.app_update_latest)
                    return@onSuccess
                }
                if (repository.shouldPrompt(release, result.forceUpdate, manual)) {
                    _state.value = AppUpdateUiState.Available(release, result.forceUpdate)
                } else {
                    _state.value = AppUpdateUiState.Idle
                }
            }
            .onFailure {
                _state.value = AppUpdateUiState.Idle
                if (manual) _message.value = UiText.Resource(R.string.app_update_check_failed)
            }
    }

    private fun monitor(pending: PendingAppUpdateDownload) {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            _state.value = AppUpdateUiState.Downloading(pending.release, pending.forceUpdate)
            while (true) {
                when (val status = manager.query(pending.downloadId)) {
                    is AppUpdateDownloadStatus.Running -> {
                        _state.value = AppUpdateUiState.Downloading(
                            release = pending.release,
                            forceUpdate = pending.forceUpdate,
                            downloadedBytes = status.downloadedBytes,
                            totalBytes = status.totalBytes,
                        )
                        delay(DOWNLOAD_POLL_INTERVAL_MILLIS)
                    }
                    AppUpdateDownloadStatus.Successful -> {
                        runCatching { manager.verify(pending.release) }
                            .onSuccess {
                                _state.value = AppUpdateUiState.Ready(pending.release, pending.forceUpdate)
                                requestInstall()
                            }
                            .onFailure {
                                manager.cancel(pending.downloadId, pending.release)
                                _state.value = AppUpdateUiState.Failed(
                                    pending.release,
                                    pending.forceUpdate,
                                    UiText.Resource(R.string.app_update_verification_failed),
                                )
                            }
                        return@launch
                    }
                    is AppUpdateDownloadStatus.Failed,
                    AppUpdateDownloadStatus.Missing,
                    -> {
                        manager.cancel(pending.downloadId, pending.release)
                        _state.value = AppUpdateUiState.Failed(
                            pending.release,
                            pending.forceUpdate,
                            UiText.Resource(R.string.app_update_download_failed),
                        )
                        return@launch
                    }
                }
            }
        }
    }

    private companion object {
        const val DOWNLOAD_POLL_INTERVAL_MILLIS = 750L
    }
}
