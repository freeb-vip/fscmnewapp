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

package com.google.samples.apps.nowinandroid.fscm.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class EdgeServiceDiscovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun discoverBaseUrls(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): List<String> =
        suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val handler = Handler(Looper.getMainLooper())
            val discovered = linkedSetOf<String>()
            val pending = ArrayDeque<NsdServiceInfo>()
            var resolving = false
            var stopped = false

            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun finish() {
                if (stopped) return
                stopped = true
                runCatching { manager.stopServiceDiscovery(discoveryListener) }
                val merged = (discovered + cachedBaseUrls()).distinct()
                if (discovered.isNotEmpty()) cacheBaseUrls(discovered)
                if (continuation.isActive) continuation.resume(merged)
            }

            fun resolveNext() {
                if (resolving || pending.isEmpty() || stopped) return
                resolving = true
                val service = pending.removeFirst()
                @Suppress("DEPRECATION")
                manager.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving = false
                            resolveNext()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val advertisedBaseUrl = serviceInfo.attributes["lan_base_url"]
                                ?.decodeToString()
                                ?.trim()
                                ?.trimEnd('/')
                                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            val address = serviceInfo.host?.hostAddress
                            if (advertisedBaseUrl != null) {
                                discovered += advertisedBaseUrl
                            } else if (!address.isNullOrBlank() && serviceInfo.port in 1..65535) {
                                val scheme = serviceInfo.attributes["scheme"]
                                    ?.decodeToString()
                                    ?.takeIf { it == "http" || it == "https" }
                                    ?: "http"
                                val host = if (address.contains(':')) "[$address]" else address
                                discovered += "$scheme://$host:${serviceInfo.port}"
                            }
                            resolving = false
                            resolveNext()
                        }
                    },
                )
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.startsWith(SERVICE_TYPE)) {
                        pending += serviceInfo
                        resolveNext()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish()

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = finish()
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                if (!stopped) runCatching { manager.stopServiceDiscovery(discoveryListener) }
            }
            handler.postDelayed(::finish, timeoutMillis)
            runCatching {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure { finish() }
        }

    fun cachedBaseUrls(): List<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_BASE_URLS, emptySet())
            .orEmpty()
            .toList()

    private fun cacheBaseUrls(urls: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BASE_URLS, urls.take(MAX_CACHED_NODES).toSet())
            .apply()
    }

    private companion object {
        const val SERVICE_TYPE = "_fscm-edge._tcp."
        const val DEFAULT_TIMEOUT_MS = 1_800L
        const val MAX_CACHED_NODES = 20
        const val PREFS_NAME = "fscm_edge_discovery"
        const val KEY_BASE_URLS = "base_urls"
    }
}
