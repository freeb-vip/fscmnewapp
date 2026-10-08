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

/** One row per carton, independent of how often it was scanned or which aliases were used. */
data class InboundCollectionBox(
    val snapshot: LocalInboundSnapshotEntity,
    val local: LocalInboundItemEntity?,
) {
    // Preview eligibility includes duplicate-scan rejection; that is not a removal from the collection plan.
    val eligible: Boolean get() = snapshot.receiptScanStatus != "manual_received" &&
        (snapshot.eligible || (snapshot.status.equals("normal", true) && snapshot.inboundStatus.lowercase() !in setOf("exception", "invalid")))
    val centerConfirmed: Boolean get() = eligible && snapshot.locationCode.isNotBlank() &&
        !snapshot.locationCode.trim().equals("FSCM-VIRTUAL-INBOUND", true)
    val collected: Boolean get() = eligible && (centerConfirmed || local != null)
    val draft: Boolean get() = local?.status == InboundItemStatus.Draft.value
    val pendingSync: Boolean get() = local?.status in setOf("pending", "syncing", "accept_remote_pending")
    val exception: Boolean get() = !eligible || local?.status in setOf("conflict_pending", "rejected")
    val productKey: String get() = snapshot.firstProductCode.trim().uppercase(java.util.Locale.ROOT).ifBlank { "__unknown__" }
}

internal fun inboundCollectionBoxes(
    snapshots: List<LocalInboundSnapshotEntity>,
    items: List<LocalInboundItemEntity>,
): List<InboundCollectionBox> {
    val byPlan = items.groupBy { it.boxPlanId }.mapValues { (_, rows) -> rows.maxBy { it.scannedAt } }
    val byLabel = items.groupBy { it.labelCode.uppercase(java.util.Locale.ROOT) }.mapValues { (_, rows) -> rows.maxBy { it.scannedAt } }
    val knownPlans = snapshots.map { it.boxPlanId }.toSet()
    val knownLabels = snapshots.map { it.labelCode.uppercase(java.util.Locale.ROOT) }.toSet()
    val missing = byPlan.values.filter {
        it.boxPlanId !in knownPlans && it.labelCode.uppercase(java.util.Locale.ROOT) !in knownLabels &&
            it.status in setOf("draft", "pending", "syncing", "conflict_pending", "rejected", "accept_remote_pending")
    }.map { local ->
        LocalInboundSnapshotEntity(
            userId = local.userId, namespaceId = local.namespaceId, sessionId = local.sessionId,
            boxPlanId = local.boxPlanId, labelCode = local.labelCode, boxNo = "", boxUid = "",
            status = "exception", inboundStatus = "exception", locationCode = "", eligible = false, snapshotAt = 0,
        )
    }
    return (snapshots + missing).distinctBy { it.boxPlanId }.map { snapshot ->
        val candidate = byPlan[snapshot.boxPlanId] ?: byLabel[snapshot.labelCode.uppercase(java.util.Locale.ROOT)]
        val local = candidate?.takeIf {
            it.status in setOf("draft", "pending", "syncing", "conflict_pending", "rejected", "accept_remote_pending") ||
                (it.status in setOf("synced", "remote_accepted", "remote_overridden") && it.updatedAt > snapshot.snapshotAt)
        }
        InboundCollectionBox(snapshot, local)
    }
}

internal val InboundWorkspace.collectionBoxes: List<InboundCollectionBox>
    get() = inboundCollectionBoxes(snapshotItems, collectionItems)

internal fun collectionProducts(documentId: Long, boxes: List<InboundCollectionBox>): List<ReceivingProductProgress> =
    boxes.groupBy { it.productKey }.map { (key, group) ->
        val first = group.first().snapshot
        val total = group.count { it.eligible }
        val collected = group.count { it.collected }
        val exceptions = group.count { it.exception }
        ReceivingProductProgress(
            business = ReceivingProductBusiness.Inbound, documentId = documentId,
            productKey = key, productId = first.firstProductId,
            productCode = first.firstProductCode, productName = first.firstProductName,
            productImage = "", skuCount = group.map { it.snapshot.firstSkuCode }.filter(String::isNotBlank).distinct().size,
            skuCodes = group.map { it.snapshot.firstSkuCode }.filter(String::isNotBlank).distinct(),
            sourceItemIds = emptyList(), boxPlanIds = group.map { it.snapshot.boxPlanId },
            totalBoxCount = total, completedBoxCount = collected, pendingBoxCount = total - collected,
            exceptionBoxCount = exceptions,
            workStatus = if (total > 0 && total == collected && exceptions == 0) "completed" else "pending",
            lastOperatedAt = group.maxOf { maxOf(it.snapshot.snapshotAt, it.local?.scannedAt ?: 0) },
            draftBoxCount = group.count { it.draft }, pendingSyncBoxCount = group.count { it.pendingSync },
        )
    }.sortedWith(compareByDescending<ReceivingProductProgress> { it.pendingBoxCount }.thenBy { it.productCode })

internal fun InboundCollectionBox.toProductBox(): ReceivingProductBoxDetail = ReceivingProductBoxDetail(
    id = "inbound-${snapshot.boxPlanId}", labelCode = snapshot.labelCode.ifBlank { snapshot.boxNo },
    skuCode = snapshot.firstSkuCode, skuName = snapshot.firstSkuName, boxNo = snapshot.boxNo, isMixed = snapshot.isMixed,
    status = when {
        exception -> "exception"
        draft -> "draft"
        pendingSync -> "pending_sync"
        collected -> "completed"
        else -> "pending"
    },
    locationCode = local?.localLocationCode ?: snapshot.locationCode,
    operator = snapshot.remoteScannedBy, operatedAt = local?.scannedAt ?: snapshot.remoteScannedAt,
    exceptionReason = local?.errorMessage?.takeIf(String::isNotBlank) ?: snapshot.exceptionReason,
    localPending = pendingSync && !exception, collected = collected, eligible = eligible,
)
