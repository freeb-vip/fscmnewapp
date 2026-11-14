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

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import kotlinx.coroutines.launch

@Composable
internal fun rememberExceptionCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val coroutine = rememberCoroutineScope()
    val context = LocalContext.current
    val copied = stringResource(R.string.oe_copied)
    return { value ->
        if (value.isNotBlank()) {
            coroutine.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", value)))
                // Android 13 and later already display clipboard confirmation.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
internal fun ExceptionCopyField(label: String, value: String) {
    val copy = rememberExceptionCopy()
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = FscmInkSoft, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { stringResource(R.string.oe_not_recorded) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = value.isNotBlank(), onClickLabel = stringResource(R.string.oe_copy)) { copy(value) }, color = FscmBrandMain, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotBlank()) TextButton(onClick = { copy(value) }) { Text(stringResource(R.string.oe_copy)) }
        }
    }
}
