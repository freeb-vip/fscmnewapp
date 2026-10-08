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

import com.google.samples.apps.nowinandroid.fscm.ui.ReceivingProductFilter
import com.google.samples.apps.nowinandroid.fscm.ui.filterReceivingProducts
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboundCollectionTest {
    private fun box(id: Long, product: String = "A") = LocalInboundSnapshotEntity(
        userId = 7, namespaceId = 9, sessionId = 11, boxPlanId = id, labelCode = "BOX-$id", boxNo = "$id", boxUid = "$id",
        status = "normal", inboundStatus = "pending", locationCode = "", eligible = true, snapshotAt = 100,
        firstProductCode = product, firstProductName = product, firstSkuCode = "$product-1", firstSkuName = "Name $product",
    )
    private fun scan(id: Long, status: String = "draft") = LocalInboundItemEntity(
        requestId = "scan-$id", batchId = "batch", userId = 7, namespaceId = 9, sessionId = 11, boxPlanId = id,
        labelCode = "BOX-$id", rawCode = "BOX-$id", localLocationCode = "A-01", status = status, scannedAt = 200,
    )

    @Test
    fun completedProductsRemainInAllAndCompletedAndCountsAgreeWithDetails() {
        val boxes = inboundCollectionBoxes(listOf(box(1), box(2), box(3, "B")), listOf(scan(1, "pending"), scan(2)))
        val products = collectionProducts(11, boxes)
        assertEquals(2, filterReceivingProducts(products, "", ReceivingProductFilter.All).size)
        assertEquals(listOf("A"), filterReceivingProducts(products, "", ReceivingProductFilter.Completed).map { it.productCode })
        assertEquals(listOf("B"), filterReceivingProducts(products, "", ReceivingProductFilter.Pending).map { it.productCode })
        products.forEach { product ->
            val rows = boxes.filter { it.snapshot.boxPlanId in product.boxPlanIds }.map { it.toProductBox() }
            assertEquals(product.pendingBoxCount, rows.count { it.eligible && !it.collected })
            assertEquals(product.totalBoxCount, product.completedBoxCount + product.pendingBoxCount)
        }
        assertEquals(1, products.first { it.productCode == "A" }.draftBoxCount)
        assertEquals(1, products.first { it.productCode == "A" }.pendingSyncBoxCount)
    }

    @Test
    fun mixedAndUnknownCartonsAreCountedExactlyOnceAndExceptionsStaySeparate() {
        val boxes = inboundCollectionBoxes(listOf(box(1).copy(isMixed = true), box(1), box(2, ""), box(3).copy(status = "voided", eligible = false)), emptyList())
        val products = collectionProducts(11, boxes)
        assertEquals(2, products.sumOf { it.totalBoxCount })
        assertEquals(1, products.sumOf { it.exceptionBoxCount })
        assertTrue(products.any { it.productKey == "__unknown__" && it.totalBoxCount == 1 })
        assertEquals("Name A", boxes.first().toProductBox().skuName)
        assertTrue(boxes.first().toProductBox().isMixed)
    }

    @Test
    fun draftsCancellationDuplicatesAndRebindNeverInflateCounts() {
        val center = box(1).copy(locationCode = "A-01")
        assertEquals(1, inboundCollectionBoxes(listOf(center), listOf(scan(1), scan(1).copy(requestId = "duplicate"))).count { it.collected })
        assertEquals(1, inboundCollectionBoxes(listOf(box(1)), listOf(scan(1))).count { it.collected })
        assertEquals(0, inboundCollectionBoxes(listOf(box(1)), emptyList()).count { it.collected })
        assertFalse(inboundCollectionBoxes(listOf(box(1).copy(locationCode = "FSCM-VIRTUAL-INBOUND")), emptyList()).single().collected)
        assertTrue(inboundCollectionBoxes(listOf(box(1).copy(eligible = false)), listOf(scan(1))).single().collected)
    }

    @Test
    fun rejectedAndConflictScansAreVisibleButNeverBecomeNormalCompletedProducts() {
        listOf("rejected", "conflict_pending").forEach { status ->
            val product = collectionProducts(11, inboundCollectionBoxes(listOf(box(1)), listOf(scan(1, status)))).single()
            assertEquals(1, product.completedBoxCount)
            assertEquals(1, product.exceptionBoxCount)
            assertTrue(filterReceivingProducts(listOf(product), "", ReceivingProductFilter.Completed).isEmpty())
        }
    }

    @Test
    fun successfulSyncBridgesSnapshotRefreshWithoutOverridingNewerCenterFacts() {
        val synced = scan(1, "synced").copy(updatedAt = 200)
        assertTrue(inboundCollectionBoxes(listOf(box(1)), listOf(synced)).single().collected)
        assertFalse(inboundCollectionBoxes(listOf(box(1).copy(snapshotAt = 300)), listOf(synced)).single().collected)
    }

    @Test
    fun orphanLocalWorkRemainsVisibleAsExceptionWithoutInflatingThePlan() {
        val boxes = inboundCollectionBoxes(emptyList(), listOf(scan(1)))
        val product = collectionProducts(11, boxes).single()
        assertEquals(0, product.totalBoxCount)
        assertEquals(1, product.exceptionBoxCount)
        assertEquals("BOX-1", boxes.single().toProductBox().labelCode)
    }

    @Test
    fun scanPreviewPreservesCachedProductMetadata() {
        val cached = box(1).copy(isMixed = true)
        val batch = LocalInboundBatchEntity("batch", 7, 9, 11, "A-01")
        val preview = ReceivingScanResponse(item = ReceivingScanItem(boxPlanId = 1, labelCode = "BOX-1", status = "normal"))
        val updated = inboundSnapshotFromPreview(batch, cached, preview, ReceivingCurrentAssignment())
        assertEquals(cached.firstProductCode, updated.firstProductCode)
        assertEquals(cached.firstSkuName, updated.firstSkuName)
        assertTrue(updated.isMixed)
    }
}
