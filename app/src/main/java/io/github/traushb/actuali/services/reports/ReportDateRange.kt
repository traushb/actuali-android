package io.github.traushb.actuali.services.reports

import java.time.LocalDate

/**
 * Resolves a custom report's date range to concrete (start, end) dates.
 * Mirrors upstream getLiveRange()/getSpecificRange() (desktop-client
 * reports). Dynamic ranges are computed relative to `today`; "All time" spans
 * the actual transaction history.
 */
object ReportDateRange {
    fun resolve(
        dateRange: String?,
        dateStatic: Boolean,
        startDate: String?,
        endDate: String?,
        includeCurrent: Boolean,
        earliest: LocalDate?,
        latest: LocalDate?,
        today: LocalDate,
        firstDayOfWeekIdx: Int,
    ): Pair<LocalDate, LocalDate> {
        val allTime = (earliest ?: today) to (latest ?: today)
        val floor = allTime.first

        if (dateStatic) return (TimeFrame.parseRangeStart(startDate) ?: floor) to (TimeFrame.parseRangeEnd(endDate) ?: today)

        return when (dateRange) {
            "All time", null -> allTime
            "Year to date" -> maxOf(floor, LocalDate.of(today.year, 1, 1)) to today
            "Prior year to date" -> maxOf(floor, LocalDate.of(today.year - 1, 1, 1)) to lenientDate(today.year - 1, today.monthValue, today.dayOfMonth)
            "Last year" -> maxOf(floor, LocalDate.of(today.year - 1, 1, 1)) to LocalDate.of(today.year - 1, 12, 31)
            "Last 30 days" -> maxOf(floor, today.minusDays(29)) to today
            "This month" -> monthsBack(0, 0, today, floor)
            "Last month" -> monthsBack(1, if (includeCurrent) 1 else 0, today, floor)
            "Last 3 months" -> monthsBack(3, if (includeCurrent) 3 else 2, today, floor)
            "Last 6 months" -> monthsBack(6, if (includeCurrent) 6 else 5, today, floor)
            "Last 12 months" -> monthsBack(12, if (includeCurrent) 12 else 11, today, floor)
            "This week" -> {
                val start = weekStart(today, firstDayOfWeekIdx)
                maxOf(floor, start) to start.plusDays(6)
            }
            "Last week" -> {
                val start = weekStart(today, firstDayOfWeekIdx).minusDays(7)
                val weeksForward = if (includeCurrent) 1L else 0L
                maxOf(floor, start) to start.plusDays(6 + weeksForward * 7)
            }
            // Unknown/newer range names degrade to All time rather than blanking the widget.
            else -> allTime
        }
    }

    /**
     * Upstream getSpecificRange for Month type: start = first of the month
     * [offset] months before today; end = end of month [add] months after
     * the start.
     */
    private fun monthsBack(offset: Long, add: Long, today: LocalDate, earliest: LocalDate): Pair<LocalDate, LocalDate> {
        val startMonth = today.monthStart().minusMonths(offset)
        return maxOf(earliest, startMonth) to startMonth.plusMonths(add).monthEnd()
    }

    /** First day of the week containing [date], with 0 = Sunday … 6 = Saturday. */
    fun weekStart(date: LocalDate, firstDayOfWeekIdx: Int): LocalDate {
        val weekday = date.dayOfWeek.value % 7 // 0-based Sunday
        val idx = if (firstDayOfWeekIdx in 0..6) firstDayOfWeekIdx else 0
        return date.minusDays(((weekday - idx + 7) % 7).toLong())
    }
}
