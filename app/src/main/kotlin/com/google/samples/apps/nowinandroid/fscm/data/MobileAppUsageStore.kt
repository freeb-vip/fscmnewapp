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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.fscmMobileAppUsageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fscm_mobile_app_usage",
)

@Serializable
data class MobileAppUsage(
    val counts: Map<String, Int> = emptyMap(),
    val pinnedKeys: Set<String> = emptySet(),
    val pinnedOrder: List<String> = emptyList(),
    val defaultsInitialized: Boolean = false,
    val hiddenKeys: Set<String> = emptySet(),
) {
    fun usageFor(key: String): Int = counts[key] ?: 0
    fun isPinned(key: String): Boolean = key in pinnedKeys
    fun isHidden(key: String): Boolean = key in hiddenKeys

    fun pinnedRank(key: String): Int = orderedPinnedKeys().indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE

    fun orderedPinnedKeys(): List<String> =
        pinnedOrder.filter { it in pinnedKeys } + pinnedKeys.filterNot { it in pinnedOrder }
}

@Serializable
private data class MobileAppUsageBuckets(
    val buckets: Map<String, MobileAppUsage> = emptyMap(),
)

@Singleton
class MobileAppUsageStore @Inject constructor(
    @ApplicationContext context: Context,
    @param:FscmJson private val json: Json,
) {
    private val dataStore = context.fscmMobileAppUsageDataStore

    fun usage(session: SessionState): Flow<MobileAppUsage> = dataStore.data.map { preferences ->
        readBuckets(preferences)[mobileAppUsageScopeKey(session)] ?: MobileAppUsage()
    }

    suspend fun initializeDefaultCommonApps(session: SessionState) {
        update(session, MobileAppUsage::withDefaultCommonApps)
    }

    suspend fun recordUsage(session: SessionState, appKey: String) {
        val normalizedKey = appKey.trim()
        if (normalizedKey.isBlank()) return
        update(session) { usage ->
            usage.copy(
                counts = usage.counts + (normalizedKey to (usage.usageFor(normalizedKey) + 1)),
            )
        }
    }

    suspend fun togglePinned(session: SessionState, appKey: String) {
        val normalizedKey = appKey.trim()
        if (normalizedKey.isBlank()) return
        update(session) { usage ->
            val pins = usage.pinnedKeys.toMutableSet()
            if (pins.remove(normalizedKey)) {
                usage.copy(
                    pinnedKeys = pins,
                    pinnedOrder = usage.orderedPinnedKeys().filterNot { it == normalizedKey },
                )
            } else {
                pins.add(normalizedKey)
                usage.copy(
                    pinnedKeys = pins,
                    pinnedOrder = listOf(normalizedKey) + usage.orderedPinnedKeys(),
                )
            }
        }
    }

    suspend fun setHidden(session: SessionState, appKey: String, hidden: Boolean) {
        val normalizedKey = appKey.trim()
        if (normalizedKey.isBlank()) return
        update(session) { usage -> usage.withAppHidden(normalizedKey, hidden) }
    }

    private suspend fun update(session: SessionState, transform: (MobileAppUsage) -> MobileAppUsage) {
        dataStore.edit { preferences ->
            val buckets = readBuckets(preferences).toMutableMap()
            val key = mobileAppUsageScopeKey(session)
            buckets[key] = transform(buckets[key] ?: MobileAppUsage())
            preferences[USAGE_BUCKETS_KEY] = json.encodeToString(MobileAppUsageBuckets(buckets))
        }
    }

    private fun readBuckets(preferences: Preferences): Map<String, MobileAppUsage> =
        preferences[USAGE_BUCKETS_KEY]
            ?.let { raw -> runCatching { json.decodeFromString<MobileAppUsageBuckets>(raw).buckets }.getOrDefault(emptyMap()) }
            .orEmpty()

    private companion object {
        val USAGE_BUCKETS_KEY = stringPreferencesKey("usage_buckets")
    }
}

internal fun mobileAppUsageScopeKey(session: SessionState): String =
    "${session.user?.id ?: 0}:${session.currentNamespace?.id ?: 0}"

val DEFAULT_COMMON_APP_KEYS = listOf(
    "label_print",
    "product_management",
    "box_labels",
    "receiving",
)

internal fun MobileAppUsage.withDefaultCommonApps(): MobileAppUsage =
    if (defaultsInitialized) {
        this
    } else {
        copy(
            pinnedKeys = pinnedKeys + DEFAULT_COMMON_APP_KEYS,
            pinnedOrder = DEFAULT_COMMON_APP_KEYS + orderedPinnedKeys()
                .filterNot { it in DEFAULT_COMMON_APP_KEYS },
            defaultsInitialized = true,
        )
    }

internal fun MobileAppUsage.withAppHidden(appKey: String, hidden: Boolean): MobileAppUsage {
    val normalizedKey = appKey.trim()
    if (normalizedKey.isBlank()) return this
    val updatedHiddenKeys = hiddenKeys.toMutableSet()
    if (hidden) updatedHiddenKeys.add(normalizedKey) else updatedHiddenKeys.remove(normalizedKey)
    return if (hidden) {
        copy(
            pinnedKeys = pinnedKeys - normalizedKey,
            pinnedOrder = orderedPinnedKeys().filterNot { it == normalizedKey },
            hiddenKeys = updatedHiddenKeys,
        )
    } else {
        copy(hiddenKeys = updatedHiddenKeys)
    }
}

const val COMMON_APP_USAGE_THRESHOLD = 5

fun commonMobileApps(
    entries: List<MobileAppEntry>,
    usage: MobileAppUsage,
    maxItems: Int = 4,
): List<MobileAppEntry> = entries
    .filterNot { usage.isHidden(it.key) }
    .withIndex()
    .sortedWith(
        compareByDescending<IndexedValue<MobileAppEntry>> { usage.isPinned(it.value.key) }
            .thenBy { usage.pinnedRank(it.value.key) }
            .thenByDescending { usage.usageFor(it.value.key) >= COMMON_APP_USAGE_THRESHOLD }
            .thenByDescending { usage.usageFor(it.value.key) }
            .thenBy { it.index },
    )
    .map { it.value }
    .take(maxItems)

fun shownMobileApps(
    entries: List<MobileAppEntry>,
    usage: MobileAppUsage,
): List<MobileAppEntry> = entries.filterNot { usage.isHidden(it.key) }

fun hiddenMobileApps(
    entries: List<MobileAppEntry>,
    usage: MobileAppUsage,
): List<MobileAppEntry> = entries.filter { usage.isHidden(it.key) }
