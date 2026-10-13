package io.github.traushb.actuali.services.notifications

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.DateStyle
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.schedules.DayDate
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Schedules notifications for credit cards with upcoming payment due dates:
 * reminders at 7, 5, 3, and 1 days before the due date while the card has an
 * unpaid statement, falling back to the live balance when statement data is
 * unavailable. Main-thread only, like the iOS @MainActor notifier.
 */
class CreditCardDueNotifier {
    private data class Inputs(
        val accounts: List<Account>,
        val cycles: Map<String, CreditCardCycle>,
        val statementDues: Map<String, List<CreditCardCycle.StatementDue>>,
        val currencyCode: String,
        val narrowSymbol: Boolean,
        val enabled: Boolean,
        val today: DayDate,
        val beforeReminderTime: Boolean,
        val zone: ZoneId,
        val locale: Locale,
        val allowed: Boolean,
    )

    private var lastInputs: Inputs? = null

    /** Schedule or cancel reminders from the active cycles, statement dues, balances, and the user's setting. */
    fun scheduleNotifications(
        accounts: List<Account>,
        cycles: Map<String, CreditCardCycle>,
        statementDues: Map<String, List<CreditCardCycle.StatementDue>> = emptyMap(),
        currencyCode: String,
        narrowSymbol: Boolean = false,
        settings: CreditCardNotificationSettings = CreditCardNotificationSettings(),
        center: NotificationPosting? = NewTransactionNotifier.poster,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        center ?: return
        val nowAtZone = Instant.ofEpochMilli(now).atZone(zone)
        val today = DayDate.of(nowAtZone.toLocalDate())
        val inputs = Inputs(
            accounts, cycles, statementDues, currencyCode, narrowSymbol, settings.isEnabled, today,
            nowAtZone.hour < 9, zone, Locale.getDefault(), settings.isEnabled && center.isAllowed(),
        )
        if (inputs == lastInputs) return
        val previous = lastInputs
        lastInputs = inputs
        val accountIds = accounts.map { it.id }.toSet() + cycles.keys + previous?.accounts?.map { it.id }.orEmpty() + previous?.cycles?.keys.orEmpty()
        // Disabled or not permitted: clear any pending reminders for known accounts.
        if (!inputs.allowed) {
            center.cancel(accountIds.flatMap { accountId -> REMINDER_OFFSETS.map { requestIdentifier(accountId, it) } })
            return
        }

        val accountsById = accounts.associateBy { it.id }
        for (accountId in accountIds) {
            val ids = REMINDER_OFFSETS.map { requestIdentifier(accountId, it) }
            val dues = statementDues[accountId]
            val statementDue = dues?.firstOrNull { today <= it.dueDate && it.remainingDue > 0 }
            val isUnpaid = if (dues == null) accountsById[accountId]?.let { it.balance < 0 } ?: false else statementDue != null
            val account = accountsById[accountId]
            val cycle = cycles[accountId]
            if (account == null || account.closed || !isUnpaid || cycle == null) {
                center.cancel(ids)
                continue
            }

            // Card has an unpaid balance. Schedule reminders for upcoming offsets.
            val dueDate = statementDue?.dueDate ?: cycle.upcomingDueDate(today)
            for (offset in REMINDER_OFFSETS) {
                val id = requestIdentifier(accountId, offset)
                val reminderAt = dueDate.addingDays(-offset).localDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                // Do not schedule notifications for dates/times already in the past.
                if (reminderAt <= now) {
                    center.cancel(listOf(id))
                    continue
                }
                center.schedule(id, reminderAt, makeContent(account, dueDate, offset, statementDue, currencyCode, narrowSymbol))
            }
        }
    }

    companion object {
        /** Reminders scheduled at 7, 5, 3, and 1 day before due date. */
        val REMINDER_OFFSETS = listOf(7, 5, 3, 1)

        const val IDENTIFIER_PREFIX = "com.mfazz.Actuali.creditCardDue."

        /** Routes the tap to the card's account. */
        const val CATEGORY_IDENTIFIER = "CREDIT_CARD_DUE"

        fun requestIdentifier(accountId: String, offsetDays: Int) = "$IDENTIFIER_PREFIX$accountId.${offsetDays}d"

        fun makeContent(
            account: Account,
            dueDate: DayDate,
            offsetDays: Int,
            statementDue: CreditCardCycle.StatementDue? = null,
            currencyCode: String,
            narrowSymbol: Boolean,
        ): NotificationContent {
            val daysText = if (offsetDays == 1) L("tomorrow") else L("in %lld days", offsetDays.toLong())
            val dueDateFormatted = Transaction.formattedDate(dueDate.yyyymmdd, DateStyle.Abbreviated)
            val body = if (statementDue != null && statementDue.remainingDue > 0) {
                val dueAmount = CurrencyAmountFormat.string(statementDue.remainingDue, currencyCode, narrowSymbol)
                L("Statement due %1\$@. Payment due %2\$@.", dueAmount, dueDateFormatted)
            } else {
                val amount = CurrencyAmountFormat.string(abs(account.balance), currencyCode, narrowSymbol)
                L("Current balance %@. Payment due %@.", amount, dueDateFormatted)
            }
            return NotificationContent(L("%@ payment due %@", account.name, daysText), body, CATEGORY_IDENTIFIER, accountId = account.id)
        }
    }
}
