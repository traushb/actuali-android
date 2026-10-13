package io.github.traushb.actuali.services.bills

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.BillCalendarItem
import io.github.traushb.actuali.models.BillItemKind
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.RecurConfig
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillsCalendarEngineTest {
    private fun monthly(year: Int, month: Int, day: Int, skipWeekend: Boolean = false, weekendSolveMode: String = "after", endMode: String = "never", endOccurrences: Int? = null) =
        ScheduleDateCondition.Recurring(
            RecurConfig.make(RecurConfig.Frequency.Monthly, start = DayDate(year, month, day), skipWeekend = skipWeekend, weekendSolveMode = weekendSolveMode, endMode = endMode, endOccurrences = endOccurrences),
        )

    private fun items(
        schedules: List<ScheduleSummary>,
        statuses: Map<String, ScheduleStatus> = emptyMap(),
        paymentDates: Map<String, Set<DayDate>> = emptyMap(),
        month: Int,
        today: DayDate,
    ) = BillsCalendarEngine.itemsForSchedules(schedules, statuses, paymentDates, emptyList(), emptyList(), emptyList(), 2026, month, today)

    private fun card(name: String, balance: Long) = Account("acc-cc", name, AccountType.Credit, offBudget = false, closed = false, sortOrder = 0, balance = balance)

    @Test
    fun leadingEmptyDaysAndMonthDayCount() {
        // September 1, 2026 is a Tuesday: one blank cell on a Monday-first grid.
        assertEquals(1, BillsCalendarEngine.leadingEmptyDays(2026, 9))
        val days = BillsCalendarEngine.daysInMonth(2026, 9)
        assertEquals(30, days.size)
        assertEquals(DayDate(2026, 9, 1), days.first())
        assertEquals(DayDate(2026, 9, 30), days.last())
    }

    @Test
    fun relativeDueTextFormatting() {
        val today = DayDate(2026, 9, 4)
        assertEquals("Due today", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 4), today, ScheduleStatus.Due))
        assertEquals("Due tomorrow", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 5), today, ScheduleStatus.Upcoming))
        assertEquals("Due in 3 days", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 7), today, ScheduleStatus.Upcoming))
        assertEquals("Overdue by 1 day", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 3), today, ScheduleStatus.Missed))
        assertEquals("Overdue by 3 days", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 1), today, ScheduleStatus.Missed))
        assertEquals("Paid", BillsCalendarEngine.relativeDueText(DayDate(2026, 9, 4), today, ScheduleStatus.Paid))
    }

    @Test
    fun projectsSchedulesIntoMonthOccurrences() {
        val recurring = ScheduleSummary(
            "sch-1", name = "Internet Bill", nextDate = DayDate(2026, 9, 15), amount = ScheduledAmount.Fixed(-7500),
            amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 1, 15), postsTransaction = true,
        )
        val fixed = ScheduleSummary(
            "sch-2", name = "Annual Domain", nextDate = DayDate(2026, 9, 22), amount = ScheduledAmount.Fixed(-2000),
            amountOp = ScheduleAmountOp.IsExactly, dateCondition = ScheduleDateCondition.Fixed(DayDate(2026, 9, 22)),
        )
        val result = items(listOf(recurring, fixed), mapOf("sch-1" to ScheduleStatus.Upcoming, "sch-2" to ScheduleStatus.Upcoming), month = 9, today = DayDate(2026, 9, 4))
        assertEquals(listOf("Internet Bill", "Annual Domain"), result.map { it.title })
        assertEquals(listOf(DayDate(2026, 9, 15), DayDate(2026, 9, 22)), result.map { it.date })
        assertEquals(listOf(-7500L, -2000L), result.map { it.amount })
        assertEquals("Due in 11 days", result[0].relativeDueText)
    }

    private fun item(id: String, day: Int, amount: Long, status: ScheduleStatus) = BillCalendarItem(
        id, DayDate(2026, 9, day), id, amount, null, "Checking", status,
        BillItemKind.Schedule(ScheduleSummary(id, amountOp = ScheduleAmountOp.IsExactly, postsTransaction = true)), "",
    )

    @Test
    fun computesSummaryTotalsAndFilters() {
        val all = listOf(item("1", 7, -12500, ScheduleStatus.Upcoming), item("2", 1, -5000, ScheduleStatus.Missed), item("3", 3, -1500, ScheduleStatus.Paid))
        assertEquals(BillsMonthSummary(upcomingTotal = 12500, overdueTotal = 5000, paidTotal = 1500, clearedCount = 1, totalCount = 3), BillsCalendarEngine.summarize(all))
        assertEquals(listOf("1"), BillsCalendarEngine.filter(all, BillFilter.Upcoming, null).map { it.id })
        assertEquals(listOf("2"), BillsCalendarEngine.filter(all, BillFilter.Overdue, null).map { it.id })
        assertEquals(listOf("3"), BillsCalendarEngine.filter(all, BillFilter.Paid, null).map { it.id })
        assertEquals(listOf("1"), BillsCalendarEngine.filter(all, BillFilter.All, DayDate(2026, 9, 7)).map { it.id })
    }

    /** Statement day 15, due 15 days after: Aug 15's statement fell due Aug 30, so September shows Sept 15's, due Sept 30. */
    @Test
    fun projectsCreditCardBillsIntoMonth() {
        val result = BillsCalendarEngine.itemsForCreditCards(
            listOf(card("Amex Gold", -45000)),
            mapOf("acc-cc" to CreditCardCycle(15, CreditCardCycle.PaymentDue.DaysAfter(15))),
            year = 2026,
            month = 9,
            today = DayDate(2026, 9, 4),
        )
        val bill = result.single()
        assertEquals("Amex Gold", bill.title)
        assertEquals(-45000L, bill.amount)
        assertEquals(DayDate(2026, 9, 30), bill.date)
        assertTrue(bill.isCreditCard)
    }

    /** September 6, 2026 is a Sunday; solving the weekend "before" lands on Friday the 4th, without looping. */
    @Test
    fun skipWeekendBeforeSolvesToFriday() {
        val schedule = ScheduleSummary(
            "sch-weekend", name = "Weekend Bill", nextDate = DayDate(2026, 9, 4), amount = ScheduledAmount.Fixed(-5000),
            amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 9, 6, skipWeekend = true, weekendSolveMode = "before"), postsTransaction = true,
        )
        assertEquals(listOf(DayDate(2026, 9, 4)), items(listOf(schedule), mapOf("sch-weekend" to ScheduleStatus.Upcoming), month = 9, today = DayDate(2026, 9, 1)).map { it.date })
    }

    @Test
    fun exhaustedBoundedRecurrenceShowsNothing() {
        val schedule = ScheduleSummary(
            "sch-bounded", name = "Trial Subscription", nextDate = DayDate(2026, 8, 10), amount = ScheduledAmount.Fixed(-1000),
            amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 8, 10, endMode = "after_n_occurrences", endOccurrences = 1), postsTransaction = true,
        )
        assertTrue(items(listOf(schedule), month = 9, today = DayDate(2026, 9, 1)).isEmpty())
    }

    /** Viewing August though the next date is in September: the past occurrence shows, missed. */
    @Test
    fun recurringScheduleProjectsIntoAPastMonth() {
        val schedule = ScheduleSummary(
            "sch-history", name = "Cloud Storage", nextDate = DayDate(2026, 9, 15), amount = ScheduledAmount.Fixed(-999),
            amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 1, 15), postsTransaction = true,
        )
        val result = items(listOf(schedule), month = 8, today = DayDate(2026, 9, 4)).single()
        assertEquals(DayDate(2026, 8, 15), result.date)
        assertEquals(ScheduleStatus.Missed, result.status)
    }

    private fun augustSchedule(id: String, dateOp: String? = null, postsTransaction: Boolean = true) = ScheduleSummary(
        id, nextDate = DayDate(2026, 9, 15), amount = ScheduledAmount.Fixed(-999), amountOp = ScheduleAmountOp.IsExactly,
        dateOp = dateOp, dateCondition = monthly(2026, 8, 15), postsTransaction = postsTransaction,
    )

    @Test
    fun paidHistoricalOccurrenceKeepsItsPaidStatus() {
        val schedule = augustSchedule("sch-paid-history")
        val result = items(listOf(schedule), paymentDates = mapOf(schedule.id to setOf(DayDate(2026, 8, 15))), month = 8, today = DayDate(2026, 9, 4))
        assertEquals(listOf(ScheduleStatus.Paid), result.map { it.status })
    }

    @Test
    fun approximateHistoricalOccurrenceAcceptsAnEarlyPayment() {
        val schedule = augustSchedule("sch-early-payment", dateOp = "isapprox", postsTransaction = false)
        val result = items(listOf(schedule), paymentDates = mapOf(schedule.id to setOf(DayDate(2026, 8, 13))), month = 8, today = DayDate(2026, 9, 4))
        assertEquals(listOf(ScheduleStatus.Paid), result.map { it.status })
    }

    @Test
    fun historicalOccurrenceAcceptsALatePaymentBeforeTheNextOccurrence() {
        val schedule = augustSchedule("sch-late-payment")
        val result = items(listOf(schedule), paymentDates = mapOf(schedule.id to setOf(DayDate(2026, 8, 20))), month = 8, today = DayDate(2026, 9, 4))
        assertEquals(listOf(ScheduleStatus.Paid), result.map { it.status })
    }

    @Test
    fun onlyTheCurrentOccurrenceAllowsScheduleActions() {
        val schedule = ScheduleSummary("sch-current", nextDate = DayDate(2026, 9, 15), amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 9, 15), postsTransaction = true)
        fun occurrence(date: DayDate) = BillCalendarItem("x", date, "", 0, null, null, ScheduleStatus.Upcoming, BillItemKind.Schedule(schedule), "")
        assertTrue(occurrence(DayDate(2026, 9, 15)).isCurrentScheduleOccurrence)
        assertFalse(occurrence(DayDate(2026, 10, 15)).isCurrentScheduleOccurrence)
    }

    @Test
    fun creditCardCyclesProjectPerNavigatedMonth() {
        val cycles = mapOf("acc-cc" to CreditCardCycle(15, CreditCardCycle.PaymentDue.DaysAfter(15)))
        val today = DayDate(2026, 9, 4)
        val august = BillsCalendarEngine.itemsForCreditCards(listOf(card("Visa Signature", -25000)), cycles, year = 2026, month = 8, today = today).single()
        assertEquals(DayDate(2026, 8, 30), august.date)
        assertEquals(ScheduleStatus.Missed, august.status)
        val october = BillsCalendarEngine.itemsForCreditCards(listOf(card("Visa Signature", -25000)), cycles, year = 2026, month = 10, today = today).single()
        assertEquals(DayDate(2026, 10, 30), october.date)
        assertEquals(ScheduleStatus.Upcoming, october.status)
    }

    /** Salary on the 1st, auto-posted, arriving on Aug 31 for the Sep 1 occurrence. */
    @Test
    fun autoPostingSalaryPaidOnTheLastDayOfThePriorMonthIsPaid() {
        val schedule = ScheduleSummary(
            "sch-salary", name = "Monthly Salary", nextDate = DayDate(2026, 9, 1), amount = ScheduledAmount.Fixed(500_000),
            amountOp = ScheduleAmountOp.IsApprox, dateOp = "isapprox", dateCondition = monthly(2026, 1, 1), postsTransaction = true,
        )
        val result = items(listOf(schedule), mapOf(schedule.id to ScheduleStatus.Paid), mapOf(schedule.id to setOf(DayDate(2026, 8, 31))), month = 9, today = DayDate(2026, 9, 9)).single()
        assertEquals(DayDate(2026, 9, 1), result.date)
        assertEquals(ScheduleStatus.Paid, result.status)
        assertEquals("Paid", result.relativeDueText)
    }

    /** Rent on the 4th, exact date match, paid three days early. */
    @Test
    fun exactRentPaidEarlyInTheCurrentMonthIsPaid() {
        val schedule = ScheduleSummary(
            "sch-rent", name = "Rent", nextDate = DayDate(2026, 9, 4), amount = ScheduledAmount.Fixed(-4_000_000),
            amountOp = ScheduleAmountOp.IsExactly, dateOp = "is", dateCondition = monthly(2026, 1, 4),
        )
        val result = items(listOf(schedule), mapOf(schedule.id to ScheduleStatus.Paid), mapOf(schedule.id to setOf(DayDate(2026, 9, 1))), month = 9, today = DayDate(2026, 9, 9)).single()
        assertEquals(DayDate(2026, 9, 4), result.date)
        assertEquals(ScheduleStatus.Paid, result.status)
    }

    @Test
    fun advancedScheduleWithoutAPaymentIsMissed() {
        val schedule = ScheduleSummary("sch-skipped", nextDate = DayDate(2026, 10, 1), amountOp = ScheduleAmountOp.IsExactly, dateCondition = monthly(2026, 1, 1))
        assertEquals(listOf(ScheduleStatus.Missed), items(listOf(schedule), mapOf(schedule.id to ScheduleStatus.Scheduled), month = 9, today = DayDate(2026, 9, 9)).map { it.status })
    }

    @Test
    fun lateWeeklyPaymentStaysWithTheCurrentOccurrence() {
        val schedule = ScheduleSummary(
            "sch-weekly", nextDate = DayDate(2026, 9, 14), amountOp = ScheduleAmountOp.IsExactly,
            dateCondition = ScheduleDateCondition.Recurring(RecurConfig.make(RecurConfig.Frequency.Weekly, start = DayDate(2026, 9, 7))), postsTransaction = true,
        )
        val result = items(listOf(schedule), mapOf(schedule.id to ScheduleStatus.Upcoming), mapOf(schedule.id to setOf(DayDate(2026, 9, 12))), month = 9, today = DayDate(2026, 9, 13))
        assertEquals(DayDate(2026, 9, 7), result[0].date)
        assertEquals(ScheduleStatus.Paid, result[0].status)
        assertEquals(DayDate(2026, 9, 14), result[1].date)
        assertEquals(ScheduleStatus.Upcoming, result[1].status)
    }

    @Test
    fun creditCardsUseEachPendingStatementWhenDueDatesOverlap() {
        val cycles = mapOf("acc-cc" to CreditCardCycle(15, CreditCardCycle.PaymentDue.DaysAfter(45)))
        val dues = mapOf(
            "acc-cc" to listOf(
                CreditCardCycle.StatementDue(statementBalance = 50000, paymentsSince = 50000, remainingDue = 0, dueDate = DayDate(2026, 3, 1)),
                CreditCardCycle.StatementDue(statementBalance = 30000, paymentsSince = 0, remainingDue = 30000, dueDate = DayDate(2026, 4, 1)),
            ),
        )
        val today = DayDate(2026, 2, 20)
        val march = BillsCalendarEngine.itemsForCreditCards(listOf(card("Visa Signature", -50000)), cycles, dues, 2026, 3, today).single()
        assertEquals(ScheduleStatus.Paid, march.status)
        assertEquals(-50000L, march.amount)
        assertEquals("Paid", march.relativeDueText)
        val april = BillsCalendarEngine.itemsForCreditCards(listOf(card("Visa Signature", -50000)), cycles, dues, 2026, 4, today).single()
        assertEquals(ScheduleStatus.Upcoming, april.status)
        assertEquals(-30000L, april.amount)
    }
}
