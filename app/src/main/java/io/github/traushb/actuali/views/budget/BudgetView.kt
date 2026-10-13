package io.github.traushb.actuali.views.budget

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.BudgetDisplayStyle
import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.services.CleanupOutcome
import io.github.traushb.actuali.services.GoalTemplateAction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.budget.CleanupEngine
import io.github.traushb.actuali.services.copyPreviousMonthBudget
import io.github.traushb.actuali.services.fetchBudgetMonth
import io.github.traushb.actuali.services.fetchNote
import io.github.traushb.actuali.services.moveCategory
import io.github.traushb.actuali.services.moveCategoryGroup
import io.github.traushb.actuali.services.renameCategory
import io.github.traushb.actuali.services.runCleanup
import io.github.traushb.actuali.services.runGoalTemplates
import io.github.traushb.actuali.services.setBudgetsToZero
import io.github.traushb.actuali.services.setCategoryGroupHidden
import io.github.traushb.actuali.services.setCategoryHidden
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.LocalTopBarScrollBehavior
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppStorage
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.InitialSyncBanner
import io.github.traushb.actuali.views.components.NoteEditorView
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.components.pageTopBarColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class NewBudgetItem { Category, Group }

/**
 * The Budget tab: the month's categories by group with their Available
 * balances, in the Clean card style or the Compact table, over a summary of
 * the month. Tapping a balance moves money; a name opens the category's
 * editor; the Spent caption opens its transactions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BudgetView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var selectedMonth by rememberSaveable { mutableStateOf(store.lastViewedBudgetMonth ?: BudgetViewLogic.currentMonthString()) }
    var filter by rememberSaveable { mutableStateOf(BudgetCategoryFilter.All) }
    var isReordering by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryBudget?>(null) }
    var selectedCategory by remember { mutableStateOf<CategoryBudget?>(null) }
    var renamingCategory by remember { mutableStateOf<CategoryBudget?>(null) }
    var editingGroup by remember { mutableStateOf<CategoryGroup?>(null) }
    var transferContext by remember { mutableStateOf<BudgetTransferContext?>(null) }
    var newItem by remember { mutableStateOf<NewBudgetItem?>(null) }
    var templateResult by remember { mutableStateOf<BudgetViewLogic.TemplateAlert?>(null) }
    var runningAction by remember { mutableStateOf(false) }
    var showingSummary by remember { mutableStateOf(false) }
    var monthNote by remember { mutableStateOf(EntityNote.unsupported) }
    // The month monthNote was read for; null until a read lands for the open budget file.
    var monthNoteMonth by remember { mutableStateOf<String?>(null) }
    var editingMonthNote by remember { mutableStateOf(false) }
    // Comma-joined ids of the groups the user collapsed, PWA-style.
    var collapsedStorage by AppStorage.string("collapsedBudgetGroups", "")
    val collapsed = collapsedStorage.split(",").filter { it.isNotEmpty() }.toSet()
    val budget = store.currentBudgetMonth
    val isCompact = store.budgetDisplayStyle == BudgetDisplayStyle.Compact
    val groups = budget?.let { BudgetViewLogic.groupedCategories(it, store, filter, isReordering) }.orEmpty()
    val incomes = budget?.let { BudgetViewLogic.displayedIncomeCategories(it, store.showHiddenCategories, store.hideIncomeGroup) }.orEmpty()
    val displayedNote = BudgetViewLogic.monthNote(monthNote, monthNoteMonth, selectedMonth)
    // The group a new category starts out filed under: the first one the table would draw.
    val firstSelectableGroupId = store.categoryGroups.filter { !it.hidden }.minByOrNull { it.sortOrder }?.id

    fun setCollapsed(ids: Set<String>) {
        collapsedStorage = ids.sorted().joinToString(",")
    }

    // Expand/collapse all touch only the displayed budget's groups; ids remembered for other budget files stay put (GH #130).
    fun displayedGroupIDs() = BudgetViewLogic.displayedGroupIDs(groups.map { it.id }, incomes.isNotEmpty())

    // A file switch: the last file's note for the same month string isn't this file's note.
    LaunchedEffect(store.currentBudgetId) {
        monthNoteMonth = null
        selectedMonth = store.lastViewedBudgetMonth ?: BudgetViewLogic.currentMonthString()
    }
    LaunchedEffect(selectedMonth) {
        if (store.lastViewedBudgetMonth != selectedMonth) store.lastViewedBudgetMonth = selectedMonth
        if (store.currentBudgetMonth?.month != selectedMonth) store.fetchBudgetMonth(selectedMonth)
    }
    // Hiding the strip takes its filter with it, or the table stays filtered with no control to clear it.
    LaunchedEffect(store.showBudgetCheckInStrip) { if (!store.showBudgetCheckInStrip) filter = BudgetCategoryFilter.All }
    // Keyed on the data version too, so a note synced in from Actual shows without leaving the month.
    LaunchedEffect(selectedMonth, store.dataVersion, store.currentBudgetId) {
        val month = selectedMonth
        val note = store.fetchNote(EntityNote.monthNoteId(month))
        // A slower read for a month already swiped away mustn't overwrite the one on screen.
        if (month == selectedMonth) {
            monthNote = note
            monthNoteMonth = month
        }
    }

    fun guarded(block: suspend () -> Unit) {
        if (runningAction) return
        runningAction = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                templateResult = BudgetViewLogic.TemplateAlert(L("Error"), e.localizedMessage.orEmpty())
            }
            runningAction = false
        }
    }

    fun runTemplates(action: GoalTemplateAction, category: CategoryBudget? = null, groupCategoryIds: Set<String>? = null) = guarded {
        val outcome = when {
            category != null -> store.runGoalTemplates(category.month, action, categoryId = category.categoryId)
            groupCategoryIds != null -> store.runGoalTemplates(selectedMonth, action, categoryIds = groupCategoryIds)
            else -> store.runGoalTemplates(selectedMonth, action)
        }
        templateResult = BudgetViewLogic.templateAlert(outcome, singleCategory = category != null)
    }

    fun runCleanup() = guarded {
        val message = when (val outcome = store.runCleanup(selectedMonth)) {
            is CleanupOutcome.Failed -> outcome.message
            is CleanupOutcome.Completed -> when (val notification = outcome.notification) {
                is CleanupEngine.Notification.Applied -> L("End of month cleanup completed.")
                CleanupEngine.Notification.UpToDate -> L("End of month cleanup is up to date.")
                is CleanupEngine.Notification.Warnings -> notification.warnings.joinToString("\n\n") { warning ->
                    when (warning) {
                        is CleanupEngine.Warning.NoAvailableFunds -> L("%@ does not have available funds.", warning.category)
                        is CleanupEngine.Warning.NoMatchingSinks -> L("Cleanup pool \"%@\" has no matching sink categories.", warning.group)
                        CleanupEngine.Warning.NoGlobalFunds -> L("No funds are available to reallocate.")
                    }
                }
            }
        }
        templateResult = BudgetViewLogic.TemplateAlert(L("End of Month Cleanup"), message)
    }

    fun storeWrite(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.error = e.localizedMessage
            }
        }
    }

    // A group's template action, or null when templates are off or the group is hidden (month runs skip hidden groups too).
    fun groupTemplateAction(action: GoalTemplateAction, group: CategoryGroupSection): (() -> Unit)? {
        if (!store.goalTemplatesEnabled || group.isHidden || budget == null) return null
        val ids = BudgetViewLogic.groupTemplateCategoryIds(group.id, budget)
        return { runTemplates(action, groupCategoryIds = ids) }
    }

    val actions = CategoryRowActions(
        onShowDetails = { selectedCategory = it },
        onEditBudget = { editingCategory = it },
        onShowTransactions = { category, month -> nav.push(Route.CategoryTransactions(category.categoryId, category.categoryName, month)) },
        onMoveMoney = { category -> store.currentBudgetMonth?.let { transferContext = BudgetTransferContext(category, it) } },
        onApplyTemplate = if (store.goalTemplatesEnabled) ({ runTemplates(GoalTemplateAction.Apply, category = it) }) else null,
        onRename = { renamingCategory = it },
        onSetHidden = { category, hidden -> storeWrite { store.setCategoryHidden(category.categoryId, hidden, selectedMonth) } },
    )

    // The income group can only be unhidden, never hidden: hiding it would drop the app's only income total. Rename stays.
    val incomeGroup = store.categoryGroups.firstOrNull { it.isIncome }
    val incomeName = (incomeGroup?.name ?: incomes.firstOrNull()?.groupName ?: "Income").let { if (it == "Income") L("Income") else it }
    val incomeActions = GroupHeaderAction.groupActions(
        isHidden = incomeGroup?.hidden == true,
        onRename = incomeGroup?.let { group -> { editingGroup = group } },
        onSetHidden = incomeGroup?.takeIf { it.hidden }?.let { group -> { hidden -> storeWrite { store.setCategoryGroupHidden(group.id, hidden, selectedMonth) } } },
    )
    fun groupActions(group: CategoryGroupSection) = GroupHeaderAction.groupActions(
        isHidden = group.isHidden,
        onApplyTemplate = groupTemplateAction(GoalTemplateAction.Apply, group),
        onOverwriteTemplate = groupTemplateAction(GoalTemplateAction.Overwrite, group),
        onRename = { editingGroup = store.categoryGroups.firstOrNull { it.id == group.id } },
        onSetHidden = { hidden -> storeWrite { store.setCategoryGroupHidden(group.id, hidden, selectedMonth) } },
    )
    fun toggle(id: String) = setCollapsed(if (id in collapsed) collapsed - id else collapsed + id)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { MonthStepper(selectedMonth, displayedNote, { selectedMonth = it }, { editingMonthNote = true }) },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Reordering is turned on from the options menu; this is its way out.
                        if (isReordering) {
                            TextButton(onClick = { isReordering = false }, modifier = Modifier.testTag("budget.reorderDone")) { Text(L("Done"), fontWeight = FontWeight.SemiBold) }
                        } else if (budget != null) {
                            BudgetActionsMenu(
                                onCopyPreviousMonth = { guarded { store.copyPreviousMonthBudget(selectedMonth) } },
                                onSetBudgetsToZero = { guarded { store.setBudgetsToZero(selectedMonth) } },
                                onTemplateAction = if (store.goalTemplatesEnabled) ({ runTemplates(it) }) else null,
                                onCleanup = if (!budget.isTrackingBudget && store.goalTemplatesEnabled) ({ runCleanup() }) else null,
                            )
                        }
                    }
                },
                actions = {
                    BudgetOptionsMenu(
                        hasBudget = budget != null,
                        canAddCategory = firstSelectableGroupId != null,
                        isReordering = isReordering,
                        onNewCategory = { newItem = NewBudgetItem.Category },
                        onNewGroup = { newItem = NewBudgetItem.Group },
                        onToggleReorder = {
                            // Reordering shows every group's categories, so a filter that hides some goes first.
                            if (!isReordering) filter = BudgetCategoryFilter.All
                            isReordering = !isReordering
                        },
                        onExpandAll = { setCollapsed(collapsed - displayedGroupIDs()) },
                        onCollapseAll = { setCollapsed(collapsed + displayedGroupIDs()) },
                    )
                },
                colors = pageTopBarColors(),
                scrollBehavior = LocalTopBarScrollBehavior.current,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                budget != null -> Column(Modifier.fillMaxSize()) {
                    InitialSyncBanner(store.isInitialSyncing)
                    // The summary leads and stays pinned above the table while it scrolls (GH #155).
                    when {
                        isCompact && store.showCompactBudgetOverview -> CompactBudgetSummary(
                            budget, store.showCompactSpentColumn, store.showBudgetedAmounts, { showingSummary = true },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        )
                        !isCompact && store.showCleanBudgetOverview -> CleanBudgetSummary(
                            budget, store.showBudgetedAmounts, { showingSummary = true },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        )
                        // With the Clean overview hidden, what's left to budget stays in view as one card.
                        !isCompact && budget.toBudget != null -> ReadyToBudgetRow(budget, { showingSummary = true }, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
                    }
                    if (store.showBudgetCheckInStrip && !isReordering) {
                        BudgetCheckInStrip(budget, filter, { filter = it }, Modifier.padding(vertical = 8.dp))
                    }
                    // The strip filters categories, so it can't reach uncategorized transactions; this is the in-app route.
                    if (store.uncategorizedCount > 0 && !isReordering) {
                        UncategorizedLink(store.uncategorizedCount, { nav.push(Route.Uncategorized) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    BudgetTable(
                        budget, groups, incomes, filter, isReordering, collapsed, actions, incomeName, incomeGroup?.hidden == true, incomeActions,
                        groupActions = ::groupActions,
                        onToggle = ::toggle,
                        onShowAll = { filter = BudgetCategoryFilter.All },
                        onSwipeMonth = { selectedMonth = it },
                        selectedMonth = selectedMonth,
                        onMoveCategory = { id, groupId, before -> storeWrite { store.moveCategory(id, groupId, before, selectedMonth) } },
                        onMoveGroup = { id, before -> storeWrite { store.moveCategoryGroup(id, before, selectedMonth) } },
                        onShowIncome = { income, month -> nav.push(Route.CategoryTransactions(income.categoryId, income.categoryName, month)) },
                        onSetIncomeHidden = { income, hidden -> storeWrite { store.setCategoryHidden(income.categoryId, hidden, selectedMonth) } },
                    )
                }
                !store.isLoading -> if (store.isConnected && store.currentBudgetId == null) {
                    EmptyState(L("Select a Budget"), Icons.Filled.PieChart, L("You're connected. Choose a budget in More → Connection & Data to load it here."))
                } else {
                    EmptyState(L("No Budget Loaded"), Icons.Filled.PieChart, L("Go to More → Connection & Data to connect to your Actual Budget server"))
                }
            }
            if (store.isLoading) DelayedLoadingIndicator(Modifier.align(Alignment.Center), contained = true)
        }
    }

    editingCategory?.let { EditBudgetAmountSheet(it) { editingCategory = null } }
    selectedCategory?.let { CategoryBudgetDetailSheet(it) { selectedCategory = null } }
    transferContext?.let { BudgetTransferSheet(it) { transferContext = null } }
    editingGroup?.let { CategoryGroupSheet(it, selectedMonth) { editingGroup = null } }
    when (newItem) {
        NewBudgetItem.Category -> NewCategorySheet(firstSelectableGroupId.orEmpty()) { newItem = null }
        NewBudgetItem.Group -> CategoryGroupSheet(null, selectedMonth) { newItem = null }
        null -> Unit
    }
    if (showingSummary) BudgetSummarySheet(selectedMonth) { showingSummary = false }
    renamingCategory?.let { category ->
        RenameCategoryDialog(category.categoryName, onDismiss = { renamingCategory = null }) { name ->
            if (name != category.categoryName) storeWrite { store.renameCategory(category.categoryId, name, category.month) }
        }
    }
    TemplateResultAlert(templateResult) { templateResult = null }
    if (editingMonthNote) {
        FullScreenSheet({ editingMonthNote = false }) {
            NoteEditorView(EntityNote.monthNoteId(selectedMonth), MonthTitles.title(selectedMonth), displayedNote.text) {
                editingMonthNote = false
                monthNoteMonth = null
                scope.launch {
                    val month = selectedMonth
                    monthNote = store.fetchNote(EntityNote.monthNoteId(month))
                    monthNoteMonth = month
                }
            }
        }
    }
}

/** The scrolling table under the summary: reorder mode, or the Clean or Compact table, all with month swipes and pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BudgetTable(
    budget: BudgetMonth,
    groups: List<CategoryGroupSection>,
    incomes: List<IncomeCategory>,
    filter: BudgetCategoryFilter,
    isReordering: Boolean,
    collapsed: Set<String>,
    actions: CategoryRowActions,
    incomeName: String,
    incomeHidden: Boolean,
    incomeActions: List<GroupHeaderAction>,
    groupActions: (CategoryGroupSection) -> List<GroupHeaderAction>,
    onToggle: (String) -> Unit,
    onShowAll: () -> Unit,
    onSwipeMonth: (String) -> Unit,
    selectedMonth: String,
    onMoveCategory: (String, String, String?) -> Unit,
    onMoveGroup: (String, String?) -> Unit,
    onShowIncome: (IncomeCategory, String?) -> Unit,
    onSetIncomeHidden: (IncomeCategory, Boolean) -> Unit,
) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    val isCompact = store.budgetDisplayStyle == BudgetDisplayStyle.Compact
    val incomeCollapsed = BudgetViewLogic.INCOME_GROUP_COLLAPSE_ID in collapsed

    RefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                store.sync()
                refreshing = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            // A sideways swipe steps the month, like iOS's drag; vertical drags stay the list's.
            .pointerInput(selectedMonth) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { BudgetViewLogic.monthSwipeTarget(selectedMonth, dx, 0f, threshold)?.let(onSwipeMonth) },
                ) { _, amount -> dx += amount }
            },
    ) {
        if (isReordering) {
            CategoryReorderTable(groups, onMoveCategory, onMoveGroup)
            return@RefreshBox
        }
        // Row keys carry the month, so a month switch replaces every row; content
        // types let the list recycle the old rows' nodes for the new ones.
        LazyColumn(Modifier.fillMaxSize()) {
            if (filter != BudgetCategoryFilter.All && groups.isEmpty()) {
                item(key = "noMatch") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyState(L("No Matching Categories"), Icons.Filled.FilterList, L("Try another category filter."), Modifier.fillParentMaxHeight(0.4f))
                        Button(onClick = onShowAll) { Text(L("Show All Categories")) }
                    }
                }
            }
            for (group in groups) {
                val groupCollapsed = group.id in collapsed
                stickyHeader(key = "header-${group.id}", contentType = "groupHeader") {
                    if (isCompact) {
                        CompactBudgetGroupHeader(
                            group.name, groupCollapsed, group.isHidden, groupActions(group),
                            totals = if (store.showGroupTotals) group.totals else null,
                            showsSpent = store.showCompactSpentColumn,
                            showsBudgeted = store.showBudgetedAmounts,
                        ) { onToggle(group.id) }
                    } else {
                        BudgetGroupHeader(group.name, groupCollapsed, group.isHidden, groupActions(group), { onToggle(group.id) }, balanceTotal = group.totals.balance)
                    }
                }
                if (groupCollapsed) continue
                if (isCompact) {
                    itemsIndexed(group.categories, key = { _, it -> it.id }, contentType = { _, _ -> "compactCategory" }) { index, category ->
                        CompactCategoryBudgetRow(
                            category,
                            showsSpent = store.showCompactSpentColumn,
                            showsBudgeted = store.showBudgetedAmounts,
                            showsProgressBars = store.showBudgetProgressBars && category.categoryId !in store.hiddenBudgetProgressCategoryIDs,
                            showsStatusDots = store.showCategoryStatusDots,
                            actions = actions,
                            modifier = Modifier.animateItem().listTile(index, group.categories.size),
                        )
                    }
                } else {
                    itemsIndexed(group.categories, key = { _, it -> it.id }, contentType = { _, _ -> "category" }) { index, category ->
                        CleanCategoryBudgetRow(category, actions, Modifier.animateItem().listTile(index, group.categories.size))
                    }
                }
            }
            // Income last, matching the bottom of the web UI's budget table.
            if (filter == BudgetCategoryFilter.All && incomes.isNotEmpty()) {
                stickyHeader(key = "header-income", contentType = "groupHeader") {
                    if (isCompact) {
                        CompactIncomeGroupHeader(
                            incomeName, incomeCollapsed, incomeHidden, incomeActions,
                            totalBudgeted = budget.totalBudgetedIncome,
                            totalReceived = budget.totalIncome,
                            isTrackingBudget = budget.isTrackingBudget,
                            showsSpent = store.showCompactSpentColumn,
                            showsBudgeted = store.showBudgetedAmounts,
                        ) { onToggle(BudgetViewLogic.INCOME_GROUP_COLLAPSE_ID) }
                    } else {
                        BudgetGroupHeader(incomeName, incomeCollapsed, incomeHidden, incomeActions, { onToggle(BudgetViewLogic.INCOME_GROUP_COLLAPSE_ID) }, receivedTotal = budget.totalIncome)
                    }
                }
                if (!incomeCollapsed) {
                    itemsIndexed(incomes, key = { _, it -> it.id }, contentType = { _, _ -> "income" }) { index, income ->
                        val setHidden: (Boolean) -> Unit = { hidden -> onSetIncomeHidden(income, hidden) }
                        if (isCompact) {
                            CompactIncomeCategoryRow(income, budget.isTrackingBudget, store.showCompactSpentColumn, store.showBudgetedAmounts, onShowIncome, setHidden, Modifier.listTile(index, incomes.size))
                        } else {
                            IncomeCategoryRow(income, budget.toBudget == null && store.showBudgetedAmounts, onShowIncome, setHidden, Modifier.listTile(index, incomes.size))
                        }
                    }
                }
            }
            item(key = "bottomSpace") { Box(Modifier.padding(bottom = 16.dp)) }
        }
    }
}

/** The month between previous/next steps; the month itself opens the month list and the month's note (GH #567). */
@Composable
private fun MonthStepper(month: String, note: EntityNote, onSelect: (String) -> Unit, onEditNote: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("budget.monthStepper")) {
        IconButton(onClick = { onSelect(BudgetViewLogic.shiftMonth(month, -1)) }, modifier = Modifier.testTag("budget.previousMonth")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = L("Previous month"))
        }
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = MonthTitles.title(month) }) {
                // The abbreviation is a layout constraint, not what the month is called; TalkBack reads it in full.
                Text(MonthTitles.shortTitle(month), style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (note.supported) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(L(if (note.isEmpty) "Add Note" else "Edit Note"))
                                if (!note.isEmpty) Text(note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        },
                        onClick = {
                            menu = false
                            onEditNote()
                        },
                        modifier = Modifier.testTag("budget.monthNote"),
                    )
                    HorizontalDivider()
                }
                for (option in BudgetViewLogic.monthOptions(month)) {
                    DropdownMenuItem(
                        text = { Text(MonthTitles.title(option)) },
                        trailingIcon = if (option == month) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                        onClick = {
                            menu = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
        IconButton(onClick = { onSelect(BudgetViewLogic.shiftMonth(month, 1)) }, modifier = Modifier.testTag("budget.nextMonth")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = L("Next month"))
        }
    }
}

