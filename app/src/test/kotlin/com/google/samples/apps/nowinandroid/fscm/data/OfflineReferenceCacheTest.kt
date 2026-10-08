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

import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class OfflineReferenceCacheTest {
    private lateinit var db: FscmDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun locationHitDoesNotRequireCompleteOrFreshSnapshot() = runBlocking {
        val row = location()
        db.warehouseLocationDao().upsertAll(listOf(row))
        assertEquals(row, resolveLocalWarehouseLocation(db.warehouseLocationDao(), 1, 2, "WH", "A-01"))
        db.warehouseLocationDao().upsertMeta(WarehouseLocationCacheMetaEntity(1, 2, 1, true, 1))
        assertEquals(row, resolveLocalWarehouseLocation(db.warehouseLocationDao(), 1, 2, "WH", "A-01"))
        assertEquals(
            InboundClientError.LocationWarehouseMismatch,
            assertFailsWith<InboundClientException> {
                resolveLocalWarehouseLocation(db.warehouseLocationDao(), 1, 2, "OTHER", "A-01")
            }.reason,
        )
        assertEquals(
            InboundClientError.LocationNotFound,
            assertFailsWith<InboundClientException> {
                resolveLocalWarehouseLocation(db.warehouseLocationDao(), 1, 2, "WH", "UNKNOWN")
            }.reason,
        )
        assertEquals(
            InboundClientError.LocationCacheUnavailable,
            assertFailsWith<InboundClientException> {
                resolveLocalWarehouseLocation(db.warehouseLocationDao(), 9, 2, "WH", "A-01")
            }.reason,
        )
    }

    @Test
    fun fullSkuReplacementRemovesDeletedRowsAndPreservesOtherScopes() = runBlocking {
        val dao = db.localSkuCatalogDao()
        val row = LocalSkuCatalogEntity(1, 2, "SKU-A", 3, "A", "large", 4, "P", "Product")
        dao.replace(listOf(row), ReferenceCacheMeta(1, 2, "skus", true, 1, 100))
        dao.replace(listOf(row.copy(userId = 8)), ReferenceCacheMeta(8, 2, "skus", true, 1, 100))
        assertEquals(row, dao.find(1, 2, listOf("SKU-A")).single())
        dao.replace(emptyList(), ReferenceCacheMeta(1, 2, "skus", true, 0, 200))
        assertTrue(dao.find(1, 2, listOf("SKU-A")).isEmpty())
        assertEquals(1, dao.find(8, 2, listOf("SKU-A")).size)
        assertEquals(200L, dao.meta(1, 2, "skus")?.refreshedAt)
    }

    @Test
    fun interruptedDownloadCannotReplaceOldSnapshot() = runBlocking {
        val dao = db.localSkuCatalogDao()
        dao.replace(emptyList(), ReferenceCacheMeta(1, 2, "skus", true, 0, 100))
        val result = cacheSyncResult {
            validateSkuSnapshotPage(MobileSkuSnapshotPage(hasMore = true, nextAfterId = 0), 0)
            dao.replace(emptyList(), ReferenceCacheMeta(1, 2, "skus", true, 0, 200))
        }
        assertTrue(result.isFailure)
        assertEquals(100L, dao.meta(1, 2, "skus")?.refreshedAt)
        assertFailsWith<CancellationException> { cacheSyncResult<Unit> { throw CancellationException() } }
        Unit
    }

    @Test
    fun adjustmentAliasCursorDoesNotExtendTheMappingSnapshot() {
        val page = Json { ignoreUnknownKeys = true }.decodeFromString<BoxLabelMappingSnapshot>(
            """{"items":[],"next_after_id":41,"has_more":false,"next_alias_after_id":900,"alias_has_more":true}""",
        )
        assertEquals(41L, page.nextAfterId)
        assertTrue(!page.hasMore)
    }

    @Test
    fun repeatedOrOutOfOrderSkuPagesAreRejected() {
        val row = MobileSkuSnapshotItem(10, "SKU", productId = 1, productCode = "P")
        validateSkuSnapshotPage(MobileSkuSnapshotPage(listOf(row), 10, true), 0)
        assertFailsWith<IllegalStateException> { validateSkuSnapshotPage(MobileSkuSnapshotPage(listOf(row), 10, true), 10) }
        assertFailsWith<IllegalStateException> { validateSkuSnapshotPage(MobileSkuSnapshotPage(listOf(row, row)), 0) }
        assertEquals("SKU-A", normalizeCatalogCode(" sku-a "))
    }

    @Test
    fun cartonLookupIsCaseInsensitiveAndRejectsAmbiguousOwnership() = runBlocking {
        val dao = db.localInboundDao()
        val row = LocalInboundSnapshotEntity(1, 2, 3, 4, "BOX-1", "NO-1", "UID-1", "normal", "pending", "", eligible = true, snapshotAt = 100)
        dao.insertSnapshot(row)
        assertEquals(row, dao.scanCandidates(1, 2, 3, listOf("box-1")).single())
        assertTrue(dao.scanCandidates(9, 2, 3, listOf("BOX-1")).isEmpty())
        dao.insertSnapshot(row.copy(boxPlanId = 5))
        assertEquals(2, dao.scanCandidates(1, 2, 3, listOf("BOX-1")).size)
        assertTrue(dao.scanCandidates(1, 2, 3, listOf("UNKNOWN")).isEmpty())
    }

    @Test
    fun migrationPreservesExistingTables() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO warehouse_location_cache_meta VALUES (1, 2, 123, 1, 7)")
        FscmDatabaseModule.MIGRATION_18_19.migrate(sql)
        sql.query("SELECT totalCount FROM warehouse_location_cache_meta WHERE userId=1 AND namespaceId=2").use {
            assertTrue(it.moveToFirst())
            assertEquals(7, it.getInt(0))
        }
    }

    private fun location() = LocalWarehouseLocationEntity(1, 2, 3, 4, "WH", "Warehouse", 5, "Z", "Zone", "A-01", "A", "", true, "", 1)
}
