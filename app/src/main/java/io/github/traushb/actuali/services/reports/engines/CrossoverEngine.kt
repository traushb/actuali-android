package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.CanonicalDateParser
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Mirrors upstream `CrossoverWidget` meta (loot-core dashboard models).
 * `expectedContribution` is monthly cents, like all upstream amounts.
 */
@Serializable
data class CrossoverMeta(
    val name: String? = null,
    val expenseCategoryIds: List<String>? = null,
    val incomeAccountIds: List<String>? = null,
    val timeFrame: WidgetTimeFrame? = null,
    /** annual, default 0.04 */
    val safeWithdrawalRate: Double? = null,
    /** annual, null = derive from history */
    val estimatedReturn: Double? = null,
    /** monthly cents, default 0 */
    val expectedContribution: Double? = null,
    /** default hampel */
    val projectionType: ProjectionType? = null,
    val showHiddenCategories: Boolean? = null,
    /** default 1.0 */
    val expenseAdjustmentFactor: Double? = null,
) {
    @Serializable
    enum class ProjectionType {
        @SerialName("hampel")
        Hampel,

        @SerialName("median")
        Median,

        @SerialName("mean")
        Mean,
    }
}

data class CrossoverPoint(
    /** first of month */
    val month: LocalDate,
    /** balance * monthly SWR */
    val investmentIncomeCents: Long,
    val expensesCents: Long,
    val nestEggCents: Long,
    /** projection points only */
    val adjustedExpensesCents: Long?,
    val isProjection: Boolean,
)

data class CrossoverData(
    val points: List<CrossoverPoint>,
    /** projected month income first covers adjusted expenses */
    val crossoverMonth: LocalDate?,
    val lastKnownBalanceCents: Long,
    val lastKnownMonthlyIncomeCents: Long,
    val lastKnownMonthlyExpensesCents: Long,
    /** annualized CAGR of the selected accounts */
    val historicalReturn: Double?,
    val yearsToRetire: Double?,
    val targetMonthlyIncomeCents: Long?,
    val targetNestEggCents: Long?,
) {
    companion object {
        val empty = CrossoverData(emptyList(), null, 0, 0, 0, null, null, null, null)
    }
}

/**
 * Port of upstream crossover-spreadsheet.ts + the date-range resolution in
 * CrossoverCard.tsx, computed over in-memory transactions.
 */
