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

import org.junit.Assert.assertEquals
import org.junit.Test

class BarcodeCandidateResolverTest {
    private val isBoxLabel: (String) -> Boolean = { it.startsWith("BOX:") }

    @Test
    fun preferredCandidate_isAcceptedImmediately() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("BOX:1")),
            resolvePreferredBarcodes(listOf("SKU-1", "BOX:1"), isBoxLabel, timedOut = false),
        )
    }

    @Test
    fun multiplePreferredCandidates_excludeOtherCodes() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("BOX:1", "BOX:2")),
            resolvePreferredBarcodes(listOf("BOX:1", "SKU-1", "BOX:2"), isBoxLabel, timedOut = false),
        )
    }

    @Test
    fun duplicateCandidates_areDeduplicated() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("BOX:1")),
            resolvePreferredBarcodes(listOf("BOX:1", "BOX:1"), isBoxLabel, timedOut = false),
        )
    }

    @Test
    fun nonPreferredCandidate_waitsBeforeTimeout() {
        assertEquals(
            PreferredBarcodeDecision.Wait,
            resolvePreferredBarcodes(listOf("SKU-1"), isBoxLabel, timedOut = false),
        )
    }

    @Test
    fun nonPreferredCandidate_requestsRetryAfterTimeout() {
        assertEquals(
            PreferredBarcodeDecision.Retry,
            resolvePreferredBarcodes(listOf("SKU-1"), isBoxLabel, timedOut = true),
        )
    }

    @Test
    fun singleNonPreferredCandidate_isAcceptedAfterTimeoutWhenFallbackEnabled() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("LOCATION-A-01")),
            resolvePreferredBarcodes(
                candidates = listOf("LOCATION-A-01"),
                preferredMatcher = isBoxLabel,
                timedOut = true,
                allowNonPreferredFallback = true,
            ),
        )
    }

    @Test
    fun multipleNonPreferredCandidates_areSelectableAfterTimeoutWhenFallbackEnabled() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("LOCATION-A-01", "LOCATION-A-02")),
            resolvePreferredBarcodes(
                candidates = listOf("LOCATION-A-01", "LOCATION-A-02"),
                preferredMatcher = isBoxLabel,
                timedOut = true,
                allowNonPreferredFallback = true,
            ),
        )
    }

    @Test
    fun preferredCandidate_stillWinsWhenFallbackEnabled() {
        assertEquals(
            PreferredBarcodeDecision.Accept(listOf("BOX:1")),
            resolvePreferredBarcodes(
                candidates = listOf("LOCATION-A-01", "BOX:1"),
                preferredMatcher = isBoxLabel,
                timedOut = true,
                allowNonPreferredFallback = true,
            ),
        )
    }
}
