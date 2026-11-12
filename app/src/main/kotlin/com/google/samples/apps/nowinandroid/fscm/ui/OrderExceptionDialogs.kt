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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.samples.apps.nowinandroid.R

@Composable
internal fun ExceptionPhotoViewer(bytes: ByteArray, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val image = remember(bytes, context) {
        ImageRequest.Builder(context).data(bytes).size(3072, 3072).build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    ScanAwareDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.oe_close), color = Color.White) }
                    TextButton(onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    }) { Text(stringResource(R.string.oe_reset_zoom), color = Color.White) }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds()
                        .semantics { stateDescription = "${(scale * 100).toInt()}%" }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 3f
                                offset = Offset.Zero
                            })
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                val maxX = size.width * (scale - 1f) / 2f
                                val maxY = size.height * (scale - 1f) / 2f
                                offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = image,
                        contentDescription = name.ifBlank { stringResource(R.string.oe_photo_hint) },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    )
                }
                Text(stringResource(R.string.oe_photo_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun ExceptionMeasuresEditor(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    ScanAwareDialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.oe_measures), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        keyboard?.hide()
                        onDone()
                    }) { Text(stringResource(R.string.oe_done)) }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 2000) onChange(it) },
                    placeholder = { Text(stringResource(R.string.oe_measures_hint)) },
                    modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
                )
                Text(stringResource(R.string.oe_char_count, value.length), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelMedium)
            }
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
        }
    }
}

@Composable
internal fun ExceptionCorrectionConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ScanAwareAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.oe_confirm_title)) },
        text = { Text(stringResource(R.string.oe_confirm_message), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.oe_confirm_submit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.oe_cancel)) } },
    )
}
