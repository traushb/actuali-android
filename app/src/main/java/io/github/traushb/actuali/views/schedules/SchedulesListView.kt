package io.github.traushb.actuali.views.schedules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.deleteSchedule
import io.github.traushb.actuali.services.loadSchedules
import io.github.traushb.actuali.services.postScheduleTransaction
import io.github.traushb.actuali.services.schedules.ScheduleDescription
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import io.github.traushb.actuali.services.schedules.UpcomingScheduleEntry
import io.github.traushb.actuali.services.setScheduleCompleted
import io.github.traushb.actuali.services.skipScheduleNextDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppStorage
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.DisclosureChevron
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.balanceColor
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

object SchedulesListLocalization {
    fun completedFooter(count: Int, locale: Locale = Locale.getDefault()): String =
        ReportStrings.localized("%lld completed schedules hidden.", count.toLong(), locale = locale)
}

/** Row actions all write to the server; failures surface rather than letting a tap look like it worked. */
internal fun CoroutineScope.runReporting(onError: (String) -> Unit, operation: suspend () -> Unit) = launch {
    try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.localizedMessage ?: e.toString())
    }
}

/**
 * The scheduled-transactions screen (GH #221). Completed schedules hide
 * behind a toggle, matching the web — a finished schedule is history, not
 * something to scroll past every time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SchedulesListView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScheduleSummary?>(null) }
    var actionsFor by remember { mutableStateOf<ScheduleSummary?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.loadSchedules() }

    fun accountName(schedule: ScheduleSummary) = schedule.accountId?.let { id -> store.accounts.firstOrNull { it.id == id }?.name }
    fun payeeName(schedule: ScheduleSummary) = schedule.payeeId?.let { id -> store.payees.firstOrNull { it.id == id }?.name }

    // Search covers everything visible on the row, so an account or payee name
    // finds the schedule even when it was never given a name.
    val visible = store.schedules.filter { showCompleted || !it.completed }.filter { schedule ->
        searchText.isEmpty() || listOfNotNull(schedule.name, payeeName(schedule), accountName(schedule), ScheduleDescription.dateSummary(schedule.dateCondition))
            .joinToString(" ").contains(searchText, ignoreCase = true)
    }
    val completedCount = store.schedules.count { it.completed }

    Scaffold(
        topBar = {
            AppTopBar(
                L("Scheduled Transactions"),
                actions = {
                    IconButton(onClick = { searching = !searching }) { Icon(Icons.Filled.Search, contentDescription = L("Search schedules")) }
                    IconButton(onClick = { nav.push(Route.BillsCalendar) }) { Icon(Icons.Filled.CalendarMonth, contentDescription = L("Calendar")) }
                    IconButton(onClick = { nav.push(Route.ScheduleEditor(null)) }, enabled = !store.accounts.all { it.closed }) {
                        Icon(Icons.Filled.Add, contentDescription = L("Add Schedule"))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = L("Options")) }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(L("Show Completed")) },
                                trailingIcon = { Checkbox(showCompleted, onCheckedChange = null) },
                                onClick = { showCompleted = !showCompleted },
                            )
                            DropdownMenuItem(text = { Text(L("Find Schedules")) }, onClick = {
                                menuOpen = false
                                nav.push(Route.DiscoverSchedules)
                            })
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
                    store.loadSchedules()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column {
                if (searching) {
                    OutlinedTextField(
                        searchText,
                        { searchText = it },
                        placeholder = { Text(L("Search schedules")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                when {
                    visible.isEmpty() && searchText.isNotEmpty() -> EmptyState(L("No Results"), Icons.Filled.Search, L("Check the spelling or try a new search."))
                    visible.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        EmptyState(L("No Scheduled Transactions"), Icons.Filled.EventRepeat, L("Create a schedule to track a recurring bill or paycheck."), Modifier.weight(1f, fill = false))
                        Button(onClick = { nav.push(Route.ScheduleEditor(null)) }) { Text(L("New Schedule")) }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                        itemsIndexed(visible, key = { _, it -> it.id }) { index, schedule ->
                            ScheduleRow(
                                schedule,
                                store.scheduleStatuses[schedule.id] ?: ScheduleStatus.Scheduled,
                                accountName(schedule),
                                payeeName(schedule),
                                modifier = Modifier
                                    .listTile(index, visible.size)
                                    .combinedClickable(onClick = { nav.push(Route.ScheduleEditor(schedule)) }, onLongClick = { actionsFor = schedule })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        if (completedCount > 0 && !showCompleted) {
                            item {
                                Text(
                                    SchedulesListLocalization.completedFooter(completedCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The long-press menu: iOS's context menu and swipe actions in one place.
    actionsFor?.let { schedule ->
        ScheduleActionsDialog(
            schedule,
            onDismiss = { actionsFor = null },
            includeCompletion = true,
            onAction = { action ->
                actionsFor = null
                when (action) {
                    ScheduleAction.Delete -> pendingDelete = schedule
                    ScheduleAction.Edit -> nav.push(Route.ScheduleEditor(schedule))
                    else -> scope.runReporting({ actionError = it }) { action.perform(store, schedule) }
                }
            },
        )
    }
    pendingDelete?.let { schedule ->
        ConfirmationDialog(
            title = L("Delete this schedule?"),
            message = L("Transactions this schedule already created are kept."),
            confirmLabel = L("Delete Schedule"),
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = { scope.runReporting({ actionError = it }) { store.deleteSchedule(schedule) } },
        )
    }
    MessageAlert(L("Action Failed"), actionError) { actionError = null }
}

/** What a long press (or a tap in the upcoming register) offers for one schedule. */
enum class ScheduleAction(val key: String) {
    Post("Post Transaction"),
    PostToday("Post Transaction Today"),
    Skip("Skip Next Date"),
    Edit("Edit"),
    Restart("Restart"),
    MarkCompleted("Mark Completed"),
    Delete("Delete"),
    ;

