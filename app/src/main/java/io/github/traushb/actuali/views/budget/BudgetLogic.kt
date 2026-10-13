package io.github.traushb.actuali.views.budget

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryGroupTotals
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.EnvelopeBudgetSummary
import io.github.traushb.actuali.services.GoalTemplateOutcome
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.shiftBudgetMonth
import io.github.traushb.actuali.views.components.StatusTone
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** The Budget tab's status filters, shown as the check-in strip above the table. */
enum class BudgetCategoryFilter(val rawValue: String) {
    All("all"),
    Overspent("overspent"),
    Unassigned("unassigned"),
    ApproachingLimit("approachingLimit"),
    OnTrack("onTrack"),
    ;

    fun includes(category: CategoryBudget): Boolean = when (this) {
        All -> true
        Overspent -> category.progressState == CategoryProgressState.Overspent
        Unassigned -> category.progressState == CategoryProgressState.Unassigned
        ApproachingLimit -> category.isApproachingLimit
        OnTrack -> category.progressState == CategoryProgressState.Funded || category.progressState == CategoryProgressState.Spending
    }

    fun title(count: Int, isTrackingBudget: Boolean, locale: Locale = Locale.getDefault()): String {
        val key = when (this) {
            All -> "budget.filter.all %lld"
            Overspent -> if (isTrackingBudget) "budget.filter.overBudget %lld" else "budget.filter.overspent %lld"
            Unassigned -> if (isTrackingBudget) "budget.filter.noBudget %lld" else "budget.filter.notFunded %lld"
            ApproachingLimit -> if (isTrackingBudget) "budget.filter.nearBudget %lld" else "budget.filter.almostSpent %lld"
            OnTrack -> if (isTrackingBudget) "budget.filter.withinBudget %lld" else "budget.filter.onTrack %lld"
        }
        return ReportStrings.localized(key, count.toLong(), locale = locale)
    }
}

/** One expense group as the table draws it. */
data class CategoryGroupSection(
    val id: String,
    val name: String,
    val isHidden: Boolean,
    /** The rows to draw, after "Hide Spent Categories" filtering. */
    val categories: List<CategoryBudget>,
    /** Totals over the group's non-hidden category list. */
    val totals: CategoryGroupTotals,
)

/** The Budget tab's pure rules, kept out of the composables so they're unit-tested. */
object BudgetViewLogic {
    const val INCOME_GROUP_COLLAPSE_ID = "__income_group__"

    fun currentMonthString(today: LocalDate = LocalDate.now()): String = "%04d-%02d".format(today.year, today.monthValue)

    fun shiftMonth(month: String, offset: Int): String = BudgetStore.shiftBudgetMonth(month, offset) ?: month

    /** IDs controlled by Expand/Collapse All for the budget currently shown. */
    fun displayedGroupIDs(groupIDs: List<String>, hasIncome: Boolean): Set<String> =
        if (hasIncome) groupIDs.toSet() + INCOME_GROUP_COLLAPSE_ID else groupIDs.toSet()

    fun renameName(draft: String): String? = draft.trim().ifEmpty { null }

    /**
     * The note to offer for [selectedMonth]. Until that month's own read
     * lands, the previous month's text is still in state; offering it would
     * seed the editor with it under the new month's id and a save would
     * overwrite the new month's note. Unsupported hides the item instead.
     */
    fun monthNote(note: EntityNote, loadedMonth: String?, selectedMonth: String): EntityNote =
        if (loadedMonth == selectedMonth) note else EntityNote.unsupported

    /**
     * Every category in the group, not just the rows the filters left on
     * screen: a zero-available category dropped by "Hide Spent Categories" is
     * exactly the one that needs its template.
     */
    fun groupTemplateCategoryIds(groupId: String, budget: BudgetMonth): Set<String> =
        budget.allCategoryBudgets.filter { it.groupId == groupId }.mapTo(HashSet()) { it.categoryId }

    /** The income rows to draw; empty when the user hides the Income group. */
    fun displayedIncomeCategories(budget: BudgetMonth, showHidden: Boolean, hideIncomeGroup: Boolean): List<IncomeCategory> = when {
        hideIncomeGroup -> emptyList()
        showHidden -> budget.allIncomeCategories
        else -> budget.incomeCategories
    }

