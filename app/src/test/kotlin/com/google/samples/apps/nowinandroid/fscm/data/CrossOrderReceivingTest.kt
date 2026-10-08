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
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CrossOrderReceivingTest {
    private val catalog = Json.parseToJsonElement("""{"source_session_id":11,"source_order_id":1,"source_order_code":"A","warehouse_id":7,"warehouse_code":"WH","warehouse_name":"仓库"}""").jsonObject
    private val box = Json.parseToJsonElement("""{"box_plan_id":42,"target_order_id":2,"target_order_code":"B","target_session_id":0,"quantity":6,"receiving_cycle":1,"inbound_cycle":1,"label_code":"BOX","sku_code":"SKU"}""").jsonObject

    @Test
    fun offlineConfirmationPreservesOriginalOrderAndSourceContextWithoutInventingSession() {
        val confirmation = offlineCrossOrderConfirmation(catalog, box, "inbound", 8, "A-01")
        assertEquals(2L, confirmation.crossID("target_order_id"))
        assertEquals(0L, confirmation.crossID("target_session_id"))
        val snapshot = Json.parseToJsonElement(confirmation.crossText("cross_order_snapshot")).jsonObject
        assertEquals(11L, snapshot.crossID("source_session_id"))
        assertEquals(1L, snapshot.crossID("source_order_id"))
        assertEquals(2L, snapshot.crossID("target_order_id"))
        assertEquals(42L, snapshot.crossID("physical_box_plan_id"))
        assertEquals(8L, snapshot.crossID("location_id"))
        assertEquals(7L, snapshot.crossID("warehouse_id"))
        assertEquals(6L, snapshot.crossID("actual_quantity"))
    }

    @Test
    fun offlineApprovalCannotBeReusedForDifferentLocationOrOperation() {
        val first = offlineCrossOrderConfirmation(catalog, box, "inbound", 8, "A-01")
        val otherLocation = offlineCrossOrderConfirmation(catalog, box, "inbound", 9, "B-01")
        val receiving = offlineCrossOrderConfirmation(catalog, box, "receiving", 0, "")
        assertNotEquals(first.crossText("cross_order_snapshot"), otherLocation.crossText("cross_order_snapshot"))
        assertNotEquals(first.crossText("cross_order_snapshot"), receiving.crossText("cross_order_snapshot"))
    }
}
