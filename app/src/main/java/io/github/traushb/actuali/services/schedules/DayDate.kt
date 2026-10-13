package io.github.traushb.actuali.services.schedules

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A timezone-free calendar day. All schedule math happens on these. */
data class DayDate(val year: Int, val month: Int, val day: Int) : Comparable<DayDate> {
    val yyyymmdd: Int get() = year * 10000 + month * 100 + day

    val iso: String get() = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

    override fun compareTo(other: DayDate): Int = yyyymmdd.compareTo(other.yyyymmdd)

    val localDate: LocalDate get() = LocalDate.of(year, month, day)

    /** "Sep 26, 2026" in the user's locale. */
    fun displayText(locale: Locale = Locale.getDefault()): String =
        localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    fun addingDays(days: Int): DayDate = of(localDate.plusDays(days.toLong()))

    /** 1 = Sunday ... 7 = Saturday. */
    val weekday: Int get() = localDate.dayOfWeek.value % 7 + 1

    val isWeekend: Boolean get() = weekday == 1 || weekday == 7

    /**
     * Whole calendar days from this day to [other]; negative when [other] is
     * earlier.
     */
    fun days(until: DayDate): Int = ChronoUnit.DAYS.between(localDate, until.localDate).toInt()

    /**
     * Shifted by whole months, clamped to the target month's length
     * (Jan 31 + 1 month = Feb 28), matching date-fns `addMonths` — which is
     * what upstream's upcoming-window math is built on.
     */
    fun addingMonths(months: Int): DayDate {
        val total = year * 12 + (month - 1) + months
        val y = Math.floorDiv(total, 12)
        val m = total - y * 12 + 1
        return DayDate(y, m, minOf(day, lastDay(y, m)))
    }

    companion object {
        fun of(date: LocalDate): DayDate = DayDate(date.year, date.monthValue, date.dayOfMonth)

        fun fromYYYYMMDD(value: Int): DayDate? {
            val y = value / 10000
            val m = (value / 100) % 100
            val d = value % 100
            if (m !in 1..12 || d < 1 || d > lastDay(y, m)) return null
            return DayDate(y, m, d)
        }

        /** Accepts "YYYY-MM-DD" or longer ISO strings (first 10 chars used). */
        fun fromIso(iso: String): DayDate? {
            val parts = iso.take(10).split("-")
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val d = parts[2].toIntOrNull() ?: return null
            if (m !in 1..12 || d < 1 || d > lastDay(y, m)) return null
            return DayDate(y, m, d)
        }

        fun lastDay(year: Int, month: Int): Int = java.time.YearMonth.of(year, month).lengthOfMonth()

        /** Today in the user's local calendar — the only place local time enters. */
        fun today(zone: ZoneId = ZoneId.systemDefault()): DayDate = of(LocalDate.now(zone))
    }
}