    data class TemplateAlert(val title: String, val message: String)

    /** The web's template-run toasts as one alert, shared by the month and single-category runs. */
    fun templateAlert(outcome: GoalTemplateOutcome, singleCategory: Boolean = false, locale: Locale = Locale.getDefault()): TemplateAlert = when (outcome) {
        is GoalTemplateOutcome.Applied -> TemplateAlert(
            ReportStrings.text("Templates Applied", locale),
            ReportStrings.localized("Successfully applied templates to %lld categories.", outcome.count.toLong(), locale = locale),
        )
        GoalTemplateOutcome.UpToDate -> if (singleCategory) {
            TemplateAlert(ReportStrings.text("Apply Budget Template", locale), ReportStrings.text("No templates to apply for this category.", locale))
        } else {
            TemplateAlert(ReportStrings.text("Templates Applied", locale), ReportStrings.text("All templates are up to date.", locale))
        }
        GoalTemplateOutcome.CheckPassed -> TemplateAlert(ReportStrings.text("Check Passed", locale), ReportStrings.text("All templates passed the check.", locale))
        is GoalTemplateOutcome.Errors -> TemplateAlert(ReportStrings.text("Template Errors", locale), outcome.messages.joinToString("\n\n"))
        is GoalTemplateOutcome.Failed -> TemplateAlert(ReportStrings.text("Template Error", locale), outcome.message)
    }

    /** The month a horizontal swipe on the table moves to, as iOS's drag gesture: far enough, and mostly sideways. */
    fun monthSwipeTarget(month: String, dx: Float, dy: Float, threshold: Float): String? {
        if (abs(dx) <= abs(dy) * 1.5f || abs(dx) <= threshold) return null
        return shiftMonth(month, if (dx > 0) -1 else 1)
    }

    /** Next month back through the prior year, newest first, padded with the selection when swiping has left that window. */
    fun monthOptions(selected: String, current: String = currentMonthString()): List<String> {
        val months = (-12..1).map { shiftMonth(current, it) }.toMutableList()
        if (selected !in months) {
            months += selected
            months.sort()
        }
        return months.reversed()
    }

    /**
     * The groups the table draws, sorted. An explicit filter is its own
     * visibility rule, and reordering needs every visible category. A group
     * just created has no categories yet; it's drawn anyway in the unfiltered
     * table so creating one doesn't look like it did nothing (GH #284).
     */
    fun groupedCategories(
        budget: BudgetMonth,
        store: BudgetStore,
        filter: BudgetCategoryFilter,
        isReordering: Boolean,
    ): List<CategoryGroupSection> {
        val categories = if (store.showHiddenCategories) budget.allCategoryBudgets else budget.categoryBudgets
        val unfiltered = isReordering || filter == BudgetCategoryFilter.All
        val sections = categories.groupBy { it.groupId }.mapNotNull { (groupId, items) ->
            val first = items.first()
            val base = if (unfiltered) store.visibleCategoryBudgets(items, includeSpent = isReordering) else items.filter(filter::includes)
            val visible = base.sortedBy { it.categorySortOrder }
            // A group whose rows are all hidden drops out rather than stranding a header over an empty card.
            if (visible.isEmpty()) return@mapNotNull null
            first.groupSortOrder to CategoryGroupSection(
                groupId,
                first.groupName,
                first.groupHidden,
                visible,
                CategoryGroupTotals((if (unfiltered) items else visible).filter { !it.isEffectivelyHidden }),
            )
        } + store.categoryGroups
            .filter { unfiltered && !it.isIncome && (store.showHiddenCategories || !it.hidden) && it.categories.isEmpty() }
            .map { it.sortOrder to CategoryGroupSection(it.id, it.name, it.hidden, emptyList(), CategoryGroupTotals(emptyList())) }
        return sections.sortedBy { it.first }.map { it.second }
    }
}

/**
 * Where a dragged (or stepped) category lands among the groups, and the
 * arrangement that results. Kept free of views so the placement rules are
 * unit-tested.
 */
