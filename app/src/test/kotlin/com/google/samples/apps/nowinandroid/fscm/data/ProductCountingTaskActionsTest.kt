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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProductCountingTaskActionsTest {
    private fun task(id: String = "empty") = ProductCountTaskEntity(id, 1, 2, editorDeviceId = "device", createdAt = 1, updatedAt = 1)

    private val label = ProductCountResolvedLabel("BOX-1", "BOX-1", listOf("SKU"), "P", "Product", "test")

    private fun withDao(block: suspend (ProductCountingDao) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).build()
        try {
            block(db.productCountingDao())
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyTaskDeletesOnlyForItsOwnerAndScope() = withDao { dao ->
        dao.upsertTask(task())
        dao.upsertTask(task("other"))
        assertTrue(dao.observeTask(1, 2, "empty").first()!!.canDeleteEmptyTask())
        assertEquals(0, dao.deleteEmptyTask(9, 2, "empty", "device"))
        assertEquals(0, dao.deleteEmptyTask(1, 9, "empty", "device"))
        assertEquals(0, dao.deleteEmptyTask(1, 2, "empty", "other-device"))
        assertEquals(1, dao.deleteEmptyTask(1, 2, "empty", "device"))
        assertNull(dao.task(1, 2, "empty"))
        assertEquals(listOf("other"), dao.observeTasks(1, 2).first().map { it.task.clientTaskId })
        assertEquals(0, dao.deleteEmptyTask(1, 2, "empty", "device"))
    }

    @Test
    fun anyScanHistoryPreventsDeletionEvenAfterReset() = withDao { dao ->
        for (syncStatus in listOf("pending", "rejected", "conflict")) {
            val id = "history-$syncStatus"
            dao.upsertTask(task(id))
            val result = assertIs<ProductCountScanResult.Accepted>(dao.acceptResolvedLabel(1, 2, id, "device", label, false, 10, Json))
            dao.updateEntrySync(result.entry.clientScanId, syncStatus)
            assertEquals(1, dao.resetTask(1, 2, id, "device", 20))
            val summary = dao.observeTask(1, 2, id).first()!!
            assertEquals(0, summary.boxCount)
            assertEquals(1, summary.scanRecordCount)
            assertFalse(summary.canDeleteEmptyTask())
            assertEquals(0, dao.deleteEmptyTask(1, 2, id, "device"))
            assertEquals(1, dao.completeTask(1, 2, id, "device", 30))
            assertEquals("completed", dao.task(1, 2, id)?.status)
            assertEquals(listOf(result.entry.clientScanId), dao.allEntries(1, 2, id).map { it.clientScanId })
        }
    }

    @Test
    fun uploadedOrChangedTaskCannotBeDeletedAsEmpty() = withDao { dao ->
        val protected = listOf(
            task().copy(remoteId = 12),
            task().copy(serverRevision = 1),
            task().copy(serverCount = 2),
            task().copy(uploadedAt = 10),
            task().copy(lastUploadId = "uncertain-upload"),
            task().copy(uploadStatus = "uploading"),
            task().copy(uploadStatus = "failed"),
            task().copy(uploadStatus = "uploaded"),
            task().copy(productCode = "P"),
            task().copy(status = "completed"),
        )
        for (current in protected) {
            dao.upsertTask(current)
            assertFalse(dao.observeTask(1, 2, "empty").first()!!.canDeleteEmptyTask())
            assertEquals(0, dao.deleteEmptyTask(1, 2, "empty", "device"))
            assertNotNull(dao.task(1, 2, "empty"))
        }
    }

    @Test
    fun completionPreservesScansAndRejectsFurtherScanning() = withDao { dao ->
        dao.upsertTask(task())
        assertEquals(0, dao.completeTask(1, 2, "empty", "device", 10))
        assertIs<ProductCountScanResult.Accepted>(dao.acceptResolvedLabel(1, 2, "empty", "device", label, false, 20, Json))
        assertEquals(0, dao.completeTask(9, 2, "empty", "device", 30))
        assertEquals(0, dao.completeTask(1, 2, "empty", "other-device", 30))
        assertEquals(1, dao.completeTask(1, 2, "empty", "device", 30))
        assertEquals(0, dao.completeTask(1, 2, "empty", "device", 40))
        assertEquals(30L, dao.task(1, 2, "empty")?.completedAt)
        assertEquals(1, dao.allEntries(1, 2, "empty").size)
        assertIs<ProductCountScanResult.TaskCompleted>(dao.acceptResolvedLabel(1, 2, "empty", "device", label, true, 40, Json))
    }

    @Test
    fun scanAndDeleteRaceCannotLeaveOrphanData() = withDao { dao ->
        coroutineScope {
            repeat(5) { index ->
                val id = "race-$index"
                dao.upsertTask(task(id))
                val start = CompletableDeferred<Unit>()
                val scan = async(Dispatchers.Default) {
                    start.await()
                    dao.acceptResolvedLabel(1, 2, id, "device", label, false, 10, Json)
                }
                val delete = async(Dispatchers.Default) {
                    start.await()
                    dao.deleteEmptyTask(1, 2, id, "device")
                }
                start.complete(Unit)
                if (delete.await() == 1) {
                    assertIs<ProductCountScanResult.MissingTask>(scan.await())
                    assertTrue(dao.allEntries(1, 2, id).isEmpty())
                    assertNull(dao.task(1, 2, id))
                } else {
                    assertIs<ProductCountScanResult.Accepted>(scan.await())
                    assertNotNull(dao.task(1, 2, id))
                    assertEquals(1, dao.allEntries(1, 2, id).size)
                }
            }
        }
    }
}
