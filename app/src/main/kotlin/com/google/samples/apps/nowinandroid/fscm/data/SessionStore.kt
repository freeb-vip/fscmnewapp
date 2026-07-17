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
    private val sessionCipher: SessionCipher,
    private val deviceIdentityManager: DeviceIdentityManager,
    private val offlineLeaseVerifier: OfflineLeaseVerifier,
    private val trustedClock: TrustedClock,
) {
    private val dataStore = context.fscmSessionDataStore

    val session: Flow<SessionState> = dataStore.data.map { preferences ->
        preferences[SESSION_KEY]?.let(::decodeSession) ?: SessionState()
    }

    suspend fun current(): SessionState {
        val raw = dataStore.data.first()[SESSION_KEY] ?: return SessionState()
        val decoded = decodeSession(raw)
        if (!raw.startsWith(ENCRYPTED_SESSION_PREFIX) && decoded != SessionState()) save(decoded)
        return decoded
    }

    suspend fun applyAuth(
        payload: AuthPayload,
        preserveNamespace: Boolean = false,
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
        trustedClock.recordTrustedTime(payload.serverTime)
        val device = deviceIdentityManager.registration()
        val leaseToken = payload.offlineLease.ifBlank {
            if (preserveNamespace) current.offlineLease else ""
        }
        val leaseKey = payload.offlineLeasePublicKey.ifBlank {
            if (preserveNamespace) current.offlineLeasePublicKey else ""
        }
        val verifiedLease = verifyLease(leaseToken, leaseKey, namespace?.id ?: 0)
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
                connectivityMode = ConnectivityMode.CenterOnline,
                deviceId = device.deviceId,
                deviceKeyAlgorithm = device.keyAlgorithm,
                deviceSessionId = payload.deviceSessionId.ifBlank { current.deviceSessionId },
                accessExpiresAt = payload.accessExpiresAt.takeIf { it > 0 }
                    ?: payload.expiresIn.takeIf { it > 0 }?.let { trustedNow() + it * 1000 }
                    ?: current.accessExpiresAt,
                refreshExpiresAt = payload.refreshExpiresAt.takeIf { it > 0 } ?: current.refreshExpiresAt,
                offlineLease = verifiedLease?.let { leaseToken }.orEmpty(),
                offlineLeaseId = verifiedLease?.leaseId ?: payload.offlineLeaseId,
                offlineLeasePublicKey = verifiedLease?.let { leaseKey }.orEmpty(),
                offlineLeaseExpiresAt = verifiedLease?.expiresAt ?: 0,
                offlineScopes = verifiedLease?.scopes ?: payload.offlineScopes,
                lastOnlineVerifiedAt = trustedNow(),
            ),
        )
    }

    suspend fun replaceNamespace(namespace: NamespaceProfile?) {
        val current = current()
        save(current.copy(currentNamespace = namespace))
    }

    suspend fun applyRefresh(payload: AuthPayload) {
        val current = current()
        trustedClock.recordTrustedTime(payload.serverTime)
        val namespaceId = current.currentNamespace?.id ?: 0
        val leaseToken = payload.offlineLease.ifBlank { current.offlineLease }
        val leaseKey = payload.offlineLeasePublicKey.ifBlank { current.offlineLeasePublicKey }
        val verifiedLease = verifyLease(leaseToken, leaseKey, namespaceId)
        save(
            current.copy(
                token = payload.token.ifBlank { current.token },
                refreshToken = payload.refreshToken.ifBlank { current.refreshToken },
                sessionMode = SessionMode.Online,
                connectivityMode = ConnectivityMode.CenterOnline,
                deviceSessionId = payload.deviceSessionId.ifBlank { current.deviceSessionId },
                accessExpiresAt = payload.accessExpiresAt.takeIf { it > 0 }
                    ?: payload.expiresIn.takeIf { it > 0 }?.let { trustedNow() + it * 1000 }
                    ?: current.accessExpiresAt,
                refreshExpiresAt = payload.refreshExpiresAt.takeIf { it > 0 } ?: current.refreshExpiresAt,
                offlineLease = verifiedLease?.let { leaseToken }.orEmpty(),
                offlineLeaseId = verifiedLease?.leaseId ?: payload.offlineLeaseId.ifBlank { current.offlineLeaseId },
                offlineLeasePublicKey = verifiedLease?.let { leaseKey }.orEmpty(),
                offlineLeaseExpiresAt = verifiedLease?.expiresAt ?: 0,
                offlineScopes = verifiedLease?.scopes ?: current.offlineScopes,
                lastOnlineVerifiedAt = trustedNow(),
            ),
        )
    }

    suspend fun replaceTokens(token: String, refreshToken: String) {
        applyRefresh(AuthPayload(token = token, refreshToken = refreshToken))
    }

    suspend fun markOnlineVerified() {
        save(
            current().copy(
                sessionMode = SessionMode.Online,
                connectivityMode = ConnectivityMode.CenterOnline,
                lastOnlineVerifiedAt = trustedNow(),
            ),
        )
    }

    suspend fun tryOfflineLogin(username: String, password: String): Boolean = false

    suspend fun hasValidOfflineCredential(): Boolean {
        val current = current()
        return verifyCurrentLease(current) != null
    }

    suspend fun enterOfflineMode(): Boolean {
        val current = current()
        if (current.user == null || verifyCurrentLease(current) == null) return false
        save(
            current.copy(
                sessionMode = SessionMode.Offline,
                connectivityMode = ConnectivityMode.LocalOnly,
            ),
        )
        return true
    }

    suspend fun markEdgeReachable(reachable: Boolean) {
        val current = current()
        if (!current.isOffline) return
        save(
            current.copy(
                connectivityMode = if (reachable) ConnectivityMode.EdgeOnly else ConnectivityMode.LocalOnly,
            ),
        )
    }

    suspend fun validateActiveOfflineSession(): Boolean {
        val current = current()
        if (!current.isOffline) return true
        if (verifyCurrentLease(current) != null) return true
        clear()
        return false
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SESSION_KEY) }
    }

    private suspend fun save(session: SessionState) {
        dataStore.edit { preferences ->
            val userId = session.user?.id ?: 0
            val namespaceId = session.currentNamespace?.id ?: 0
            val associatedData = sessionAssociatedData(userId, namespaceId)
            val sealed = sessionCipher.seal(json.encodeToString(session), associatedData)
            preferences[SESSION_KEY] = "$ENCRYPTED_SESSION_PREFIX$userId|$namespaceId|$sealed"
        }
    }

    private fun decodeSession(raw: String): SessionState {
        if (!raw.startsWith(ENCRYPTED_SESSION_PREFIX)) {
            return runCatching { json.decodeFromString<SessionState>(raw) }.getOrDefault(SessionState())
        }
        val parts = raw.removePrefix(ENCRYPTED_SESSION_PREFIX).split('|', limit = 3)
        if (parts.size != 3) return SessionState()
        val userId = parts[0].toLongOrNull() ?: return SessionState()
        val namespaceId = parts[1].toLongOrNull() ?: return SessionState()
        val plaintext = sessionCipher.open(parts[2], sessionAssociatedData(userId, namespaceId)) ?: return SessionState()
        return runCatching { json.decodeFromString<SessionState>(plaintext) }
            .getOrDefault(SessionState())
            .takeIf { it.user?.id == userId && (it.currentNamespace?.id ?: 0) == namespaceId }
            ?: SessionState()
    }

    private fun verifyCurrentLease(session: SessionState): VerifiedOfflineLease? {
        val now = trustedClock.trustedNowMillis() ?: return null
        val verified = verifyLease(
            token = session.offlineLease,
            publicKey = session.offlineLeasePublicKey,
            namespaceId = session.currentNamespace?.id ?: 0,
            now = now,
        ) ?: return null
        return verified.takeIf { it.subject == session.user?.id?.toString() }
    }

    private fun verifyLease(
        token: String,
        publicKey: String,
        namespaceId: Long,
        now: Long = trustedNow(),
    ): VerifiedOfflineLease? {
        if (token.isBlank() || publicKey.isBlank() || namespaceId <= 0) return null
        return offlineLeaseVerifier.verify(
            token = token,
            encodedPublicKey = publicKey,
            expectedDeviceId = deviceIdentityManager.deviceId(),
            expectedNamespaceId = namespaceId,
            nowMillis = now,
        )
    }

    private fun trustedNow(): Long = trustedClock.trustedNowMillis() ?: System.currentTimeMillis()

    private fun sessionAssociatedData(userId: Long, namespaceId: Long): String =
        "fscm-session:v2:${deviceIdentityManager.deviceId()}:$userId:$namespaceId"

    private companion object {
        val SESSION_KEY = stringPreferencesKey("session")
        const val ENCRYPTED_SESSION_PREFIX = "enc2|"
    }
}
