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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class SessionMode {
    Online,
    Offline,
}

@Serializable
data class UserProfile(
    val id: Long = 0,
    val username: String = "",
    val name: String = "",
    val role: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
)

@Serializable
data class NamespaceProfile(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("logo_url") val logoUrl: String = "",
    val role: String = "",
    @SerialName("role_code") val roleCode: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class AuthPayload(
    val token: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val user: UserProfile = UserProfile(),
    val permissions: List<String> = emptyList(),
    @SerialName("effective_role") val effectiveRole: String = "",
    val namespaces: List<NamespaceProfile> = emptyList(),
    @SerialName("current_namespace") val currentNamespace: NamespaceProfile? = null,
)

@Serializable
data class LoginConfig(
    val password: Boolean = true,
    val methods: List<String> = emptyList(),
    @SerialName("enabled_methods") val enabledMethods: List<String> = emptyList(),
    @SerialName("login_methods") val loginMethods: List<String> = emptyList(),
)

@Serializable
data class SwitchNamespaceResponse(
    @SerialName("namespace_id") val namespaceId: Long = 0,
    val namespace: NamespaceProfile? = null,
    val role: String = "",
)

@Serializable
data class SessionState(
    val token: String = "",
    val refreshToken: String = "",
    val user: UserProfile? = null,
    val permissions: List<String> = emptyList(),
    val effectiveRole: String = "",
    val namespaces: List<NamespaceProfile> = emptyList(),
    val currentNamespace: NamespaceProfile? = null,
    val sessionMode: SessionMode = SessionMode.Online,
    val offlineCredentialId: String = "",
    val offlineUsername: String = "",
    val offlinePasswordSalt: String = "",
    val offlinePasswordVerifier: String = "",
    val offlineIssuedAt: Long = 0,
    val offlineExpiresAt: Long = 0,
    val lastOnlineVerifiedAt: Long = 0,
    val offlineSessionExpiresAt: Long = 0,
) {
    val isLoggedIn: Boolean get() = user != null && (token.isNotBlank() || isOffline)
    val isOffline: Boolean get() = sessionMode == SessionMode.Offline
    val hasValidOfflineSession: Boolean get() =
        maxOf(offlineSessionExpiresAt, offlineExpiresAt) > System.currentTimeMillis()
    val hasUsableOfflineCredential: Boolean get() =
        hasValidOfflineSession &&
            user != null &&
            offlineCredentialId.isNotBlank() &&
            offlineUsername.isNotBlank() &&
            offlinePasswordSalt.isNotBlank() &&
            offlinePasswordVerifier.isNotBlank()
}

@Serializable
data class MobileWorkbenchOverview(
    val user: UserProfile = UserProfile(),
    @SerialName("current_namespace") val currentNamespace: NamespaceProfile? = null,
    @SerialName("visible_tabs") val visibleTabs: List<MobileTab> = emptyList(),
    val todos: List<MobileTodo> = emptyList(),
    val announcements: List<MobileAnnouncement> = emptyList(),
    val activities: List<MobileActivity> = emptyList(),
    @SerialName("quick_entries") val quickEntries: List<MobileAppEntry> = emptyList(),
    val apps: List<MobileAppEntry> = emptyList(),
    @SerialName("app_columns") val appColumns: Int = 4,
    @SerialName("apps_per_row") val appsPerRow: Int = 4,
    @SerialName("app_config") val appConfig: MobileAppConfig? = null,
    @SerialName("role_profit_summary") val roleProfitSummary: MobileRoleProfitSummary? = null,
)

@Serializable
data class MobileTab(
    val key: String = "",
    val title: String = "",
    val path: String = "",
    val visible: Boolean = true,
)

@Serializable
data class MobileAppConfig(
    val columns: Int = 4,
    @SerialName("apps_per_row") val appsPerRow: Int = 4,
    val apps: List<MobileAppEntry> = emptyList(),
)

@Serializable
data class MobileAppEntry(
    val key: String = "",
    val title: String = "",
    val description: String = "",
    val path: String = "",
    val permission: String = "",
    @SerialName("business_permission") val businessPermission: String = "",
    val icon: String = "",
    val color: String = "",
    @SerialName("badge_count") val badgeCount: Int = 0,
    val visible: Boolean = true,
    val sort: Int = 0,
    @SerialName("offline_supported") val offlineSupported: Boolean = false,
    @SerialName("offline_read_only") val offlineReadOnly: Boolean = false,
    @SerialName("offline_syncable") val offlineSyncable: Boolean = false,
    @SerialName("offline_data_scope") val offlineDataScope: String = "",
    @SerialName("offline_required_permissions") val offlineRequiredPermissions: List<String> = emptyList(),
)

@Serializable
data class MobileTodo(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val content: String = "",
    val status: String = "",
    val priority: String = "",
    @SerialName("due_at") val dueAt: String = "",
    @SerialName("due_date") val dueDate: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val path: String = "",
)

@Serializable
data class MobileAnnouncement(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("published_at") val publishedAt: String = "",
)

@Serializable
data class MobileActivity(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class MobileRoleProfitSummary(
    val audience: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("total_profit") val totalProfit: Double = 0.0,
    @SerialName("total_gmv") val totalGmv: Double = 0.0,
    @SerialName("total_orders") val totalOrders: Int = 0,
    @SerialName("profit_margin") val profitMargin: Double = 0.0,
)

@Serializable
data class ProfitOverview(
    val audience: String = "",
    val permissions: List<String> = emptyList(),
    val summary: ProfitSummary = ProfitSummary(),
    val shops: List<ShopOverview> = emptyList(),
    val trend: ShopTrendResponse? = null,
)

@Serializable
data class ProfitSummary(
    @SerialName("total_profit") val totalProfit: Double = 0.0,
    @SerialName("avg_profit_margin") val avgProfitMargin: Double = 0.0,
    @SerialName("ad_spend_ratio") val adSpendRatio: Double = 0.0,
    @SerialName("product_cost_ratio") val productCostRatio: Double = 0.0,
    @SerialName("shipping_costs_ratio") val shippingCostsRatio: Double = 0.0,
    @SerialName("commission_ratio") val commissionRatio: Double = 0.0,
    @SerialName("total_orders") val totalOrders: Int = 0,
    @SerialName("total_gmv") val totalGmv: Double = 0.0,
    @SerialName("avg_commission") val avgCommission: Double = 0.0,
)

@Serializable
data class ShopOverview(
    @SerialName("shop_id") val shopId: Long = 0,
    @SerialName("shop_name") val shopName: String = "",
    val platform: String = "",
    val profit: Double = 0.0,
    @SerialName("profit_margin") val profitMargin: Double = 0.0,
    @SerialName("gross_sales") val grossSales: Double = 0.0,
    @SerialName("total_orders") val totalOrders: Int = 0,
    @SerialName("latest_report_date") val latestReportDate: String = "",
)

@Serializable
data class ShopTrendResponse(
    val metric: String = "profit",
    val granularity: String = "auto",
    val buckets: List<String> = emptyList(),
    val series: List<ShopTrendSeries> = emptyList(),
    val ranking: List<ShopTrendRank> = emptyList(),
    @SerialName("truncated_count") val truncatedCount: Int = 0,
)

@Serializable
data class ShopTrendSeries(
    @SerialName("shop_id") val shopId: Long = 0,
    @SerialName("shop_name") val shopName: String = "",
    val values: List<Double> = emptyList(),
)

@Serializable
data class ShopTrendRank(
    @SerialName("shop_id") val shopId: Long = 0,
    @SerialName("shop_name") val shopName: String = "",
    val platform: String = "",
    val rank: Int = 0,
    val value: Double = 0.0,
    val latest: Double = 0.0,
    val share: Double = 0.0,
)

@Serializable
data class ProfitShopDetail(
    val shop: ShopDetail? = null,
    val reports: List<JsonElement> = emptyList(),
    val total: Long = 0,
)

@Serializable
data class ShopDetail(
    val id: Long = 0,
    val name: String = "",
    val platform: String = "",
    @SerialName("is_followed") val isFollowed: Boolean = false,
)

@Serializable
data class ReceivingSession(
    val id: Long = 0,
    val code: String = "",
    @SerialName("warehouse_name") val warehouseName: String = "",
    @SerialName("container_no") val containerNo: String = "",
    @SerialName("expected_box_count") val expectedBoxCount: Int = 0,
    @SerialName("pending_box_count") val pendingBoxCount: Int = 0,
    @SerialName("tallied_box_count") val talliedBoxCount: Int = 0,
    @SerialName("scanned_box_count") val scannedBoxCount: Int = 0,
    @SerialName("exception_box_count") val exceptionBoxCount: Int = 0,
    val status: String = "",
    val remark: String = "",
    @SerialName("scan_items") val scanItems: List<ReceivingScanItem> = emptyList(),
)

@Serializable
data class ReceivingScanItem(
    val id: Long = 0,
    @SerialName("box_uid") val boxUid: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("box_no") val boxNo: String = "",
    @SerialName("raw_code") val rawCode: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("sku_name") val skuName: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("box_qty") val boxQty: Int = 0,
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("actual_received_qty") val actualReceivedQty: Int = 0,
    val status: String = "",
    @SerialName("exception_reason") val exceptionReason: String = "",
    @SerialName("scanned_at") val scannedAt: String = "",
)

@Serializable
data class ReceivingScanResponse(
    val data: ReceivingScanItem = ReceivingScanItem(),
    val duplicate: Boolean = false,
)

@Serializable
data class PagedList<T>(
    val data: List<T> = emptyList(),
    val items: List<T> = emptyList(),
    val list: List<T> = emptyList(),
    val records: List<T> = emptyList(),
    val total: Long = 0,
) {
    fun rows(): List<T> = when {
        data.isNotEmpty() -> data
        items.isNotEmpty() -> items
        list.isNotEmpty() -> list
        else -> records
    }
}

enum class BusinessTypeCode(val wireValue: String) {
    SkuDetail("sku_detail"),
    BoxLabelDetail("box_label_detail"),
    LocalApp("local_app"),
    Unknown("unknown"),
}

fun String.toBusinessTypeCode(): BusinessTypeCode =
    BusinessTypeCode.entries.firstOrNull { it.wireValue == this } ?: BusinessTypeCode.Unknown

@Serializable
data class GlobalSearchHit(
    @SerialName("business_type") val businessType: String,
    @SerialName("target_id") val targetId: String = "",
    @SerialName("target_code") val targetCode: String = "",
    val title: String,
    val subtitle: String = "",
    val status: String = "",
    @SerialName("match_kind") val matchKind: String = "fuzzy",
    val score: Double = 0.0,
    val source: String = "remote",
)

@Serializable
data class GlobalSearchResponse(
    val query: String = "",
    @SerialName("normalized_query") val normalizedQuery: String = "",
    val hits: List<GlobalSearchHit> = emptyList(),
)

@Serializable
data class SearchBusinessType(
    val code: String,
    val title: String,
    @SerialName("required_permissions") val requiredPermissions: List<String> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class SearchBusinessTypesResponse(
    val types: List<SearchBusinessType> = emptyList(),
)

@Serializable
data class SearchCachePayload(
    val response: GlobalSearchResponse,
    val cachedAt: Long,
)

sealed interface SearchDestination {
    data class LocalApp(val entry: MobileAppEntry) : SearchDestination
    data class Business(val hit: GlobalSearchHit) : SearchDestination
}

@Serializable
data class EdgePrinter(
    val name: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    val status: String = "",
)

@Serializable
data class EdgePrintTemplate(
    val type: String = "label",
    val code: String = "",
    val name: String = "",
    @SerialName("printer_name") val printerName: String = "",
    @SerialName("width_mm") val widthMM: Double = 0.0,
    @SerialName("height_mm") val heightMM: Double = 0.0,
    val orientation: String = "",
    val version: String = "",
    val available: Boolean = false,
)

@Serializable
data class EdgePrintInventory(
    val printers: List<EdgePrinter> = emptyList(),
    val templates: List<EdgePrintTemplate> = emptyList(),
)

@Serializable
data class EdgeNode(
    val id: Long = 0,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("lan_base_url") val lanBaseUrl: String = "",
    val capabilities: List<String> = emptyList(),
    @SerialName("print_inventory") val printInventory: EdgePrintInventory = EdgePrintInventory(),
    val status: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String = "",
)

data class EdgePrintNodeOption(
    val node: EdgeNode,
    val template: EdgePrintTemplate,
    val localReachable: Boolean,
) {
    val selectionKey: String get() = "${node.id}:${template.code}"
}

data class EdgePrintNodeStatus(
    val node: EdgeNode,
    val localReachable: Boolean,
) {
    val identityKey: String
        get() = if (node.id > 0) "center:${node.id}" else "lan:${node.nodeId}:${node.lanBaseUrl}"
}

@Serializable
data class EdgeTerminalRegistrationRequest(
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("edge_node_id") val edgeNodeId: String,
    @SerialName("terminal_name") val terminalName: String,
    @SerialName("app_version") val appVersion: String,
    val platform: String,
    @SerialName("lan_ip") val lanIp: String,
    val capabilities: List<String>,
)

@Serializable
data class EdgePrintJob(
    val id: Long = 0,
    @SerialName("edge_node_id") val edgeNodeId: Long = 0,
    @SerialName("sku_id") val skuId: Long = 0,
    @SerialName("template_code") val templateCode: String = "",
    @SerialName("printer_name") val printerName: String = "",
    val copies: Int = 1,
    val status: String = "",
)

data class EdgePrintSubmission(
    val nodeName: String,
    val local: Boolean,
    val status: String,
    val jobId: String,
)

@Serializable
data class Product(
    val id: Long = 0,
    val code: String = "",
    val name: String = "",
    val brand: String = "",
    val material: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val features: String = "",
    val detail: String = "",
    val skus: List<Sku> = emptyList(),
)

@Serializable
data class Sku(
    val id: Long = 0,
    val code: String = "",
    @SerialName("product_id") val productId: Long = 0,
    val name: String = "",
    val spec: String = "",
    val color: String = "",
    val size: String = "",
    val weight: Double = 0.0,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val product: Product? = null,
)

@Serializable
data class BoxLabelDocumentRef(
    val id: Long = 0,
    val code: String = "",
    val status: String = "",
)

@Serializable
data class BoxLabelSKUItem(
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("qty_per_box") val qtyPerBox: Int = 0,
)

@Serializable
data class BoxLabel(
    @SerialName("box_uid") val boxUid: String = "",
    @SerialName("label_code") val labelCode: String = "",
    @SerialName("box_no") val boxNo: String = "",
    val status: String = "",
    @SerialName("warning_code") val warningCode: String = "",
    @SerialName("warning_message") val warningMessage: String = "",
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    @SerialName("case_spec_name") val caseSpecName: String = "",
    @SerialName("qty_per_carton") val qtyPerCarton: Int = 0,
    @SerialName("planned_box_qty") val plannedBoxQty: Int = 0,
    @SerialName("actual_box_qty") val actualBoxQty: Int = 0,
    @SerialName("warehouse_code") val warehouseCode: String = "",
    @SerialName("location_code") val locationCode: String = "",
    @SerialName("supplier_order") val supplierOrder: BoxLabelDocumentRef? = null,
    @SerialName("purchase_order") val purchaseOrder: BoxLabelDocumentRef? = null,
    @SerialName("central_receipt") val centralReceipt: BoxLabelDocumentRef? = null,
    @SerialName("consolidation_order") val consolidationOrder: BoxLabelDocumentRef? = null,
    @SerialName("receiving_session") val receivingSession: BoxLabelDocumentRef? = null,
    @SerialName("receiving_record") val receivingRecord: BoxLabelDocumentRef? = null,
    @SerialName("sku_items") val skuItems: List<BoxLabelSKUItem> = emptyList(),
    val warnings: List<String> = emptyList(),
)
