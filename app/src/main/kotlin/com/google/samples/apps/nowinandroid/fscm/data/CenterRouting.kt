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
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val Context.centerRoutesStore by preferencesDataStore(name = "center_routes")

@Serializable
internal data class CenterLine(val host: String, val ip: String, val country: String, val lan: Boolean, val networks: List<String> = emptyList())

@Serializable
internal data class CenterSnapshot(@SerialName("center_id") val id: String, val version: Long, val routes: List<CenterLine>)

@Serializable
private data class ManifestResponse(val code: Int, val data: CenterSnapshot)

@Serializable
private data class ProbeIdentity(@SerialName("center_id") val id: String)

@Serializable
private data class ProbeResponse(val code: Int, val data: ProbeIdentity)

private fun literalIP(ip: String): InetAddress {
    require(ip.matches(Regex("[0-9.]+|[0-9a-fA-F:]+")) && ('.' in ip || ':' in ip))
    return InetAddress.getByName(ip)
}
private class Attempt : EventListener() {
    var finished: (Call) -> Unit = {}
    override fun callEnd(call: Call) = finished(call)
    override fun callFailed(call: Call, ioe: IOException) = finished(call)

    @Volatile var sent = false
    override fun requestHeadersStart(call: Call) {
        sent = true
    }
}
private class LineClient(val client: OkHttpClient) {
    val active = AtomicInteger()
    val recovering = AtomicBoolean()

    @Volatile var retryAfter = 0L

    @Volatile var latency = 0L

    @Volatile var verified = false
}

