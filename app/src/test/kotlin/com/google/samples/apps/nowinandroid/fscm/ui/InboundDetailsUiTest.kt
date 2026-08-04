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

package com.google.samples.apps.nowinandroid.fscm.ui

import com.google.samples.apps.nowinandroid.fscm.data.InboundItemStatus
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundItemEntity
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundProductSummaryEntity
import com.google.samples.apps.nowinandroid.fscm.data.pendingInboundProducts
import org.junit.Test
import kotlin.test.assertEquals

class InboundDetailsUiTest {
    @Test
    fun pendingProducts_filtersCompletedSearchesLocallyAndSortsByPendingCount() {
        val products = listOf(
            product(id = 1, code = "P-RED", name = "Red chair", inbounded = 28, total = 50, pending = 22),
            product(id = 2, code = "P-BLUE", name = "Blue chair", inbounded = 10, total = 50, pending = 40),
            product(id = 3, code = "P-DONE", name = "Done", inbounded = 20, total = 20, pending = 0),
        )

        assertEquals(listOf("P-BLUE", "P-RED"), pendingInboundProducts(products).map { it.productCode })
        val result = pendingInboundProducts(products, "red")
        assertEquals(listOf("P-RED"), result.map { it.productCode })
        assertEquals(28, result.single().inboundedBoxCount)
        assertEquals(50, result.single().totalBoxCount)
    }

    @Test
    fun syncTabs_keepPendingConflictAndFailedSeparate() {
        val items = listOf(
            syncItem("pending", InboundItemStatus.Pending),
            syncItem("syncing", InboundItemStatus.Syncing),
            syncItem("conflict", InboundItemStatus.ConflictPending),
            syncItem("failed", InboundItemStatus.Rejected),
            syncItem("synced", InboundItemStatus.Synced),
        )

        assertEquals(
            listOf("pending", "syncing"),
            localSyncItemsForTab(items, InboundSyncDetailsTab.Pending).map { it.requestId },
        )
        assertEquals(
            listOf("conflict"),
            localSyncItemsForTab(items, InboundSyncDetailsTab.Conflict).map { it.requestId },
        )
        assertEquals(
            listOf("failed"),
            localSyncItemsForTab(items, InboundSyncDetailsTab.Failed).map { it.requestId },
        )
    }

    private fun product(
        id: Long,
        code: String,
        name: String,
        inbounded: Int,
        total: Int,
        pending: Int,
    ) = LocalInboundProductSummaryEntity(
        namespaceId = 9,
        sessionId = 11,
        productId = id,
        productCode = code,
        productName = name,
        productImage = "",
        skuCount = 1,
        totalBoxCount = total,
        inboundedBoxCount = inbounded,
        pendingBoxCount = pending,
        exceptionBoxCount = 0,
        snapshotAt = 1,
    )

    private fun syncItem(requestId: String, status: InboundItemStatus) = LocalInboundItemEntity(
        requestId = requestId,
        batchId = "batch",
        namespaceId = 9,
        sessionId = 11,
        boxPlanId = requestId.hashCode().toLong(),
        labelCode = requestId,
        rawCode = requestId,
        localLocationCode = "A-01",
        status = status.value,
    )
}
