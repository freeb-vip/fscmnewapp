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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class CentralReceivingRoomTest {
    private lateinit var database: FscmDatabase
    private lateinit var dao: CentralReceivingDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FscmDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.centralReceivingDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stagedGenerationDoesNotReplaceActiveSnapshotUntilActivation() = runBlocking {
        dao.upsertReceipts(listOf(receipt("old", "pending")))
        dao.upsertLabels(listOf(label("old", "shipped")))
        dao.activateSnapshot(meta("old"))

        dao.upsertReceipts(listOf(receipt("new", "completed")))
        dao.upsertLabels(listOf(label("new", "central_received")))

        assertEquals("pending", dao.receipts(USER_ID, NAMESPACE_ID).single().status)
        assertEquals("shipped", dao.labels(USER_ID, NAMESPACE_ID).single().remoteStatus)

        dao.activateSnapshot(meta("new"))

        assertEquals("completed", dao.receipts(USER_ID, NAMESPACE_ID).single().status)
        assertEquals("central_received", dao.labels(USER_ID, NAMESPACE_ID).single().remoteStatus)
    }

    private fun meta(generation: String) = CentralReceivingSnapshotMetaEntity(
        userId = USER_ID,
        namespaceId = NAMESPACE_ID,
        activeGeneration = generation,
        complete = true,
    )

    private fun receipt(generation: String, status: String) = CentralReceivingReceiptEntity(
        userId = USER_ID,
        namespaceId = NAMESPACE_ID,
        remoteId = 11,
        status = status,
        payload = "{}",
        generation = generation,
    )

    private fun label(generation: String, status: String) = CentralReceivingLabelEntity(
        userId = USER_ID,
        namespaceId = NAMESPACE_ID,
        boxPlanId = 21,
        centralReceiptId = 11,
        centralReceiptItemId = 31,
        labelCode = "LABEL-21",
        boxUid = "UID-21",
        boxNo = "BOX-21",
        remoteStatus = status,
        signStatus = "pending",
        payload = "{}",
        generation = generation,
    )

    private companion object {
        const val USER_ID = 1L
        const val NAMESPACE_ID = 2L
    }
}
