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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileAppUsageTest {
    private val apps = listOf(
        MobileAppEntry(key = "todos", title = "待办"),
        MobileAppEntry(key = "receiving", title = "仓库收货"),
        MobileAppEntry(key = "product_management", title = "产品管理"),
        MobileAppEntry(key = "box_labels", title = "箱唛管理"),
        MobileAppEntry(key = "label_print", title = "标签打印"),
        MobileAppEntry(key = "documents", title = "文档"),
        MobileAppEntry(key = "tickets", title = "工单"),
        MobileAppEntry(key = "profit", title = "利润"),
    )

    @Test
    fun commonApps_prioritizePinnedThenFrequentlyUsedApps() {
        val usage = MobileAppUsage(
            counts = mapOf("receiving" to 8, "documents" to 5, "tickets" to 4),
            pinnedKeys = setOf("tickets"),
        )

        val common = commonMobileApps(apps, usage)

        assertEquals(listOf("tickets", "receiving", "documents", "todos"), common.map { it.key })
    }

    @Test
    fun commonApps_keepConfiguredOrderBeforeUsageHistoryExists() {
        val common = commonMobileApps(apps, MobileAppUsage())

        assertEquals(listOf("todos", "receiving", "product_management", "box_labels"), common.map { it.key })
    }

    @Test
    fun commonApps_areLimitedWithoutDiscardingPinnedApps() {
        val usage = MobileAppUsage(
            counts = mapOf("receiving" to 20, "documents" to 15, "tickets" to 7),
            pinnedKeys = setOf("profit"),
        )

        val common = commonMobileApps(apps, usage, maxItems = 3)

        assertEquals(listOf("profit", "receiving", "documents"), common.map { it.key })
    }

    @Test
    fun defaultCommonApps_initializeOnlyOncePerUsageBucket() {
        val initialized = MobileAppUsage(counts = mapOf("tickets" to 3)).withDefaultCommonApps()

        assertEquals(DEFAULT_COMMON_APP_KEYS.toSet(), initialized.pinnedKeys)
        assertEquals(DEFAULT_COMMON_APP_KEYS, initialized.pinnedOrder)
        assertEquals(true, initialized.defaultsInitialized)
        assertEquals(initialized, initialized.withDefaultCommonApps())
    }

    @Test
    fun commonApps_showDefaultApplicationsFirst() {
        val usage = MobileAppUsage(
            pinnedKeys = DEFAULT_COMMON_APP_KEYS.toSet(),
            pinnedOrder = DEFAULT_COMMON_APP_KEYS,
            defaultsInitialized = true,
        )

        val common = commonMobileApps(apps, usage)

        assertEquals(DEFAULT_COMMON_APP_KEYS, common.map { it.key })
    }

    @Test
    fun commonApps_unpinningDefaultApplicationAllowsConfiguredFallback() {
        val usage = MobileAppUsage(
            pinnedKeys = DEFAULT_COMMON_APP_KEYS.toSet() - "label_print",
            pinnedOrder = DEFAULT_COMMON_APP_KEYS.filterNot { it == "label_print" },
            defaultsInitialized = true,
        )

        val common = commonMobileApps(apps, usage)

        assertEquals(listOf("product_management", "box_labels", "receiving", "todos"), common.map { it.key })
    }

    @Test
    fun commonApps_fillSlotsWhenDefaultApplicationIsUnavailable() {
        val visibleApps = apps.filterNot { it.key == "product_management" }
        val usage = MobileAppUsage(
            pinnedKeys = DEFAULT_COMMON_APP_KEYS.toSet(),
            pinnedOrder = DEFAULT_COMMON_APP_KEYS,
            defaultsInitialized = true,
        )

        val common = commonMobileApps(visibleApps, usage)

        assertEquals(listOf("label_print", "box_labels", "receiving", "todos"), common.map { it.key })
    }

    @Test
    fun commonApps_newlyPinnedApplicationTakesPriority() {
        val usage = MobileAppUsage(
            pinnedKeys = DEFAULT_COMMON_APP_KEYS.toSet() + "tickets",
            pinnedOrder = listOf("tickets") + DEFAULT_COMMON_APP_KEYS,
            defaultsInitialized = true,
        )

        val common = commonMobileApps(apps, usage)

        assertEquals(listOf("tickets", "label_print", "product_management", "box_labels"), common.map { it.key })
    }

    @Test
    fun hiddenApps_areRemovedFromVisibleAndCommonLists() {
        val usage = MobileAppUsage(
            counts = mapOf("receiving" to 20),
            hiddenKeys = setOf("receiving"),
        )

        assertFalse(shownMobileApps(apps, usage).any { it.key == "receiving" })
        assertEquals(listOf("receiving"), hiddenMobileApps(apps, usage).map { it.key })
        assertFalse(commonMobileApps(apps, usage).any { it.key == "receiving" })
    }

    @Test
    fun hidingApp_clearsPinButPreservesUsageAndRestoresWithoutRepinning() {
        val initial = MobileAppUsage(
            counts = mapOf("tickets" to 7),
            pinnedKeys = setOf("tickets", "receiving"),
            pinnedOrder = listOf("tickets", "receiving"),
        )
        assertEquals(initial, initial.withAppHidden("tickets", hidden = false))

        val hidden = initial.withAppHidden("tickets", hidden = true)
        assertTrue(hidden.isHidden("tickets"))
        assertFalse(hidden.isPinned("tickets"))
        assertEquals(listOf("receiving"), hidden.pinnedOrder)
        assertEquals(7, hidden.usageFor("tickets"))

        val restored = hidden.withAppHidden("tickets", hidden = false)
        assertFalse(restored.isHidden("tickets"))
        assertFalse(restored.isPinned("tickets"))
        assertEquals(7, restored.usageFor("tickets"))
    }

    @Test
    fun usageScope_isIsolatedByUserAndNamespace() {
        val first = SessionState(
            user = UserProfile(id = 11),
            currentNamespace = NamespaceProfile(id = 101),
        )
        val otherUser = first.copy(user = UserProfile(id = 12))
        val otherNamespace = first.copy(currentNamespace = NamespaceProfile(id = 102))

        assertEquals("11:101", mobileAppUsageScopeKey(first))
        assertFalse(mobileAppUsageScopeKey(first) == mobileAppUsageScopeKey(otherUser))
        assertFalse(mobileAppUsageScopeKey(first) == mobileAppUsageScopeKey(otherNamespace))
    }
}
