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

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
internal fun BoxLabelCodeButton(
    code: String,
    onShowQrCode: (String) -> Unit,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val description = stringResource(R.string.box_label_show_code_qr, code)
    TextButton(
        onClick = { onShowQrCode(code) },
        enabled = code.isNotBlank(),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(code, modifier = Modifier.weight(1f, fill = false), style = style, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun BoxLabelCodeDialog(code: String, onDismiss: () -> Unit) {
    val qrImage = remember(code) {
        try {
            boxLabelCodeQrBitmap(code).asImageBitmap()
        } catch (_: WriterException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.box_label_code_qr_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (qrImage != null) {
                    Image(
                        bitmap = qrImage,
                        contentDescription = stringResource(R.string.box_label_code_qr_description, code),
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(stringResource(R.string.box_label_code_qr_error), color = MaterialTheme.colorScheme.error)
                }
                SelectionContainer {
                    Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                if (qrImage != null) Text(stringResource(R.string.box_label_code_qr_hint), textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

// Encode only the displayed label code, preserving its original case and all characters.
internal fun boxLabelCodeQrBitmap(code: String): Bitmap {
    require(code.isNotBlank())
    val matrix = QRCodeWriter().encode(
        code,
        BarcodeFormat.QR_CODE,
        768,
        768,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
