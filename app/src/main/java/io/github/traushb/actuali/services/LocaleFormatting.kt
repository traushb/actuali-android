package io.github.traushb.actuali.services

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Locale formatting the iOS app gets from Foundation formatters. */
object LocaleFormatting {
    /**
     * "1st", "15th" — NumberFormatter's ordinal style for the catalog's
     * languages (the JVM has no ordinal formatter of its own).
     */
    fun ordinal(value: Int, locale: Locale = Locale.getDefault()): String = when (Strings.tag(locale)) {
        "de" -> "$value."
        "es" -> "$value.º"
        "fr" -> if (value == 1) "1er" else "${value}e"
        "it", "pt-BR" -> "${value}º"
        "nl" -> "${value}e"
        else -> {
            val mod100 = value % 100
            val suffix = if (mod100 in 11..13) "th" else when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            "$value$suffix"
        }
    }

    /** 1 = Sunday ... 7 = Saturday, matching `DayDate.weekday`. */
    fun weekdayName(weekday: Int, locale: Locale = Locale.getDefault(), style: TextStyle = TextStyle.FULL): String {
        if (weekday !in 1..7) return ""
        // java.time numbers Monday 1 ... Sunday 7.
        val dayOfWeek = DayOfWeek.of(if (weekday == 1) 7 else weekday - 1)
        return dayOfWeek.getDisplayName(style, locale)
    }

    fun monthName(month: Int, locale: Locale = Locale.getDefault(), style: TextStyle = TextStyle.FULL_STANDALONE): String =
        if (month in 1..12) Month.of(month).getDisplayName(style, locale) else ""

    /**
     * A localized pattern for a skeleton like "MMMd" — Foundation's
     * `setLocalizedDateFormatFromTemplate`. Android supplies the real CLDR
     * pattern; the fallback covers JVM tests.
     */
    fun bestPattern(skeleton: String, locale: Locale = Locale.getDefault()): String =
        runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
            ?: when (skeleton) {
                "MMMd" -> "MMM d"
                "MMMMd" -> "MMMM d"
                "MMMyyyy", "yyyyMMM", "yMMM" -> "MMM yyyy"
                "MMMMyyyy", "yyyyMMMM", "yMMMM" -> "MMMM yyyy"
                "EEEMMMd" -> "EEE, MMM d"
                else -> skeleton
            }

    /**
     * Resolving a skeleton goes through ICU and parsing the pattern isn't free,
     * and rows format dates on every recomposition — so each skeleton and
     * locale builds its formatter once. Formatters are immutable and thread-safe.
     */
    private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    fun format(date: LocalDate, skeleton: String, locale: Locale = Locale.getDefault()): String =
        date.format(formatters.getOrPut(skeleton to locale) { DateTimeFormatter.ofPattern(bestPattern(skeleton, locale), locale) })
}
