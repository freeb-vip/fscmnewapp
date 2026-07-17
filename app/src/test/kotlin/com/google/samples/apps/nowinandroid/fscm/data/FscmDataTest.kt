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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
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
    fun skuDetailResponse_unwrapsDirectDataEnvelope() {
        val payload = json.parseToJsonElement(
            """{"data":{"id":8,"code":"SKU-8","name":"测试 SKU","product_id":4}}""",
        )

        val sku = decodeBusinessObject<Sku>(payload, json)

        assertEquals(8L, sku.id)
        assertEquals("SKU-8", sku.code)
        assertEquals(4L, sku.productId)
    }

    @Test
    fun boxLabelResponse_unwrapsLegacyDataEnvelope() {
        val payload = json.parseToJsonElement(
            """{"data":{"recognized":true,"label_code":"BOX-001","box_no":"1"}}""",
        )

        val boxLabel = decodeBusinessObject<BoxLabel>(payload, json)

        assertTrue(boxLabel.recognized)
        assertEquals("BOX-001", boxLabel.labelCode)
    }

    @Test
    fun boxLabel_decodesPrintableSnapshot() {
        val boxLabel = json.decodeFromString<BoxLabel>(
            """{"id":42,"recognized":true,"label_code":"BOX-042","printable":true,"print_snapshot":{"label_code":"BOX-042","box_no":"1/4"}}""",
        )

        assertEquals(42, boxLabel.id)
        assertTrue(boxLabel.printable)
        assertEquals("BOX-042", boxLabel.printSnapshot?.get("label_code")?.jsonPrimitive?.content)
    }

    @Test
    fun receivingSessionResponse_unwrapsLegacyDataEnvelope() {
        val payload = json.parseToJsonElement(
            """{"data":{"id":3,"code":"RCV-003","warehouse_name":"上海仓"}}""",
        )

        val session = decodeBusinessObject<ReceivingSession>(payload, json)

        assertEquals(3L, session.id)
        assertEquals("RCV-003", session.code)
        assertEquals("上海仓", session.warehouseName)
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
    fun terminalRegistration_includesDiscoveredEdgeNode() {
        val request = EdgeTerminalRegistrationRequest(
            terminalId = "android-123",
            edgeNodeId = "edge-01",
            terminalName = "FSCM Android",
            appVersion = "0.1.2",
            platform = "android",
            lanIp = "192.168.1.12",
            capabilities = listOf("mobile-app"),
        )

        val body = json.encodeToJsonElement(request) as JsonObject

        assertEquals("android-123", body["terminal_id"]?.jsonPrimitive?.content)
        assertEquals("edge-01", body["edge_node_id"]?.jsonPrimitive?.content)
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

        assertEquals(listOf("profit", "receiving", "product_management", "inbound_management", "label_print", "edge_nodes", "pda_settings"), apps.map { it.key })
    }

    @Test
    fun visibleMobileApps_excludesEntriesWithoutImplementedNativePages() {
        val overview = MobileWorkbenchOverview(
            apps = listOf(
                MobileAppEntry(key = "todos", title = "待办"),
                MobileAppEntry(key = "supplier_orders", title = "供应商订单"),
                MobileAppEntry(key = "purchase_approvals", title = "采购审批"),
                MobileAppEntry(key = "future_app", title = "未来应用"),
                MobileAppEntry(key = "profit", title = "利润"),
            ),
        )

        val apps = visibleMobileApps(overview, permissions = emptyList())

        assertTrue(apps.all { it.key in implementedMobileAppKeys })
        assertTrue(apps.any { it.key == "profit" })
        assertTrue(apps.none { it.key in setOf("todos", "supplier_orders", "purchase_approvals", "future_app") })
    }

    @Test
    fun defaultMobileApps_containsOnlyImplementedNativePages() {
        assertEquals(implementedMobileAppKeys, defaultMobileApps.mapTo(mutableSetOf()) { it.key })
    }

    @Test
    fun visibleMobileApps_keepsOnlyWarehouseReceivingEntry() {
        val apps = visibleMobileApps(
            overview = MobileWorkbenchOverview(
                apps = listOf(
                    MobileAppEntry(key = "receiving", title = "仓库收货"),
                    MobileAppEntry(key = "central_receiving", title = "集运收货"),
                ),
            ),
            permissions = emptyList(),
        )

        assertEquals(1, apps.count { it.key == "receiving" })
        assertTrue(apps.none { it.key == "central_receiving" })
        assertTrue(localSearchHits("集运收货", apps).isEmpty())
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
    fun localSkuPrintRequest_usesSnapshotWithoutCenterPrintConfiguration() {
        val request = buildLocalSkuPrintRequest(
            json = json,
            option = EdgePrintNodeOption(
                node = EdgeNode(id = 7),
                template = EdgePrintTemplate(code = "sku_60x40", printerName = "Center Printer", version = "5"),
                localReachable = true,
            ),
            sku = Sku(
                id = 8,
                code = "SKU-8",
                name = "测试 SKU",
                spec = "标准款",
                color = "蓝色",
                product = Product(code = "PRODUCT-8"),
            ),
            copies = 2,
            labelPrefix = "T",
            idempotencyKey = "print-8",
        )

        val body = json.encodeToJsonElement(request) as JsonObject
        val snapshot = body["payload_snapshot"] as JsonObject

        assertEquals(setOf("source", "template_code", "copies", "type", "idempotency_key", "payload_snapshot"), body.keys)
        assertEquals("mobile-app", body["source"]?.jsonPrimitive?.content)
        assertEquals("sku_60x40", body["template_code"]?.jsonPrimitive?.content)
        assertEquals("sku_label", body["type"]?.jsonPrimitive?.content)
        assertEquals("print-8", body["idempotency_key"]?.jsonPrimitive?.content)
        assertEquals("sku_label", snapshot["type"]?.jsonPrimitive?.content)
        assertEquals("TSKU-8", snapshot["qr_payload"]?.jsonPrimitive?.content)
        assertEquals("5", snapshot["template_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun localLabelPrintRequest_usesCustomLabelSnapshot() {
        val request = buildLocalLabelPrintRequest(
            json = json,
            option = EdgePrintNodeOption(
                node = EdgeNode(id = 7),
                template = EdgePrintTemplate(code = "barcode_60x40", version = "3"),
                localReachable = true,
            ),
            content = "BOX-2026-001",
            copies = 2,
            idempotencyKey = "label-1",
        )

        val body = json.encodeToJsonElement(request) as JsonObject
        val snapshot = body["payload_snapshot"] as JsonObject

        assertEquals(setOf("source", "template_code", "copies", "type", "idempotency_key", "payload_snapshot"), body.keys)
        assertEquals("barcode_60x40", body["template_code"]?.jsonPrimitive?.content)
        assertEquals("custom_label", body["type"]?.jsonPrimitive?.content)
        assertEquals("custom_label", snapshot["type"]?.jsonPrimitive?.content)
        assertEquals("BOX-2026-001", snapshot["label_content"]?.jsonPrimitive?.content)
        assertEquals("BOX-2026-001", snapshot["text"]?.jsonPrimitive?.content)
        assertEquals("BOX-2026-001", snapshot["qr_payload"]?.jsonPrimitive?.content)
        assertEquals("barcode_60x40", snapshot["template_code"]?.jsonPrimitive?.content)
        assertEquals("3", snapshot["template_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun boxMarkTemplate_requiresDedicatedAvailable100x150PortraitTemplate() {
        val valid = EdgePrintTemplate(
            type = "manufacturer_box_mark",
            code = "manufacturer_box_mark_100x150mm",
            printerName = "ZDesigner",
            widthMM = 100.0,
            heightMM = 150.0,
            orientation = "portrait",
            available = true,
        )

        assertTrue(valid.isAvailableBoxMarkTemplate())
        assertTrue(!valid.copy(type = "label").isAvailableBoxMarkTemplate())
        assertTrue(!valid.copy(widthMM = 150.0, heightMM = 100.0).isAvailableBoxMarkTemplate())
        assertTrue(!valid.copy(orientation = "landscape").isAvailableBoxMarkTemplate())
        assertTrue(!valid.copy(printerName = "").isAvailableBoxMarkTemplate())
    }

    @Test
    fun localBoxMarkPrintRequest_usesFrozenManufacturerSnapshot() {
        val frozen = buildJsonObject {
            put("label_code", "BOX-042")
            put("box_no", "1/4")
        }
        val request = buildLocalBoxMarkPrintRequest(
            option = EdgePrintNodeOption(
                node = EdgeNode(id = 7),
                template = EdgePrintTemplate(code = "manufacturer_box_mark_100x150mm"),
                localReachable = true,
            ),
            label = BoxLabel(id = 42, printable = true, printSnapshot = frozen),
            copies = 3,
            idempotencyKey = "mobile-box-mark-fixed",
        )

        val body = json.encodeToJsonElement(request) as JsonObject
        val snapshot = body["payload_snapshot"] as JsonObject
        val items = snapshot["items"] as JsonArray

        assertEquals(setOf("source", "template_code", "copies", "type", "idempotency_key", "payload_snapshot"), body.keys)
        assertEquals("manufacturer_box_mark", body["type"]?.jsonPrimitive?.content)
        assertEquals("mobile-box-mark-fixed", body["idempotency_key"]?.jsonPrimitive?.content)
        assertEquals("manufacturer_box_mark", snapshot["kind"]?.jsonPrimitive?.content)
        assertEquals("manufacturer_box_mark.v1", snapshot["document_version"]?.jsonPrimitive?.content)
        assertEquals("100", snapshot["paper_width_mm"]?.jsonPrimitive?.content)
        assertEquals("150", snapshot["paper_height_mm"]?.jsonPrimitive?.content)
        assertEquals("portrait", snapshot["orientation"]?.jsonPrimitive?.content)
        assertEquals(frozen, items.single())
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

        assertEquals(listOf("receiving", "inbound_management"), apps.map { it.key })
    }

    @Test
    fun visibleMobileApps_defaultsIncludePdaSettingsEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        assertTrue(apps.any { it.key == "pda_settings" && it.localFallback && it.title.isEmpty() })
    }

    @Test
    fun defaultApps_includeSearchableEdgeNodesEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        val edgeNodes = apps.single { it.key == "edge_nodes" }
        assertTrue(edgeNodes.localFallback)
        assertTrue(edgeNodes.title.isEmpty())
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
        assertEquals("打印配置", apps.single { it.key == "edge_nodes" }.title)
        assertTrue(!apps.single { it.key == "edge_nodes" }.localFallback)
        assertTrue(apps.none { it.key == "print_settings" })
    }

    @Test
    fun visibleMobileApps_normalizesOtherImplementedAliases() {
        val apps = visibleMobileApps(
            overview = MobileWorkbenchOverview(
                apps = listOf(
                    MobileAppEntry(key = "carton_management", title = "装箱管理"),
                    MobileAppEntry(key = "scanner_settings", title = "扫描设置"),
                    MobileAppEntry(key = "print_label", title = "打印标签"),
                ),
            ),
            permissions = emptyList(),
        )

        assertEquals("装箱管理", apps.single { it.key == "box_labels" }.title)
        assertEquals("扫描设置", apps.single { it.key == "pda_settings" }.title)
        assertEquals("打印标签", apps.single { it.key == "label_print" }.title)
        assertTrue(apps.none { it.key in setOf("carton_management", "scanner_settings", "print_label") })
    }

    @Test
    fun defaultApps_includeSearchableLabelPrintEntry() {
        val apps = visibleMobileApps(
            overview = null,
            permissions = emptyList(),
        )

        val labelPrint = apps.single { it.key == "label_print" }
        assertTrue(labelPrint.localFallback)
        assertTrue(labelPrint.title.isEmpty())
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
    fun edgeTemplateUnavailable_requiresConflictAndStableErrorCode() {
        assertTrue(isEdgeTemplateUnavailable(409, "LABEL_TEMPLATE_UNAVAILABLE"))
        assertEquals(false, isEdgeTemplateUnavailable(400, "LABEL_TEMPLATE_UNAVAILABLE"))
        assertEquals(false, isEdgeTemplateUnavailable(409, "OTHER_ERROR"))
    }

    @Test
    fun localEdgeBaseUrlCandidates_supportsRegisteredApiSuffix() {
        assertEquals(
            listOf("http://192.168.1.20:8089", "http://192.168.1.20:8089/api"),
            localEdgeBaseUrlCandidates("http://192.168.1.20:8089/api/"),
        )
        assertEquals(
            listOf("http://192.168.1.20:8089"),
            localEdgeBaseUrlCandidates("http://192.168.1.20:8089"),
        )
        assertEquals("http://192.168.1.20:8089", localEdgeDirectBaseUrl("http://192.168.1.20:8089/api"))
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
    fun offlineCredentialHasher_android7FallbackMatchesSecretKeyFactory() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("password".toCharArray(), salt, 4_096, 256))
            .encoded

        assertEquals(
            expected.toList(),
            OfflineCredentialHasher.pbkdf2WithHmacSha256(password, salt, 4_096, 32).toList(),
        )
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
