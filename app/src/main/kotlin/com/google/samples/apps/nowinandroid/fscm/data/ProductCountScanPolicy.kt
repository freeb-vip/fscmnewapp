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

internal enum class ProductCountScanPolicy {
    Accept,
    RequireDuplicateConfirmation,
    ProductMismatch,
    TaskCompleted,
    NotEditor,
}

internal fun productCountScanPolicy(
    taskStatus: String,
    editorDeviceId: String,
    currentDeviceId: String,
    lockedProductCode: String,
    scannedProductCode: String,
    duplicateCount: Int,
    duplicateConfirmed: Boolean,
): ProductCountScanPolicy = when {
    taskStatus != ProductCountTaskStatus.InProgress.value -> ProductCountScanPolicy.TaskCompleted
    editorDeviceId != currentDeviceId -> ProductCountScanPolicy.NotEditor
    lockedProductCode.isNotBlank() && !lockedProductCode.equals(scannedProductCode, ignoreCase = true) ->
        ProductCountScanPolicy.ProductMismatch
    duplicateCount > 0 && !duplicateConfirmed -> ProductCountScanPolicy.RequireDuplicateConfirmation
    else -> ProductCountScanPolicy.Accept
}
