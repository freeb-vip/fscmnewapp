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

package com.google.samples.apps.nowinandroid.core.ui

internal sealed interface PreferredBarcodeDecision {
    data object Wait : PreferredBarcodeDecision

    data object Retry : PreferredBarcodeDecision

    data class Accept(val values: List<String>) : PreferredBarcodeDecision
}

internal fun resolvePreferredBarcodes(
    candidates: Collection<String>,
    preferredMatcher: (String) -> Boolean,
    timedOut: Boolean,
): PreferredBarcodeDecision {
    val uniqueCandidates = candidates.distinct()
    val preferredCandidates = uniqueCandidates.filter(preferredMatcher)
    return when {
        preferredCandidates.isNotEmpty() -> PreferredBarcodeDecision.Accept(preferredCandidates)
        timedOut && uniqueCandidates.isNotEmpty() -> PreferredBarcodeDecision.Retry
        else -> PreferredBarcodeDecision.Wait
    }
}
