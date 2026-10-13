package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.SummaryMeta
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SummaryData(val value: Double, val kind: Kind) {
    enum class Kind {
        /** value is cents */
        Currency,

        /** value is a percent (e.g. 42.45) */
        Percentage,
    }

    /** Rounded cents for currency values; kept for display convenience. */
    val totalCents: Long get() = roundHalfAwayFromZero(value)

    companion object {
        val zero = SummaryData(0.0, Kind.Currency)
    }
}

/**
 * Port of the webapp's summary-spreadsheet.ts. The widget's `content` selects
 * how the filtered total is reduced: raw sum, average per month/year/
 * transaction, or a percentage of a second filtered total.
 */
object SummaryEngine {
    fun compute(
        meta: SummaryMeta?,
        transactions: List<Transaction>,
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): SummaryData {
        if (meta == null) return SummaryData.zero

        val (resolvedStart, resolvedEnd) = TimeFrame.resolve(meta.timeFrame, today)
        // Upstream clamps the range the same way regardless of mode: the start
        // snaps to the first of its month, and a range ending in the current
        // month ends *today* (this also keeps the avg-per-month divisor from
        // counting days that haven't happened yet).
        val start = resolvedStart.monthStart()
        val end = if (resolvedEnd.year == today.year && resolvedEnd.monthValue == today.monthValue) today else resolvedEnd
        val startYMD = start.ymd
        val endYMD = end.ymd

        val filtered = transactions.filter {
            !it.tombstone && it.date in startYMD..endYMD && ConditionsFilter.matches(it, meta.conditions, meta.conditionsOp, context)
        }
        val total = filtered.sumOf { it.amount }.toDouble()
        val content = meta.content

        return when (content?.type ?: "sum") {
            "avgPerTransact" -> SummaryData(if (filtered.isEmpty()) 0.0 else total / filtered.size, SummaryData.Kind.Currency)
            "avgPerMonth" -> {
                if (filtered.isEmpty()) return SummaryData.zero
                // Months between start month and end month, plus the elapsed
                // fraction of the final month (upstream calculatePerMonth).
                val wholeMonths = ChronoUnit.MONTHS.between(start.monthStart(), end.monthStart())
                val numMonths = wholeMonths + end.dayOfMonth.toDouble() / end.lengthOfMonth()
                SummaryData(if (numMonths > 0) total / numMonths else 0.0, SummaryData.Kind.Currency)
            }
            "avgPerYear" -> {
                if (filtered.isEmpty()) return SummaryData.zero
                val numYears = (ChronoUnit.DAYS.between(start, end) + 1) / 365.25
                SummaryData(if (numYears > 0) total / numYears else 0.0, SummaryData.Kind.Currency)
            }
            "percentage" -> {
                val allTime = content?.divisorAllTimeDateRange ?: false
                val divisor = transactions
                    .filter { !it.tombstone && (allTime || it.date in startYMD..endYMD) }
                    .filter { ConditionsFilter.matches(it, content?.divisorConditions, content?.divisorConditionsOp, context) }
                    .sumOf { it.amount }
                    .toDouble()
                if (divisor == 0.0) return SummaryData(0.0, SummaryData.Kind.Percentage)
                SummaryData(roundHalfAwayFromZero(total / divisor * 10000) / 100.0, SummaryData.Kind.Percentage)
            }
            else -> SummaryData(total, SummaryData.Kind.Currency) // "sum" and anything unrecognized
        }
    }
}
