package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.TransactionPager
import io.github.traushb.actuali.models.TransactionStatusFilter
import io.github.traushb.actuali.models.groupedByDate
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.fetchTransactions
import io.github.traushb.actuali.services.loadSchedules
import io.github.traushb.actuali.services.schedules.UpcomingScheduleEntry
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.upcomingRegisterEntries
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppStorage
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.LoadingOverlay
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.schedules.SHOW_UPCOMING_KEY
import io.github.traushb.actuali.views.schedules.UpcomingScheduleDialogs
import io.github.traushb.actuali.views.schedules.UpcomingSchedulesVisibilityMenuItem
import io.github.traushb.actuali.views.schedules.rememberUpcomingScheduleState
import io.github.traushb.actuali.views.schedules.upcomingScheduleSections
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object TransactionsListLogic {
    fun showsEmptyState(
        transactionsEmpty: Boolean,
        isLoading: Boolean,
        isSearching: Boolean,
        statusFilter: TransactionStatusFilter,
        schedulesLoaded: Boolean,
        hasUpcomingSchedules: Boolean,
        showUpcomingSchedules: Boolean = true,
    ): Boolean = transactionsEmpty && !isLoading &&
        (isSearching || statusFilter != TransactionStatusFilter.All || !showUpcomingSchedules || (schedulesLoaded && !hasUpcomingSchedules))

    fun showsScheduleLoadFailure(transactionsEmpty: Boolean, isLoading: Boolean, schedulesLoaded: Boolean, scheduleLoadFailed: Boolean, showUpcomingSchedules: Boolean = true): Boolean =
        showUpcomingSchedules && transactionsEmpty && !isLoading && schedulesLoaded && scheduleLoadFailed
}

/**
 * Status preset chips above the transaction lists (GH #439): one tap narrows
 * the list. [filters] lets a screen drop chips that can never match there —
 * an off-budget account has no on-budget rows for the uncategorized chip.
 */
@Composable
fun TransactionFilterStrip(selection: TransactionStatusFilter, onSelect: (TransactionStatusFilter) -> Unit, filters: List<TransactionStatusFilter> = TransactionStatusFilter.entries) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (filter in filters) {
            FilterChip(
                selected = selection == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label()) },
                leadingIcon = { Icon(filter.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.testTag("transactionFilter-${filter.rawValue}"),
            )
        }
    }
}

/** Each status's glyph, matching the rows' own status badges. */
private val TransactionStatusFilter.icon: ImageVector
    get() = when (this) {
        TransactionStatusFilter.All -> Icons.AutoMirrored.Outlined.List
        TransactionStatusFilter.Uncategorized -> Icons.Outlined.Category
        TransactionStatusFilter.Uncleared -> Icons.Outlined.RadioButtonUnchecked
        TransactionStatusFilter.Cleared -> Icons.Outlined.CheckCircle
        TransactionStatusFilter.Reconciled -> Icons.Outlined.Lock
        TransactionStatusFilter.Unreconciled -> Icons.Outlined.LockOpen
    }

/** The overflow menu items every transaction list shares: status strip, date grouping, and the upcoming register. */
@Composable
fun TransactionListMenuItems() {
    val store = LocalBudgetStore.current
    DropdownMenuItem(
        text = { Text(L("Status Filters")) },
        leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null) },
        trailingIcon = { Checkbox(store.showTransactionStatusFilters, onCheckedChange = null) },
        onClick = { store.showTransactionStatusFilters = !store.showTransactionStatusFilters },
    )
    val grouped = store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate
    DropdownMenuItem(
        text = { Text(L("Group by Date")) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
        trailingIcon = { Checkbox(grouped, onCheckedChange = null) },
        onClick = { store.transactionDisplayMode = if (grouped) TransactionDisplayMode.Flat else TransactionDisplayMode.GroupedByDate },
    )
    UpcomingSchedulesVisibilityMenuItem()
}

/** Selection-mode state shared by the transaction lists; leaving the mode clears the selection the same way from every path. */
class TransactionSelection {
    var isSelecting by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())

    fun setSelectionMode(value: Boolean) {
        isSelecting = value
        if (!value) selectedIds = emptySet()
    }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
}

/** The transaction rows of a list, flat or grouped by date, with the paging sentinel riding at the end. */
fun LazyListScope.transactionRows(
    transactions: List<Transaction>,
    pager: TransactionPager,
    grouped: Boolean,
    selection: TransactionSelection,
    onEdit: (Transaction) -> Unit,
    showAccount: Boolean = true,
) {
    // Each day is one segmented group of tiles; a flat list is a single group.
    if (grouped) {
        for (group in transactions.groupedByDate()) {
            item(key = "date-${group.id}", contentType = "dateHeader") { ListSectionHeader(group.title) }
            itemsIndexed(group.transactions, key = { _, transaction -> transaction.id }, contentType = { _, _ -> "transaction" }) { index, transaction ->
                SelectableTransactionRow(transaction, selection, onEdit, showAccount, showDate = false, Modifier.listTile(index, group.transactions.size))
            }
        }
    } else {
        itemsIndexed(transactions, key = { _, transaction -> transaction.id }, contentType = { _, _ -> "transaction" }) { index, transaction ->
            SelectableTransactionRow(transaction, selection, onEdit, showAccount, showDate = true, Modifier.listTile(index, transactions.size))
        }
    }
    if (pager.hasMore) item(key = "sentinel") { TransactionPagingSentinel(pager) }
}

