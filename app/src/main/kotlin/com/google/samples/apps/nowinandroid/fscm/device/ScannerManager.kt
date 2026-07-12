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

package com.google.samples.apps.nowinandroid.fscm.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
) {
    private val onScanListeners = mutableListOf<(String) -> Unit>()
    private val _scannerState = MutableStateFlow(
        ScannerRuntimeState(profile = ScannerProfile.default()),
    )
    val scannerState: StateFlow<ScannerRuntimeState> = _scannerState.asStateFlow()

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.extractScannerEvent(activeProfile.extraKeys)
            val effectiveMode = if (event.extracted && activeConfiguredMode != ScannerInputMode.Keyboard) {
                ScannerInputMode.IntentBroadcast
            } else {
                activeConfiguredMode ?: ScannerInputMode.Keyboard
            }
            _scannerState.update {
                it.copy(
                    effectiveMode = effectiveMode,
                    lastEvent = event,
                    lastError = null,
                )
            }
            if (event.extracted && effectiveMode == ScannerInputMode.IntentBroadcast) {
                onScanListeners.lastOrNull()?.invoke(event.normalizedValue)
            }
        }
    }
    private var activeProfile = ScannerProfile.default()
    private var activeConfiguredMode: ScannerInputMode? = null
    private var registered = false

    fun pushOnScanListener(listener: (String) -> Unit) {
        onScanListeners.add(listener)
    }

    fun popOnScanListener() {
        if (onScanListeners.isNotEmpty()) {
            onScanListeners.removeAt(onScanListeners.size - 1)
        }
    }

    fun clearDiagnostics() {
        _scannerState.update { it.copy(lastEvent = null, lastError = null) }
    }

    fun applyInputMode(mode: ScannerInputMode) {
        activeConfiguredMode = mode
        _scannerState.update {
            it.copy(
                configuredMode = mode,
                effectiveMode = mode,
            )
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(scannerReceiver)
            registered = false
            _scannerState.update { it.copy(registered = false) }
        } catch (e: Exception) {
            // Ignore if not registered.
            _scannerState.update { it.copy(registered = false) }
        }
    }

    fun register(lifecycleOwner: LifecycleOwner) {
        // If the same owner is already registered, don't add another observer
        if (activeOwnerLifecycle?.get() == lifecycleOwner.lifecycle) return

        // Remove old observer from previous lifecycle if possible
        activeObserver?.let { observer ->
            activeOwnerLifecycle?.get()?.removeObserver(observer)
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                owner.lifecycleScope.launch {
                    val scannerConfig = userDataRepository.userData.first().scannerAction.toScannerConfig()
                    activeProfile = scannerConfig.profile
                    activeConfiguredMode = scannerConfig.configuredMode
                    val effectiveMode = scannerConfig.configuredMode
                        ?: if (_scannerState.value.lastEvent?.extracted == true) {
                            ScannerInputMode.IntentBroadcast
                        } else {
                            ScannerInputMode.Keyboard
                        }
                    _scannerState.update {
                        it.copy(
                            profile = activeProfile,
                            configuredMode = scannerConfig.configuredMode,
                            effectiveMode = effectiveMode,
                            registered = false,
                            lastError = null,
                        )
                    }
                    val filter = IntentFilter().apply {
                        activeProfile.actions.forEach(::addAction)
                        addCategory(Intent.CATEGORY_DEFAULT)
                    }
                    if (registered) unregister()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(scannerReceiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(scannerReceiver, filter)
                    }
                    registered = true
                    _scannerState.update {
                        it.copy(
                            profile = activeProfile,
                            configuredMode = scannerConfig.configuredMode,
                            effectiveMode = effectiveMode,
                            registered = true,
                            lastError = null,
                        )
                    }

                    if (effectiveMode == ScannerInputMode.IntentBroadcast) {
                        // Poke hardware scanner to ensure it is active for this app.
                        pokeScanners()
                    }
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                unregister()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                if (activeObserver == this) {
                    activeObserver = null
                    activeOwnerLifecycle = null
                }
            }
        }
        activeObserver = observer
        activeOwnerLifecycle = java.lang.ref.WeakReference(lifecycleOwner.lifecycle)
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    private fun pokeScanners() {
        // Broadly send common enable intents for various PDA vendors (Zebra, Honeywell, etc.)
        // This helps when the system scanner service is "waiting" for an app to claim it.
        listOf(
            "com.symbol.datawedge.api.ACTION_DATAWEDGE_FROM_6_2" to ("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN" to "ENABLE_PLUGIN"),
            "com.seuic.scanner.action.PARAM_SETTINGS" to ("scan_mode" to "1"),
            "com.honeywell.decode.intent.action.EDIT_RECAP" to null,
        ).forEach { (action, extra) ->
            try {
                val intent = Intent(action)
                extra?.let { (key, value) -> intent.putExtra(key, value) }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // Ignore errors for non-existent actions on different hardware
                _scannerState.update { it.copy(lastError = e.message) }
            }
        }
    }

    private var activeObserver: DefaultLifecycleObserver? = null
    private var activeOwnerLifecycle: java.lang.ref.WeakReference<androidx.lifecycle.Lifecycle>? = null

    companion object {
        const val FSCM_SCAN_ACTION = "com.fscm.SCAN"

        val DEFAULT_SCANNER_ACTIONS = listOf(
            FSCM_SCAN_ACTION,
            "android.intent.ACTION_DECODE_DATA",
            "android.intent.action.SCANRESULT",
            "ACTION_BAR_SCANCFG",
            "cn.guanmai.scanner.data",
            "com.symbol.datawedge.api.RESULT_ACTION",
            "com.symbol.datawedge.api.ACTION_DATAWEDGE_FROM_6_2",
            "com.datalogic.decodewedge.decode_action",
            "com.honeywell.decode.intent.action.BARCODE_RESULT",
            "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",
            "com.nlscan.action.SCANNER_RESULT",
            "nlscan.action.SCANNER_RESULT",
            "com.android.server.scannerservice.broadcast",
            "com.android.scanner.service_settings",
            "com.seuic.scan",
            "com.idata.scan.broadcast",
            "com.urovo.scan_action",
            "com.zkc.scancode",
        )

        val DEFAULT_EXTRA_KEYS = listOf(
            "barcode",
            "barCode",
            "barcode_string",
            "code",
            "data",
            "string",
            "scannerdata",
            "com.symbol.datawedge.data_string",
            "com.datalogic.decode.intent_data",
            "data_string",
            "dataBytes",
            "EXTRA_SCAN_DATA",
            "SCAN_BARCODE1",
            "SCAN_BARCODE2",
            "SCAN_BARCODE",
            "scanner_data",
            "value",
        )

        fun String.toScannerProfile(): ScannerProfile {
            val parts = scannerProfileSource().split("|", limit = 2)
            val configuredActions = parts.firstOrNull()
                .orEmpty()
                .split(',', ';', '\n')
                .map(String::trim)
                .filter(String::isNotBlank)
            val configuredExtras = parts.getOrNull(1)
                .orEmpty()
                .split(',', ';', '\n')
                .map(String::trim)
                .filter(String::isNotBlank)
            return ScannerProfile(
                actions = (configuredActions + DEFAULT_SCANNER_ACTIONS).distinct(),
                extraKeys = (configuredExtras + DEFAULT_EXTRA_KEYS).distinct(),
            )
        }

        fun String.toScannerConfig(): ScannerConfig =
            ScannerConfig(
                configuredMode = scannerInputMode(),
                profile = toScannerProfile(),
                profileSource = scannerProfileSource(),
            )

        fun String.withScannerInputMode(mode: ScannerInputMode): String {
            val profileSource = scannerProfileSource()
            return buildString {
                append("mode=")
                append(mode.storageValue)
                if (profileSource.isNotBlank()) {
                    append('\n')
                    append(profileSource)
                }
            }
        }

        private fun String.scannerInputMode(): ScannerInputMode? {
            val firstLine = lineSequence().firstOrNull()?.trim().orEmpty()
            val value = firstLine
                .substringBefore('|')
                .substringAfter("=", missingDelimiterValue = "")
                .trim()
                .lowercase()
            if (!firstLine.startsWith("mode", ignoreCase = true)) return null
            return when (value) {
                "keyboard", "key", "input" -> ScannerInputMode.Keyboard
                "intent", "broadcast", "intent_broadcast", "intent-broadcast" -> ScannerInputMode.IntentBroadcast
                else -> null
            }
        }

        private fun String.scannerProfileSource(): String {
            val normalized = trim()
            if (!normalized.startsWith("mode", ignoreCase = true)) return normalized
            val modeLineEnd = normalized.indexOf('\n').takeIf { it >= 0 }
            val firstLine = modeLineEnd?.let { normalized.substring(0, it) } ?: normalized
            if (!firstLine.substringBefore('|').contains("=")) return normalized
            val inlineProfile = firstLine.substringAfter('|', missingDelimiterValue = "")
            val remaining = modeLineEnd?.let { normalized.substring(it + 1) }.orEmpty()
            return listOf(inlineProfile, remaining)
                .filter(String::isNotBlank)
                .joinToString("\n")
                .trim()
        }

        fun Intent.extractBarcode(extraKeys: List<String> = DEFAULT_EXTRA_KEYS): String =
            extractScannerEvent(extraKeys).normalizedValue

        fun Intent.extractScannerEvent(extraKeys: List<String> = DEFAULT_EXTRA_KEYS): ScannerEvent {
            extraKeys.forEach { key ->
                val value = getStringExtra(key)
                    ?: getByteArrayExtra(key)?.let { String(it) }
                if (!value.isNullOrBlank()) {
                    return ScannerEvent(
                        action = action.orEmpty(),
                        extraKey = key,
                        rawValue = value,
                        normalizedValue = value.trim(),
                        receivedAtMillis = System.currentTimeMillis(),
                        extracted = true,
                    )
                }
            }
            val clipValue = clipData
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
            if (!clipValue.isNullOrBlank()) {
                return ScannerEvent(
                    action = action.orEmpty(),
                    extraKey = "clipData",
                    rawValue = clipValue,
                    normalizedValue = clipValue.trim(),
                    receivedAtMillis = System.currentTimeMillis(),
                    extracted = true,
                )
            }
            return ScannerEvent(
                action = action.orEmpty(),
                extraKey = null,
                rawValue = "",
                normalizedValue = "",
                receivedAtMillis = System.currentTimeMillis(),
                extracted = false,
            )
        }
    }
}

data class ScannerRuntimeState(
    val profile: ScannerProfile,
    val configuredMode: ScannerInputMode? = null,
    val effectiveMode: ScannerInputMode = ScannerInputMode.Keyboard,
    val registered: Boolean = false,
    val lastEvent: ScannerEvent? = null,
    val lastError: String? = null,
)

data class ScannerEvent(
    val action: String,
    val extraKey: String?,
    val rawValue: String,
    val normalizedValue: String,
    val receivedAtMillis: Long,
    val extracted: Boolean,
)

data class ScannerProfile(
    val actions: List<String>,
    val extraKeys: List<String>,
) {
    companion object {
        fun default() = ScannerProfile(
            actions = ScannerManager.DEFAULT_SCANNER_ACTIONS,
            extraKeys = ScannerManager.DEFAULT_EXTRA_KEYS,
        )
    }
}

data class ScannerConfig(
    val configuredMode: ScannerInputMode?,
    val profile: ScannerProfile,
    val profileSource: String,
)

enum class ScannerInputMode(val storageValue: String) {
    Keyboard("keyboard"),
    IntentBroadcast("intent"),
}
