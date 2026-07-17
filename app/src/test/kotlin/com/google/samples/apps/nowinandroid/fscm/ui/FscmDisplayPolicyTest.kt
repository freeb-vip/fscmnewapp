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

import com.google.samples.apps.nowinandroid.core.model.data.DisplayMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FscmDisplayPolicyTest {

    @Test
    fun fullModeUsesStandardLayoutOnRegularScreen() {
        val policy = fscmDisplayPolicy(DisplayMode.FULL, screenWidthDp = 360, fontScale = 1f)

        assertFalse(policy.isCompact)
        assertEquals(4, policy.appGridColumns)
        assertEquals(2, policy.taskGridColumns)
    }

    @Test
    fun narrowScreenUsesCompactLayoutInFullMode() {
        val policy = fscmDisplayPolicy(DisplayMode.FULL, screenWidthDp = 359, fontScale = 1f)

        assertTrue(policy.isCompact)
        assertEquals(3, policy.appGridColumns)
        assertEquals(1, policy.taskGridColumns)
    }

    @Test
    fun largeSystemFontUsesCompactLayoutInFullMode() {
        val policy = fscmDisplayPolicy(DisplayMode.FULL, screenWidthDp = 411, fontScale = 1.3f)

        assertTrue(policy.isCompact)
    }

    @Test
    fun manualCompactModeOverridesRegularScreen() {
        val policy = fscmDisplayPolicy(DisplayMode.COMPACT, screenWidthDp = 411, fontScale = 1f)

        assertTrue(policy.isCompact)
        assertEquals(3, policy.appGridColumns)
    }
}