object CategoryReorderPlanner {
    /** One group's categories, top to bottom. */
    data class Entry(val id: String, val ids: List<String>)

    /** A position: [index] among the group's *other* categories, so the dragged one never counts itself. */
    data class Slot(val groupId: String, val index: Int)

    /** A row's vertical extent, in one shared coordinate space. */
    data class Frame(val top: Float, val bottom: Float) {
        val middle: Float get() = (top + bottom) / 2
    }

    fun slot(dragged: String, arrangement: List<Entry>): Slot? {
        for (entry in arrangement) {
            val position = entry.ids.indexOf(dragged)
            if (position >= 0) return Slot(entry.id, position)
        }
        return null
    }

    /**
     * The slot a dragged row's centre is over: the group whose card the
     * centre is in, or the nearest card when it's in a gap or over a header.
     * Within the group, the index is the number of other rows whose middle
     * sits above the centre. An empty group's header supplies its bounds.
     */
    fun destination(dragged: String, centerY: Float, arrangement: List<Entry>, frames: Map<String, Frame>): Slot? {
        var best: Pair<Int, Float>? = null
        arrangement.forEachIndexed { position, entry ->
            var rects = entry.ids.mapNotNull { frames[it] }
            if (entry.ids.isEmpty()) frames[headerKey(entry.id)]?.let { rects = listOf(it) }
            val top = rects.minOfOrNull { it.top } ?: return@forEachIndexed
            val bottom = rects.maxOf { it.bottom }
            val distance = when {
                centerY < top -> top - centerY
                centerY > bottom -> centerY - bottom
                else -> 0f
            }
            if (best == null || distance < best!!.second) best = position to distance
        }
        val group = best?.first ?: return null
        val entry = arrangement[group]
        val index = entry.ids.filter { it != dragged }.count { (frames[it]?.middle ?: Float.POSITIVE_INFINITY) < centerY }
        return Slot(entry.id, index)
    }

    /** [arrangement] with [dragged] taken out and put at [slot]. */
    fun moved(arrangement: List<Entry>, dragged: String, slot: Slot): List<Entry> {
        val result = arrangement.map { it.copy(ids = it.ids - dragged) }
        val group = result.indexOfFirst { it.id == slot.groupId }
        if (group < 0) return arrangement
        val ids = result[group].ids.toMutableList().apply { add(minOf(slot.index, size), dragged) }
        return result.toMutableList().apply { set(group, result[group].copy(ids = ids)) }
    }

    /** The write that makes the database match [arrangement]: [dragged] into its group, before the category now below it (null = last). */
    fun target(dragged: String, arrangement: List<Entry>): Pair<String, String?>? {
        for (entry in arrangement) {
            val position = entry.ids.indexOf(dragged)
            if (position >= 0) return entry.id to entry.ids.getOrNull(position + 1)
        }
        return null
    }

    /**
     * Where one step up (-1) or down (+1) puts the category: the neighbouring
     * position in its group, spilling into the end of the previous group or
     * the start of the next one at an edge. Null at the very top or bottom.
     */
    fun step(direction: Int, dragged: String, arrangement: List<Entry>): Slot? {
        val current = slot(dragged, arrangement) ?: return null
        val group = arrangement.indexOfFirst { it.id == current.groupId }
        if (group < 0) return null
        val count = arrangement[group].ids.size
        if (direction < 0) {
            if (current.index > 0) return Slot(current.groupId, current.index - 1)
            if (group == 0) return null
            val previous = arrangement[group - 1]
            return Slot(previous.id, previous.ids.size)
        }
        if (current.index < count - 1) return Slot(current.groupId, current.index + 1)
        if (group + 1 >= arrangement.size) return null
        return Slot(arrangement[group + 1].id, 0)
    }

    /** The frame key a group's header is measured under. */
    fun headerKey(groupId: String) = "header-$groupId"

    /** The index a dragged group's centre is over, among the *other* groups, counted by their header frames. */
    fun groupDestination(dragged: String, centerY: Float, order: List<String>, frames: Map<String, Frame>): Int =
        order.filter { it != dragged }.count { (frames[headerKey(it)]?.middle ?: Float.POSITIVE_INFINITY) < centerY }

