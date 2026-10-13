package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.reports.AgeOfMoneyMeta
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.localDate
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

data class AgeOfMoneyData(
    /** null when no expenses in range */
    val currentAge: Int?,
    val points: List<Point>,
    val trend: Trend,
    /** expenses exceeded income buckets */
    val insufficientData: Boolean,
) {
    data class Point(
        /** "Jan 2026" */
        val monthLabel: String,
        /** days */
        val age: Int,
    )

    enum class Trend { Up, Down, Stable }

    companion object {
        val empty = AgeOfMoneyData(null, emptyList(), Trend.Stable, false)
    }
}

/** "Jan 2026" in the locale's own order, the label the month-axis report charts use. */
object ReportMonthYearFormatting {
    fun string(date: LocalDate, locale: Locale): String = LocaleFormatting.format(date, "yMMM", locale)
}

/**
 * Port of the webapp's age-of-money-spreadsheet.ts. Income transactions
 * become FIFO buckets; each expense drains the oldest buckets and its age is
 * the day distance to the last bucket it touched. The headline is the rounded
 * average of the last 10 ages inside the display range; the graph is a
 * per-month cumulative rolling average of the same window.
 */
object AgeOfMoneyEngine {
    private class Bucket(val date: Int, var remaining: Long)

    fun compute(
        meta: AgeOfMoneyMeta?,
        transactions: List<Transaction>,
        today: LocalDate,
        context: ConditionsFilter.Context,
        locale: Locale = Locale.getDefault(),
    ): AgeOfMoneyData {
        val (start, resolvedEnd) = TimeFrame.resolve(meta?.timeFrame, today)
        // Upstream: fixedEnd = min(lastDayOfMonth(end), today). Only the
        // transaction query is clamped to today — the monthly graph still runs
        // through the resolved end month (current month stays visible).
        val fixedEndYMD = minOf(resolvedEnd, today).ymd

        // FIFO needs FULL history up to the end date: on-budget accounts only,
        // and no on-budget↔on-budget transfers (they just move the pool
        // around). Transfers to/from off-budget accounts count as real
        // spending/income (upstream's $or on payee.transfer_acct).
        val pool = transactions
            .filter { tx ->
                !tx.tombstone && tx.date <= fixedEndYMD && tx.accountId !in context.offBudgetAccountIds &&
                    (tx.transferAcct?.let { it in context.offBudgetAccountIds } ?: true) &&
                    ConditionsFilter.matches(tx, meta?.conditions, meta?.conditionsOp, context)
            }
            .sortedWith { lhs, rhs ->
                val left = lhs.sortOrder
                val right = rhs.sortOrder
                when {
                    lhs.date != rhs.date -> lhs.date.compareTo(rhs.date)
                    left != null && right != null && left != right -> right.compareTo(left)
                    left != null && right == null -> -1
                    left == null && right != null -> 1
                    else -> lhs.id.compareTo(rhs.id)
                }
            }

        // FIFO drain (upstream calculateAgeOfMoney).
        val buckets = pool.filter { it.amount > 0 }.map { Bucket(it.date, it.amount) }
        var bucketIdx = 0
        var insufficientData = false
        val ages = mutableListOf<Pair<Int, Int>>() // (date, age)

        for (expense in pool) {
            if (expense.amount >= 0) continue
            var remaining = -expense.amount
            var lastBucketDate: Int? = null
            while (remaining > 0 && bucketIdx < buckets.size) {
                val bucket = buckets[bucketIdx]
                if (bucket.remaining > 0) {
                    val deduction = minOf(bucket.remaining, remaining)
                    bucket.remaining -= deduction
                    remaining -= deduction
                    lastBucketDate = bucket.date
                }
                if (bucket.remaining <= 0) bucketIdx += 1
            }
            if (remaining > 0) insufficientData = true
            if (lastBucketDate != null) ages += expense.date to maxOf(0, daysBetween(lastBucketDate, expense.date))
        }

        // Only ages inside the display range count toward headline and graph.
        val displayStartYMD = start.monthStart().ymd
        val displayAges = ages.filter { it.first >= displayStartYMD }

        fun average(values: List<Int>): Int {
            val lastTen = values.takeLast(10)
            return roundHalfAwayFromZero(lastTen.sum().toDouble() / lastTen.size).toInt()
        }

        // Headline (upstream calculateAverageAge, count = 10).
        val currentAge = if (displayAges.isEmpty()) null else average(displayAges.map { it.second })

        // Per-month cumulative rolling average (upstream calculateGraphData,
        // monthly granularity). Months with a running window but no new ages
        // still emit a point (the average carries forward).
        val points = mutableListOf<AgeOfMoneyData.Point>()
        val agesSoFar = mutableListOf<Int>()
        var month = start.monthStart()
        val lastMonth = resolvedEnd.monthStart()
        while (month <= lastMonth) {
            val monthKey = month.ymd / 100 // YYYYMM
            displayAges.filter { it.first / 100 == monthKey }.mapTo(agesSoFar) { it.second }
            if (agesSoFar.isNotEmpty()) points += AgeOfMoneyData.Point(ReportMonthYearFormatting.string(month, locale), average(agesSoFar))
            month = month.plusMonths(1)
        }

        // Trend: last two points, ±2 day threshold (upstream calculateTrend).
        val trend = if (points.size < 2) {
            AgeOfMoneyData.Trend.Stable
        } else {
            val diff = points[points.size - 1].age - points[points.size - 2].age
            when {
                diff > 2 -> AgeOfMoneyData.Trend.Up
                diff < -2 -> AgeOfMoneyData.Trend.Down
                else -> AgeOfMoneyData.Trend.Stable
            }
        }
        return AgeOfMoneyData(currentAge, points, trend, insufficientData)
    }

    private fun daysBetween(from: Int, to: Int): Int {
        val f = localDate(from) ?: return 0
        val t = localDate(to) ?: return 0
        return ChronoUnit.DAYS.between(f, t).toInt()
    }
}
