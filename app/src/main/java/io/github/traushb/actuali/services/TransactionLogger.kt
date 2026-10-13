package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.fetchTransaction
import io.github.traushb.actuali.services.database.mostRecentCategoryId
import io.github.traushb.actuali.services.sync.SyncClient
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private val logger = Logger("TransactionLogger")

/**
 * Headless transaction-write service for imports that skip the form:
 * normalizes the merchant string, matches-or-creates the payee, infers a
 * category from the most recent prior transaction at that payee, and writes
 * via [createTransaction]. Main-thread only.
 */
class TransactionLogger(private val store: BudgetStore) {
    sealed class LoggerError : Exception() {
        object NoBudgetLoaded : LoggerError()
        object TransactionAlreadyExists : LoggerError()
        object TransactionSuppressedByRule : LoggerError()
        object TransactionNeedsRecovery : LoggerError()

        override val message: String get() = message(Locale.getDefault())

        fun message(locale: Locale): String = when (this) {
            NoBudgetLoaded -> ReportStrings.text("Open Actuali and select a budget first.", locale)
            TransactionAlreadyExists -> ReportStrings.text("This transaction was already saved.", locale)
            TransactionSuppressedByRule -> ReportStrings.text("A transaction rule suppressed this transaction.", locale)
            TransactionNeedsRecovery -> ReportStrings.text("This import needs review before it can be approved.", locale)
        }
    }

    /**
     * The written row plus whether it reached the server. `synced == false`
     * means it is queued locally for the next successful sync (issue #139).
     */
    data class Result(val transaction: Transaction, val synced: Boolean)

    /**
     * @param amountCents signed (negative = outflow)
     * @param rawMerchant merchant text as it arrived (un-normalized)
     * @param categoryId a category pinned by the caller; wins over the payee
     *   auto-pick and over rules. Ignored if it no longer exists.
     */
    suspend fun logTransaction(
        accountId: String,
        amountCents: Long,
        rawMerchant: String,
        notes: String?,
        date: LocalDate,
        cleared: Boolean = true,
        categoryId: String? = null,
        financialId: String? = null,
        transactionId: String? = null,
    ): Result {
        val database = store.databaseForLogger ?: throw LoggerError.NoBudgetLoaded
        val payeeName = MerchantNormalizer.normalize(rawMerchant).ifEmpty { rawMerchant }
        val payee = store.findOrCreatePayee(payeeName)
        val pinnedCategoryId = store.existingCategoryId(categoryId)
        val resolvedCategoryId = pinnedCategoryId ?: database.mostRecentCategoryId(payee.id)

        val transaction = Transaction(
            id = transactionId ?: UUID.randomUUID().toString(), accountId = accountId, date = Transaction.yyyymmdd(date), amount = amountCents,
            payeeId = payee.id, payeeName = payee.name, categoryId = resolvedCategoryId, categoryName = null, notes = notes, cleared = cleared,
            reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false, sortOrder = null,
            // The un-normalized text, so rules like "imported_payee contains X" can match the original bank text.
            importedPayee = rawMerchant.ifEmpty { payeeName },
            financialId = financialId,
        )

        // A pinned category is a user choice, so rules may not replace it — the add form does the same for a picked category.
        val persistedTransactionId = try {
            when (val result = store.createTransaction(transaction, preserveCategory = pinnedCategoryId != null)) {
                is SyncClient.TransactionCreateResult.Inserted -> result.id
                SyncClient.TransactionCreateResult.Duplicate -> throw LoggerError.TransactionAlreadyExists
                SyncClient.TransactionCreateResult.SuppressedByRule -> throw LoggerError.TransactionSuppressedByRule
            }
        } catch (e: BudgetDatabase.TransactionWriteError.IncompleteFinancialIdMessages) {
            // The old HLC cells may already be on the server. Replacing them
            // would create a second CRDT history, so leave the row untouched
            // and keep the pending import available for explicit recovery.
            throw LoggerError.TransactionNeedsRecovery
        }
        store.recordPayeeLocationIfAppropriate(payee.id)
        // Writes push in the background so the UI never waits on the network,
        // but a headless entry point can be stopped the moment it returns —
        // wait for the push so the row reaches the server first.
        store.flushPendingSync()
        // The push can't report its own outcome: anything still queued means
        // the server wasn't reachable and the row lives only on this device.
        val synced = !store.hasPendingLocalWrites(Transaction.DATASET_NAME, persistedTransactionId)
        val persisted = database.fetchTransaction(persistedTransactionId) ?: throw LoggerError.NoBudgetLoaded
        logger.info("Logged transaction ${persisted.id} for ${payee.name}, synced: $synced")
        return Result(persisted, synced)
    }
}