    /** [order] with [dragged] taken out and put at [index] among the others. */
    fun movedGroup(order: List<String>, dragged: String, index: Int): List<String> =
        (order - dragged).toMutableList().apply { add(index.coerceIn(0, size), dragged) }

    /** The group now below [dragged] (null when it is last): what the save places it before. */
    fun groupTarget(dragged: String, order: List<String>): String? {
        val position = order.indexOf(dragged)
        return if (position >= 0) order.getOrNull(position + 1) else null
    }

    /** Where one step up (-1) or down (+1) puts a group among the others; null at the top or bottom. */
    fun groupStep(direction: Int, dragged: String, order: List<String>): Int? {
        val position = order.indexOf(dragged).takeIf { it >= 0 } ?: return null
        return (position + direction).takeIf { it in order.indices }
    }
}

/**
 * Everything the move-money sheet needs, captured at tap time: the selected
 * category, if any, and the month it lives in.
 */
data class BudgetTransferContext(val category: CategoryBudget?, val budget: BudgetMonth) {
    val amount: Long get() = category?.available ?: budget.toBudget ?: 0

    /**
     * Covering ranks sources that can fully solve the problem first, then
     * prefers the same group and the smallest sufficient balance. Partial
     * sources follow largest-first. Moving a surplus keeps table order.
     */
    val rankedCategories: List<CategoryBudget>
        get() {
            val candidates = budget.categoryBudgets.filter { it.categoryId != category?.categoryId }.filter { if (amount < 0) it.available > 0 else true }
            val tableOrder = compareBy<CategoryBudget>({ it.groupSortOrder }, { it.categorySortOrder })
            if (amount >= 0) return candidates.sortedWith(tableOrder)
            val needed = abs(amount)
            return candidates.sortedWith { lhs, rhs ->
                val lhsCovers = lhs.available >= needed
                val rhsCovers = rhs.available >= needed
                val lhsSameGroup = lhs.groupId == category?.groupId
                val rhsSameGroup = rhs.groupId == category?.groupId
                when {
                    lhsCovers != rhsCovers -> if (lhsCovers) -1 else 1
                    lhsSameGroup != rhsSameGroup -> if (lhsSameGroup) -1 else 1
                    lhs.available != rhs.available -> if (lhsCovers) lhs.available.compareTo(rhs.available) else rhs.available.compareTo(lhs.available)
                    else -> tableOrder.compare(lhs, rhs)
                }
            }
        }

    val canUseToBudget: Boolean
        get() {
            val category = category ?: return false
            val toBudget = budget.toBudget ?: return false
            return if (category.available < 0) toBudget > 0 else true
        }

    companion object {
        fun toBudgetIn(budget: BudgetMonth) = BudgetTransferContext(null, budget)
    }
}

/** Actions available from the envelope budget summary result menu. */
enum class EnvelopeBudgetSummaryAction {
    ResetBuffer,
    DisableAutoBuffer,
    MoveToCategory,
    HoldForNextMonth,
    CoverFromCategory,
    ;

    companion object {
        fun available(summary: EnvelopeBudgetSummary): List<EnvelopeBudgetSummaryAction> = buildList {
            if (summary.toBudget > 0) {
                add(MoveToCategory)
                if (summary.autoBuffered == 0L) add(HoldForNextMonth)
            } else if (summary.toBudget < 0) {
                add(CoverFromCategory)
            }
            if (summary.forNextMonth > 0) add(if (summary.manualBuffered == 0L) DisableAutoBuffer else ResetBuffer)
        }
    }
}

enum class CompactBudgetColumn(val rawValue: String) {
    Budgeted("Budgeted"),
    Spent("Spent"),
    Balance("Balance"),
    Received("Received"),
    ;

    fun label(locale: Locale = Locale.getDefault()): String = ReportStrings.text(rawValue, locale)
}

enum class CompactBalanceTone {
    Negative,
    Zero,
    Positive,
    Masked,
    ;

