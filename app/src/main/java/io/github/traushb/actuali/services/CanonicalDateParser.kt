package io.github.traushb.actuali.services

import java.time.LocalDate

/** Actual's canonical `YYYY`, `YYYY-MM` and `YYYY-MM-DD` strings, validated strictly. */
object CanonicalDateParser {
    fun parse(string: String): LocalDate? {
        val length = string.length
        if (length != 4 && length != 7 && length != 10) return null
        if (!string.substring(0, 4).all { it in '0'..'9' }) return null
        if (length >= 7 && (string[4] != '-' || !string.substring(5, 7).all { it in '0'..'9' })) return null
        if (length == 10 && (string[7] != '-' || !string.substring(8, 10).all { it in '0'..'9' })) return null
        val year = string.substring(0, 4).toInt()
        val month = if (length == 4) 1 else string.substring(5, 7).toInt()
        val day = if (length == 10) string.substring(8, 10).toInt() else 1
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    fun parseMonthOrDay(string: String): LocalDate? =
        if (string.length == 7 || string.length == 10) parse(string) else null

    fun parseMonthStart(string: String): LocalDate? = parseMonthOrDay(string)?.withDayOfMonth(1)
}