object CrossoverEngine {
    /**
     * @param categories all live categories — used to default the expense set
     *   to every non-income category and to honor `showHiddenCategories`.
     * @param accountIds all live account ids — the default income-account set
     *   when meta doesn't select specific accounts (upstream `useAccounts`).
     */
    fun compute(
        meta: CrossoverMeta?,
        transactions: List<Transaction>,
        categories: List<Category>,
        accountIds: List<String>,
        today: LocalDate,
    ): CrossoverData {
        val incomeAccountIds = meta?.incomeAccountIds ?: accountIds
        if (incomeAccountIds.isEmpty()) return CrossoverData.empty

        val live = transactions.filter { !it.tombstone }

        // Date range: months as year*12 + (month-1) indices. Upstream card
        // clamps to [earliest transaction month, previous month] and defaults
        // to mode "full" (Crossover.tsx defaultTimeFrame).
        val currentMonth = today.year * 12 + today.monthValue - 1
        val latestMonth = currentMonth - 1
        val earliestMonth = min(live.minOfOrNull { monthIndex(it.date) } ?: latestMonth, latestMonth)
        fun clamp(m: Int) = min(max(m, earliestMonth), latestMonth)

        val tf = meta?.timeFrame
        var start: Int
        var end: Int
        when (tf?.mode ?: WidgetTimeFrame.Mode.Full) {
            WidgetTimeFrame.Mode.Full -> {
                start = earliestMonth
                end = latestMonth
            }
            WidgetTimeFrame.Mode.SlidingWindow -> {
                // calculateTimeRange keeps the stored window width ending at the
                // current month; the card then shifts both back one month.
                val storedStart = parseMonthIndex(tf?.start) ?: (currentMonth - 120)
                val storedEnd = parseMonthIndex(tf?.end) ?: (currentMonth - 1)
                val offset = storedEnd - storedStart
                start = clamp(currentMonth - offset - 1)
                end = clamp(currentMonth - 1)
            }
            WidgetTimeFrame.Mode.LastMonth -> {
                start = clamp(currentMonth - 1)
                end = clamp(currentMonth - 1)
            }
            WidgetTimeFrame.Mode.LastYear -> {
                val priorJan = ((currentMonth / 12) - 1) * 12
                start = clamp(priorJan)
                end = clamp(priorJan + 11)
            }
            WidgetTimeFrame.Mode.YearToDate -> {
                start = clamp((currentMonth / 12) * 12)
                end = clamp(currentMonth)
            }
            WidgetTimeFrame.Mode.PriorYearToDate -> {
                start = clamp(((currentMonth / 12) - 1) * 12)
                end = clamp(currentMonth - 12)
            }
            WidgetTimeFrame.Mode.Static -> {
                start = clamp(parseMonthIndex(tf?.start) ?: (currentMonth - 120))
                end = clamp(parseMonthIndex(tf?.end) ?: (currentMonth - 1))
            }
        }
        if (end < start) end = start
        val months = (start..end).toList()

        // Expense category set: stored ids, or every non-income category, then
        // filter hidden unless showHiddenCategories.
        val showHidden = meta?.showHiddenCategories ?: false
        val baseCategories = meta?.expenseCategoryIds?.toSet()?.let { stored -> categories.filter { it.id in stored } }
            ?: categories.filter { !it.isIncome }
        val expenseIds = baseCategories.filter { showHidden || !it.hidden }.mapTo(HashSet()) { it.id }

        // Monthly expenses (expenses are negative; flip to positive spend).
        val expenseByMonth = HashMap<Int, Long>()
        for (t in live) {
            val categoryId = t.categoryId ?: continue
            if (categoryId !in expenseIds) continue
            val m = monthIndex(t.date)
            if (m in start..end) expenseByMonth.merge(m, -t.amount, Long::plus)
        }

        // Running balances of the selected accounts per month. Upstream seeds
        // each account with its balance through the end of the start month,
        // then applies per-month deltas; summing across accounts commutes.
        val incomeSet = incomeAccountIds.toSet()
        var startingBalance = 0L
        val deltas = LongArray(months.size)
        for (t in live) {
            if (t.accountId !in incomeSet) continue
            val m = monthIndex(t.date)
            if (m <= start) startingBalance += t.amount else if (m <= end) deltas[m - start] += t.amount
        }
        val historicalBalances = mutableListOf<Long>()
        var running = startingBalance
        for (i in months.indices) {
            if (i > 0) running += deltas[i]
            historicalBalances += running
        }

        val monthlySWR = (meta?.safeWithdrawalRate ?: 0.04) / 12

        val points = months.mapIndexed { i, m ->
            val balance = historicalBalances[i]
            CrossoverPoint(monthDate(m), jsRound(balance * monthlySWR), expenseByMonth[m] ?: 0, balance, null, false)
        }.toMutableList()
        val lastBalance = historicalBalances.lastOrNull() ?: 0
        val lastExpense = expenseByMonth[end] ?: 0

        // Default monthly return: CAGR of the selected accounts' total balance.
        var defaultMonthlyReturn: Double? = null
        if (historicalBalances.size >= 2) {
            var startBalance = historicalBalances[0].toDouble()
            val finalBalance = historicalBalances.last().toDouble()
            val n = historicalBalances.size - 1
            if (startBalance == 0.0) historicalBalances.drop(1).firstOrNull { it != 0L }?.let { startBalance = it.toDouble() }
            defaultMonthlyReturn = if (startBalance > 0 && finalBalance > 0) {
                val cagr = (finalBalance / startBalance).pow(1.0 / n) - 1
                if (cagr.isFinite()) cagr else 0.0
            } else {
                0.0
            }
        }

        val monthlyReturn = meta?.estimatedReturn?.let { (1 + it).pow(1.0 / 12) - 1 } ?: defaultMonthlyReturn
        val monthlyContribution = meta?.expectedContribution ?: 0.0
        val adjustmentFactor = meta?.expenseAdjustmentFactor ?: 1.0

        // Forward projection: flat expenses per projectionType, balance grows by
        // contribution then return, up to 600 months or crossover.
        var crossoverIndex: Int? = null
        val y = months.map { (expenseByMonth[it] ?: 0).toDouble() }
        val flatExpense = when (meta?.projectionType ?: CrossoverMeta.ProjectionType.Hampel) {
            CrossoverMeta.ProjectionType.Hampel -> hampelFilteredMedian(y)
            CrossoverMeta.ProjectionType.Median -> median(y)
            CrossoverMeta.ProjectionType.Mean -> mean(y)
        }

        var projectedBalance = lastBalance.toDouble()
        var cursor = end
        for (i in 1..600) {
            cursor += 1
            // Upstream adds the contribution BEFORE applying growth.
            projectedBalance += monthlyContribution
            if (monthlyReturn != null) projectedBalance *= (1 + monthlyReturn)
            val projectedIncome = projectedBalance * monthlySWR
            val projectedExpenses = max(0.0, flatExpense)
            val adjustedExpenses = projectedExpenses * adjustmentFactor

            points += CrossoverPoint(
                monthDate(cursor), jsRound(projectedIncome), jsRound(projectedExpenses), jsRound(projectedBalance), jsRound(adjustedExpenses), true,
            )
            // Crossover is checked against ADJUSTED expenses, projection only.
            if (jsRound(projectedIncome) >= jsRound(adjustedExpenses)) {
                crossoverIndex = months.size + (i - 1)
                break
            }
        }

        var crossoverMonth: LocalDate? = null
        var yearsToRetire: Double? = null
        var targetMonthlyIncome: Long? = null
        var targetNestEgg: Long? = null
        if (crossoverIndex != null && crossoverIndex < points.size) {
            val crossoverPoint = points[crossoverIndex]
            crossoverMonth = crossoverPoint.month
            // Upstream measures whole months from "now" to the crossover month
            // (date-fns parse reuses today's day-of-month), floored at 0.
            val monthsDiff = (start + crossoverIndex) - currentMonth
            yearsToRetire = if (monthsDiff > 0) monthsDiff / 12.0 else 0.0
            targetMonthlyIncome = crossoverPoint.adjustedExpensesCents
            if (targetMonthlyIncome != null && monthlySWR > 0) targetNestEgg = jsRound(targetMonthlyIncome / monthlySWR)
        }

        return CrossoverData(
            points = points,
            crossoverMonth = crossoverMonth,
            lastKnownBalanceCents = lastBalance,
            lastKnownMonthlyIncomeCents = jsRound(lastBalance * monthlySWR),
            lastKnownMonthlyExpensesCents = lastExpense,
            historicalReturn = defaultMonthlyReturn?.let { (1 + it).pow(12) - 1 },
            yearsToRetire = yearsToRetire,
            targetMonthlyIncomeCents = targetMonthlyIncome,
            targetNestEggCents = targetNestEgg,
        )
    }

    // Statistics (upstream Hampel identifier utilities)

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        if (values.size == 1) return values[0]
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    /** Median after rejecting outliers beyond median ± 3 * 1.4826 * MAD. */
    fun hampelFilteredMedian(expenses: List<Double>): Double {
        if (expenses.isEmpty()) return 0.0
        if (expenses.size == 1) return expenses[0]
        val med = median(expenses)
        val mad = median(expenses.map { abs(it - med) })
        val lowerBound = med - 1.4826 * mad * 3.0
        val upperBound = med + 1.4826 * mad * 3.0
        return median(expenses.filter { it in lowerBound..upperBound })
    }

    // Month helpers

    private fun monthIndex(date: Int): Int {
        val ym = date / 100
        return (ym / 100) * 12 + (ym % 100) - 1
    }

    private fun monthDate(index: Int): LocalDate = LocalDate.of(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1, 1)

    /** Parses "YYYY-MM" or "YYYY-MM-DD" to a month index. */
    private fun parseMonthIndex(s: String?): Int? = s?.let(CanonicalDateParser::parseMonthOrDay)?.let { it.year * 12 + it.monthValue - 1 }

    /** JS Math.round (half toward +infinity), for upstream parity. */
    private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()
}
