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
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineCredentialMaterial(
    val credentialId: String,
    val username: String,
    val salt: String,
    val verifier: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

@Singleton
class OfflineCredentialManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun create(username: String, password: String, ttlMs: Long, now: Long = System.currentTimeMillis()): OfflineCredentialMaterial {
        val normalizedUsername = username.trim()
        val salt = randomBytes(SALT_BYTES)
        val verifier = OfflineCredentialHasher.verifier(
            username = normalizedUsername,
            password = password,
            salt = salt,
            deviceSecret = deviceSecret(),
        )
        return OfflineCredentialMaterial(
            credentialId = randomBytes(CREDENTIAL_ID_BYTES).base64(),
            username = normalizedUsername,
            salt = salt.base64(),
            verifier = verifier,
            issuedAt = now,
            expiresAt = now + ttlMs,
        )
    }

    fun verify(session: SessionState, username: String, password: String, now: Long = System.currentTimeMillis()): Boolean {
        if (session.offlineExpiresAt <= now) return false
        if (!session.offlineUsername.equals(username.trim(), ignoreCase = true)) return false
        val salt = runCatching { session.offlinePasswordSalt.base64Bytes() }.getOrNull() ?: return false
        val expected = session.offlinePasswordVerifier.takeIf { it.isNotBlank() } ?: return false
        val actual = OfflineCredentialHasher.verifier(
            username = session.offlineUsername,
            password = password,
            salt = salt,
            deviceSecret = deviceSecret(),
        )
        return MessageDigest.isEqual(expected.base64Bytes(), actual.base64Bytes())
    }

    private fun deviceSecret(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_ENCRYPTED_SECRET, null)
        val iv = prefs.getString(KEY_SECRET_IV, null)
        if (encrypted != null && iv != null) {
            return decryptWithKeystore(encrypted.base64Bytes(), iv.base64Bytes())
                ?: error("Legacy offline credential key is unavailable")
        }
        check(encrypted == null && iv == null) { "Legacy offline credential is incomplete" }

        val secret = randomBytes(DEVICE_SECRET_BYTES)
        val encryptedSecret = encryptWithKeystore(secret)
            ?: error("Android Keystore is unavailable")
        check(
            prefs.edit()
                .putString(KEY_ENCRYPTED_SECRET, encryptedSecret.ciphertext.base64())
                .putString(KEY_SECRET_IV, encryptedSecret.iv.base64())
                .commit(),
        )
        return secret
    }

    private fun encryptWithKeystore(plaintext: ByteArray): EncryptedSecret? = runCatching {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        EncryptedSecret(cipher.doFinal(plaintext), cipher.iv)
    }.getOrNull()

    private fun decryptWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(false)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private data class EncryptedSecret(val ciphertext: ByteArray, val iv: ByteArray)

    private companion object {
        const val PREFS_NAME = "fscm_offline_credential"
        const val KEYSTORE_ALIAS = "fscm_offline_credential_secret"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_MODE = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_ENCRYPTED_SECRET = "encrypted_secret"
        const val KEY_SECRET_IV = "secret_iv"
        const val SALT_BYTES = 24
        const val DEVICE_SECRET_BYTES = 32
        const val CREDENTIAL_ID_BYTES = 16
    }
}

object OfflineCredentialHasher {
    fun verifier(
        username: String,
        password: String,
        salt: ByteArray,
        deviceSecret: ByteArray,
    ): String {
        val mixedSalt = sha256(username.trim().lowercase().encodeToByteArray() + salt + deviceSecret)
        return deriveKey(password, mixedSalt).base64()
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } catch (_: NoSuchAlgorithmException) {
            // Android 7 does not provide PBKDF2WithHmacSHA256 through SecretKeyFactory.
            pbkdf2WithHmacSha256(password.encodeToByteArray(), salt, ITERATIONS, KEY_BITS / Byte.SIZE_BITS)
        } finally {
            spec.clearPassword()
        }
    }

    internal fun pbkdf2WithHmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }

        val mac = Mac.getInstance(HMAC_SHA256).apply {
            init(SecretKeySpec(password, HMAC_SHA256))
        }
        val output = ByteArray(keyLengthBytes)
        var outputOffset = 0
        var blockIndex = 1
        while (outputOffset < keyLengthBytes) {
            val block = salt + byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            )
            var u = mac.doFinal(block)
            val derived = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                derived.indices.forEach { index ->
                    derived[index] = (derived[index].toInt() xor u[index].toInt()).toByte()
                }
            }
            val bytesToCopy = minOf(derived.size, keyLengthBytes - outputOffset)
            derived.copyInto(output, outputOffset, endIndex = bytesToCopy)
            outputOffset += bytesToCopy
            blockIndex++
        }
        return output
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun String.base64Bytes(): ByteArray = Base64.getDecoder().decode(this)
