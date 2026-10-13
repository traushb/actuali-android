package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.WidgetRuleCondition
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.services.reports.conditionStrings
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs

/**
 * Meta for the upstream `budget-analysis-card` widget (dashboard.ts
 * BudgetAnalysisWidget). `interval` is declared upstream but the spreadsheet
 * only ever computes monthly intervals; it is kept for round-tripping.
 */
@Serializable
data class BudgetAnalysisMeta(
    val name: String? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    /** "Daily" | "Weekly" | "Monthly" | "Yearly" */
    val interval: String? = null,
    /** "Line" | "Bar" */
    val graphType: String? = null,
    val showBalance: Boolean? = null,
    val balanceOnly: Boolean? = null,
    val showHiddenCategories: Boolean? = null,
)

/**
 * One budget row from the live budget table (zero_budgets for envelope,
 * reflect_budgets for tracking — see `BudgetDatabase.fetchBudgetDataForReports`).
 * `month` is YYYYMM and `amount` integer cents, both stored as such.
 */
data class BudgetAnalysisBudgetEntry(val month: Int, val categoryId: String, val amountCents: Long)

data class BudgetAnalysisIntervalPoint(
    /** YYYYMM */
    val month: Int,
    val budgetedCents: Long,
    /** negative for spending, as upstream */
    val spentCents: Long,
    val balanceCents: Long,
    /** positive */
    val overspendingAdjustmentCents: Long,
)

data class BudgetAnalysisData(
    val intervalData: List<BudgetAnalysisIntervalPoint>,
    val totalBudgetedCents: Long,
    val totalSpentCents: Long,
    val graphType: GraphType,
    val showBalance: Boolean,
    val balanceOnly: Boolean,
) {
    enum class GraphType { Line, Bar }
}

/**
 * Port of the webapp's budget-analysis-spreadsheet.ts at card fidelity.
 * Budget rows here carry no carryover flag, so the balance math uses
 * upstream's default (carryover off): positive category balances roll
 * forward, negative ones zero out and surface as the next month's
 * overspending adjustment.
 */
object BudgetAnalysisEngine {
    fun compute(
        meta: BudgetAnalysisMeta?,
        transactions: List<Transaction>,
        budgets: List<BudgetAnalysisBudgetEntry>,
        categories: List<Category>,
        categoryGroups: List<CategoryGroup>,
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): BudgetAnalysisData {
        // Stored time frame, or the card's default sliding window of the
        // trailing six months (BudgetAnalysisCard.tsx).
        val startMonth: Int
        val endMonth: Int
        val tf = meta?.timeFrame
        if (tf != null) {
            val (start, end) = TimeFrame.resolve(tf, today)
            startMonth = yyyymm(start)
            endMonth = yyyymm(end)
        } else {
            endMonth = yyyymm(today)
            startMonth = addMonths(endMonth, -5)
        }

        val graphType = if (meta?.graphType == "Line") BudgetAnalysisData.GraphType.Line else BudgetAnalysisData.GraphType.Bar
        val showBalance = meta?.showBalance ?: true
        val balanceOnly = meta?.balanceOnly ?: false
        if (startMonth > endMonth) return BudgetAnalysisData(emptyList(), 0, 0, graphType, showBalance, balanceOnly)

        // Base set mirrors upstream isBaseCategory: expense categories only;
        // hidden ones count only when showHiddenCategories.
        val showHidden = meta?.showHiddenCategories ?: false
        val base = categories.filter { !it.isIncome && (showHidden || !it.hidden) }
        val included = filterCategoriesByConditions(base, categoryGroups, meta?.conditions, meta?.conditionsOp)
        val includedIds = included.mapTo(HashSet()) { it.id }

        // Bucket budgets and spending by month. Spending mirrors the envelope
        // sum-amount cells: on-budget transactions of the included categories,
        // amounts kept negative.
        val budgetedByMonth = HashMap<Int, HashMap<String, Long>>()
        for (entry in budgets) {
            if (entry.categoryId !in includedIds || entry.month > endMonth) continue
            budgetedByMonth.getOrPut(entry.month) { HashMap() }.merge(entry.categoryId, entry.amountCents, Long::plus)
        }
        val spentByMonth = HashMap<Int, HashMap<String, Long>>()
        for (tx in transactions) {
            val catId = tx.categoryId
            if (tx.tombstone || catId == null || catId !in includedIds || tx.accountId in context.offBudgetAccountIds) continue
            val month = tx.date / 100
            if (month > endMonth) continue
            spentByMonth.getOrPut(month) { HashMap() }.merge(catId, tx.amount, Long::plus)
        }

        // Upstream seeds the running balance from the leftover cells of the
        // month before the range; replaying every earlier month we were given
        // produces the same per-category carryovers.
        val earliest = minOf(budgetedByMonth.keys.minOrNull() ?: startMonth, spentByMonth.keys.minOrNull() ?: startMonth, startMonth)

        var carried = emptyMap<String, Long>() // category id → balance carried into the current month
        var overspendingFromPrevMonth = 0L
        val intervalData = mutableListOf<BudgetAnalysisIntervalPoint>()
        var totalBudgeted = 0L
        var totalSpent = 0L

        var month = earliest
        while (month <= endMonth) {
            // Upstream starts the display loop with a zero adjustment
            // regardless of pre-range overspending.
            if (month == startMonth) overspendingFromPrevMonth = 0

            var budgeted = 0L
            var spent = 0L
            var runningBalance = 0L
            var overspendingThisMonth = 0L
            val carriedNext = HashMap<String, Long>()

            for (cat in included) {
                val catBudgeted = budgetedByMonth[month]?.get(cat.id) ?: 0
                val catSpent = spentByMonth[month]?.get(cat.id) ?: 0
                val carriedIn = carried[cat.id] ?: 0
                val catBalance = catBudgeted + catSpent + carriedIn
                budgeted += catBudgeted
                spent += catSpent
                runningBalance += carriedIn
                if (catBalance > 0) carriedNext[cat.id] = catBalance else if (catBalance < 0) overspendingThisMonth += catBalance
            }

            if (month >= startMonth) {
                totalBudgeted += budgeted
                totalSpent += spent
                intervalData += BudgetAnalysisIntervalPoint(month, budgeted, spent, budgeted + spent + runningBalance, abs(overspendingFromPrevMonth))
            }

            carried = carriedNext
            overspendingFromPrevMonth = overspendingThisMonth
            month = addMonths(month, 1)
        }

        return BudgetAnalysisData(intervalData, totalBudgeted, totalSpent, graphType, showBalance, balanceOnly)
    }

