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

import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientError
import com.google.samples.apps.nowinandroid.fscm.data.InboundClientException
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

class InboundErrorTextTest {
    @Test
    fun skuAmbiguityPreservesOriginalCodeAndUpgradeIsExplicit() {
        val error = text(InboundClientError.SkuAmbiguous, "B13-SINGLE-BROWN")
        assertEquals(R.string.inbound_sku_ambiguous, error.id)
        assertEquals(listOf("B13-SINGLE-BROWN"), error.args)
        assertEquals(R.string.inbound_sku_server_upgrade_required, text(InboundClientError.SkuServerUpgradeRequired).id)
    }

    @Test
    fun centerLocationRejectionDoesNotClaimOnlyTheLocalCacheIsMissing() {
        val error = FscmApiException("missing", 422, Json.parseToJsonElement("""{"code":42244}"""))
        assertEquals(R.string.inbound_location_center_not_found, (error.inboundUiText() as UiText.Resource).id)
        assertEquals(R.string.inbound_location_not_found, text(InboundClientError.LocationNotFound).id)
    }

    @Test
    fun missingOfflineDataDoesNotClaimTheCartonBelongsToAnotherReceipt() {
        assertEquals(R.string.inbound_box_offline_not_found, text(InboundClientError.BoxOfflineNotFound).id)
        assertEquals(R.string.inbound_box_not_in_session, text(InboundClientError.BoxNotInSession).id)
        assertEquals(R.string.inbound_box_not_eligible, text(InboundClientError.BoxNotEligible).id)
    }

    @Test
    fun locationIsOptionalAndPassedAsAFormatArgument() {
        assertEquals(R.string.inbound_box_already_assigned, text(InboundClientError.BoxAlreadyAssigned).id)
        val assigned = text(InboundClientError.BoxAlreadyAssigned, "A-01")
        assertEquals(R.string.inbound_box_assigned_location, assigned.id)
        assertEquals(listOf("A-01"), assigned.args)
        assertEquals(R.string.inbound_box_already_scanned, text(InboundClientError.BoxAlreadyScanned).id)
        assertEquals(R.string.inbound_box_scanned_location, text(InboundClientError.BoxAlreadyScanned, "A-01").id)
        assertEquals(R.string.inbound_box_scanned_current, text(InboundClientError.BoxScannedInCurrentBatch).id)
        assertEquals(R.string.inbound_box_duplicate_record, text(InboundClientError.BoxDuplicateRecord).id)
    }

    @Test
    fun exceptionDetailsRemainLiteralFormatArguments() {
        val error = text(InboundClientError.BoxException, "Reason with 10% and %1\$s")
        assertEquals(R.string.inbound_box_exception, error.id)
        assertEquals(listOf("Reason with 10% and %1\$s"), error.args)
    }

    private fun text(reason: InboundClientError, detail: String = "") =
        InboundClientException(reason, detail).inboundUiText() as UiText.Resource
}
