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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class EdgePrintLease(val token: String, @SerialName("expires_at") val expiresAt: Long)

/** A separate, narrowly scoped node lease; it never grants offline login or business writes. */
@Singleton
class EdgePrintLeaseStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val identity: DeviceIdentityManager,
    private val cipher: SessionCipher,
    private val clock: TrustedClock,
    @param:FscmJson private val json: Json,
) {
    suspend fun beginAttempt(labelId: Long, destination: String, payload: String, suggestedKey: String): String = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        synchronized(this@EdgePrintLeaseStore) {
            val key = "attempt:${session.user?.id}:${session.currentNamespace?.id}:$labelId"
            val prefs = context.getSharedPreferences("fscm_edge_print_leases", Context.MODE_PRIVATE)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
            val fingerprint = "$destination:$digest"
            val previous = prefs.getString(key, null)?.let { cipher.open(it, key) }?.split('\n', limit = 2)
            if (previous != null) {
                check(previous.size == 2 && previous[0] == fingerprint) { "上次打印结果尚未确认，请使用原节点、模板和份数重试，避免重复打印" }
                return@synchronized previous[1]
            }
            check(prefs.edit().putString(key, cipher.seal("$fingerprint\n$suggestedKey", key)).commit())
            suggestedKey
        }
    }

    suspend fun pendingKey(labelId: Long, nodeId: String, channel: String): String? {
        val session = sessionStore.current()
        val key = "attempt:${session.user?.id}:${session.currentNamespace?.id}:$labelId"
        val value = context.getSharedPreferences("fscm_edge_print_leases", Context.MODE_PRIVATE)
            .getString(key, null)?.let { cipher.open(it, key) }?.split('\n', limit = 2) ?: return null
        check(value.size == 2) { "上次打印记录损坏，请在原节点核对任务" }
        if (!value[0].startsWith("$nodeId:$channel:")) return null
        return value[1]
    }

    suspend fun finishAttempt(labelId: Long, expectedKey: String) = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        val key = "attempt:${session.user?.id}:${session.currentNamespace?.id}:$labelId"
        synchronized(this@EdgePrintLeaseStore) {
            val prefs = context.getSharedPreferences("fscm_edge_print_leases", Context.MODE_PRIVATE)
            val saved = prefs.getString(key, null)?.let { cipher.open(it, key) }?.substringAfter('\n')
            if (saved == expectedKey) check(prefs.edit().remove(key).commit())
        }
    }

    suspend fun lease(nodeId: String): String? = withContext(Dispatchers.IO) {
        val session = sessionStore.current()
        val now = clock.trustedNowMillis() ?: return@withContext null
        val key = "${session.user?.id}:${session.currentNamespace?.id}:$nodeId:${identity.deviceId()}"
        val prefs = context.getSharedPreferences("fscm_edge_print_leases", Context.MODE_PRIVATE)
        val saved = prefs.getString(key, null)?.let { cipher.open(it, key) }
            ?.let { runCatching { json.decodeFromString<EdgePrintLease>(it) }.getOrNull() }
        if (saved != null && saved.expiresAt > now + 300_000) return@withContext saved.token
        if (!session.isOffline) {
            try {
                val registration = identity.registration()
                val result = api.post<EdgePrintLease>(
                    "edge/print-lease",
                    buildJsonObject {
                        put("node_id", nodeId)
                        put("device_id", registration.deviceId)
                        put("public_key", registration.publicKey)
                    },
                )
                check(prefs.edit().putString(key, cipher.seal(json.encodeToString(result), key)).commit())
                return@withContext result.token
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is FscmApiException && error.statusCode in listOf(401, 403)) {
                    prefs.edit().remove(key).commit()
                    throw error
                }
            }
        }
        saved?.takeIf { it.expiresAt > now }?.token
    }
}
