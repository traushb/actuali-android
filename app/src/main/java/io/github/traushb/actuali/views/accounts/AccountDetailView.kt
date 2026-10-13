package io.github.traushb.actuali.views.accounts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountBalanceBreakdown
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.DateStyle
import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.TransactionPager
import io.github.traushb.actuali.models.TransactionStatusFilter
import io.github.traushb.actuali.models.withRunningBalances
import io.github.traushb.actuali.services.BudgetStoreError
import io.github.traushb.actuali.services.CategoryFundingAutomation
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.balanceBreakdown
import io.github.traushb.actuali.services.bankSyncAccount
import io.github.traushb.actuali.services.budget.BudgetMonthMath
import io.github.traushb.actuali.services.fetchCycleSpend
import io.github.traushb.actuali.services.fetchNote
import io.github.traushb.actuali.services.fetchRecentStatements
import io.github.traushb.actuali.services.fetchTransactions
import io.github.traushb.actuali.services.loadSchedules
import io.github.traushb.actuali.services.loanTargetMonthly
import io.github.traushb.actuali.services.offBudgetAccountIds
import io.github.traushb.actuali.services.pairedLoanCategory
import io.github.traushb.actuali.services.runBankSync
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.snoozeLoanTarget
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.upcomingRegisterEntries
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.budget.automations.BudgetAutomationsSheet
import io.github.traushb.actuali.views.components.AnimatedAmountText
import io.github.traushb.actuali.views.components.AppStorage
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.NoteCard
import io.github.traushb.actuali.views.components.NoteEditorView
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.schedules.SHOW_UPCOMING_KEY
import io.github.traushb.actuali.views.schedules.UpcomingScheduleDialogs
import io.github.traushb.actuali.views.schedules.rememberUpcomingScheduleState
import io.github.traushb.actuali.views.schedules.upcomingScheduleSections
import io.github.traushb.actuali.views.settings.DepositEditor
import io.github.traushb.actuali.views.settings.DepositEditorMode
import io.github.traushb.actuali.views.settings.DepositEditorView
import io.github.traushb.actuali.views.settings.DepositGrowthChart
import io.github.traushb.actuali.views.settings.LoanEditorMode
import io.github.traushb.actuali.views.settings.LoanEditorView
import io.github.traushb.actuali.views.settings.LoanSummaryText
import io.github.traushb.actuali.views.transactions.AddTransactionView
import io.github.traushb.actuali.views.transactions.EditTransactionView
import io.github.traushb.actuali.views.transactions.ScheduleLoadErrorRow
import io.github.traushb.actuali.views.transactions.TransactionBulkActionBar
import io.github.traushb.actuali.views.transactions.TransactionFilterStrip
import io.github.traushb.actuali.views.transactions.TransactionListMenuItems
import io.github.traushb.actuali.views.transactions.TransactionSelection
import io.github.traushb.actuali.views.transactions.TransactionsListView
import io.github.traushb.actuali.views.transactions.transactionRows
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One owner for the transaction-screen chrome shared by All Accounts and the
 * individual accounts: the status strip above either list.
 */
@Composable
fun AccountTransactionsScreen(accountId: String?) {
    val store = LocalBudgetStore.current
    val account = accountId?.let { id -> store.accounts.firstOrNull { it.id == id } }
    // An off-budget account has no on-budget rows for the uncategorized chip.
    val filters = if (account?.offBudget == true) TransactionStatusFilter.entries - TransactionStatusFilter.Uncategorized else TransactionStatusFilter.entries
    LaunchedEffect(account?.id) {
        if (account?.offBudget == true && store.transactionStatusFilter == TransactionStatusFilter.Uncategorized) store.transactionStatusFilter = TransactionStatusFilter.All
    }
    val strip: @Composable () -> Unit = {
        if (store.showTransactionStatusFilters) TransactionFilterStrip(store.transactionStatusFilter, { store.transactionStatusFilter = it }, filters)
    }
    when {
        accountId == null -> TransactionsListView(strip)
        account != null -> AccountDetailView(account, strip)
        // Closed or removed out from under the screen.
        else -> Scaffold(topBar = { AppTopBar(L("Account Unavailable")) }) { padding ->
            EmptyState(L("Account Unavailable"), Icons.Filled.Search, L("Pick another account from the list."), Modifier.padding(padding))
        }
    }
}

