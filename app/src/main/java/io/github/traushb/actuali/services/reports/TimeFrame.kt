package io.github.traushb.actuali.services.reports

import io.github.traushb.actuali.services.CanonicalDateParser
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Date helpers the report engines share; dates are UTC calendar days, as upstream computes them. */
internal fun LocalDate.monthStart(): LocalDate = withDayOfMonth(1)

internal fun LocalDate.monthEnd(): LocalDate = withDayOfMonth(lengthOfMonth())

internal val LocalDate.ymd: Int get() = year * 10000 + monthValue * 100 + dayOfMonth

internal fun localDate(ymd: Int): LocalDate? = runCatching { LocalDate.of(ymd / 10000, (ymd % 10000) / 100, ymd % 100) }.getOrNull()

/** Swift's `.weekday` numbering: 1 = Sunday … 7 = Saturday. */
internal val LocalDate.swiftWeekday: Int get() = dayOfWeek.value % 7 + 1

/** Year/month/day built the way Foundation's calendar does it: an overflowing day rolls into the next month. */
internal fun lenientDate(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, 1).plusDays((day - 1).toLong())

/**
 * Resolves a widget's `WidgetTimeFrame` to a concrete `(start, end)` date range.
 * Uses UTC days to match upstream behavior.
 */
object TimeFrame {
    /** `today` is the reference "now" — production passes the current UTC day, tests a fixed date. */
    fun resolve(tf: WidgetTimeFrame?, today: LocalDate): Pair<LocalDate, LocalDate> {
        if (tf == null) return currentMonth(today)
        return when (tf.mode) {
            null -> currentMonth(today)
            WidgetTimeFrame.Mode.SlidingWindow -> slidingWindow(tf, today)
            WidgetTimeFrame.Mode.YearToDate -> LocalDate.of(today.year, 1, 1) to today
            WidgetTimeFrame.Mode.LastMonth -> {
                val lastMonthEnd = today.monthStart().minusDays(1)
                lastMonthEnd.monthStart() to lastMonthEnd
            }
            WidgetTimeFrame.Mode.LastYear -> LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
            WidgetTimeFrame.Mode.PriorYearToDate -> LocalDate.of(today.year - 1, 1, 1) to lenientDate(today.year - 1, today.monthValue, today.dayOfMonth)
            WidgetTimeFrame.Mode.Static -> (parseRangeStart(tf.start) ?: currentMonth(today).first) to (parseRangeEnd(tf.end) ?: today)
            // Upstream's "full" mode honors the stored start but ALWAYS uses the
            // current month as the end (regardless of any stored end). This is
            // why upstream's "Spent All Time" widget shows "Apr 2025 - May 2026"
            // even when end is stored as "2026-04": the stored end is residual
            // from when the widget was edited and is intentionally ignored.
            WidgetTimeFrame.Mode.Full -> (parseRangeStart(tf.start) ?: LocalDate.of(1900, 1, 1)) to today.monthEnd()
        }
    }

    /**
     * "Sliding window" preserves the configured window size and slides it
     * forward each month relative to today. Stored start/end are YYYY-MM
     * strings marking the window when configured; we compute the difference
     * between today's month and the stored end month and shift both by that.
     */
    private fun slidingWindow(tf: WidgetTimeFrame, today: LocalDate): Pair<LocalDate, LocalDate> {
        val storedStart = tf.start?.let(CanonicalDateParser::parseMonthStart)
        val storedEnd = tf.end?.let(CanonicalDateParser::parseMonthStart)
        if (storedStart == null || storedEnd == null) return currentMonth(today)
        val monthsDiff = ChronoUnit.MONTHS.between(storedEnd, today.monthStart())
        return storedStart.plusMonths(monthsDiff) to storedEnd.plusMonths(monthsDiff).monthEnd()
    }

    private fun currentMonth(today: LocalDate) = today.monthStart() to today.monthEnd()

    /** YYYY-MM snaps to first-of-month, YYYY-MM-DD is used as-is. */
    internal fun parseRangeStart(s: String?): LocalDate? = s?.let(CanonicalDateParser::parseMonthOrDay)

    /**
     * YYYY-MM expands to end-of-month so the range is inclusive of the entire
     * month, matching upstream behavior. YYYY-MM-DD is used as-is.
     */
    internal fun parseRangeEnd(s: String?): LocalDate? {
        val date = s?.let(CanonicalDateParser::parseMonthOrDay) ?: return null
        return if (s.length == 7) date.monthEnd() else date
    }
}
