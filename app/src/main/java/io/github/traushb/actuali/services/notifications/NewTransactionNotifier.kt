package io.github.traushb.actuali.services.notifications

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.bool
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.fetchMaxMessageId
import io.github.traushb.actuali.services.database.fetchTransactionsCreated
import io.github.traushb.actuali.services.remove

private val logger = Logger("NewTransactionNotifier")

/**
 * Whether the user has opted into transaction notifications (GH #27). Default
 * off — this gates notification posting only; background refresh runs
 * regardless (fresh data on open).
 */
class TransactionNotificationSettings(private val defaults: Defaults = Defaults.standard) {
    var isEnabled: Boolean
        get() = defaults.bool(KEY)
        set(value) = defaults.set(KEY, value)

    companion object {
        const val KEY = "transactionNotificationsEnabled"
    }
}

/**
 * Whether the user has opted into credit card payment due date notifications:
 * reminders at 7, 5, 3, and 1 days before the due date while the card has an
 * unpaid balance.
 */
class CreditCardNotificationSettings(private val defaults: Defaults = Defaults.standard) {
    var isEnabled: Boolean
        get() = defaults.bool(KEY)
        set(value) = defaults.set(KEY, value)

    companion object {
        const val KEY = "creditCardDueNotificationsEnabled"
    }
}

/**
 * Finds transactions that arrived via sync since the last check, so each sync
 * path (foreground or background) can notify about them exactly once.
 *
 * The watermark is the highest messages_crdt rowid seen at the previous check,
 * persisted per budget (device-local state — it must not sync). A transaction
 * counts as new when its first CRDT message landed after the watermark and was
 * authored by another device.
 */
class NewTransactionDetector(private val defaults: Defaults = Defaults.standard) {
    suspend fun detectNewTransactions(database: BudgetDatabase, budgetId: String, localNode: String): List<Transaction> {
        val key = watermarkKey(budgetId)
        val maxId = database.fetchMaxMessageId()
        val watermark = (defaults.get(key) as? Number)?.toLong()
        if (watermark == null || watermark > maxId) {
            // First run, or the watermark is ahead of the database because the
            // budget file was re-downloaded (message ids reset). Baseline
            // silently rather than notifying about history.
            defaults.set(key, maxId)
            return emptyList()
        }
        if (maxId <= watermark) return emptyList()
        val created = database.fetchTransactionsCreated(watermark, localNode)
        defaults.set(key, maxId)
        return created
    }

    companion object {
        private fun watermarkKey(budgetId: String) = "transactionNotificationWatermark.$budgetId"

        /**
         * Drop the watermark for a budget whose file was just downloaded. The
         * new snapshot's messages_crdt rowids come from whichever client
         * uploaded it, so a watermark left by a previous copy points at
         * unrelated messages. Forgetting it makes the next detection re-baseline.
         */
        fun forgetWatermark(budgetId: String, defaults: Defaults = Defaults.standard) = defaults.remove(watermarkKey(budgetId))
    }
}

/**
 * Posts one notification per sync cycle summarizing transactions that arrived
 * from other devices (from [NewTransactionDetector]).
 */
object NewTransactionNotifier {
    /** Stable, so a newer summary replaces the previous one instead of stacking. */
    const val REQUEST_IDENTIFIER = "com.mfazz.Actuali.newTransactions"

    /** Routes the tap-through to the transaction editor. */
    const val CATEGORY_IDENTIFIER = "NEW_TRANSACTIONS"

    /** Detail lines shown for a batch before overflowing into "…and N more". */
    const val MAX_DETAIL_LINES = 4

    /** Installed at launch with the app's poster; JVM tests pass their own. */
    @Volatile
    var poster: NotificationPosting? = null

    /**
     * Every sync path calls this unconditionally; the user's opt-in is
     * enforced here, not at scheduling time, so syncing itself keeps data
     * fresh for everyone.
     */
    fun notify(
        transactions: List<Transaction>,
        currencyCode: String,
        narrowSymbol: Boolean = false,
        numberFormat: ActualNumberFormat = ActualNumberFormat.CommaDot,
        accountNames: Map<String, String> = emptyMap(),
        offBudgetAccountIds: Set<String> = emptySet(),
        settings: TransactionNotificationSettings = TransactionNotificationSettings(),
        center: NotificationPosting? = poster,
    ) {
        if (!settings.isEnabled || center == null) return
        val content = makeContent(transactions, currencyCode, narrowSymbol, numberFormat, accountNames, offBudgetAccountIds) ?: return
        if (!center.isAllowed()) return
        center.post(REQUEST_IDENTIFIER, content)
        logger.info("Posted new-transaction notification (${transactions.size} transactions)")
    }

    fun makeContent(
        transactions: List<Transaction>,
        currencyCode: String,
        narrowSymbol: Boolean = false,
        numberFormat: ActualNumberFormat = ActualNumberFormat.CommaDot,
        accountNames: Map<String, String> = emptyMap(),
        offBudgetAccountIds: Set<String> = emptySet(),
    ): NotificationContent? {
        if (transactions.isEmpty()) return null
        val lines = transactions.take(MAX_DETAIL_LINES).map { line(it, currencyCode, narrowSymbol, numberFormat, accountNames, offBudgetAccountIds) }.toMutableList()
        if (transactions.size > MAX_DETAIL_LINES) lines += L("…and %lld more", transactions.size - MAX_DETAIL_LINES)
        return NotificationContent(
            title = L("%lld new transactions", transactions.size),
            body = lines.joinToString("\n"),
            category = CATEGORY_IDENTIFIER,
            transactionIds = transactions.map { it.id },
        )
    }

    /**
     * One transaction's detail: "$12.50 at Starbucks on Checking", with an
     * uncategorized marker so each line says whether it still needs sorting.
     * Transfers and off-budget transactions never take a category, so they
     * carry no marker (GH #104, #123).
     */
    private fun line(
        transaction: Transaction,
        currencyCode: String,
        narrowSymbol: Boolean,
        numberFormat: ActualNumberFormat,
        accountNames: Map<String, String>,
        offBudgetAccountIds: Set<String>,
    ): String {
        var line = CurrencyAmountFormat.string(transaction.amount, currencyCode, narrowSymbol = narrowSymbol, numberFormat = numberFormat)
        transaction.payeeName?.takeIf { it.isNotEmpty() }?.let { line = L("%@ at %@", line, it) }
        accountNames[transaction.accountId]?.takeIf { it.isNotEmpty() }?.let { line = L("%@ on %@", line, it) }
        if (transaction.needsCategory(offBudgetAccountIds)) line += " · " + L("Needs a category")
        return line
    }
}
