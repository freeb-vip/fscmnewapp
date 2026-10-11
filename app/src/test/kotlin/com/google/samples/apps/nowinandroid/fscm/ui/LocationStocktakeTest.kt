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

import com.google.samples.apps.nowinandroid.fscm.data.CountTask
import com.google.samples.apps.nowinandroid.fscm.data.CountTaskDetail
import com.google.samples.apps.nowinandroid.fscm.data.STOCKTAKE_MAX_QUANTITY
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeContext
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeDraft
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeLookup
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeRecord
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeStock
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeSubmissionInput
import com.google.samples.apps.nowinandroid.fscm.data.StocktakeWorkspace
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeNeedsConfirmation
import com.google.samples.apps.nowinandroid.fscm.data.stocktakeQuantity
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationStocktakeTest {
    private val stock = StocktakeStock("row", "location", "F0-01-02", "TB999-VIP-BOX", onHand = 201, unit = "PCS")
    private val lookup = StocktakeLookup("ready", stock, "snapshot", Instant.now().plusSeconds(120).toString())
    private val state = LocationStocktakeUiState(
        allowed = true,
        canWrite = true,
        userId = 7,
        showTasks = false,
        task = CountTask("task", sku = "TB999-VIP-BOX", status = "in_progress", claimedBy = 7),
        context = StocktakeContext(StocktakeWorkspace(status = "configured"), writeEnabled = true),
        lookup = lookup,
        quantity = "201",
        stage = StocktakeStage.Count,
    )

    @Test
    fun quantitiesRejectDecimalsSignsOverflowAndMissingValues() {
        for (text in listOf("", "-1", "1.5", "1e3", "+2", " 1", "9007199254740992", "99999999999999999999")) assertNull(stocktakeQuantity(text))
        assertEquals(0, stocktakeQuantity("0"))
        assertEquals(STOCKTAKE_MAX_QUANTITY, stocktakeQuantity(STOCKTAKE_MAX_QUANTITY.toString()))
        assertFalse(stocktakeNeedsConfirmation(201, 101))
        assertFalse(stocktakeNeedsConfirmation(201, 301))
        assertTrue(stocktakeNeedsConfirmation(201, 100))
        assertTrue(stocktakeNeedsConfirmation(201, 302))
        assertTrue(stocktakeNeedsConfirmation(201, 0))
    }

    @Test
    fun countEditingAndPendingRequestsDisableScanning() {
        assertTrue(state.readyToSubmit)
        assertFalse(state.canScan)
        assertTrue(state.copy(stage = StocktakeStage.Sku).canScan)
        val pending = StocktakeSubmissionInput("snapshot", "request", 0, "zero")
        assertFalse(state.copy(stage = StocktakeStage.Sku, pending = pending).canScan)
        assertFalse(state.copy(pending = pending).readyToSubmit)
        assertFalse(state.copy(busy = true).readyToSubmit)
        assertFalse(state.copy(allowed = false).readyToSubmit)
        assertFalse(state.copy(canWrite = false).readyToSubmit)
        val denied = state.copy(context = state.context!!.copy(writeEnabled = false))
        assertFalse(denied.readyToSubmit)
        assertFalse(denied.canQuick)
        assertFalse(denied.copy(stage = StocktakeStage.Sku).canScan)
        assertFalse(state.copy(expired = true).readyToSubmit)
        assertFalse(state.dirty)
        assertTrue(state.copy(quantity = "0").dirty)
    }

    @Test
    fun snapshotsAllowKnownZeroStockAndRequireUnexpiredServerIdentity() {
        assertTrue(lookup.usable())
        val zero = lookup.copy(stock = stock.copy(onHand = 0))
        assertTrue(zero.usable())
        assertTrue(state.copy(lookup = zero, quantity = "0").readyToSubmit)
        assertTrue(state.copy(lookup = zero, quantity = "12").readyToSubmit)
        assertFalse(zero.copy(status = "sku_not_found").usable())
        assertFalse(lookup.copy(stock = stock.copy(onHand = -1)).usable())
        assertFalse(lookup.copy(stock = stock.copy(onHand = null)).usable())
        assertFalse(lookup.copy(snapshotId = "").usable())
        assertFalse(lookup.copy(expiresAt = Instant.now().minusSeconds(1).toString()).usable())
        assertFalse(lookup.copy(expiresAt = "invalid").usable())
        val saved = StocktakeSubmissionInput("snapshot", "original-request", 10, "manual", "bound-token", 10)
        val encoded = Json.encodeToString(saved)
        assertEquals(saved, Json.decodeFromString<StocktakeSubmissionInput>(encoded))
    }

    @Test
    fun taskOwnershipAndLifecycleProtectScanningAndSubmission() {
        for (status in listOf("pending", "processing", "needs_review", "completed", "cancelled")) {
            val inactive = state.copy(task = state.task!!.copy(status = status), stage = StocktakeStage.Location)
            assertFalse(inactive.canScan)
            assertFalse(inactive.readyToSubmit)
        }
        assertFalse(state.copy(task = state.task!!.copy(claimedBy = 8), stage = StocktakeStage.Location).canScan)
        assertFalse(state.copy(showTasks = true, stage = StocktakeStage.Sku).canScan)
        assertFalse(state.copy(review = true, stage = StocktakeStage.Location).canScan)
        assertFalse(state.copy(progressFailed = true).readyToSubmit)
        val conflicted = state.copy(draftConflict = StocktakeDraft(quantity = "5"), stage = StocktakeStage.Location)
        assertFalse(conflicted.canQuick)
        assertFalse(conflicted.canScan)
        assertFalse(conflicted.readyToSubmit)
        assertFalse(state.copy(pending = StocktakeSubmissionInput("snapshot", "request", 10, "manual")).canQuick)
        assertTrue(state.copy(task = state.task!!.copy(status = "completed")).canQuick)
    }

    @Test
    fun taskDraftsRetainIdentityQuantityAndOriginalRequest() {
        val request = StocktakeSubmissionInput("snapshot", "same-request", 0, "zero", countTaskId = "task")
        val draft = StocktakeDraft(location = "P01", sku = "SKU", quantity = "0", pending = request, taskVersion = 9)
        assertEquals(draft, Json.decodeFromString<StocktakeDraft>(Json.encodeToString(draft)))
        val quick = StocktakeDraft(location = "P01", createRequestId = "original-create")
        assertEquals(quick, Json.decodeFromString<StocktakeDraft>(Json.encodeToString(quick)))
        val detail = Json.decodeFromString<CountTaskDetail>("""{"task":{"id":"task","status":"completed","draft_quantity":0,"claimed_by":7},"records":[],"events":[]}""")
        assertEquals(0, detail.task.draftQuantity)
        assertEquals("completed", detail.task.status)
    }

    @Test
    fun restartResolvesTerminalServerResultsBeforeRestoringPendingWrites() {
        val request = StocktakeSubmissionInput("snapshot", "request", 0, "zero", countTaskId = "task", countTaskVersion = 2)
        val draft = StocktakeDraft(pending = request)
        val task = state.task!!.copy(lastRecordId = "record", version = 3)
        val record = StocktakeRecord("record", "succeeded", "P01", "SKU", 20, 0, -20, requestId = "request", snapshotId = "snapshot")
        assertNull(restoreStocktakeRequest(task.copy(status = "completed"), listOf(record), draft))
        assertNull(restoreStocktakeRequest(task.copy(status = "pending"), emptyList(), draft))
        assertNull(restoreStocktakeRequest(task, listOf(record.copy(status = "cancelled")), draft))
        assertEquals(request, restoreStocktakeRequest(task, emptyList(), draft))
        assertEquals("request", restoreStocktakeRequest(task.copy(status = "needs_review"), listOf(record.copy(status = "result_unknown")), StocktakeDraft())?.requestId)
        assertEquals(3, restoreStocktakeRequest(task, listOf(record.copy(status = "awaiting_confirmation")), StocktakeDraft())?.countTaskVersion)
    }
}