/** The sparkles button: the actions that change the month's budget, apart from the view options. */
@Composable
private fun BudgetActionsMenu(
    onCopyPreviousMonth: () -> Unit,
    onSetBudgetsToZero: () -> Unit,
    onTemplateAction: ((GoalTemplateAction) -> Unit)?,
    onCleanup: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("budget.actionsMenu")) { Icon(Icons.Filled.AutoAwesome, contentDescription = L("Budget actions")) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            @Composable
            fun Item(label: String, tag: String? = null, action: () -> Unit) {
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    menu = false
                    action()
                }, modifier = tag?.let { Modifier.testTag(it) } ?: Modifier)
            }
            Item(L("Copy last month's budget"), "budget.copyPreviousMonthBudget", onCopyPreviousMonth)
            Item(L("Set budgets to zero"), action = onSetBudgetsToZero)
            // The web month menu's three template actions, in its order.
            if (onTemplateAction != null) {
                HorizontalDivider()
                Item(L("Check Templates")) { onTemplateAction(GoalTemplateAction.Check) }
                Item(L("Apply Budget Template")) { onTemplateAction(GoalTemplateAction.Apply) }
                Item(L("Overwrite with Budget Template")) { onTemplateAction(GoalTemplateAction.Overwrite) }
                onCleanup?.let { Item(L("End of Month Cleanup"), action = it) }
            }
        }
    }
}

