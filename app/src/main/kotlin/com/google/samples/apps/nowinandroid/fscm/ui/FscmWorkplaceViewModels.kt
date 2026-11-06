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

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.DocumentFilter
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiException
import com.google.samples.apps.nowinandroid.fscm.data.TicketFilter
import com.google.samples.apps.nowinandroid.fscm.data.TodoFilter
import com.google.samples.apps.nowinandroid.fscm.data.TodoInput
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceDocument
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceNote
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceRepository
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceTicket
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceTodo
import com.google.samples.apps.nowinandroid.fscm.data.isTicketClosed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodosUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val filter: TodoFilter = TodoFilter.All,
    val todos: List<WorkplaceTodo> = emptyList(),
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class TodosViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodosUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.todos() }
            .onSuccess { rows -> _state.update { it.copy(loading = false, todos = rows) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_todos_load_failed)) } }
    }

    fun setFilter(filter: TodoFilter) = _state.update { it.copy(filter = filter) }

    fun toggleComplete(todo: WorkplaceTodo) {
        val input = todo.toInput(status = if (todo.status.isTodoComplete()) "pending" else "completed")
        submit(R.string.workplace_todo_updated) { repository.updateTodo(todo.id, input) }
    }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }

    private fun submit(@StringRes message: Int, action: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.submitting) return@launch
        _state.update { it.copy(submitting = true, error = null) }
        runCatching { action() }
            .onSuccess {
                _state.update { it.copy(submitting = false, message = UiText.Resource(message)) }
                load()
            }
            .onFailure { error -> _state.update { it.copy(submitting = false, error = error.workplaceMessage(R.string.workplace_todo_action_failed)) } }
    }
}

data class TodoDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val todo: WorkplaceTodo? = null,
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class TodoDetailViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodoDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.todos().firstOrNull { todo -> todo.id == id } }
            .onSuccess { todo -> _state.update { it.copy(loading = false, todo = todo, error = if (todo == null) UiText.Resource(R.string.workplace_todo_not_found) else null) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_todo_detail_failed)) } }
    }

    fun save(id: Long?, input: TodoInput) = execute(id, R.string.workplace_todo_saved) {
        if (id == null) repository.createTodo(input) else repository.updateTodo(id, input)
    }

    fun delete(id: Long) = execute(id, R.string.workplace_todo_deleted) { repository.deleteTodo(id) }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }

    private fun execute(id: Long?, @StringRes message: Int, action: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.submitting) return@launch
        _state.update { it.copy(submitting = true, error = null) }
        runCatching { action() }
            .onSuccess {
                _state.update { it.copy(submitting = false, message = UiText.Resource(message)) }
                id?.let(::load)
            }
            .onFailure { error -> _state.update { it.copy(submitting = false, error = error.workplaceMessage(R.string.workplace_todo_action_failed)) } }
    }
}

data class NotesUiState(
    val loading: Boolean = false,
    val notes: List<WorkplaceNote> = emptyList(),
    val error: UiText? = null,
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.notes() }
            .onSuccess { rows -> _state.update { it.copy(loading = false, notes = rows) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_notes_load_failed)) } }
    }
}

data class NoteDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val note: WorkplaceNote? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    val promotedDocumentId: Long? = null,
)

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NoteDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.note(id) }
            .onSuccess { note -> _state.update { it.copy(loading = false, note = note) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_note_detail_failed)) } }
    }

    fun save(id: Long?, title: String, content: String) = execute(id, R.string.workplace_note_saved) {
        if (id == null) repository.createNote(title, content) else repository.updateNote(id, title, content)
    }

    fun delete(id: Long) = execute(id, R.string.workplace_note_deleted) { repository.deleteNote(id) }

    fun promote(id: Long) = viewModelScope.launch {
        if (_state.value.submitting) return@launch
        _state.update { it.copy(submitting = true, error = null, promotedDocumentId = null) }
        runCatching { repository.promoteNote(id) }
            .onSuccess { documentId -> _state.update { it.copy(submitting = false, message = UiText.Resource(R.string.workplace_note_promoted), promotedDocumentId = documentId.takeIf { value -> value > 0 }) } }
            .onFailure { error -> _state.update { it.copy(submitting = false, error = error.workplaceMessage(R.string.workplace_note_promote_failed)) } }
    }

    fun consumeNotice() = _state.update { it.copy(error = null, message = null, promotedDocumentId = null) }

    private fun execute(id: Long?, @StringRes message: Int, action: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.submitting) return@launch
        _state.update { it.copy(submitting = true, error = null) }
        runCatching { action() }
            .onSuccess {
                _state.update { it.copy(submitting = false, message = UiText.Resource(message)) }
                id?.let(::load)
            }
            .onFailure { error -> _state.update { it.copy(submitting = false, error = error.workplaceMessage(R.string.workplace_note_action_failed)) } }
    }
}

