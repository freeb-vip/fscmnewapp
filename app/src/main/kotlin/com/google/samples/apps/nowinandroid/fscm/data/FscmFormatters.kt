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

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

fun formatAmount(value: Double): String {
    val prefix = if (value < 0) "-" else ""
    val absValue = abs(value)
    return when {
        absValue >= 10000 -> "$prefix${"%.1f".format(absValue / 10000)}万"
        else -> "$prefix${"%.2f".format(absValue)}"
    }
}

fun formatCount(value: Int): String = when {
    value >= 10000 -> "${"%.1f".format(value / 10000.0)}万"
    else -> value.toString()
}

fun formatPercent(value: Double): String = "${"%.1f".format(value * 100)}%"

fun shortTime(value: String): String =
    value.takeIf { it.length >= 16 }?.substring(5, 16)?.replace('T', ' ') ?: value

fun todayRange(days: Long = 6): Pair<String, String> {
    val end = LocalDate.now()
    val start = end.minusDays(days)
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    return start.format(formatter) to end.format(formatter)
}

fun statusText(status: String): String = when (status.lowercase()) {
    "completed", "done", "finished" -> "已完成"
    "processing", "in_progress", "working", "scanning" -> "处理中"
    "cancelled" -> "已取消"
    "draft" -> "草稿"
    "tally" -> "清点中"
    "normal" -> "正常"
    "damaged" -> "破损"
    "duplicate" -> "重复"
    "unknown" -> "未知"
    else -> status.ifBlank { "待处理" }
}
