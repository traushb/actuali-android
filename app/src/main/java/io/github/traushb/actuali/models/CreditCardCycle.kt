package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.schedules.DayDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Synced configuration for a credit card account, persisted in Actual's
 * `preferences` table under `actuali:credit_card:<accountId>` as a JSON string.
 */
@Serializable(with = CreditCardConfigSerializer::class)
data class CreditCardConfig(
    val statementDay: Int,
    val dueOffsetDays: Int = CreditCardCycle.DEFAULT_DUE_OFFSET_DAYS,
    val dueDay: Int? = null,
    val limit: Long? = null,
) {
    val paymentDue: CreditCardCycle.PaymentDue
        get() = dueDay?.let { CreditCardCycle.PaymentDue.DayOfMonth(it) } ?: CreditCardCycle.PaymentDue.DaysAfter(dueOffsetDays)
}

/** Reads older configs without the later fields, and rejects an out-of-range due day as Swift's decoder does. */
object CreditCardConfigSerializer : KSerializer<CreditCardConfig> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): CreditCardConfig {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val statementDay = json["statementDay"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("statementDay required")
        val dueDay = json["dueDay"]?.jsonPrimitive?.intOrNull
        if (dueDay != null && dueDay !in 1..31) throw IllegalArgumentException("dueDay must be between 1 and 31")
        return CreditCardConfig(
            statementDay = statementDay,
            dueOffsetDays = json["dueOffsetDays"]?.jsonPrimitive?.intOrNull ?: CreditCardCycle.DEFAULT_DUE_OFFSET_DAYS,
            dueDay = dueDay,
            limit = json["limit"]?.jsonPrimitive?.longOrNull,
        )
    }

    override fun serialize(encoder: Encoder, value: CreditCardConfig) {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "statementDay" to JsonPrimitive(value.statementDay),
            "dueOffsetDays" to JsonPrimitive(value.dueOffsetDays),
        )
        value.dueDay?.let { fields["dueDay"] = JsonPrimitive(it) }
        value.limit?.let { fields["limit"] = JsonPrimitive(it) }
        encoder.encodeSerializableValue(JsonObject.serializer(), JsonObject(fields))
    }
}

