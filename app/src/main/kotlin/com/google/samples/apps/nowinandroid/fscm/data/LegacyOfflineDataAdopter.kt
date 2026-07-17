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

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyOfflineDataAdopter @Inject constructor(
    private val offlineCacheDao: OfflineCacheDao,
    private val receivingDao: ReceivingDao,
    private val pendingSyncDao: PendingSyncDao,
    private val localReceivingDao: LocalReceivingDao,
    private val localInboundDao: LocalInboundDao,
) {
    suspend fun adoptFor(session: SessionState) {
        val userId = session.user?.id ?: return
        val namespaceId = session.currentNamespace?.id ?: return
        if (userId <= 0 || namespaceId <= 0) return
        offlineCacheDao.adoptLegacy(userId, namespaceId)
        receivingDao.adoptLegacy(userId, namespaceId)
        pendingSyncDao.adoptLegacy(userId, namespaceId, session.deviceId, session.deviceKeyAlgorithm)
        localReceivingDao.adoptLegacyBatches(userId, namespaceId)
        localReceivingDao.adoptLegacyScans(userId, namespaceId)
        localInboundDao.adoptLegacySessions(userId, namespaceId)
        localInboundDao.adoptLegacySnapshots(userId, namespaceId)
        localInboundDao.adoptLegacyBatches(userId, namespaceId)
        localInboundDao.adoptLegacyItems(userId, namespaceId)
    }
}