    // Category condition filtering

    /**
     * Port of budgetDataQuery.ts filterCategoriesByConditions: only category /
     * category_group conditions narrow the set, and if any of them can't be
     * safely interpreted, no filtering happens at all (better to be broad than
     * to silently exclude data). Shared with SpendingEngine, mirroring
     * upstream's import into getSpendingBudgetFilters.
     */
    fun filterCategoriesByConditions(
        categories: List<Category>,
        groups: List<CategoryGroup>,
        conditions: List<WidgetRuleCondition>?,
        conditionsOp: String?,
    ): List<Category> {
        val categoryConditions = conditions.orEmpty().filter {
            it.customName == null && (it.field == "category" || it.field == "category_group")
        }
        if (categoryConditions.isEmpty() || !categoryConditions.all(::isSupportedCategoryCondition)) return categories

        val groupNameById = HashMap<String, String>()
        for (group in groups) groupNameById.putIfAbsent(group.id, group.name)
        val isOr = (conditionsOp ?: "and").lowercase() == "or"
        return categories.filter { cat ->
            if (isOr) categoryConditions.any { matches(cat, it, groupNameById) } else categoryConditions.all { matches(cat, it, groupNameById) }
        }
    }

    /** Port of budgetDataQuery.ts isSupportedCategoryCondition. */
    fun isSupportedCategoryCondition(cond: WidgetRuleCondition): Boolean = when (cond.op) {
        "is", "isNot", "contains", "doesNotContain", "matches" -> cond.value.stringOrNull != null
        "oneOf", "notOneOf" -> cond.value.conditionStrings() != null
        else -> false
    }

    private fun matches(cat: Category, cond: WidgetRuleCondition, groupNameById: Map<String, String>): Boolean {
        val key = if (cond.field == "category_group") cat.groupId else cat.id
        // Text operators compare against the human-readable name, not the UUID.
        val text = if (cond.field == "category_group") groupNameById[key] ?: key else cat.name
        val value = cond.value.stringOrNull
        return when (cond.op) {
            "is" -> value == key
            "isNot" -> value != key
            "oneOf" -> cond.value.conditionStrings()?.contains(key) ?: false
            "notOneOf" -> !(cond.value.conditionStrings()?.contains(key) ?: false)
            "contains" -> value != null && text.lowercase().contains(value.lowercase())
            "doesNotContain" -> value != null && !text.lowercase().contains(value.lowercase())
            "matches" -> {
                if (value == null || value.length > 256) return false
                val regex = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
                regex.containsMatchIn(text)
            }
            else -> true
        }
    }

    private fun yyyymm(date: LocalDate): Int = date.year * 100 + date.monthValue

    private fun addMonths(yyyymm: Int, delta: Int): Int {
        val total = (yyyymm / 100) * 12 + (yyyymm % 100 - 1) + delta
        return (total / 12) * 100 + total % 12 + 1
    }
}
