package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.NetWorthMeta
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.swiftWeekday
import io.github.traushb.actuali.services.reports.ymd
import java.time.LocalDate
import java.time.YearMonth

data class NetWorthPoint(val date: LocalDate, val balanceCents: Long)

data class NetWorthData(val points: List<NetWorthPoint>)

object NetWorthEngine {
    fun compute(
        meta: NetWorthMeta?,
        transactions: List<Transaction>,
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): NetWorthData {
        val (start, end) = TimeFrame.resolve(meta?.timeFrame ?: defaultTimeFrame(today), today)
        val filtered = transactions.filter {
            !it.tombstone && ConditionsFilter.matches(it, meta?.conditions, meta?.conditionsOp, context)
        }
        val points = intervalBoundaries(start, end, meta?.interval ?: NetWorthMeta.Interval.Monthly).map { boundary ->
            val boundaryYMD = boundary.ymd
            NetWorthPoint(boundary, filtered.filter { it.date <= boundaryYMD }.sumOf { it.amount })
        }
        return NetWorthData(points)
    }

    /**
     * Upstream NetWorthCard passes no default time frame, so calculateTimeRange's
     * own applies to a widget saved without one (the default dashboard's):
     * a sliding window over the last six months (reportRanges.ts).
     */
    private fun defaultTimeFrame(today: LocalDate): WidgetTimeFrame {
        val month = YearMonth.from(today)
        return WidgetTimeFrame(start = month.minusMonths(5).toString(), end = month.toString(), mode = WidgetTimeFrame.Mode.SlidingWindow)
    }

    /**
     * One anchor date per interval. Monthly: the last day of each month in the
     * range (capped at [end]). Yearly: Dec 31 of each year (capped). Weekly:
     * Saturday (calendar end-of-week, capped). Daily: each day.
     */
    private fun intervalBoundaries(start: LocalDate, end: LocalDate, interval: NetWorthMeta.Interval): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        when (interval) {
            NetWorthMeta.Interval.Daily -> {
                var cursor = start
                while (cursor <= end) {
                    result += cursor
                    cursor = cursor.plusDays(1)
                }
            }
            NetWorthMeta.Interval.Monthly -> {
                var monthStart = start.monthStart()
                while (monthStart <= end) {
                    val next = monthStart.plusMonths(1)
                    result += minOf(next.minusDays(1), end)
                    monthStart = next
                }
            }
            NetWorthMeta.Interval.Yearly -> for (year in start.year..end.year) result += minOf(LocalDate.of(year, 12, 31), end)
            NetWorthMeta.Interval.Weekly -> {
                var cursor = start
                while (cursor <= end) {
                    // Move to Saturday (end of week with Sunday-first calendar).
                    val weekEnd = cursor.plusDays(((7 - cursor.swiftWeekday) % 7).toLong())
                    result += minOf(weekEnd, end)
                    cursor = weekEnd.plusDays(7)
                }
            }
        }
        return result
    }
}
