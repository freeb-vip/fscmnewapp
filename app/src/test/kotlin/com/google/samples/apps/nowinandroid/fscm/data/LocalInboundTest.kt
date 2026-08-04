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

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalInboundTest {
    @Test
    fun snapshotIsStaleWhenItReachesTwelveHours() {
        val now = 1_800_000_000_000L
        val session =
            LocalInboundSessionEntity(
                namespaceId = 1,
                sessionId = 10,
                sessionCode = "RS-10",
                snapshotComplete = true,
                snapshotAt = now - 12 * 60 * 60 * 1000L + 1,
            )

        assertFalse(session.isSnapshotStale(now))
        assertTrue(session.copy(snapshotAt = now - 12 * 60 * 60 * 1000L).isSnapshotStale(now))
        assertTrue(session.copy(snapshotComplete = false).isSnapshotStale(now))
    }

    @Test
    fun locationValidation_matchesBackendRulesAndNormalizesUppercase() {
        assertTrue(isValidInboundLocation("A-01"))
        assertTrue(isValidInboundLocation("a/01_pick\n"))
        assertEquals("A/01_PICK", normalizeInboundLocation("a/01_pick\n"))
        assertFalse(isValidInboundLocation("A\u0007-01"))
        assertFalse(isValidInboundLocation(""))
        assertFalse(isValidInboundLocation("A".repeat(101)))
    }

    @Test
    fun inboundRequests_includeFormalWarehouseLocationId() {
        val item =
            item("BOX-A", InboundItemStatus.Pending).copy(
                localWarehouseLocationId = 88,
                remoteLocationCode = "OLD-01",
            )

        val scan = inboundScanRequest(item)
        val correction = inboundCorrectionRequest(item, "correction-1")

        assertEquals(88, scan.getValue("warehouse_location_id").jsonPrimitive.long)
        assertEquals("A-01", scan.getValue("location_code").jsonPrimitive.contentOrNull)
        assertEquals(88, correction.getValue("warehouse_location_id").jsonPrimitive.long)
        assertEquals("OLD-01", correction.getValue("expected_location_code").jsonPrimitive.contentOrNull)
    }

    @Test
    fun inboundBatchRequest_groupsBySessionAndLocationAndChunksAtOneHundred() {
        val items =
            (1..205).map { index ->
                item("BOX-$index", InboundItemStatus.Pending).copy(requestId = "request-$index")
            } +
                item("OTHER-LOCATION", InboundItemStatus.Pending).copy(
                    requestId = "request-other-location",
                    localLocationCode = "B-01",
                    localWarehouseLocationId = 9,
                )

        val chunks = groupInboundSyncItems(items)

        assertEquals(listOf(100, 100, 5, 1), chunks.map(List<*>::size))
        assertTrue(chunks.take(3).all { chunk -> chunk.all { it.localLocationCode == "A-01" } })
        val request = inboundBatchScanRequest("batch-1", chunks.first())
        assertEquals(100, request.getValue("items").jsonArray.size)
        assertEquals(
            "request-1",
            request.getValue("items").jsonArray.first().jsonObject.getValue("request_id").jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun sessionList_keepsLocalPreparedSessionsMissingFromRemoteCache() {
        val ids =
            mergeInboundSessionIds(
                remote = listOf(ReceivingSession(id = 1), ReceivingSession(id = 2)),
                local =
                listOf(
                    LocalInboundSessionEntity(namespaceId = 9, sessionId = 2, sessionCode = "RS-2"),
                    LocalInboundSessionEntity(namespaceId = 9, sessionId = 3, sessionCode = "RS-3"),
                ),
            )

        assertEquals(listOf(1L, 2L, 3L), ids.toList())
    }

    @Test
    fun pendingLocalBox_completesLocalProgressWithoutDoubleCountingRemote() {
        val snapshots =
            listOf(
                snapshot("BOX-A"),
                snapshot("BOX-B", location = "B-01"),
                snapshot("BOX-C", eligible = false),
            )
        val progress = calculateInboundProgress(snapshots, listOf(item("BOX-A", InboundItemStatus.Pending)))

        assertEquals(1, progress.localContribution)
        assertEquals(0, progress.remaining)
        assertEquals(1, progress.pending)
        assertEquals(0, progress.conflicts)
    }

    @Test
    fun rejectedBox_doesNotCountAsAssigned() {
        val progress =
            calculateInboundProgress(
                snapshots = listOf(snapshot("BOX-A")),
                items = listOf(item("BOX-A", InboundItemStatus.Rejected)),
            )

        assertEquals(0, progress.localContribution)
        assertEquals(1, progress.remaining)
        assertEquals(1, progress.rejected)
    }

    @Test
    fun conflictCountsAsLocallyAssignedButRemainsUnresolved() {
        val progress =
            calculateInboundProgress(
                snapshots = listOf(snapshot("BOX-A")),
                items = listOf(item("BOX-A", InboundItemStatus.ConflictPending)),
            )

        assertEquals(1, progress.localContribution)
        assertEquals(0, progress.remaining)
        assertEquals(1, progress.conflicts)
    }

    @Test
    fun refreshedRemoteLocation_removesLocalContribution() {
        val progress =
            calculateInboundProgress(
                snapshots = listOf(snapshot("BOX-A", location = "A-01")),
                items = listOf(item("BOX-A", InboundItemStatus.Synced)),
            )

        assertEquals(0, progress.localContribution)
        assertEquals(0, progress.remaining)
    }

    @Test
    fun reconciliation_marksMatchingCenterLocationAsSynced() {
        val result =
            reconcileInboundItem(
                item("BOX-A", InboundItemStatus.ConflictPending),
                snapshot("BOX-A", location = "A-01"),
            )

        assertEquals(InboundItemStatus.Synced.value, result?.status)
        assertEquals("A-01", result?.remoteLocationCode)
    }

    @Test
    fun reconciliation_keepsDifferentCenterLocationAsConflict() {
        val result =
            reconcileInboundItem(
                item("BOX-A", InboundItemStatus.Rejected),
                snapshot("BOX-A", location = "B-02"),
            )

        assertEquals(InboundItemStatus.ConflictPending.value, result?.status)
        assertEquals("B-02", result?.remoteLocationCode)
    }

    @Test
    fun reconciliation_retriesEligibleUnassignedCenterItem() {
        val result =
            reconcileInboundItem(
                item("BOX-A", InboundItemStatus.Rejected),
                snapshot("BOX-A"),
            )

        assertEquals(InboundItemStatus.Pending.value, result?.status)
        assertEquals(0, result?.errorCode)
    }

    @Test
    fun reconciliation_preservesCenterExceptionReasonAsRejected() {
        val result =
            reconcileInboundItem(
                item("BOX-A", InboundItemStatus.Pending),
                snapshot("BOX-A", eligible = false).copy(exceptionReason = "箱唛已作废"),
            )

        assertEquals(InboundItemStatus.Rejected.value, result?.status)
        assertEquals("箱唛已作废", result?.errorMessage)
    }

    @Test
    fun inboundSkuDetails_preferMultiSkuQrPayload() {
        val items =
            resolveInboundSkuQuantities(
                rawCode = "ABCDEF123456,SKU-A=3,SKU-B=2",
                fallbackSkuCode = "FALLBACK",
                fallbackQuantity = 99,
            )

        assertEquals(
            listOf(
                InboundSkuQuantity("SKU-A", 3),
                InboundSkuQuantity("SKU-B", 2),
            ),
            items,
        )
    }

    @Test
    fun inboundSkuDetails_fallBackToCachedMappingAndPreserveUnknownQuantity() {
        assertEquals(
            listOf(InboundSkuQuantity("SKU-C", 12)),
            resolveInboundSkuQuantities("ABCDEF123456", "SKU-C", 12),
        )
        assertEquals(
            listOf(InboundSkuQuantity("SKU-D", null)),
            resolveInboundSkuQuantities("ABCDEF123456", "SKU-D", 0),
        )
        assertTrue(resolveInboundSkuQuantities("ABCDEF123456").isEmpty())
    }

    @Test
    fun inboundDraftMetrics_countDistinctSkusAndFlagIncompleteQuantity() {
        val details =
            listOf(
                InboundDraftBoxDetail(
                    item = item("BOX-A", InboundItemStatus.Draft),
                    skuItems = listOf(InboundSkuQuantity("SKU-A", 3), InboundSkuQuantity("sku-b", 2)),
                ),
                InboundDraftBoxDetail(
                    item = item("BOX-B", InboundItemStatus.Draft),
                    skuItems = listOf(InboundSkuQuantity("SKU-A", null)),
                ),
            )

        assertEquals(InboundDraftMetrics(2, 2, 5, false), calculateInboundDraftMetrics(details))
    }

    @Test
    fun inboundDraftDetails_areSortedNewestFirst() {
        val details =
            listOf(
                InboundDraftBoxDetail(item("BOX-A", InboundItemStatus.Draft).copy(scannedAt = 10), emptyList()),
                InboundDraftBoxDetail(item("BOX-B", InboundItemStatus.Draft).copy(scannedAt = 30), emptyList()),
                InboundDraftBoxDetail(item("BOX-C", InboundItemStatus.Draft).copy(scannedAt = 20), emptyList()),
            )

        assertEquals(
            listOf("BOX-B", "BOX-C", "BOX-A"),
            sortInboundDraftBoxDetails(details).map { it.item.labelCode },
        )
    }

    private fun snapshot(
        label: String,
        location: String = "",
        eligible: Boolean = true,
    ) = LocalInboundSnapshotEntity(
        namespaceId = 1,
        sessionId = 10,
        boxPlanId = label.hashCode().toLong(),
        labelCode = label,
        boxNo = label,
        boxUid = "",
        status = "received",
        inboundStatus = if (eligible) "pending" else "exception",
        locationCode = location,
        eligible = eligible,
        snapshotAt = 1,
    )

    private fun item(label: String, status: InboundItemStatus) =
        LocalInboundItemEntity(
            requestId = "request-$label",
            batchId = "batch-1",
            namespaceId = 1,
            sessionId = 10,
            boxPlanId = label.hashCode().toLong(),
            labelCode = label,
            rawCode = label,
            localLocationCode = "A-01",
            status = status.value,
        )
}
