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

package com.google.samples.apps.nowinandroid.fscm.device

import com.google.samples.apps.nowinandroid.fscm.data.isEdgeTicketUsable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFindPolicyTest {
    @Test
    fun registrationMessage_alwaysIncludesRequiredProtocolFields() {
        val encoded = Json.encodeToString(
            TerminalRegisterMessage(
                type = "register",
                protocolVersion = 1,
                terminalId = "terminal-1",
                terminalName = "DT50",
                appVersion = "1.0.0",
                platform = "android",
                lanIp = "192.168.5.18",
                capabilities = listOf("find-device"),
            ),
        )
        val registration = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("register", registration.getValue("type").jsonPrimitive.content)
        assertEquals(1, registration.getValue("protocol_version").jsonPrimitive.content.toInt())
        assertEquals("terminal-1", registration.getValue("terminal_id").jsonPrimitive.content)
        assertTrue(
            registration.getValue("capabilities").jsonArray
                .any { it.jsonPrimitive.content == "find-device" },
        )
    }

    @Test
    fun reconnectDelay_usesBoundedBackoff() {
        assertEquals(5_000L, terminalReconnectDelayMillis(0))
        assertEquals(30_000L, terminalReconnectDelayMillis(1))
        assertEquals(30_000L, terminalReconnectDelayMillis(100))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun connectionBurst_stopsAfterThreeAttempts() = runTest {
        val times = mutableListOf<Long>()
        withTerminalConnectionAttempts { times += currentTime }
        assertEquals(listOf(0L, 5_000L, 35_000L), times)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun hourlyRetry_waitsOneHourSkipsActiveConnectionsAndStopsWhenCancelled() = runTest {
        var loggedIn = true
        var connectingOrConnected = false
        val times = mutableListOf<Long>()
        val job = launch {
            retryIdleTerminalHourly(
                isIdle = { loggedIn && !connectingOrConnected },
                retry = { times += currentTime },
            )
        }
        runCurrent()
        advanceTimeBy(3_599_999L)
        runCurrent()
        assertTrue(times.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(3_600_000L), times)
        connectingOrConnected = true
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertEquals(1, times.size)
        connectingOrConnected = false
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertEquals(listOf(3_600_000L, 10_800_000L), times)
        loggedIn = false
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertEquals(2, times.size)
        job.cancel()
        loggedIn = true
        advanceTimeBy(7_200_000L)
        runCurrent()
        assertEquals(2, times.size)
    }

    @Test
    fun edgeTicket_requiresRemainingLifetimeBeyondRefreshMargin() {
        val now = 1_000_000L
        assertTrue(isEdgeTicketUsable(now + 11_000L, now, 10_000L))
        assertFalse(isEdgeTicketUsable(now + 10_000L, now, 10_000L))
        assertFalse(isEdgeTicketUsable(now - 1L, now))
    }
}
