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
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalInboundSyncScheduler
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedulePeriodic() {
        val request =
            PeriodicWorkRequestBuilder<LocalInboundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueSoon() {
        val request =
            OneTimeWorkRequestBuilder<LocalInboundSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun enqueueCacheRefresh(preferredSessionId: Long? = null) {
        val request =
            OneTimeWorkRequestBuilder<LocalInboundCacheRefreshWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(CACHE_SESSION_ID_KEY to (preferredSessionId ?: 0L)))
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CACHE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val PERIODIC_WORK_NAME = "fscm-local-inbound-periodic-sync"
        const val IMMEDIATE_WORK_NAME = "fscm-local-inbound-immediate-sync"
        const val CACHE_WORK_NAME = "fscm-local-inbound-cache-refresh"
        const val CACHE_SESSION_ID_KEY = "preferred_session_id"
    }
}

@HiltWorker
class LocalInboundSyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocalInboundRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (runCatching { repository.syncAllPending() }.getOrDefault(false)) Result.success() else Result.retry()
}

@HiltWorker
class LocalInboundCacheRefreshWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocalInboundRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferredSessionId = inputData.getLong("preferred_session_id", 0L).takeIf { it > 0 }
        return if (repository.refreshCachesInBackground(preferredSessionId)) Result.success() else Result.retry()
    }
}