@Singleton
class CenterRouting @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:FscmJson private val json: Json,
) : Interceptor {
    private val base = BuildConfig.BACKEND_URL.toHttpUrl()
    private val key = stringPreferencesKey(base.toString())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val children = ConcurrentHashMap<Call, Call>()
    val cancellation = object : EventListener() {
        override fun canceled(call: Call) {
            children.remove(call)?.cancel()
        }
    }
    private val direct = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor(PaymentAttachmentTimeouts)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.method !in setOf("GET", "HEAD") && request.tag(Attempt::class.java)?.sent == true) {
                throw java.net.ProtocolException("Write already dispatched")
            }
            chain.proceed(request)
        }
        .eventListenerFactory { it.request().tag(Attempt::class.java) ?: EventListener.NONE }
        .build()
    private val clients = ConcurrentHashMap<CenterLine, LineClient>()
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Volatile private var snapshot: CenterSnapshot? = null

    @Volatile private var currentNetwork: Network? = null

    @Volatile private var country = ""

    @Volatile private var due = 0L

    fun start() {
        scope.launch {
            try {
                context.centerRoutesStore.data.first()[key]?.let { snapshot = validate(json.decodeFromString<CenterSnapshot>(it)) }
            } catch (e: Exception) {
                Log.w(TAG, "Route cache unavailable", e)
            } finally {
                ready.complete(Unit)
            }
            while (true) {
                refresh()
                delay(30_000)
            }
        }
        val work = PeriodicWorkRequestBuilder<CenterRoutesWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("center-routes", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun refreshSoon() {
        scope.launch { refresh() }
    }

    @Synchronized
    private fun network(): Network? {
        val active = connectivity.activeNetwork
        if (currentNetwork != active) {
            currentNetwork = active
            clients.clear()
            country = ""
            due = 0
        }
        return active
    }

    private fun validate(s: CenterSnapshot): CenterSnapshot {
        require(s.id.isNotBlank() && s.version >= 0 && s.routes.size <= 64)
        s.routes.forEach {
            require(("https://" + it.host).toHttpUrl().host == it.host)
            literalIP(it.ip)
            require(!it.lan || it.networks.isNotEmpty())
            for (cidr in it.networks) {
                val address = literalIP(cidr.substringBefore('/'))
                require((cidr.substringAfter('/').toIntOrNull() ?: 0) in 1..address.address.size * 8)
            }
            require(it.country.matches(Regex("[A-Z]{2}")))
        }
        return s
    }

    internal suspend fun refresh(): Boolean {
        ready.await()
        return mutex.withLock {
            val net = network() ?: return@withLock false
            if (SystemClock.elapsedRealtime() < due) return@withLock true
            val old = snapshot
            var request = Request.Builder().url(base.resolve("mobile/center-routes")!!)
            if (old != null) request = request.header("If-None-Match", "\"${old.id}-${old.version}\"")
            try {
                route(request.build(), old, net, null).use { r ->
                    if (r.code == 200) {
                        require(base.isHttps || BuildConfig.DEBUG)
                        val reply = json.decodeFromString<ManifestResponse>(r.body!!.string())
                        val next = validate(reply.data)
                        require(reply.code == 0 && (old == null || old.id == next.id && old.version <= next.version))
                        context.centerRoutesStore.edit { it[key] = json.encodeToString(CenterSnapshot.serializer(), next) }
                        snapshot = next
                        clients.keys.retainAll(next.routes.toSet())
                    } else if (r.code == 404 && old == null) {
                        due = SystemClock.elapsedRealtime() + 600_000
                        return@withLock true
                    } else if (r.code != 304 || old == null) {
                        throw IOException("Manifest HTTP ${r.code}")
                    }
                    if (net == network()) {
                        country = r.header("X-FSCM-Country").orEmpty()
                        due = SystemClock.elapsedRealtime() + Random.nextLong(600_000, 720_001)
                    }
                    Log.i(TAG, "Confirmed version=${snapshot?.version} routes=${snapshot?.routes?.size}")
                }
                true
            } catch (e: Exception) {
                due = SystemClock.elapsedRealtime() + 30_000
                Log.w(TAG, "Route sync deferred: ${e.message}")
                false
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != base.host || req.url.port != base.port || req.url.scheme != base.scheme) return chain.proceed(req)
        runBlocking { ready.await() }
        val net = network()
        val s = snapshot
        val response = if (net == null || s == null || s.routes.isEmpty()) chain.proceed(req) else route(req, s, net, chain.call())
        if (response.isSuccessful && SystemClock.elapsedRealtime() >= due) refreshSoon()
        return response
    }

    private fun client(line: CenterLine, net: Network): LineClient = synchronized(this) {
        if (currentNetwork != net) throw IOException("Network changed")
        clients.getOrPut(line) {
            val ip = literalIP(line.ip)
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != line.host) throw java.net.UnknownHostException(hostname)
                    return listOf(ip)
                }
            }
            LineClient(
                direct.newBuilder().dns(dns).proxy(Proxy.NO_PROXY).socketFactory(net.socketFactory)
                    .connectionPool(ConnectionPool()).connectTimeout(2, TimeUnit.SECONDS).build(),
            )
        }
    }

    private fun eligible(line: CenterLine, net: Network): Boolean {
        if (!line.lan) return true
        val links = connectivity.getLinkProperties(net)?.linkAddresses.orEmpty()
        return line.networks.any { cidr ->
            val bits = cidr.substringAfter('/').toIntOrNull() ?: return@any false
            val address = literalIP(cidr.substringBefore('/')).address
            links.any { sameEdgeSubnet(it.address.address, address, bits) }
        }
    }

    private fun route(req: Request, s: CenterSnapshot?, net: Network, outer: Call?): Response {
        val caps = connectivity.getNetworkCapabilities(net)
        val region = country.ifBlank {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                context.getSystemService(TelephonyManager::class.java)?.networkCountryIso.orEmpty().uppercase()
            } else {
                ""
            }
        }
        val start = SystemClock.elapsedRealtime()
        val candidates = s?.routes.orEmpty().filter { eligible(it, net) }.shuffled()
        val ranked = candidates.groupBy {
            if (it.lan) {
                0
            } else if (it.country == region) {
                1
            } else {
                2
            }
        }.toSortedMap()
        val failedIPs = mutableSetOf<String>()
        for (tier in ranked.values) {
            val remaining = tier.toMutableList()
            while (remaining.isNotEmpty() && SystemClock.elapsedRealtime() - start < 8_000) {
                val preferred = remaining.filter { it.ip !in failedIPs }.ifEmpty { remaining }
                val line = preferred.take(2).minWith(compareBy({ client(it, net).active.get() }, { client(it, net).latency }))
                remaining.remove(line)
                val state = client(line, net)
                if (state.retryAfter > SystemClock.elapsedRealtime()) continue
                val recovering = state.retryAfter != 0L
                if (recovering && !state.recovering.compareAndSet(false, true)) continue
                val attempt = Attempt()
                val began = SystemClock.elapsedRealtime()
                val target = req.url.newBuilder().scheme("https").host(line.host).port(443).build()
                state.active.incrementAndGet()
                try {
                    if (line.lan && !state.verified) {
                        synchronized(state) {
                            if (!state.verified) {
                                val probe = Request.Builder().url(target.newBuilder().encodedPath("/api/mobile/center-probe").query(null).build()).build()
                                exchange(state.client, probe, outer, 4_000).use {
                                    val identity = if (it.code == 200) runCatching { json.decodeFromString<ProbeResponse>(it.body!!.string()) }.getOrNull() else null
                                    if (identity?.code != 0 || identity.data.id != s?.id) throw IOException("Center identity mismatch")
                                }
                                state.verified = true
                            }
                        }
                    }
                    val routed = req.newBuilder().url(target).tag(Attempt::class.java, attempt).build()
                    val res = exchange(state.client, routed, outer, if (outer == null) 4_000 else 0)
                    if (res.code !in setOf(502, 503, 504)) {
                        state.retryAfter = 0
                        state.latency = SystemClock.elapsedRealtime() - began
                        Log.d(TAG, "Route ${line.host}/${line.ip} version=${s?.version} elapsedMs=${state.latency}")
                        return res
                    }
                    state.retryAfter = SystemClock.elapsedRealtime() + 30_000
                    if (req.method !in setOf("GET", "HEAD")) return res
                    res.close()
                } catch (e: IOException) {
                    if (outer?.isCanceled() == true) throw e
                    state.retryAfter = SystemClock.elapsedRealtime() + 30_000
                    failedIPs.add(line.ip)
                    Log.w(TAG, "Route unavailable ${line.host}/${line.ip} version=${s?.version} elapsedMs=${SystemClock.elapsedRealtime() - began}")
                    if (attempt.sent && req.method !in setOf("GET", "HEAD")) throw e
                } finally {
                    if (recovering) state.recovering.set(false)
                    state.active.decrementAndGet()
                }
            }
        }
        return exchange(direct, req, outer, if (outer == null) 4_000 else 0)
    }

    private fun exchange(client: OkHttpClient, req: Request, outer: Call?, timeoutMs: Long): Response {
        val attempt = req.tag(Attempt::class.java) ?: Attempt()
        val call = client.newCall(req.newBuilder().tag(Attempt::class.java, attempt).build())
        if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        if (outer != null) {
            attempt.finished = { completed -> children.remove(outer, completed) }
            children[outer] = call
            if (outer.isCanceled()) call.cancel()
        }
        return call.execute()
    }

    private companion object {
        const val TAG = "FscmCenterRoutes"
    }
}

@HiltWorker
class CenterRoutesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val routes: CenterRouting,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (routes.refresh()) Result.success() else Result.retry()
}
