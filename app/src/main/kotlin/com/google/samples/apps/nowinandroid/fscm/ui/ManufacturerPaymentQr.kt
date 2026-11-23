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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.google.samples.apps.nowinandroid.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ManufacturerPaymentQrLink(url: String) = key(url) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<Int?>(null) }
    val request = remember(context, url) { ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).allowHardware(false).build() }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val fileName = rememberSaveable { "FSCM-QR-${System.currentTimeMillis()}.png" }

    fun save(destination: Uri?) {
        if (saving) return
        saveMessage = null
        saving = true
        scope.launch {
            val message = try {
                // The document picker may recreate the activity before delivering its result.
                val image = preview ?: (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
                    ?: throw IOException("QR image unavailable")
                saveManufacturerPaymentQr(context, image, fileName, destination)
                if (destination == null) R.string.manufacturer_bill_qr_saved_gallery else R.string.manufacturer_bill_qr_saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                R.string.manufacturer_bill_qr_save_failed
            } finally {
                saving = false
            }
            saveMessage = message
        }
    }

    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) save(uri)
    }
    fun close() {
        if (saving) return
        open = false
        preview = null
        saveMessage = null
    }

    OutlinedButton(onClick = { open = true }) {
        Icon(Icons.Rounded.QrCode, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.manufacturer_bill_view_qr))
    }
    if (open) {
        var failed by remember { mutableStateOf(false) }
        ScanAwareAlertDialog(
            onDismissRequest = ::close,
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (failed) {
                        Text(stringResource(R.string.manufacturer_bill_qr_load_failed))
                    } else {
                        AsyncImage(
                            model = request,
                            contentDescription = stringResource(R.string.manufacturer_bill_view_qr),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                            contentScale = ContentScale.Fit,
                            onSuccess = { preview = it.result.drawable.toBitmap() },
                            onError = { failed = true },
                        )
                    }
                    saveMessage?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                if (failed) {
                    Button(onClick = { failed = false }) { Text(stringResource(R.string.common_retry)) }
                } else {
                    Button(
                        onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) save(null) else document.launch(fileName) },
                        enabled = preview != null && !saving,
                    ) {
                        Text(stringResource(if (saving) R.string.manufacturer_bill_qr_saving else R.string.manufacturer_bill_qr_save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::close, enabled = !saving) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
}

internal suspend fun saveManufacturerPaymentQr(context: Context, bitmap: Bitmap, fileName: String, destination: Uri?) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val target = destination ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FSCM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Gallery unavailable")
    } else {
        throw IOException("Save location required")
    }
    try {
        val written = resolver.openOutputStream(target, "wt")?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (written != true) throw IOException("QR image write failed")
        if (destination == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            if (published != 1) throw IOException("QR image unavailable in gallery")
        }
    } catch (e: Exception) {
        if (destination == null) resolver.delete(target, null, null)
        throw e
    }
}
