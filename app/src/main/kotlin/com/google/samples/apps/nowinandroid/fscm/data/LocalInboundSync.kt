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
    private val requests = context.getSharedPreferences("inbound_sync_requests", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueueSession(sessionId: Long, userId: Long, namespaceId: Long) {
        val key = "$userId:$namespaceId:$sessionId"
        val generation = requests.getLong(key, 0) + 1
        check(requests.edit().putLong(key, generation).commit())
        val work = OneTimeWorkRequestBuilder<InboundSessionSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("session" to sessionId, "user" to userId, "namespace" to namespaceId, "generation" to generation))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("fscm-inbound-session-$key", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    fun requested(key: String): Long = requests.getLong(key, 0)
    fun completed(key: String): Long = requests.getLong("done:$key", 0)
    fun markCompleted(key: String, generation: Long) {
        check(requests.edit().putLong("done:$key", generation).commit())
    }

    fun schedulePeriodic() {
        val cacheRequest = PeriodicWorkRequestBuilder<LocalInboundCacheRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("fscm-reference-cache-daily", ExistingPeriodicWorkPolicy.KEEP, cacheRequest)
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

    fun enqueueCacheRefresh(preferredSessionId: Long? = null, force: Boolean = false, userId: Long = 0, namespaceId: Long = 0) {
        val request =
            OneTimeWorkRequestBuilder<LocalInboundCacheRefreshWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(CACHE_SESSION_ID_KEY to (preferredSessionId ?: 0L), "force" to force, "user" to userId, "namespace" to namespaceId))
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$CACHE_WORK_NAME-$userId-$namespaceId-${if (force) "force" else "normal"}",
            ExistingWorkPolicy.KEEP,
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
    private val crossOrderRepository: CrossOrderReceivingRepository,
    private val purchaseReceivingRepository: PurchaseReceivingRepository,
    private val autoPurchaseReceivingRepository: AutoPurchaseReceivingRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (runCatching {
                val cross = crossOrderRepository.sync()
                val scans = repository.syncAllPending()
                val purchases = purchaseReceivingRepository.resumePending()
                val automatic = autoPurchaseReceivingRepository.sync()
                scans && cross && purchases && automatic
            }.getOrDefault(false)
        ) {
            Result.success()
        } else {
            Result.retry()
        }
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
        val user = inputData.getLong("user", 0)
        val namespace = inputData.getLong("namespace", 0)
        if (user > 0 && !repository.matchesScope(user, namespace)) return Result.success()
        return if (repository.refreshCachesInBackground(preferredSessionId, inputData.getBoolean("force", false))) Result.success() else Result.retry()
    }
}

/** New triggers during a sync are drained together; already covered queued work becomes a no-op. */
@HiltWorker
class InboundSessionSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocalInboundRepository,
    private val scheduler: LocalInboundSyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("session", 0)
        val user = inputData.getLong("user", 0)
        val namespace = inputData.getLong("namespace", 0)
        val key = "$user:$namespace:$id"
        if (id <= 0 || !repository.matchesScope(user, namespace)) return Result.success()
        return try {
            drainInboundSyncRequests(
                requested = { scheduler.requested(key) },
                completed = { scheduler.completed(key) },
                markCompleted = { scheduler.markCompleted(key, it) },
                sameScope = { repository.matchesScope(user, namespace) },
                sync = { repository.syncSessionInBackground(id) },
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal suspend fun drainInboundSyncRequests(
    requested: () -> Long,
    completed: () -> Long,
    markCompleted: (Long) -> Unit,
    sameScope: suspend () -> Boolean,
    sync: suspend () -> Unit,
) {
    while (completed() < requested()) {
        val generation = requested()
        if (!sameScope()) return
        sync()
        markCompleted(generation)
    }
}
