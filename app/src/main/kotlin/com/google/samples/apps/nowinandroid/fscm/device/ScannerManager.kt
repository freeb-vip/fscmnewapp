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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
) {
    private var onScanListener: ((String) -> Unit)? = null

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.extractBarcode(activeProfile.extraKeys).takeIf { it.isNotBlank() }?.let { barcode ->
                onScanListener?.invoke(barcode.trim())
            }
        }
    }
    private var activeProfile = ScannerProfile.default()
    private var registered = false

    fun setOnScanListener(listener: (String) -> Unit) {
        onScanListener = listener
    }

    fun clearOnScanListener() {
        onScanListener = null
    }

    fun unregister() {
        try {
            context.unregisterReceiver(scannerReceiver)
            registered = false
        } catch (e: Exception) {
            // Ignore if not registered.
        }
    }

    fun register(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                owner.lifecycleScope.launch {
                    activeProfile = userDataRepository.userData.first().scannerAction.toScannerProfile()
                    val filter = IntentFilter().apply {
                        activeProfile.actions.forEach(::addAction)
                    }
                    if (registered) unregister()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(scannerReceiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(scannerReceiver, filter)
                    }
                    registered = true
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                unregister()
            }
        })
    }

    companion object {
        const val FSCM_SCAN_ACTION = "com.fscm.SCAN"

        val DEFAULT_SCANNER_ACTIONS = listOf(
            FSCM_SCAN_ACTION,
            "android.intent.ACTION_DECODE_DATA",
            "com.symbol.datawedge.data_string",
            "com.symbol.datawedge.api.RESULT_ACTION",
            "com.datalogic.decodewedge.decode_action",
            "com.honeywell.decode.intent.action.BARCODE_RESULT",
            "com.nlscan.action.SCANNER_RESULT",
            "nlscan.action.SCANNER_RESULT",
            "com.android.server.scannerservice.broadcast",
            "com.android.scanner.service_settings",
            "com.seuic.scan",
            "com.zkc.scancode",
        )

        val DEFAULT_EXTRA_KEYS = listOf(
            "barcode",
            "barCode",
            "code",
            "data",
            "scannerdata",
            "com.symbol.datawedge.data_string",
            "com.datalogic.decode.intent_data",
            "data_string",
            "dataBytes",
            "EXTRA_SCAN_DATA",
            "SCAN_BARCODE1",
            "SCAN_BARCODE",
            "scanner_data",
            "value",
        )

        fun String.toScannerProfile(): ScannerProfile {
            val parts = split("|", limit = 2)
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

        fun Intent.extractBarcode(extraKeys: List<String> = DEFAULT_EXTRA_KEYS): String =
            extraKeys.firstNotNullOfOrNull { key -> getStringExtra(key)?.takeIf(String::isNotBlank) }
                ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
    }
}

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
