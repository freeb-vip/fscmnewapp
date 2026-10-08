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

import kotlinx.coroutines.CompletableDeferred

/** Shares an in-flight download; uploads can require a download started after their acknowledgement. */
internal class InboundRefreshCoordinator<K, V> {
    private data class Flight<V>(val startedAt: Long, val result: CompletableDeferred<V>)
    private val flights = mutableMapOf<K, Flight<V>>()

    suspend fun run(key: K, minimumStartedAt: Long = Long.MIN_VALUE, download: suspend () -> V): V {
        while (true) {
            var owner = false
            val flight = synchronized(flights) {
                flights[key] ?: Flight<V>(System.nanoTime(), CompletableDeferred()).also {
                    flights[key] = it
                    owner = true
                }
            }
            if (!owner) {
                val result = flight.result.await()
                if (flight.startedAt >= minimumStartedAt) return result
                continue
            }
            try {
                val result = download()
                synchronized(flights) { flights.remove(key) }
                flight.result.complete(result)
                return result
            } catch (error: Throwable) {
                synchronized(flights) { flights.remove(key) }
                flight.result.completeExceptionally(error)
                throw error
            }
        }
    }
}
