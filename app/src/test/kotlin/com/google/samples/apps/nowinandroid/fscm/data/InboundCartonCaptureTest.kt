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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InboundCartonCaptureTest {
    private lateinit var db: FscmDatabase
    private val label = "F681543E5224"
    private val session = LocalInboundSessionEntity(7, 2, 20, "RS-20")
    private val batch = LocalInboundBatchEntity("draft", 7, 2, 20, "A-01", 101)
    private val box = LocalInboundSnapshotEntity(
        7, 2, 20, 14121, label, "TH-DA999_20261102-M344-I12777-0006", "BOX-425-12777-006-1793838851576584921",
        "central_received", "pending", "", eligible = true, snapshotAt = 1, firstSkuCode = "B20-1-Grid", quantity = 1200,
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FscmDatabase::class.java).allowMainThreadQueries().build()
        db.localInboundDao().insertBatch(batch)
        db.localInboundDao().replaceSnapshots(session, emptyList())
        db.offlineCacheDao().upsert(OfflineCacheEntity(userId = 7, namespaceId = 2, cacheKey = "cross-order:20", payload = """{"source_session_id":20,"source_order_id":19,"warehouse_id":1}""", updatedAt = 1))
        db.crossOrderCatalogDao().insert(listOf(catalogBox(14121, 19)))
    }

    private fun catalogBox(plan: Long, order: Long) = buildJsonObject {
        put("box_plan_id", plan)
        put("label_code", label)
        put("box_no", "TH-$plan")
        put("box_uid", "BOX-$plan")
        put("target_order_id", order)
        put("quantity", 1200)
    }.toCatalogBox(7, 2, 1)

    @After
    fun tearDown() = db.close()

    private suspend fun capture(offline: Boolean, refresh: suspend () -> Unit = { error("Unexpected refresh") }): LocalInboundItemEntity {
        val snapshot = resolveInboundCartonWithRefresh(offline, refresh) {
            checkNotNull(db.resolveInboundCarton(batch, label) { error("Unexpected cross-order confirmation") })
        }
        return db.localInboundDao().recordDraftBox(batch, snapshot, label, null)
    }

    @Test
    fun updatedOrderWorksOfflineAndDuplicateDoesNotCreateAnotherDraft() = runBlocking {
        val missing = assertFailsWith<InboundClientException> { capture(true) }
        assertEquals(InboundClientError.BoxOfflineNotFound, missing.reason)
        assertEquals("20", missing.detail)
        db.localInboundDao().replaceSnapshots(session, listOf(box))
        val item = capture(true)
        val stored = db.localInboundDao().snapshots(7, 2, 20).single()
        assertEquals(14121L, item.boxPlanId)
        assertEquals(listOf(InboundSkuQuantity("B20-1-Grid", 1200)), resolveInboundSkuQuantities(item.rawCode, stored.firstSkuCode, stored.quantity))
        assertEquals(InboundClientError.BoxScannedInCurrentBatch, assertFailsWith<InboundClientException> { capture(true) }.reason)
        assertEquals(listOf(item), db.localInboundDao().itemsForBatch(batch.id))
    }

    @Test
    fun onlineMissRefreshesOnceAndRetriesTheSameScan() = runBlocking {
        var refreshes = 0
        assertEquals(
            14121L,
            capture(false) {
                refreshes++
                db.localInboundDao().replaceSnapshots(session, listOf(box))
            }.boxPlanId,
        )
        assertEquals(1, refreshes)
    }

    @Test
    fun disconnectedOnlineSessionNamesTheOrderToUpdate() = runBlocking {
        val missing = assertFailsWith<InboundClientException> {
            capture(false) { throw java.net.UnknownHostException() }
        }
        assertEquals(InboundClientError.BoxOfflineNotFound, missing.reason)
        assertEquals("20", missing.detail)
    }

    @Test
    fun repeatedMissStopsAfterOneRefresh() = runBlocking {
        var refreshes = 0
        val missing = assertFailsWith<InboundClientException> { capture(false) { refreshes++ } }
        assertEquals(InboundClientError.BoxNotInSession, missing.reason)
        assertEquals("20", missing.detail)
        assertEquals(label, missing.scannedCode)
        assertEquals(1, refreshes)
        assertTrue(db.localInboundDao().itemsForBatch(batch.id).isEmpty())
    }

    @Test
    fun unverifiedPrintedLabelCannotCreateADraftEvenIfCandidateIsPresent() = runBlocking {
        val raw = "759D55F92653"
        db.localInboundDao().replaceSnapshots(session, listOf(box.copy(boxPlanId = 14167, labelCode = "0FF452405854")))
        val response = Json.parseToJsonElement("""{"code":40948,"data":{"reason_code":"physical_identity_unverified","canonical_label":{"box_plan_id":0}}}""")
        val rejection = assertFailsWith<InboundClientException> {
            db.previewMissingInboundCarton(batch, raw) { throw FscmApiException("unverified", 409, response) }
        }
        assertEquals(InboundClientError.PhysicalIdentityUnverified, rejection.reason)
        assertEquals(raw, rejection.scannedCode)
        assertTrue(db.localInboundDao().itemsForBatch(batch.id).isEmpty())
        assertTrue(db.crossOrderScanDao().pending(7, 2).isEmpty())
    }

    @Test
    fun reviewSubmissionPreservesOneOriginalEventAndRequiresTheSameActiveLocation() = runBlocking {
        val raw = "759D55F92653"
        val request = buildJsonObject {
            put("raw_code", raw)
            put("request_id", "original")
            put("client_event_id", "original")
            put("warehouse_location_id", batch.warehouseLocationId)
            put("location_code", batch.locationCode)
        }
        val task = CrossOrderScanEntity("original", 7, 2, 20, batch.id, "inbound", raw, request.toString())
        val offer = InboundReviewOffer(task, batch.locationCode)
        assertFailsWith<IllegalStateException> { db.stageOriginalScan(InboundReviewOffer(task, "other")) }
        assertTrue(db.crossOrderScanDao().pending(7, 2).isEmpty())
        assertTrue(db.stageOriginalScan(offer))
        assertEquals(false, db.stageOriginalScan(InboundReviewOffer(task.copy(id = "second"), batch.locationCode)))
        val saved = db.crossOrderScanDao().pending(7, 2).single()
        assertEquals("original", saved.id)
        assertEquals(request.toString(), saved.requestPayload)
        assertTrue(db.localInboundDao().itemsForBatch(batch.id).isEmpty())
    }

    @Test
    fun verifiedOriginalLabelUsesOnlyTheSameSessionPlanAndUploadKeepsPhysicalIdentity() = runBlocking {
        val raw = "759D55F92653"
        val target = box.copy(boxPlanId = 14167, labelCode = "0FF452405854", firstSkuCode = "B20-4-Grid", quantity = 800)
        db.localInboundDao().replaceSnapshots(session, listOf(target))
        fun preview(plan: Long) = Json.parseToJsonElement(
            """{"item":{"status":"normal","receiving_session_id":20,"resolution_type":"adjustment_alias","source_box_plan_id":13949,"label_code":"0FF452405854","box_plan_id":$plan}}""",
        ).jsonObject
        assertEquals(
            InboundClientError.BoxOwnershipConflict,
            assertFailsWith<InboundClientException> { db.previewMissingInboundCarton(batch, raw) { preview(999) } }.reason,
        )
        val verified = db.previewMissingInboundCarton(batch, raw) { preview(14167) }
        assertEquals(target, verified)
        val draft = db.localInboundDao().recordDraftBox(batch, verified, raw, null)
        val request = inboundBatchScanRequest(batch.id, listOf(draft)).getValue("items").jsonArray.single().jsonObject
        assertEquals(14167L, request.crossID("box_plan_id"))
        assertEquals(raw, request.crossText("raw_code"))
        assertNull(request["box_no"])
        assertEquals(
            InboundClientError.BoxScannedInCurrentBatch,
            assertFailsWith<InboundClientException> { db.localInboundDao().recordDraftBox(batch, target, target.labelCode, null) }.reason,
        )
    }

    @Test
    fun conflictingWarehousePlanBlocksAnExactLocalHitAndForeignBoxRequiresConfirmation() = runBlocking {
        val catalog = db.crossOrderCatalogDao()
        db.localInboundDao().replaceSnapshots(session, listOf(box))
        catalog.insert(listOf(catalogBox(14122, 21)))
        assertEquals(InboundClientError.BoxOwnershipConflict, assertFailsWith<InboundClientException> { capture(false) }.reason)
        db.localInboundDao().replaceSnapshots(session, emptyList())
        catalog.clear(7, 2, 1)
        catalog.insert(listOf(catalogBox(14122, 21)))
        var confirmation: JsonObject? = null
        assertNull(db.resolveInboundCarton(batch, label) { confirmation = it })
        assertEquals(21L, confirmation?.crossID("target_order_id"))
        assertTrue(db.localInboundDao().itemsForBatch(batch.id).isEmpty())
    }

    @Test
    fun interruptedSnapshotReplacementPreservesThePreviousDetailsAndDraft() = runBlocking {
        db.localInboundDao().replaceSnapshots(session, listOf(box))
        val item = capture(true)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_snapshot BEFORE INSERT ON local_inbound_snapshots BEGIN SELECT RAISE(ABORT,'interrupted'); END")
        assertTrue(runCatching { db.localInboundDao().replaceSnapshots(session, listOf(box.copy(boxPlanId = 99))) }.isFailure)
        assertEquals(listOf(box), db.localInboundDao().snapshots(7, 2, 20))
        assertEquals(listOf(item), db.localInboundDao().itemsForBatch(batch.id))
    }

    @Test
    fun migrationExpandsLargeLegacyWarehouseAndPreservesOfflineLookup() = runBlocking {
        val sql = db.openHelper.writableDatabase
        db.crossOrderCatalogDao().clear(7, 2, 1)
        val items = (1..9546).joinToString(",") { id ->
            if (id == 9546) {
                catalogBox(14121, 19).payload
            } else {
                """{"box_plan_id":$id,"label_code":"L$id","box_uid":"UID$id","box_no":"NO$id","sku_code":"${"x".repeat(180)}"}"""
            }
        }
        assertTrue(items.length > 2 * 1024 * 1024)
        db.offlineCacheDao().upsert(OfflineCacheEntity(userId = 7, namespaceId = 2, cacheKey = "cross-order-wh:1", payload = """{"items":[$items]}""", updatedAt = 1))
        FscmDatabaseModule.MIGRATION_22_23.migrate(sql)
        val hit = db.crossOrderCatalogDao().matches(7, 2, 1, listOf(label)).single()
        assertEquals(14121L, hit.boxPlanId)
        assertEquals("{}", db.offlineCacheDao().find(7, 2, "cross-order-wh:1")?.payload)
        sql.query("SELECT count(*) FROM cross_order_catalog_boxes").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(9546, cursor.getInt(0))
        }
        assertTrue(db.crossOrderCatalogDao().matches(8, 2, 1, listOf(label)).isEmpty())
    }
}
