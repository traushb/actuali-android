package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.WidgetRuleCondition
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.services.reports.monthEnd
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs

/** Mirrors upstream `CalendarWidget['meta']` (CalendarCard.tsx). */
@Serializable
data class CalendarMeta(
    val name: String? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
)

/**
 * One grid cell. [dayOfMonth] is null for the leading/trailing padding cells
 * that align the month grid to the configured first weekday.
 */
data class CalendarDayCell(
    val dayOfMonth: Int?,
    /** absolute value */
    val incomeCents: Long,
    /** absolute value */
    val expenseCents: Long,
    /** 0–100: this day's share of the month's income total, matching upstream `getBarLength`. */
    val incomeSize: Double,
    val expenseSize: Double,
)

data class CalendarMonthData(
    val monthStart: LocalDate,
    /** Row-major weeks; count is a multiple of 7 and the first cell falls on the configured first weekday. */
    val days: List<CalendarDayCell>,
    val totalIncomeCents: Long,
    val totalExpenseCents: Long,
)

data class CalendarData(
    val months: List<CalendarMonthData>,
    val totalIncomeCents: Long,
    val totalExpenseCents: Long,
    /** 0 = Sunday … 6 = Saturday, echoed back for the view's weekday header. */
    val firstDayOfWeekIdx: Int,
)

object CalendarEngine {
    fun compute(
        meta: CalendarMeta?,
        transactions: List<Transaction>,
        today: LocalDate,
        firstDayOfWeekIdx: Int,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): CalendarData {
        // Upstream expands the resolved range to whole months
        // (firstDayOfMonth(start) .. lastDayOfMonth(end)).
        val (rawStart, rawEnd) = TimeFrame.resolve(meta?.timeFrame, today)
        val firstMonth = rawStart.monthStart()
        val lastMonth = rawEnd.monthStart()
        val startYMD = firstMonth.ymd
        val endYMD = lastMonth.monthEnd().ymd

        // Upstream buckets income (amount > 0) and expenses (amount < 0)
        // separately per day; zero-amount transactions land in neither.
        val incomeByDay = HashMap<Int, Long>()
        val expenseByDay = HashMap<Int, Long>()
        for (tx in transactions) {
            if (tx.tombstone || tx.date !in startYMD..endYMD) continue
            if (!ConditionsFilter.matches(tx, meta?.conditions, meta?.conditionsOp, context)) continue
            if (tx.amount > 0) incomeByDay.merge(tx.date, tx.amount, Long::plus)
            else if (tx.amount < 0) expenseByDay.merge(tx.date, tx.amount, Long::plus)
        }

        // Out-of-range values fall back to Sunday, matching upstream.
        val weekStart = if (firstDayOfWeekIdx in 0..6) firstDayOfWeekIdx else 0

        val months = mutableListOf<CalendarMonthData>()
        var cursor = firstMonth
        while (cursor <= lastMonth) {
            months += monthData(cursor, incomeByDay, expenseByDay, weekStart)
            cursor = cursor.plusMonths(1)
        }
        return CalendarData(months, months.sumOf { it.totalIncomeCents }, months.sumOf { it.totalExpenseCents }, weekStart)
    }

    private fun monthData(monthStart: LocalDate, incomeByDay: Map<Int, Long>, expenseByDay: Map<Int, Long>, weekStart: Int): CalendarMonthData {
        val yyyymm = monthStart.ymd / 100
        val daysInMonth = monthStart.lengthOfMonth()
        val totalIncome = (1..daysInMonth).sumOf { incomeByDay[yyyymm * 100 + it] ?: 0L }
        val totalExpense = (1..daysInMonth).sumOf { abs(expenseByDay[yyyymm * 100 + it] ?: 0L) }

        // Leading padding back to the configured first weekday, trailing
        // padding to a whole number of weeks (upstream recalculate()).
        val jsWeekday = monthStart.dayOfWeek.value % 7 // 0 = Sunday
        val leading = (jsWeekday - weekStart + 7) % 7
        var totalCells = leading + daysInMonth
        if (totalCells % 7 != 0) totalCells += 7 - totalCells % 7

        val cells = (0 until totalCells).map { i ->
            val day = i - leading + 1
            if (day < 1 || day > daysInMonth) {
                CalendarDayCell(null, 0, 0, 0.0, 0.0)
            } else {
                val income = incomeByDay[yyyymm * 100 + day] ?: 0L
                val expense = abs(expenseByDay[yyyymm * 100 + day] ?: 0L)
                CalendarDayCell(
                    dayOfMonth = day,
                    incomeCents = income,
                    expenseCents = expense,
                    incomeSize = if (totalIncome > 0) income.toDouble() / totalIncome * 100 else 0.0,
                    expenseSize = if (totalExpense > 0) expense.toDouble() / totalExpense * 100 else 0.0,
                )
            }
        }
        return CalendarMonthData(monthStart, cells, totalIncome, totalExpense)
    }
}
