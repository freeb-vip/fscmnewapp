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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class EdgeTicketResponse(
    val token: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
private data class StoredEdgeTicket(
    val token: String,
    val namespaceId: Long,
    val nodeId: String,
    val expiresAtMillis: Long,
)

@Singleton
class EdgeTicketProvider @Inject constructor(
    private val api: FscmApiClient,
    private val sessionStore: SessionStore,
    private val sessionCipher: SessionCipher,
    @param:ApplicationContext private val context: Context,
    @param:FscmJson private val json: Json,
) {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, StoredEdgeTicket>()

    suspend fun ticket(nodeId: String): String? = mutex.withLock {
        val session = sessionStore.current()
        val namespaceId = session.currentNamespace?.id ?: return@withLock null
        val normalizedNodeId = nodeId.trim().takeIf(String::isNotBlank) ?: return@withLock null
        val key = ticketKey(namespaceId, normalizedNodeId)
        val now = System.currentTimeMillis()
        val cached = memory[key] ?: read(key)?.also { memory[key] = it }
        if (cached != null && isEdgeTicketUsable(cached.expiresAtMillis, now, REFRESH_MARGIN_MS)) {
            return@withLock cached.token
        }
        if (!session.isOffline && session.token.isNotBlank()) {
            val refreshed = runCatching {
                api.post<EdgeTicketResponse>(
                    path = "edge/catalog-ticket",
                    headers = mapOf("X-FSCM-Edge-Node-ID" to normalizedNodeId),
                )
            }.getOrNull()?.takeIf { it.token.isNotBlank() }?.let { response ->
                StoredEdgeTicket(
                    token = response.token,
                    namespaceId = namespaceId,
                    nodeId = normalizedNodeId,
                    expiresAtMillis = response.expiresAt.toEpochMillisOrNull()
                        ?: now + DEFAULT_TTL_MS,
                )
            }
            if (refreshed != null) {
                memory[key] = refreshed
                write(key, refreshed)
                return@withLock refreshed.token
            }
        }
        cached?.takeIf { isEdgeTicketUsable(it.expiresAtMillis, now) }?.token
    }

    suspend fun clear() = mutex.withLock {
        memory.clear()
        preferences().edit().clear().apply()
    }

    suspend fun invalidate(nodeId: String) = mutex.withLock {
        val namespaceId = sessionStore.current().currentNamespace?.id ?: return@withLock
        val key = ticketKey(namespaceId, nodeId.trim())
        memory.remove(key)
        preferences().edit().remove(key).apply()
    }

    private fun read(key: String): StoredEdgeTicket? {
        val envelope = preferences().getString(key, null) ?: return null
        val plaintext = sessionCipher.open(envelope, aad(key)) ?: return null
        return runCatching { json.decodeFromString<StoredEdgeTicket>(plaintext) }.getOrNull()
    }

    private fun write(key: String, ticket: StoredEdgeTicket) {
        val envelope = sessionCipher.seal(json.encodeToString(ticket), aad(key))
        preferences().edit().putString(key, envelope).apply()
    }

    private fun preferences() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ticketKey(namespaceId: Long, nodeId: String) = "$namespaceId:$nodeId"

    private fun aad(key: String) = "fscm-edge-ticket:v1:$key"

    private fun String.toEpochMillisOrNull(): Long? =
        takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val PREFS_NAME = "fscm_edge_terminal_tickets"
        const val REFRESH_MARGIN_MS = 10L * 60L * 1000L
        const val DEFAULT_TTL_MS = 7L * 60L * 60L * 1000L
    }
}

internal fun isEdgeTicketUsable(
    expiresAtMillis: Long,
    nowMillis: Long,
    refreshMarginMillis: Long = 0,
): Boolean = expiresAtMillis > nowMillis + refreshMarginMillis
