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

package com.google.samples.apps.nowinandroid

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import com.google.samples.apps.nowinandroid.fscm.data.CentralReceivingSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.data.LocalInboundSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.data.LocalReceivingSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.data.OfflineSyncScheduler
import com.google.samples.apps.nowinandroid.fscm.ui.AppLanguage
import com.google.samples.apps.nowinandroid.fscm.ui.setAppLanguage
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltAndroidApp
class FscmApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var userDataRepository: UserDataRepository

    @Inject
    lateinit var localReceivingSyncScheduler: LocalReceivingSyncScheduler

    @Inject
    lateinit var localInboundSyncScheduler: LocalInboundSyncScheduler

    @Inject
    lateinit var offlineSyncScheduler: OfflineSyncScheduler

    @Inject
    lateinit var centralReceivingSyncScheduler: CentralReceivingSyncScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        localReceivingSyncScheduler.schedulePeriodic()
        localInboundSyncScheduler.schedulePeriodic()
        offlineSyncScheduler.schedulePeriodic()
        centralReceivingSyncScheduler.schedulePeriodic()
        val legacyTag = runBlocking {
            withContext(Dispatchers.IO) { userDataRepository.userData.first().language }
        }
        if (legacyTag.isNotBlank()) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppLanguage.fromLanguageTag(legacyTag)
                    .takeUnless { it == AppLanguage.System }
                    ?.let(::setAppLanguage)
            }
            applicationScope.launch {
                userDataRepository.clearLegacyLanguage()
            }
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
