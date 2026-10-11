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

import javax.inject.Inject

const val OFFLINE_CODE_LIMIT = 20

data class OfflineCodeOption(val code: String, val description: String)

data class OfflineCodeResults(val items: List<OfflineCodeOption> = emptyList(), val hasMore: Boolean = false)

// Escape LIKE metacharacters so scanner labels are always treated literally.
internal fun offlineCodePrefix(raw: String): String = foldCatalogCode(raw)
    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Uses the same Room snapshots as inbound capture. Typing never starts a network query. */
class OfflineCodeSearch @Inject constructor(
    private val database: FscmDatabase,
    private val sessions: SessionStore,
    private val skus: LocalSkuCatalogRepository,
    private val locations: WarehouseLocationRepository,
) {
    suspend fun search(raw: String, location: Boolean): OfflineCodeResults {
        if (raw.isBlank() || raw.length > 200) return OfflineCodeResults()
        val session = sessions.current()
        val user = session.user?.id ?: return OfflineCodeResults()
        val namespace = session.currentNamespace?.id ?: return OfflineCodeResults()
        val pattern = offlineCodePrefix(raw)
        val rows = if (location) {
            // Local warehouse IDs and provider warehouse IDs are different identity domains.
            // Suggest codes with local warehouse labels; live stocktake lookup validates the configured warehouse.
            database.warehouseLocationDao().prefixCandidates(user, namespace, pattern, OFFLINE_CODE_LIMIT + 1)
        } else {
            database.localSkuCatalogDao().prefixCandidates(user, namespace, pattern, OFFLINE_CODE_LIMIT + 1)
                .map { OfflineCodeOption(it.code, listOf(it.name.ifBlank { it.productName }, it.spec).filter(String::isNotBlank).joinToString(" · ")) }
        }
        val current = sessions.current()
        check(current.user?.id == user && current.currentNamespace?.id == namespace) { "工作空间已切换" }
        return OfflineCodeResults(rows.take(OFFLINE_CODE_LIMIT), rows.size > OFFLINE_CODE_LIMIT)
    }

    suspend fun refresh() {
        // Attempt both catalogs; a failed refresh preserves the previously committed snapshot.
        val skuResult = cacheSyncResult { skus.refreshIfStale(force = true) }
        val locationResult = cacheSyncResult { locations.refreshIfStale(force = true) }
        skuResult.getOrThrow()
        locationResult.getOrThrow()
    }
}
