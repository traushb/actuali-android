package io.github.traushb.actuali.views.budget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AmountPill
import io.github.traushb.actuali.views.components.AnimatedAmountText
import io.github.traushb.actuali.views.components.AppAnimation
import io.github.traushb.actuali.views.components.DisclosureChevron
import io.github.traushb.actuali.views.components.StatusTone
import io.github.traushb.actuali.views.components.color
import io.github.traushb.actuali.views.components.container
import io.github.traushb.actuali.views.components.dimmedWhen
import io.github.traushb.actuali.views.components.onContainer
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import java.text.NumberFormat

/** The row actions a category's tappable cells offer, gathered for its long-press menu. */
class CategoryRowActions(
    val onShowDetails: (CategoryBudget) -> Unit,
    val onEditBudget: (CategoryBudget) -> Unit,
    /** Month narrows to one "yyyy-MM"; null means all time (GH #56). */
    val onShowTransactions: (CategoryBudget, String?) -> Unit,
    val onMoveMoney: (CategoryBudget) -> Unit,
    /** Null hides the item: callers gate it on the goal templates flag. */
    val onApplyTemplate: ((CategoryBudget) -> Unit)?,
    val onRename: (CategoryBudget) -> Unit,
    val onSetHidden: (CategoryBudget, Boolean) -> Unit,
)

/**
 * Spent-vs-available bar for a budget row — Material's linear indicator, its
 * gap and stop dot included: green while money remains, red once overspent.
 * Inverse mode draws the remaining share, so it starts full and drains — and
 * the status color moves to the track, or an overspent row would read as a
 * spent one.
 */
@Composable
fun CategoryProgressBar(category: CategoryBudget, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val inverse = store.showInverseBudgetProgressBars
    val fraction by animateFloatAsState(category.progressFraction(inverse).toFloat(), tween(AppAnimation.AMOUNT_MILLIS), label = "progress")
    val color = store.statusColor(category.progressState)
    val track = if (inverse || category.progressState == CategoryProgressState.Funded) color.copy(alpha = 0.25f) else ProgressIndicatorDefaults.linearTrackColor
    val description = L("%@, spent %lld percent of available", category.progressState.statusText(), Math.round(category.progressFraction * 100))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics { contentDescription = description },
        color = color,
        trackColor = track,
    )
}

/** A deliberately quiet status cue; the detail sheet carries the full plain-language status. */
@Composable
fun CompactCategoryStatusDot(state: CategoryProgressState) {
    val store = LocalBudgetStore.current
    val label = state.statusText()
    Box(Modifier.size(8.dp).background(store.statusColor(state), CircleShape).semantics { contentDescription = label }.testTag("categoryStatusDot"))
}

/**
 * The web's CarryoverIndicator: a small arrow just past the balance when the
 * category's overspending rolls into next month (GH #372). Drawn beside the
 * amount, offset outward, so amounts stay aligned whether or not a row rolls over.
 */
@Composable
fun RolloverIndicator(shown: Boolean, color: Color, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.CenterEnd) {
        content()
        if (shown) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = L("Overspending rolls over to next month"), tint = color, modifier = Modifier.size(10.dp).offset(x = 12.dp))
        }
    }
}

/**
 * The shared long-press menu for category rows — the same actions as the
 * row's tappable cells plus rename and hide/show. Nothing is a swipe action:
 * a row swipe would swallow the table's month navigation (GH #425).
 */
@Composable
fun CategoryRowMenu(expanded: Boolean, onDismiss: () -> Unit, category: CategoryBudget, actions: CategoryRowActions) {
    val store = LocalBudgetStore.current
    DropdownMenu(expanded, onDismissRequest = onDismiss) {
        @Composable
        fun Item(label: String, tag: String? = null, action: () -> Unit) {
            DropdownMenuItem(text = { Text(label) }, onClick = {
                onDismiss()
                action()
            }, modifier = tag?.let { Modifier.testTag(it) } ?: Modifier)
        }
        Item(L("Category Details")) { actions.onShowDetails(category) }
        Item(L("Rename Category"), "categoryRename.action") { actions.onRename(category) }
        Item(L("Edit Budgeted Amount")) { actions.onEditBudget(category) }
        actions.onApplyTemplate?.let { apply -> Item(L("Apply Budget Template")) { apply(category) } }
        Item(L("Transactions This Month")) { actions.onShowTransactions(category, category.month) }
        Item(L("All Transactions")) { actions.onShowTransactions(category, null) }
        // Zero balance: nothing to move, nothing to cover — same rule as the balance pill.
        if (category.available != 0L) Item(L(if (category.isOverspent) "Cover Overspending" else "Move Money")) { actions.onMoveMoney(category) }
        Item(L(if (category.hidden) "Show Category" else "Hide Category")) { actions.onSetHidden(category, !category.hidden) }
        if (store.showBudgetProgressBars && category.showsProgressBar) {
            val hidden = category.categoryId in store.hiddenBudgetProgressCategoryIDs
            Item(L(if (hidden) "Show progress bar" else "Hide progress bar")) { store.setBudgetProgressBarHidden(!hidden, category.categoryId) }
        }
    }
}