/**
 * The single view-options control (GH #157): creating categories and
 * groups, reordering, the layout, what's shown, and expand/collapse all.
 */
@Composable
private fun BudgetOptionsMenu(
    hasBudget: Boolean,
    canAddCategory: Boolean,
    isReordering: Boolean,
    onNewCategory: () -> Unit,
    onNewGroup: () -> Unit,
    onToggleReorder: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    val clean = store.budgetDisplayStyle == BudgetDisplayStyle.Clean
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("budget.optionsMenu")) { Icon(Icons.Filled.MoreVert, contentDescription = L("Budget options")) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            @Composable
            fun Item(label: String, tag: String? = null, enabled: Boolean = true, action: () -> Unit) {
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
                    menu = false
                    action()
                }, modifier = tag?.let { Modifier.testTag(it) } ?: Modifier)
            }

            @Composable
            fun Toggle(label: String, checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { onChange(!checked) },
                    modifier = tag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
            if (hasBudget) {
                Item(L("New Category"), enabled = canAddCategory, action = onNewCategory)
                Item(L("New Group"), action = onNewGroup)
                Item(L(if (isReordering) "Done Reordering" else "Reorder Items"), "budgetOptions.reorder", action = onToggleReorder)
                HorizontalDivider()
            }
            Toggle(L("Clean"), clean) { store.budgetDisplayStyle = BudgetDisplayStyle.Clean }
            Toggle(L("Compact"), !clean) { store.budgetDisplayStyle = BudgetDisplayStyle.Compact }
            HorizontalDivider()
            Toggle(L("Show Overview"), if (clean) store.showCleanBudgetOverview else store.showCompactBudgetOverview, "budgetOptions.showOverview") {
                if (clean) store.showCleanBudgetOverview = it else store.showCompactBudgetOverview = it
            }
            Toggle(L("Show Budgeted"), store.showBudgetedAmounts, "budgetOptions.showBudgetedAmounts") { store.showBudgetedAmounts = it }
            if (!clean) Toggle(L("Show Spent"), store.showCompactSpentColumn) { store.showCompactSpentColumn = it }
            if (hasBudget) {
                HorizontalDivider()
                Item(L("Expand Groups"), action = onExpandAll)
                Item(L("Collapse Groups"), action = onCollapseAll)
            }
            // Amount masking isn't here: it's app-wide, so it lives in Settings (GH #158).
            HorizontalDivider()
            if (!clean) Toggle(L("Group Totals"), store.showGroupTotals) { store.showGroupTotals = it }
            Toggle(L("Status Filters"), store.showBudgetCheckInStrip) { store.showBudgetCheckInStrip = it }
            Toggle(L("Hide Spent"), store.hideZeroBudgetCategories) { store.hideZeroBudgetCategories = it }
            Toggle(L("Hidden Categories"), store.showHiddenCategories) { store.showHiddenCategories = it }
        }
    }
}

