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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class OfflineCodeSearchTest {
    private lateinit var db: FscmDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun skuPrefixIsLiteralCaseInsensitiveScopedSortedAndBounded() = runBlocking {
        val dao = db.localSkuCatalogDao()
        val row = LocalSkuCatalogEntity(1, 2, "SKU-A", 3, "A", "large", 4, "P", "Product")
        fun sku(code: String) = row.copy(code = code, foldedCode = foldCatalogCode(code))
        dao.insert(listOf(sku("SKU-B"), sku("SKU-A"), sku("sku-a"), sku("XSKU-A"), sku("SKU_%"), sku("SKU_%2"), sku("SKU_XX"), sku("SKU\\A"), row.copy(userId = 9), row.copy(namespaceId = 9)))
        assertEquals(listOf("SKU-A", "sku-a", "SKU-B"), dao.prefixCandidates(1, 2, offlineCodePrefix(" sku- "), 21).map { it.code })
        assertEquals(listOf("SKU_%", "SKU_%2"), dao.prefixCandidates(1, 2, offlineCodePrefix("SKU_%"), 21).map { it.code })
        assertEquals(listOf("SKU\\A"), dao.prefixCandidates(1, 2, offlineCodePrefix("SKU\\"), 21).map { it.code })
        assertEquals(listOf("SKU-A"), dao.prefixCandidates(1, 2, offlineCodePrefix("SKU-"), 1).map { it.code })
        assertTrue(dao.prefixCandidates(5, 2, offlineCodePrefix("SKU"), 21).isEmpty())
    }

    @Test
    fun locationsExcludeDisabledAndOtherScopesAndDeduplicateCodesAcrossWarehouses() = runBlocking {
        val dao = db.warehouseLocationDao()
        val row = LocalWarehouseLocationEntity(1, 2, 3, 4, "WH", "Warehouse", 5, "Z", "Zone", "A-01", "A", "", true, "", 1)
        dao.upsertAll(listOf(row, row.copy(locationId = 8, warehouseCode = "OTHER", warehouseName = "Other"), row.copy(locationId = 9, code = "A-02"), row.copy(locationId = 10, code = "A-00", isActive = false), row.copy(userId = 9), row.copy(namespaceId = 9), row.copy(locationId = 11, code = "A_%")))
        val found = dao.prefixCandidates(1, 2, offlineCodePrefix(" a- "), 21)
        assertEquals(listOf("A-01", "A-02"), found.map { it.code })
        assertEquals(setOf("Warehouse", "Other"), found.first().description.split(',').toSet())
        assertEquals(listOf("A_%"), dao.prefixCandidates(1, 2, offlineCodePrefix("A_%"), 21).map { it.code })
        assertEquals(1, dao.prefixCandidates(1, 2, offlineCodePrefix("A-"), 1).size)
        assertTrue(dao.prefixCandidates(1, 8, offlineCodePrefix("A-"), 21).isEmpty())
    }
}
