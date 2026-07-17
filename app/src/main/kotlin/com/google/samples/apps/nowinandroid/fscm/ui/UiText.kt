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

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.samples.apps.nowinandroid.R

sealed interface UiText {
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Dynamic(val value: String) : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Dynamic -> value
    is UiText.Resource -> context.getString(id, *args.toTypedArray())
}

@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)

@Composable
fun localizedStatusText(status: String): String {
    val resource = when (status.lowercase()) {
        "completed", "done", "finished" -> R.string.status_completed
        "processing", "in_progress", "working", "scanning" -> R.string.status_processing
        "cancelled" -> R.string.status_cancelled
        "draft" -> R.string.status_draft
        "tally" -> R.string.status_tallying
        "normal" -> R.string.status_normal
        "damaged" -> R.string.status_damaged
        "duplicate" -> R.string.status_duplicate
        "voided" -> R.string.status_voided
        "shorted" -> R.string.status_shorted
        "received" -> R.string.status_received
        "exception" -> R.string.status_exception
        "unknown" -> R.string.common_unknown
        "open" -> R.string.status_open
        "resolved" -> R.string.status_resolved
        "closed" -> R.string.status_closed
        "on_hold" -> R.string.status_on_hold
        "archived" -> R.string.status_archived
        else -> null
    }
    return resource?.let { stringResource(it) } ?: status.ifBlank { stringResource(R.string.status_pending) }
}

@Composable
fun localizedPriorityText(priority: String): String = stringResource(
    when (priority.lowercase()) {
        "low" -> R.string.priority_low
        "high" -> R.string.priority_high
        "urgent" -> R.string.priority_urgent
        else -> R.string.priority_normal
    },
)
