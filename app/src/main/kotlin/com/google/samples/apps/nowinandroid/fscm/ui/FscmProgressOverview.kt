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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceiptStatistics
import com.google.samples.apps.nowinandroid.fscm.data.InboundSessionSummary
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private val ProgressBrand = Color(0xFF1677FF)
private val ProgressTrack = Color(0xFFE6F0FF)
private val ProgressStroke = Color(0xFFDCE7F5)
private val ProgressInk = Color(0xFF172033)
private val ProgressMuted = Color(0xFF66758A)
private val ProgressSuccess = Color(0xFF389E0D)
private val ProgressWarning = Color(0xFFD48806)
private val ProgressDanger = Color(0xFFCF1322)

internal data class FscmProgressMetrics(
    val total: Int,
    val pending: Int,
    val completed: Int,
    val exception: Int,
) {
    val percentage: Int = progressPercentage(total, completed)
    val fraction: Float = percentage / 100f
}

internal fun progressPercentage(total: Int, completed: Int): Int {
    if (total <= 0) return 0
    return (completed.coerceAtLeast(0).toDouble() / total * 100)
        .roundToInt()
        .coerceIn(0, 100)
}

internal fun warehouseReceivingProgress(session: ReceivingSession): FscmProgressMetrics {
    val total = session.expectedBoxCount.coerceAtLeast(0)
    val completed = maxOf(session.talliedBoxCount, session.scannedBoxCount).coerceAtLeast(0)
    return FscmProgressMetrics(
        total = total,
        pending = (total - completed).coerceAtLeast(0),
        completed = completed,
        exception = session.exceptionBoxCount.coerceAtLeast(0),
    )
}

internal fun centralReceivingProgress(statistics: CentralReceiptStatistics): FscmProgressMetrics =
    FscmProgressMetrics(
        total = statistics.totalBoxCount.coerceAtLeast(0),
        pending = statistics.pendingBoxCount.coerceAtLeast(0),
        completed = statistics.receivedBoxCount.coerceAtLeast(0),
        exception = statistics.exceptionBoxCount.coerceAtLeast(0),
    )

internal fun inboundProgress(summary: InboundSessionSummary): FscmProgressMetrics =
    FscmProgressMetrics(
        total = summary.totalCount.coerceAtLeast(0),
        pending = summary.centerPendingCount.coerceAtLeast(0),
        completed = summary.inboundedCount.coerceAtLeast(0),
        exception = summary.exceptionCount.coerceAtLeast(0),
    )

private fun saturatedNonNegativeSum(first: Int, second: Int): Int =
    (first.coerceAtLeast(0).toLong() + second.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

@Composable
internal fun FscmProgressOverview(
    title: String,
    totalLabel: String,
    pendingLabel: String,
    completedLabel: String,
    exceptionLabel: String,
    metrics: FscmProgressMetrics,
    modifier: Modifier = Modifier,
    onPendingClick: (() -> Unit)? = null,
    onExceptionClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, ProgressStroke),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = ProgressInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${metrics.percentage}%",
                    color = ProgressBrand,
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { metrics.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = ProgressBrand,
                trackColor = ProgressTrack,
            )
            Column(Modifier.padding(top = 14.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    ProgressMetricCell(totalLabel, metrics.total, ProgressInk, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(88.dp), color = ProgressStroke)
                    ProgressMetricCell(
                        pendingLabel,
                        metrics.pending,
                        ProgressWarning,
                        Modifier.weight(1f),
                        onClick = onPendingClick,
                    )
                }
                HorizontalDivider(color = ProgressStroke)
                Row(Modifier.fillMaxWidth()) {
                    ProgressMetricCell(completedLabel, metrics.completed, ProgressSuccess, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(88.dp), color = ProgressStroke)
                    ProgressMetricCell(
                        exceptionLabel,
                        metrics.exception,
                        ProgressDanger,
                        Modifier.weight(1f),
                        onClick = onExceptionClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressMetricCell(
    label: String,
    value: Int,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val formattedValue = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
    Column(
        modifier = modifier
            .height(88.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = "$label $formattedValue" }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formattedValue,
                modifier = Modifier.fillMaxWidth(),
                color = valueColor,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 24.sp,
                    maxFontSize = 36.sp,
                    stepSize = 1.sp,
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        if (onClick != null) {
            Text("查看详情 ›", color = valueColor, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = label,
            color = if (valueColor == ProgressDanger) ProgressDanger else ProgressMuted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (valueColor == ProgressDanger) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