/**
 * Reorder mode: every group and category with steps up and down — iOS's
 * VoiceOver path through the same planner. ponytail: no drag; the upgrade is
 * a drag handle driving [CategoryReorderPlanner.destination] from the lazy
 * list's item offsets, as iOS's controller does from row frames.
 */
@Composable
private fun CategoryReorderTable(
    groups: List<CategoryGroupSection>,
    onMoveCategory: (String, String, String?) -> Unit,
    onMoveGroup: (String, String?) -> Unit,
) {
    val arrangement = groups.map { CategoryReorderPlanner.Entry(it.id, it.categories.map(CategoryBudget::categoryId)) }
    val order = groups.map { it.id }

    fun stepCategory(id: String, direction: Int) {
        val slot = CategoryReorderPlanner.step(direction, id, arrangement) ?: return
        val (groupId, before) = CategoryReorderPlanner.target(id, CategoryReorderPlanner.moved(arrangement, id, slot)) ?: return
        onMoveCategory(id, groupId, before)
    }

    fun stepGroup(id: String, direction: Int) {
        val index = CategoryReorderPlanner.groupStep(direction, id, order) ?: return
        onMoveGroup(id, CategoryReorderPlanner.groupTarget(id, CategoryReorderPlanner.movedGroup(order, id, index)))
    }

    @Composable
    fun Steps(name: String, canUp: Boolean, canDown: Boolean, onStep: (Int) -> Unit) {
        IconButton(onClick = { onStep(-1) }, enabled = canUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "${L("Move Up")}, $name") }
        IconButton(onClick = { onStep(1) }, enabled = canDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = "${L("Move Down")}, $name") }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEachIndexed { groupIndex, group ->
            item(key = "header-${group.id}") {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Steps(group.name, groupIndex > 0, groupIndex < groups.lastIndex) { stepGroup(group.id, it) }
                }
            }
            itemsIndexed(group.categories, key = { _, it -> it.id }) { index, category ->
                Row(Modifier.listTile(index, group.categories.size).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(category.categoryName, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 2)
                    val canUp = CategoryReorderPlanner.step(-1, category.categoryId, arrangement) != null
                    val canDown = CategoryReorderPlanner.step(1, category.categoryId, arrangement) != null
                    Steps(category.categoryName, canUp, canDown) { stepCategory(category.categoryId, it) }
                }
            }
        }
    }
}
