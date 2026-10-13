package io.github.traushb.actuali.services.bills

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.BillCalendarItem
import io.github.traushb.actuali.models.BillItemKind
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import io.github.traushb.actuali.services.schedules.ScheduleStatusCalculator
import kotlin.math.abs
import kotlin.math.max

/** Filter mode for the Bills Calendar list. */
enum class BillFilter(val rawValue: String) {
    All("All"),
    Upcoming("Upcoming"),
    Overdue("Overdue"),
    Paid("Paid"),
    ;

    val label: String get() = L(rawValue)
}

/** Tab switcher between Recurring Schedules and Credit Card Bills. */
enum class BillsTabMode(val rawValue: String) {
    Recurring("Recurring"),
    CardBills("Card Bills"),
    ;

    val label: String get() = L(rawValue)
}

/** Month cashflow summary for the bills calendar header. Amounts in cents. */
data class BillsMonthSummary(val upcomingTotal: Long, val overdueTotal: Long, val paidTotal: Long, val clearedCount: Int, val totalCount: Int)

/** Pure functions for generating calendar grid cells, occurrences, and summary statistics. */
object BillsCalendarEngine {
    /** Number of blank leading cells in a Monday-first monthly grid. In [DayDate], 1 = Sunday … 7 = Saturday. */
    fun leadingEmptyDays(year: Int, month: Int): Int = (DayDate(year, month, 1).weekday + 5) % 7

    fun daysInMonth(year: Int, month: Int): List<DayDate> = (1..DayDate.lastDay(year, month)).map { DayDate(year, month, it) }

    /** "Due in 3 days", "Due today", "Overdue by 2 days", "Paid". */
    fun relativeDueText(date: DayDate, today: DayDate = DayDate.today(), status: ScheduleStatus): String {
        if (status == ScheduleStatus.Paid || status == ScheduleStatus.Completed) return L("Paid")
        val diff = today.days(date)
        return when {
            diff < 0 -> if (diff == -1) L("Overdue by 1 day") else L("Overdue by %lld days", abs(diff).toLong())
            diff == 0 -> L("Due today")
            diff == 1 -> L("Due tomorrow")
            else -> L("Due in %lld days", diff.toLong())
        }
    }

    /** Projects recurring schedules into [BillCalendarItem]s for the month. */
    fun itemsForSchedules(
        schedules: List<ScheduleSummary>,
        statuses: Map<String, ScheduleStatus>,
        paymentDates: Map<String, Set<DayDate>> = emptyMap(),
        accounts: List<Account>,
        payees: List<Payee>,
        categoryGroups: List<CategoryGroup>,
        year: Int,
        month: Int,
        today: DayDate = DayDate.today(),
    ): List<BillCalendarItem> {
        val monthStart = DayDate(year, month, 1)
        val monthEnd = DayDate(year, month, DayDate.lastDay(year, month))
        val accountMap = accounts.associate { it.id to it.name }
        val payeeMap = payees.associate { it.id to it.name }
        val categoryMap = categoryGroups.flatMap { it.categories }.associate { it.id to it.name }
        val items = mutableListOf<BillCalendarItem>()

        for (schedule in schedules) {
            val title = schedule.name ?: schedule.payeeId?.let { payeeMap[it] } ?: L("Scheduled Transaction")
            val accountName = schedule.accountId?.let { accountMap[it] }
            val categoryName = schedule.categoryId?.let { categoryMap[it] }
            val baseStatus = statuses[schedule.id] ?: if (schedule.completed) ScheduleStatus.Completed else ScheduleStatus.Scheduled
            fun item(date: DayDate, status: ScheduleStatus, dueText: String = relativeDueText(date, today, status)) = BillCalendarItem(
                "${schedule.id}_${date.yyyymmdd}", date, title, schedule.postAmount, categoryName, accountName, status, BillItemKind.Schedule(schedule), dueText,
            )
            val next = schedule.nextDate?.takeIf { it.year == year && it.month == month }

            if (schedule.completed) {
                // Completed schedules only show if their recorded nextDate fell in this month.
                next?.let { items += item(it, ScheduleStatus.Completed, L("Completed")) }
                continue
            }

            when (val condition = schedule.dateCondition) {
                is ScheduleDateCondition.Recurring -> {
                    val config = condition.config
                    val occurrenceDates = sortedSetOf<DayDate>()
                    next?.let { occurrenceDates += it }
                    occurrenceDates += ScheduleRecurrence.upcomingDates(config, 31, monthStart).filter { it in monthStart..monthEnd }
                    val sortedDates = occurrenceDates.toList()
                    fun matchStart(date: DayDate) =
                        ScheduleStatusCalculator.occurrenceMatchStartDate(date, schedule.dateOp, schedule.postsTransaction, config.frequency)

                    for ((index, date) in sortedDates.withIndex()) {
                        val earlyBound = matchStart(date)
                        val matchStart = sortedDates.getOrNull(index - 1)?.let { maxOf(it.addingDays(1), earlyBound) } ?: earlyBound
                        val nextDate = sortedDates.getOrNull(index + 1) ?: ScheduleRecurrence.nextOccurrence(config, date.addingDays(1))
                        val nextMatchStart = nextDate?.let { following ->
                            when {
                                following <= date -> null
                                following > today -> following
                                else -> maxOf(date.addingDays(1), matchStart(following))
                            }
                        }
                        val itemStatus = when {
                            date == schedule.nextDate -> baseStatus
                            paymentDates[schedule.id]?.any { it >= matchStart && (nextMatchStart == null || it < nextMatchStart) } == true -> ScheduleStatus.Paid
                            date < today -> ScheduleStatus.Missed
                            date == today -> ScheduleStatus.Due
                            else -> ScheduleStatus.Upcoming
                        }
                        items += item(date, itemStatus)
                    }
                }
                is ScheduleDateCondition.Fixed -> {
                    val day = condition.day
                    if (day.year == year && day.month == month) items += item(day, baseStatus)
                }
                ScheduleDateCondition.Unsupported, null -> next?.let { items += item(it, baseStatus) }
            }
        }
        return items.sortedBy { it.date }
    }

