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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "auto_receipt_scan_items", indices = [Index(value = ["userId", "namespaceId", "sessionId"]), Index(value = ["requestId", "crossOrder"])])
data class AutoReceiptScanSnapshot(
    @PrimaryKey val id: String,
    val batchId: String,
    val userId: Long,
    val namespaceId: Long,
    val sessionId: Long,
    val requestId: String,
    val crossOrder: Boolean,
    val scanJson: String,
    val ackJson: String = "",
    val errorMessage: String = "",
)

@Serializable
data class InboundScanAck(
    val id: String,
    @SerialName("session_id") val sessionId: Long,
    @SerialName("box_plan_id") val boxPlanId: Long,
    @SerialName("request_id") val requestId: String,
    @SerialName("warehouse_location_id") val locationId: Long,
    @SerialName("location_code") val locationCode: String,
)

@Serializable
data class ResolvedInboundAck(
    @SerialName("request_id") val requestId: String,
    @SerialName("scan_ack") val ack: InboundScanAck? = null,
    val error: String = "",
)

internal fun inboundScopeHeaders(user: Long, namespace: Long) = mapOf(
    "X-FSCM-Expected-User-ID" to user.toString(),
    "X-FSCM-Expected-Namespace-ID" to namespace.toString(),
)