data class TicketsUiState(
    val scopeSku: com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption? = null,
    val scopeProduct: com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption? = null,
    val scopeShops: List<com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption> = emptyList(),
    val skuCode: String = "",
    val productCode: String = "",
    val loading: Boolean = false,
    val filter: TicketFilter = TicketFilter.Active,
    val query: String = "",
    val tickets: List<WorkplaceTicket> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
class TicketsViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TicketsUiState())
    val state = _state.asStateFlow()

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun setScope(shops: List<com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption>, sku: String, product: String, selectedSku: com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption?, selectedProduct: com.google.samples.apps.nowinandroid.fscm.data.TicketReferenceOption?) {
        _state.update { it.copy(scopeShops = shops, skuCode = sku, productCode = product, scopeSku = selectedSku, scopeProduct = selectedProduct) }
        load(true)
    }

    fun setFilter(filter: TicketFilter) {
        _state.update { it.copy(filter = filter) }
        load(true)
    }

    fun load(reset: Boolean = true) {
        if (_state.value.loading) return
        val current = _state.value
        val page = if (reset) 1 else current.page + 1
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.tickets(current.filter, current.query, page, current.scopeShops.map { it.id }, current.skuCode, current.productCode, current.scopeSku?.id, current.scopeProduct?.id) }
                .onSuccess { result ->
                    val filtered = result.data.filter { ticket ->
                        when (current.filter) {
                            TicketFilter.Active -> !isTicketClosed(ticket.status)
                            TicketFilter.Closed -> ticket.status == "closed"
                            else -> true
                        }
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            tickets = if (reset) filtered else it.tickets + filtered,
                            page = page,
                            hasMore = result.meta.hasMore,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_tickets_load_failed)) } }
        }
    }
}

data class TicketDetailUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val ticket: WorkplaceTicket? = null,
    val error: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class TicketDetailViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TicketDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.ticket(id) }
            .onSuccess { ticket -> _state.update { it.copy(loading = false, ticket = ticket) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_ticket_detail_failed)) } }
    }

    fun updateStatus(id: Long, status: String, remark: String = "") = execute(id, R.string.workplace_ticket_status_updated) { repository.updateTicketStatus(id, status, remark) }
    fun consumeNotice() = _state.update { it.copy(error = null, message = null) }

    private fun execute(id: Long, @StringRes message: Int, action: suspend () -> Unit) = viewModelScope.launch {
        if (_state.value.submitting) return@launch
        _state.update { it.copy(submitting = true, error = null) }
        runCatching { action() }
            .onSuccess {
                _state.update { it.copy(submitting = false, message = UiText.Resource(message)) }
                load(id)
            }
            .onFailure { error -> _state.update { it.copy(submitting = false, error = error.workplaceMessage(R.string.workplace_ticket_action_failed)) } }
    }
}

data class DocumentsUiState(
    val loading: Boolean = false,
    val filter: DocumentFilter = DocumentFilter.All,
    val query: String = "",
    val quickAccess: List<WorkplaceDocument> = emptyList(),
    val documents: List<WorkplaceDocument> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: UiText? = null,
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DocumentsUiState())
    val state = _state.asStateFlow()

    fun setQuery(value: String) = _state.update { it.copy(query = value) }
    fun setFilter(filter: DocumentFilter) {
        _state.update { it.copy(filter = filter) }
        load(true)
    }

    fun load(reset: Boolean = true) {
        if (_state.value.loading) return
        val current = _state.value
        val page = if (reset) 1 else current.page + 1
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val list = repository.documents(current.filter, current.query, page)
                val quick = if (reset && current.filter == DocumentFilter.All && current.query.isBlank()) repository.quickAccessDocuments() else null
                list to quick
            }.onSuccess { (list, quick) ->
                _state.update {
                    val rows = if (reset) list.rows else it.documents + list.rows
                    it.copy(
                        loading = false,
                        quickAccess = quick ?: it.quickAccess,
                        documents = rows,
                        page = page,
                        total = list.total,
                        hasMore = rows.size < list.total,
                    )
                }
            }.onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_documents_load_failed)) } }
        }
    }
}

data class DocumentDetailUiState(
    val loading: Boolean = false,
    val document: WorkplaceDocument? = null,
    val error: UiText? = null,
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val repository: WorkplaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DocumentDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repository.document(id) }
            .onSuccess { document -> _state.update { it.copy(loading = false, document = document) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.workplaceMessage(R.string.workplace_document_detail_failed)) } }
    }
}

private fun WorkplaceTodo.toInput(status: String = this.status) = TodoInput(
    title = title,
    description = description,
    dueDate = dueDate.ifBlank { dueAt.take(10) },
    dueTime = dueTime,
    priority = priority,
    status = status,
)

private fun String.isTodoComplete(): Boolean = lowercase() in setOf("completed", "done", "finished")

private fun Throwable.workplaceMessage(@StringRes fallback: Int): UiText =
    (this as? FscmApiException)?.serverMessage?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
        ?: UiText.Resource(fallback)
