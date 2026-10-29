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

package com.google.samples.apps.nowinandroid.fscm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.DocumentFilter
import com.google.samples.apps.nowinandroid.fscm.data.RichTextBlock
import com.google.samples.apps.nowinandroid.fscm.data.RichTextBlockType
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.TicketFilter
import com.google.samples.apps.nowinandroid.fscm.data.TodoFilter
import com.google.samples.apps.nowinandroid.fscm.data.TodoInput
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceDocument
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceNote
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceTicket
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceTodo
import com.google.samples.apps.nowinandroid.fscm.data.allowedTicketStatuses
import com.google.samples.apps.nowinandroid.fscm.data.isTodoCompleted
import com.google.samples.apps.nowinandroid.fscm.data.plainRichText
import com.google.samples.apps.nowinandroid.fscm.data.richTextBlocks
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareAlertDialog as AlertDialog

private val WorkplaceBlue = Color(0xFF1677FF)
private val WorkplaceDeepBlue = Color(0xFF0958D9)
private val WorkplaceBackground = Color(0xFFF5F8FF)
private val WorkplaceBorder = Color(0xFFDCE7F5)
private val WorkplaceText = Color(0xFF172033)
private val WorkplaceMuted = Color(0xFF66758A)
private val WorkplaceSuccess = Color(0xFF389E0D)
private val WorkplaceWarning = Color(0xFFD48806)
private val WorkplaceDanger = Color(0xFFCF1322)

@Composable
fun TodosScreen(
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: TodosViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val visible = state.todos.filter { todo ->
        when (state.filter) {
            TodoFilter.All -> true
            TodoFilter.Pending -> !isTodoCompleted(todo.status)
            TodoFilter.Urgent -> !isTodoCompleted(todo.status) && todo.priority.lowercase() in setOf("high", "urgent")
            TodoFilter.Completed -> isTodoCompleted(todo.status)
        }
    }
    WorkplacePage {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workplace_todos), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = WorkplaceText)
                Button(onClick = onCreate) {
                    Icon(Icons.Rounded.Add, null)
                    Text(stringResource(R.string.workplace_new))
                }
            }
        }
        item { WorkplaceNotice(state.error, state.message, viewModel::consumeNotice) }
        item {
            WorkplaceMetrics(
                listOf(
                    stringResource(R.string.status_pending) to state.todos.count { !isTodoCompleted(it.status) }.toString(),
                    stringResource(R.string.status_completed) to state.todos.count { isTodoCompleted(it.status) }.toString(),
                    stringResource(R.string.priority_urgent) to state.todos.count { !isTodoCompleted(it.status) && it.priority.lowercase() in setOf("high", "urgent") }.toString(),
                ),
            )
        }
        item { TodoFilters(state.filter, viewModel::setFilter) }
        if (state.loading && visible.isEmpty()) item { WorkplaceLoading() }
        if (!state.loading && visible.isEmpty()) item { WorkplaceEmpty(stringResource(R.string.workplace_no_todos)) }
        items(visible, key = { it.id }) { todo -> TodoCard(todo, onOpen = { onOpen(todo.id) }, onToggle = { viewModel.toggleComplete(todo) }) }
    }
}

