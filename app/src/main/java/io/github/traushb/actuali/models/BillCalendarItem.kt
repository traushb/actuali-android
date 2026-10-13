package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

sealed interface BillItemKind {
    data class Schedule(val summary: ScheduleSummary) : BillItemKind
    data object CreditCard : BillItemKind
}

/**
 * A unified item projected onto the Bills Calendar: either an Actual scheduled
 * transaction occurrence or an upcoming credit card payment due date from
 * [CreditCardCycle].
 */
data class BillCalendarItem(
    val id: String,
    val date: DayDate,
    val title: String,
    /** Cents. Following Actual's convention, negative is money leaving (bills), positive money entering (income). */
    val amount: Long,
    val categoryName: String?,
    val accountName: String?,
    val status: ScheduleStatus,
    val kind: BillItemKind,
    val relativeDueText: String,
) {
    val isCreditCard: Boolean get() = kind == BillItemKind.CreditCard

    val scheduleSummary: ScheduleSummary? get() = (kind as? BillItemKind.Schedule)?.summary

    /** Only the current occurrence can be posted or skipped by the schedule APIs. */
    val isCurrentScheduleOccurrence: Boolean get() = scheduleSummary?.nextDate == date

    /** Two-digit day for the calendar date badge, e.g. "07". */
    val dayString: String get() = "%02d".format(date.day)

    /** Short uppercase month name, e.g. "SEP". */
    fun monthAbbreviation(locale: Locale = Locale.getDefault()): String =
        if (date.month in 1..12) Month.of(date.month).getDisplayName(TextStyle.SHORT_STANDALONE, locale).uppercase(locale) else ""
}
