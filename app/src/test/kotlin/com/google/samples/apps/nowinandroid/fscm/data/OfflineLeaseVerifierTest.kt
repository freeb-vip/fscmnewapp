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

import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineLeaseVerifierTest {
    private val verifier = OfflineLeaseVerifier()
    private val keyPair = ecKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    @Test
    fun validLease_isBoundToDeviceNamespaceAndScopes() {
        val now = System.currentTimeMillis()
        val token = lease(keyPair, now + 60_000)

        val verified = verifier.verify(token, publicKey, DEVICE_ID, NAMESPACE_ID, now)

        assertEquals("lease-1", verified?.leaseId)
        assertEquals(USER_ID.toString(), verified?.subject)
        assertEquals(listOf("receiving.write", "edge.print"), verified?.scopes)
    }

    @Test
    fun copiedLease_isRejectedOnAnotherDevice() {
        val now = System.currentTimeMillis()

        val verified = verifier.verify(lease(keyPair, now + 60_000), publicKey, "other-device", NAMESPACE_ID, now)

        assertNull(verified)
    }

    @Test
    fun expiredLease_isRejected() {
        val now = System.currentTimeMillis()

        val verified = verifier.verify(lease(keyPair, now - 10 * 60_000), publicKey, DEVICE_ID, NAMESPACE_ID, now)

        assertNull(verified)
    }

    @Test
    fun tamperedLease_isRejected() {
        val now = System.currentTimeMillis()
        val token = lease(keyPair, now + 60_000)
        val parts = token.split('.').toMutableList()
        val signature = Base64.getUrlDecoder().decode(parts[2]).also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
        }
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        val tampered = parts.joinToString(".")

        assertNull(verifier.verify(tampered, publicKey, DEVICE_ID, NAMESPACE_ID, now))
        assertTrue(token != tampered)
    }

    private fun lease(keyPair: KeyPair, expiresAt: Long): String {
        val claims = JwtClaims().apply {
            subject = USER_ID.toString()
            jwtId = "lease-1"
            setAudience("fscm-mobile")
            expirationTime = NumericDate.fromMilliseconds(expiresAt)
            setClaim("device_id", DEVICE_ID)
            setClaim("namespace_id", NAMESPACE_ID)
            setStringListClaim("offline_scopes", "receiving.write", "edge.print")
        }
        return JsonWebSignature().apply {
            payload = claims.toJson()
            key = keyPair.private
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        }.compactSerialization
    }

    private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private companion object {
        const val DEVICE_ID = "android-device-1"
        const val NAMESPACE_ID = 7L
        const val USER_ID = 42L
    }
}
