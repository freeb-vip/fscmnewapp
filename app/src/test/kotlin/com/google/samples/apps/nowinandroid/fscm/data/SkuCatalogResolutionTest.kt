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
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SkuCatalogResolutionTest {
    private val now = System.currentTimeMillis()
    private val brown = row(10, "B13-single-Brown")
    private val lower = row(20, "B13-single-brown")
    private val folded = "B13-SINGLE-BROWN"

    private fun row(id: Long, code: String) = LocalSkuCatalogEntity(1, 2, code, id, "SKU $id", "", 3, "P", "Product", now)
    private fun full(count: Int = 2) = ReferenceCacheMeta(1, 2, "skus", true, count, now)
    private fun group(count: Int) = ReferenceCacheMeta(1, 2, "sku_group:$folded", true, count, now)
    private fun item(row: LocalSkuCatalogEntity) = MobileSkuSnapshotItem(row.skuId, row.code, row.name, row.spec, row.productId, row.productCode, row.productName)

    @Test
    fun completeSnapshotRetainsCrossPageCaseVariantsAndExactIdentities() = runBlocking {
        val first = MobileSkuSnapshotPage(listOf(item(brown)), 10, true)
        val second = MobileSkuSnapshotPage(listOf(item(lower)))
        validateSkuSnapshotPage(first, 0)
        validateSkuSnapshotPage(second, first.nextAfterId)
        validateSkuSnapshot(listOf(brown, lower))
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).build()
        try {
            val dao = db.localSkuCatalogDao()
            dao.replace(listOf(brown, lower), full())
            val cache = SkuCatalogCache(dao.candidates(1, 2, listOf(folded)), listOf(full()))
            assertEquals(10L, cache.resolve(brown.code)?.skuId)
            assertEquals(20L, cache.resolve(lower.code)?.skuId)
            val error = assertFailsWith<InboundClientException> { cache.resolve(folded) }
            assertEquals(InboundClientError.SkuAmbiguous, error.reason)
            assertEquals("$folded (${brown.code} / ${lower.code})", error.detail)
            assertTrue(dao.candidates(1, 9, listOf(folded)).isEmpty())
            assertTrue(dao.candidates(9, 2, listOf(folded)).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun incompleteCacheCannotInventUniquenessButExactMatchWorks() {
        val partial = SkuCatalogCache(listOf(brown), emptyList())
        assertEquals(brown, partial.resolve(brown.code))
        assertTrue(partial.isFresh(brown.code))
        assertNull(partial.resolve(folded))
        assertFalse(partial.isFresh(folded))
        val complete = SkuCatalogCache(listOf(brown), listOf(full(1)))
        assertEquals(brown, complete.resolve(folded))
        assertTrue(complete.isFresh(folded))
        val verifiedGroup = SkuCatalogCache(listOf(brown), listOf(group(1)))
        assertEquals(brown, verifiedGroup.resolve(folded))
        assertTrue(verifiedGroup.isFresh(folded))
        val incompleteGroup = SkuCatalogCache(listOf(brown), listOf(group(2)))
        assertNull(incompleteGroup.resolve(folded))
        assertFalse(incompleteGroup.isFresh(folded))
        val old = SkuCatalogCache(listOf(brown.copy(syncedAt = 1)), listOf(full(1).copy(refreshedAt = 1)))
        assertFalse(old.isFresh(folded))
        assertEquals(brown.skuId, old.resolve(folded)?.skuId) // Complete old cache remains usable offline.
    }

    @Test
    fun responseMustCoverEachRequestAndProveItsMatch() {
        val candidates = listOf(item(brown), item(lower))
        val exact = MobileSkuResolution(brown.code, "matched", brown.skuId, candidates)
        val ambiguous = MobileSkuResolution(folded, "ambiguous", candidates = candidates)
        val missing = MobileSkuResolution("UNKNOWN", "missing", candidates = emptyList())
        val valid = MobileSkuResolveResult(listOf(exact, ambiguous, missing))
        assertEquals(2, validateSkuResolutionResult(valid, listOf(brown.code, folded, "UNKNOWN")).getValue(folded).size)
        for (bad in listOf(
            valid.copy(results = listOf(exact, exact, missing)),
            valid.copy(results = listOf(exact.copy(matchedSkuId = lower.skuId), ambiguous, missing)),
            valid.copy(results = listOf(exact.copy(candidates = candidates + item(brown)), ambiguous, missing)),
            valid.copy(results = listOf(exact, ambiguous.copy(candidates = listOf(item(lower))), missing)),
            valid.copy(results = listOf(exact, ambiguous, missing.copy(candidates = listOf(item(brown))))),
            valid.copy(results = listOf(exact, ambiguous.copy(status = "missing"), missing)),
        )) {
            assertFailsWith<IllegalStateException> { validateSkuResolutionResult(bad, listOf(brown.code, folded, "UNKNOWN")) }
        }
        val unique = MobileSkuResolution(folded, "matched", brown.skuId, listOf(item(brown)))
        assertEquals(listOf(item(brown)), validateSkuResolutionResult(MobileSkuResolveResult(listOf(unique)), listOf(folded))[folded])
    }

    @Test
    fun groupReplacementDeletesStaleAliasesAndMissingRowsAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).build()
        try {
            val dao = db.localSkuCatalogDao()
            dao.replace(listOf(brown, lower), full())
            dao.insert(listOf(brown.copy(userId = 9), lower.copy(namespaceId = 9)))
            dao.applyResolvedGroups(1, 2, listOf(lower), listOf(group(1)))
            assertEquals(listOf(lower), dao.candidates(1, 2, listOf(folded)))
            assertEquals(2, dao.meta(1, 2, "skus")?.totalCount) // Targeted refresh does not renew a full snapshot.
            assertFailsWith<IllegalStateException> {
                db.withTransaction {
                    dao.applyResolvedGroups(1, 2, emptyList(), listOf(group(0)))
                    error("interrupted")
                }
            }
            assertEquals(listOf(lower), dao.candidates(1, 2, listOf(folded)))
            dao.applyResolvedGroups(1, 2, emptyList(), listOf(group(0)))
            assertTrue(dao.candidates(1, 2, listOf(folded)).isEmpty())
            assertEquals(1, dao.candidates(9, 2, listOf(folded)).size)
            assertEquals(1, dao.candidates(1, 9, listOf(folded)).size)
            val missing = SkuCatalogCache(emptyList(), listOf(group(0)))
            assertTrue(missing.isFresh(folded))
            assertNull(missing.resolve(folded))
            dao.replace(listOf(brown, lower), full())
            assertNull(dao.meta(1, 2, "sku_group:$folded"))
        } finally {
            db.close()
        }
    }

    @Test
    fun trueDuplicateIdentitiesAndIncompletePagesCannotPublish() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).build()
        try {
            val dao = db.localSkuCatalogDao()
            dao.replace(listOf(brown), full(1))
            for (invalid in listOf(listOf(brown, brown.copy(skuId = 30)), listOf(brown, lower.copy(skuId = brown.skuId)))) {
                assertFailsWith<IllegalStateException> {
                    validateSkuSnapshot(invalid)
                    dao.replace(invalid, full())
                }
            }
            assertFailsWith<IllegalStateException> { validateSkuSnapshotPage(MobileSkuSnapshotPage(emptyList(), 10, true), 0) }
            assertEquals(listOf(brown), dao.candidates(1, 2, listOf(folded)))
            assertEquals(1, dao.meta(1, 2, "skus")?.totalCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration21KeepsBusinessData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "sku-case-migration-${java.util.UUID.randomUUID()}"
        val old = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            old.localSkuCatalogDao().replace(listOf(brown), full(1))
            old.localSkuCatalogDao().saveMeta(ReferenceCacheMeta(1, 2, "box_mappings", true, 5, 10))
            val boxMapping = LocalBoxLabelMappingEntity(1, 2, 3, 4, "BOX", "1", "UID", "REMOTE", "QR", "active", "P", "Product", brown.code, "Manufacturer", "Case", 2, 1, "", 1)
            old.boxLabelMappingDao().insertAll(listOf(boxMapping))
            old.warehouseLocationDao().upsertAll(listOf(LocalWarehouseLocationEntity(1, 2, 3, 4, "WH", "Warehouse", 5, "Z", "Zone", "A-01", "A", "", true, "", 1)))
            old.localInboundDao().insertBatch(LocalInboundBatchEntity("draft", 1, 2, 3, "A-01", 10))
            old.localInboundDao().insertItem(LocalInboundItemEntity("pending", "done", 1, 2, 3, 4, "BOX", "BOX", "A-01", status = "pending"))
            removeSkuFoldedCode(old.openHelper.writableDatabase)
            old.openHelper.writableDatabase.version = 20
            old.close()
            val migrated = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().addMigrations(FscmDatabaseModule.MIGRATION_20_21).build()
            try {
                val dao = migrated.localSkuCatalogDao()
                assertTrue(dao.find(1, 2, listOf(brown.code)).isEmpty())
                assertNull(dao.meta(1, 2, "skus"))
                assertEquals(5, dao.meta(1, 2, "box_mappings")?.totalCount)
                assertEquals(boxMapping, migrated.boxLabelMappingDao().find(1, 2, "BOX", "BOX"))
                assertEquals("A-01", migrated.warehouseLocationDao().findActive(1, 2, "WH", "A-01")?.code)
                assertEquals("draft", migrated.localInboundDao().batch("draft")?.status)
                assertEquals("pending", migrated.localInboundDao().itemsForSession(1, 2, 3).single().status)
                dao.replace(listOf(brown, lower), full())
                assertEquals(2, dao.candidates(1, 2, listOf(folded)).size)
            } finally {
                migrated.close()
            }
        } finally {
            old.close()
            context.deleteDatabase(name)
        }
    }
}
