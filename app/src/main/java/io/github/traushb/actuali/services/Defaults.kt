package io.github.traushb.actuali.services

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * `UserDefaults`: small typed settings that survive relaunches. Reads come
 * from an in-memory mirror, so they are cheap enough for the UI thread.
 */
interface Defaults {
    fun get(key: String): Any?

    /** Stores [value] (String, Boolean, Int, Long, Float or Double); null removes the key. */
    fun set(key: String, value: Any?)

    companion object {
        /** `UserDefaults.standard`. The app swaps in the SharedPreferences-backed store at launch. */
        @Volatile
        var standard: Defaults = InMemoryDefaults()
    }
}

fun Defaults.integer(key: String): Int = (get(key) as? Number)?.toInt() ?: 0

fun Defaults.long(key: String): Long = (get(key) as? Number)?.toLong() ?: 0

fun Defaults.double(key: String): Double = (get(key) as? Number)?.toDouble() ?: 0.0

fun Defaults.bool(key: String): Boolean = get(key) as? Boolean ?: false

fun Defaults.string(key: String): String? = get(key) as? String

/** `object(forKey:) != nil` — whether the key was ever set. */
fun Defaults.contains(key: String): Boolean = get(key) != null

fun Defaults.remove(key: String) = set(key, null)

class InMemoryDefaults : Defaults {
    private val values = ConcurrentHashMap<String, Any>()

    override fun get(key: String): Any? = values[key]

    override fun set(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class SharedPreferencesDefaults(private val prefs: SharedPreferences) : Defaults {
    private val mirror = ConcurrentHashMap<String, Any>().apply {
        prefs.all.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    override fun get(key: String): Any? = mirror[key]

    override fun set(key: String, value: Any?) {
        val editor = prefs.edit()
        when (value) {
            null -> {
                mirror.remove(key)
                editor.remove(key)
            }
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            // SharedPreferences has no doubles; keep the full precision as bits.
            is Double -> editor.putString(key, "double:" + value.toRawBits())
            else -> editor.putString(key, value.toString())
        }
        if (value != null) mirror[key] = value
        editor.apply()
    }

    init {
        // Restore doubles written in the bit form above.
        for ((key, value) in mirror) {
            if (value is String && value.startsWith("double:")) {
                value.removePrefix("double:").toLongOrNull()?.let { mirror[key] = Double.fromBits(it) }
            }
        }
    }
}
