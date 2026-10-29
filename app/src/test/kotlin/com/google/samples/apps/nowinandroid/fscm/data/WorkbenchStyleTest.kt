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

class WorkbenchStyleTest {
    @Test
    fun ticketsAppearInOnlineCatalogAndStayHiddenOffline() {
        val ticket = defaultMobileApps.single { it.key == "tickets" }
        val overview = MobileWorkbenchOverview(apps = listOf(ticket))
        val permissions = listOf("mobile-app:tickets", "workbench-access")
        assertEquals(listOf("tickets"), visibleMobileApps(overview, permissions).map { it.key })
        assertEquals(emptyList(), visibleMobileApps(overview, permissions, SessionMode.Offline))
        assertEquals(emptyList(), visibleMobileApps(overview.copy(apps = listOf(ticket.copy(visible = false))), permissions))
    }

    @Test
    fun configuredAppsKeepRoleOrderAndIgnoreUnavailableApps() {
        val catalog = listOf("receiving", "box_labels", "product_counting").map { MobileAppEntry(key = it) }
        val ordered = orderedWarehouseHomeApps(catalog, listOf("product_counting", "unknown", "receiving"))
        assertEquals(listOf("product_counting", "receiving"), ordered.map { it.key })
    }

    @Test
    fun emptyConfigurationUsesFirstFourAvailableApps() {
        val catalog = (1..6).map { MobileAppEntry(key = "app_$it") }
        assertEquals(catalog.take(4), orderedWarehouseHomeApps(catalog, emptyList()))
    }

    @Test
    fun emptyConfigurationPrefersWarehouseJobs() {
        val catalog = listOf("todos", "product_counting", "central_receiving", "receiving", "inbound_management")
            .map { MobileAppEntry(key = it) }
        assertEquals(
            listOf("receiving", "inbound_management", "central_receiving", "product_counting"),
            orderedWarehouseHomeApps(catalog, emptyList()).map { it.key },
        )
    }
}
