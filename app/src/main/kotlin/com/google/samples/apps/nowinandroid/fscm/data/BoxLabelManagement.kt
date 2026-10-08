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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class BoxLabelSearchPage(
    val data: List<BoxLabel> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    @SerialName("contract_version") val contractVersion: Int = 0,
    val search: BoxLabelSearchInfo? = null,
)

@Serializable
data class BoxLabelSearchInfo(
    val kinds: List<String> = emptyList(),
    @SerialName("match_mode") val matchMode: String = "",
    @SerialName("exact_label_id") val exactLabelId: Long = 0,
    @SerialName("scanned_code") val scannedCode: String = "",
    @SerialName("resolved_via") val resolvedVia: String = "",
)

@Serializable
data class BoxLabelDimensions(val length: Double = 0.0, val width: Double = 0.0, val height: Double = 0.0)

@Serializable
data class BoxLabelReceiving(
    val status: String = "",
    @SerialName("warehouse_code") val warehouseCode: String = "",
    @SerialName("location_code") val locationCode: String = "",
    @SerialName("actual_received_qty") val actualReceivedQty: Int = 0,
    val session: BoxLabelDocumentRef? = null,
    @SerialName("receiving_record") val receivingRecord: BoxLabelDocumentRef? = null,
)

@Serializable
data class BoxLabelRelation(
    val type: String = "",
    val id: Long = 0,
    val code: String = "",
    val historical: Boolean = false,
    @SerialName("box_label_code") val boxLabelCode: String = "",
)

@Serializable
data class BoxLabelAlias(
    val id: Long = 0,
    @SerialName("source_label_code") val sourceLabelCode: String = "",
    @SerialName("target_label_code") val targetLabelCode: String = "",
    @SerialName("source_quantity") val sourceQuantity: Int = 0,
    @SerialName("target_quantity") val targetQuantity: Int = 0,
    val status: String = "",
)

@Serializable
data class BoxLabelLifecycleEvent(
    val action: String = "",
    @SerialName("from_status") val fromStatus: String = "",
    @SerialName("to_status") val toStatus: String = "",
    val reason: String = "",
    val operator: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("source_type") val sourceType: String = "",
    @SerialName("source_id") val sourceId: Long = 0,
)

internal fun BoxLabelSearchPage.exactNavigationId(): Long? = search
    ?.takeIf { it.matchMode == "exact" && it.kinds == listOf("box_label") }
    ?.exactLabelId?.takeIf { it > 0 }

internal fun BoxLabel.samePrintContent(other: BoxLabel): Boolean =
    id == other.id && labelCode == other.labelCode && printable == other.printable &&
        printSnapshot == other.printSnapshot && skuItems == other.skuItems &&
        plannedBoxQty == other.plannedBoxQty

// The network layer already unwraps code/data/msg. Keep the page's own data array inside the page.
internal fun decodeBoxLabelSearchPage(payload: JsonElement, json: Json): BoxLabelSearchPage =
    json.decodeFromJsonElement<BoxLabelSearchPage>(payload).also {
        require(it.contractVersion >= 2) { "服务尚未支持新版箱唛搜索，请升级服务" }
    }
