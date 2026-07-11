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
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
            decryptWithKeystore(encrypted.base64Bytes(), iv.base64Bytes())?.let { return it }
        }

        val secret = randomBytes(DEVICE_SECRET_BYTES)
        val encryptedSecret = encryptWithKeystore(secret)
        if (encryptedSecret != null) {
            prefs.edit()
                .putString(KEY_ENCRYPTED_SECRET, encryptedSecret.ciphertext.base64())
                .putString(KEY_SECRET_IV, encryptedSecret.iv.base64())
                .apply()
        } else {
            prefs.edit().putString(KEY_FALLBACK_SECRET, secret.base64()).apply()
        }
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
    }.getOrElse {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FALLBACK_SECRET, null)
            ?.base64Bytes()
    }

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
        const val KEY_FALLBACK_SECRET = "fallback_secret"
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
        val spec = PBEKeySpec(password.toCharArray(), mixedSalt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        return key.base64()
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun String.base64Bytes(): ByteArray = Base64.getDecoder().decode(this)