    companion object {
        fun of(amount: Long, isMasked: Boolean): CompactBalanceTone = when {
            isMasked -> Masked
            amount < 0 -> Negative
            amount == 0L -> Zero
            else -> Positive
        }
    }
}

/** Which amount columns the Compact table draws, for expenses and the income section beneath them. */
data class CompactBudgetTableLayout(val expenseColumns: List<CompactBudgetColumn>, val incomeColumns: List<CompactBudgetColumn?>) {
    companion object {
        /** [isTrackingBudget] gates income's Budgeted column (only tracking budgets budget income); [showsBudgeted] is the user's preference. */
        fun of(isTrackingBudget: Boolean, showsSpent: Boolean, showsBudgeted: Boolean = true): CompactBudgetTableLayout {
            val expense = listOfNotNull(
                CompactBudgetColumn.Budgeted.takeIf { showsBudgeted },
                CompactBudgetColumn.Spent.takeIf { showsSpent },
                CompactBudgetColumn.Balance,
            )
            val income = expense.map { column ->
                when (column) {
                    CompactBudgetColumn.Budgeted -> CompactBudgetColumn.Budgeted.takeIf { isTrackingBudget }
                    CompactBudgetColumn.Spent -> null
                    CompactBudgetColumn.Balance -> CompactBudgetColumn.Received
                    CompactBudgetColumn.Received -> null
                }
            }
            return CompactBudgetTableLayout(expense, income)
        }
    }
}

/** The Compact overview: To Budget (or Income) first, then the month's column totals. */
data class CompactBudgetOverview(val leading: Stat, val columns: List<Stat>) {
    data class Stat(val kind: Kind, val amount: Long) {
        enum class Kind { ToBudget, Income, Budgeted, Spent, Saved, Projected, Balance }

        fun label(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
            when (kind) {
                Kind.ToBudget -> "To Budget"
                Kind.Income -> "Income"
                Kind.Budgeted -> "Budgeted"
                Kind.Spent -> "Spent"
                Kind.Saved -> "Saved"
                Kind.Projected -> "Projected"
                Kind.Balance -> "Balance"
            },
            locale,
        )

        /** The month's bottom line, drawn as a colored pill. */
        val isResult: Boolean get() = kind == Kind.Balance || kind == Kind.Projected || kind == Kind.Saved
    }

    companion object {
        fun of(
            budget: BudgetMonth,
            showsSpent: Boolean,
            showsBudgeted: Boolean = true,
            excludedFromSpentCategoryIds: Set<String> = emptySet(),
            currentMonth: String,
        ): CompactBudgetOverview {
            val toBudget = budget.toBudget
            val leading = if (toBudget != null) Stat(Stat.Kind.ToBudget, toBudget) else Stat(Stat.Kind.Income, budget.totalIncome)
            val columns = buildList {
                if (showsBudgeted) add(Stat(Stat.Kind.Budgeted, budget.totalBudgeted))
                if (showsSpent) add(Stat(Stat.Kind.Spent, budget.totalSpent(excludedFromSpentCategoryIds)))
                add(
                    when {
                        !budget.isTrackingBudget -> Stat(Stat.Kind.Balance, budget.totalAvailable)
                        budget.month < currentMonth -> Stat(Stat.Kind.Saved, budget.savedActual(excludedFromSpentCategoryIds))
                        else -> Stat(Stat.Kind.Projected, budget.projectedSavings)
                    },
                )
            }
            return CompactBudgetOverview(leading, columns)
        }
    }
}

/**
 * A category balance's pill — the web's `makeBalanceAmountStyle`: overspent
 * is always red; with goal templates on and a goal on the row, yellow marks
 * an underfunded goal and green a funded one; otherwise gray at zero.
 */
fun categoryBalanceTone(category: CategoryBudget, goalsEnabled: Boolean): StatusTone = when {
    category.isOverspent -> StatusTone.Negative
    goalsEnabled && category.goal != null -> if (category.isGoalUnderfunded) StatusTone.Warning else StatusTone.Positive
    category.available == 0L -> StatusTone.Neutral
    else -> StatusTone.Positive
}
