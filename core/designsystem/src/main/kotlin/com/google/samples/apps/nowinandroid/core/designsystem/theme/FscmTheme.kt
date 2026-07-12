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

package com.google.samples.apps.nowinandroid.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * FSCM default light color scheme.
 */
val FscmLightColorScheme = lightColorScheme(
    primary = FscmBrandMain,
    onPrimary = Color.White,
    primaryContainer = FscmBrandSoft,
    onPrimaryContainer = FscmBrandDeep,
    secondary = FscmBrandDeep,
    secondaryContainer = Color(0xFFE6F7FF),
    background = FscmPageBottom,
    surface = FscmSurfaceRaised,
    onSurface = FscmInkStrong,
    surfaceVariant = FscmSurfaceMuted,
    onSurfaceVariant = FscmInkSoft,
    outlineVariant = FscmStrokeSoft,
    error = FscmDanger,
    onError = Color.White,
)

/**
 * FSCM Theme.
 */
@Composable
fun FscmTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = FscmLightColorScheme,
        typography = NiaTypography,
        content = content,
    )
}
