package io.github.traushb.actuali.services.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class SyncEncryptionError : Exception() {
    object EncryptionFailed : SyncEncryptionError()
    object DecryptionFailed : SyncEncryptionError()
    object InvalidKey : SyncEncryptionError()
    object MissingKey : SyncEncryptionError()
}

/** A 256-bit AES key (CryptoKit's `SymmetricKey`). */
class SymmetricKey(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    fun bytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is SymmetricKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Encryption/decryption for sync messages (AES-256-GCM). */
object SyncEncryption {
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, key: SymmetricKey): EncryptedData = try {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
        // The JCA appends the tag; the wire format carries it separately.
        val sealed = cipher.doFinal(plaintext)
        EncryptedData(
            iv = iv,
            authTag = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size),
            data = sealed.copyOfRange(0, sealed.size - TAG_BYTES),
        )
    } catch (_: Exception) {
        throw SyncEncryptionError.EncryptionFailed
    }

    fun decrypt(encrypted: EncryptedData, key: SymmetricKey): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(encrypted.authTag.size * 8, encrypted.iv))
        cipher.doFinal(encrypted.data + encrypted.authTag)
    } catch (_: Exception) {
        throw SyncEncryptionError.DecryptionFailed
    }

    /**
     * Derive the key from a password with PBKDF2-HMAC-SHA512, 10,000
     * iterations, 32 bytes — Actual's `crypto.pbkdf2Sync(password, salt, …)`.
     * Computed over raw UTF-8 bytes rather than through PBEKeySpec, whose
     * char-to-byte conversion has differed between JCA providers.
     */
    fun deriveKey(password: String, salt: ByteArray): SymmetricKey {
        val mac = Mac.getInstance("HmacSHA512")
        // SecretKeySpec rejects an empty key. HMAC zero-pads keys to its block
        // size, so a single zero byte is the same key as an empty one.
        val passwordBytes = password.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA512"))
        // One block suffices: SHA-512 outputs 64 bytes and the key is 32.
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(10_000 - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return SymmetricKey(result.copyOf(32))
    }

    /**
     * Decrypt a blob whose IV/auth-tag arrive as base64 strings (file download
     * blobs and the `/user-get-key` test message).
     */
    fun decrypt(ciphertext: ByteArray, ivBase64: String, authTagBase64: String, key: SymmetricKey): ByteArray {
        val iv = runCatching { Base64.getDecoder().decode(ivBase64) }.getOrNull() ?: throw SyncEncryptionError.DecryptionFailed
        val tag = runCatching { Base64.getDecoder().decode(authTagBase64) }.getOrNull() ?: throw SyncEncryptionError.DecryptionFailed
        return decrypt(EncryptedData(iv, tag, ciphertext), key)
    }
}