/**
 * Clean-style category row: name and a large Available amount up top, the
 * progress bar beneath, then tappable Budgeted/Spent captions. Long press
 * opens the row's actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CleanCategoryBudgetRow(category: CategoryBudget, actions: CategoryRowActions, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val tone = categoryBalanceTone(category, store.goalTemplatesEnabled)
    val monthTitle = MonthTitles.title(category.month)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = { menu = true }, onClick = { actions.onShowDetails(category) })
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)
                .dimmedWhen(category.isEffectivelyHidden),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = L("Details for %@", category.categoryName) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (store.showCategoryStatusDots) CompactCategoryStatusDot(category.progressState)
                    Text(category.categoryName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                // A zero balance has nothing to move and nothing to cover, so it stays a plain label.
                RolloverIndicator(category.carryoverEnabled, tone.color) {
                    AmountPill(
                        store.displayBalance(category.available),
                        if (store.hideBalances) StatusTone.Neutral else tone,
                        Modifier
                            .clip(CircleShape)
                            .clickable(enabled = category.available != 0L) { actions.onMoveMoney(category) }
                            .semantics { contentDescription = L(if (category.isOverspent) "Cover overspending for %@" else "Move money from %@", category.categoryName) },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            if (store.showBudgetProgressBars && category.showsProgressBar && category.categoryId !in store.hiddenBudgetProgressCategoryIDs) {
                CategoryProgressBar(category)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (store.showBudgetedAmounts) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .clickable { actions.onEditBudget(category) }
                            .padding(vertical = 4.dp)
                            .semantics(mergeDescendants = true) { contentDescription = L("Edit budgeted amount for %@", category.categoryName) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(L("Budgeted: %@", store.displayBalance(category.budgeted)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable { actions.onShowTransactions(category, category.month) }
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) { contentDescription = L("Transactions for %@ in %@", category.categoryName, monthTitle) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Green and signed so a deposit-only category doesn't read as spending (GH #102).
                    Text(
                        L("Spent: %@", store.displaySpentCaption(category.spent)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (category.spent > 0) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        CategoryRowMenu(menu, { menu = false }, category, actions)
    }
}

/** One income category: name and the amount received this month. Tracking budgets can budget income, so they also get a Budgeted caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IncomeCategoryRow(
    income: IncomeCategory,
    showsBudgeted: Boolean,
    onShowTransactions: (IncomeCategory, String?) -> Unit,
    onSetHidden: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = if (onSetHidden != null) ({ menu = true }) else null, onClick = { onShowTransactions(income, null) })
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .dimmedWhen(income.isEffectivelyHidden),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    income.categoryName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { contentDescription = L("All transactions for %@", income.categoryName) },
                )
                AnimatedAmountText(
                    store.displayBalance(income.received),
                    color = if (income.received > 0) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onShowTransactions(income, income.month) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { contentDescription = L("Transactions for %@ in %@", income.categoryName, MonthTitles.title(income.month)) },
                )
            }
            if (showsBudgeted) {
                Text(L("Budgeted: %@", store.displayBalance(income.budgeted)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Hide/show lives in the long-press menu, not a swipe: a row swipe swallows the table's month navigation (GH #425).
        if (onSetHidden != null) {
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(L(if (income.hidden) "Show" else "Hide")) }, onClick = {
                    menu = false
                    onSetHidden(!income.hidden)
                })
            }
        }
    }
}

/** One group-header action, shared by the Clean and Compact headers. */
data class GroupHeaderAction(val title: String, val handler: () -> Unit) {
    companion object {
        fun groupActions(
            isHidden: Boolean,
            onApplyTemplate: (() -> Unit)? = null,
            onOverwriteTemplate: (() -> Unit)? = null,
            onRename: (() -> Unit)?,
            onSetHidden: ((Boolean) -> Unit)?,
        ): List<GroupHeaderAction> = buildList {
            onApplyTemplate?.let { add(GroupHeaderAction(L("Apply Budget Template"), it)) }
            onOverwriteTemplate?.let { add(GroupHeaderAction(L("Overwrite with Budget Template"), it)) }
            onRename?.let { add(GroupHeaderAction(L("Rename Group"), it)) }
            onSetHidden?.let { add(GroupHeaderAction(L(if (isHidden) "Show Group" else "Hide Group")) { it(!isHidden) }) }
        }
    }
}

