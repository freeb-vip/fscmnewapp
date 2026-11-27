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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner

private fun stageText(stage: String) = when (stage) {
    "picking" -> R.string.fbs_picking
    "packing" -> R.string.fbs_packing
    "pickup" -> R.string.fbs_pickup
    "received" -> R.string.fbs_received
    else -> R.string.fbs_shipped
}

@Composable
fun FbsScreen(id: Long?, scopeKey: String, onOpen: (Long) -> Unit) {
    val vm: FbsViewModel = hiltViewModel(key = "fbs-$scopeKey-$id")
    val state by vm.state.collectAsStateWithLifecycle()
    val detail = state.detail
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var completed by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(1) }
    var code by rememberSaveable(id) { mutableStateOf("") }
    val packages = state.packageDraft
    var pdf by rememberSaveable(id) { mutableStateOf("") }
    var cameraKind by rememberSaveable(id) { mutableStateOf("") }
    var scanning by rememberSaveable(id) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.savePdf(pdf, uri)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && id != null) vm.detail(id)
    }
    fun camera() {
        if (cameraKind == "scan") {
            scanning = true
        } else if (id != null) {
            val file = vm.cameraFile(id, cameraKind)
            photo.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) camera() else vm.denied()
    }
    fun requestCamera(kind: String) {
        cameraKind = kind
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera() else permission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(id, page, completed) {
        if (id == null) vm.list(keyword, page, completed) else vm.detail(id)
    }
    val picking = detail?.order?.stage == "picking"
    DisposableEffect(owner, picking) {
        if (picking) vm.register(owner) { code = it.trim() }
        onDispose { if (picking) vm.unregister() }
    }
    if (scanning) {
        ScanAwareDialog(onDismissRequest = { scanning = false }) {
            BarcodeScanner(onBarcodeDetected = {
                code = it.trim()
                scanning = false
            }, modifier = Modifier.fillMaxSize())
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.message?.let { item { Text(stringResource(it)) } }
        if (id == null) {
            item {
                OutlinedButton({
                    completed = !completed
                    page = 1
                }, enabled = !state.busy) {
                    Text(stringResource(if (completed) R.string.shipping_pending else R.string.shipping_shipped))
                }
                OutlinedTextField(keyword, { keyword = it }, label = { Text(stringResource(R.string.fbs_search)) }, singleLine = true)
                Button(onClick = { if (page == 1) vm.list(keyword, page, completed) else page = 1 }, enabled = !state.busy) { Text(stringResource(R.string.fbs_find)) }
            }
            items(state.orders, key = { it.id }) { order ->
                Card(onClick = { onOpen(order.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(order.shopNumber)
                        Text(order.number)
                        Text(stringResource(stageText(order.stage)))
                    }
                }
            }
            if (!state.busy && state.orders.isEmpty()) item { Text(stringResource(R.string.fbs_empty)) }
            if (page > 1) item { OutlinedButton({ page-- }, enabled = !state.busy) { Text(stringResource(R.string.fbs_prev)) } }
            if (page * 30 < state.total) item { OutlinedButton({ page++ }, enabled = !state.busy) { Text(stringResource(R.string.fbs_next)) } }
        } else if (detail != null) {
            val order = detail.order
            item {
                Text(order.shopNumber)
                Text(order.number)
                Text(stringResource(stageText(order.stage)))
                if (order.pickup.isNotBlank()) Text(order.pickup)
                if (order.packages > 0) Text(stringResource(R.string.fbs_packages) + " · " + order.packages)
            }
            if (picking) {
                items(order.lines.groupBy { it.code }.toList()) { (sku, lines) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            val image = lines.first().image
                            if (image.isNotBlank()) AsyncImage(image, null, Modifier.size(56.dp))
                            Text(sku)
                            Text(stringResource(R.string.fbs_pick_total, lines.sumOf { it.quantity }))
                            if (lines.all { it.picked }) Text(stringResource(R.string.fbs_picked))
                        }
                    }
                }
            } else {
                items(order.lines) { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            if (line.image.isNotBlank()) AsyncImage(line.image, null, Modifier.size(56.dp))
                            Text(line.code)
                            Text(line.warehouseSku)
                            Text(stringResource(R.string.fbs_qty, line.quantity, line.pack, line.units))
                            Text(stringResource(if (line.label) R.string.fbs_label else R.string.fbs_no_label))
                            if (line.picked) Text(stringResource(R.string.fbs_picked))
                        }
                    }
                }
            }
            if (picking) {
                items(detail.batches.flatMap { it.actual }) { row -> Text(stringResource(R.string.fbs_location, "${row.sku} · ${row.location}", row.quantity)) }
                item {
                    OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.fbs_scan)) }, singleLine = true)
                    OutlinedButton({ requestCamera("scan") }) { Text(stringResource(R.string.fbs_camera)) }
                    Button({ vm.action("pick", code.trim()) }, enabled = !state.busy && code.isNotBlank()) { Text(stringResource(R.string.fbs_pick)) }
                }
            }
            item {
                Text(stringResource(R.string.fbs_print))
                for (kind in listOf("labels", "document")) {
                    val file = detail.files.lastOrNull { it.kind == kind } ?: continue
                    OutlinedButton({
                        pdf = file.id
                        save.launch("${order.shopNumber}-$kind.pdf")
                    }, enabled = !state.busy) {
                        Text(stringResource(if (kind == "labels") R.string.fbs_labels else R.string.fbs_document))
                    }
                }
            }
            if (order.stage == "packing") {
                item {
                    OutlinedTextField(packages, vm::changePackages, label = { Text(stringResource(R.string.fbs_packages)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            val photos = when (order.stage) {
                "packing" -> listOf("packing" to R.string.fbs_photo_pack)
                "pickup" -> listOf("signed" to R.string.fbs_signed, "vehicle" to R.string.fbs_vehicle)
                else -> emptyList()
            }
            items(photos) { (kind, title) ->
                OutlinedButton({ requestCamera(kind) }, enabled = !state.busy) { Text(stringResource(title)) }
                Text(stringResource(R.string.fbs_uploaded, detail.files.count { it.kind == kind }))
            }
            if (order.stage == "packing") {
                item {
                    Button({ vm.action("pack", packages = packages.toIntOrNull() ?: 0) }, enabled = !state.busy && (packages.toIntOrNull() ?: 0) > 0 && detail.files.any { it.kind == "packing" }) { Text(stringResource(R.string.fbs_pack)) }
                }
            }
            if (order.stage == "pickup") {
                item {
                    Button({ vm.action("ship") }, enabled = !state.busy && photos.all { (kind, _) -> detail.files.any { it.kind == kind } }) { Text(stringResource(R.string.fbs_ship)) }
                }
            }
        }
        if (id != null) item { OutlinedButton({ vm.detail(id) }, enabled = !state.busy) { Text(stringResource(R.string.fbs_retry)) } }
    }
}
