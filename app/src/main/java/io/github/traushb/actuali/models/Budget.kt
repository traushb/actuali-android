package io.github.traushb.actuali.models

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class BudgetType(val rawValue: String) {
    Envelope("envelope"),
    Tracking("tracking"),
    ;

    companion object {
        /** Actual used "report" before renaming Tracking Budget. */
        fun fromPreference(value: String?): BudgetType = when (value) {
            "tracking", "report" -> Tracking
            else -> Envelope
        }
    }
}

data class BudgetMonth(
    /** Format: "2025-01". */
    val month: String,
    val categoryBudgets: List<CategoryBudget>,
    /**
     * Income categories for the month, shown as their own section below the
     * expense groups — mirrors the Income group at the bottom of the Actual
     * web UI's budget table.
     */
    val incomeCategories: List<IncomeCategory> = emptyList(),
    /**
     * Unallocated funds for this month ("To Budget" in Actual): income plus
     * what last month left over, minus everything budgeted. Only meaningful
     * for envelope budgets — null for tracking budgets.
     */
    val toBudget: Long? = null,
    /** Manual `zero_budget_months.buffered` amount held for next month. */
    val buffered: Long = 0,
    /**
     * Hidden rows stay available to the Budget tab without changing the
     * visible-row totals or leaking into widgets and intents.
     */
    val hiddenCategoryBudgets: List<CategoryBudget> = emptyList(),
    val hiddenIncomeCategories: List<IncomeCategory> = emptyList(),
) {
    val id: String get() = month

    val allCategoryBudgets: List<CategoryBudget> get() = categoryBudgets + hiddenCategoryBudgets

    val allIncomeCategories: List<IncomeCategory> get() = incomeCategories + hiddenIncomeCategories

    val isTrackingBudget: Boolean get() = toBudget == null

    /** Number of expense categories in the red — drives the Budget tab badge. */
    val overspentCount: Int get() = categoryBudgets.count { it.isOverspent }

    val totalBudgeted: Long get() = categoryBudgets.sumOf { it.budgeted }

    /**
     * Net activity across expense categories — inflows (refunds,
     * reimbursements) offset outflows, matching the Spent total in Actual's
     * web UI (GH #212).
     */
    val totalSpent: Long get() = categoryBudgets.sumOf { it.spent }

    fun totalSpent(excluding: Set<String>): Long =
        categoryBudgets.sumOf { if (it.categoryId in excluding) 0L else it.spent }

    val totalAvailable: Long get() = categoryBudgets.sumOf { it.available }

    val totalIncome: Long get() = incomeCategories.sumOf { it.received }

    /**
     * Sum of budgeted amounts on income categories. Only meaningful for
     * tracking budgets, which can budget income. Mirrors loot-core
     * tracking.ts `total-budget-income`.
     */
    val totalBudgetedIncome: Long get() = incomeCategories.sumOf { it.budgeted }

    /**
     * Actual money kept this month: income received minus what actually went
     * out. Mirrors loot-core tracking.ts `real-saved`; `totalSpent` is
     * negative, so this adds.
     */
    val savedActual: Long get() = totalIncome + totalSpent

    /** Actual money kept after excluding the selected categories from spent. */
    fun savedActual(excluding: Set<String>): Long = totalIncome + totalSpent(excluding)

    /**
     * What the budget projects will be kept: budgeted income minus budgeted
     * expenses. Mirrors loot-core tracking.ts `total-saved`.
     */
    val projectedSavings: Long get() = totalBudgetedIncome - totalBudgeted
}

data class IncomeCategory(
    val month: String,
    val categoryId: String,
    val categoryName: String,
    val groupName: String,
    val sortOrder: Double,
    /** In cents; only meaningful for tracking budgets. */
    val budgeted: Long,
    /** In cents (positive when money came in). */
    val received: Long,
    val hidden: Boolean = false,
    val groupHidden: Boolean = false,
) {
    val id: String get() = "$month-$categoryId"

    val isEffectivelyHidden: Boolean get() = hidden || groupHidden
}

