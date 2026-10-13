package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.Keychain
import io.github.traushb.actuali.services.L
import kotlinx.serialization.Serializable
import java.util.Base64

/** Server response from POST /user-get-key. */
@Serializable
data class ServerKeyInfo(
    val id: String,
    val salt: String,
    /** JSON string {value, meta:{keyId,algorithm,iv,authTag}}, or null for legacy keys. */
    val test: String? = null,
)

/** A validated, in-memory encryption key for a budget. */
data class LoadedKey(val keyId: String, val key: SymmetricKey)

sealed class EncryptionKeyError : Exception() {
    object InvalidPassword : EncryptionKeyError() {
        override val message: String get() = L("Incorrect encryption password.")
    }

    object UnsupportedLegacyKey : EncryptionKeyError() {
        override val message: String get() = L("This budget uses an old encryption format that isn't supported.")
    }

    object MalformedTestMessage : EncryptionKeyError() {
        override val message: String get() = L("The server returned an unreadable encryption key test.")
    }
}

/**
 * Derives, validates, persists, and retrieves a budget's E2EE key.
 * Persists the derived key (never the password) in the Keychain, keyed by fileId.
 */
object EncryptionKeyManager {
    @Serializable
    private data class StoredKey(val keyId: String, val base64Key: String)

    @Serializable
    private data class TestMessage(val value: String, val meta: Meta) {
        @Serializable
        data class Meta(val keyId: String, val algorithm: String, val iv: String, val authTag: String)
    }

    private fun keychainKey(fileId: String) = "encryptKey.$fileId"

    /** Derive the key from the password + server salt and validate it against the test message. */
    fun deriveAndValidate(password: String, keyInfo: ServerKeyInfo): LoadedKey {
        val testJSON = keyInfo.test ?: throw EncryptionKeyError.UnsupportedLegacyKey
        val test = runCatching { AppJson.decodeFromString(TestMessage.serializer(), testJSON) }.getOrNull()
            ?: throw EncryptionKeyError.MalformedTestMessage
        val ciphertext = runCatching { Base64.getDecoder().decode(test.value) }.getOrNull()
            ?: throw EncryptionKeyError.MalformedTestMessage

        // CRITICAL: salt is the UTF-8 bytes of the base64 salt string (matches Actual's Buffer.from(salt)).
        val key = SyncEncryption.deriveKey(password, keyInfo.salt.toByteArray(Charsets.UTF_8))
        try {
            SyncEncryption.decrypt(ciphertext, test.meta.iv, test.meta.authTag, key)
        } catch (_: Exception) {
            throw EncryptionKeyError.InvalidPassword
        }
        return LoadedKey(keyInfo.id, key)
    }

    fun store(loaded: LoadedKey, fileId: String) {
        val stored = StoredKey(loaded.keyId, Base64.getEncoder().encodeToString(loaded.key.bytes()))
        Keychain.set(AppJson.encodeToString(StoredKey.serializer(), stored), keychainKey(fileId))
    }

    fun load(fileId: String): LoadedKey? {
        val json = Keychain.get(keychainKey(fileId)) ?: return null
        val stored = runCatching { AppJson.decodeFromString(StoredKey.serializer(), json) }.getOrNull() ?: return null
        val keyData = runCatching { Base64.getDecoder().decode(stored.base64Key) }.getOrNull() ?: return null
        return LoadedKey(stored.keyId, SymmetricKey(keyData))
    }

    fun remove(fileId: String) = Keychain.remove(keychainKey(fileId))
}
