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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.fscmSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fscm_session",
)

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
    @param:FscmJson
    private val json: Json,
    private val offlineCredentialManager: OfflineCredentialManager,
) {
    private val dataStore = context.fscmSessionDataStore

    val session: Flow<SessionState> = dataStore.data.map { preferences ->
        preferences[SESSION_KEY]?.let { raw ->
            runCatching { json.decodeFromString<SessionState>(raw) }.getOrDefault(SessionState())
        } ?: SessionState()
    }

    suspend fun current(): SessionState = session.first()

    suspend fun applyAuth(
        payload: AuthPayload,
        preserveNamespace: Boolean = false,
        username: String = "",
        password: String = "",
    ) {
        val current = current()
        val namespace = when {
            preserveNamespace && current.currentNamespace != null ->
                payload.namespaces.firstOrNull { it.id == current.currentNamespace.id }
                    ?: payload.currentNamespace
                    ?: payload.namespaces.firstOrNull()
            payload.currentNamespace != null -> payload.currentNamespace
            else -> payload.namespaces.firstOrNull()
        }
        val credentialSession = offlineCredential(username, password, current)
        save(
            SessionState(
                token = payload.token.ifBlank { current.token },
                refreshToken = payload.refreshToken.ifBlank { current.refreshToken },
                user = payload.user,
                permissions = payload.permissions,
                effectiveRole = payload.effectiveRole.ifBlank { payload.user.role },
                namespaces = payload.namespaces,
                currentNamespace = namespace,
                sessionMode = SessionMode.Online,
                offlineCredentialId = credentialSession.offlineCredentialId,
                offlineUsername = credentialSession.offlineUsername,
                offlinePasswordSalt = credentialSession.offlinePasswordSalt,
                offlinePasswordVerifier = credentialSession.offlinePasswordVerifier,
                offlineIssuedAt = credentialSession.offlineIssuedAt,
                offlineExpiresAt = credentialSession.offlineExpiresAt,
                lastOnlineVerifiedAt = System.currentTimeMillis(),
                offlineSessionExpiresAt = System.currentTimeMillis() + OFFLINE_SESSION_TTL_MS,
            ),
        )
    }

    suspend fun replaceNamespace(namespace: NamespaceProfile?) {
        val current = current()
        save(current.copy(currentNamespace = namespace))
    }

    suspend fun replaceTokens(token: String, refreshToken: String) {
        val current = current()
        save(
            current.renewOfflineWindow().copy(
                token = token,
                refreshToken = refreshToken,
                sessionMode = SessionMode.Online,
                lastOnlineVerifiedAt = System.currentTimeMillis(),
                offlineSessionExpiresAt = System.currentTimeMillis() + OFFLINE_SESSION_TTL_MS,
            ),
        )
    }

    suspend fun markOnlineVerified() {
        save(
            current().renewOfflineWindow().copy(
                sessionMode = SessionMode.Online,
                lastOnlineVerifiedAt = System.currentTimeMillis(),
                offlineSessionExpiresAt = System.currentTimeMillis() + OFFLINE_SESSION_TTL_MS,
            ),
        )
    }

    suspend fun tryOfflineLogin(username: String, password: String): Boolean {
        val current = current()
        if (!offlineCredentialManager.verify(current, username, password)) return false
        save(current.copy(sessionMode = SessionMode.Offline))
        return true
    }

    suspend fun hasValidOfflineCredential(): Boolean {
        return current().hasUsableOfflineCredential
    }

    suspend fun enterOfflineMode(): Boolean {
        val current = current()
        if (!current.hasValidOfflineSession || current.user == null) return false
        save(current.copy(sessionMode = SessionMode.Offline))
        return true
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SESSION_KEY) }
    }

    private fun offlineCredential(
        username: String,
        password: String,
        current: SessionState,
    ): SessionState {
        if (username.isBlank() || password.isBlank()) return current.renewOfflineWindow()
        val credential = offlineCredentialManager.create(username, password, OFFLINE_SESSION_TTL_MS)
        return current.copy(
            offlineCredentialId = credential.credentialId,
            offlineUsername = credential.username,
            offlinePasswordSalt = credential.salt,
            offlinePasswordVerifier = credential.verifier,
            offlineIssuedAt = credential.issuedAt,
            offlineExpiresAt = credential.expiresAt,
        )
    }

    private fun SessionState.renewOfflineWindow(): SessionState {
        if (offlineCredentialId.isBlank()) return this
        return copy(
            offlineExpiresAt = System.currentTimeMillis() + OFFLINE_SESSION_TTL_MS,
            offlineSessionExpiresAt = System.currentTimeMillis() + OFFLINE_SESSION_TTL_MS,
        )
    }

    private suspend fun save(session: SessionState) {
        dataStore.edit { preferences ->
            preferences[SESSION_KEY] = json.encodeToString(session)
        }
    }

    private companion object {
        val SESSION_KEY = stringPreferencesKey("session")
        const val OFFLINE_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
