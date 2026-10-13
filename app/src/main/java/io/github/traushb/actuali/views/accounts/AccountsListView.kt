package io.github.traushb.actuali.views.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.PendingImportStore
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.fetchAccountsMonthSummary
import io.github.traushb.actuali.services.notifications.NotificationRouter
import io.github.traushb.actuali.services.runBankSync
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AnimatedAmountText
import io.github.traushb.actuali.views.components.AppStorage
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DisclosureChevron
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.LoadingOverlay
import io.github.traushb.actuali.views.components.MonogramAvatar
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.balanceColor
import io.github.traushb.actuali.views.components.containerShape
import io.github.traushb.actuali.views.components.rememberOpenBudgetName
import kotlinx.coroutines.launch

/**
 * A month's totals together with the month they were fetched for, so the
 * card can't caption August's figures with September when the calendar rolls
 * over under it.
 */
data class AccountsMonthTotals(val month: String, val totals: BudgetDatabase.AccountsMonthSummary)

object AccountsListLogic {
    fun filterAccounts(accounts: List<Account>, query: String): List<Account> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return accounts
        return accounts.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
}

/** The Accounts tab: the all-accounts summary card, then On Budget, Off Budget and Closed sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsListView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var monthSummary by remember { mutableStateOf<AccountsMonthTotals?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val pendingCount = PendingImportStore.shared.count

    // Each section's expansion persists, so a collapsed section stays collapsed across launches.
    val onBudgetExpanded = AppStorage.bool("accountsOnBudgetExpanded", true)
    val offBudgetExpanded = AppStorage.bool("accountsOffBudgetExpanded", true)
    val closedExpanded = AppStorage.bool("accountsClosedExpanded", true)

    suspend fun loadMonthSummary() {
        val month = store.currentMonthString()
        store.fetchAccountsMonthSummary(month)?.let { monthSummary = AccountsMonthTotals(month, it) }
    }
    // Follows every edit and sync like the balances beneath it, and the Spent exclusions, which change totals without an edit.
    LaunchedEffect(store.dataVersion, store.excludedFromSpentCategoryIds) { loadMonthSummary() }
    // A phone left on this tab overnight would otherwise keep last month's totals.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { loadMonthSummary() } }

    // A notification tap or a tab-hosted save lands on an account's transactions.
    LaunchedEffect(NotificationRouter.pendingAccountNavigation) {
        val accountId = NotificationRouter.pendingAccountNavigation ?: return@LaunchedEffect
        NotificationRouter.pendingAccountNavigation = null
        if (store.accounts.any { it.id == accountId }) nav.reset(Route.AccountTransactions(accountId))
    }

    val onBudget = store.accounts.filter { !it.offBudget && !it.closed }
    val offBudget = store.accounts.filter { it.offBudget && !it.closed }
    val closed = store.visibleClosedAccounts
    val isSearching = searchText.isNotBlank()
    val filteredOn = AccountsListLogic.filterAccounts(onBudget, searchText)
    val filteredOff = AccountsListLogic.filterAccounts(offBudget, searchText)
    val filteredClosed = AccountsListLogic.filterAccounts(closed, searchText)
    val hasNoAccounts = store.accounts.isEmpty() && !store.isLoading

    Scaffold(
        topBar = {
            AppTopBar(
                L("Accounts"),
                subtitle = rememberOpenBudgetName(),
                actions = {
                    if (pendingCount > 0) {
                        IconButton(onClick = { nav.push(Route.PendingImports) }) {
                            BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                Icon(Icons.Filled.MoveToInbox, contentDescription = L("Pending imports") + ", " + L("%lld pending", pendingCount.toLong()))
                            }
                        }
                    }
                    if (!hasNoAccounts || searching) {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) searchText = ""
                        }, modifier = Modifier.testTag("accounts.search")) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) L("Cancel Search") else L("Search Accounts"))
                        }
                    }
                    IconButton(onClick = { nav.push(Route.AddAccount) }) { Icon(Icons.Filled.Add, contentDescription = L("accounts.add.title")) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = L("Accounts options")) }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            fun go(route: Route) {
                                menuOpen = false
                                nav.push(route)
                            }
                            if (store.bankSyncAccounts.isNotEmpty()) {
                                DropdownMenuItem(text = { Text(L("Sync Bank Accounts")) }, enabled = !store.isBankSyncing, onClick = {
                                    menuOpen = false
                                    scope.launch { store.runBankSync() }
                                })
                            }
                            DropdownMenuItem(text = { Text(L("Credit Cards")) }, onClick = { go(Route.CreditCardsSettings) })
                            DropdownMenuItem(text = { Text(L("Loans")) }, onClick = { go(Route.LoansSettings) })
                            DropdownMenuItem(text = { Text(L("Deposits")) }, onClick = { go(Route.DepositsSettings) })
                            DropdownMenuItem(text = { Text(L("Bills & Calendar")) }, onClick = { go(Route.BillsCalendar) })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(L("Hide Closed")) },
                                trailingIcon = { Checkbox(store.hideClosedAccounts, onCheckedChange = null) },
                                onClick = { store.hideClosedAccounts = !store.hideClosedAccounts },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        RefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    store.sync()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column {
                if (searching) {
                    SearchField(searchText, { searchText = it }, L("Search Accounts"), Modifier.testTag("accounts.searchField"))
                }
                when {
                    hasNoAccounts -> AccountsEmptyState(store)
                    isSearching && filteredOn.isEmpty() && filteredOff.isEmpty() && filteredClosed.isEmpty() ->
                        EmptyState(L("No Results"), Icons.Filled.Search, L("Check the spelling or try a new search."))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (!isSearching) {
                            item(key = "summary") {
                                AccountsSummaryCard(
                                    store.accounts.sumOf { it.balance },
                                    monthSummary,
                                    onClick = { nav.push(Route.AccountTransactions(null)) },
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("accounts.topBox"),
                                )
                            }
                        }
                        accountSection("on-budget", L("On Budget"), filteredOn, onBudget.sumOf { it.balance }, onBudgetExpanded, isSearching) { nav.push(Route.AccountTransactions(it.id)) }
                        accountSection("off-budget", L("Off Budget"), filteredOff, offBudget.sumOf { it.balance }, offBudgetExpanded, isSearching) { nav.push(Route.AccountTransactions(it.id)) }
                        accountSection("closed", L("Closed Accounts"), filteredClosed, closed.sumOf { it.balance }, closedExpanded, isSearching) { nav.push(Route.AccountTransactions(it.id)) }
                    }
                }
            }
            LoadingOverlay(store.isLoading)
        }
    }

    // Kicked off from a menu that's gone by the time it finishes, so the outcome shows here.
    store.bankSyncSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { store.bankSyncSummary = null },
            title = { Text(L("Bank Sync")) },
            text = { Text(summary) },
            confirmButton = { TextButton(onClick = { store.bankSyncSummary = null }) { Text(L("common.ok")) } },
        )
    }
}

@Composable
private fun AccountsEmptyState(store: BudgetStore) {
    when {
        // A budget is loaded, it just has no accounts (yet) — "go connect a server" would be wrong advice (GH #122).
        store.currentBudgetId != null -> EmptyState(L("No Accounts"), Icons.Outlined.AttachMoney, L("This budget doesn't have any accounts yet. Create one in Actual Budget, then sync."))
        store.isConnected -> EmptyState(L("Select a Budget"), Icons.Outlined.AttachMoney, L("You're connected. Choose a budget in More → Connection & Data to load it here."))
        else -> EmptyState(L("No Budget Loaded"), Icons.Outlined.AttachMoney, L("Go to More → Connection & Data to connect to your Actual Budget server"))
    }
}

/** Section totals always cover the whole section, search or not, so a header's number never silently changes meaning while filtering. */
private fun LazyListScope.accountSection(
    identifier: String,
    title: String,
    accounts: List<Account>,
    total: Long,
    expanded: MutableState<Boolean>,
    isSearching: Boolean,
    onOpen: (Account) -> Unit,
) {
    if (accounts.isEmpty()) return
    item(key = "header-$identifier") { AccountSectionHeader(identifier, title, total, isExpanded = expanded.value || isSearching, allowsCollapse = !isSearching) { expanded.value = !expanded.value } }
    if (expanded.value || isSearching) {
        itemsIndexed(accounts, key = { _, account -> account.id }) { index, account ->
            AccountRow(account, index, accounts.size, Modifier.animateItem()) { onOpen(account) }
        }
    }
}