@Composable
private fun SelectableTransactionRow(transaction: Transaction, selection: TransactionSelection, onEdit: (Transaction) -> Unit, showAccount: Boolean, showDate: Boolean, modifier: Modifier) {
    TransactionListRow(
        transaction,
        isSelectionMode = selection.isSelecting,
        onSelectionModeChange = selection::setSelectionMode,
        onEdit = onEdit,
        modifier = modifier,
        showAccount = showAccount,
        showDate = showDate,
        isSelected = transaction.id in selection.selectedIds,
        onToggleSelect = { selection.toggle(transaction.id) },
    )
}

/** "Unable to load scheduled transactions", above a list whose register couldn't load. */
@Composable
fun ScheduleLoadErrorRow() {
    ListItem(
        headlineContent = { Text(L("Unable to load scheduled transactions"), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(Icons.Filled.WarningAmber, contentDescription = null) },
    )
}

/** Every account's transactions, newest first, paged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsListView(filterStrip: @Composable () -> Unit = {}) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val showUpcoming by AppStorage.bool(SHOW_UPCOMING_KEY, true)
    var searchText by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val selection = remember { TransactionSelection() }
    val upcomingState = rememberUpcomingScheduleState()
    val pager = remember {
        TransactionPager { offset, limit, search -> store.fetchTransactions(limit = limit, offset = offset, search = search, statusFilter = store.transactionStatusFilter) }
    }
    val searchQuery = searchText.trim().ifEmpty { null }
    val statusFilter = store.transactionStatusFilter
    val upcomingEntries: List<UpcomingScheduleEntry> = store.upcomingRegisterEntries()

    LaunchedEffect(Unit) { store.loadSchedules() }
    // The single reload path: every mutation bumps dataVersion, a chip tap
    // changes the filter the fetch reads, and keystrokes are debounced.
    LaunchedEffect(searchText, store.dataVersion, statusFilter) {
        if (searchQuery != null) delay(250)
        pager.loadFirstPage(searchQuery)
    }

    Scaffold(
        topBar = {
            AppTopBar(L("All Accounts"), actions = {
                TextButton(onClick = { selection.setSelectionMode(!selection.isSelecting) }, modifier = Modifier.testTag("transactions.selectionMode")) {
                    Text(if (selection.isSelecting) L("Done") else L("Select"))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = L("More")) }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) { TransactionListMenuItems() }
                }
            })
        },
        bottomBar = {
            if (selection.isSelecting) {
                TransactionBulkActionBar(pager.transactions, selection.selectedIds, { selection.selectedIds = it }, onFinish = { selection.setSelectionMode(false) })
            }
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
                        pager.loadFirstPage(searchQuery)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val empty = pager.transactions.isEmpty()
                val unfilteredRegister = showUpcoming && searchQuery == null && statusFilter == TransactionStatusFilter.All
                when {
                    searchQuery == null && statusFilter == TransactionStatusFilter.All &&
                        TransactionsListLogic.showsScheduleLoadFailure(empty, store.isLoading, store.schedulesLoaded, store.scheduleLoadError != null, showUpcoming) ->
                        EmptyState(L("Unable to load scheduled transactions"), Icons.Filled.WarningAmber)
                    unfilteredRegister && empty && !store.schedulesLoaded -> DelayedLoadingIndicator(Modifier.fillMaxSize())
                    TransactionsListLogic.showsEmptyState(empty, store.isLoading, searchQuery != null, statusFilter, store.schedulesLoaded, upcomingEntries.isNotEmpty(), showUpcoming) -> when {
                        searchQuery != null -> EmptyState(L("No Results"), Icons.Filled.Search, L("Check the spelling or try a new search."))
                        statusFilter != TransactionStatusFilter.All -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            EmptyState(L("No Matching Transactions"), Icons.Filled.FilterList, L("Try another status filter."), Modifier.weight(1f, fill = false))
                            Button(onClick = { store.transactionStatusFilter = TransactionStatusFilter.All }) { Text(L("Show All Transactions")) }
                        }
                        else -> EmptyState(L("No Transactions"), Icons.AutoMirrored.Filled.List, L("Transactions will appear here once you load a budget"))
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (unfilteredRegister) {
                            if (store.scheduleLoadError != null) item(key = "scheduleError") { ScheduleLoadErrorRow() }
                            if (store.schedulesLoaded && upcomingEntries.isNotEmpty()) upcomingScheduleSections(upcomingEntries, upcomingState)
                        }
                        transactionRows(pager.transactions, pager, store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate, selection, onEdit = { editing = it })
                    }
                }
                LoadingOverlay(store.isLoading)
            }
        }
    }

    UpcomingScheduleDialogs(upcomingState)
    editing?.let { transaction -> FullScreenSheet(onDismiss = { editing = null }) { EditTransactionView(transaction) { editing = null } } }
}
