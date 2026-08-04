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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProductCountingRegistrationTest {
    @Test
    fun productCountingApp_isNativeOfflineCapableAndPermissionGated() {
        val app = assertNotNull(defaultMobileApps.firstOrNull { it.key == "product_counting" })

        assertEquals("mobile-app:product-counting", app.permission)
        assertEquals(true, app.offlineSupported)
        assertEquals(true, app.offlineSyncable)
        assertEquals(
            listOf("product_counting"),
            visibleMobileApps(
                overview = MobileWorkbenchOverview(appConfig = MobileAppConfig(apps = listOf(MobileAppEntry(key = "product_counting")))),
                permissions = listOf("mobile-app:product-counting"),
                sessionMode = SessionMode.Offline,
            ).filter { it.key == "product_counting" }.map { it.key },
        )
    }

    @Test
    fun offlineProductCounting_requiresDedicatedWriteScope() {
        val session = SessionState(
            sessionMode = SessionMode.Offline,
            offlineLease = "lease",
            offlineLeaseId = "lease-id",
            offlineLeaseExpiresAt = System.currentTimeMillis() + 60_000,
            offlineScopes = listOf("product_counting.write"),
        )

        session.requireOfflineOperationPermission("product_counting")
        assertFailsWith<IllegalStateException> {
            session.copy(offlineScopes = listOf("receiving.write")).requireOfflineOperationPermission("product_counting")
        }
    }
}
