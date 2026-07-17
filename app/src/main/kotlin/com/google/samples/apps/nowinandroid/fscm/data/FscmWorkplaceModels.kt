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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WorkplaceTodo(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val status: String = "pending",
    val priority: String = "medium",
    @SerialName("due_date") val dueDate: String = "",
    @SerialName("due_time") val dueTime: String = "",
    @SerialName("due_at") val dueAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WorkplaceNote(
    val id: Long = 0,
    val title: String = "",
    val content: JsonElement = JsonNull,
    @SerialName("preview_text") val previewText: String = "",
    @SerialName("last_promoted_document_id") val lastPromotedDocumentId: Long? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WorkplaceUserRef(
    val id: Long = 0,
    val username: String = "",
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
) {
    val displayName: String get() = name.ifBlank { username }
}

@Serializable
data class WorkplaceTicketCategory(
    val id: Long = 0,
    val name: String = "",
    val color: String = "",
)

@Serializable
data class WorkplaceTicketHistory(
    val id: Long = 0,
    @SerialName("from_status") val fromStatus: String = "",
    @SerialName("to_status") val toStatus: String = "",
    val remark: String = "",
    val operator: WorkplaceUserRef? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class WorkplaceTicket(
    val id: Long = 0,
    @SerialName("ticket_no") val ticketNo: String = "",
    val title: String = "",
    val description: String = "",
    val priority: String = "medium",
    val status: String = "open",
    @SerialName("expected_at") val expectedAt: String = "",
    @SerialName("creator_id") val creatorId: Long = 0,
    @SerialName("assignee_id") val assigneeId: Long = 0,
    val category: WorkplaceTicketCategory? = null,
    val creator: WorkplaceUserRef? = null,
    val assignee: WorkplaceUserRef? = null,
    val history: List<WorkplaceTicketHistory> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WorkplaceTicketPageMeta(
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class WorkplaceTicketPage(
    val data: List<WorkplaceTicket> = emptyList(),
    val meta: WorkplaceTicketPageMeta = WorkplaceTicketPageMeta(),
)

@Serializable
data class WorkplaceDocument(
    val id: Long = 0,
    val title: String = "",
    val summary: String = "",
    val content: JsonElement = JsonNull,
    val status: String = "",
    @SerialName("document_type") val documentType: String = "document",
    val visibility: String = "",
    val author: WorkplaceUserRef? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class WorkplaceDocumentPage(
    val data: List<WorkplaceDocument> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20,
)

enum class TodoFilter {
    All,
    Pending,
    Urgent,
    Completed,
}

enum class TicketFilter {
    Active,
    Urgent,
    Closed,
    All,
}

enum class DocumentFilter(val wireValue: String?) {
    All(null),
    Document("document"),
    Mindmap("mindmap"),
}

data class RichTextBlock(
    val type: RichTextBlockType,
    val text: String = "",
    val level: Int = 0,
    val items: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

enum class RichTextBlockType {
    Heading,
    Paragraph,
    Quote,
    Code,
    BulletList,
    OrderedList,
    Table,
}

fun isTodoCompleted(status: String): Boolean = status.lowercase() in setOf("completed", "done", "finished")

fun isTicketClosed(status: String): Boolean = status.lowercase() in setOf("resolved", "closed")

fun allowedTicketStatuses(status: String): List<String> = when (status.lowercase()) {
    "open" -> listOf("in_progress", "resolved")
    "in_progress" -> listOf("pending", "resolved")
    "pending" -> listOf("in_progress", "resolved")
    "resolved" -> listOf("closed")
    "closed" -> listOf("open")
    else -> emptyList()
}

fun richTextBlocks(content: JsonElement): List<RichTextBlock> {
    val blocks = mutableListOf<RichTextBlock>()
    appendRichTextBlocks(content, blocks)
    return blocks.ifEmpty {
        plainRichText(content).takeIf { it.isNotBlank() }?.let { listOf(RichTextBlock(RichTextBlockType.Paragraph, it)) }.orEmpty()
    }
}

fun plainRichText(content: JsonElement): String = when (content) {
    is JsonObject -> {
        val directText = content["text"]?.jsonPrimitive?.contentOrNull
        if (directText != null) directText else content["content"]?.let(::plainRichText).orEmpty()
    }
    is JsonArray -> content.joinToString("") { plainRichText(it) }
    else -> content.jsonPrimitive.contentOrNull.orEmpty()
}

private fun appendRichTextBlocks(element: JsonElement, blocks: MutableList<RichTextBlock>) {
    when (element) {
        is JsonArray -> element.forEach { appendRichTextBlocks(it, blocks) }
        is JsonObject -> {
            when (element["type"]?.jsonPrimitive?.contentOrNull) {
                "doc" -> appendRichTextBlocks(element["content"] ?: JsonNull, blocks)
                "heading" -> blocks += RichTextBlock(
                    type = RichTextBlockType.Heading,
                    text = inlineRichText(element["content"]),
                    level = element["attrs"]?.jsonObjectOrNull()?.get("level")?.jsonPrimitive?.intOrNull ?: 2,
                )
                "paragraph" -> blocks += RichTextBlock(RichTextBlockType.Paragraph, inlineRichText(element["content"]))
                "blockquote" -> blocks += RichTextBlock(RichTextBlockType.Quote, plainRichText(element["content"] ?: JsonNull))
                "codeBlock" -> blocks += RichTextBlock(RichTextBlockType.Code, plainRichText(element["content"] ?: JsonNull))
                "bulletList" -> blocks += RichTextBlock(RichTextBlockType.BulletList, items = listItems(element))
                "orderedList" -> blocks += RichTextBlock(RichTextBlockType.OrderedList, items = listItems(element))
                "table" -> blocks += RichTextBlock(RichTextBlockType.Table, rows = tableRows(element))
                "text", "hardBreak" -> Unit
                else -> element["content"]?.let { appendRichTextBlocks(it, blocks) }
            }
        }
        else -> Unit
    }
}

private fun inlineRichText(content: JsonElement?): String = when (content) {
    is JsonArray -> content.joinToString("") { item ->
        if (item is JsonObject && item["type"]?.jsonPrimitive?.contentOrNull == "hardBreak") "\n" else plainRichText(item)
    }
    null -> ""
    else -> plainRichText(content)
}

private fun listItems(element: JsonObject): List<String> = (element["content"] as? JsonArray).orEmpty().map { item ->
    inlineRichText((item as? JsonObject)?.get("content"))
}.filter { it.isNotBlank() }

private fun tableRows(element: JsonObject): List<List<String>> = (element["content"] as? JsonArray).orEmpty().map { row ->
    ((row as? JsonObject)?.get("content") as? JsonArray).orEmpty().map { cell ->
        plainRichText((cell as? JsonObject)?.get("content") ?: JsonNull)
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
