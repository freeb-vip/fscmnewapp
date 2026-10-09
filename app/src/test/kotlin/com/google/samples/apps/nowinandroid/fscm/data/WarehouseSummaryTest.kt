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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WarehouseSummaryTest {
    @Test
    fun comparisonHandlesRiseFallFlatZeroAndMissingValues() {
        assertEquals(20.0, warehouseChangePercent(1200.0, 1000.0))
        assertEquals(-20.0, warehouseChangePercent(80.0, 100.0))
        assertEquals(0.0, warehouseChangePercent(100.0, 100.0))
        assertEquals(-100.0, warehouseChangePercent(0.0, 100.0))
        assertNull(warehouseChangePercent(12.0, 0.0))
        assertNull(warehouseChangePercent(0.0, 0.0))
        assertNull(warehouseChangePercent(null, 100.0))
        assertNull(warehouseChangePercent(100.0, null))
        assertNull(warehouseChangePercent(Double.NaN, 100.0))
        assertNull(warehouseChangePercent(100.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun businessMidnightNeverRelabelsOldCacheAsToday() {
        val metric = WarehouseMetric(count = 1200, available = true)
        val cached = WarehouseWorkbench(scope = "workspace", date = "2026-10-08", timezone = "Asia/Bangkok", metric = metric, comparisonAvailable = true, cached = true)
        assertSame(cached, cached.forCurrentBusinessDate(Instant.parse("2026-10-08T16:59:59Z")))
        val nextDay = cached.forCurrentBusinessDate(Instant.parse("2026-10-08T17:00:00Z"))
        assertEquals("2026-10-09", nextDay.date)
        assertEquals("2026-10-08", nextDay.yesterdayDate)
        assertNull(nextDay.metric)
        assertSame(metric, nextDay.yesterdayMetric)
        assertFalse(nextDay.comparisonAvailable)
        assertTrue(nextDay.cached)
        val old = cached.forCurrentBusinessDate(Instant.parse("2026-10-10T17:00:00Z"))
        assertNull(old.metric)
        assertNull(old.yesterdayMetric)
    }

    @Test
    fun summaryPermissionIsSeparateFromAppAccess() {
        assertFalse(canReadWarehouseSummary(SessionState(permissions = listOf("workbench", "mobile-app:receiving"))))
        assertTrue(canReadWarehouseSummary(SessionState(permissions = listOf("order_statistics"))))
    }

    @Test
    fun summaryDecodesNullableMetricsAndDoesNotPersistCacheFlag() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = """{"scope":"workspace","date":"2026-10-09","timezone":"Asia/Bangkok","metric":null,"yesterday_metric":{"count":1000,"efficiency":100,"available":true,"efficiency_available":true},"comparison_available":false}"""
        val decoded = json.decodeFromString<WarehouseWorkbench>(payload)
        assertNull(decoded.metric)
        assertEquals(1000, decoded.yesterdayMetric?.count)
        assertFalse(decoded.comparisonAvailable)
        val stored = json.encodeToString(WarehouseWorkbench.serializer(), decoded.copy(cached = true))
        assertFalse("cached" in stored)
    }
}