@Composable
fun TodoDetailScreen(
    id: Long?,
    onBack: () -> Unit,
    viewModel: TodoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var title by rememberSaveable(id) { mutableStateOf("") }
    var description by rememberSaveable(id) { mutableStateOf("") }
    var dueDate by rememberSaveable(id) { mutableStateOf(todayIsoDate()) }
    var priority by rememberSaveable(id) { mutableStateOf("medium") }
    var status by rememberSaveable(id) { mutableStateOf("pending") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { id?.let(viewModel::load) }
    LaunchedEffect(state.todo?.id) {
        state.todo?.let { todo ->
            title = todo.title
            description = todo.description
            dueDate = todo.dueDate.take(10).ifBlank { todo.dueAt.take(10).ifBlank { todayIsoDate() } }
            priority = todo.priority.ifBlank { "medium" }
            status = todo.status.ifBlank { "pending" }
        }
    }
    LaunchedEffect(state.message) { if (state.message != null) onBack() }
    WorkplacePage(bottomPadding = 104) {
        item { WorkplaceNotice(state.error, state.message, viewModel::consumeNotice) }
        item {
            WorkplaceCard {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.workplace_todo_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.workplace_description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dueDate, { dueDate = it.take(10) }, label = { Text(stringResource(R.string.workplace_due_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.workplace_priority), fontWeight = FontWeight.SemiBold)
                PrioritySelector(priority, { priority = it })
            }
        }
        if (state.loading) item { WorkplaceLoading() }
    }
    if (!state.loading) {
        TodoActionBar(
            isExisting = id != null,
            submitting = state.submitting,
            isCompleted = isTodoCompleted(status),
            onDelete = { confirmation = "delete" },
            onSave = {
                if (title.isBlank() || dueDate.length != 10) confirmation = "invalid" else confirmation = "save"
            },
            onComplete = { confirmation = if (title.isBlank() || dueDate.length != 10) "invalid" else "complete" },
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(todoDialogTitle(action)) },
            text = { Text(todoDialogText(action)) },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    when (action) {
                        "delete" -> id?.let(viewModel::delete)
                        "save" -> viewModel.save(id, TodoInput(title, description, dueDate, priority = priority, status = status))
                        "complete" -> viewModel.save(id, TodoInput(title, description, dueDate, priority = priority, status = "completed"))
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun NotesScreen(
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    WorkplacePage {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workplace_notes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = WorkplaceText)
                Button(onClick = onCreate) {
                    Icon(Icons.Rounded.Add, null)
                    Text(stringResource(R.string.workplace_new))
                }
            }
        }
        state.error?.let { item { WorkplaceNotice(it, null, null) } }
        if (state.loading && state.notes.isEmpty()) item { WorkplaceLoading() }
        if (!state.loading && state.notes.isEmpty()) item { WorkplaceEmpty(stringResource(R.string.workplace_no_notes)) }
        items(state.notes, key = { it.id }) { note -> NoteCard(note) { onOpen(note.id) } }
    }
}

@Composable
fun NoteDetailScreen(
    id: Long?,
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable(id) { mutableStateOf(id == null) }
    var title by rememberSaveable(id) { mutableStateOf("") }
    var content by rememberSaveable(id) { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { id?.let(viewModel::load) }
    LaunchedEffect(state.note?.id) {
        state.note?.let { note ->
            title = note.title
            content = plainRichText(note.content)
        }
    }
    LaunchedEffect(state.promotedDocumentId) {
        state.promotedDocumentId?.let(onOpenDocument)
    }
    LaunchedEffect(state.message) {
        val resource = (state.message as? UiText.Resource)?.id
        if (resource == R.string.workplace_note_saved || resource == R.string.workplace_note_deleted) onBack()
    }
    WorkplacePage(bottomPadding = 104) {
        item { WorkplaceNotice(state.error, state.message, viewModel::consumeNotice) }
        if (editing) {
            item {
                WorkplaceCard {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.workplace_note_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.workplace_content)) }, minLines = 8, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            item {
                WorkplaceCard {
                    Text(title.ifBlank { stringResource(R.string.workplace_notes) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    RichTextContent(state.note?.content)
                }
            }
        }
        if (state.loading) item { WorkplaceLoading() }
    }
    NoteActionBar(
        isExisting = id != null,
        editing = editing,
        submitting = state.submitting,
        onEdit = { editing = true },
        onDelete = { confirmation = "delete" },
        onPromote = { confirmation = "promote" },
        onSave = { confirmation = if (title.isBlank()) "invalid" else "save" },
    )
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(noteDialogTitle(action)) },
            text = { Text(noteDialogText(action)) },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    when (action) {
                        "delete" -> id?.let(viewModel::delete)
                        "promote" -> id?.let(viewModel::promote)
                        "save" -> viewModel.save(id, title, content)
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun TicketsScreen(
    onOpen: (Long) -> Unit,
    viewModel: TicketsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load(true) }
    WorkplacePage {
        item { WorkplaceSearch(state.query, stringResource(R.string.workplace_search_tickets), viewModel::setQuery) { viewModel.load(true) } }
        state.error?.let { item { WorkplaceNotice(it, null, null) } }
        item { TicketFilters(state.filter, viewModel::setFilter) }
        if (state.loading && state.tickets.isEmpty()) item { WorkplaceLoading() }
        if (!state.loading && state.tickets.isEmpty()) item { WorkplaceEmpty(stringResource(R.string.workplace_no_tickets)) }
        items(state.tickets, key = { it.id }) { ticket -> TicketCard(ticket) { onOpen(ticket.id) } }
        if (state.hasMore) item { OutlinedButton(onClick = { viewModel.load(false) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_load_more)) } }
    }
}

@Composable
fun TicketDetailScreen(
    id: Long,
    session: SessionState,
    viewModel: TicketDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ticket = state.ticket
    var historyExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var targetStatus by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { viewModel.load(id) }
    val isCreator = ticket?.creatorId == session.user?.id
    val isAssignee = ticket?.assigneeId == session.user?.id
    WorkplacePage(bottomPadding = 104) {
        item { WorkplaceNotice(state.error, state.message, viewModel::consumeNotice) }
        ticket?.let { item ->
            item {
                WorkplaceCard(containerColor = Color(0xFFE6F4FF)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.ticketNo.ifBlank { "TK-${item.id}" }, fontWeight = FontWeight.Bold, color = WorkplaceText)
                        WorkplacePill(ticketStatusText(item.status), ticketStatusColor(item.status))
                    }
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${item.category?.name.orEmpty().ifBlank { stringResource(R.string.workplace_general) }} · ${priorityText(item.priority)}", color = WorkplaceMuted)
                    Text(stringResource(R.string.workplace_assignee, item.assignee?.displayName.orEmpty().ifBlank { stringResource(R.string.workplace_unassigned) }), color = WorkplaceMuted)
                    Text(stringResource(R.string.workplace_expected_completion, item.expectedAt.workplaceDate()), color = WorkplaceMuted)
                }
            }
            item {
                WorkplaceCard(containerColor = Color(0xFFFFF7E6)) {
                    Text(stringResource(R.string.workplace_ticket_description), fontWeight = FontWeight.SemiBold)
                    Text(item.description.ifBlank { stringResource(R.string.workplace_no_ticket_description) })
                }
            }
            item { TicketConversation(id, session.user?.id, item.status) }
            if (item.history.isNotEmpty()) {
                item {
                    TextButton(onClick = { historyExpanded = !historyExpanded }) {
                        Text(
                            "状态历史 · ${item.history.size} 条  ${if (historyExpanded) "收起" else "展开"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WorkplaceMuted,
                        )
                    }
                }
                if (historyExpanded) {
                    items(item.history, key = { history -> "history-${history.id}" }) { history ->
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(
                                "${ticketStatusText(history.fromStatus)} → ${ticketStatusText(history.toStatus)} · ${history.operator?.displayName.orEmpty().ifBlank { stringResource(R.string.business_system) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = WorkplaceMuted,
                            )
                            Text(history.createdAt.workplaceDate(), style = MaterialTheme.typography.labelSmall, color = WorkplaceMuted)
                            if (history.remark.isNotBlank()) Text(history.remark, style = MaterialTheme.typography.bodySmall, color = WorkplaceMuted)
                        }
                    }
                }
            }
        }
        if (state.loading) item { WorkplaceLoading() }
    }
    ticket?.let { item ->
        TicketActionBar(
            canChangeStatus = allowedTicketStatuses(item.status).any { target -> ticketStatusAllowedForUser(item.status, target, isCreator, isAssignee) },
            targets = allowedTicketStatuses(item.status).filter { target -> ticketStatusAllowedForUser(item.status, target, isCreator, isAssignee) },
            submitting = state.submitting,
            onStatus = { targetStatus = it },
        )
    }
    targetStatus?.let { status ->
        AlertDialog(
            onDismissRequest = { targetStatus = null },
            title = { Text(stringResource(R.string.workplace_confirm_ticket_status, ticketStatusText(status))) },
            text = { Text(stringResource(R.string.workplace_status_permission_note)) },
            confirmButton = {
                Button(onClick = {
                    targetStatus = null
                    viewModel.updateStatus(id, status)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { targetStatus = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun DocumentsScreen(
    onOpen: (Long) -> Unit,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load(true) }
    WorkplacePage {
        item { WorkplaceSearch(state.query, stringResource(R.string.workplace_search_documents), viewModel::setQuery) { viewModel.load(true) } }
        state.error?.let { item { WorkplaceNotice(it, null, null) } }
        item { DocumentFilters(state.filter, viewModel::setFilter) }
        if (state.quickAccess.isNotEmpty() && state.filter == DocumentFilter.All && state.query.isBlank()) {
            item { WorkplaceSectionTitle(stringResource(R.string.workplace_quick_access)) }
            items(state.quickAccess, key = { "quick-${it.id}" }) { document -> DocumentCard(document, compact = true) { onOpen(document.id) } }
            item { WorkplaceSectionTitle(stringResource(R.string.workplace_recent_updates)) }
        }
        if (state.loading && state.documents.isEmpty()) item { WorkplaceLoading() }
        if (!state.loading && state.documents.isEmpty()) item { WorkplaceEmpty(stringResource(R.string.workplace_no_documents)) }
        items(state.documents, key = { it.id }) { document -> DocumentCard(document) { onOpen(document.id) } }
        if (state.hasMore) item { OutlinedButton(onClick = { viewModel.load(false) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_load_more)) } }
    }
}

@Composable
fun DocumentDetailScreen(
    id: Long,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { viewModel.load(id) }
    WorkplacePage {
        state.error?.let { item { WorkplaceNotice(it, null, null) } }
        state.document?.let { document ->
            item {
                WorkplaceCard {
                    Text(document.title.ifBlank { stringResource(R.string.workplace_document) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = WorkplaceText)
                    Text("${document.author?.displayName.orEmpty().ifBlank { stringResource(R.string.workplace_unknown_author) }} · ${document.updatedAt.workplaceDate()}", color = WorkplaceMuted)
                    if (document.summary.isNotBlank()) Text(document.summary, color = WorkplaceMuted)
                }
            }
            item { WorkplaceCard { RichTextContent(document.content) } }
        }
        if (state.loading) item { WorkplaceLoading() }
    }
}

@Composable
private fun WorkplacePage(bottomPadding: Int = 24, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(WorkplaceBackground),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, bottomPadding.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun WorkplaceCard(onClick: (() -> Unit)? = null, containerColor: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, WorkplaceBorder),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content) }
}

@Composable
private fun TodoCard(todo: WorkplaceTodo, onOpen: () -> Unit, onToggle: () -> Unit) = WorkplaceCard(onOpen) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(todo.title, fontWeight = FontWeight.Bold, color = WorkplaceText)
            if (todo.description.isNotBlank()) Text(todo.description, color = WorkplaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        WorkplacePill(stringResource(if (isTodoCompleted(todo.status)) R.string.status_completed else R.string.status_pending), if (isTodoCompleted(todo.status)) WorkplaceSuccess else WorkplaceBlue)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${priorityText(todo.priority)} · ${todo.dueDate.workplaceDate()}", color = WorkplaceMuted, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onToggle) { Icon(if (isTodoCompleted(todo.status)) Icons.Rounded.Refresh else Icons.Rounded.Check, contentDescription = stringResource(R.string.workplace_update_todo), tint = WorkplaceBlue) }
    }
}

@Composable
private fun NoteCard(note: WorkplaceNote, onOpen: () -> Unit) = WorkplaceCard(onOpen) {
    Text(note.title.ifBlank { stringResource(R.string.workplace_unnamed_note) }, fontWeight = FontWeight.Bold, color = WorkplaceText)
    Text(note.previewText.ifBlank { plainRichText(note.content) }.ifBlank { stringResource(R.string.workplace_no_content) }, color = WorkplaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    Text(note.updatedAt.workplaceDate(), color = WorkplaceMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TicketCard(ticket: WorkplaceTicket, onOpen: () -> Unit) = WorkplaceCard(onOpen) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(ticket.title.ifBlank { stringResource(R.string.workplace_unnamed_ticket) }, fontWeight = FontWeight.Bold, color = WorkplaceText, modifier = Modifier.weight(1f))
        WorkplacePill(ticketStatusText(ticket.status), ticketStatusColor(ticket.status))
    }
    Text(ticket.description.ifBlank { stringResource(R.string.workplace_no_ticket_description) }, color = WorkplaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("${ticket.ticketNo} · ${priorityText(ticket.priority)} · ${ticket.expectedAt.workplaceDate()}", color = WorkplaceMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DocumentCard(document: WorkplaceDocument, compact: Boolean = false, onOpen: () -> Unit) = WorkplaceCard(onOpen) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(document.title.ifBlank { stringResource(R.string.workplace_unnamed_document) }, fontWeight = FontWeight.Bold, color = WorkplaceText, modifier = Modifier.weight(1f))
        WorkplacePill(stringResource(if (document.documentType == "mindmap") R.string.workplace_mindmap else R.string.workplace_document), WorkplaceBlue)
    }
    if (!compact) Text(document.summary.ifBlank { plainRichText(document.content) }.ifBlank { stringResource(R.string.workplace_no_summary) }, color = WorkplaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("${document.author?.displayName.orEmpty().ifBlank { stringResource(R.string.workplace_unknown_author) }} · ${document.updatedAt.workplaceDate()}", color = WorkplaceMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RichTextContent(content: kotlinx.serialization.json.JsonElement?) {
    val blocks = content?.let(::richTextBlocks).orEmpty()
    if (blocks.isEmpty()) {
        Text(stringResource(R.string.workplace_no_content), color = WorkplaceMuted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> RichTextBlockContent(block) }
    }
}

@Composable
private fun RichTextBlockContent(block: RichTextBlock) = when (block.type) {
    RichTextBlockType.Heading -> Text(block.text, style = if (block.level <= 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = WorkplaceText)
    RichTextBlockType.Paragraph -> Text(block.text, color = WorkplaceText)
    RichTextBlockType.Quote -> Text(block.text, color = WorkplaceMuted, modifier = Modifier.background(WorkplaceBlue.copy(alpha = 0.06f), RoundedCornerShape(6.dp)).padding(10.dp))
    RichTextBlockType.Code -> Text(block.text, color = WorkplaceText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp)).padding(10.dp))
    RichTextBlockType.BulletList, RichTextBlockType.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item -> Text(if (block.type == RichTextBlockType.OrderedList) "${index + 1}. $item" else "• $item", color = WorkplaceText) }
    }
    RichTextBlockType.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
        block.rows.forEach { row -> Row { row.forEach { cell -> Text(cell.ifBlank { "-" }, modifier = Modifier.width(120.dp).padding(8.dp), color = WorkplaceText) } } }
    }
}

@Composable
private fun TodoFilters(selected: TodoFilter, onSelected: (TodoFilter) -> Unit) = FilterRow(
    selected = selected,
    values = TodoFilter.entries.toList(),
    label = { filter ->
        when (filter) {
            TodoFilter.All -> stringResource(R.string.common_all)
            TodoFilter.Pending -> stringResource(R.string.status_pending)
            TodoFilter.Urgent -> stringResource(R.string.priority_urgent)
            TodoFilter.Completed -> stringResource(R.string.status_completed)
        }
    },
    onSelected = onSelected,
)

@Composable
private fun TicketFilters(selected: TicketFilter, onSelected: (TicketFilter) -> Unit) = FilterRow(
    selected = selected,
    values = listOf(TicketFilter.Active, TicketFilter.All),
    label = { filter ->
        when (filter) {
            TicketFilter.Active -> stringResource(R.string.status_pending)
            TicketFilter.Urgent -> stringResource(R.string.priority_urgent)
            TicketFilter.Closed -> stringResource(R.string.status_closed)
            TicketFilter.All -> stringResource(R.string.common_all)
        }
    },
    onSelected = onSelected,
)

@Composable
private fun DocumentFilters(selected: DocumentFilter, onSelected: (DocumentFilter) -> Unit) = FilterRow(
    selected = selected,
    values = DocumentFilter.entries.toList(),
    label = { filter ->
        when (filter) {
            DocumentFilter.All -> stringResource(R.string.common_all)
            DocumentFilter.Document -> stringResource(R.string.workplace_document)
            DocumentFilter.Mindmap -> stringResource(R.string.workplace_mindmap)
        }
    },
    onSelected = onSelected,
)

@Composable
private fun <T> FilterRow(selected: T, values: List<T>, label: @Composable (T) -> String, onSelected: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected == value, { onSelected(value) }, label = { Text(label(value)) }) }
    }
}

@Composable
private fun PrioritySelector(selected: String, onSelected: (String) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf("low", "medium", "high").forEach { value -> FilterChip(selected == value, { onSelected(value) }, label = { Text(localizedPriorityText(value)) }) }
}

@Composable
private fun WorkplaceMetrics(metrics: List<Pair<String, String>>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    metrics.forEach { (label, value) ->
        Card(Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(Color.White), border = BorderStroke(1.dp, WorkplaceBorder)) {
            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = WorkplaceDeepBlue, fontWeight = FontWeight.Bold)
                Text(label, color = WorkplaceMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WorkplaceSearch(value: String, placeholder: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(value, onValueChange, placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Refresh, stringResource(R.string.common_search), tint = WorkplaceBlue) }
}

@Composable
private fun WorkplaceNotice(error: UiText?, message: UiText?, onDismiss: (() -> Unit)?) {
    val text = error ?: message ?: return
    val color = if (error == null) WorkplaceSuccess else WorkplaceDanger
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.resolve(), color = color, modifier = Modifier.weight(1f))
        if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Delete, stringResource(R.string.common_close), tint = color) }
    }
}

