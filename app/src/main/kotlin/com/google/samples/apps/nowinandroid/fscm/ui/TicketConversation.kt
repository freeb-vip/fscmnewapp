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

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.fscm.data.FscmApiClient
import com.google.samples.apps.nowinandroid.fscm.data.WorkplaceUserRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@Serializable
data class TicketCommentFile(
    val id: Long = 0,
    @SerialName("file_name") val name: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    val url: String = "",
)

@Serializable
data class TicketConversationComment(
    val id: Long = 0,
    val content: String = "",
    val user: WorkplaceUserRef? = null,
    @SerialName("created_at") val createdAt: String = "",
    val attachments: List<TicketCommentFile> = emptyList(),
)

@Serializable
data class MobileTicketCollaborator(
    @SerialName("user_id") val userId: Long = 0,
    val user: WorkplaceUserRef? = null,
    val assignment: String = "",
    val status: String = "pending",
    val version: Int = 1,
)

@Serializable
data class MobileTicketParticipants(
    val collaborators: List<MobileTicketCollaborator> = emptyList(),
)

data class TicketConversationState(
    val comments: List<TicketConversationComment> = emptyList(),
    val files: List<TicketCommentFile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val sent: Int = 0,
    val collaborators: List<MobileTicketCollaborator> = emptyList(),
)

@HiltViewModel
class TicketConversationViewModel @Inject constructor(
    private val api: FscmApiClient,
    private val application: Application,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TicketConversationState())
    val state = mutableState.asStateFlow()
    private var currentTicketId: Long? = null

    fun load(id: Long) = viewModelScope.launch {
        if (currentTicketId != id) {
            currentTicketId = id
            mutableState.value = TicketConversationState()
        }
        runCatching {
            val participants = api.get<MobileTicketParticipants>("tickets/$id")
            mutableState.value = mutableState.value.copy(collaborators = participants.collaborators)
            api.get<List<TicketConversationComment>>("tickets/$id/comments")
        }
            .onSuccess { mutableState.value = mutableState.value.copy(comments = it, error = null) }
            .onFailure { mutableState.value = mutableState.value.copy(error = it.message ?: "评论加载失败") }
    }

    fun upload(uri: Uri) {
        if (mutableState.value.busy || mutableState.value.files.size >= 10) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                val part = withContext(Dispatchers.IO) {
                    val resolver = application.contentResolver
                    val type = resolver.getType(uri) ?: error("无法识别图片类型")
                    require(type in listOf("image/jpeg", "image/png", "image/gif", "image/webp")) { "请选择 JPG、PNG、GIF 或 WebP 图片" }
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 20 * 1024 * 1024) { "单张图片不能超过20MiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取图片")
                    val extension = when (type) {
                        "image/jpeg" -> "jpg"
                        "image/png" -> "png"
                        "image/gif" -> "gif"
                        else -> "webp"
                    }
                    MultipartBody.Part.createFormData("file", "photo.$extension", bytes.toRequestBody(type.toMediaType()))
                }
                api.upload<TicketCommentFile>("tickets/attachments", part)
            }.onSuccess { file -> mutableState.value = mutableState.value.copy(busy = false, files = mutableState.value.files + file) }
                .onFailure { mutableState.value = mutableState.value.copy(busy = false, error = it.message ?: "图片上传失败，请重试") }
        }
    }

    fun remove(file: TicketCommentFile) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(files = mutableState.value.files.filterNot { it.id == file.id })
    }

    fun completeCollaboration(id: Long, collaborator: MobileTicketCollaborator, text: String) {
        val current = mutableState.value
        if (current.busy) return
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                api.post<JsonElement>(
                    "tickets/$id/collaborators/${collaborator.userId}/complete",
                    buildJsonObject {
                        put("version", collaborator.version)
                        put("content", text.trim())
                        putJsonArray("attachment_ids") { current.files.forEach { add(it.id) } }
                    },
                )
            }.onSuccess {
                mutableState.value = mutableState.value.copy(busy = false, files = emptyList(), sent = current.sent + 1)
                load(id)
            }.onFailure { mutableState.value = mutableState.value.copy(busy = false, error = it.message ?: "完成失败，请刷新后重试") }
        }
    }

    fun send(id: Long, text: String) {
        val current = mutableState.value
        if (current.busy || (text.isBlank() && current.files.isEmpty())) return
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                api.post<TicketConversationComment>(
                    "tickets/$id/comments",
                    buildJsonObject {
                        put("content", text.trim())
                        putJsonArray("attachment_ids") { current.files.forEach { add(it.id) } }
                    },
                )
            }.onSuccess { comment ->
                mutableState.value = mutableState.value.copy(busy = false, files = emptyList(), sent = current.sent + 1, comments = current.comments + comment)
            }.onFailure { mutableState.value = mutableState.value.copy(busy = false, error = it.message ?: "发送失败，内容已保留") }
        }
    }
}

@Composable
fun TicketConversation(id: Long, userId: Long?, ticketStatus: String, viewModel: TicketConversationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable(id) { mutableStateOf("") }
    var observedSent by remember(id) { mutableIntStateOf(state.sent) }
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::upload) }
    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(state.sent) {
        if (state.sent != observedSent) {
            text = ""
            observedSent = state.sent
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.collaborators.isNotEmpty()) {
            Text("协作人员", style = MaterialTheme.typography.titleMedium)
            state.collaborators.filter { it.status != "removed" }.forEach { collaborator ->
                Text(
                    "${collaborator.user?.displayName ?: "成员"} · ${when (collaborator.status) {
                        "completed" -> "已完成"
                        "terminated" -> "已终止"
                        else -> "待完成"
                    }}",
                )
                if (collaborator.assignment.isNotBlank()) Text(collaborator.assignment)
            }
        }
        Text("交流", style = MaterialTheme.typography.titleMedium)
        state.comments.asReversed().forEach { comment ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(comment.user?.displayName ?: "工单成员", style = MaterialTheme.typography.labelLarge)
                    if (comment.content.isNotBlank()) Text(comment.content)
                    comment.attachments.forEach { file ->
                        if (file.mimeType.startsWith("image/")) {
                            AsyncImage(model = file.url, contentDescription = file.name, modifier = Modifier.fillMaxWidth().height(180.dp).clickable { if (file.url.isNotBlank()) uriHandler.openUri(file.url) })
                        } else {
                            TextButton(onClick = { if (file.url.isNotBlank()) uriHandler.openUri(file.url) }) { Text(file.name) }
                        }
                    }
                    Text(comment.createdAt.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("说点处理进展…") }, minLines = 2, enabled = !state.busy)
        state.files.forEach { file ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(file.name)
                TextButton(onClick = { viewModel.remove(file) }, enabled = !state.busy) { Text("移除") }
            }
        }
        state.collaborators.firstOrNull { it.userId == userId && it.status == "pending" }?.let { collaborator ->
            if (ticketStatus != "resolved" && ticketStatus != "closed") {
                Button(onClick = { viewModel.completeCollaboration(id, collaborator, text) }, enabled = !state.busy) { Text("提交并完成我的协作") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !state.busy && state.files.size < 10) { Text("添加图片") }
            Button(onClick = { viewModel.send(id, text) }, enabled = !state.busy && (text.isNotBlank() || state.files.isNotEmpty())) { Text(if (state.busy) "正在处理…" else "发送") }
        }
    }
}
