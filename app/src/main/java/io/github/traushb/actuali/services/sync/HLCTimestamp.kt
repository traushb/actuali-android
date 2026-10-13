package io.github.traushb.actuali.services.sync

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hybrid Logical Clock timestamp.
 * Format: 2025-12-09T14:30:45.123Z-0000-9f66d38cba0ef956
 *         |ISO 8601 timestamp    |cntr|node ID (16 hex)
 */
data class HLCTimestamp(
    /** Wall clock time in milliseconds. */
    val millis: Long,
    /** Logical counter for same-millisecond events (0...0xFFFF). */
    val counter: Int,
    /** 16-char hex device ID. */
    val node: String,
) : Comparable<HLCTimestamp> {
    override fun toString(): String {
        val iso = ISO.format(Instant.ofEpochMilli(millis))
        val counterHex = String.format(Locale.ROOT, "%04X", counter)
        // Ensure node is 16 chars, padded with leading zeros
        val paddedNode = "0".repeat(maxOf(0, 16 - node.length)) + node.takeLast(16)
        return "$iso-$counterHex-$paddedNode"
    }

    /** Lexicographic comparison of the string representation, as upstream orders them. */
    override fun compareTo(other: HLCTimestamp): Int = toString().compareTo(other.toString())

    /** Hash as a signed 32-bit int, matching JavaScript's signed XOR behavior. */
    fun hash(): Int = MurmurHash3.hash(toString())

    companion object {
        /** `ISO8601DateFormatter` with fractional seconds: always UTC, always 3 digits. */
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

        val zero = HLCTimestamp(0, 0, "0000000000000000")
        val max = HLCTimestamp(253_402_300_799_999, 0xffff, "FFFFFFFFFFFFFFFF")

        /** Parse a timestamp string into an HLCTimestamp; null if invalid. */
        fun parse(string: String): HLCTimestamp? {
            // Format: 2015-04-24T22:23:42.123Z-1000-0123456789ABCDEF
            val parts = string.split("-").filter { it.isNotEmpty() }
            if (parts.size != 5) return null
            val iso = "${parts[0]}-${parts[1]}-${parts[2]}"
            val millis = parseIsoMillis(iso) ?: return null
            // Validate millis range (1970 to 9999)
            if (millis < 0 || millis >= 253_402_300_800_000) return null
            val counter = parts[3].toIntOrNull(16)?.takeIf { it in 0..0xffff && parts[3].all { c -> c.isLetterOrDigit() } } ?: return null
            val node = parts[4]
            if (node.length > 16) return null
            return HLCTimestamp(millis, counter, node)
        }

        /**
         * `ISO8601DateFormatter` with `.withInternetDateTime, .withFractionalSeconds`:
         * requires the fraction and a zone designator.
         */
        private fun parseIsoMillis(iso: String): Long? {
            if (!Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+(Z|[+-]\d{2}:?\d{2})""").matches(iso)) return null
            return runCatching { java.time.OffsetDateTime.parse(iso.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")).toInstant().toEpochMilli() }
                .getOrNull()
        }

        /**
         * Minutes since the Unix epoch for a timestamp string, read straight
         * off its ISO-8601 prefix (`YYYY-MM-DDTHH:MM`, always UTC).
         *
         * The Merkle trie only ever needs the minute, never the full
         * timestamp, so a pass over a whole message log skips full parsing.
         * Returns null if the prefix isn't well formed.
         */
        fun minutesSinceEpoch(string: String): Long? {
            var index = 0
            fun field(digits: Int, separator: Boolean): Int? {
                var value = 0
                repeat(digits) {
                    val c = string.getOrNull(index++) ?: return null
                    if (c !in '0'..'9') return null
                    value = value * 10 + (c - '0')
                }
                if (separator && string.getOrNull(index++) == null) return null
                return value
            }
            val year = field(4, true) ?: return null
            val month = field(2, true) ?: return null
            val day = field(2, true) ?: return null
            val hour = field(2, true) ?: return null
            val minute = field(2, false) ?: return null
            if (month !in 1..12 || day !in 1..31 || hour >= 24 || minute >= 60) return null
            return daysSinceEpoch(year, month, day) * 1440 + (hour * 60 + minute)
        }

        /**
         * Days from 1970-01-01 to a proleptic-Gregorian date (Howard Hinnant's
         * `days_from_civil`), shifting the year to start in March so the leap
         * day lands at the end of the cycle.
         */
        private fun daysSinceEpoch(year: Int, month: Int, day: Int): Long {
            val shiftedYear = year - if (month <= 2) 1 else 0
            val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
            val yearOfEra = shiftedYear - era * 400
            val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era.toLong() * 146_097 + dayOfEra - 719_468
        }

        /** A "since" timestamp for a given ISO date string. */
        fun since(isoString: String): String = "$isoString-0000-0000000000000000"
    }
}
