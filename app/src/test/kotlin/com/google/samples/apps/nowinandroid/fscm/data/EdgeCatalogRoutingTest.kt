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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeCatalogRoutingTest {
    @Test
    fun noNodes_keepsCenterAsOnlyRoute() {
        assertTrue(rankEdgeCatalogNodes(emptyList()).isEmpty())
    }

    @Test
    fun noDiscoveredNodes_usesRegisteredNodeFallbackWhenOnline() {
        assertTrue(shouldUseEdgeRegistryFallback(emptyList(), isOffline = false))
        assertFalse(shouldUseEdgeRegistryFallback(emptyList(), isOffline = true))
        assertFalse(shouldUseEdgeRegistryFallback(listOf(node("edge-1", 1, 0, 1)), isOffline = false))
    }

    @Test
    fun everyEdgeServedCatalogResponseIsLocal() {
        assertTrue(isEdgeServedCatalogResponse("edge-catalog"))
        assertTrue(isEdgeServedCatalogResponse("edge-cache"))
        assertTrue(isEdgeServedCatalogResponse("center-via-edge"))
        assertFalse(isEdgeServedCatalogResponse(null))
        assertFalse(isEdgeServedCatalogResponse("center"))
    }

    @Test
    fun onlyUnauthorizedResponseRenewsEdgeTicket() {
        assertTrue(shouldRetryEdgeAuthentication(401))
        assertFalse(shouldRetryEdgeAuthentication(403))
        assertFalse(shouldRetryEdgeAuthentication(500))
    }

    @Test
    fun edgeMediaRequestUsesNodeUrlTicketAndVersionedCacheKey() {
        val response = EdgeCatalogResponse(
            payload = Json.parseToJsonElement("{}"),
            node = node("edge-1", sequence = 1, lagMillis = 0, latencyMillis = 1).copy(
                baseUrl = "http://192.168.1.8:8089",
            ),
            authorization = mapOf("X-Edge-Ticket" to "ticket-1"),
            servedByEdge = true,
        )

        val request = response.mediaRequest(
            media = MediaRef(
                id = "sku:9",
                version = "abc123",
                thumbnailPath = "/edge/v2/catalog/media/sku/9/thumbnail?v=abc123",
                centralUrl = "https://center.example/sku-9.jpg",
            ),
            legacyUrl = "",
        )

        assertEquals("http://192.168.1.8:8089/edge/v2/catalog/media/sku/9/thumbnail?v=abc123", request?.url)
        assertEquals("https://center.example/sku-9.jpg", request?.centralUrl)
        assertEquals("catalog:sku:9:abc123:thumbnail", request?.cacheKey)
        assertEquals("X-Edge-Ticket", request?.headerName)
        assertEquals("ticket-1", request?.headerValue)
    }

    @Test
    fun oneFreshNode_isPreferredBeforeCenterFallback() {
        val node = node("edge-1", sequence = 10, lagMillis = 2_000, latencyMillis = 80)

        val ranked = rankEdgeCatalogNodes(listOf(node))

        assertEquals(listOf("edge-1"), ranked.map(EdgeCatalogNode::nodeId))
        assertFalse(ranked.single().isStale)
    }

    @Test
    fun multipleNodes_preferFreshestSequenceThenLowestLatency() {
        val ranked = rankEdgeCatalogNodes(
            listOf(
                node("slow", sequence = 12, lagMillis = 1_000, latencyMillis = 160),
                node("older", sequence = 11, lagMillis = 1_000, latencyMillis = 20),
                node("fast", sequence = 12, lagMillis = 1_000, latencyMillis = 35),
            ),
        )

        assertEquals(listOf("fast", "slow", "older"), ranked.map(EdgeCatalogNode::nodeId))
    }

    @Test
    fun staleNode_neverOutranksFreshNodeWithLowerSequence() {
        val ranked = rankEdgeCatalogNodes(
            listOf(
                node("stale", sequence = 100, lagMillis = 10_001, latencyMillis = 10),
                node("fresh", sequence = 90, lagMillis = 9_999, latencyMillis = 100),
            ),
        )

        assertEquals(listOf("fresh", "stale"), ranked.map(EdgeCatalogNode::nodeId))
        assertTrue(ranked.last().isStale)
    }

    @Test
    fun failuresBreakTiesBeforeLatency() {
        val ranked = rankEdgeCatalogNodes(
            listOf(
                node("unreliable", sequence = 5, lagMillis = 500, latencyMillis = 10, failureCount = 1),
                node("reliable", sequence = 5, lagMillis = 500, latencyMillis = 30),
            ),
        )

        assertEquals(listOf("reliable", "unreliable"), ranked.map(EdgeCatalogNode::nodeId))
    }

    @Test
    fun legacySkuQueryNode_supportsProductsAndSkus() {
        val node = node("legacy", sequence = 0, lagMillis = 0, latencyMillis = 20).copy(
            capabilities = listOf("print", "sku_query"),
            protocol = EdgeCatalogProtocol.Legacy,
        )

        assertEquals(EdgeCatalogProtocol.Legacy, edgeCatalogProtocol(node.capabilities))
        assertTrue(node.supportsCatalogPath("products"))
        assertTrue(node.supportsCatalogPath("skus/SKU-001"))
        assertFalse(node.supportsCatalogPath("box-labels/resolve"))
        assertFalse(node.isStale)
    }

    @Test
    fun deployedCatalogCacheNode_supportsProductsSkusAndBoxLabels() {
        val node = node("site-edge", sequence = 0, lagMillis = 0, latencyMillis = 20).copy(
            capabilities = listOf(
                "proxy",
                "adaptive_cache",
                "catalog_cache",
                "box_label_catalog",
                "local_print",
            ),
            protocol = EdgeCatalogProtocol.Legacy,
        )

        assertEquals(EdgeCatalogProtocol.Legacy, edgeCatalogProtocol(node.capabilities))
        assertTrue(node.supportsCatalogPath("products"))
        assertTrue(node.supportsCatalogPath("skus"))
        assertTrue(node.supportsCatalogPath("box-labels/resolve"))
        assertFalse(node.supportsCatalogPath("unknown"))
    }

    @Test
    fun legacyArrayResponse_decodesAsCatalogPage() {
        val payload = Json.parseToJsonElement(
            """[{"id":1,"code":"P-001","name":"Product"}]""",
        )

        val page = decodeEdgeCatalogPage<Product>(payload, Json)

        assertEquals(listOf("P-001"), page.rows().map(Product::code))
        assertEquals(1, page.total)
    }

    private fun node(
        id: String,
        sequence: Long,
        lagMillis: Long,
        latencyMillis: Long,
        failureCount: Int = 0,
    ) = EdgeCatalogNode(
        nodeId = id,
        nodeName = id,
        baseUrl = "https://$id.fscm.lan",
        capabilities = listOf("catalog_v2"),
        protocol = EdgeCatalogProtocol.V2,
        sequence = sequence,
        syncedAt = "2026-07-18T00:00:00Z",
        syncLagMillis = lagMillis,
        latencyMillis = latencyMillis,
        failureCount = failureCount,
    )
}
