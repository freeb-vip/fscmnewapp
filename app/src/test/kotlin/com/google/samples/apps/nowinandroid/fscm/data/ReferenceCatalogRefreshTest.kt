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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ReferenceCatalogRefreshTest {
    @Test
    fun freshnessExpiresAtTwoHoursAndDoesNotTrustFutureTimestamps() {
        val synced = 1_000L
        assertTrue(referenceDataIsFresh(synced, synced + 2 * 60 * 60 * 1000L - 1))
        assertFalse(referenceDataIsFresh(synced, synced + 2 * 60 * 60 * 1000L))
        assertFalse(referenceDataIsFresh(0, synced))
        assertFalse(referenceDataIsFresh(synced, synced - 1))
    }

    @Test
    fun onlyTransientFailuresAllowOfflineFallback() {
        assertTrue(IOException("offline").canUseReferenceCache())
        assertTrue(FscmApiException("unavailable", 503).canUseReferenceCache())
        assertTrue(SessionRefreshUnavailableException().canUseReferenceCache())
        for (status in listOf(401, 403, 404, 409, 422)) {
            assertFalse(FscmApiException("rejected", status).canUseReferenceCache())
        }
        assertFalse(CancellationException().canUseReferenceCache())
        assertFalse(IllegalStateException("malformed data").canUseReferenceCache())
        assertFalse(SessionExpiredException().canUseReferenceCache())
    }

    @Test
    fun incompleteLocationPagesCannotPublish() {
        val row = WarehouseLocationOption(id = 10, code = "A", isActive = true, warehouse = WarehouseLocationWarehouse(code = "WH"))
        validateLocationSnapshotPage(MobileLocationSnapshotPage(listOf(row), 10, true), 0)
        assertFailsWith<IllegalStateException> { validateLocationSnapshotPage(MobileLocationSnapshotPage(emptyList(), 10, true), 0) }
        assertFailsWith<IllegalStateException> { validateLocationSnapshotPage(MobileLocationSnapshotPage(listOf(row), 10, true), 10) }
        assertFailsWith<IllegalStateException> { validateLocationSnapshotPage(MobileLocationSnapshotPage(listOf(row, row)), 0) }
        assertFailsWith<IllegalStateException> { validateLocationSnapshotPage(MobileLocationSnapshotPage(listOf(row.copy(isActive = false))), 0) }
    }

    @Test
    fun targetedLookupRemovesMissingSkuWithoutExtendingWholeSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.localSkuCatalogDao()
            val a = LocalSkuCatalogEntity(1, 2, "A", 10, "Old", "", 1, "P", "Product", 100)
            val b = a.copy(code = "B", skuId = 11, foldedCode = "B")
            dao.replace(listOf(a, b), ReferenceCacheMeta(1, 2, "skus", true, 2, 100))
            dao.insert(listOf(a.copy(userId = 3)))
            dao.applyResolvedGroups(1, 2, listOf(a.copy(name = "Center", syncedAt = 200)), listOf(ReferenceCacheMeta(1, 2, "sku_group:A", true, 1, 200), ReferenceCacheMeta(1, 2, "sku_group:B", true, 0, 200)))
            assertEquals("Center", dao.find(1, 2, listOf("A")).single().name)
            assertTrue(dao.find(1, 2, listOf("B")).isEmpty())
            assertEquals("Old", dao.find(3, 2, listOf("A")).single().name)
            assertEquals(100L, dao.meta(1, 2, "skus")?.refreshedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration19To21KeepsPendingData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "reference-migration-${java.util.UUID.randomUUID()}"
        val old = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().build()
        val row = LocalSkuCatalogEntity(1, 2, "A", 10, "SKU", "", 1, "P", "Product")
        try {
            old.localSkuCatalogDao().insert(listOf(row))
            old.localInboundDao().insertBatch(LocalInboundBatchEntity("draft", 1, 2, 3, "A-01", 10))
            old.localInboundDao().insertItem(LocalInboundItemEntity("pending", "done", 1, 2, 3, 4, "BOX", "BOX", "A-01", status = "pending"))
            old.openHelper.writableDatabase.execSQL("ALTER TABLE local_sku_catalog DROP COLUMN syncedAt")
            removeSkuFoldedCode(old.openHelper.writableDatabase)
            old.openHelper.writableDatabase.version = 19
            old.close()
            val migrated = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(FscmDatabaseModule.MIGRATION_19_20, FscmDatabaseModule.MIGRATION_20_21, FscmDatabaseModule.MIGRATION_21_22).build()
            try {
                assertTrue(migrated.localSkuCatalogDao().find(1, 2, listOf("A")).isEmpty())
                assertEquals("draft", migrated.localInboundDao().batch("draft")?.status)
                assertEquals("pending", migrated.localInboundDao().itemsForSession(1, 2, 3).single().status)
            } finally {
                migrated.close()
            }
        } finally {
            old.close()
            context.deleteDatabase(name)
        }
    }
}
