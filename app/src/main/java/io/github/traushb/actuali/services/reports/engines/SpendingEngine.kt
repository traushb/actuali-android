package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.CanonicalDateParser
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.SpendingAverageRange
import io.github.traushb.actuali.services.reports.SpendingMeta
import io.github.traushb.actuali.services.reports.localDate
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import java.time.LocalDate

data class SpendingData(
    /** positive */
    val currentSpentCents: Long,
    /** positive */
    val comparisonCents: Long,
)

/**
 * Port of the webapp's spending-spreadsheet.ts + SpendingCard.tsx. The card
 * shows the compare month's cumulative spending at "today's day" against a
 * comparison: another month (single-month), an average over a configurable
 * window (average), or the month's budget (budget). Spending is the NET of all
 * matching transactions (refunds count), with income categories and
 * off-budget accounts already removed by the caller (spendingScope).
 */
object SpendingEngine {
    fun compute(
        meta: SpendingMeta?,
        transactions: List<Transaction>,
        budgets: List<BudgetAnalysisBudgetEntry> = emptyList(),
        categories: List<Category> = emptyList(),
        categoryGroups: List<CategoryGroup> = emptyList(),
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): SpendingData {
        val live = transactions.filter { !it.tombstone }
        val filtered = live.filter { ConditionsFilter.matches(it, meta?.conditions, meta?.conditionsOp, context) }

        val currentMonthStart = today.monthStart()
        val (compareStart, compareToStart) = resolveMonths(meta, currentMonthStart)

        val todayDay = today.dayOfMonth
        val isCurrentCompare = compareStart == currentMonthStart

        // Upstream's card reads the cumulative at today's day of the compare
        // month; a past compare month reads the day-28 bucket, i.e. the full
        // month (SpendingCard.tsx todayDay). Comparison values use the same
        // day so current-vs-past is month-to-date against month-to-same-date,
        // except past day 28 where prior months use their full totals.
        val current = monthSpending(filtered, compareStart, if (isCurrentCompare) todayDay else null)
        val sameDayCutoff = if (isCurrentCompare && todayDay < 28) todayDay else null

        val comparison = when (meta?.mode ?: SpendingMeta.Mode.SingleMonth) {
            SpendingMeta.Mode.SingleMonth -> monthSpending(filtered, compareToStart, sameDayCutoff)
            SpendingMeta.Mode.Budget -> budgetComparison(meta, budgets, categories, categoryGroups, compareStart, sameDayCutoff)
            SpendingMeta.Mode.Average -> averageSpending(filtered, live, compareStart, meta?.averageRange, sameDayCutoff)
        }
        return SpendingData(current, comparison)
    }

    /**
     * Port of calculateSpendingReportTimeRange (reportRanges.ts): live
     * budget/average pin to the stored compare month or the current one; live
     * single-month with a stored compare uses it (and compareTo, else the
     * month before); otherwise a live window slides to end at the current
     * month while a static one keeps its stored months verbatim.
     */
    private fun resolveMonths(meta: SpendingMeta?, currentMonthStart: LocalDate): Pair<LocalDate, LocalDate> {
        val isLive = meta?.isLive ?: true
        val mode = meta?.mode ?: SpendingMeta.Mode.SingleMonth
        val stored = parseMonthStart(meta?.compare)
        val storedTo = parseMonthStart(meta?.compareTo)

        if ((mode == SpendingMeta.Mode.Budget || mode == SpendingMeta.Mode.Average) && isLive) {
            val compare = stored ?: currentMonthStart
            return compare to compare.minusMonths(1)
        }
        if (mode == SpendingMeta.Mode.SingleMonth && isLive && stored != null) return stored to (storedTo ?: stored.minusMonths(1))
        if (isLive) {
            // Sliding window: compare is the current month; compareTo keeps its
            // stored month (clamped), defaulting to the previous month.
            val to = storedTo ?: currentMonthStart.minusMonths(1)
            return currentMonthStart to minOf(to, currentMonthStart)
        }
        val compare = stored ?: currentMonthStart
        return compare to (storedTo ?: compare.minusMonths(1))
    }

