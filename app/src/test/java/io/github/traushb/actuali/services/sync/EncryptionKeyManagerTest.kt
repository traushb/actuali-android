package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.support.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.UUID

class EncryptionKeyManagerTest {
    @Serializable
    private data class Fixture(
        val password: String,
        val wrongPassword: String,
        val salt: String,
        val keyId: String,
        val derivedKeyBase64: String,
        val test: String,
    )

    private fun loadFixture(): Fixture = AppJson.decodeFromString(Fixture.serializer(), Fixtures.text("encryption-key-fixture.json"))

    @Test
    fun derivesKeyMatchingActualConvention() {
        val fx = loadFixture()
        val key = SyncEncryption.deriveKey(fx.password, fx.salt.toByteArray(Charsets.UTF_8))
        // Locks in the UTF-8-salt convention vs Actual.
        assertEquals(fx.derivedKeyBase64, Base64.getEncoder().encodeToString(key.bytes()))
    }

    @Test
    fun validatesCorrectPassword() {
        val fx = loadFixture()
        val loaded = EncryptionKeyManager.deriveAndValidate(fx.password, ServerKeyInfo(fx.keyId, fx.salt, fx.test))
        assertEquals(fx.keyId, loaded.keyId)
    }

    @Test
    fun rejectsWrongPassword() {
        val fx = loadFixture()
        assertThrows(EncryptionKeyError.InvalidPassword::class.java) {
            EncryptionKeyManager.deriveAndValidate(fx.wrongPassword, ServerKeyInfo(fx.keyId, fx.salt, fx.test))
        }
    }

    @Test
    fun rejectsLegacyNilTest() {
        val fx = loadFixture()
        assertThrows(EncryptionKeyError.UnsupportedLegacyKey::class.java) {
            EncryptionKeyManager.deriveAndValidate(fx.password, ServerKeyInfo(fx.keyId, fx.salt, null))
        }
    }

    @Test
    fun keychainRoundTrip() {
        val fileId = "test-file-${UUID.randomUUID()}"
        val loaded = LoadedKey("kid", SymmetricKey(ByteArray(32) { (it * 7).toByte() }))
        EncryptionKeyManager.store(loaded, fileId)
        assertEquals(loaded, EncryptionKeyManager.load(fileId))
        EncryptionKeyManager.remove(fileId)
        assertNull(EncryptionKeyManager.load(fileId))
    }
}
