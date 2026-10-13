package io.github.traushb.actuali.services.schedules

import kotlin.math.max

/** A schedule's lifecycle state, mirroring loot-core `getStatus`. */
enum class ScheduleStatus(val rawValue: String) {
    Completed("completed"),
    Paid("paid"),
    Due("due"),
    Upcoming("upcoming"),
    Missed("missed"),
    Scheduled("scheduled"),
}

/**
 * How long the "upcoming" window is, mirroring loot-core `getUpcomingDays`.
 *
 * The stored value is a string because upstream overloads it: a bare number is
 * a literal day count, `currentMonth`/`oneMonth` are named windows, and
 * `N-day|week|month|year` is a compound form. A schedule's own
 * `custom_upcoming_length` overrides the budget-wide preference.
 */
object ScheduleUpcomingLength {
    /** Upstream's default when no preference is set. */
    const val FALLBACK = "7"

    fun days(raw: String?, today: DayDate = DayDate.today()): Int {
        val value = raw?.takeIf { it.isNotEmpty() } ?: FALLBACK
        // Upstream measures the month-based windows from the FIRST of the
        // current month, not from `today` (it passes a "YYYY-MM" month string
        // into a day-difference helper). Reproduced deliberately so the badge
        // in Actuali and the badge on the web agree.
        val monthStart = DayDate(today.year, today.month, 1)
        return when (value) {
            "currentMonth" -> DayDate.lastDay(today.year, today.month) - today.day
            "oneMonth" -> monthStart.days(monthStart.addingMonths(1))
            else -> {
                if (!value.contains("-")) {
                    // Upstream returns NaN for unparseable input, which silently
                    // makes every comparison false. Fall back to the default
                    // window instead — a wrong badge beats an unreadable one.
                    return value.toIntOrNull() ?: 7
                }
                val parts = value.split("-", limit = 2)
                val parsed = parts.getOrNull(0)?.toIntOrNull() ?: return 7
                val amount = max(1, parsed)
                when (parts.getOrNull(1)) {
                    "day" -> amount
                    "week" -> amount * 7
                    "month" -> monthStart.days(today.addingMonths(amount)) + 1
                    "year" -> monthStart.days(today.addingMonths(amount * 12)) + 1
                    else -> 7
                }
            }
        }
    }
}

object ScheduleStatusCalculator {
    /**
     * Port of loot-core `getStatus`. The branch ORDER is the contract:
     * completed wins over paid, paid wins over any date comparison.
     */
    fun status(
        nextDate: DayDate?,
        completed: Boolean,
        hasTransaction: Boolean,
        upcomingLength: String?,
        today: DayDate = DayDate.today(),
    ): ScheduleStatus {
        if (completed) return ScheduleStatus.Completed
        if (hasTransaction) return ScheduleStatus.Paid
        // A schedule whose next-date row is missing or unreadable still has to
        // render; upstream can't reach this case because its view guarantees
        // the column.
        if (nextDate == null) return ScheduleStatus.Scheduled
        if (nextDate == today) return ScheduleStatus.Due
        val window = today.addingDays(ScheduleUpcomingLength.days(upcomingLength, today))
        if (nextDate > today && nextDate <= window) return ScheduleStatus.Upcoming
        if (nextDate < today) return ScheduleStatus.Missed
        return ScheduleStatus.Scheduled
    }

    /**
     * Earliest date a transaction may carry and still cover this occurrence.
     * Recurring schedules deliberately differ from loot-core by allowing a
     * frequency-bounded early payment window.
     */
    fun occurrenceMatchStartDate(
        nextDate: DayDate,
        dateOp: String?,
        postsTransaction: Boolean,
        frequency: RecurConfig.Frequency? = null,
    ): DayDate {
        if (frequency != null) {
            return when (frequency) {
                RecurConfig.Frequency.Daily -> nextDate
                RecurConfig.Frequency.Weekly -> nextDate.addingDays(-2)
                RecurConfig.Frequency.Monthly, RecurConfig.Frequency.Yearly -> nextDate.addingDays(-4)
            }
        }
        if (dateOp == "is") return nextDate
        if (postsTransaction) return nextDate
        return nextDate.addingDays(-2)
    }
}
