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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class InboundChangeLocationRoomTest {
    private lateinit var database: FscmDatabase
    private lateinit var dao: LocalInboundDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.localInboundDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun changingLocationPreservesScansAndCompletionUsesNewLocation() = runBlocking {
        seed()
        val before = dao.itemsForBatch("batch")
        dao.changeDraftLocation("batch", 7, 9, location())
        val changed = dao.itemsForBatch("batch")
        assertEquals(before.map { it.requestId }, changed.map { it.requestId })
        before.zip(changed).forEach { (old, new) ->
            assertEquals(old.copy(localLocationCode = "B-02", localWarehouseLocationId = 202, updatedAt = new.updatedAt), new)
        }
        assertEquals("B-02", dao.batch("batch")?.locationCode)
        assertEquals("Zone B", dao.batch("batch")?.zoneName)
        dao.finishBatch("batch")
        val pending = dao.itemsForBatch("batch")
        assertEquals(listOf("pending", "pending"), pending.map { it.status })
        assertEquals(
            "B-02",
            inboundBatchScanRequest("batch", pending)["location_code"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun completedBatchCannotBeChanged() = runBlocking {
        seed()
        dao.finishBatch("batch")
        val before = dao.itemsForBatch("batch")
        assertFailsWith<InboundClientException> { dao.changeDraftLocation("batch", 7, 9, location()) }
        assertEquals(before, dao.itemsForBatch("batch"))
        assertEquals("A-01", dao.batch("batch")?.locationCode)
    }

    @Test
    fun otherScopeCannotChangeDraft() = runBlocking {
        seed()
        val before = dao.itemsForBatch("batch")
        assertFailsWith<InboundClientException> { dao.changeDraftLocation("batch", 8, 9, location()) }
        assertFailsWith<InboundClientException> { dao.changeDraftLocation("batch", 7, 10, location()) }
        assertEquals(before, dao.itemsForBatch("batch"))
        assertEquals("A-01", dao.batch("batch")?.locationCode)
    }

    @Test
    fun correctingPreviewSnapshotPreservesDraftAndPendingOperations() = runBlocking {
        seed()
        val batch = dao.batch("batch")!!
        val preview = ReceivingScanResponse(item = ReceivingScanItem(boxPlanId = 0, labelCode = "BOX-0", status = "normal", locationCode = "A-01"))
        val stale = inboundSnapshotFromPreview(batch, null, preview, ReceivingCurrentAssignment("A-01", 101))
        dao.insertSnapshot(stale)
        val before = dao.itemsForBatch("batch")
        dao.insertSnapshot(inboundSnapshotFromPreview(batch, stale, preview, ReceivingCurrentAssignment()))
        assertEquals(before, dao.itemsForBatch("batch"))
        assertEquals(batch, dao.batch("batch"))
        assertEquals("", dao.snapshots(7, 9, 11).single().locationCode)
        dao.finishBatch("batch")
        val pending = dao.itemsForBatch("batch")
        dao.insertSnapshot(inboundSnapshotFromPreview(batch, stale, preview, ReceivingCurrentAssignment()))
        assertEquals(pending, dao.itemsForBatch("batch"))
    }

    private suspend fun seed() {
        dao.insertBatch(LocalInboundBatchEntity("batch", 7, 9, 11, "A-01", 101))
        repeat(2) { index ->
            dao.insertItem(
                LocalInboundItemEntity(
                    requestId = "request-$index", batchId = "batch", userId = 7, namespaceId = 9,
                    sessionId = 11, boxPlanId = index.toLong(), labelCode = "BOX-$index", rawCode = "RAW-$index",
                    localLocationCode = "A-01", localWarehouseLocationId = 101, scannedAt = index.toLong(),
                ),
            )
        }
    }

    private fun location() = LocalWarehouseLocationEntity(
        userId = 7, namespaceId = 9, locationId = 202, warehouseId = 1, warehouseCode = "WH",
        warehouseName = "Warehouse", zoneId = 2, zoneCode = "B", zoneName = "Zone B", code = "B-02",
        name = "Location B", type = "storage", isActive = true, remoteUpdatedAt = "", syncedAt = 1,
    )
}
