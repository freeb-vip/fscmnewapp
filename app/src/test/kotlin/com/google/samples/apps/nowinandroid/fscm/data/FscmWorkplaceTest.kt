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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FscmWorkplaceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun todoInput_normalizesCompletionAndDate() {
        val body = TodoInput(
            title = "盘点库存",
            description = "确认差异",
            dueDate = "2026-07-15",
            status = "done",
        ).toJson()

        assertEquals("completed", body["status"]?.jsonPrimitive?.content)
        assertEquals("2026-07-15T12:00:00Z", body["due_date"]?.jsonPrimitive?.content)
        assertEquals("2026-07-15T12:00:00Z", todoDateTime("2026-07-15"))
        assertEquals("2026-07-15T08:30:00Z", todoDateTime("2026-07-15T08:30:00Z"))
    }

    @Test
    fun tipTapDocument_preservesPlainTextLines() {
        val document = tipTapDocument("第一行\n第二行")

        assertEquals("doc", document["type"]?.jsonPrimitive?.content)
        assertEquals("第一行第二行", plainRichText(document))
        assertEquals(2, richTextBlocks(document).size)
    }

    @Test
    fun richTextBlocks_parseSupportedNodesAndFallbackText() {
        val content = json.parseToJsonElement(
            """{"type":"doc","content":[
                {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"标题"}]},
                {"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"项目一"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"项目二"}]}]}
                ]},
                {"type":"table","content":[{"type":"tableRow","content":[
                    {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"单元格"}]}]}
                ]}]}
            ]}""",
        )

        val blocks = richTextBlocks(content)

        assertEquals(RichTextBlockType.Heading, blocks[0].type)
        assertEquals("标题", blocks[0].text)
        assertEquals(listOf("项目一", "项目二"), blocks[1].items)
        assertEquals(listOf(listOf("单元格")), blocks[2].rows)
    }

    @Test
    fun ticketTransitions_matchBackendStateMachine() {
        assertEquals(listOf("in_progress", "resolved"), allowedTicketStatuses("open"))
        assertEquals(listOf("pending", "resolved"), allowedTicketStatuses("in_progress"))
        assertEquals(listOf("in_progress", "resolved"), allowedTicketStatuses("pending"))
        assertEquals(listOf("closed"), allowedTicketStatuses("resolved"))
        assertEquals(listOf("open"), allowedTicketStatuses("closed"))
        assertTrue(isTicketClosed("resolved"))
        assertTrue(isTicketClosed("closed"))
        assertFalse(isTicketClosed("in_progress"))
    }

    @Test
    fun ticketAndQuickAccessResponses_decodeCommonPayloads() {
        val ticketPayload = json.parseToJsonElement(
            """{"data":[{"id":7,"ticket_no":"TK-7","title":"跟进样品"}],"meta":{"total":22,"page":2,"page_size":10,"has_more":true}}""",
        )
        val quickAccessPayload = json.parseToJsonElement(
            """{"data":{"recent":[{"id":1,"title":"最近文档"}],"favorites":[{"id":1,"title":"最近文档"},{"id":2,"title":"收藏文档"}]}}""",
        )

        val page = decodeTicketPage(ticketPayload, json)
        val quickAccess = decodeQuickAccessDocuments(quickAccessPayload, json)

        assertEquals(7L, page.data.single().id)
        assertEquals(22, page.meta.total)
        assertEquals(2, page.meta.page)
        assertTrue(page.meta.hasMore)
        assertEquals(listOf(1L, 2L), quickAccess.map { it.id })
    }

    @Test
    fun quickAccess_acceptsDirectDataArray() {
        val payload = json.parseToJsonElement("""[{"id":3,"title":"直接文档"}]""")

        assertEquals(3L, decodeQuickAccessDocuments(payload, json).single().id)
    }
}