/**
 * The list's lead card: the all-accounts balance, large, over this month's
 * income, spending and difference — the figures the budget tab opens with
 * (GH #256). Tapping it opens the All Accounts transactions behind them.
 */
@Composable
fun AccountsSummaryCard(totalBalance: Long, monthTotals: AccountsMonthTotals?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val summary = monthTotals?.totals
    // "—" until the month's totals land, rather than flashing zeroes that a moment later turn into money.
    fun amount(cents: Long?) = cents?.let(store::displayBalance) ?: "—"
    val headline = MaterialTheme.typography.displaySmallEmphasized
    // Expressive's hero moment: the key figure, emphasized, on the primary container — the whole card the tap target.
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("All Accounts"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // The card's emblem on one of Expressive's shapes: the container's own pair inverted, which holds its contrast in both themes.
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.onPrimaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedAmountText(store.displayBalance(totalBalance), style = headline, autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = headline.fontSize))
            // Today's month only while the figures are still dashes — nothing yet for the caption to disagree with.
            Text(MonthTitles.title(monthTotals?.month ?: store.currentMonthString()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStat(Icons.Filled.SouthWest, L("Income"), amount(summary?.incomeCents), Modifier.weight(1f))
                SummaryStat(Icons.Filled.NorthEast, L("Spent"), amount(summary?.expenseCents), Modifier.weight(1f))
                SummaryStat(Icons.Filled.Balance, L("Net"), amount(summary?.netCents), Modifier.weight(1f))
            }
        }
    }
}