object AccountDetailLogic {
    /** Running balances only make sense over the unfiltered register. */
    fun allowsRunningBalance(isSearching: Boolean, statusFilter: TransactionStatusFilter): Boolean = !isSearching && statusFilter == TransactionStatusFilter.All

    fun showsEmptyTransactions(
        isSearching: Boolean,
        statusFilter: TransactionStatusFilter,
        schedulesLoaded: Boolean,
        hasUpcomingSchedules: Boolean,
        scheduleLoadFailed: Boolean,
        showUpcomingSchedules: Boolean = true,
    ): Boolean = isSearching || statusFilter != TransactionStatusFilter.All || !showUpcomingSchedules || (schedulesLoaded && !hasUpcomingSchedules && !scheduleLoadFailed)

    fun emptyTransactionsKey(isSearching: Boolean, statusFilter: TransactionStatusFilter): String =
        if (isSearching || statusFilter != TransactionStatusFilter.All) "No matching transactions" else "No transactions"

    /** Search suppresses the note even when the preference allows it: account search is about transactions. */
    fun showsNote(supported: Boolean, hidden: Boolean, isSearching: Boolean): Boolean = supported && !hidden && !isSearching

    fun showsCreditHeadroom(showingBreakdown: Boolean, hasHeadroom: Boolean): Boolean = showingBreakdown && hasHeadroom
}

