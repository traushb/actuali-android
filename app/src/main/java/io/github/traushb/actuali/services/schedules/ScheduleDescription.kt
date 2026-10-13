package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.DateStyle
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.ReportStrings
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

/** Human-readable text for a schedule's recurrence and status. */
object ScheduleDescription {
    fun statusLabel(status: ScheduleStatus, locale: Locale = Locale.getDefault()): String = when (status) {
        ScheduleStatus.Completed -> ReportStrings.text("Completed", locale)
        ScheduleStatus.Paid -> ReportStrings.text("Paid", locale)
        ScheduleStatus.Due -> ReportStrings.text("Due", locale)
        ScheduleStatus.Upcoming -> ReportStrings.text("Upcoming", locale)
        ScheduleStatus.Missed -> ReportStrings.text("Missed", locale)
        ScheduleStatus.Scheduled -> ReportStrings.text("Scheduled", locale)
    }

    /** One-line summary of a schedule's date condition, for the list row. */
    fun dateSummary(condition: ScheduleDateCondition?, locale: Locale = Locale.getDefault()): String = when (condition) {
        is ScheduleDateCondition.Fixed -> mediumDate(condition.day, locale)
        is ScheduleDateCondition.Recurring -> recurring(condition.config, locale)
        ScheduleDateCondition.Unsupported -> ReportStrings.text("Unsupported repeat", locale)
        null -> ReportStrings.text("No date", locale)
    }

    fun recurring(config: RecurConfig, locale: Locale = Locale.getDefault()): String {
        val interval = max(1, config.interval)

        var endSuffix = ""
        when (config.endMode) {
            "after_n_occurrences" -> {
                val count = config.endOccurrences ?: 1
                endSuffix = if (count == 1) ReportStrings.text("once", locale) else ReportStrings.localized("%lld times", count.toLong(), locale = locale)
            }
            "on_date" -> config.endDate?.let { endSuffix = ReportStrings.localized("until %@", mediumDate(it, locale), locale = locale) }
        }

        val weekendSuffix = if (!config.skipWeekend) "" else if (config.weekendSolveMode == "after") {
            ReportStrings.text("(after weekend)", locale)
        } else {
            ReportStrings.text("(before weekend)", locale)
        }

        var suffix = ""
        if (endSuffix.isNotEmpty()) suffix += ReportStrings.localized(", %@", endSuffix, locale = locale)
        if (weekendSuffix.isNotEmpty()) suffix += " $weekendSuffix"

        val n = interval.toLong()
        val body = when (config.frequency) {
            RecurConfig.Frequency.Daily ->
                if (interval == 1) ReportStrings.text("Every day", locale) else ReportStrings.localized("Every %lld days", n, locale = locale)
            RecurConfig.Frequency.Weekly -> {
                val day = LocaleFormatting.weekdayName(config.start.weekday, locale)
                if (interval == 1) {
                    ReportStrings.localized("Every week on %@", day, locale = locale)
                } else {
                    ReportStrings.localized("Every %lld weeks on %@", n, day, locale = locale)
                }
            }
            RecurConfig.Frequency.Monthly -> {
                val range = monthlyRange(config, locale)
                if (range.isEmpty()) {
                    val day = LocaleFormatting.ordinal(config.start.day, locale)
                    if (interval == 1) {
                        ReportStrings.localized("Every month on the %@", day, locale = locale)
                    } else {
                        ReportStrings.localized("Every %lld months on the %@", n, day, locale = locale)
                    }
                } else if (interval == 1) {
                    ReportStrings.localized("Every month on %@", range, locale = locale)
                } else {
                    ReportStrings.localized("Every %lld months on %@", n, range, locale = locale)
                }
            }
            RecurConfig.Frequency.Yearly -> {
                val day = LocaleFormatting.format(config.start.localDate, "MMMd", locale)
                if (interval == 1) {
                    ReportStrings.localized("Every year on %@", day, locale = locale)
                } else {
                    ReportStrings.localized("Every %lld years on %@", n, day, locale = locale)
                }
            }
        }
        return (body + suffix).trim { it == ' ' || it == '\t' }
    }

    /**
     * The "15th and last day" / "1st and 3rd Monday" fragment. Empty when the
     * config carries no patterns (a plain monthly recurrence).
     */
    private fun monthlyRange(config: RecurConfig, locale: Locale): String {
        if (config.patterns.isEmpty()) return ""
        // Weekday patterns sort ahead of day-of-month patterns, then by value.
        // `-1` means "last" and is pulled out so it always lands at the end.
        val sorted = config.patterns.filter { it.value != -1 }
            .sortedWith(compareBy<RecurConfig.Pattern> { if (it.type == "day") 1 else 0 }.thenBy { it.value })
        val patterns = sorted + config.patterns.filter { it.value == -1 }
        val first = patterns.firstOrNull() ?: return ""

        // When every pattern names the same weekday ("1st and 3rd Monday"),
        // the weekday is said once at the end instead of after each ordinal.
        val uniqueTypes = patterns.map { it.type }.toSet()
        val isSameDay = uniqueTypes.size == 1 && "day" !in uniqueTypes

        val parts = patterns.map { pattern ->
            if (pattern.type == "day") {
                if (pattern.value == -1) ReportStrings.text("last day", locale) else LocaleFormatting.ordinal(pattern.value, locale)
            } else {
                val dayName = if (isSameDay) "" else " " + weekdayName(pattern.type, locale)
                if (pattern.value == -1) ReportStrings.text("last", locale) + dayName else LocaleFormatting.ordinal(pattern.value, locale) + dayName
            }
        }
        var range = if (parts.size > 2) {
            parts.dropLast(1).joinToString(", ") + ReportStrings.text(", and ", locale) + parts.last()
        } else {
            parts.joinToString(ReportStrings.text(" and ", locale))
        }
        if (isSameDay) range += " " + weekdayName(first.type, locale)
        return range
    }

    /** "SU".."SA" — the pattern-type codes used in a recurrence config. */
    fun weekdayName(code: String, locale: Locale = Locale.getDefault()): String {
        val weekdays = mapOf("SU" to 1, "MO" to 2, "TU" to 3, "WE" to 4, "TH" to 5, "FR" to 6, "SA" to 7)
        return weekdays[code]?.let { LocaleFormatting.weekdayName(it, locale) } ?: code
    }

    fun shortMonthName(month: Int, locale: Locale = Locale.getDefault()): String = LocaleFormatting.monthName(month, locale, TextStyle.SHORT)

    /** Locale-formatted medium date, matching how transaction rows read. */
    fun mediumDate(day: DayDate, locale: Locale = Locale.getDefault()): String =
        Transaction.formattedDate(day.yyyymmdd, DateStyle.Abbreviated, locale)
}