@Composable
private fun WorkplacePill(text: String, color: Color) = Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))

@Composable
private fun WorkplaceSectionTitle(text: String) = Text(text, color = WorkplaceText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun WorkplaceLoading() = Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun WorkplaceEmpty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = WorkplaceMuted) }

@Composable
private fun TodoActionBar(isExisting: Boolean, submitting: Boolean, isCompleted: Boolean, onDelete: () -> Unit, onSave: () -> Unit, onComplete: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isExisting) OutlinedButton(onClick = onDelete, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_delete), color = WorkplaceDanger) }
            OutlinedButton(onClick = onSave, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
            if (isExisting) Button(onClick = onComplete, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(if (isCompleted) R.string.workplace_reopen else R.string.workplace_complete)) }
        }
    }
}

@Composable
private fun NoteActionBar(isExisting: Boolean, editing: Boolean, submitting: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onPromote: () -> Unit, onSave: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (!editing) {
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Icon(Icons.Rounded.Edit, null)
                Text(stringResource(R.string.common_edit))
            }
        } else {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isExisting) OutlinedButton(onClick = onDelete, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_delete), color = WorkplaceDanger) }
                if (isExisting) OutlinedButton(onClick = onPromote, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workplace_promote_document)) }
                Button(onClick = onSave, enabled = !submitting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

@Composable
private fun TicketActionBar(canChangeStatus: Boolean, targets: List<String>, submitting: Boolean, onStatus: (String) -> Unit) {
    if (!canChangeStatus) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canChangeStatus) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { status -> Button(onClick = { onStatus(status) }, enabled = !submitting) { Text(ticketStatusText(status)) } }
                }
            }
        }
    }
}

