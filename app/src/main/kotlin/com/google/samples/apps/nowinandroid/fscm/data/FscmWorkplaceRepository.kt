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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkplaceRepository @Inject constructor(
    private val api: FscmApiClient,
    @param:FscmJson private val json: Json,
) {
    suspend fun todos(): List<WorkplaceTodo> =
        decodeBusinessList(api.get("workplace/todos", mapOf("page" to "1", "page_size" to "100")), json)

    suspend fun createTodo(input: TodoInput): WorkplaceTodo =
        decodeBusinessObject(api.post("workplace/todos", input.toJson()), json)

    suspend fun updateTodo(id: Long, input: TodoInput): WorkplaceTodo =
        decodeBusinessObject(api.put("workplace/todos/$id", input.toJson()), json)

    suspend fun deleteTodo(id: Long) {
        api.delete<JsonElement>("workplace/todos/$id")
    }

    suspend fun notes(): List<WorkplaceNote> =
        decodeBusinessList(api.get("workplace/notes"), json)

    suspend fun note(id: Long): WorkplaceNote =
        decodeBusinessObject(api.get("workplace/notes/$id"), json)

    suspend fun createNote(title: String, content: String): WorkplaceNote =
        decodeBusinessObject(api.post("workplace/notes", noteBody(title, content)), json)

    suspend fun updateNote(id: Long, title: String, content: String): WorkplaceNote =
        decodeBusinessObject(api.put("workplace/notes/$id", noteBody(title, content)), json)

    suspend fun deleteNote(id: Long) {
        api.delete<JsonElement>("workplace/notes/$id")
    }

    suspend fun promoteNote(id: Long): Long {
        val payload: JsonElement = api.post("workplace/notes/$id/promote-to-document", buildJsonObject { })
        val data = (payload as? JsonObject)?.get("data") ?: payload
        return (data as? JsonObject)?.get("document_id")?.let { json.decodeFromJsonElement<Long>(it) } ?: 0L
    }

    suspend fun tickets(filter: TicketFilter, keyword: String, page: Int, shops: List<Long> = emptyList(), skuCode: String = "", productCode: String = "", skuId: Long? = null, productId: Long? = null): WorkplaceTicketPage {
        val params = mutableMapOf(
            "page" to page.toString(),
            "page_size" to "20",
            "scope" to "related",
        )
        when (filter) {
            TicketFilter.Active -> params["todo_for_me"] = "true"
            TicketFilter.Closed -> params["status"] = "closed"
            TicketFilter.Urgent -> params["priority"] = "urgent"
            else -> Unit
        }
        keyword.trim().takeIf { it.isNotEmpty() }?.let { params["keyword"] = it }
        if (shops.isNotEmpty()) params["shop_ids"] = shops.joinToString(",")
        if (skuCode.isNotBlank()) params["sku_code"] = skuCode.trim()
        if (productCode.isNotBlank()) params["product_code"] = productCode.trim()
        skuId?.let { params["sku_id"] = it.toString() }
        productId?.let { params["product_id"] = it.toString() }
        val payload: JsonElement = api.get("tickets", params)
        return decodeTicketPage(payload, json)
    }

    suspend fun ticket(id: Long): WorkplaceTicket =
        decodeBusinessObject(api.get("tickets/$id"), json)

    suspend fun updateTicketStatus(id: Long, status: String, remark: String = "") {
        api.post<JsonElement>(
            "tickets/$id/status",
            buildJsonObject {
                put("status", status)
                if (remark.isNotBlank()) put("remark", remark.trim())
            },
        )
    }

    suspend fun documents(filter: DocumentFilter, keyword: String, page: Int): BusinessPage<WorkplaceDocument> {
        val params = mutableMapOf("page" to page.toString(), "pageSize" to "20")
        filter.wireValue?.let { params["document_type"] = it }
        keyword.trim().takeIf { it.isNotEmpty() }?.let { params["keyword"] = it }
        return decodeBusinessPage(api.get("documents", params), json)
    }

    suspend fun quickAccessDocuments(): List<WorkplaceDocument> =
        decodeQuickAccessDocuments(api.get("documents/quick-access", mapOf("limit" to "8")), json)

    suspend fun document(id: Long): WorkplaceDocument =
        decodeBusinessObject(api.get("documents/$id"), json)

    private fun noteBody(title: String, content: String) = buildJsonObject {
        put("title", title.trim())
        put("content", tipTapDocument(content))
    }
}

data class TodoInput(
    val title: String,
    val description: String,
    val dueDate: String,
    val dueTime: String = "",
    val priority: String = "medium",
    val status: String = "pending",
) {
    fun toJson() = buildJsonObject {
        put("title", title.trim())
        put("description", description.trim())
        put("due_date", todoDateTime(dueDate))
        if (dueTime.isNotBlank()) put("due_time", dueTime)
        put("priority", priority)
        put("status", if (isTodoCompleted(status)) "completed" else status)
    }
}

internal fun todoDateTime(value: String): String {
    val normalized = value.trim()
    return if (normalized.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) normalized + "T12:00:00Z" else normalized
}

internal fun tipTapDocument(content: String): JsonObject = buildJsonObject {
    put("type", "doc")
    putJsonArray("content") {
        content.lines().forEach { line ->
            add(
                buildJsonObject {
                    put("type", "paragraph")
                    if (line.isNotEmpty()) {
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", line)
                                },
                            )
                        }
                    }
                },
            )
        }
        if (content.isEmpty()) add(buildJsonObject { put("type", "paragraph") })
    }
}

internal fun decodeTicketPage(payload: JsonElement, json: Json): WorkplaceTicketPage {
    val obj = payload as? JsonObject
    val meta = obj?.get("meta") as? JsonObject ?: obj
    return WorkplaceTicketPage(
        data = decodeBusinessList(payload, json),
        meta = WorkplaceTicketPageMeta(
            total = meta?.get("total")?.jsonPrimitive?.intOrNull ?: 0,
            page = meta?.get("page")?.jsonPrimitive?.intOrNull ?: 1,
            pageSize = (meta?.get("page_size") ?: meta?.get("pageSize"))?.jsonPrimitive?.intOrNull ?: 20,
            hasMore = meta?.get("has_more")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: (
                    (
                        (meta?.get("page")?.jsonPrimitive?.intOrNull ?: 1) *
                            ((meta?.get("page_size") ?: meta?.get("pageSize"))?.jsonPrimitive?.intOrNull ?: 20)
                        ) <
                        (meta?.get("total")?.jsonPrimitive?.intOrNull ?: 0)
                    ),
        ),
    )
}

internal fun decodeQuickAccessDocuments(payload: JsonElement, json: Json): List<WorkplaceDocument> {
    val root = (payload as? JsonObject)?.get("data") ?: payload
    val obj = root as? JsonObject
    if (obj == null) return decodeBusinessList(root, json)
    val recent = obj["recent"]?.let { decodeBusinessList<WorkplaceDocument>(it, json) }.orEmpty()
    val favorites = obj["favorites"]?.let { decodeBusinessList<WorkplaceDocument>(it, json) }.orEmpty()
    return (recent + favorites).distinctBy { it.id }
}
