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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

const val STOCKTAKE_MAX_QUANTITY = 9_007_199_254_740_991L

fun stocktakeQuantity(text: String): Long? = text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toLongOrNull()?.takeIf { it in 0..STOCKTAKE_MAX_QUANTITY }

fun stocktakeNeedsConfirmation(before: Long, counted: Long): Boolean = counted - before > 100 || before - counted > 100

internal fun stocktakeScope(session: SessionState): String =
    "${BuildConfig.BACKEND_URL}|${session.user?.id ?: 0}|${session.currentNamespace?.id ?: 0}"

@Serializable
data class StocktakeWorkspace(
    val status: String = "unconfigured",
    val message: String = "",
    @SerialName("warehouse_id") val warehouseId: String = "",
    @SerialName("warehouse_name") val warehouseName: String = "",
    val version: Long = 0,
)

@Serializable
data class StocktakeContext(
    val workspace: StocktakeWorkspace = StocktakeWorkspace(),
    @SerialName("write_enabled") val writeEnabled: Boolean = false,
    @SerialName("block_reason") val blockReason: String = "",
    @SerialName("confirmation_threshold") val confirmationThreshold: Long = 100,
)

@Serializable
data class StocktakeStock(
    @SerialName("external_id") val externalId: String,
    @SerialName("location_id") val locationId: String,
    @SerialName("location_code") val locationCode: String,
    val sku: String,
    val name: String? = null,
    @SerialName("on_hand") val onHand: Long? = null,
    val unit: String? = null,
    @SerialName("supports_picking") val supportsPicking: Boolean? = null,
    @SerialName("zone_name") val zoneName: String? = null,
)

@Serializable
data class StocktakeLookup(
    val status: String,
    val stock: StocktakeStock? = null,
    @SerialName("snapshot_id") val snapshotId: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("query_id") val queryId: String = "",
) {
    fun usable(now: Instant = Instant.now()): Boolean = status == "ready" && (stock?.onHand?.let { it >= 0 } == true) &&
        snapshotId.isNotBlank() && runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false)
}

@Serializable
data class StocktakePage(val items: List<StocktakeStock> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false)

@Serializable
data class StocktakeTask(
    val id: String,
    val status: String,
    val result: JsonElement? = null,
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("retry_after_ms") val retryAfterMs: Long = 500,
)

@Serializable
data class StocktakeSubmissionInput(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("counted_quantity") val countedQuantity: Long,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("confirmation_token") val confirmationToken: String? = null,
    @SerialName("confirmed_quantity") val confirmedQuantity: Long? = null,
    @SerialName("count_task_id") val countTaskId: String? = null,
    @SerialName("count_task_version") val countTaskVersion: Long? = null,
)

@Serializable
data class StocktakeRecord(
    val id: String,
    val status: String,
    @SerialName("location_code") val locationCode: String,
    val sku: String,
    @SerialName("before_quantity") val beforeQuantity: Long,
    @SerialName("counted_quantity") val countedQuantity: Long,
    val delta: Long,
    @SerialName("operator_name") val operatorName: String = "",
    @SerialName("error_message") val errorMessage: String = "",
    @SerialName("verified_quantity") val verifiedQuantity: Long? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("request_id") val requestId: String = "",
    @SerialName("snapshot_id") val snapshotId: String = "",
    @SerialName("count_task_id") val countTaskId: String? = null,
    @SerialName("input_method") val inputMethod: String = "manual",
    val unit: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("risk_messages") val riskMessages: List<String> = emptyList(),
    @SerialName("reenter_required") val reenterRequired: Boolean = true,
)

@Serializable
data class StocktakeSubmission(val record: StocktakeRecord, @SerialName("confirmation_token") val confirmationToken: String? = null)

@Serializable
data class StocktakeDetail(val record: StocktakeRecord)

@Serializable
data class StocktakeDraft(
    val location: String = "",
    val sku: String = "",
    val quantity: String = "",
    val pending: StocktakeSubmissionInput? = null,
    val lookup: StocktakeLookup? = null,
    val workspaceVersion: Long = 0,
    val inputMethod: String = "manual",
    val taskVersion: Long = 0,
    val createRequestId: String = "",
)

@Singleton
class LocationStocktakeRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson private val json: Json,
) {
    suspend fun context(): StocktakeContext = api.get("location-stocktakes/context")

    suspend fun lookup(location: String, sku: String = ""): StocktakeLookup = json.decodeFromJsonElement(
        query(if (sku.isBlank()) "location" else "match", location, sku),
    )

    suspend fun locations(sku: String, page: Int): StocktakePage = json.decodeFromJsonElement(query("sku_locations", "", sku, page))
    suspend fun shelf(location: String, page: Int): StocktakePage = json.decodeFromJsonElement(query("location_skus", location, "", page))

    suspend fun countTasks(status: String, mine: Boolean, page: Int): CountTaskPage = api.get("location-stocktakes/count-tasks", mapOf("status" to status, "mine" to mine.toString(), "page" to page.toString(), "page_size" to "20"))
    suspend fun countTask(id: String): CountTaskDetail = api.get("location-stocktakes/count-tasks/$id")
    suspend fun quick(requestId: String): CountTask = api.post(
        "location-stocktakes/count-tasks",
        buildJsonObject {
            put("quick", true)
            put("request_id", requestId)
        },
    )
    suspend fun changeTask(task: CountTask, action: String, queryId: String = "", quantity: Long? = null, inputMethod: String = "manual", clearTarget: Boolean = false): CountTask = api.post(
        "location-stocktakes/count-tasks/${task.id}/$action",
        buildJsonObject {
            put("version", task.version)
            if (queryId.isNotBlank()) put("query_id", queryId)
            if (quantity != null) put("quantity", quantity)
            put("input_method", inputMethod)
            put("clear_target", clearTarget)
        },
    )

    private suspend fun query(kind: String, location: String, sku: String, page: Int = 1): JsonElement = withTimeout(120_000) {
        var task = api.post<StocktakeTask>(
            "location-stocktakes/queries",
            buildJsonObject {
                put("kind", kind)
                put("location_code", location)
                put("sku", sku)
                put("page", page)
                put("page_size", 20)
            },
        )
        while (task.status != "succeeded") {
            if (task.status in setOf("failed", "cancelled", "result_unknown", "blocked_auth", "blocked_config")) {
                throw StocktakeQueryException(task.errorMessage)
            }
            delay(task.retryAfterMs.coerceIn(500, 5000))
            task = api.get("location-stocktakes/tasks/${task.id}")
        }
        task.result ?: throw StocktakeQueryException("")
    }

    suspend fun submit(input: StocktakeSubmissionInput): StocktakeSubmission = api.post("location-stocktakes/submissions", json.encodeToJsonElement(input))

    suspend fun record(id: String): StocktakeRecord = api.get<StocktakeDetail>("location-stocktakes/records/$id").record
}

class StocktakeQueryException(message: String) : Exception(message)

private val Context.stocktakeDataStore by preferencesDataStore(name = "fscm_location_stocktakes")

@Singleton
class LocationStocktakeDraftStore @Inject constructor(
    @ApplicationContext context: Context,
    @param:FscmJson private val json: Json,
) {
    private val store = context.stocktakeDataStore

    suspend fun read(scope: String): StocktakeDraft = store.data.first()[stringPreferencesKey(scope)]
        ?.let { json.decodeFromString<StocktakeDraft>(it) } ?: StocktakeDraft()

    suspend fun save(scope: String, draft: StocktakeDraft) {
        store.edit { it[stringPreferencesKey(scope)] = json.encodeToString(draft) }
    }
}
