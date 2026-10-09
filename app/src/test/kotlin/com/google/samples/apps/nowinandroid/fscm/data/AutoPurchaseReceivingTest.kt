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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AutoPurchaseReceivingTest {
    private lateinit var database: FscmDatabase
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed(id: String = "batch") {
        database.localInboundDao().insertBatch(LocalInboundBatchEntity(id, 7, 9, 11, "A-01", 101))
        database.localInboundDao().insertItem(LocalInboundItemEntity("scan-$id", id, 7, 9, 11, 12, "BOX-$id", "BOX-$id", "A-01"))
    }

    @Test
    fun waitingPurchaseIsQueriedWithoutResubmission() = runBlocking {
        assertTrue(receiptTaskActive("waiting_purchase"))
        assertTrue(!receiptTaskRetryable("waiting_purchase"))
        val job = json.decodeFromString<PurchaseReceiptJob>("""{"id":"original-job","status":"waiting_purchase","result":{"waiting_reason":{"code":"purchase_not_linked","message":"等待关联采购单，将自动继续"}}}""")
        assertEquals("purchase_not_linked", job.result.waitingReason?.code)
        val saved = AutoReceiptSubmissionEntity("request", 7, 9, 11, 11, "batch", "[]", "original-body", jobId = job.id, status = job.status)
        var persisted = saved
        val result = resumeAutoReceiptSubmission(saved, {}, { persisted = it }, { error("waiting task must not be submitted again") }, { id ->
            assertEquals("original-job", id)
            PurchaseReceiptJob(id = id, status = "succeeded")
        })
        assertEquals("succeeded", result.status)
        assertEquals("original-body", persisted.payload)
        assertEquals("original-job", persisted.jobId)
    }

    @Test
    fun completionAtomicallyFreezesNormalAndCrossOrderScans() = runBlocking {
        seed()
        database.crossOrderScanDao().save(CrossOrderScanEntity("cross", 7, 9, 11, "batch", "inbound", "OTHER-BOX", "{}"))
        assertTrue(database.autoReceiptDao().intents(7, 9).isEmpty())
        database.localInboundDao().finishBatch("batch")
        val intent = database.autoReceiptDao().intents(7, 9).single()
        assertEquals(listOf(AutoReceiptScanRef("scan-batch"), AutoReceiptScanRef("cross", true)), json.decodeFromString<List<AutoReceiptScanRef>>(intent.scanRefsJson))
        assertEquals("pending", database.localInboundDao().itemsForBatch("batch").single().status)
        assertTrue(database.autoReceiptDao().intents(8, 9).isEmpty())
        assertTrue(database.autoReceiptDao().intents(7, 10).isEmpty())
    }

    @Test
    fun rollbackLeavesDraftAndNoAutomaticIntent() = runBlocking {
        seed()
        runCatching {
            database.withTransaction {
                database.localInboundDao().finishBatch("batch")
                error("interrupted transaction")
            }
        }
        assertTrue(database.autoReceiptDao().intents(7, 9).isEmpty())
        assertEquals("draft", database.localInboundDao().itemsForBatch("batch").single().status)
        database.localInboundDao().deleteDraftItems("batch")
        database.localInboundDao().deleteDraftBatch("batch")
        assertTrue(database.autoReceiptDao().intents(7, 9).isEmpty())
    }

    @Test
    fun historicalCompletedBatchIsNotEnrolledUntilSelected() = runBlocking {
        seed()
        database.localInboundDao().updateBatchStatus("batch", "synced", 123, 123)
        assertTrue(database.autoReceiptDao().intents(7, 9).isEmpty())
        database.localInboundDao().saveAutoReceiptIntent("batch")
        database.localInboundDao().saveAutoReceiptIntent("batch")
        assertEquals(1, database.autoReceiptDao().intents(7, 9).size)
    }

    @Test
    fun immutableRequestAndTaskIdentitySurviveRepositoryRecreation() = runBlocking {
        val refs = listOf(AutoReceiptScanRef("s2"), AutoReceiptScanRef("s1"))
        val id = autoReceiptRequestId("batch", 11, refs)
        assertEquals(id, autoReceiptRequestId("batch", 11, refs.reversed()))
        assertNotEquals(id, autoReceiptRequestId("batch", 12, refs))
        val request = AutoReceiptRequest(id, "batch", listOf(AutoReceiptItem(12, "s1")))
        val saved = AutoReceiptSubmissionEntity(id, 7, 9, 11, 11, "batch", json.encodeToString(refs), json.encodeToString(request))
        database.autoReceiptDao().insertSubmission(saved)
        database.autoReceiptDao().insertSubmission(saved.copy(payload = "invalid replay"))
        assertEquals(saved, database.autoReceiptDao().submissions(7, 9).single())
        database.autoReceiptDao().saveSubmission(saved.copy(jobId = "server-job", status = "result_unknown"))
        val restored = database.autoReceiptDao().submissions(7, 9).single()
        assertEquals("server-job", restored.jobId)
        assertEquals(saved.payload, restored.payload)
        assertFalse(receiptTaskRetryable(restored.status))
        assertFalse(receiptTaskActive(restored.status))
    }

    @Test
    fun synchronizationDoesNotMergeDifferentBatchesOrAccounts() {
        val item = LocalInboundItemEntity("scan", "batch-1", 7, 9, 11, 12, "BOX", "BOX", "A-01")
        val groups = groupInboundSyncItems(listOf(item, item.copy(batchId = "batch-2"), item.copy(userId = 8), item.copy(namespaceId = 10)))
        assertEquals(4, groups.size)
    }

    @Test
    fun productAndRecordPayloadsRetainFailureAndUnknownQuantities() {
        val state = json.decodeFromString<PurchaseReceivingState>("""{"enabled":true,"products":[{"product_id":1,"total_quantity":40,"received_quantity":10,"pending_quantity":30,"failed_quantity":20,"unknown_quantity":10,"total_box_count":2,"received_box_count":0}],"recent_receipts":[{"id":"r","status":"partial_failed","issues":[{"box_plan_id":2,"message":"库位失效"}]}],"receipt_record_count":25}""")
        val product = state.products.single()
        assertEquals(product.totalQuantity, product.receivedQuantity + product.pendingQuantity)
        assertEquals(20, product.failedQuantity)
        assertEquals(10, product.unknownQuantity)
        assertEquals(25, state.receiptRecordCount)
        assertEquals("库位失效", state.recentReceipts.single().issues.single().message)
    }

    @Test
    fun lostResponseReplaysSameRequestAndKnownJobOnlyQueries() = runBlocking {
        val original = AutoReceiptSubmissionEntity("request", 7, 9, 11, 11, "batch", "[]", "{\"request_id\":\"request\"}")
        database.autoReceiptDao().insertSubmission(original)
        var sends = 0
        var queries = 0
        val submit: suspend (AutoReceiptSubmissionEntity) -> PurchaseReceiptJob = { request ->
            assertEquals(original.payload, request.payload)
            sends++
            if (sends == 1) throw java.io.IOException("response lost after acceptance")
            PurchaseReceiptJob(id = "same-server-job", status = "running")
        }
        val query: suspend (String) -> PurchaseReceiptJob = { id ->
            queries++
            assertEquals("same-server-job", id)
            PurchaseReceiptJob(id = id, status = "succeeded")
        }
        val first = runCatching { resumeAutoReceiptSubmission(original, {}, database.autoReceiptDao()::saveSubmission, submit, query) }
        assertTrue(first.isFailure)
        val afterRestart = database.autoReceiptDao().submissions(7, 9).single()
        assertEquals("submitting", afterRestart.status)
        resumeAutoReceiptSubmission(afterRestart, {}, database.autoReceiptDao()::saveSubmission, submit, query)
        resumeAutoReceiptSubmission(database.autoReceiptDao().submissions(7, 9).single(), {}, database.autoReceiptDao()::saveSubmission, submit, query)
        assertEquals(2, sends)
        assertEquals(1, queries)
        assertEquals("succeeded", database.autoReceiptDao().submissions(7, 9).single().status)
    }

    @Test
    fun switchingWorkspaceBetweenPersistenceAndSendDoesNotSubmit() = runBlocking {
        val original = AutoReceiptSubmissionEntity("request", 7, 9, 11, 11, "batch", "[]", "{}")
        var checks = 0
        var sends = 0
        val result = runCatching {
            resumeAutoReceiptSubmission(original, {
                checks++
                check(checks == 1) { "workspace changed" }
            }, {}, {
                sends++
                PurchaseReceiptJob()
            }, { error("unexpected query") })
        }
        assertTrue(result.isFailure)
        assertEquals(0, sends)
    }

    @Test
    fun frozenScanSurvivesRescanAndCancelledReplacement() = runBlocking {
        seed()
        val local = database.localInboundDao()
        local.finishBatch("batch")
        val ack = InboundScanAck("ack", 11, 12, "scan-batch", 101, "A-01")
        local.updateItemResult("scan-batch", "synced", "A-01", 101, 12, 0, "", 123, ackJson = json.encodeToString(ack))
        val frozen = database.autoReceiptDao().scan("batch:false:scan-batch")!!
        assertEquals("synced", json.decodeFromString<LocalInboundItemEntity>(frozen.scanJson).status)
        assertEquals(ack, json.decodeFromString<InboundScanAck>(frozen.ackJson))
        local.deleteTerminalItemForLabel(7, 9, "BOX-batch")
        local.insertBatch(LocalInboundBatchEntity("replacement", 7, 9, 11, "B-02", 102))
        local.insertItem(LocalInboundItemEntity("new-scan", "replacement", 7, 9, 11, 12, "BOX-batch", "BOX-batch", "B-02"))
        local.deleteDraftItems("replacement")
        local.deleteDraftBatch("replacement")
        assertEquals(frozen, database.autoReceiptDao().scan(frozen.id))
        assertEquals(1, database.autoReceiptDao().intents(7, 9).size)
    }

    @Test
    fun historicalLocationAcceptanceUsesScanQueueWithoutEnrollingPurchase() = runBlocking {
        seed()
        val local = database.localInboundDao()
        local.updateBatchStatus("batch", "synced", 123, 123)
        local.updateItemResult("scan-batch", "accept_remote_pending", "A-01", 101, 12, 0, "待确认", 0)
        val pending = local.pendingItems(7, 9).single()
        assertEquals("accept_remote_pending", pending.status)
        assertTrue(local.pendingItems(8, 9).isEmpty())
        local.saveAcceptedLocation(pending, InboundScanAck("ack", 11, 12, "accept-scan-batch", 101, "A-01"))
        assertTrue(local.pendingItems(7, 9).isEmpty())
        assertEquals("remote_accepted", local.itemsForBatch("batch").single().status)
        assertTrue(database.autoReceiptDao().intents(7, 9).isEmpty())
    }

    @Test
    fun scanResultAndFrozenProofRollbackTogether() = runBlocking {
        seed()
        database.localInboundDao().finishBatch("batch")
        val before = database.autoReceiptDao().scan("batch:false:scan-batch")
        runCatching {
            database.withTransaction {
                database.localInboundDao().updateItemResult("scan-batch", "synced", "A-01", 101, 12, 0, "", 123, ackJson = "ack")
                error("interrupted")
            }
        }
        assertEquals("pending", database.localInboundDao().itemsForBatch("batch").single().status)
        assertEquals(before, database.autoReceiptDao().scan("batch:false:scan-batch"))
    }

    @Test
    fun migration17PreservesScansRequestsAndReportsMissingSource() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "receipt-migration-${java.util.UUID.randomUUID()}"
        val old = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            old.localInboundDao().insertBatch(LocalInboundBatchEntity("old", 7, 9, 11, "A-01", 101))
            old.localInboundDao().insertItem(LocalInboundItemEntity("kept", "old", 7, 9, 11, 12, "KEPT", "KEPT", "A-01"))
            old.localInboundDao().insertItem(LocalInboundItemEntity("missing", "old", 7, 9, 11, 13, "MISSING", "MISSING", "A-01"))
            old.crossOrderScanDao().save(CrossOrderScanEntity("cross-old", 7, 9, 11, "old", "inbound", "CROSS", "{}"))
            old.localInboundDao().finishBatch("old")
            old.localInboundDao().updateItemResult("missing", "synced", "A-01", 101, 13, 0, "", 123)
            old.localInboundDao().deleteTerminalItemForLabel(7, 9, "MISSING")
            val submission = AutoReceiptSubmissionEntity("original", 7, 9, 11, 11, "old", "[]", "fixed-body", jobId = "original-job")
            old.autoReceiptDao().insertSubmission(submission)
            old.openHelper.writableDatabase.execSQL("DROP TABLE auto_receipt_scan_items")
            removeCollectionMetadataColumns(old.openHelper.writableDatabase)
            old.openHelper.writableDatabase.version = 16
            old.close()
            val migrated = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().addMigrations(FscmDatabaseModule.MIGRATION_16_17, FscmDatabaseModule.MIGRATION_17_18, FscmDatabaseModule.MIGRATION_18_19, FscmDatabaseModule.MIGRATION_19_20, FscmDatabaseModule.MIGRATION_20_21).build()
            try {
                val kept = migrated.autoReceiptDao().scan("old:false:kept")!!
                assertEquals("KEPT", json.decodeFromString<LocalInboundItemEntity>(kept.scanJson).labelCode)
                val cross = migrated.autoReceiptDao().scan("old:true:cross-old")!!
                assertEquals("CROSS", json.decodeFromString<CrossOrderScanEntity>(cross.scanJson).rawCode)
                assertTrue(migrated.autoReceiptDao().scan("old:false:missing")!!.errorMessage.isNotBlank())
                assertEquals(submission, migrated.autoReceiptDao().submissions(7, 9).single())
                assertEquals(1, migrated.autoReceiptDao().intents(7, 9).size)
                assertTrue(migrated.autoReceiptDao().intents(8, 9).isEmpty())
            } finally {
                migrated.close()
            }
        } finally {
            old.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationRetainsLegacyCrossOrderDataWithoutCreatingIntents() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE cross_order_scans (id TEXT NOT NULL PRIMARY KEY, rawCode TEXT NOT NULL)")
                        db.execSQL("INSERT INTO cross_order_scans VALUES ('legacy','BOX-OLD')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.use {
            val db = it.writableDatabase
            FscmDatabaseModule.MIGRATION_15_16.migrate(db)
            db.query("SELECT rawCode,resultPayload FROM cross_order_scans WHERE id='legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("BOX-OLD", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            db.query("SELECT COUNT(*) FROM auto_receipt_intents").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