    suspend fun perform(store: io.github.traushb.actuali.services.BudgetStore, schedule: ScheduleSummary) {
        when (this) {
            Post -> store.postScheduleTransaction(schedule, today = false)
            PostToday -> store.postScheduleTransaction(schedule, today = true)
            // Only a recurrence has a next occurrence to skip to.
            Skip -> store.skipScheduleNextDate(schedule)
            Restart -> store.setScheduleCompleted(schedule, completed = false)
            MarkCompleted -> store.setScheduleCompleted(schedule, completed = true)
            Edit, Delete -> Unit
        }
    }
}

@Composable
fun ScheduleActionsDialog(schedule: ScheduleSummary, onDismiss: () -> Unit, includeCompletion: Boolean, onAction: (ScheduleAction) -> Unit) {
    val actions = buildList {
        if (!schedule.completed) {
            add(ScheduleAction.Post)
            add(ScheduleAction.PostToday)
            if (schedule.isRecurring) add(ScheduleAction.Skip)
        }
        add(ScheduleAction.Edit)
        if (includeCompletion) {
            add(if (schedule.completed) ScheduleAction.Restart else ScheduleAction.MarkCompleted)
            add(ScheduleAction.Delete)
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Scheduled Transactions")) },
        text = {
            Column {
                for (action in actions) {
                    Text(
                        L(action.key),
                        color = if (action == ScheduleAction.Delete) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(action) }
                            .padding(vertical = 12.dp)
                            .testTag("scheduleRegister.${action.name.replaceFirstChar { it.lowercase() }}.${schedule.id}"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
    )
}

/** Status pill for a schedule row, mirroring the web's `StatusBadge`. */
@Composable
fun ScheduleStatusBadge(status: ScheduleStatus) {
    val label = ScheduleDescription.statusLabel(status)
    val tint = status.tint
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = L("Status: %@", label) },
    )
}

val ScheduleStatus.tint: Color
    @Composable get() = when (this) {
        ScheduleStatus.Missed -> MaterialTheme.colorScheme.negative
        ScheduleStatus.Due -> MaterialTheme.colorScheme.warning
        ScheduleStatus.Upcoming -> Color(0xFF0A84FF)
        ScheduleStatus.Paid -> MaterialTheme.colorScheme.positive
        ScheduleStatus.Completed, ScheduleStatus.Scheduled -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** One schedule in a list. */
@Composable
fun ScheduleRow(
    schedule: ScheduleSummary,
    status: ScheduleStatus,
    accountName: String?,
    payeeName: String?,
    modifier: Modifier = Modifier,
    runningBalance: Long? = null,
    showsNextDate: Boolean = true,
) {
    val store = LocalBudgetStore.current
    // A schedule need not have a name; fall back to the payee, then the account, so the row is never blank.
    val title = schedule.name?.takeIf { it.isNotEmpty() } ?: payeeName?.takeIf { it.isNotEmpty() } ?: accountName ?: L("Schedule")
    // `~` for an approximate amount and a range for `isbetween`, matching the web's amount cell.
    val amount = schedule.amount
    val amountText = if (schedule.amountOp == ScheduleAmountOp.IsBetween && amount is ScheduledAmount.Range) {
        val (low, high) = if (amount.num1 <= amount.num2) amount.num1 to amount.num2 else amount.num2 to amount.num1
        "${store.displayBalance(low)} – ${store.displayBalance(high)}"
    } else {
        formattedScheduleAmount(store.displayBalance(schedule.postAmount), schedule.amountOp)
    }
    Column(modifier.fillMaxWidth().testTag("scheduleRow.${schedule.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            ScheduleStatusBadge(status)
            Box(Modifier.weight(1f))
            Text(amountText, color = if (schedule.postAmount > 0) MaterialTheme.colorScheme.positive else Color.Unspecified)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            accountName?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.weight(1f))
            if (schedule.isRecurring) {
                Icon(Icons.Filled.Repeat, contentDescription = L("Recurring"), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            runningBalance?.let { Text(store.displayBalance(it), style = MaterialTheme.typography.bodySmall, color = balanceColor(it)) }
            if (showsNextDate) {
                Text(
                    schedule.nextDate?.let { ScheduleDescription.mediumDate(it) } ?: L("No next date"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun formattedScheduleAmount(amount: String, amountOp: ScheduleAmountOp): String = if (amountOp == ScheduleAmountOp.IsApprox) "~ $amount" else amount

/** "Show Upcoming Scheduled Transactions", for the transaction lists' overflow menus. */
@Composable
fun UpcomingSchedulesVisibilityMenuItem() {
    var show by AppStorage.bool(SHOW_UPCOMING_KEY, true)
    DropdownMenuItem(
        text = { Text(L("Show Upcoming Scheduled Transactions")) },
        trailingIcon = { Checkbox(show, onCheckedChange = null) },
        onClick = { show = !show },
        modifier = Modifier.testTag("scheduleRegister.visibility"),
    )
}

const val SHOW_UPCOMING_KEY = "showUpcomingScheduledTransactions"
private const val UPCOMING_EXPANDED_KEY = "upcomingScheduledTransactionsExpanded"

/** State the upcoming register shares across its header and rows; hold it with [rememberUpcomingScheduleState]. */
class UpcomingScheduleState {
    var actionsFor by mutableStateOf<ScheduleSummary?>(null)
    var actionError by mutableStateOf<String?>(null)
}

@Composable
fun rememberUpcomingScheduleState() = remember { UpcomingScheduleState() }

/**
 * The upcoming scheduled transactions above a transaction list: a
 * collapsible header, then one dated group per occurrence day. Tapping an
 * entry offers posting, skipping and editing it.
 */
fun LazyListScope.upcomingScheduleSections(entries: List<UpcomingScheduleEntry>, state: UpcomingScheduleState, showRunningBalance: Boolean = false) {
    item(key = "upcoming-header") { UpcomingHeader() }
    item(key = "upcoming-body") {
        val isExpanded by AppStorage.bool(UPCOMING_EXPANDED_KEY, true)
        if (!isExpanded) return@item
        val store = LocalBudgetStore.current
        // Each day is one segmented group of tiles, like the transaction rows below it.
        Column(Modifier.padding(bottom = 8.dp)) {
            for ((date, group) in entries.groupBy { it.date }.toSortedMap(compareByDescending { it })) {
                ListSectionHeader(Transaction.formattedDate(date.yyyymmdd, io.github.traushb.actuali.models.DateStyle.Long))
                group.forEachIndexed { index, entry ->
                    val schedule = entry.schedule
                    ScheduleRow(
                        schedule,
                        store.scheduleStatuses[schedule.id] ?: ScheduleStatus.Scheduled,
                        accountName = schedule.accountId?.let { id -> store.accounts.firstOrNull { it.id == id }?.name },
                        payeeName = schedule.payeeId?.let { id -> store.payees.firstOrNull { it.id == id }?.name },
                        runningBalance = if (showRunningBalance) entry.runningBalance else null,
                        showsNextDate = false,
                        modifier = Modifier
                            .listTile(index, group.size)
                            .clickable { state.actionsFor = schedule }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("scheduleRegister.row.${schedule.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingHeader() {
    var isExpanded by AppStorage.bool(UPCOMING_EXPANDED_KEY, true)
    val stateText = if (isExpanded) L("expanded") else L("collapsed")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("scheduleRegister.expansion")
            .semantics {
                heading()
                stateDescription = stateText
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DisclosureChevron(isExpanded)
        Text(L("Upcoming Scheduled Transactions"), style = MaterialTheme.typography.titleSmall)
    }
}

/** The dialogs the upcoming register opens; place once per screen alongside [upcomingScheduleSections]. */
@Composable
fun UpcomingScheduleDialogs(state: UpcomingScheduleState) {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    state.actionsFor?.let { schedule ->
        ScheduleActionsDialog(schedule, onDismiss = { state.actionsFor = null }, includeCompletion = false) { action ->
            state.actionsFor = null
            if (action == ScheduleAction.Edit) {
                nav.push(Route.ScheduleEditor(schedule))
            } else {
                scope.runReporting({ state.actionError = it }) { action.perform(store, schedule) }
            }
        }
    }
    MessageAlert(L("Action Failed"), state.actionError) { state.actionError = null }
}
