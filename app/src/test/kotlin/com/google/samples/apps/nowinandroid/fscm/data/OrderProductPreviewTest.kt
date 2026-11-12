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

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderProductPreviewTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exceptionPreviewPreservesTheRealTitleImageAndLeadingZeroSku() {
        val report = json.decodeFromString<WarehouseException>("""{"id":1,"product_preview":{"title":"Carton","sku":"000SKU","image_url":"https://example.invalid/carton.jpg","item_count":3}}""")
        assertEquals("Carton", report.productPreview?.title)
        assertEquals("000SKU", report.productPreview?.sku)
        assertEquals("https://example.invalid/carton.jpg", report.productPreview?.imageUrl)
        assertEquals(3L, report.productPreview?.itemCount)
    }

    @Test
    fun shippingSummaryUsesTheSamePreviewContract() {
        val task = json.decodeFromString<ShippingTask>("""{"order_id":42,"order_no":"00042","product_preview":{"title":"Printer","sku":"001","image_url":"","item_count":2}}""")
        assertEquals("00042", task.orderNo)
        assertEquals("Printer", task.productPreview?.title)
        assertEquals(2L, task.productPreview?.itemCount)
    }

    @Test
    fun oldServerResponsesWithoutPreviewStillDecode() {
        assertNull(json.decodeFromString<ShippingTask>("""{"order_id":42}""").productPreview)
        assertNull(json.decodeFromString<WarehouseException>("""{"id":1,"product_preview":null}""").productPreview)
    }
}
