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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appUpdateDataStore by preferencesDataStore(name = "fscm_app_update")

@Serializable
data class AppUpdateRelease(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("min_supported_version_code") val minSupportedVersionCode: Long = 0,
    @SerialName("release_notes") val releaseNotes: String = "",
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("checksum_sha256") val checksumSha256: String,
    @SerialName("download_path") val downloadPath: String,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class AppUpdateCheckResult(
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("force_update") val forceUpdate: Boolean = false,
    @SerialName("current_version_code") val currentVersionCode: Long = 0,
    val latest: AppUpdateRelease? = null,
)

data class PendingAppUpdateDownload(
    val downloadId: Long,
    val release: AppUpdateRelease,
    val forceUpdate: Boolean,
)

@Singleton
class AppUpdateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:FscmJson private val json: Json,
) {
    suspend fun shouldPrompt(release: AppUpdateRelease, forceUpdate: Boolean, manual: Boolean): Boolean {
        if (forceUpdate || manual) return true
        val preferences = context.appUpdateDataStore.data.first()
        val snoozedVersionCode = preferences[SNOOZED_VERSION_CODE] ?: 0
        val snoozedUntil = preferences[SNOOZED_UNTIL] ?: 0
        return shouldPromptForUpdate(
            releaseVersionCode = release.versionCode,
            forceUpdate = forceUpdate,
            manual = manual,
            snoozedVersionCode = snoozedVersionCode,
            snoozedUntil = snoozedUntil,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun snooze(release: AppUpdateRelease) {
        context.appUpdateDataStore.edit { preferences ->
            preferences[SNOOZED_VERSION_CODE] = release.versionCode
            preferences[SNOOZED_UNTIL] = System.currentTimeMillis() + SNOOZE_DURATION_MILLIS
        }
    }

    suspend fun savePending(download: PendingAppUpdateDownload) {
        context.appUpdateDataStore.edit { preferences ->
            preferences[DOWNLOAD_ID] = download.downloadId
            preferences[DOWNLOAD_RELEASE] = json.encodeToString(download.release)
            preferences[DOWNLOAD_FORCE_UPDATE] = if (download.forceUpdate) 1 else 0
        }
    }

    suspend fun pendingDownload(): PendingAppUpdateDownload? {
        val preferences = context.appUpdateDataStore.data.first()
        val downloadId = preferences[DOWNLOAD_ID]?.takeIf { it >= 0 } ?: return null
        val rawRelease = preferences[DOWNLOAD_RELEASE] ?: return null
        val release = runCatching { json.decodeFromString<AppUpdateRelease>(rawRelease) }.getOrNull() ?: return null
        return PendingAppUpdateDownload(
            downloadId = downloadId,
            release = release,
            forceUpdate = preferences[DOWNLOAD_FORCE_UPDATE] == 1L,
        )
    }

    suspend fun clearPending() {
        context.appUpdateDataStore.edit { preferences ->
            preferences.remove(DOWNLOAD_ID)
            preferences.remove(DOWNLOAD_RELEASE)
            preferences.remove(DOWNLOAD_FORCE_UPDATE)
        }
    }

    private companion object {
        val SNOOZED_VERSION_CODE = longPreferencesKey("snoozed_version_code")
        val SNOOZED_UNTIL = longPreferencesKey("snoozed_until")
        val DOWNLOAD_ID = longPreferencesKey("download_id")
        val DOWNLOAD_RELEASE = stringPreferencesKey("download_release")
        val DOWNLOAD_FORCE_UPDATE = longPreferencesKey("download_force_update")
        const val SNOOZE_DURATION_MILLIS = 24L * 60 * 60 * 1_000
    }
}

@Singleton
class AppUpdateRepository @Inject constructor(
    private val api: FscmApiClient,
    private val store: AppUpdateStore,
) {
    suspend fun check(): AppUpdateCheckResult = api.get(
        path = "app-downloads/check-update",
        params = mapOf(
            "platform" to "android",
            "package_name" to BuildConfig.APPLICATION_ID,
            "current_version_code" to BuildConfig.VERSION_CODE.toString(),
        ),
        auth = false,
    )

    suspend fun shouldPrompt(release: AppUpdateRelease, forceUpdate: Boolean, manual: Boolean): Boolean =
        store.shouldPrompt(release, forceUpdate, manual)

    suspend fun snooze(release: AppUpdateRelease) = store.snooze(release)
}

internal fun resolveUpdateDownloadUrl(baseUrl: String, downloadPath: String): String =
    URI(baseUrl).resolve(downloadPath).toString()

internal fun isAppUpdateAvailable(currentVersionCode: Long, latestVersionCode: Long): Boolean =
    latestVersionCode > currentVersionCode

internal fun isAppUpdateForced(
    currentVersionCode: Long,
    latestVersionCode: Long,
    minSupportedVersionCode: Long,
): Boolean = isAppUpdateAvailable(currentVersionCode, latestVersionCode) &&
    currentVersionCode < minSupportedVersionCode

internal fun shouldPromptForUpdate(
    releaseVersionCode: Long,
    forceUpdate: Boolean,
    manual: Boolean,
    snoozedVersionCode: Long,
    snoozedUntil: Long,
    now: Long,
): Boolean = forceUpdate || manual || snoozedVersionCode != releaseVersionCode || now >= snoozedUntil