data class CategoryBudget(
    val month: String,
    val categoryId: String,
    val categoryName: String,
    val groupId: String,
    val groupName: String,
    val groupSortOrder: Double,
    val categorySortOrder: Double,
    /** In cents. */
    val budgeted: Long,
    /** In cents (negative value, net of inflows). */
    val spent: Long,
    /** In cents (budgeted + spent + carryover). */
    val available: Long,
    val carryover: Long,
    val hidden: Boolean = false,
    val groupHidden: Boolean = false,
    /**
     * Goal target written by budget templates (`goal` column, cents). Null
     * when no template has run for this month.
     */
    val goal: Long? = null,
    /**
     * True when the goal came from a `#goal` directive (`long_goal` = 1):
     * progress is measured against the balance rather than this month's
     * budgeted amount, mirroring the web's BalanceWithCarryover.
     */
    val longGoal: Boolean = false,
    /**
     * The row's `carryover` flag ("Rollover overspending" in the web's
     * balance menu): a negative balance rolls into next month instead of
     * being absorbed by To Budget (envelope) or dropped (tracking). Distinct
     * from [carryover], which is the amount that actually rolled in.
     */
    val carryoverEnabled: Boolean = false,
) {
    val id: String get() = "$month-$categoryId"

    val isEffectivelyHidden: Boolean get() = hidden || groupHidden

    /** The value a goal measures — balance for long-term goals, budgeted for template automations. */
    val goalTrackedAmount: Long get() = if (longGoal) available else budgeted

    /** Positive = overfunded, negative = underfunded; null without a goal. */
    val differenceToGoal: Long? get() = goal?.let { goalTrackedAmount - it }

    val isGoalUnderfunded: Boolean get() = (differenceToGoal ?: 0) < 0

    val isOverspent: Boolean get() = available < 0

    /**
     * Overspending carried in from earlier months (negative, or 0 when the
     * carryover is a credit). Actual's web UI doesn't surface this either,
     * so a category can sit in the red with no visible cause in the current
     * month — the trigger for GH #138.
     */
    val rolledOverOverspending: Long get() = min(carryover, 0)

    /**
     * What the category actually had to spend this month (spent + remaining
     * available), so the bar agrees with the displayed Available amount
     * even when carryover makes it diverge from the budgeted figure.
     */
    private val progressCapacity: Long get() = abs(spent) + max(available, 0)

    /** Spent share of the row's progress bar, 0...1. */
    val progressFraction: Double
        get() = if (progressCapacity > 0) abs(spent).toDouble() / progressCapacity.toDouble() else 0.0

    /**
     * Fill for the row's progress bar: spent share, or the remaining share
     * when inverted. Zero capacity stays empty either way — there is no
     * money left to draw.
     */
    fun progressFraction(inverted: Boolean): Double =
        if (inverted && progressCapacity > 0) 1 - progressFraction else progressFraction

    /**
     * Healthy but close to running out: at least 80% of what this category
     * had to spend is gone. Overspent and fully spent categories have their
     * own status, so they're excluded rather than double-counted here.
     */
    val isApproachingLimit: Boolean
        get() = !isOverspent && available > 0 && spent < 0 && progressFraction >= 0.8

    /** A bar with no budget and no activity carries no information. */
    val showsProgressBar: Boolean get() = budgeted != 0L || spent != 0L

    /**
     * A compact, mode-neutral description of where this category stands.
     * Views translate it into envelope or tracking language as needed.
     */
    val progressState: CategoryProgressState
        get() = when {
            available < 0 -> CategoryProgressState.Overspent
            available == 0L && spent != 0L -> CategoryProgressState.Spent
            available == 0L && budgeted == 0L && carryover == 0L -> CategoryProgressState.Unassigned
            spent != 0L -> CategoryProgressState.Spending
            else -> CategoryProgressState.Funded
        }

    /**
     * Suggested *final assigned amounts*, never deltas. This keeps the UI on
     * Actual's existing setBudgetAmount path and makes every choice previewable.
     */
    fun quickAssignSuggestions(history: List<CategoryBudget>): List<QuickAssignSuggestion> {
        val suggestions = mutableListOf<QuickAssignSuggestion>()
        history.firstOrNull()?.let { lastMonth ->
            val spent = abs(min(lastMonth.spent, 0))
            if (spent > 0) suggestions += QuickAssignSuggestion(QuickAssignSuggestion.Kind.SpentLastMonth, spent)
            if (lastMonth.budgeted != 0L) {
                suggestions += QuickAssignSuggestion(QuickAssignSuggestion.Kind.AssignedLastMonth, lastMonth.budgeted)
            }
        }

        // One month of history would just duplicate Spent Last Month.
        val spending = history.map { abs(min(it.spent, 0)) }
        if (spending.size >= 2) {
            val average = roundHalfAwayFromZero(spending.sum().toDouble() / spending.size)
            if (average > 0) suggestions += QuickAssignSuggestion(QuickAssignSuggestion.Kind.AverageSpent, average)
        }

        if (available != 0L) {
            suggestions += QuickAssignSuggestion(QuickAssignSuggestion.Kind.ResetAvailable, budgeted - available)
        }
        if (budgeted != 0L) {
            suggestions += QuickAssignSuggestion(QuickAssignSuggestion.Kind.SetToZero, 0)
        }
        return suggestions
    }
}

/** Swift's `.rounded()`: half away from zero. */
fun roundHalfAwayFromZero(value: Double): Long =
    if (value < 0) -abs(value).roundToLong() else value.roundToLong()

/** Swift's `.rounded()` kept as a Double, for values a Long can't hold. */
fun roundedHalfAwayFromZero(value: Double): Double {
    val truncated = kotlin.math.truncate(value)
    return if (abs(value - truncated) >= 0.5) truncated + kotlin.math.sign(value) else truncated
}

enum class CategoryProgressState { Unassigned, Funded, Spending, Spent, Overspent }

data class QuickAssignSuggestion(val kind: Kind, val amount: Long) {
    enum class Kind(val rawValue: String) {
        SpentLastMonth("spentLastMonth"),
        AverageSpent("averageSpent"),
        AssignedLastMonth("assignedLastMonth"),
        ResetAvailable("resetAvailable"),
        SetToZero("setToZero"),
    }

    val id: Kind get() = kind
}

/**
 * Column sums for one category group, shown in the Compact style's group
 * header the way the PWA's table totals its group rows. Always built from a
 * group's full category list — "Hide Spent Categories" trims which rows are
 * drawn, and a header total that quietly dropped those categories would
 * disagree with the summary card at the top of the table.
 */
data class CategoryGroupTotals(val budgeted: Long, val spent: Long, val balance: Long) {
    constructor(categories: List<CategoryBudget>) : this(
        budgeted = categories.sumOf { it.budgeted },
        spent = categories.sumOf { it.spent },
        balance = categories.sumOf { it.available },
    )
}
