package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.schedules.DayDate
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

/**
 * String month/date arithmetic mirroring loot-core `shared/months.ts` as the
 * goal-template engine uses it. Upstream mixes "yyyy-MM" months and
 * "yyyy-MM-dd" days and compares them lexicographically; these helpers keep
 * those exact semantics (including `addMonths` collapsing a day string to a
 * month string) so ported engine code behaves identically.
 */
object BudgetMonthMath {
    /** JS `Math.round`: half rounds toward +∞ (matters for negatives). */
    fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    /** Whole currency units → cents, upstream `amountToInteger(x, 2)`. */
    fun amountToInteger(amount: Double): Long = jsRound(amount * 100)

    fun yearAndMonth(value: String): Pair<Int, Int>? {
        val parts = value.split("-").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return year to month
    }

    /** "2026-08" or "2026-08-15" → 202608; 0 when malformed. */
    fun monthInt(value: String): Int = yearAndMonth(value)?.let { (year, month) -> year * 100 + month } ?: 0

    /** Month-or-day string → "yyyy-MM", like upstream `addMonths`. */
    fun addMonths(value: String, n: Int): String {
        val (year, month) = yearAndMonth(value) ?: return value
        val total = year * 12 + (month - 1) + n
        return String.format(Locale.ROOT, "%04d-%02d", total / 12, total % 12 + 1)
    }

    fun subMonths(value: String, n: Int): String = addMonths(value, -n)

    fun nextMonth(value: String): String = addMonths(value, 1)

    fun prevMonth(value: String): String = addMonths(value, -1)

    fun differenceInCalendarMonths(a: String, b: String): Int {
        val first = yearAndMonth(a) ?: return 0
        val second = yearAndMonth(b) ?: return 0
        return (first.first * 12 + first.second) - (second.first * 12 + second.second)
    }

    fun firstDayOfMonth(value: String): String {
        val (year, month) = yearAndMonth(value) ?: return value
        return String.format(Locale.ROOT, "%04d-%02d-01", year, month)
    }

    /** Month-or-day string → DayDate (day 1 for months), upstream `_parse`. */
    fun day(value: String): DayDate? {
        DayDate.fromIso(value)?.let { return it }
        val (year, month) = yearAndMonth(value) ?: return null
        return DayDate(year, month, 1)
    }

    fun addDays(value: String, n: Int): String = day(value)?.addingDays(n)?.iso ?: value

    fun addWeeks(value: String, n: Int): String = addDays(value, 7 * n)

    fun subWeeks(value: String, n: Int): String = addDays(value, -7 * n)

    fun subDays(value: String, n: Int): String = addDays(value, -n)

    /** Days in the month of [value] — upstream's `differenceInCalendarDays(addMonths(m, 1), m)`. */
    fun daysInMonth(value: String): Int = yearAndMonth(value)?.let { (y, m) -> DayDate.lastDay(y, m) } ?: 30

    fun currentMonth(now: LocalDate = LocalDate.now()): String =
        String.format(Locale.ROOT, "%04d-%02d", now.year, now.monthValue)
}
