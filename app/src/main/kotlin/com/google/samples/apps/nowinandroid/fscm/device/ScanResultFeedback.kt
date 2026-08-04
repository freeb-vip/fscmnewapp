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

package com.google.samples.apps.nowinandroid.fscm.device

import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptScanResult
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem

fun CentralReceiptScanResult.isSuccessfulScanResult(): Boolean = confirmed && !duplicate

fun ReceivingScanItem.isSuccessfulLookup(): Boolean =
    (labelCode.isNotBlank() || boxNo.isNotBlank() || rawCode.isNotBlank()) &&
        status.lowercase() !in FAILED_RECEIVING_STATUSES

fun BoxLabel.isNormalBoxLabel(): Boolean =
    recognized &&
        validForCurrentContext &&
        warningCode.isBlank() &&
        warningMessage.isBlank() &&
        warnings.isEmpty() &&
        status.lowercase() !in FAILED_BOX_LABEL_STATUSES

fun isSuccessfulProductScanResult(resultCount: Int): Boolean = resultCount > 0

private val FAILED_RECEIVING_STATUSES = setOf(
    "exception",
    "failed",
    "invalid",
    "not_found",
    "rejected",
)

private val FAILED_BOX_LABEL_STATUSES = FAILED_RECEIVING_STATUSES + setOf(
    "abnormal",
    "damaged",
    "pending_exception",
)
