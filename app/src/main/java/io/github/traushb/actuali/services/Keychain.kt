package io.github.traushb.actuali.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where [Keychain] keeps its values. */
interface SecretStore {
    fun get(key: String): String?

    fun set(key: String, value: String)

    fun remove(key: String)
}

/** Process-lifetime store: the default until the app installs the Keystore-backed one, and what tests use. */
class InMemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/** Minimal secret storage for small values (auth tokens, derived encryption keys). */
object Keychain {
    @Volatile
    var store: SecretStore = InMemorySecretStore()

    fun set(value: String, key: String) = store.set(key, value)

    /** The stored string, or null when missing or unreadable. */
    fun get(key: String): String? = runCatching { store.get(key) }.getOrNull()

    /** Remove the stored item. No-op if missing. */
    fun remove(key: String) = store.remove(key)
}

/**
 * The app's [SecretStore]: values AES-GCM encrypted under a hardware-backed
 * Android Keystore key, ciphertext kept in private preferences. No user
 * authentication on the key, so background refresh can read the token the
 * way iOS reads its after-first-unlock keychain items.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("keychain", Context.MODE_PRIVATE)

    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
    }

    override fun get(key: String): String? {
        val blob = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, blob, 0, IV_SIZE))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE).decodeToString()
    }

    override fun set(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        // commit, not apply: callers treat a stored secret as durable, as a keychain write is.
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    private companion object {
        const val ALIAS = "actuali.keychain"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
