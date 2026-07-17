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

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.samples.apps.nowinandroid.R

enum class AppLanguage(
    val languageTag: String,
    @StringRes val labelRes: Int,
) {
    System("", R.string.language_default),
    English("en", R.string.language_en),
    SimplifiedChinese("zh-Hans", R.string.language_zh_hans),
    Thai("th", R.string.language_th),
    Burmese("my", R.string.language_my),
    ;

    companion object {
        fun selected(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag().orEmpty()
            return fromLanguageTag(tag)
        }

        fun fromLanguageTag(tag: String): AppLanguage = when {
            tag.isBlank() -> System
            tag.equals("zh", ignoreCase = true) || tag.startsWith("zh-Hans", ignoreCase = true) -> SimplifiedChinese
            tag.startsWith("en", ignoreCase = true) -> English
            tag.startsWith("th", ignoreCase = true) -> Thai
            tag.startsWith("my", ignoreCase = true) -> Burmese
            else -> System
        }
    }
}

fun setAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
        if (language == AppLanguage.System) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.languageTag)
        },
    )
}
