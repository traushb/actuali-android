package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong

/** The kind of entry being captured by the add/edit transaction form. */
enum class TransactionType { Expense, Income, Transfer }

/** Apple's `Date.FormatStyle.DateStyle`, mapped onto java.time. */
enum class DateStyle(val format: FormatStyle) {
    Numeric(FormatStyle.SHORT),
    Abbreviated(FormatStyle.MEDIUM),
    Long(FormatStyle.LONG),
    Complete(FormatStyle.FULL),
}

@Serializable
data class Transaction(
    override val id: String,
    val accountId: String,
    /** YYYYMMDD format. */
    val date: Int,
    /** Stored in cents (negative = outflow, positive = inflow). */
    val amount: Long,
    val payeeId: String?,
    /** Denormalized for display. */
    val payeeName: String?,
    val categoryId: String?,
    /** Denormalized for display. */
    val categoryName: String?,
    val notes: String?,
    val cleared: Boolean,
    val reconciled: Boolean,
    /** Links to paired transfer transaction. */
    val transferId: String?,
    /** True if this is a split parent. */
    val isParent: Boolean,
    /** Links to parent if this is a split child. */
    val parentId: String?,
    val tombstone: Boolean,
    /** Timestamp in ms, determines order within same date. */
    val sortOrder: Double?,
    /** Original payee text from import / Shortcut entry. */
    val importedPayee: String?,
    /** Id of the schedule that posted this transaction, nil if entered manually. */
    val schedule: String? = null,
    /**
     * Bank-import dedup key (Actual's imported_id, stored as financial_id).
     * Set at creation time by imports; not read back by the fetch paths, so
     * it is null on fetched rows — dedup queries the column directly.
     */
    val financialId: String? = null,
    /**
     * Marks the special "opening balance" transaction created alongside a
     * new account (Actual's starting_balance_flag). Only ever set at
     * creation, matching financialId's write-only shape.
     */
    val startingBalanceFlag: Boolean = false,
    /**
     * Payee's transfer_acct: the account on the other side when the payee is a
     * transfer payee, null otherwise. Populated by the display and reports
     * fetches so rows can render transfers as transfers and engines can
     * exclude them the way the WebUI does. Not synced (it lives on the payee,
     * not the transaction).
     */
    val transferAcct: String? = null,
    /**
     * One entry per live child of a split parent, in entry order. Populated
     * for isParent rows by fetchTransactions (the list row's breakdown) and
     * fetchAllLiveTransactions (History's caption). Display-only, not synced.
     */
    val splitPortions: List<SplitPortion>? = null,
    /**
     * Display-only running balance used by account transaction registers.
     * Populated from the account's current balance in account detail views
     * and intentionally not part of CRDT sync.
     */
    val runningBalance: Long? = null,
) : CRDTSyncable {
    @Serializable
    data class SplitPortion(
        val categoryName: String?,
        /** Cents, signed like the parent. */
        val amount: Long,
    )

    override val datasetName: String get() = DATASET_NAME

    val dateFormatted: String get() = formattedDate(date, DateStyle.Abbreviated)

    val isOutflow: Boolean get() = amount < 0

    /**
     * Whether this transaction still needs a category, mirroring the WebUI's
     * "uncategorized" filter (see `BudgetDatabase.uncategorizedWhere`): split
     * parents carry no category of their own (the children do), off-budget
     * accounts aren't categorized at all, and transfers only take a category
     * when the other side is off-budget — money leaving the budget still
     * needs one (GH #123, #104).
     */
    fun needsCategory(offBudgetAccountIds: Set<String>): Boolean {
        if (categoryId != null || isParent) return false
        if (accountId in offBudgetAccountIds) return false
        transferAcct?.let { return it in offBudgetAccountIds }
        return transferId == null
    }

    override val syncableFields: Map<String, Any?>
        get() {
            val fields = linkedMapOf<String, Any?>(
                "acct" to accountId,
                "date" to date,
                // payeeId maps to the "description" column
                "description" to payeeId,
                "category" to categoryId,
                "amount" to amount,
                "notes" to notes,
                "cleared" to if (cleared) 1 else 0,
                "reconciled" to if (reconciled) 1 else 0,
                "transferred_id" to transferId,
                "isParent" to if (isParent) 1 else 0,
                "isChild" to if (parentId != null) 1 else 0,
                "parent_id" to parentId,
                "tombstone" to if (tombstone) 1 else 0,
                "sort_order" to (sortOrder ?: (System.currentTimeMillis().toDouble())),
                "imported_description" to importedPayee,
                "schedule" to schedule,
            )
            // Only present on imported transactions — keeps inserts for ordinary
            // transactions identical (messagesForInsert emits every listed field,
            // null included).
            if (financialId != null) fields["financial_id"] = financialId
            if (startingBalanceFlag) fields["starting_balance_flag"] = 1
            return fields
        }

    companion object {
        const val DATASET_NAME = "transactions"

        fun formattedDate(dateInt: Int, style: DateStyle = DateStyle.Abbreviated, locale: Locale = Locale.getDefault()): String {
            val year = dateInt / 10000
            val month = (dateInt % 10000) / 100
            val day = dateInt % 100
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                ?: return "$year-$month-$day"
            return date.format(localizedFormatters.getOrPut(style to locale) { DateTimeFormatter.ofLocalizedDate(style.format).withLocale(locale) })
        }

        /** Every transaction row formats its date; the formatter is built once per style and locale. */
        private val localizedFormatters = ConcurrentHashMap<Pair<DateStyle, Locale>, DateTimeFormatter>()

        /**
         * Convert a dollar amount to integer cents, rounding half away from zero
         * (e.g. 8.20 → 820, not 819 via truncation).
         * @return null if the value is non-finite or outside the exactly
         *   representable integer range of `Double` (±2^53).
         */
        fun cents(fromDollars: Double): Long? {
            val scaled = fromDollars * 100
            if (!scaled.isFinite()) return null
            // Kotlin's roundToLong rounds half up; Swift's rounded() is half away from zero.
            val cents = if (scaled < 0) -abs(scaled).roundToLong() else scaled.roundToLong()
            if (abs(cents) > 9_007_199_254_740_992L) return null
            return cents
        }

        /** Encode a calendar date as the YYYYMMDD integer used throughout the database. */
        fun yyyymmdd(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

        fun yyyymmdd(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
            yyyymmdd(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())

        /**
         * Inverse of [yyyymmdd]. Falls back to today for values that don't
         * decode to a real calendar date.
         */
        fun date(fromYYYYMMDD: Int): LocalDate =
            runCatching { LocalDate.of(fromYYYYMMDD / 10000, (fromYYYYMMDD % 10000) / 100, fromYYYYMMDD % 100) }
                .getOrElse { LocalDate.now() }
    }
}

data class TransactionDateGroup(
    val date: Int,
    val transactions: List<Transaction>,
) {
    val id: Int get() = date

    /**
     * Section header for the group. Spelled out in full because the rows
     * underneath drop their own date once they're grouped.
     */
    val title: String get() = Transaction.formattedDate(date, DateStyle.Long)
}

/**
 * Groups transactions by date, preserving the list's existing encounter
 * order. Every list that calls this fetches `ORDER BY date DESC`, so the
 * groups come out newest-first and each date appears exactly once.
 */
fun List<Transaction>.groupedByDate(): List<TransactionDateGroup> =
    groupBy { it.date }.map { (date, transactions) -> TransactionDateGroup(date, transactions) }

/**
 * Adds the register balance after each transaction to a newest-first
 * transaction list. Starting at the account's current balance means the
 * newest transaction shows the current balance, while each older row
 * walks backward by that row's amount.
 */
fun List<Transaction>.withRunningBalances(startingAt: Long): List<Transaction> {
    var balance = startingAt
    return map { transaction ->
        transaction.copy(runningBalance = balance).also { balance -= transaction.amount }
    }
}
