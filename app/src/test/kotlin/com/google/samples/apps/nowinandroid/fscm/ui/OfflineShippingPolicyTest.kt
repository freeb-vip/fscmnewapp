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

package com.google.samples.apps.nowinandroid.fscm.ui

import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppConfig
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.SessionExpiredException
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.ShippingTask
import com.google.samples.apps.nowinandroid.fscm.data.defaultMobileApps
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineShippingPolicyTest {
    @Test
    fun warehouseAppRequiresItsOwnPermissionAndOnlineSession() {
        val app = defaultMobileApps.single { it.key == "offline_shipping" }
        val overview = MobileWorkbenchOverview(appConfig = MobileAppConfig(apps = listOf(app)))
        assertEquals("", app.businessPermission)
        assertFalse(app.offlineSupported)
        assertTrue(visibleMobileApps(overview, listOf("offline-orders")).isEmpty())
        assertEquals(listOf(app.key), visibleMobileApps(overview, listOf(app.permission)).map { it.key })
        assertTrue(visibleMobileApps(overview, listOf(app.permission), SessionMode.Offline).isEmpty())
        assertEquals(R.string.shipping_title, localAppTitleResource(app.key))
    }

    @Test
    fun errorsUseLocalResourcesInsteadOfSalesServerText() {
        val payload = Json.parseToJsonElement("""{"code":409,"data":{"reason":"already_picked"},"msg":"内部销售数据"}""")
        assertEquals(R.string.shipping_already_picked, FscmApiException("private", 409, payload, "private").shippingMessage())
        assertEquals(R.string.shipping_request_failed, FscmApiException("price=999", 500).shippingMessage())
        assertEquals(R.string.shipping_access_error, FscmApiException("private", 403).shippingMessage())
        assertEquals(R.string.shipping_access_error, SessionExpiredException().shippingMessage())
    }

    @Test
    fun shippingWireUsesWarehouseQuantitiesAndIndependentStage() {
        val task = Json.decodeFromString<ShippingTask>("""{"order_id":42,"order_no":"OFF-42","stage":"shipped","version":4,"quantity":2,"items":[{"item_id":8,"sku_code":"ABC","quantity":2,"picked":true}],"photos":[{"id":"photo","stage":"shipping","confirmed":true}]}""")
        assertEquals(2L, task.quantity)
        assertEquals("shipped", task.stage)
        assertEquals(R.string.shipping_shipped, shippingStageLabel(task.stage))
        assertTrue(task.items.single().picked)
    }
}