/**
 * The clean table's group header: tap collapses, long press opens the
 * group's actions. Income groups show the money received, expense groups the
 * sum of their categories' balances.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BudgetGroupHeader(
    name: String,
    isCollapsed: Boolean,
    isHidden: Boolean,
    actions: List<GroupHeaderAction>,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    receivedTotal: Long? = null,
    balanceTotal: Long? = null,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val state = L(if (isCollapsed) "collapsed" else "expanded")
    val label = when {
        receivedTotal != null -> L("%@, %@, received %@", name, state, store.displayBalance(receivedTotal))
        balanceTotal != null -> L("%@, %@, balance %@", name, state, store.displayBalance(balanceTotal))
        else -> L("%@, %@", name, state)
    }
    // Opaque page color: the header pins while its group's tiles scroll under it.
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = if (actions.isNotEmpty()) ({ menu = true }) else null, onClick = onToggleCollapse)
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp)
                .dimmedWhen(isHidden)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = label
                    stateDescription = state
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DisclosureChevron(!isCollapsed)
            Text(name, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 2, modifier = Modifier.weight(1f))
            when {
                receivedTotal != null -> Text(L("Received %@", store.displayBalance(receivedTotal)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                balanceTotal != null -> AnimatedAmountText(
                    store.displayBalance(balanceTotal),
                    color = if (balanceTotal < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            for (action in actions) {
                DropdownMenuItem(text = { Text(action.title) }, onClick = {
                    menu = false
                    action.handler()
                })
            }
        }
    }
}

/**
 * How much of the month's budget is spent, as Expressive's wavy indicator with
 * its percentage — red once spending passes what's budgeted. Nothing when
 * nothing is budgeted: there's no share to show.
 */
@Composable
fun SpendingProgress(spent: Long, budgeted: Long, modifier: Modifier = Modifier) {
    if (budgeted <= 0) return
    val fraction = spent.toFloat() / budgeted
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(AppAnimation.AMOUNT_MILLIS), label = "spending")
    val percent = remember(fraction) { NumberFormat.getPercentInstance().format(fraction.toDouble()) }
    val color = if (fraction > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val label = "${L("Spent")}, $percent"
    Row(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearWavyProgressIndicator(progress = { shown }, color = color, modifier = Modifier.weight(1f))
        Text(percent, style = MaterialTheme.typography.labelLargeEmphasized, color = color)
    }
}

/** Whether [month] ("YYYY-MM") is before the current calendar month; zero-padded, so a string compare is exact. */
private fun isPastMonth(month: String) = month < BudgetViewLogic.currentMonthString()

/**
 * Clean-style summary card: a 2x2 grid whose reading order follows the
 * money — came in, allocated, went out, left over. What's left sits on a
 * tonal chip of its status, the one figure to act on.
 */
