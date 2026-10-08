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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class EdgeConnectionPolicyTest {
    private fun address(value: String) = InetAddress.getByName(value).address

    @Test fun subnets_useRealPrefixAndAddressFamily() {
        assertTrue(sameEdgeSubnet(address("192.168.1.5"), address("192.168.0.9"), 23))
        assertFalse(sameEdgeSubnet(address("192.168.1.5"), address("192.168.1.200"), 25))
        assertFalse(sameEdgeSubnet(address("192.168.1.5"), address("10.0.0.5"), 24))
        assertTrue(sameEdgeSubnet(address("2001:db8::1"), address("2001:db8::2"), 64))
        assertFalse(sameEdgeSubnet(address("192.168.1.5"), address("2001:db8::2"), 24))
        assertFalse(sameEdgeSubnet(address("192.168.1.5"), address("192.168.1.6"), 0))
    }

    @Test fun unknownAndNonLanAddresses_areNeverResolvedAutomatically() {
        assertNull(literalEdgeAddress("http://edge.local:3001"))
        assertNull(literalEdgeAddress("http://127.0.0.1:3001"))
        assertNull(literalEdgeAddress("ftp://192.168.1.3"))
        assertNull(literalEdgeAddress("invalid"))
        assertNotNull(literalEdgeAddress("http://192.168.1.3:3001"))
    }

    @Test fun transport_requiresDirectWifiOrEthernet() {
        assertTrue(allowsEdgeLan(wifi = true, ethernet = false, vpn = false))
        assertTrue(allowsEdgeLan(wifi = false, ethernet = true, vpn = false))
        assertFalse(allowsEdgeLan(wifi = false, ethernet = false, vpn = false))
        assertFalse(allowsEdgeLan(wifi = true, ethernet = false, vpn = true))
    }

    @Test fun registry_coalescesConcurrentRequestsAndCachesEmptyResults() = runTest {
        val cache = EdgeRegistryCache()
        var calls = 0
        List(10) {
            async {
                cache.get("scope", 0) {
                    calls++
                    delay(10)
                    emptyList()
                }
            }
        }.awaitAll()
        assertEquals(1, calls)
        cache.get("scope", 299_999) {
            calls++
            emptyList()
        }
        assertEquals(1, calls)
        cache.get("scope", 300_000) {
            calls++
            emptyList()
        }
        assertEquals(2, calls)
    }

    @Test fun registry_failureDoesNotProduceBackgroundRetryStorm() = runTest {
        val cache = EdgeRegistryCache()
        var calls = 0
        runCatching {
            cache.get("scope", 0) {
                calls++
                error("center unavailable")
            }
        }
        assertTrue(
            cache.get("scope", 100) {
                calls++
                listOf(EdgeNode(id = 1))
            }.isEmpty(),
        )
        assertEquals(1, calls)
        cache.get("scope", 200, force = true) {
            calls++
            emptyList()
        }
        assertEquals(2, calls)
    }

    @Test fun registry_scopeChangesAndCancellationNeverReuseOldNodes() = runTest {
        val cache = EdgeRegistryCache()
        cache.get("account-a/wifi-a", 0) { listOf(EdgeNode(id = 1)) }
        assertTrue(cache.get("account-b/wifi-b", 0) { emptyList() }.isEmpty())
        runCatching { cache.get("cancelled", 0) { throw CancellationException() } }
        assertEquals(2L, cache.get("cancelled", 0) { listOf(EdgeNode(id = 2)) }.single().id)
        cache.invalidate()
        assertTrue(cache.get("cancelled", 1) { emptyList() }.isEmpty())
    }

    @Test fun manualNodes_areProcessAndNetworkScoped() {
        val memory = EdgeManualNodes()
        memory.remember("account/workspace/wifi", listOf(EdgeNode(id = 1)))
        assertEquals(1, memory.get("account/workspace/wifi").size)
        assertTrue(memory.get("account/workspace/cellular").isEmpty())
        assertTrue(memory.get("another/workspace/wifi").isEmpty())
        memory.clear()
        assertTrue(memory.get("account/workspace/wifi").isEmpty())
        assertTrue(EdgeManualNodes().get("account/workspace/wifi").isEmpty())
    }

    @Test fun settingsAndLegacyAliases_areNotBusinessApps() {
        val keys = listOf("edge_nodes", "edge_node", "printer_settings", "print_settings", "edge_print_settings", "label_print_settings", "pda_settings", "scanner_settings", "pda_scanner_settings", "label_print")
        val overview = MobileWorkbenchOverview(apps = keys.map { MobileAppEntry(key = it) })
        assertEquals(listOf("label_print"), visibleMobileApps(overview, emptyList()).map { it.key })
    }
}
