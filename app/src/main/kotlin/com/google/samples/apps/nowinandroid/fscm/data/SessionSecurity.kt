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
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class VerifiedOfflineLease(
    val leaseId: String,
    val subject: String,
    val deviceId: String,
    val namespaceId: Long,
    val expiresAt: Long,
    val scopes: List<String>,
)

@Singleton
class DeviceIdentityManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun registration(): DeviceRegistration {
        val keyPair = keyPair()
        return DeviceRegistration(
            deviceId = deviceId(),
            publicKey = keyPair.public.encoded.base64Url(),
            keyAlgorithm = keyAlgorithm(keyPair.public.algorithm),
        )
    }

    fun deviceId(): String {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return preferences.getString(KEY_DEVICE_ID, null)
            ?: "android-${UUID.randomUUID()}".also { generated ->
                check(preferences.edit().putString(KEY_DEVICE_ID, generated).commit())
            }
    }

    fun proofHeaders(method: String, path: String, body: String, timestampMillis: Long): Map<String, String> {
        val nonce = UUID.randomUUID().toString()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray()).base64Url()
        val canonical = listOf(method.uppercase(), path, bodyHash, nonce, timestampMillis.toString()).joinToString("\n")
        val keyPair = keyPair()
        val algorithm = keyAlgorithm(keyPair.public.algorithm)
        val signature = Signature.getInstance(
            if (algorithm == ALGORITHM_ES256) "SHA256withECDSA" else "SHA256withRSA",
        ).run {
            initSign(keyPair.private)
            update(canonical.encodeToByteArray())
            sign().base64Url()
        }
        return mapOf(
            "X-FSCM-Device-ID" to deviceId(),
            "X-FSCM-Device-Key-Alg" to algorithm,
            "X-FSCM-Device-Timestamp" to timestampMillis.toString(),
            "X-FSCM-Device-Nonce" to nonce,
            "X-FSCM-Device-Body-SHA256" to bodyHash,
            "X-FSCM-Device-Signature" to signature,
        )
    }

    fun signDetached(value: String): String {
        val keyPair = keyPair()
        val algorithm = keyAlgorithm(keyPair.public.algorithm)
        return Signature.getInstance(
            if (algorithm == ALGORITHM_ES256) "SHA256withECDSA" else "SHA256withRSA",
        ).run {
            initSign(keyPair.private)
            update(value.encodeToByteArray())
            sign().base64Url()
        }
    }

    private fun keyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS)
        if (privateKey != null && certificate != null) return KeyPair(certificate.publicKey, privateKey)

        return runCatching { generateEcKeyPair() }
            .getOrElse {
                keyStore.deleteEntry(KEY_ALIAS)
                generateRsaKeyPair()
            }
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun generateRsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun keyAlgorithm(algorithm: String): String =
        if (algorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true)) ALGORITHM_ES256 else ALGORITHM_RS256

    private companion object {
        const val PREFS_NAME = "fscm_device_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ALIAS = "fscm_device_signing_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALGORITHM_ES256 = "ES256"
        const val ALGORITHM_RS256 = "RS256"
    }
}

@Singleton
class SessionCipher @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    fun seal(plaintext: String, associatedData: String): String {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(associatedData.encodeToByteArray())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return listOf(ENVELOPE_VERSION, cipher.iv.base64Url(), ciphertext.base64Url()).joinToString(":")
    }

    fun open(envelope: String, associatedData: String): String? = runCatching {
        val parts = envelope.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, parts[1].base64UrlBytes()))
        cipher.updateAAD(associatedData.encodeToByteArray())
        cipher.doFinal(parts[2].base64UrlBytes()).decodeToString()
    }.getOrNull()

    fun databasePassphrase(): CharArray {
        val preferences = applicationContext.getSharedPreferences(DB_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.getString(KEY_WRAPPED_DB_PASSPHRASE, null)?.let { wrapped ->
            return open(wrapped, DB_AAD)?.toCharArray() ?: error("Database encryption key is unavailable")
        }
        val passphrase = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val encoded = passphrase.base64Url()
        passphrase.fill(0)
        val wrapped = seal(encoded, DB_AAD)
        check(preferences.edit().putString(KEY_WRAPPED_DB_PASSPHRASE, wrapped).commit())
        return encoded.toCharArray()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private val applicationContext = context.applicationContext

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fscm_session_encryption_v2"
        const val AES_MODE = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val ENVELOPE_VERSION = "v1"
        const val DB_PREFS_NAME = "fscm_database_key"
        const val KEY_WRAPPED_DB_PASSPHRASE = "wrapped_passphrase"
        const val DB_AAD = "fscm-database-key:v1"
    }
}