/** Which sheet the account screen has up — one at a time, as on iOS. */
private sealed interface AccountSheet {
    data object AddTransaction : AccountSheet
    data class Edit(val transaction: Transaction) : AccountSheet
    data object Reconcile : AccountSheet
    data object Note : AccountSheet
    data class Statement(val record: CreditCardCycle.StatementRecord) : AccountSheet
    data object LoanPlanner : AccountSheet
    data object LoanPayment : AccountSheet
    data object LoanPairing : AccountSheet
    data object LoanAutomations : AccountSheet
    data object LoanEditor : AccountSheet
    data object DepositEditor : AccountSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailView(account: Account, filterStrip: @Composable () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val pager = remember(account.id) {
        TransactionPager { offset, limit, search -> store.fetchTransactions(accountId = account.id, limit = limit, offset = offset, search = search, statusFilter = store.transactionStatusFilter) }
    }
    val selection = remember(account.id) { TransactionSelection() }
    val upcomingState = rememberUpcomingScheduleState()
    var breakdown by remember(account.id) { mutableStateOf<AccountBalanceBreakdown?>(null) }
    var showingBreakdown by rememberSaveable { mutableStateOf(false) }
    var showingBillingCycle by rememberSaveable { mutableStateOf(false) }
    var showingLoanDetails by rememberSaveable { mutableStateOf(false) }
    var showingDepositDetails by rememberSaveable { mutableStateOf(false) }
    var loanTarget by remember { mutableStateOf<Long?>(null) }
    var loanTotalPaid by remember { mutableStateOf<Long?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var previousSearch by remember { mutableStateOf("") }
    // Starts unsupported, so the note stays hidden until the read confirms this file can store notes.
    var note by remember(account.id) { mutableStateOf(EntityNote.unsupported) }
    var hideNotes by AppStorage.bool("accountsHideNotes", false)
    val showUpcoming by AppStorage.bool(SHOW_UPCOMING_KEY, true)
    var showRunningBalance by AppStorage.bool("showAccountRunningBalance", true)
    var cycleSpend by remember(account.id) { mutableLongStateOf(0L) }
    var loadedFullHistory by remember { mutableStateOf(false) }
    var recentStatements by remember(account.id) { mutableStateOf<List<CreditCardCycle.StatementRecord>>(emptyList()) }
    var sheet by remember { mutableStateOf<AccountSheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val searchQuery = searchText.trim().ifEmpty { null }
    val statusFilter = store.transactionStatusFilter
    val cycle = store.activeCreditCardCycle(account.id)
    val statementDue = store.creditCardStatementDues[account.id]?.let { CreditCardCycle.pendingStatementDue(it) }
    val currentBalance = store.accounts.firstOrNull { it.id == account.id }?.balance ?: account.balance

    suspend fun reload() = coroutineScope {
        // Captured before the fetch, which reads the same flags, so a filter flipped mid-fetch can't label the page unfiltered.
        val fullHistory = AccountDetailLogic.allowsRunningBalance(searchQuery != null, store.transactionStatusFilter)
        val newBreakdown = async { store.balanceBreakdown(account.id) }
        val newNote = async { store.fetchNote(EntityNote.accountNoteId(account.id)) }
        val newSpend = async { cycle?.cycleRange()?.let { (start, end) -> store.fetchCycleSpend(account.id, start, end) } ?: 0L }
        val newStatements = async { store.fetchRecentStatements(account.id) }
        pager.loadFirstPage(searchQuery)
        breakdown = newBreakdown.await()
        note = newNote.await()
        cycleSpend = newSpend.await()
        recentStatements = newStatements.await()
        // The previous column stays visible until the replacement page lands.
        loadedFullHistory = fullHistory
    }
    suspend fun refreshLoanTarget() {
        loanTarget = store.loanTargetMonthly(account.id, BudgetMonthMath.currentMonth())
    }

    LaunchedEffect(Unit) { store.loadSchedules() }
    // The one reload path: dataVersion covers every mutation, the cycle replaces a statement-day observer.
    LaunchedEffect(account.id, store.dataVersion, searchText, statusFilter, cycle) {
        val searchChanged = previousSearch != searchText
        previousSearch = searchText
        // Debounce keystrokes; the initial load runs immediately.
        if (searchChanged && searchQuery != null) delay(250)
        reload()
    }
    // Keyed on the data version so recording a payment refreshes the total — even one that leaves the balance where it was.
    if (store.activeLoanConfig(account.id) != null) {
        LaunchedEffect(store.dataVersion) {
            loanTotalPaid = store.totalPaidIntoLoan(account.id)
            refreshLoanTarget()
        }
    }

    val transactions = if (showRunningBalance && loadedFullHistory) pager.transactions.withRunningBalances(currentBalance) else pager.transactions
    val upcomingEntries = store.upcomingRegisterEntries(account.id)

    Scaffold(
        topBar = {
            AppTopBar(account.name, actions = {
                if (selection.isSelecting) {
                    TextButton(onClick = { selection.setSelectionMode(false) }) { Text(L("Done")) }
                } else {
                    IconButton(onClick = { sheet = AccountSheet.AddTransaction }) { Icon(Icons.Filled.Add, contentDescription = L("Add Transaction")) }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = L("More")) }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!selection.isSelecting) {
                            DropdownMenuItem(text = { Text(L("Select Transactions")) }, onClick = {
                                menuOpen = false
                                selection.setSelectionMode(true)
                            })
                        }
                        if (store.bankSyncAccount(account.id) != null) {
                            DropdownMenuItem(text = { Text(L("Sync from Bank")) }, enabled = !store.isBankSyncing, onClick = {
                                menuOpen = false
                                scope.launch { store.runBankSync(listOf(account.id)) }
                            })
                        }
                        TransactionListMenuItems()
                        DropdownMenuItem(
                            text = { Text(L("Show Running Balance")) },
                            trailingIcon = { Checkbox(showRunningBalance, onCheckedChange = null) },
                            onClick = { showRunningBalance = !showRunningBalance },
                        )
                        if (note.supported) {
                            DropdownMenuItem(
                                text = { Text(L("Hide Notes")) },
                                trailingIcon = { Checkbox(hideNotes, onCheckedChange = null) },
                                onClick = { hideNotes = !hideNotes },
                                modifier = Modifier.testTag("accountDetails.notesVisibility"),
                            )
                        }
                        DropdownMenuItem(text = { Text(L("Reconcile")) }, onClick = {
                            menuOpen = false
                            sheet = AccountSheet.Reconcile
                        })
                    }
                }
            })
        },
        bottomBar = {
            if (selection.isSelecting) TransactionBulkActionBar(pager.transactions, selection.selectedIds, { selection.selectedIds = it }, onFinish = { selection.setSelectionMode(false) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(searchText, { searchText = it }, L("Search transactions"))
            filterStrip()
            RefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        store.sync()
                        // Not every sync bumps dataVersion, so re-read explicitly: a pull must always show what's on disk.
                        reload()
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "balance") {
                        BalanceSection(currentBalance, breakdown, showingBreakdown, { showingBreakdown = !showingBreakdown }, store.availableCredit(account.id)?.let { available -> store.creditCardLimits[account.id]?.let { it to available } })
                    }
                    if (cycle != null && searchQuery == null) {
                        item(key = "cycle") {
                            BillingCycleSection(cycle, statementDue, cycleSpend, recentStatements, showingBillingCycle, { showingBillingCycle = !showingBillingCycle }) { sheet = AccountSheet.Statement(it) }
                        }
                    }
                    store.activeLoanConfig(account.id)?.takeIf { searchQuery == null }?.let { config ->
                        item(key = "loan") {
                            LoanSection(account, config, currentBalance, showingLoanDetails, { showingLoanDetails = !showingLoanDetails }, loanTarget, loanTotalPaid) { sheet = it }
                        }
                    }
                    store.activeDepositConfig(account.id)?.takeIf { searchQuery == null }?.let { config ->
                        item(key = "deposit") { DepositSection(config, showingDepositDetails, { showingDepositDetails = !showingDepositDetails }) { sheet = AccountSheet.DepositEditor } }
                    }
                    if (AccountDetailLogic.showsNote(note.supported, hideNotes, searchQuery != null)) {
                        item(key = "note") { NoteCard(note, "accountNoteRow", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { sheet = AccountSheet.Note } }
                    }
                    transactionSection(
                        transactions,
                        pager,
                        selection,
                        showUpcoming && searchQuery == null && statusFilter == TransactionStatusFilter.All,
                        upcomingEntries,
                        upcomingState,
                        showRunningBalance,
                        store.scheduleLoadError != null,
                        store.schedulesLoaded,
                        store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate,
                        AccountDetailLogic.showsEmptyTransactions(searchQuery != null, statusFilter, store.schedulesLoaded, upcomingEntries.isNotEmpty(), store.scheduleLoadError != null, showUpcoming),
                        L(AccountDetailLogic.emptyTransactionsKey(searchQuery != null, statusFilter)),
                    ) { sheet = AccountSheet.Edit(it) }
                }
            }
        }
    }

    UpcomingScheduleDialogs(upcomingState)
    val dismiss = { sheet = null }
    when (val current = sheet) {
        null -> Unit
        AccountSheet.AddTransaction -> FullScreenSheet(dismiss) {
            AddTransactionView(account.id, onDismiss = dismiss, onSaved = { CategoryFundingAutomation.processIfNeeded(it, store) })
        }
        is AccountSheet.Edit -> FullScreenSheet(dismiss) { EditTransactionView(current.transaction, dismiss) }
        AccountSheet.Reconcile -> FullScreenSheet(dismiss) { ReconcileView(account, dismiss) }
        AccountSheet.Note -> NoteEditorView(EntityNote.accountNoteId(account.id), account.name, note.text) {
            sheet = null
            // Only the note needs re-reading — a note save doesn't touch transactions or the balance.
            scope.launch { note = store.fetchNote(EntityNote.accountNoteId(account.id)) }
        }
        is AccountSheet.Statement -> FullScreenSheet(dismiss) { CreditCardStatementDetailView(account, current.record, dismiss) }
        AccountSheet.LoanPlanner -> store.activeLoanConfig(account.id)?.let { config -> FullScreenSheet(dismiss) { LoanPayoffPlannerView(account, config, dismiss) } }
        AccountSheet.LoanPayment -> store.activeLoanConfig(account.id)?.let { config -> FullScreenSheet(dismiss) { LoanPaymentView(account, config, currentBalance, dismiss) } }
        AccountSheet.LoanPairing -> FullScreenSheet(dismiss) {
            LoanCategoryPairingView(account.id) {
                sheet = null
                scope.launch { refreshLoanTarget() }
            }
        }
        AccountSheet.LoanAutomations -> store.pairedLoanCategory(account.id)?.let { category ->
            FullScreenSheet(dismiss) {
                BudgetAutomationsSheet(category.id, BudgetMonthMath.currentMonth()) {
                    sheet = null
                    scope.launch { refreshLoanTarget() }
                }
            }
        }
        // Read at presentation, so the editor opens on the current terms even if a sync landed in between.
        AccountSheet.LoanEditor -> store.activeLoanConfig(account.id)?.let { LoanEditorView(LoanEditorMode.Edit(account.id, it), dismiss) }
        AccountSheet.DepositEditor -> store.activeDepositConfig(account.id)?.let { DepositEditorView(DepositEditorMode.Edit(account.id, it), dismiss) }
    }
}

