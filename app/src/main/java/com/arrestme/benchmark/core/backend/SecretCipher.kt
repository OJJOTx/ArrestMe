package com.arrestme.benchmark.core.backend

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts sensitive authentication tokens at rest before persisting to DataStore.
 */
interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

/**
 * Production Android Keystore AES-256-GCM cipher.
 * Keys never leave hardware/TEE-backed Android Keystore.
 */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecretCipher {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "arrestme_cloud_token_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val PREFIX = "enc:v1:"
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()

        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(PREFIX)) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
            if (raw.size <= 1 + IV_LENGTH_BYTES) return null
            val buffer = ByteBuffer.wrap(raw)
            val ivLength = buffer.get().toInt() and 0xFF
            if (ivLength !in 12..16 || raw.size <= 1 + ivLength) return null

            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val encryptedBytes = ByteArray(buffer.remaining())
            buffer.get(encryptedBytes)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        }.getOrNull()
    }
}

/**
 * Standard JCA AES-256-GCM cipher for local JVM unit tests where AndroidKeyStore is unavailable.
 */
class InMemoryAesGcmSecretCipher(
    private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
) : SecretCipher {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX = "enc:mem:v1:"
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(PREFIX)) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX))
            val buffer = ByteBuffer.wrap(raw)
            val ivLength = buffer.get().toInt() and 0xFF
            if (ivLength !in 12..16 || raw.size <= 1 + ivLength) return null
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val encryptedBytes = ByteArray(buffer.remaining())
            buffer.get(encryptedBytes)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        }.getOrNull()
    }
}