@Singleton
class TrustedClock @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun recordTrustedTime(serverTimeMillis: Long) {
        if (serverTimeMillis <= 0) return
        val wall = System.currentTimeMillis()
        preferences().edit()
            .putLong(KEY_TRUSTED_TIME, serverTimeMillis)
            .putLong(KEY_TRUSTED_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT_COUNT, bootCount())
            .putLong(KEY_MAX_WALL_TIME, maxOf(serverTimeMillis, wall, maxObservedWallTime()))
            .commit()
    }

    fun trustedNowMillis(): Long? {
        val wall = System.currentTimeMillis()
        val maximum = maxObservedWallTime()
        if (maximum > 0 && wall + CLOCK_ROLLBACK_TOLERANCE_MS < maximum) return null
        val preferences = preferences()
        val trusted = preferences.getLong(KEY_TRUSTED_TIME, 0)
        val trustedElapsed = preferences.getLong(KEY_TRUSTED_ELAPSED, 0)
        val estimate = if (
            trusted > 0 &&
            preferences.getInt(KEY_BOOT_COUNT, -1) == bootCount() &&
            SystemClock.elapsedRealtime() >= trustedElapsed
        ) {
            trusted + (SystemClock.elapsedRealtime() - trustedElapsed)
        } else {
            0
        }
        val now = maxOf(wall, estimate, maximum)
        preferences.edit().putLong(KEY_MAX_WALL_TIME, now).apply()
        return now
    }

    private fun maxObservedWallTime(): Long = preferences().getLong(KEY_MAX_WALL_TIME, 0)

    private fun bootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(0)

    private fun preferences() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "fscm_trusted_clock"
        const val KEY_TRUSTED_TIME = "trusted_time"
        const val KEY_TRUSTED_ELAPSED = "trusted_elapsed"
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_MAX_WALL_TIME = "max_wall_time"
        const val CLOCK_ROLLBACK_TOLERANCE_MS = 5L * 60L * 1000L
    }
}

@Singleton
class OfflineLeaseVerifier @Inject constructor() {
    fun verify(
        token: String,
        encodedPublicKey: String,
        expectedDeviceId: String,
        expectedNamespaceId: Long,
        nowMillis: Long,
    ): VerifiedOfflineLease? = runCatching {
        val publicKeyBytes = encodedPublicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot(Char::isWhitespace)
            .let { encoded ->
                runCatching { Base64.getDecoder().decode(encoded) }
                    .getOrElse { Base64.getUrlDecoder().decode(encoded) }
            }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val claims = JwtConsumerBuilder()
            .setRequireExpirationTime()
            .setExpectedAudience("fscm-mobile")
            .setEvaluationTime(org.jose4j.jwt.NumericDate.fromMilliseconds(nowMillis))
            .setAllowedClockSkewInSeconds(300)
            .setVerificationKey(publicKey)
            .setJwsAlgorithmConstraints(
                AlgorithmConstraints(
                    AlgorithmConstraints.ConstraintType.PERMIT,
                    AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
                ),
            )
            .build()
            .processToClaims(token)
        val deviceId = claims.getStringClaimValue("device_id")
        val namespaceId = claims.getClaimValue("namespace_id").toString().toLong()
        require(deviceId == expectedDeviceId)
        require(namespaceId == expectedNamespaceId)
        VerifiedOfflineLease(
            leaseId = claims.jwtId,
            subject = claims.subject,
            deviceId = deviceId,
            namespaceId = namespaceId,
            expiresAt = claims.expirationTime.valueInMillis,
            scopes = (claims.getClaimValue("offline_scopes") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
        )
    }.getOrNull()
}

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.base64UrlBytes(): ByteArray = Base64.getUrlDecoder().decode(this)
