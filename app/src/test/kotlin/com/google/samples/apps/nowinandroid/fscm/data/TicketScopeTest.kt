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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TicketScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun attributesDisplayReadableValuesWithoutJsonSyntax() {
        assertEquals("Color: Red · Size: M", ticketScopeAttributes(json.parseToJsonElement("""{"Color":"Red","Size":["M"]}""")))
        assertEquals("", ticketScopeAttributes(json.parseToJsonElement("null")))
    }

    @Test
    fun oldTicketsHaveEmptyOptionalScopeAndNoEditPermission() {
        val ticket = json.decodeFromString<WorkplaceTicket>("""{"id":7,"title":"Old"}""")
        assertTrue(ticket.scopeItems.isEmpty())
        assertFalse(ticket.permissions.editScope)
    }

    @Test
    fun sourceSkuRetainsLeadingZerosAndUnmatchedIdentity() {
        val row = json.decodeFromString<TicketScopeItem>("""{"id":9,"order_id":10,"order_line_id":11,"shop_sku":"00012","inventory_sku":"00003","sku_id":null}""")
        assertEquals("00012", row.shopSku)
        assertEquals("00003", row.inventorySku)
        assertEquals("11", row.input().getValue("order_line_id").jsonPrimitive.content)
        assertFalse("sku_id" in row.input())
        assertFalse("shop_sku" in row.input())
    }

    @Test
    fun identitiesPreserveShopSkuPairingAndStableSavedRows() {
        val row = TicketScopeItem(id = 8, shopId = 1, productId = 2, skuId = 3)
        assertNotEquals(row.identity(), row.copy(shopId = 4).identity())
        assertNotEquals(row.identity(), row.copy(skuId = 5).identity())
        assertEquals("8", row.input().getValue("id").jsonPrimitive.content)
        assertEquals("1", row.input().getValue("shop_id").jsonPrimitive.content)
    }
}
