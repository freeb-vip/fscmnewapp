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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FscmDataTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun unwrapStandardPayload_returnsDataWhenCodeIsZero() {
        val payload = buildJsonObject {
            put("code", 0)
            put("data", buildJsonObject { put("name", "FSCM") })
            put("msg", "ok")
        }

        val data = payload.unwrapStandardPayload(statusCode = 200) as JsonObject

        assertEquals("FSCM", data["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun edgeNodeQueries_includeOfflineOnlyForRegisteredNodeInventory() {
        assertEquals(
            mapOf("include_offline" to "true", "page_size" to "100"),
            edgeNodeListParams(includeOffline = true),
        )
        assertEquals(
            mapOf("page_size" to "100"),
            edgeNodeListParams(includeOffline = false),
        )
    }

    @Test
    fun availablePrintTemplate_usesServerTemplateWithoutLegacyPrintCapability() {
        val labelTemplate = EdgePrintTemplate(
            type = "label",
            code = "sku_60x40",
            printerName = "ZDesigner",
            available = true,
        )
        val node = EdgeNode(
            nodeId = "edge-01",
            status = "online",
            capabilities = listOf("local_cache"),
            printInventory = EdgePrintInventory(
                templates = listOf(
                    EdgePrintTemplate(type = "manufacturer_box_mark", code = "carton", printerName = "ZDesigner", available = true),
                    labelTemplate,
                ),
            ),
        )

        assertEquals(labelTemplate, node.availablePrintTemplate())
    }

    @Test
    fun unwrapStandardPayload_throwsApiExceptionWhenCodeIsNotZero() {
        val payload = buildJsonObject {
            put("code", 403)
            put("msg", "没有权限")
        }

        val error = assertFailsWith<FscmApiException> {
            payload.unwrapStandardPayload(statusCode = 200)
        }

        assertEquals("没有权限", error.message)
        assertEquals(200, error.statusCode)
    }

    @Test
    fun visibleMobileApps_filtersByVisibilityAndPermissionsThenSorts() {
        val overview = MobileWorkbenchOverview(
            appConfig = MobileAppConfig(
                apps = listOf(
                    MobileAppEntry(key = "hidden", title = "隐藏", visible = false, sort = 1),
                    MobileAppEntry(key = "denied", title = "无权限", permission = "mobile-app:denied", sort = 2),
                    MobileAppEntry(key = "receiving", title = "收货", permission = "mobile-app:receiving", businessPermission = "receiving_records", sort = 4),
                    MobileAppEntry(key = "profit", title = "利润", sort = 3),
                    MobileAppEntry(key = "product_management", title = "产品", permission = "mobile-app:product", sort = 5),
                ),
            ),
        )

        val apps = visibleMobileApps(
            overview = overview,
            permissions = listOf("mobile-app:receiving", "receiving_records", "mobile-app:product"),
        )

        assertEquals(listOf("profit", "receiving", "product_management", "label_print", "edge_nodes", "pda_settings"), apps.map { it.key })
    }

    @Test
    fun localSearchHits_prefersExactApplicationMatch() {
        val entries = listOf(
            MobileAppEntry(key = "product_management", title = "产品管理", description = "SKU 信息维护"),
            MobileAppEntry(key = "pda_settings", title = "PDA 设置", description = "扫码诊断"),
        )

        val hits = localSearchHits("PDA 设置", entries)

        assertEquals(1, hits.size)
        assertEquals(BusinessTypeCode.LocalApp.wireValue, hits.single().businessType)
        assertEquals("pda_settings", hits.single().targetCode)
        assertEquals("exact", hits.single().matchKind)
    }

    @Test
    fun localSearchHits_supportsAliasesAndKeepsMultipleMatches() {
        val entries = listOf(
            MobileAppEntry(key = "box_labels", title = "箱唛管理"),
            MobileAppEntry(key = "carton_management", title = "装箱管理"),
        )

        val hits = localSearchHits("箱唛", entries)

        assertEquals(listOf("box_labels", "carton_management"), hits.map { it.targetCode })
        assertTrue(hits.all { it.matchKind == "exact" })
    }

    @Test
    fun businessTypeCode_keepsUnknownServerTypesSafe() {
        assertEquals(BusinessTypeCode.SkuDetail, "sku_detail".toBusinessTypeCode())
        assertEquals(BusinessTypeCode.BoxLabelDetail, "box_label_detail".toBusinessTypeCode())
        assertEquals(BusinessTypeCode.Unknown, "future_business".toBusinessTypeCode())
    }

    @Test
    fun edgeNode_decodesAvailablePrinterAndTemplateInventory() {
        val node = json.decodeFromString<EdgeNode>(
            """
            {
              "id": 7,
              "node_id": "edge-01",
              "node_name": "一楼打印节点",
              "lan_base_url": "http://192.168.1.20:8089",
              "capabilities": ["print", "sku_query"],
              "print_inventory": {
                "printers": [{"name": "ZDesigner", "is_default": true, "status": "ready"}],
                "templates": [{
                  "code": "label_60x40mm",
                  "name": "SKU 标签",
                  "printer_name": "ZDesigner",
                  "width_mm": 60,
                  "height_mm": 40,
                  "orientation": "landscape",
                  "version": "1",
                  "available": true
                }]
              },
              "status": "online"
            }
            """.trimIndent(),
        )

        assertEquals("edge-01", node.nodeId)
        assertEquals("ZDesigner", node.printInventory.printers.single().name)
        assertTrue(node.printInventory.templates.single().available)
        assertEquals("label_60x40mm", node.printInventory.templates.single().code)
    }

    @Test
    fun edgeNode_withoutLanAddress_remainsAvailableForRemoteQueue() {
        val template = EdgePrintTemplate(code = "sku", printerName = "ZDesigner", available = true)
        val node = EdgeNode(
            nodeId = "edge-remote-01",
            status = "online",
            capabilities = listOf("print"),
            lanBaseUrl = "",
            printInventory = EdgePrintInventory(templates = listOf(template)),
        )

        assertEquals(template, node.availablePrintTemplate())
    }

    @Test
    fun buildLabelCode_addsConfiguredPrefix() {
        assertEquals("TSKU-001", buildLabelCode("T", "SKU-001"))
        assertEquals("SKU-001", buildLabelCode("", "SKU-001"))
    }

    @Test
    fun visibleMobileApps_offlineShowsOnlyDeclaredAndImplementedApps() {
        val overview = MobileWorkbenchOverview(
            appConfig = MobileAppConfig(
                apps = listOf(
                    MobileAppEntry(key = "profit", title = "利润", sort = 1, offlineSupported = true),
                    MobileAppEntry(
                        key = "receiving",
                        title = "收货",
                        permission = "mobile-app:receiving",
                        businessPermission = "receiving_records",
                        sort = 2,
                        offlineSupported = true,
                        offlineRequiredPermissions = listOf("mobile-app:receiving"),
                    ),
                    MobileAppEntry(key = "todos", title = "待办", sort = 3, offlineSupported = false),
                ),
            ),
        )

        val apps = visibleMobileApps(
            overview = overview,
            permissions = listOf("mobile-app:receiving", "receiving_records"),
            sessionMode = SessionMode.Offline,
        )

        assertEquals(listOf("receiving"), apps.map { it.key })
    }

    @Test
    fun visibleMobileApps_defaultsIncludePdaSettingsEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        assertTrue(apps.any { it.key == "pda_settings" && it.title == "PDA 设置" })
    }

    @Test
    fun defaultApps_includeSearchableEdgeNodesEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        val edgeNodes = apps.single { it.key == "edge_nodes" }
        assertEquals("边缘节点", edgeNodes.title)
        assertEquals("edge_nodes", localSearchHits("局域网探测", apps).single().targetCode)
    }

    @Test
    fun visibleMobileApps_normalizesLegacyPrintSettingsEntry() {
        val apps = visibleMobileApps(
            overview = MobileWorkbenchOverview(
                apps = listOf(MobileAppEntry(key = "print_settings", title = "打印配置")),
            ),
            permissions = emptyList(),
        )

        assertEquals(1, apps.count { it.key == "edge_nodes" })
        assertEquals("边缘节点", apps.single { it.key == "edge_nodes" }.title)
        assertTrue(apps.none { it.key == "print_settings" })
    }

    @Test
    fun defaultApps_includeSearchableLabelPrintEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        val labelPrint = apps.single { it.key == "label_print" }
        assertEquals("标签打印", labelPrint.title)
        assertEquals("label_print", localSearchHits("标签打印", apps).single().targetCode)
    }

    @Test
    fun edgePrintOption_keyIncludesNodeAndTemplate() {
        val option = EdgePrintNodeOption(
            node = EdgeNode(id = 9),
            template = EdgePrintTemplate(code = "barcode_60x40"),
            localReachable = true,
        )

        assertEquals("9:barcode_60x40", option.selectionKey)
    }

    @Test
    fun directPrintRouteUnavailable_fallsBackOnlyForMissingOrUnsupportedRoute() {
        assertTrue(isDirectPrintRouteUnavailable(404))
        assertTrue(isDirectPrintRouteUnavailable(405))
        assertEquals(false, isDirectPrintRouteUnavailable(400))
        assertEquals(false, isDirectPrintRouteUnavailable(401))
        assertEquals(false, isDirectPrintRouteUnavailable(503))
    }

    @Test
    fun localEdgeBaseUrlCandidates_supportsRegisteredApiSuffix() {
        assertEquals(
            listOf("http://192.168.1.20:8088", "http://192.168.1.20:8088/api"),
            localEdgeBaseUrlCandidates("http://192.168.1.20:8088/api/"),
        )
        assertEquals(
            listOf("http://192.168.1.20:8088"),
            localEdgeBaseUrlCandidates("http://192.168.1.20:8088"),
        )
        assertEquals("http://192.168.1.20:8088", localEdgeDirectBaseUrl("http://192.168.1.20:8088/api"))
    }

    @Test
    fun ipv4SubnetPrefix_parsesValidAddress() {
        assertEquals("192.168.31", ipv4SubnetPrefix("192.168.31.25"))
        assertEquals("10.10.0", ipv4SubnetPrefix("10.10.0.8"))
        assertEquals(null, ipv4SubnetPrefix("invalid"))
        assertEquals(null, ipv4SubnetPrefix("192.168.300.1"))
    }

    @Test
    fun offlineCredentialHasher_usesPasswordAndDeviceSecret() {
        val salt = "salt".encodeToByteArray()
        val secret = "device-secret".encodeToByteArray()

        val verifier = OfflineCredentialHasher.verifier("demo", "password", salt, secret)
        val same = OfflineCredentialHasher.verifier("demo", "password", salt, secret)
        val different = OfflineCredentialHasher.verifier("demo", "wrong", salt, secret)

        assertEquals(verifier, same)
        assertNotEquals(verifier, different)
        Base64.getDecoder().decode(verifier)
    }

    @Test
    fun sessionState_hasUsableOfflineCredentialRequiresCompleteUnexpiredCredential() {
        val valid = SessionState(
            user = UserProfile(id = 1, username = "demo"),
            offlineCredentialId = "credential",
            offlineUsername = "demo",
            offlinePasswordSalt = "salt",
            offlinePasswordVerifier = "verifier",
            offlineExpiresAt = System.currentTimeMillis() + 60_000,
        )

        val expired = valid.copy(offlineExpiresAt = System.currentTimeMillis() - 1)
        val missingCredential = valid.copy(offlineCredentialId = "")

        assertEquals(true, valid.hasUsableOfflineCredential)
        assertEquals(false, expired.hasUsableOfflineCredential)
        assertEquals(false, missingCredential.hasUsableOfflineCredential)
        assertEquals(false, SessionState().hasUsableOfflineCredential)
    }

    @Test
    fun decodeReceivingSessionsPayload_acceptsRawArray() {
        val payload = json.encodeToJsonElement(
            listOf(
                ReceivingSession(id = 1, code = "RCV-001"),
                ReceivingSession(id = 2, code = "RCV-002"),
            ),
        )

        val rows = decodeReceivingSessionsPayload(payload, json)

        assertEquals(listOf("RCV-001", "RCV-002"), rows.map { it.code })
    }

    @Test
    fun decodeReceivingSessionsPayload_acceptsPagedObject() {
        val payload = buildJsonObject {
            put(
                "records",
                json.encodeToJsonElement(
                    listOf(ReceivingSession(id = 9, code = "RCV-009")),
                ),
            )
            put("total", 1)
        }

        val rows = decodeReceivingSessionsPayload(payload, json)

        assertEquals(listOf("RCV-009"), rows.map { it.code })
    }
}
