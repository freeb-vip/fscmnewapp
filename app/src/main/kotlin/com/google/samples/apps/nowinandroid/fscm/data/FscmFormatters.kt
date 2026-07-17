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

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatAmount(value: Double): String = NumberFormat.getNumberInstance(Locale.getDefault())
    .apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    .format(value)

fun formatCount(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

fun formatPercent(value: Double): String = NumberFormat.getPercentInstance(Locale.getDefault())
    .apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    .format(value)

fun shortTime(value: String): String =
    value.takeIf { it.length >= 16 }?.substring(5, 16)?.replace('T', ' ') ?: value

fun todayRange(days: Long = 6): Pair<String, String> {
    val end = LocalDate.now()
    val start = end.minusDays(days)
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    return start.format(formatter) to end.format(formatter)
}
