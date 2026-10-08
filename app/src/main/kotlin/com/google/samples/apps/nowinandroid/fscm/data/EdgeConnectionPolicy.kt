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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

internal fun sameEdgeSubnet(local: ByteArray, remote: ByteArray, prefix: Int): Boolean {
    if (local.size != remote.size || prefix !in 1..local.size * 8) return false
    return local.indices.all { index ->
        val bits = (prefix - index * 8).coerceIn(0, 8)
        val mask = (0xff shl (8 - bits)) and 0xff
        (local[index].toInt() and mask) == (remote[index].toInt() and mask)
    }
}

/** One registry and network boundary for all automatic edge consumers. */
@Singleton
class EdgeConnectionPolicy @Inject constructor(
    @ApplicationContext context: Context,
    private val api: FscmApiClient,
    private val sessions: SessionStore,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val registryCache = EdgeRegistryCache()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private var networkKey = ""

    @Volatile private var lostNetwork: Network? = null
    private val manualNodes = EdgeManualNodes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            var previous = ""
            sessions.session.collect { session ->
                val key = "${session.user?.id}:${session.currentNamespace?.id}:${session.sessionMode}:${session.isLoggedIn}"
                if (key != previous) {
                    synchronized(this@EdgeConnectionPolicy) {
                        registryCache.invalidate()
                        manualNodes.clear()
                        revision.value += 1
                    }
                    previous = key
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == lostNetwork) lostNetwork = null
                networkChanged()
            }
            override fun onLost(network: Network) {
                lostNetwork = network
                networkChanged()
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networkChanged()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = networkChanged()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }
    }

    private fun networkChanged() {
        val next = networkIdentity()
        synchronized(this) {
            if (next != networkKey) {
                networkKey = next
                manualNodes.clear()
                revision.value += 1
            }
        }
    }

    private fun networkIdentity(): String {
        val network = connectivity.activeNetwork?.takeUnless { it == lostNetwork } ?: return "none"
        val capabilities = connectivity.getNetworkCapabilities(network)
        val properties = connectivity.getLinkProperties(network)
        return "$network:${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}:${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}:${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}:${properties?.linkAddresses}"
    }

    suspend fun scopeKey(): String {
        val session = sessions.current()
        return "${session.user?.id}:${session.currentNamespace?.id}:${session.sessionMode}:${session.isLoggedIn}:${networkIdentity()}"
    }

    fun hasLan(): Boolean {
        val network = connectivity.activeNetwork?.takeUnless { it == lostNetwork } ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return allowsEdgeLan(
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    fun isOnLan(baseUrl: String): Boolean {
        if (!hasLan()) return false
        // Hostnames are deliberately not resolved automatically: an unknown address requires manual discovery.
        val address = literalEdgeAddress(baseUrl) ?: return false
        return connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty().any {
            !it.address.isLoopbackAddress && sameEdgeSubnet(it.address.address, address.address, it.prefixLength)
        }
    }

    suspend fun registeredNodes(force: Boolean = false): List<EdgeNode> {
        val key = scopeKey()
        val session = sessions.current()
        val nodes = try {
            registryCache.get(key, android.os.SystemClock.elapsedRealtime(), force) {
                if (!session.isLoggedIn || session.isOffline || session.currentNamespace == null) {
                    emptyList()
                } else {
                    api.get<PagedList<EdgeNode>>("edge/nodes/available", edgeNodeListParams(includeOffline = true)).rows()
                }
            }
        } catch (error: Exception) {
            if (force && error !is CancellationException) synchronized(this) { revision.value += 1 }
            throw error
        }
        if (key != scopeKey()) return emptyList()
        if (force) synchronized(this) { revision.value += 1 }
        return nodes
    }

    suspend fun candidates(): List<EdgeNode> {
        val registered = try {
            registeredNodes()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
        val key = scopeKey()
        val manual = manualNodes.get(key)
        return (manual.filter { isOnLan(it.lanBaseUrl) } + registered.filter { isOnLan(it.lanBaseUrl) }).filter { it.nodeId.isNotBlank() }.distinctBy { it.nodeId }
    }

    suspend fun permits(node: EdgeNode): Boolean = candidates().any {
        it.nodeId == node.nodeId && localEdgeDirectBaseUrl(it.lanBaseUrl) == localEdgeDirectBaseUrl(node.lanBaseUrl)
    }

    suspend fun rememberManual(nodes: List<EdgeNode>, key: String) {
        val currentKey = scopeKey()
        synchronized(this) {
            if (key == currentKey && hasLan()) {
                manualNodes.remember(key, nodes)
                revision.value += 1
            }
        }
    }

    companion object {
        const val CACHE_MS = 300_000L
    }
}

/** Cache failures and empty registries too, so independent consumers cannot create retry storms. */
internal class EdgeRegistryCache {
    private val mutex = Mutex()

    @Volatile private var generation = 0L
    private var cachedGeneration = -1L
    private var key = ""
    private var expiresAt = 0L
    private var nodes = emptyList<EdgeNode>()

    fun invalidate() {
        generation += 1
    }

    suspend fun get(scope: String, now: Long, force: Boolean = false, fetch: suspend () -> List<EdgeNode>): List<EdgeNode> = mutex.withLock {
        if (!force && key == scope && cachedGeneration == generation && now < expiresAt) return@withLock nodes
        val startedGeneration = generation
        val fetched = try {
            fetch()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            nodes = emptyList()
            key = scope
            cachedGeneration = startedGeneration
            expiresAt = now + EdgeConnectionPolicy.CACHE_MS
            throw error
        }
        if (startedGeneration != generation) return@withLock emptyList()
        nodes = fetched
        key = scope
        cachedGeneration = startedGeneration
        expiresAt = now + EdgeConnectionPolicy.CACHE_MS
        nodes
    }
}

internal fun allowsEdgeLan(wifi: Boolean, ethernet: Boolean, vpn: Boolean): Boolean = !vpn && (wifi || ethernet)

internal class EdgeManualNodes {
    private var key = ""
    private var nodes = emptyList<EdgeNode>()

    @Synchronized fun remember(scope: String, discovered: List<EdgeNode>) {
        key = scope
        nodes = discovered
    }

    @Synchronized fun get(scope: String): List<EdgeNode> = if (key == scope) nodes else emptyList()

    @Synchronized fun clear() {
        key = ""
        nodes = emptyList()
    }
}

internal fun literalEdgeAddress(baseUrl: String): InetAddress? {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https")) return null
    val host = uri.host?.removeSurrounding("[", "]") ?: return null
    if (!host.contains(':') && !host.matches(Regex("[0-9.]+"))) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
        ?.takeUnless { it.isLoopbackAddress || it.isAnyLocalAddress || it.isMulticastAddress }
}
