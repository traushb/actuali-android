package io.github.traushb.actuali.views.bills

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.models.BillCalendarItem
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.bills.BillFilter
import io.github.traushb.actuali.services.bills.BillsCalendarEngine
import io.github.traushb.actuali.services.bills.BillsMonthSummary
import io.github.traushb.actuali.services.bills.BillsTabMode
import io.github.traushb.actuali.services.deleteSchedule
import io.github.traushb.actuali.services.loadSchedules
import io.github.traushb.actuali.services.postScheduleTransaction
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import io.github.traushb.actuali.services.skipScheduleNextDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.schedules.runReporting
import io.github.traushb.actuali.views.schedules.tint
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A calendar dashboard for scheduled transactions and credit card bill cycles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsCalendarView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val today = DayDate.today()
    var year by rememberSaveable { mutableStateOf(today.year) }
    var month by rememberSaveable { mutableStateOf(today.month) }
    var tabMode by rememberSaveable { mutableStateOf(BillsTabMode.Recurring) }
    var activeFilter by rememberSaveable { mutableStateOf(BillFilter.Upcoming) }
    var selectedDate by remember { mutableStateOf<DayDate?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduleSummary?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun shiftMonth(delta: Int) {
        val shifted = DayDate(year, month, 1).addingMonths(delta)
        year = shifted.year
        month = shifted.month
        selectedDate = null
    }

    val items = when (tabMode) {
        BillsTabMode.Recurring -> BillsCalendarEngine.itemsForSchedules(
            store.schedules, store.scheduleStatuses, store.schedulePaymentDates, store.accounts, store.payees, store.categoryGroups, year, month, today,
        )
        BillsTabMode.CardBills -> BillsCalendarEngine.itemsForCreditCards(
            store.accounts,
            store.accounts.mapNotNull { account -> store.activeCreditCardCycle(account.id)?.let { account.id to it } }.toMap(),
            store.creditCardStatementDues,
            year,
            month,
            today,
        )
    }
    val itemsByDate = items.groupBy { it.date }
    val summary = BillsCalendarEngine.summarize(items)
    val filtered = BillsCalendarEngine.filter(items, activeFilter, selectedDate)

    Scaffold(
        topBar = {
            AppTopBar(L("Bills"), actions = {
                IconButton(onClick = { if (tabMode == BillsTabMode.Recurring) nav.push(Route.ScheduleEditor(null)) else nav.push(Route.CreditCardsSettings) }) {
                    Icon(Icons.Filled.Add, contentDescription = if (tabMode == BillsTabMode.Recurring) L("Add Schedule") else L("Configure Cards"))
                }
            })
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
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FormSegmentedRow(BillsTabMode.entries, tabMode, { it.label }, {
                    tabMode = it
                    selectedDate = null
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { shiftMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = L("Previous Month")) }
                    Text(MonthTitles.title("%04d-%02d".format(year, month)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { shiftMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = L("Next Month")) }
                    Spacer(Modifier.weight(1f))
                    if (summary.totalCount > 0) {
                        Text(
                            L("Cleared %lld / %lld", summary.clearedCount.toLong(), summary.totalCount.toLong()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CalendarGrid(year, month, today, selectedDate, itemsByDate) { day -> selectedDate = if (selectedDate == day) null else day }
                SummaryStrip(summary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (filter in BillFilter.entries) {
                        FilterChip(selected = activeFilter == filter, onClick = { activeFilter = filter }, label = { Text(filter.label) })
                    }
                    selectedDate?.let { date ->
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selectedDate = null }) {
                            Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(L("Day %lld", date.day.toLong()), modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.EventAvailable, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (tabMode == BillsTabMode.Recurring) L("No Schedules Due") else L("No Card Bills Due"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (selectedDate != null) L("No items scheduled for this date.") else L("No transactions match the selected filter."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    for (item in filtered) {
                        BillCard(
                            item,
                            onEdit = { item.scheduleSummary?.let { nav.push(Route.ScheduleEditor(it)) } ?: nav.push(Route.CreditCardsSettings) },
                            onPost = { todayOnly -> item.scheduleSummary?.let { scope.runReporting({ actionError = it }) { store.postScheduleTransaction(it, todayOnly) } } },
                            onSkip = { item.scheduleSummary?.let { scope.runReporting({ actionError = it }) { store.skipScheduleNextDate(it) } } },
                            onDelete = { pendingDelete = item.scheduleSummary },
                        )
                    }
                }
            }
        }
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

@Composable
private fun CalendarGrid(year: Int, month: Int, today: DayDate, selected: DayDate?, itemsByDate: Map<DayDate, List<BillCalendarItem>>, onTap: (DayDate) -> Unit) {
    val locale = Locale.getDefault()
    // Monday first.
    val symbols = (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.NARROW_STANDALONE, locale) }
    val cells: List<DayDate?> = List(BillsCalendarEngine.leadingEmptyDays(year, month)) { null } + BillsCalendarEngine.daysInMonth(year, month)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                for (symbol in symbols) {
                    Text(
                        symbol,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            for (week in cells.chunked(7)) {
                Row {
                    for (index in 0 until 7) {
                        val day = week.getOrNull(index)
                        Box(Modifier.weight(1f).height(44.dp)) {
                            if (day != null) DayCell(day, day == today, day == selected, itemsByDate[day].orEmpty()) { onTap(day) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: DayDate, isToday: Boolean, isSelected: Boolean, items: List<BillCalendarItem>, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(28.dp)
                .then(
                    when {
                        isSelected -> Modifier.background(accent, CircleShape)
                        isToday -> Modifier.border(2.dp, accent, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.day.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    items.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            items.firstOrNull()?.let { item ->
                Box(Modifier.size(5.dp).background(item.status.tint, CircleShape))
                if (items.size > 1) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

@Composable
private fun SummaryStrip(summary: BillsMonthSummary) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SummaryMetric(L("Upcoming"), summary.upcomingTotal, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 6.dp))
            SummaryMetric(L("Overdue"), summary.overdueTotal, MaterialTheme.colorScheme.negative, Modifier.weight(1f))
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 6.dp))
            SummaryMetric(L("Paid"), summary.paidTotal, MaterialTheme.colorScheme.positive, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryMetric(title: String, amount: Long, color: Color, modifier: Modifier) {
    val store = LocalBudgetStore.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Column {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(store.displayBalance(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillCard(item: BillCalendarItem, onEdit: () -> Unit, onPost: (Boolean) -> Unit, onSkip: () -> Unit, onDelete: () -> Unit) {
    val store = LocalBudgetStore.current
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onEdit, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (item.isCreditCard) Icons.Filled.CreditCard else Icons.Filled.Repeat, contentDescription = null, tint = item.status.tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.isCreditCard) {
                        // The bill (statement remaining due), labeled so it can't be read as the
                        // card's total balance (GH #535); paid statements show the plain amount.
                        val isPaid = item.status == ScheduleStatus.Paid
                        Text(CreditCardCycle.billAmountText(L("Due"), store.displayBalance(if (isPaid) item.amount else -item.amount), isPaid), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(store.displayBalance(item.amount), style = MaterialTheme.typography.bodyMedium, color = if (item.amount > 0) MaterialTheme.colorScheme.positive else Color.Unspecified)
                    }
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.relativeDueText, style = MaterialTheme.typography.bodySmall, color = item.status.tint)
                }
                listOfNotNull(item.categoryName, item.accountName).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" • "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Column(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(item.dayString, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(item.monthAbbreviation(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.negative)
            }
        }
    }
    if (menu) {
        val schedule = item.scheduleSummary
        val actions = buildList<Pair<String, () -> Unit>> {
            if (schedule != null && !schedule.completed) {
                if (item.isCurrentScheduleOccurrence) {
                    add(L("Post Transaction") to { onPost(false) })
                    add(L("Post Transaction Today") to { onPost(true) })
                    if (schedule.isRecurring) add(L("Skip Occurrence") to onSkip)
                }
                add(L("Edit Schedule") to onEdit)
                add(L("Delete Schedule") to onDelete)
            } else if (item.isCreditCard) {
                add(L("Configure Credit Cards") to onEdit)
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(item.title) },
            text = {
                Column {
                    for ((label, action) in actions) {
                        Text(
                            label,
                            color = if (label == L("Delete Schedule")) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                menu = false
                                action()
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menu = false }) { Text(L("Cancel")) } },
        )
    }
}