private fun ticketStatusAllowedForUser(from: String, target: String, isCreator: Boolean, isAssignee: Boolean): Boolean = when {
    from == "resolved" && target == "closed" -> isCreator
    from == "closed" && target == "open" -> isCreator
    else -> isAssignee
}

@Composable
private fun ticketStatusText(status: String): String = localizedStatusText(
    if (status.equals("pending", ignoreCase = true)) "on_hold" else status,
)

private fun ticketStatusColor(status: String): Color = when (status.lowercase()) {
    "resolved", "closed" -> WorkplaceSuccess
    "pending" -> WorkplaceWarning
    else -> WorkplaceBlue
}

@Composable
private fun priorityText(priority: String): String = stringResource(
    when (priority.lowercase()) {
        "urgent", "high", "p0", "p1" -> R.string.workplace_priority_high
        "low", "p3" -> R.string.workplace_priority_low
        else -> R.string.priority_normal
    },
)

@Composable
private fun String.workplaceDate(): String = takeIf { it.isNotBlank() }?.take(16)?.replace('T', ' ')
    ?: stringResource(R.string.workplace_no_time)

private fun todayIsoDate(): String = java.time.LocalDate.now().toString()

@Composable
private fun todoDialogTitle(action: String): String = stringResource(
    when (action) {
        "delete" -> R.string.workplace_delete_todo
        "complete" -> R.string.workplace_complete_todo
        "invalid" -> R.string.workplace_fix_todo
        else -> R.string.workplace_save_todo
    },
)

@Composable
private fun todoDialogText(action: String): String = stringResource(
    when (action) {
        "delete" -> R.string.workplace_delete_irreversible
        "complete" -> R.string.workplace_confirm_complete_todo
        "invalid" -> R.string.workplace_invalid_todo
        else -> R.string.workplace_confirm_save_changes
    },
)

@Composable
private fun noteDialogTitle(action: String): String = stringResource(
    when (action) {
        "delete" -> R.string.workplace_delete_note
        "promote" -> R.string.workplace_promote_note
        "invalid" -> R.string.workplace_enter_note_title
        else -> R.string.workplace_save_note
    },
)

@Composable
private fun noteDialogText(action: String): String = stringResource(
    when (action) {
        "delete" -> R.string.workplace_delete_irreversible
        "promote" -> R.string.workplace_promote_note_text
        "invalid" -> R.string.workplace_note_title_required
        else -> R.string.workplace_confirm_save_content
    },
)