@Composable
fun CleanBudgetSummary(budget: BudgetMonth, showsBudgeted: Boolean, onOpenSummary: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val excluded = store.excludedFromSpentCategoryIds
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().testTag("budget.topBox")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BudgetSummaryStat(L("Income"), store.displayBalance(budget.totalIncome), Modifier.weight(1f))
                // GH #562: Income stands alone when Budgeted is hidden, so no row carries more than two amounts.
                if (showsBudgeted) BudgetSummaryStat(L("Budgeted"), store.displayBalance(budget.totalBudgeted), Modifier.weight(1f), alignEnd = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BudgetSummaryStat(L("Spent"), store.displayBalance(-budget.totalSpent(excluded)), Modifier.weight(1f))
                // Envelope budgets lead with unallocated funds; tracking budgets
                // report savings — actual for a finished month, projected otherwise.
                val toBudget = budget.toBudget
                if (toBudget != null) {
                    BudgetSummaryStat(
                        L(if (toBudget < 0) "Overbudgeted" else "To Budget"),
                        store.displayBalance(toBudget),
                        Modifier.weight(1f),
                        alignEnd = true,
                        tone = StatusTone.of(toBudget, store.hideBalances),
                        onClick = onOpenSummary,
                        hint = L("Budget Summary"),
                    )
                } else {
                    val past = isPastMonth(budget.month)
                    val value = if (past) budget.savedActual(excluded) else budget.projectedSavings
                    BudgetSummaryStat(
                        L(if (past) "Saved" else "Projected"),
                        store.displayBalance(value),
                        Modifier.weight(1f),
                        alignEnd = true,
                        tone = StatusTone.of(value, store.hideBalances),
                    )
                }
            }
            SpendingProgress(-budget.totalSpent(excluded), budget.totalBudgeted, Modifier.padding(top = 4.dp))
        }
    }
}

/** One figure of the summary grid; with a [tone] it sits on that tone's chip. */
@Composable
private fun BudgetSummaryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    tone: StatusTone? = null,
    onClick: (() -> Unit)? = null,
    hint: String? = null,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .then(if (tone != null) Modifier.clip(shape).background(tone.container, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).testTag("budgetToBudgetAction") else Modifier)
            .then(if (tone != null) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier)
            .semantics(mergeDescendants = true) { hint?.let { stateDescription = it } },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tone?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedAmountText(
            value,
            color = tone?.onContainer ?: Color.Unspecified,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.headlineSmallEmphasized.fontSize),
        )
    }
}

/** What's left to budget as one tonal card, shown in Clean style when the overview is hidden; tapping it opens the budget summary. */
@Composable
fun ReadyToBudgetRow(budget: BudgetMonth, onOpenSummary: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val amount = budget.toBudget ?: 0
    val label = L(if (amount < 0) "Overbudgeted" else "Ready to Budget")
    val value = store.displayBalance(amount)
    val tone = StatusTone.of(amount, store.hideBalances)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = tone.container,
        contentColor = tone.onContainer,
        onClick = onOpenSummary,
        modifier = modifier.fillMaxWidth().testTag("budget.readyToBudget").semantics { contentDescription = "$label, $value" },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            AnimatedAmountText(value, color = tone.onContainer, style = MaterialTheme.typography.titleLargeEmphasized)
        }
    }
}

/** Each filter's glyph: the chips read at a glance, before their counts. */
private val BudgetCategoryFilter.icon: ImageVector
    get() = when (this) {
        BudgetCategoryFilter.All -> Icons.Outlined.Category
        BudgetCategoryFilter.Overspent -> Icons.Outlined.ErrorOutline
        BudgetCategoryFilter.Unassigned -> Icons.Outlined.HourglassEmpty
        BudgetCategoryFilter.ApproachingLimit -> Icons.Outlined.Speed
        BudgetCategoryFilter.OnTrack -> Icons.Outlined.CheckCircle
    }

/** Status filters stay visible above the category list, so checking the month never needs a menu or a scroll past a large card. */
@Composable
fun BudgetCheckInStrip(budget: BudgetMonth, selection: BudgetCategoryFilter, onSelect: (BudgetCategoryFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (filter in BudgetCategoryFilter.entries) {
            val title = filter.title(budget.categoryBudgets.count(filter::includes), budget.isTrackingBudget)
            FilterChip(
                selected = selection == filter,
                onClick = { onSelect(filter) },
                label = { Text(title) },
                leadingIcon = { Icon(filter.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.testTag("budgetFilter-${filter.rawValue}").semantics {
                    contentDescription = L("Show %@ categories", title)
                    selected = selection == filter
                },
            )
        }
    }
}

/** "N uncategorized": the strip filters categories, so it can't reach these; this is the in-app route to them. */
@Composable
fun UncategorizedLink(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer, onClick = onOpen, modifier = modifier.fillMaxWidth().testTag("budgetUncategorized")) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("%lld uncategorized", count.toLong()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
        }
    }
}
