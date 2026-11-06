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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

fun ticketScopeAttributes(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    is JsonArray -> value.map(::ticketScopeAttributes).filter(String::isNotBlank).joinToString(" · ")
    is JsonObject -> value.entries.joinToString(" · ") { (name, content) -> "$name: ${ticketScopeAttributes(content)}" }
}

@Serializable
data class TicketScopeItem(
    val id: Long = 0,
    @SerialName("shop_id") val shopId: Long? = null,
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("sku_id") val skuId: Long? = null,
    @SerialName("order_id") val orderId: Long? = null,
    @SerialName("order_line_id") val orderLineId: Long? = null,
    @SerialName("shop_name") val shopName: String = "",
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("sku_code") val skuCode: String = "",
    val spec: String = "",
    val attributes: JsonElement? = null,
    @SerialName("shop_sku") val shopSku: String = "",
    @SerialName("inventory_sku") val inventorySku: String = "",
    @SerialName("source_item_name") val sourceItemName: String = "",
    @SerialName("source_spec") val sourceSpec: String = "",
    val unavailable: Boolean = false,
    val matched: Boolean = false,
    @SerialName("source_items") val sourceItems: List<TicketScopeSource> = emptyList(),
) {
    fun input() = buildJsonObject {
        if (id > 0) put("id", id)
        shopId?.let { put("shop_id", it) }
        productId?.let { put("product_id", it) }
        skuId?.let { put("sku_id", it) }
        orderId?.let { put("order_id", it) }
        orderLineId?.let { put("order_line_id", it) }
    }

    fun identity() = if (productId != null || (orderLineId == null && shopId != null)) listOf(shopId, productId, skuId) else listOf(shopId, null, null, orderId, orderLineId)
}

@Serializable
data class TicketScopeSource(
    @SerialName("order_id") val orderId: Long = 0,
    @SerialName("order_line_id") val orderLineId: Long = 0,
    @SerialName("shop_sku") val shopSku: String = "",
    @SerialName("inventory_sku") val inventorySku: String = "",
    val name: String = "",
    val spec: String = "",
    val quantity: Int = 0,
)

@Serializable
data class TicketScopePermissions(@SerialName("edit_scope") val editScope: Boolean = false)

@Serializable
data class TicketReferenceOption(
    val id: Long = 0,
    val name: String = "",
    val code: String = "",
    @SerialName("product_id") val productId: Long? = null,
    @SerialName("product_code") val productCode: String = "",
    @SerialName("product_name") val productName: String = "",
    val spec: String = "",
    val platform: String = "",
    val site: String = "",
    @SerialName("source_name") val sourceName: String = "",
) {
    val label: String get() = listOf(code, name, spec, platform, site, sourceName).filter(String::isNotBlank).joinToString(" · ")
}

@Serializable
data class TicketReferencePage(val items: List<TicketReferenceOption> = emptyList(), val total: Int = 0)

@Singleton
class TicketScopeRepository @Inject constructor(private val api: FscmApiClient) {
    suspend fun references(kind: String, query: String, page: Int): TicketReferencePage =
        api.get("tickets/reference-options", mapOf("kind" to kind, "query" to query, "page" to page.toString(), "page_size" to "20"))

    suspend fun save(ticketId: Long, version: Long, items: List<TicketScopeItem>) {
        api.put<JsonElement>(
            "tickets/$ticketId",
            buildJsonObject {
                put("scope_version", version)
                putJsonArray("scope_items") { items.forEach { add(it.input()) } }
            },
        )
    }
}
