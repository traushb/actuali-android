package io.github.traushb.actuali.views.budget

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryGroupTotals
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AmountPill
import io.github.traushb.actuali.views.components.AnimatedAmountText
import io.github.traushb.actuali.views.components.DisclosureChevron
import io.github.traushb.actuali.views.components.StatusTone
import io.github.traushb.actuali.views.components.color
import io.github.traushb.actuali.views.components.container
import io.github.traushb.actuali.views.components.dimmedWhen
import io.github.traushb.actuali.views.components.onContainer
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive

private val TitleColumnWidth = 132.dp
private val AmountColumnSpacing = 4.dp

/** The balance column is the widest: its figure sits in a pill. Income's Received column lines up under it. */
private const val BALANCE_COLUMN_WEIGHT = 1.3f

private fun columnWeight(isLast: Boolean) = if (isLast) BALANCE_COLUMN_WEIGHT else 1f

/** Rows sit in tiles inset 16dp with 12dp of padding inside; headers and the overview inset to match, so every column lines up. */
private val RowPadding = 12.dp
private val HeaderInset = 16.dp + RowPadding

/** Accessibility text sizes stack each amount under its label instead of squeezing columns. */
@Composable
private fun isStacked(): Boolean = LocalDensity.current.fontScale >= 1.8f

/** One table amount: balances in a pill of their tone, other figures plain (dimmed at zero). */
@Composable
private fun CompactAmountText(amount: Long, modifier: Modifier = Modifier, balanceTone: StatusTone? = null) {
    val store = LocalBudgetStore.current
    val text = store.displayBalance(amount)
    val style = MaterialTheme.typography.bodySmall
    // A large figure shrinks to its column rather than losing digits to an ellipsis.
    val autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize)
    if (balanceTone != null) {
        AmountPill(
            text,
            if (store.hideBalances) StatusTone.Neutral else balanceTone,
            modifier,
            style = style,
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
            autoSize = autoSize,
        )
    } else {
        val color = if (amount == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        AnimatedAmountText(text, modifier, color, style.copy(textAlign = TextAlign.End), autoSize)
    }
}

/** The title column and the amount columns, the way every Compact row lines up. */
@Composable
private fun CompactColumns(title: @Composable () -> Unit, amounts: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(TitleColumnWidth)) { title() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AmountColumnSpacing), verticalAlignment = Alignment.CenterVertically, content = amounts)
    }
}

/** The Compact overview card: To Budget (or Income) on its tonal chip, then the month's column totals, lined up over the table's columns. */
@Composable
fun CompactBudgetSummary(budget: BudgetMonth, showsSpent: Boolean, showsBudgeted: Boolean, onOpenSummary: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val overview = CompactBudgetOverview.of(budget, showsSpent, showsBudgeted, store.excludedFromSpentCategoryIds, BudgetViewLogic.currentMonthString())

    @Composable
    fun Stat(stat: CompactBudgetOverview.Stat, modifier: Modifier = Modifier, alignEnd: Boolean = true) {
        val leadingToBudget = stat.kind == CompactBudgetOverview.Stat.Kind.ToBudget
        val label = if (leadingToBudget) L(if (stat.amount < 0) "Overbudgeted" else "To Budget") else stat.label()
        val tone = StatusTone.of(stat.amount, store.hideBalances)
        val shape = MaterialTheme.shapes.large
        Column(
            modifier
                .then(if (leadingToBudget) Modifier.clip(shape).background(tone.container, shape).clickable(onClick = onOpenSummary).padding(horizontal = 10.dp, vertical = 6.dp) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = L("%@, %@", label, store.displayBalance(stat.amount)) },
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (leadingToBudget) tone.onContainer else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            when {
                stat.isResult -> CompactAmountText(stat.amount, balanceTone = tone)
                leadingToBudget -> AnimatedAmountText(
                    store.displayBalance(stat.amount),
                    color = tone.onContainer,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.titleSmallEmphasized.fontSize),
                )
                else -> AnimatedAmountText(store.displayBalance(stat.amount), style = MaterialTheme.typography.labelLarge, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize))
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = RowPadding, vertical = 12.dp)
            .testTag(if (isStacked()) "compactBudgetOverview.stacked" else "compactBudgetOverview"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isStacked()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(overview.leading, alignEnd = false)
                    for (stat in overview.columns) Stat(stat, Modifier.fillMaxWidth())
                }
            } else {
                CompactColumns({ Stat(overview.leading, alignEnd = false) }) {
                    overview.columns.forEachIndexed { index, stat -> Stat(stat, Modifier.weight(columnWeight(index == overview.columns.lastIndex))) }
                }
            }
            SpendingProgress(-budget.totalSpent(store.excludedFromSpentCategoryIds), budget.totalBudgeted)
        }
    }
}