    /**
     * Port of spending-spreadsheet.ts dailyBudget + getSpendingBudgetFilters:
     * the compare month's budgeted total — narrowed to categories matching the
     * widget's category/category_group conditions only when all of them are
     * supported, else unfiltered — spread evenly over the month's days and
     * accumulated through the same day bucket the card reads.
     */
    private fun budgetComparison(
        meta: SpendingMeta?,
        budgets: List<BudgetAnalysisBudgetEntry>,
        categories: List<Category>,
        categoryGroups: List<CategoryGroup>,
        compareStart: LocalDate,
        throughDay: Int?,
    ): Long {
        val monthInt = compareStart.year * 100 + compareStart.monthValue
        var rows = budgets.filter { it.month == monthInt }

        val categoryConditions = meta?.conditions.orEmpty().filter {
            it.customName == null && (it.field == "category" || it.field == "category_group")
        }
        if (categoryConditions.isNotEmpty() && categoryConditions.all(BudgetAnalysisEngine::isSupportedCategoryCondition)) {
            val allowed = BudgetAnalysisEngine.filterCategoriesByConditions(categories, categoryGroups, categoryConditions, meta?.conditionsOp)
                .mapTo(HashSet()) { it.id }
            rows = rows.filter { it.categoryId in allowed }
        }

        val total = rows.sumOf { it.amountCents }
        val daysInMonth = compareStart.lengthOfMonth()
        if (total == 0L) return 0
        val daysElapsed = throughDay ?: daysInMonth
        return roundHalfAwayFromZero(total.toDouble() / daysInMonth * daysElapsed)
    }

    /**
     * Net spending for one month as positive cents. [throughDay] limits to a
     * month-to-date cumulative; null sums the whole month.
     */
    private fun monthSpending(transactions: List<Transaction>, monthStart: LocalDate, throughDay: Int? = null): Long {
        val lastDay = monthStart.lengthOfMonth()
        val endDay = throughDay?.let { minOf(it, lastDay) } ?: lastDay
        val startYMD = monthStart.ymd
        val endYMD = monthStart.plusDays((endDay - 1).toLong()).ymd
        return -transactions.filter { it.date in startYMD..endYMD }.sumOf { it.amount }
    }

    /**
     * Average cumulative spending across the months of the configured range
     * (resolveSpendingAverageRange upstream): the range ends the month before
     * [compareStart]; months with no transactions still count in the divisor.
     */
    private fun averageSpending(
        transactions: List<Transaction>,
        allTransactions: List<Transaction>,
        compareStart: LocalDate,
        range: SpendingAverageRange?,
        throughDay: Int?,
    ): Long {
        val endMonth = compareStart.minusMonths(1)
        val startMonth = when (range?.mode) {
            "year-to-date" -> LocalDate.of(compareStart.year, 1, 1)
            // ponytail: earliest month derived from the transactions given to
            // the engine; upstream queries the globally earliest transaction.
            "all-time" -> allTransactions.minOfOrNull { it.date }?.let { localDate(it / 100 * 100 + 1) }
            // Unsupported last-n values normalize to the 3-month default upstream.
            else -> compareStart.minusMonths((range?.months?.takeIf { it in setOf(3, 6, 12) } ?: 3).toLong())
        }
        if (startMonth == null || startMonth > endMonth) return 0

        var sum = 0L
        var count = 0
        var month: LocalDate = startMonth
        while (month <= endMonth) {
            sum += monthSpending(transactions, month, throughDay)
            count += 1
            month = month.plusMonths(1)
        }
        return if (count > 0) roundHalfAwayFromZero(sum.toDouble() / count) else 0
    }

    private fun parseMonthStart(string: String?): LocalDate? = string?.let(CanonicalDateParser::parseMonthOrDay)?.monthStart()
}