    /** Projects credit card payment due dates into [BillCalendarItem]s for the month. */
    fun itemsForCreditCards(
        accounts: List<Account>,
        cycles: Map<String, CreditCardCycle>,
        statementDues: Map<String, List<CreditCardCycle.StatementDue>> = emptyMap(),
        year: Int,
        month: Int,
        today: DayDate = DayDate.today(),
    ): List<BillCalendarItem> {
        val items = mutableListOf<BillCalendarItem>()
        for (account in accounts) {
            if (account.closed) continue
            val cycle = cycles[account.id] ?: continue
            val dueDate = cycle.upcomingDueDate(DayDate(year, month, 1))
            if (dueDate.year != year || dueDate.month != month) continue

            fun openStatus() = when {
                dueDate < today -> ScheduleStatus.Missed
                dueDate == today -> ScheduleStatus.Due
                else -> ScheduleStatus.Upcoming
            }
            val statementDue = statementDues[account.id]?.firstOrNull { it.dueDate == dueDate }
            // (status, amount owed, due text)
            val (status, billAmount, dueText) = when {
                statementDue?.isPaid == true -> Triple(ScheduleStatus.Paid, statementDue.statementBalance, L("Paid"))
                statementDue != null && statementDue.remainingDue == 0L -> Triple(ScheduleStatus.Paid, 0L, L("Paid / Zero Balance"))
                statementDue != null -> openStatus().let { Triple(it, statementDue.remainingDue, relativeDueText(dueDate, today, it)) }
                max(0L, -account.balance) == 0L -> Triple(ScheduleStatus.Paid, 0L, L("Paid / Zero Balance"))
                else -> openStatus().let { Triple(it, -account.balance, relativeDueText(dueDate, today, it)) }
            }
            items += BillCalendarItem(
                "cc_${account.id}_${dueDate.yyyymmdd}", dueDate, account.name,
                -billAmount, // a bill is an outflow
                L("Credit Card Payment"), account.name, status, BillItemKind.CreditCard, dueText,
            )
        }
        return items.sortedBy { it.date }
    }

    /** Month cashflow totals and cleared count. */
    fun summarize(items: List<BillCalendarItem>): BillsMonthSummary {
        var upcoming = 0L
        var overdue = 0L
        var paid = 0L
        var cleared = 0
        for (item in items) {
            val amount = abs(item.amount)
            when (item.status) {
                ScheduleStatus.Paid, ScheduleStatus.Completed -> {
                    paid += amount
                    cleared += 1
                }
                ScheduleStatus.Missed -> overdue += amount
                ScheduleStatus.Due, ScheduleStatus.Upcoming, ScheduleStatus.Scheduled -> upcoming += amount
            }
        }
        return BillsMonthSummary(upcoming, overdue, paid, cleared, items.size)
    }

    /** Items matching the selected filter pill and optional selected date. */
    fun filter(items: List<BillCalendarItem>, filter: BillFilter, selectedDate: DayDate?): List<BillCalendarItem> = items.filter { item ->
        if (selectedDate != null && item.date != selectedDate) return@filter false
        when (filter) {
            BillFilter.All -> true
            BillFilter.Upcoming -> item.status == ScheduleStatus.Upcoming || item.status == ScheduleStatus.Due || item.status == ScheduleStatus.Scheduled
            BillFilter.Overdue -> item.status == ScheduleStatus.Missed
            BillFilter.Paid -> item.status == ScheduleStatus.Paid || item.status == ScheduleStatus.Completed
        }
    }
}