/** A Compact group header: tap collapses, long press opens the group's actions; totals line up under the columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactBudgetGroupHeader(
    name: String,
    isCollapsed: Boolean,
    isHidden: Boolean,
    actions: List<GroupHeaderAction>,
    totals: CategoryGroupTotals?,
    showsSpent: Boolean,
    showsBudgeted: Boolean,
    onToggleCollapse: () -> Unit,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val layout = CompactBudgetTableLayout.of(isTrackingBudget = false, showsSpent = showsSpent, showsBudgeted = showsBudgeted)
    fun amount(column: CompactBudgetColumn): Long = when (column) {
        CompactBudgetColumn.Budgeted -> totals?.budgeted ?: 0
        CompactBudgetColumn.Spent -> totals?.spent ?: 0
        else -> totals?.balance ?: 0
    }
    val state = L(if (isCollapsed) "collapsed" else "expanded")
    val label = if (totals == null) {
        L("%@, %@", name, state)
    } else {
        val budgeted = store.displayBalance(totals.budgeted).takeIf { showsBudgeted }
        val spent = store.displayBalance(totals.spent).takeIf { showsSpent }
        val balance = store.displayBalance(totals.balance)
        when {
            budgeted == null && spent == null -> L("%@, %@, balance %@", name, state, balance)
            budgeted == null -> L("%@, %@, spent %@, balance %@", name, state, spent, balance)
            spent == null -> L("%@, %@, budgeted %@, balance %@", name, state, budgeted, balance)
            else -> L("%@, %@, budgeted %@, spent %@, balance %@", name, state, budgeted, spent, balance)
        }
    }
    // Opaque page color: the header pins while its group's tiles scroll under it.
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = if (actions.isNotEmpty()) ({ menu = true }) else null, onClick = onToggleCollapse)
                .padding(start = HeaderInset, end = HeaderInset, top = 16.dp, bottom = 8.dp)
                .dimmedWhen(isHidden)
                .testTag("compactBudgetGroup.$name")
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = label
                },
        ) {
            val title = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisclosureChevron(!isCollapsed)
                    Text(name, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                }
            }
            // The same column geometry with totals off, so the preference changes the content, not the header's size.
            CompactColumns(title) {
                layout.expenseColumns.forEachIndexed { index, column ->
                    Column(Modifier.weight(columnWeight(index == layout.expenseColumns.lastIndex)).alpha(if (totals == null) 0f else 1f), horizontalAlignment = Alignment.End) {
                        Text(column.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        CompactAmountText(amount(column), balanceTone = if (column == CompactBudgetColumn.Balance) StatusTone.of(amount(column)) else null)
                    }
                }
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

/** A Compact category row: name (details), then Budgeted (edit), Spent (transactions) and Balance (move money). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactCategoryBudgetRow(
    category: CategoryBudget,
    showsSpent: Boolean,
    showsBudgeted: Boolean,
    showsProgressBars: Boolean,
    showsStatusDots: Boolean,
    actions: CategoryRowActions,
    modifier: Modifier = Modifier,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val balanceTone = categoryBalanceTone(category, store.goalTemplatesEnabled)
    val monthTitle = MonthTitles.title(category.month)
    val status = if (showsStatusDots) category.progressState.statusText() else null
    val tone = L(
        when (CompactBalanceTone.of(category.available, store.hideBalances)) {
            CompactBalanceTone.Negative -> "budget.balanceStatus.negative"
            CompactBalanceTone.Zero -> "budget.balanceStatus.zero"
            CompactBalanceTone.Positive -> "budget.balanceStatus.positive"
            CompactBalanceTone.Masked -> "budget.balanceStatus.hidden"
        },
    )

    val name = @Composable {
        Row(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = if (status != null) L("Details for %@, %@", category.categoryName, status) else L("Details for %@", category.categoryName)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showsStatusDots) CompactCategoryStatusDot(category.progressState)
            Text(category.categoryName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
    val budgetedCell = Modifier
        .clickable { actions.onEditBudget(category) }
        .semantics { contentDescription = L("Edit budgeted amount for %@, budgeted %@", category.categoryName, store.displayBalance(category.budgeted)) }
    val spentCell = Modifier
        .clickable { actions.onShowTransactions(category, category.month) }
        .semantics { contentDescription = L("Transactions for %@ in %@, %@ %@", category.categoryName, monthTitle, L("spent"), store.displayBalance(category.spent)) }
    val balanceCell = Modifier
        .clickable(enabled = category.available != 0L) { actions.onMoveMoney(category) }
        .semantics {
            contentDescription = L(
                if (category.isOverspent) "Cover overspending for %@, balance %@, %@" else "Move money from %@, balance %@, %@",
                category.categoryName,
                store.displayBalance(category.available),
                tone,
            )
        }

    Box(modifier.fillMaxWidth().testTag("compactBudgetCategory.${category.categoryId}")) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = { menu = true }, onClick = { actions.onShowDetails(category) })
                .heightIn(min = 44.dp)
                .padding(horizontal = RowPadding, vertical = 12.dp)
                .dimmedWhen(category.isEffectivelyHidden),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isStacked()) {
                name()
                if (showsBudgeted) StackedAmount(CompactBudgetColumn.Budgeted.label(), category.budgeted, budgetedCell)
                if (showsSpent) StackedAmount(CompactBudgetColumn.Spent.label(), category.spent, spentCell)
                RolloverIndicator(category.carryoverEnabled, balanceTone.color) {
                    StackedAmount(CompactBudgetColumn.Balance.label(), category.available, balanceCell, balanceTone)
                }
            } else {
                CompactColumns(name) {
                    if (showsBudgeted) Box(Modifier.weight(1f).then(budgetedCell), contentAlignment = Alignment.CenterEnd) { CompactAmountText(category.budgeted) }
                    if (showsSpent) Box(Modifier.weight(1f).then(spentCell), contentAlignment = Alignment.CenterEnd) { CompactAmountText(category.spent) }
                    Box(Modifier.weight(BALANCE_COLUMN_WEIGHT).then(balanceCell), contentAlignment = Alignment.CenterEnd) {
                        RolloverIndicator(category.carryoverEnabled, balanceTone.color) { CompactAmountText(category.available, balanceTone = balanceTone) }
                    }
                }
            }
            if (showsProgressBars && category.showsProgressBar) CategoryProgressBar(category)
        }
        CategoryRowMenu(menu, { menu = false }, category, actions)
    }
}

@Composable
private fun StackedAmount(label: String, amount: Long, modifier: Modifier, balanceTone: StatusTone? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        CompactAmountText(amount, balanceTone = balanceTone)
    }
}

/** The Compact income header: Budgeted (tracking budgets only) and Received under the matching columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactIncomeGroupHeader(
    name: String,
    isCollapsed: Boolean,
    isHidden: Boolean,
    actions: List<GroupHeaderAction>,
    totalBudgeted: Long,
    totalReceived: Long,
    isTrackingBudget: Boolean,
    showsSpent: Boolean,
    showsBudgeted: Boolean,
    onToggleCollapse: () -> Unit,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val layout = CompactBudgetTableLayout.of(isTrackingBudget, showsSpent, showsBudgeted)
    val state = L(if (isCollapsed) "collapsed" else "expanded")
    val label = if (CompactBudgetColumn.Budgeted in layout.incomeColumns) {
        L("%@, %@, budgeted %@, received %@", name, state, store.displayBalance(totalBudgeted), store.displayBalance(totalReceived))
    } else {
        L("%@, %@, received %@", name, state, store.displayBalance(totalReceived))
    }
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = if (actions.isNotEmpty()) ({ menu = true }) else null, onClick = onToggleCollapse)
                .padding(start = HeaderInset, end = HeaderInset, top = 16.dp, bottom = 8.dp)
                .dimmedWhen(isHidden)
                .testTag("compactIncomeSection")
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = label
                },
        ) {
            val title = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisclosureChevron(!isCollapsed)
                    Text(name, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                }
            }
            CompactColumns(title) {
                layout.incomeColumns.forEachIndexed { index, column ->
                    Column(Modifier.weight(columnWeight(index == layout.incomeColumns.lastIndex)), horizontalAlignment = Alignment.End) {
                        if (column != null) {
                            Text(column.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            CompactAmountText(if (column == CompactBudgetColumn.Budgeted) totalBudgeted else totalReceived)
                        }
                    }
                }
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

/** A Compact income row: the name opens all-time transactions, Received the month's. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactIncomeCategoryRow(
    income: IncomeCategory,
    isTrackingBudget: Boolean,
    showsSpent: Boolean,
    showsBudgeted: Boolean,
    onShowTransactions: (IncomeCategory, String?) -> Unit,
    onSetHidden: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val layout = CompactBudgetTableLayout.of(isTrackingBudget, showsSpent, showsBudgeted)
    val receivedCell = Modifier
        .clickable { onShowTransactions(income, income.month) }
        .semantics {
            contentDescription = L("Transactions for %@ in %@, %@ %@", income.categoryName, MonthTitles.title(income.month), CompactBudgetColumn.Received.label(), store.displayBalance(income.received))
        }
    val budgetedLabel = L("Budgeted for %@, %@", income.categoryName, store.displayBalance(income.budgeted))
    Box(modifier.fillMaxWidth().testTag("compactIncomeCategory.${income.categoryId}")) {
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onLongClick = if (onSetHidden != null) ({ menu = true }) else null, onClick = { onShowTransactions(income, null) })
                .heightIn(min = 44.dp)
                .padding(horizontal = RowPadding, vertical = 12.dp)
                .dimmedWhen(income.isEffectivelyHidden),
        ) {
            val name = @Composable {
                Text(income.categoryName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.semantics { contentDescription = L("All transactions for %@", income.categoryName) })
            }
            if (isStacked()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    name()
                    if (CompactBudgetColumn.Budgeted in layout.incomeColumns) {
                        StackedAmount(CompactBudgetColumn.Budgeted.label(), income.budgeted, Modifier.semantics(mergeDescendants = true) { contentDescription = budgetedLabel })
                    }
                    StackedAmount(CompactBudgetColumn.Received.label(), income.received, receivedCell)
                }
            } else {
                CompactColumns(name) {
                    layout.incomeColumns.forEachIndexed { index, column ->
                        val weight = columnWeight(index == layout.incomeColumns.lastIndex)
                        when (column) {
                            CompactBudgetColumn.Budgeted -> Box(Modifier.weight(weight).semantics { contentDescription = budgetedLabel }.testTag("compactIncomeBudgeted.${income.categoryId}"), contentAlignment = Alignment.CenterEnd) {
                                CompactAmountText(income.budgeted)
                            }
                            CompactBudgetColumn.Received -> Box(Modifier.weight(weight).then(receivedCell), contentAlignment = Alignment.CenterEnd) { CompactAmountText(income.received) }
                            else -> Box(Modifier.weight(weight))
                        }
                    }
                }
            }
        }
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
