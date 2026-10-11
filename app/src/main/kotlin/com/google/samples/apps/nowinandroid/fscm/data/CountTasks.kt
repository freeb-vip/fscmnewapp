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

@Serializable
data class CountTask(
    val id: String,
    val number: String = "",
    val sku: String = "",
    @SerialName("sku_id") val skuId: String = "",
    @SerialName("product_name") val productName: String = "",
    @SerialName("location_code") val locationCode: String = "",
    @SerialName("warehouse_name") val warehouseName: String = "",
    @SerialName("draft_quantity") val draftQuantity: Long? = null,
    @SerialName("input_method") val inputMethod: String = "manual",
    val unit: String = "",
    val source: String = "web",
    val remark: String = "",
    val status: String = "pending",
    val version: Long = 1,
    @SerialName("config_version") val configVersion: Long = 0,
    @SerialName("claimed_by") val claimedBy: Long? = null,
    @SerialName("operator_name") val operatorName: String = "",
    @SerialName("creator_name") val creatorName: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("last_record_id") val lastRecordId: String? = null,
)

@Serializable
data class CountTaskPage(val items: List<CountTask> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false)

@Serializable
data class CountTaskEvent(val id: Long, @SerialName("actor_name") val actorName: String, val kind: String, @SerialName("created_at") val createdAt: String)

@Serializable
data class CountTaskDetail(val task: CountTask, val records: List<StocktakeRecord> = emptyList(), val events: List<CountTaskEvent> = emptyList())
