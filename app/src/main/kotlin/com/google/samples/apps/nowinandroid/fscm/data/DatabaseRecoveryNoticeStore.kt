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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRecoveryNoticeStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markLocalDataReset() {
        preferences.edit().putBoolean(KEY_LOCAL_DATA_RESET, true).commit()
    }

    fun consumeLocalDataReset(): Boolean {
        if (!preferences.getBoolean(KEY_LOCAL_DATA_RESET, false)) return false
        return preferences.edit().remove(KEY_LOCAL_DATA_RESET).commit()
    }

    private companion object {
        const val PREFS_NAME = "fscm_database_recovery"
        const val KEY_LOCAL_DATA_RESET = "local_data_reset"
    }
}