/** One labeled figure of the hero's month, its direction as a glyph; a long figure shrinks to fit. */
@Composable
private fun SummaryStat(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMediumEmphasized
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        AnimatedAmountText(value, style = style, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize))
    }
}

/**
 * Header for the On Budget, Off Budget and Closed sections: the name on the
 * left, the total right-aligned, and a tap anywhere expands or collapses it.
 * The expanded state is in the spoken label — a collapsed section is
 * otherwise indistinguishable from an empty one.
 */
@Composable
fun AccountSectionHeader(identifier: String, title: String, total: Long, isExpanded: Boolean, allowsCollapse: Boolean = true, onToggle: () -> Unit) {
    val store = LocalBudgetStore.current
    val totalText = store.displayBalance(total)
    val stateText = if (isExpanded) L("expanded") else L("collapsed")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = allowsCollapse, onClick = onToggle)
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp)
            .testTag("account.group.$identifier")
            .clearAndSetSemantics {
                heading()
                contentDescription = L("accounts.group.accessibility", title, stateText, totalText)
                stateDescription = stateText
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisclosureChevron(isExpanded)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        AnimatedAmountText(totalText, color = balanceColor(total), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One account as an Expressive segmented list item — [index] of [count] in
 * its section, its corners morphing round on press — behind a monogram on
 * one of Expressive's shapes.
 */
@Composable
fun AccountRow(account: Account, index: Int, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val store = LocalBudgetStore.current
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { MonogramAvatar(account.name, account.id, shape = containerShape(account.id.hashCode())) },
        trailingContent = {
            AnimatedAmountText(store.displayBalance(account.balance), color = balanceColor(account.balance), style = MaterialTheme.typography.titleMediumEmphasized)
        },
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = if (index < count - 1) ListItemDefaults.SegmentedGap else 0.dp),
    ) {
        Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}