/** Billing cycle and statement date logic for credit card accounts. */
data class CreditCardCycle(
    /** Day of the month the statement closes (1...31). */
    val statementDay: Int,
    /** Payment due rule for this card. */
    val paymentDue: PaymentDue = PaymentDue.DaysAfter(DEFAULT_DUE_OFFSET_DAYS),
) {
    /**
     * How the payment due date is calculated: either relative to statement
     * closing, or a fixed calendar day of the month.
     */
    sealed interface PaymentDue {
        data class DaysAfter(val days: Int) : PaymentDue
        data class DayOfMonth(val day: Int) : PaymentDue
    }

    /**
     * Days between statement closing and payment due date when using a
     * relative offset, or the default when using a fixed day of the month.
     */
    val dueOffsetDays: Int
        get() = when (paymentDue) {
            is PaymentDue.DaysAfter -> paymentDue.days
            is PaymentDue.DayOfMonth -> DEFAULT_DUE_OFFSET_DAYS
        }

    /** Clamps statement day to the given month's actual length. */
    private fun clampedDay(year: Int, month: Int): Int = min(statementDay, DayDate.lastDay(year, month))

    /**
     * The active billing cycle date range containing [today]. E.g. with
     * statementDay = 15 and today Feb 20: Feb 16 ... Mar 15; with today Feb 10:
     * Jan 16 ... Feb 15.
     */
    fun cycleRange(today: DayDate = DayDate.today()): Pair<DayDate, DayDate> {
        val currentMonthCloseDay = clampedDay(today.year, today.month)
        return if (today.day > currentMonthCloseDay) {
            // Cycle closes next month
            val start = DayDate(today.year, today.month, currentMonthCloseDay).addingDays(1)
            val nextMonth = today.addingMonths(1)
            val end = DayDate(nextMonth.year, nextMonth.month, clampedDay(nextMonth.year, nextMonth.month))
            start to end
        } else {
            // Cycle closes this month
            val prevMonth = today.addingMonths(-1)
            val start = DayDate(prevMonth.year, prevMonth.month, clampedDay(prevMonth.year, prevMonth.month)).addingDays(1)
            start to DayDate(today.year, today.month, currentMonthCloseDay)
        }
    }

    /** The statement that closed before the active cycle started. */
    fun previousStatementDate(today: DayDate = DayDate.today()): DayDate = cycleRange(today).first.addingDays(-1)

    /** The payment due date corresponding to a given statement closing date. */
    fun dueDate(statement: DayDate): DayDate = when (paymentDue) {
        is PaymentDue.DaysAfter -> statement.addingDays(paymentDue.days)
        is PaymentDue.DayOfMonth -> {
            // dueDay > statementDay: due in the statement's month (5th -> 25th).
            // dueDay <= statementDay: due the following month (15th -> 1st).
            val month = if (paymentDue.day > statementDay) statement else statement.addingMonths(1)
            DayDate(month.year, month.month, min(paymentDue.day, DayDate.lastDay(month.year, month.month)))
        }
    }

    /**
     * The statement closing date whose payment is next due. Walks back through
     * closed statements rather than assuming only the most recent one is
     * pending, in case the offset exceeds a monthly cycle.
     */
    fun upcomingStatementDate(today: DayDate = DayDate.today()): DayDate {
        var pendingStatement = cycleRange(today).second
        var statement = previousStatementDate(today)
        for (i in 0..(dueOffsetDays / 28 + 1)) {
            if (today > dueDate(statement)) break
            pendingStatement = statement
            statement = previousStatementDate(statement)
        }
        return pendingStatement
    }

    /** Next upcoming payment due date. */
    fun upcomingDueDate(today: DayDate = DayDate.today()): DayDate = dueDate(upcomingStatementDate(today))

    data class Cycle(val start: DayDate, val end: DayDate, val dueDate: DayDate)

    /** The last three closed billing statement cycles, newest to oldest. */
    fun recentStatementCycles(today: DayDate = DayDate.today()): List<Cycle> {
        val cycles = mutableListOf<Cycle>()
        var currentEnd = previousStatementDate(today)
        repeat(3) {
            val prevEnd = previousStatementDate(currentEnd)
            cycles += Cycle(prevEnd.addingDays(1), currentEnd, dueDate(currentEnd))
            currentEnd = prevEnd
        }
        return cycles
    }

    /** Status of the payment due for a credit card statement. */
    data class StatementDue(
        /** Balance in cents owed when the statement closed (positive). */
        val statementBalance: Long,
        /** Payments/credits in cents received since the statement closed (positive). */
        val paymentsSince: Long,
        /** Remaining balance in cents to pay for this statement (positive). */
        val remainingDue: Long,
        val dueDate: DayDate,
    ) {
        val isPaid: Boolean get() = remainingDue == 0L && statementBalance > 0
    }

    /** A closed billing statement with spend, due, and transaction metrics. */
    data class StatementRecord(
        val startDate: DayDate,
        val endDate: DayDate,
        val dueDate: DayDate,
        val statementBalance: Long,
        val paymentsSince: Long,
        val remainingDue: Long,
        /** Outflow spend in cents during the billing cycle (positive). */
        val totalSpend: Long,
    ) {
        val id: Int get() = endDate.yyyymmdd

        val isPaid: Boolean get() = remainingDue == 0L && statementBalance > 0
    }

    /** Days remaining until the current billing cycle closes. */
    fun daysRemainingInCycle(today: DayDate = DayDate.today()): Int = max(0, today.days(cycleRange(today).second))

    /** Days remaining until the next payment due date. */
    fun daysUntilDue(today: DayDate = DayDate.today(), dueDate: DayDate? = null): Int =
        max(0, today.days(dueDate ?: upcomingDueDate(today)))

    /**
     * One-line payment summary ("Due 30 Aug 2026 (9d)"). Shared by the Credit
     * Cards row and the account detail header so the two can't word the same
     * fact differently.
     */
    fun dueSummary(today: DayDate = DayDate.today(), dueDate: DayDate? = null): String {
        val due = dueDate ?: upcomingDueDate(today)
        val days = daysUntilDue(today, due)
        if (days == 0) return L("Due today")
        if (days == 1) return L("Due tomorrow")
        val dueStr = Transaction.formattedDate(due.yyyymmdd, DateStyle.Abbreviated)
        return L("Due %@ (%lldd)", dueStr, days.toLong())
    }

    /** Compact variant of [dueSummary] for pill badges ("Due in 27d"). */
    fun dueShortSummary(today: DayDate = DayDate.today(), dueDate: DayDate? = null): String {
        val days = daysUntilDue(today, dueDate)
        return if (days <= 1) dueSummary(today, dueDate) else L("Due in %lldd", days.toLong())
    }

    companion object {
        /** Applied to cards configured before the offset became per-card. */
        const val DEFAULT_DUE_OFFSET_DAYS = 15

        /** Widest offset the picker offers; also bounds the pending-statement walk. */
        const val MAX_DUE_OFFSET_DAYS = 60

        /**
         * The statement still awaiting payment: the first unpaid one that isn't
         * past due, else the first not-yet-due one even if already paid.
         */
        fun pendingStatementDue(dues: List<StatementDue>, today: DayDate = DayDate.today()): StatementDue? =
            dues.firstOrNull { today <= it.dueDate && it.remainingDue > 0 } ?: dues.firstOrNull { today <= it.dueDate }

        /** Computes the statement payment status given raw balances and payments. */
        fun calculateStatementDue(statementRawBalance: Long, paymentsSince: Long, liveBalance: Long, dueDate: DayDate): StatementDue {
            val statementOwed = max(0, -statementRawBalance)
            val unpaid = max(0, statementOwed - paymentsSince)
            val remaining = min(unpaid, max(0, -liveBalance))
            return StatementDue(statementOwed, paymentsSince, remaining, dueDate)
        }

        /** Amount line for a credit card row in the bills list. */
        fun billAmountText(dueLabel: String, amount: String, isPaid: Boolean): String = if (isPaid) amount else "$dueLabel $amount"

        /** Cards-row due pill: "$342.18 · Due in 27d"; a settled statement shows the summary alone. */
        fun duePillText(amount: String, summary: String, remainingDue: Long): String =
            if (remainingDue > 0) "$amount · $summary" else summary
    }
}
