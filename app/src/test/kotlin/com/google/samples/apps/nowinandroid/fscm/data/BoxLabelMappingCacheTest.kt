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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLabelMappingCacheTest {
    @Test
    fun cachedJifengMapping_resolvesCanonicalSupplierLabelAndKeepsRawCode() {
        val result = mapping().toResolvedPayload("\uFEFFjf-box-001\r\n")

        assertEquals("ABCDEF123456", result?.labelCode)
        assertEquals("jf-box-001", result?.originalRawCode)
        assertEquals(BoxLabelQrFormat.JifengMapping, result?.format)
    }

    @Test
    fun cachedMapping_buildsOfflineBoxLabelWithMappingRelationship() {
        val label = mapping().toCachedBoxLabel("JF-BOX-001")

        assertTrue(label.recognized)
        assertTrue(label.localFallback)
        assertEquals("JF-BOX-001", label.jifengBoxNo)
        assertEquals("ABCDEF123456", label.labelCode)
        assertFalse(label.printable)
    }

    @Test
    fun preferredMatcher_acceptsFscmAndCachedJifengCodesOnly() {
        val aliases = setOf("JF-BOX-001")

        assertTrue(isKnownMappedBoxLabel("ABCDEF123456", aliases))
        assertTrue(isKnownMappedBoxLabel("ABCDEF123456,SKU-001=12", aliases))
        assertTrue(isKnownMappedBoxLabel("jf-box-001\n", aliases))
        assertFalse(isKnownMappedBoxLabel("SKU-001", aliases))
        assertFalse(isKnownMappedBoxLabel("https://example.com/qr", aliases))
    }

    private fun mapping() = LocalBoxLabelMappingEntity(
        userId = 7,
        namespaceId = 9,
        mappingId = 11,
        supplierOrderBoxPlanId = 13,
        supplierLabelCode = "ABCDEF123456",
        supplierBoxNo = "SUPPLIER-BOX-1",
        supplierBoxUid = "UID-1",
        jifengBoxNo = "JF-BOX-001",
        jifengQrCodeText = "JF-BOX-001",
        mappingStatus = "mapped",
        productCode = "P-1",
        productName = "Product",
        skuCode = "SKU-1",
        manufacturerName = "Factory",
        caseSpecName = "12/carton",
        qtyPerCarton = 12,
        boxQty = 12,
        remoteUpdatedAt = "2026-07-17T00:00:00Z",
        syncedAt = 1,
    )
}
