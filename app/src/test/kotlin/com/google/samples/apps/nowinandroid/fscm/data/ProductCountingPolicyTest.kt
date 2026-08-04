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

import org.junit.Test
import kotlin.test.assertEquals

class ProductCountingPolicyTest {
    @Test
    fun firstScanAndDifferentSkuOfSameProduct_areAccepted() {
        assertEquals(ProductCountScanPolicy.Accept, policy(lockedProduct = "", scannedProduct = "P-1"))
        assertEquals(ProductCountScanPolicy.Accept, policy(lockedProduct = "P-1", scannedProduct = "p-1"))
    }

    @Test
    fun differentProduct_isRejectedWithoutConfirmation() {
        assertEquals(
            ProductCountScanPolicy.ProductMismatch,
            policy(lockedProduct = "P-1", scannedProduct = "P-2", duplicateCount = 1),
        )
    }

    @Test
    fun duplicateRequiresConfirmationEveryTime() {
        assertEquals(
            ProductCountScanPolicy.RequireDuplicateConfirmation,
            policy(lockedProduct = "P-1", scannedProduct = "P-1", duplicateCount = 1),
        )
        assertEquals(
            ProductCountScanPolicy.Accept,
            policy(lockedProduct = "P-1", scannedProduct = "P-1", duplicateCount = 2, duplicateConfirmed = true),
        )
        assertEquals(
            ProductCountScanPolicy.RequireDuplicateConfirmation,
            policy(lockedProduct = "P-1", scannedProduct = "P-1", duplicateCount = 2),
        )
    }

    @Test
    fun completedOrForeignOwnedTask_isReadOnly() {
        assertEquals(
            ProductCountScanPolicy.TaskCompleted,
            policy(taskStatus = ProductCountTaskStatus.Completed.value),
        )
        assertEquals(
            ProductCountScanPolicy.NotEditor,
            policy(editorDevice = "device-b"),
        )
    }

    private fun policy(
        taskStatus: String = ProductCountTaskStatus.InProgress.value,
        editorDevice: String = "device-a",
        lockedProduct: String = "P-1",
        scannedProduct: String = "P-1",
        duplicateCount: Int = 0,
        duplicateConfirmed: Boolean = false,
    ) = productCountScanPolicy(
        taskStatus = taskStatus,
        editorDeviceId = editorDevice,
        currentDeviceId = "device-a",
        lockedProductCode = lockedProduct,
        scannedProductCode = scannedProduct,
        duplicateCount = duplicateCount,
        duplicateConfirmed = duplicateConfirmed,
    )
}
