package io.github.traushb.actuali.services.sync

import java.math.BigDecimal

/** A CRDT message representing a single field change. */
data class CRDTMessage(
    val timestamp: HLCTimestamp,
    /** Table name: "transactions", "accounts", etc. */
    val dataset: String,
    /** Row ID (UUID). */
    val row: String,
    /** Field name: "amount", "date", etc. */
    val column: String,
    /** Serialized value: "N:1234", "S:text", "0:". */
    val value: String,
)

/** Value serialization matching Actual's format. */
object CRDTValue {
    fun serialize(value: Any?): String = when (value) {
        null -> "0:"
        is Int -> "N:$value"
        is Long -> "N:$value"
        // Full precision — upstream parses "N:" payloads with parseFloat,
        // so fractional values (e.g. coordinates) must not be truncated.
        is Double -> "N:${swiftDescription(value)}"
        is String -> "S:$value"
        is Boolean -> "N:${if (value) 1 else 0}"
        else -> "0:"
    }

    /**
     * Deserialize a value from CRDT storage to an SQLite-bindable value:
     * Long, Double, String or null.
     */
    fun deserialize(value: String): Any? {
        if (value.length < 2) return null
        val content = value.substring(2)
        return when (value[0]) {
            '0' -> null
            'N' -> content.toLongOrNull() ?: content.toDoubleOrNull()?.takeIf { content.isSwiftDouble() }
            'S' -> content
            else -> null
        }
    }

    /** Swift's `Double(String)` refuses Kotlin-only spellings like "1f" or "0x1p3". */
    private fun String.isSwiftDouble(): Boolean =
        isNotEmpty() && last() !in "fFdD" && !contains("x", ignoreCase = true)

    /**
     * Swift's `Double.description`, which is what the iOS client writes into
     * "N:" payloads: shortest round-trip digits, positional with a ".0" on
     * whole values, switching to exponent form only below 1e-4 or above 2^54.
     * Upstream reads either spelling with parseFloat; matching it keeps both
     * native clients' messages identical.
     */
    fun swiftDescription(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
        // Double.toString yields the shortest digits that round-trip.
        val decimal = BigDecimal(value.toString())
        val magnitude = Math.abs(value)
        if (magnitude < 1e-4 || magnitude > 18014398509481984.0) {
            val unscaled = decimal.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val exponent = decimal.precision() - decimal.scale() - 1
            val mantissa = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            val sign = if (value < 0) "-" else ""
            val expSign = if (exponent < 0) "-" else "+"
            return "$sign${mantissa}e$expSign${Math.abs(exponent).toString().padStart(2, '0')}"
        }
        val plain = decimal.stripTrailingZeros().toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }
}

/** Models that can be synced via CRDT. */
interface CRDTSyncable {
    /** The database table name. */
    val datasetName: String

    /** The row ID. */
    val id: String

    /** Field name -> value for all syncable fields. */
    val syncableFields: Map<String, Any?>
}
