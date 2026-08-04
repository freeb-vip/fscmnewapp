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

class WarehouseLocationCacheTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun locationPage_decodesLatestBackendContract() {
        val page = json.decodeFromString<WarehouseLocationPage>(
            """
            {
              "items": [{
                "id": 17,
                "warehouse_id": 3,
                "zone_id": 5,
                "code": "A/01_PICK",
                "name": "Picking A1",
                "type": "picking",
                "is_active": true,
                "updated_at": "2026-07-21T08:00:00Z",
                "warehouse": {"id": 3, "code": "TH-FB111", "name": "Bangkok"},
                "zone": {"id": 5, "code": "PICK", "name": "Picking"}
              }],
              "total": 1,
              "page": 1,
              "page_size": 200
            }
            """.trimIndent(),
        )

        assertEquals(200, page.pageSize)
        assertEquals("TH-FB111", page.items.single().warehouse.code)
        assertEquals("Picking", page.items.single().zone.name)
    }

    @Test
    fun selectLocation_requiresExactCodeWarehouseAndActiveState() {
        val options = listOf(
            option(id = 1, code = "A-01", warehouse = "WH-OTHER"),
            option(id = 2, code = "A-010", warehouse = "WH-1"),
            option(id = 3, code = "A-01", warehouse = "WH-1", active = false),
            option(id = 4, code = "A-01", warehouse = "WH-1"),
        )

        assertEquals(4, selectWarehouseLocation(options, "a-01", "wh-1")?.id)
        assertNull(selectWarehouseLocation(options, "A-0", "WH-1"))
        assertNull(selectWarehouseLocation(options, "A-01", "WH-2"))
    }

    private fun option(
        id: Long,
        code: String,
        warehouse: String,
        active: Boolean = true,
    ) = WarehouseLocationOption(
        id = id,
        warehouseId = 8,
        zoneId = 9,
        code = code,
        isActive = active,
        warehouse = WarehouseLocationWarehouse(id = 8, code = warehouse),
    )
}
