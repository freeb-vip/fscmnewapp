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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FscmDataTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun receivingScanResponse_readsServerItemPayload() {
        val response = json.decodeFromString<ReceivingScanResponse>(
            """{"item":{"box_plan_id":4107,"label_code":"BF5E53CF6A8B","status":"normal","can_scan_rebind":true,"virtual_recovery_required":true},"duplicate":false}""",
        )

        assertEquals(4107, response.resolvedData.boxPlanId)
        assertEquals("BF5E53CF6A8B", response.resolvedData.labelCode)
        assertTrue(response.resolvedCanScanRebind)
        assertTrue(response.resolvedVirtualRecoveryRequired)
    }

    @Test
    fun receivingScanResponse_readsTopLevelVirtualRebindFlags() {
        val response = json.decodeFromString<ReceivingScanResponse>(
            """{"item":{"box_plan_id":4107,"label_code":"BF5E53CF6A8B"},"duplicate":true,"can_scan_rebind":true,"virtual_recovery_required":true}""",
        )

        assertTrue(response.resolvedCanScanRebind)
        assertTrue(response.resolvedVirtualRecoveryRequired)
    }

    @Test
    fun receivingSession_readsVirtualInboundOriginFlag() {
        val session = json.decodeFromString<ReceivingSession>(
            """{"id":18,"code":"RS-18","inbound_status":"completed","has_virtual_inbound":true}""",
        )

        assertTrue(session.hasVirtualInbound)
    }

    @Test
    fun loginSecurityState_decodesCaptchaFromInvalidCredentialsResponse() {
        val payload = json.parseToJsonElement(
            """{"code":40101,"data":{"failed_count":3,"captcha_threshold":3,"max_failed_attempts":5,"remaining_attempts":2,"require_captcha":true,"lock_remaining_seconds":0,"captcha":{"captcha_id":"challenge-1","question":"3 + 4 = ?","expires_at":"2026-07-21T12:00:00Z"},"reason_code":"INVALID_CREDENTIALS"},"msg":"invalid credentials"}""",
        )
        val error = FscmApiException(
            message = "invalid credentials",
            statusCode = 401,
            payload = payload,
            serverMessage = "invalid credentials",
        )

        val security = assertNotNull(error.loginSecurityStateOrNull(json))

        assertEquals(3, security.failedCount)
        assertEquals(2, security.remainingAttempts)
        assertTrue(security.requireCaptcha)
        assertEquals("challenge-1", security.captcha?.captchaId)
        assertEquals("3 + 4 = ?", security.captcha?.question)
    }

    @Test
    fun loginRequestBody_includesCaptchaOnlyWhenProvided() {
        val device = buildJsonObject { put("device_id", "android-1") }

        val plain = buildLoginRequestBody("user", "password", device)
        val verified = buildLoginRequestBody(
            username = "user",
            password = "password",
            device = device,
            captchaId = "challenge-1",
            captchaAnswer = "7",
        )

        assertFalse(plain.containsKey("captcha_id"))
        assertFalse(plain.containsKey("captcha_answer"))
        assertEquals("challenge-1", verified["captcha_id"]?.jsonPrimitive?.content)
        assertEquals("7", verified["captcha_answer"]?.jsonPrimitive?.content)
        assertEquals("android-1", (verified["device"] as JsonObject)["device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun skuSearchQuery_normalizesForExplicitMatchMode() {
        assertEquals("B4-", normalizeSkuSearchQuery(" tb4- "))
        assertEquals("B4-", normalizeSkuSearchQuery("pB4-"))
        assertEquals("B4-", normalizeSkuSearchQuery("Mb4-"))
        assertEquals("", normalizeSkuSearchQuery(""))
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
    fun boxMarkPrintAvailability_requiresAuthoritativePrintContract() {
        val snapshot = buildJsonObject { put("label_code", "BOX-042") }
        val printable = BoxLabel(id = 42, recognized = true, printable = true, printSnapshot = snapshot)

        assertEquals(BoxMarkPrintAvailability.Ready, printable.boxMarkPrintAvailability())
        assertEquals(
            BoxMarkPrintAvailability.NotPrintable,
            printable.copy(printable = false).boxMarkPrintAvailability(),
        )
        assertEquals(
            BoxMarkPrintAvailability.SnapshotMissing,
            printable.copy(printSnapshot = null).boxMarkPrintAvailability(),
        )
        assertEquals(
            BoxMarkPrintAvailability.OfflineFallback,
            printable.copy(localFallback = true).boxMarkPrintAvailability(),
        )
    }

    @Test
    fun freshEdgeBoxLabel_returnsImmediatelyOnlyWithCompletePrintContract() {
        val snapshot = buildJsonObject { put("label_code", "BOX-042") }
        val printable = CatalogResult(
            data = BoxLabel(id = 42, recognized = true, printable = true, printSnapshot = snapshot),
            source = CatalogSource.Edge,
        )

        assertTrue(shouldUseEdgeBoxLabelWithoutCenter(printable))
        assertFalse(shouldUseEdgeBoxLabelWithoutCenter(printable.copy(data = printable.data.copy(printable = false))))
        assertFalse(shouldUseEdgeBoxLabelWithoutCenter(printable.copy(data = printable.data.copy(printSnapshot = null))))
        assertFalse(shouldUseEdgeBoxLabelWithoutCenter(printable.copy(isStale = true)))
        assertTrue(
            shouldUseEdgeBoxLabelWithoutCenter(
                printable.copy(data = BoxLabel(recognized = false)),
            ),
        )
    }

    @Test
    fun temporaryCenterFailure_allowsEdgeDetailFallback() {
        listOf(408, 429, 500, 503).forEach { statusCode ->
            assertTrue(FscmApiException("temporary", statusCode).isTemporaryCenterFailure(), statusCode.toString())
        }
        listOf(400, 401, 404, 409).forEach { statusCode ->
            assertFalse(FscmApiException("authoritative", statusCode).isTemporaryCenterFailure(), statusCode.toString())
        }
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
    fun defaultPrintOption_matchesNodeAndTemplateCode() {
        val first = EdgePrintNodeOption(
            node = EdgeNode(id = 7),
            template = EdgePrintTemplate(code = "sku_small", version = "1"),
            localReachable = true,
        )
        val selected = EdgePrintNodeOption(
            node = EdgeNode(id = 7),
            template = EdgePrintTemplate(code = "sku_large", version = "2"),
            localReachable = true,
        )
        val sameCodeOnAnotherNode = EdgePrintNodeOption(
            node = EdgeNode(id = 8),
            template = EdgePrintTemplate(code = "sku_large", version = "2"),
            localReachable = false,
        )
        val options = listOf(first, selected, sameCodeOnAnotherNode)

        assertEquals(selected, options.defaultPrintOption(7, "sku_large"))
        assertEquals(sameCodeOnAnotherNode, options.defaultPrintOption(8, "sku_large"))
    }

    @Test
    fun defaultPrintOption_legacyNodeOnlyDefaultUsesFirstNodeTemplate() {
        val first = EdgePrintNodeOption(
            node = EdgeNode(id = 7),
            template = EdgePrintTemplate(code = "sku_small"),
            localReachable = true,
        )
        val second = first.copy(template = EdgePrintTemplate(code = "sku_large"))

        assertEquals(first, listOf(first, second).defaultPrintOption(7, ""))
    }

    @Test
    fun defaultPrintOption_doesNotFallbackWhenSavedTemplateIsUnavailable() {
        val available = EdgePrintNodeOption(
            node = EdgeNode(id = 7),
            template = EdgePrintTemplate(code = "sku_small"),
            localReachable = true,
        )

        assertEquals(null, listOf(available).defaultPrintOption(7, "removed_template"))
        assertEquals(null, listOf(available).defaultPrintOption(8, "sku_small"))
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
    fun visibleMobileApps_usesCenterAuthorizedEntriesWithoutLocalFallbacks() {
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
            permissions = emptyList(),
        )

        assertEquals(
            listOf("profit", "receiving", "product_management"),
            apps.map { it.key },
        )
    }

    @Test
    fun visibleMobileApps_includesImplementedSupplierOrdersAndExcludesUnsupportedEntries() {
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
        assertTrue(apps.any { it.key == "supplier_orders" })
        assertTrue(apps.none { it.key in setOf("todos", "purchase_approvals", "future_app") })
    }

    @Test
    fun defaultMobileApps_containsOnlyImplementedNativePages() {
        assertEquals(implementedMobileAppKeys, defaultMobileApps.mapTo(mutableSetOf()) { it.key })
    }

    @Test
    fun visibleMobileApps_keepsWarehouseAndCentralReceivingEntries() {
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
        assertEquals(1, apps.count { it.key == "central_receiving" })
        assertEquals("central_receiving", localSearchHits("集运收货", apps).single().targetCode)
    }

    @Test
    fun visibleMobileApps_requiresAllCentralReceivingOfflinePermissions() {
        val overview = MobileWorkbenchOverview(
            appConfig = MobileAppConfig(
                apps = listOf(MobileAppEntry(key = "central_receiving", title = "�����ջ�")),
            ),
        )

        listOf("receiving_records", "mobile-app:central-receiving").forEach { permission ->
            val apps = visibleMobileApps(
                overview = overview,
                permissions = listOf(permission),
                sessionMode = SessionMode.Offline,
            )

            assertTrue(apps.none { it.key == "central_receiving" }, permission)
        }

        val apps = visibleMobileApps(
            overview = overview,
            permissions = listOf("mobile-app:central-receiving", "receiving_records"),
            sessionMode = SessionMode.Offline,
        )

        assertEquals(listOf("central_receiving"), apps.map { it.key })
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
                  "number": "T01",
                  "name": "SKU 标签",
                  "printer_name": "ZDesigner",
                  "width_mm": 60,
                  "height_mm": 40,
                  "orientation": "landscape",
                  "layout_style": "qr_left_text_right",
                  "text_font_size_pt": 18,
                  "max_display_length": 16,
                  "label_qr_prefix": "BOX-",
                  "version": "a1b2c3d4e5f6",
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
        assertEquals("T01", node.printInventory.templates.single().number)
        assertEquals("qr_left_text_right", node.printInventory.templates.single().layoutStyle)
        assertEquals(18.0, node.printInventory.templates.single().textFontSizePoints)
        assertEquals("BOX-", node.printInventory.templates.single().labelQrPrefix)
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
                spec = "标准版",
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
                template = EdgePrintTemplate(code = "barcode_60x40", version = "3", labelQrPrefix = "Q-"),
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
        assertEquals("Q-BOX-2026-001", snapshot["qr_payload"]?.jsonPrimitive?.content)
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

        assertEquals(listOf("receiving"), apps.map { it.key })
    }

    @Test
    fun visibleMobileApps_withoutCenterOverviewShowsNoLocalFallbackApps() {
        assertTrue(visibleMobileApps(overview = null, permissions = emptyList()).isEmpty())
    }

    @Test
    fun visibleMobileApps_doesNotExposeLocalFallbackEdgeNodes() {
        assertTrue(visibleMobileApps(overview = null, permissions = emptyList()).isEmpty())
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
    fun visibleMobileApps_doesNotExposeLocalFallbackLabelPrint() {
        assertTrue(visibleMobileApps(overview = null, permissions = emptyList()).isEmpty())
    }

    @Test
    fun edgePrintOption_keyIncludesNodeAndTemplate() {
        val option = EdgePrintNodeOption(
            node = EdgeNode(id = 9),
            template = EdgePrintTemplate(code = "barcode_60x40", version = "v2"),
            localReachable = true,
        )

        assertEquals("9:barcode_60x40:v2", option.selectionKey)
        val secondNode = option.copy(node = EdgeNode(id = 10))
        assertEquals("10:barcode_60x40:v2", secondNode.selectionKey)
        assertTrue(option.selectionKey != secondNode.selectionKey)
    }

    @Test
    fun edgePrintTemplate_decodesLocationCodeLayoutContract() {
        val template = json.decodeFromString<EdgePrintTemplate>(
            """
            {
              "type": "label",
              "code": "location_100x150mm_landscape",
              "name": "库位 A",
              "printer_name": "ZDesigner",
              "width_mm": 100,
              "height_mm": 150,
              "orientation": "landscape",
              "layout_style": "location_code_quad_qr",
              "text_font_size_pt": 28,
              "max_display_length": 12,
              "label_qr_prefix": "",
              "version": "123456789abc",
              "available": true
            }
            """.trimIndent(),
        )

        assertEquals("location_code_quad_qr", template.layoutStyle)
        assertEquals(28.0, template.textFontSizePoints)
        assertEquals(12, template.maxDisplayLength)
        assertEquals("", template.labelQrPrefix)
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
        assertTrue(isEdgeTemplateUnavailable(409, "LABEL_TEMPLATE_CHANGED"))
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
                ReceivingSession(id = 1, code = "RCV-001", warehouseCode = "WH-1"),
                ReceivingSession(id = 2, code = "RCV-002"),
            ),
        )

        val rows = decodeReceivingSessionsPayload(payload, json)

        assertEquals(listOf("RCV-001", "RCV-002"), rows.map { it.code })
        assertEquals("WH-1", rows.first().warehouseCode)
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