private fun LazyListScope.transactionSection(
    transactions: List<Transaction>,
    pager: TransactionPager,
    selection: TransactionSelection,
    showsRegister: Boolean,
    upcomingEntries: List<io.github.traushb.actuali.services.schedules.UpcomingScheduleEntry>,
    upcomingState: io.github.traushb.actuali.views.schedules.UpcomingScheduleState,
    showRunningBalance: Boolean,
    scheduleLoadFailed: Boolean,
    schedulesLoaded: Boolean,
    grouped: Boolean,
    showsEmpty: Boolean,
    emptyText: String,
    onEdit: (Transaction) -> Unit,
) {
    if (showsRegister) {
        if (scheduleLoadFailed) item(key = "scheduleError") { ScheduleLoadErrorRow() }
        if (schedulesLoaded && upcomingEntries.isNotEmpty()) upcomingScheduleSections(upcomingEntries, upcomingState, showRunningBalance)
    }
    if (!grouped || transactions.isEmpty()) {
        // The header stays put while the first page loads, so the screen doesn't reflow once rows land.
        item(key = "recentHeader") {
            ListSectionHeader(L("Recent Transactions"))
        }
    }
    if (transactions.isEmpty()) {
        item(key = "empty") {
            when {
                showsEmpty -> Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                !schedulesLoaded -> DelayedLoadingIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    } else {
        transactionRows(transactions, pager, grouped, selection, onEdit, showAccount = false)
    }
}

/** A section on its card: the page's `surface` tiles, or [color] for the screen's hero. */
@Composable
private fun SectionCard(color: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ExpandChevron(expanded: Boolean, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp).rotate(rotation), tint = tint)
}

@Composable
private fun BreakdownRow(title: String, value: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        AnimatedAmountText(value, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BalanceSection(balance: Long, breakdown: AccountBalanceBreakdown?, expanded: Boolean, onToggle: () -> Unit, headroom: Pair<Long, Long>?) {
    val store = LocalBudgetStore.current
    fun amount(cents: Long?) = cents?.let(store::displayBalance) ?: "—"
    val headline = MaterialTheme.typography.displaySmallEmphasized
    // The screen's hero, as on the Accounts tab: the figure on the primary container, its sign carried by the minus.
    SectionCard(MaterialTheme.colorScheme.primaryContainer) {
        val content = MaterialTheme.colorScheme.onPrimaryContainer
        // The balance leads, large; a tap opens the breakdown beneath it.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = breakdown != null, onClick = onToggle)
                .testTag("accountBalance.toggle")
                .semantics { contentDescription = L("Current Balance, %@", store.displayBalance(balance)) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Balance"), style = MaterialTheme.typography.titleSmall)
                if (breakdown != null) ExpandChevron(expanded, content)
            }
            AnimatedAmountText(store.displayBalance(balance), style = headline, autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = headline.fontSize))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(Modifier.weight(1f).testTag("accountBalance.cleared")) {
                Text(L("Cleared"), style = MaterialTheme.typography.labelMedium)
                AnimatedAmountText(amount(breakdown?.cleared), style = MaterialTheme.typography.titleMediumEmphasized)
            }
            Column(Modifier.weight(1f).testTag("accountBalance.uncleared"), horizontalAlignment = Alignment.End) {
                Text(L("Uncleared"), style = MaterialTheme.typography.labelMedium)
                AnimatedAmountText(amount(breakdown?.uncleared), style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
        if (expanded && breakdown != null) BreakdownRow(L("Reconciled"), store.displayBalance(breakdown.reconciled), content)
        if (headroom != null && AccountDetailLogic.showsCreditHeadroom(expanded, true)) {
            BreakdownRow(L("Available Credit"), store.displayBalance(headroom.second), content)
            BreakdownRow(L("Credit Limit"), store.displayBalance(headroom.first), content)
        }
    }
}

@Composable
private fun BillingCycleSection(
    cycle: CreditCardCycle,
    statementDue: CreditCardCycle.StatementDue?,
    cycleSpend: Long,
    statements: List<CreditCardCycle.StatementRecord>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenStatement: (CreditCardCycle.StatementRecord) -> Unit,
) {
    val store = LocalBudgetStore.current
    fun date(day: DayDate) = Transaction.formattedDate(day.yyyymmdd, DateStyle.Abbreviated)
    val dueSummary = cycle.dueSummary(dueDate = statementDue?.dueDate)
    SectionCard {
        // Collapsed by default, but the due date rides on the header — it's the part worth acting on.
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).semantics { contentDescription = L("Billing Cycle, %@", dueSummary) }, verticalAlignment = Alignment.CenterVertically) {
            Text(L("Billing Cycle"), modifier = Modifier.weight(1f))
            Text(dueSummary, fontWeight = FontWeight.SemiBold)
            ExpandChevron(expanded)
        }
        if (expanded) {
            val (start, end) = cycle.cycleRange()
            BreakdownRow(L("Current Cycle"), "${date(start)} – ${date(end)}")
            statementDue?.let { BreakdownRow(L("Statement Due"), if (it.isPaid) L("Paid") else store.displayBalance(it.remainingDue)) }
            BreakdownRow(L("Cycle Spend"), store.displayBalance(cycleSpend))
            if (statements.isNotEmpty()) HorizontalDivider()
            for (statement in statements) {
                val period = "${date(statement.startDate)} – ${date(statement.endDate)}"
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStatement(statement) }.padding(vertical = 4.dp)
                        .semantics { contentDescription = L("Statement %1\$@ to %2\$@, %3\$@", date(statement.startDate), date(statement.endDate), store.displayBalance(statement.statementBalance)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(period)
                        if (statement.isPaid) {
                            Text(L("Paid"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.positive)
                        } else {
                            Text(L("Due %@", date(statement.dueDate)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(store.displayBalance(statement.statementBalance), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Payoff tracking for an account marked as a loan, collapsed by default with
 * the payoff date on the header row — the part worth acting on.
 */
@Composable
private fun LoanSection(
    account: Account,
    config: LoanConfig,
    balance: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    loanTarget: Long?,
    totalPaid: Long?,
    onSheet: (AccountSheet) -> Unit,
) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val summary = config.payoffSummary(balance)
    val fraction = config.fractionPaidOff(balance)
    val detached = store.syncDetachedByRestore
    // Read live: the account can be moved on budget after the loan was set up, and recordLoanPayment refuses it there.
    val loanIsOnBudget = account.id !in store.offBudgetAccountIds
    val paired = store.pairedLoanCategory(account.id)
    SectionCard {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("accountLoan.toggle")
                .semantics { contentDescription = L("Loan, %1\$@, %2\$@", LoanSummaryText.progressText(fraction), summary) },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Loan"), modifier = Modifier.weight(1f))
                Text(summary, fontWeight = FontWeight.SemiBold)
                ExpandChevron(expanded)
            }
            LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            Row {
                Text(LoanSummaryText.progressText(fraction), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(L("of %@", store.displayBalance(config.originalBalance)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!expanded) return@SectionCard
        BreakdownRow(L("Original Balance"), store.displayBalance(config.originalBalance))
        BreakdownRow(L("Interest Rate"), LoanSummaryText.percentText(config.annualRatePercent / 100))
        BreakdownRow(L("Monthly Payment"), store.displayBalance(config.minimumPayment))
        config.escrowOrFees?.let { BreakdownRow(L("Escrow or Fees"), store.displayBalance(it)) }
        config.schedule(balance)?.let { schedule ->
            BreakdownRow(L("Payments Remaining"), schedule.paymentCount.toString())
            BreakdownRow(L("Interest Remaining"), store.displayBalance(schedule.totalInterest))
        }
        // The progress bar counts principal alone; this is the same loan measured the other way, every cent paid into it.
        totalPaid?.takeIf { it > 0 }?.let { BreakdownRow(L("Total Paid"), store.displayBalance(it)) }
        BreakdownRow(L("Category"), paired?.name ?: L("Not paired"))
        val month = BudgetMonthMath.currentMonth()
        if (paired != null) {
            BreakdownRow(
                L("Payment Target"),
                when {
                    config.targetIsSnoozed(month) -> L("Skipped this month")
                    loanTarget != null -> store.displayBalance(loanTarget)
                    else -> L("No target set")
                },
            )
        }
        HorizontalDivider()
        ActionRow(L("Record Payment"), enabled = !detached && !loanIsOnBudget, tag = "accountLoan.recordPayment") { onSheet(AccountSheet.LoanPayment) }
        if (loanIsOnBudget) {
            Text(BudgetStoreError.LoanAccountOnBudget.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("accountLoan.onBudgetNote"))
        }
        ActionRow(if (paired == null) L("Pair With a Category") else L("Change Paired Category"), enabled = !detached, tag = "accountLoan.pairCategory") { onSheet(AccountSheet.LoanPairing) }
        if (paired != null) {
            // The automations editor is where Actual creates targets, and converting a notes-managed
            // category takes its warning and an explicit Save — so this opens it rather than writing behind it.
            ActionRow(if (loanTarget == null) L("Set Payment Target") else L("Edit Payment Target"), enabled = !detached, tag = "accountLoan.target") { onSheet(AccountSheet.LoanAutomations) }
            if (loanTarget != null) {
                val isSnoozed = config.targetIsSnoozed(month)
                ActionRow(if (isSnoozed) L("Stop Skipping This Month") else L("Skip This Month"), enabled = !detached, tag = "accountLoan.snooze") {
                    scope.launch { store.snoozeLoanTarget(account.id, if (isSnoozed) null else month) }
                }
            }
        }
        // The terms are most often questioned while looking at the loan, so they open from here.
        ActionRow(L("Payoff Simulator"), tag = "accountLoan.planner") { onSheet(AccountSheet.LoanPlanner) }
        ActionRow(L("Edit Loan"), enabled = !detached, tag = "accountLoan.edit") { onSheet(AccountSheet.LoanEditor) }
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp).testTag(tag),
    )
}

/** Growth tracking for a deposit: the bar runs through the term, the header carries today's value. */
@Composable
private fun DepositSection(config: DepositConfig, expanded: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    val store = LocalBudgetStore.current
    val today = DayDate.today()
    val summary = DepositEditor.maturitySummary(config, today)
    val value = store.displayBalance(config.value(today))
    SectionCard {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("accountDeposit.toggle").semantics { contentDescription = L("Deposit, %1\$@, %2\$@", value, summary) },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Deposit"), modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.SemiBold)
                ExpandChevron(expanded)
            }
            LinearProgressIndicator(progress = { config.fractionElapsed(today).toFloat() }, modifier = Modifier.fillMaxWidth())
            Row {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(L("%@ interest", store.displayBalance(config.interestEarned(today))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!expanded) return@SectionCard
        BreakdownRow(L("Type"), if (config.kind == DepositConfig.Kind.Recurring) L("Recurring Deposit") else L("Fixed Deposit"))
        BreakdownRow(if (config.kind == DepositConfig.Kind.Recurring) L("Monthly Instalment") else L("Deposit Amount"), store.displayBalance(config.amount))
        BreakdownRow(L("Interest Rate"), LoanSummaryText.percentText(config.annualRatePercent / 100))
        BreakdownRow(L("Compounding"), DepositEditor.compoundingLabel(config.compounding))
        BreakdownRow(L("Opened"), config.openedOn.displayText())
        BreakdownRow(L("Matures"), config.maturityDate.displayText())
        BreakdownRow(L("Deposited"), store.displayBalance(config.deposited()))
        BreakdownRow(L("Interest Earned"), store.displayBalance(config.interestEarned()))
        BreakdownRow(L("Value at Maturity"), store.displayBalance(config.maturityValue))
        DepositGrowthChart(config)
        ActionRow(L("Edit Deposit"), enabled = !store.syncDetachedByRestore, tag = "accountDeposit.edit", onClick = onEdit)
    }
}

