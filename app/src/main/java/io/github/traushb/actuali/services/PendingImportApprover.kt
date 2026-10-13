package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.PendingImport
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionType
import io.github.traushb.actuali.services.database.transactionExists
import io.github.traushb.actuali.services.sync.SyncClient
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val logger = Logger("PendingImportApprover")

/** Approves pending imports and writes them to the budget. Main-thread only. */
class PendingImportApprover(private val store: BudgetStore) {
    sealed class ApproveError : Exception() {
        object InvalidAmount : ApproveError()
        object NoAccountAvailable : ApproveError()
        object AccountClosed : ApproveError()
        object BudgetMismatch : ApproveError()
        object BudgetIdentityRequired : ApproveError()
        object SourceCurrencyRequired : ApproveError()
        data class SourceCurrencyMismatch(val source: String, val budget: String) : ApproveError()
        object ReviewConfirmationRequired : ApproveError()
        object SuppressedByRule : ApproveError()
        object NoBudgetLoaded : ApproveError()
        object TransactionNeedsRecovery : ApproveError()
        data class WriteFailed(val detail: String) : ApproveError()
        object AlreadyApproved : ApproveError()

        override val message: String get() = message(Locale.getDefault())

        fun message(locale: Locale): String = when (this) {
            InvalidAmount -> ReportStrings.text("Transaction amount is missing or invalid.", locale)
            NoAccountAvailable -> ReportStrings.text("No matching or default account available.", locale)
            AccountClosed -> ReportStrings.text("The target account is closed.", locale)
            BudgetMismatch -> ReportStrings.text("This import belongs to a different budget and cannot be approved here.", locale)
            BudgetIdentityRequired, SourceCurrencyRequired, TransactionNeedsRecovery ->
                ReportStrings.text("This import needs review before it can be approved.", locale)
            is SourceCurrencyMismatch -> ReportStrings.format(
                "This import is in %@, but the active budget uses %@. Review and confirm the transaction before saving.",
                source,
                budget,
                locale = locale,
            )
            ReviewConfirmationRequired -> ReportStrings.text("Review and confirm this import before saving it.", locale)
            SuppressedByRule -> ReportStrings.text("A transaction rule suppressed this import, so it remains pending.", locale)
            NoBudgetLoaded -> ReportStrings.text("Open Actuali and select a budget first.", locale)
            is WriteFailed -> ReportStrings.format("Failed to save transaction: %@", detail, locale = locale)
            AlreadyApproved -> ReportStrings.text("This pending import was already saved.", locale)
        }
    }

    sealed interface SaveResult {
        data class Inserted(val id: String) : SaveResult
        data object Duplicate : SaveResult
        data object SuppressedByRule : SaveResult
    }

    /** Logs the pending import to the budget via [TransactionLogger]. */
    suspend fun approve(item: PendingImport): TransactionLogger.Result {
        val amount = item.amount?.takeIf { it > 0 && it.isFinite() } ?: throw ApproveError.InvalidAmount
        val currentBudgetId = store.currentBudgetId ?: throw ApproveError.BudgetIdentityRequired
        val originBudgetId = item.originBudgetId ?: throw ApproveError.BudgetIdentityRequired
        if (originBudgetId != currentBudgetId) throw ApproveError.BudgetMismatch

        store.ensureBudgetReady()

        val sourceCurrency = PendingImport.normalizedCurrencyCode(item.sourceCurrencyCode ?: throw ApproveError.SourceCurrencyRequired)
        val budgetCurrency = PendingImport.normalizedCurrencyCode(store.currencyCode)
        if (sourceCurrency != budgetCurrency) throw ApproveError.SourceCurrencyMismatch(sourceCurrency, budgetCurrency)

        // The same account chain as the edit form.
        val accounts = store.accountsForIntent()
        val accountId = resolveAccountId(item.cardHint, accounts, store.cardAccountMappings, store.defaultAccountId) ?: throw ApproveError.NoAccountAvailable
        val account = accounts.firstOrNull { it.id == accountId } ?: throw ApproveError.NoAccountAvailable
        if (account.closed) throw ApproveError.AccountClosed

        val unsignedCents = Transaction.cents(amount) ?: throw ApproveError.InvalidAmount
        return try {
            TransactionLogger(store).logTransaction(
                accountId = account.id,
                amountCents = if (item.isIncome) unsignedCents else -unsignedCents,
                rawMerchant = item.payee ?: "Unknown",
                notes = null,
                date = Instant.ofEpochMilli(item.date).atZone(ZoneId.systemDefault()).toLocalDate(),
                // Uncleared until the user reconciles: parsed messages aren't
                // bank-confirmed. Matches upstream Actual, where manual entries
                // start uncleared and bank sync sets cleared from `booked`.
                cleared = false,
                financialId = financialId(item),
                transactionId = item.id,
            )
        } catch (e: TransactionLogger.LoggerError) {
            throw when (e) {
                TransactionLogger.LoggerError.TransactionAlreadyExists -> ApproveError.AlreadyApproved
                TransactionLogger.LoggerError.TransactionSuppressedByRule -> ApproveError.SuppressedByRule
                TransactionLogger.LoggerError.NoBudgetLoaded -> ApproveError.NoBudgetLoaded
                TransactionLogger.LoggerError.TransactionNeedsRecovery -> ApproveError.TransactionNeedsRecovery
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BudgetStoreError) {
            throw e
        } catch (e: Exception) {
            logger.error("Pending import log failed: ${e.localizedMessage}")
            throw ApproveError.WriteFailed(e.localizedMessage ?: e.toString())
        }
    }

    /**
     * Saves the transaction produced by the pending-import editor. The
     * deterministic financial id makes a retry after queue cleanup fails a
     * successful no-op instead of a second transaction.
     */
    suspend fun saveEdited(item: PendingImport, form: TransactionForm): SaveResult {
        val unsignedCents = form.amount.toDoubleOrNull()?.let(Transaction::cents)?.takeIf { it > 0 } ?: throw ApproveError.InvalidAmount
        val currentBudgetId = store.currentBudgetId ?: throw ApproveError.BudgetIdentityRequired
        val reviewRequirements = item.reviewRequirements(currentBudgetId, store.currencyCode)
        if (!form.reviewConfirmations.containsAll(reviewRequirements)) throw ApproveError.ReviewConfirmationRequired

        store.ensureBudgetReady()
        val accounts = store.accountsForIntent()
        val account = accounts.firstOrNull { it.id == form.accountId } ?: throw ApproveError.NoAccountAvailable
        if (account.closed) throw ApproveError.AccountClosed

        val financialId = financialId(item)
        val database = store.databaseForLogger ?: throw ApproveError.NoBudgetLoaded

        // Transfers and splits write several rows, so they go through the
        // store's form save. The import's id stamps the first row, which is
        // what a retry finds.
        if (form.type == TransactionType.Transfer || form.splits.isNotEmpty()) {
            if (form.type == TransactionType.Transfer) {
                val toAccountId = form.transferToAccountId ?: throw BudgetStoreError.MissingTransferDestination
                val toAccount = accounts.firstOrNull { it.id == toAccountId } ?: throw ApproveError.NoAccountAvailable
                if (toAccount.closed) throw ApproveError.AccountClosed
            }
            if (database.transactionExists(item.id, financialId)) return SaveResult.Duplicate
            store.saveTransaction(form, newId = item.id, financialId = financialId)
            return SaveResult.Inserted(item.id)
        }

        val payeeName = form.payeeName.trim()
        val payee = if (payeeName.isEmpty()) null else store.findOrCreatePayee(payeeName)
        val transaction = Transaction(
            id = item.id, accountId = account.id, date = Transaction.yyyymmdd(form.date),
            amount = if (form.type == TransactionType.Income) unsignedCents else -unsignedCents, payeeId = payee?.id, payeeName = payee?.name,
            categoryId = form.categoryId, categoryName = null, notes = form.notes.ifEmpty { null }, cleared = form.cleared, reconciled = false,
            transferId = null, isParent = false, parentId = null, tombstone = false, sortOrder = null,
            importedPayee = if (payeeName.isEmpty()) null else form.payeeName, financialId = financialId,
        )
        return when (val result = store.createTransaction(transaction)) {
            is SyncClient.TransactionCreateResult.Inserted -> SaveResult.Inserted(result.id)
            SyncClient.TransactionCreateResult.Duplicate -> SaveResult.Duplicate
            SyncClient.TransactionCreateResult.SuppressedByRule -> SaveResult.SuppressedByRule
        }
    }

    companion object {
        fun localizedErrorMessage(error: Throwable, locale: Locale): String = when (error) {
            is ApproveError -> error.message(locale)
            is PendingImportStore.StoreError.SaveFailed -> error.message(locale)
            is BudgetStoreError -> error.message(locale)
            else -> error.localizedMessage ?: error.toString()
        }

        /** An account for direct approval. Automatic approval is only allowed when the card mapping or explicit default is trustworthy. */
        fun resolveAccountId(cardHint: String?, accounts: List<Account>, cardMappings: Map<String, String>, defaultAccountId: String?): String? {
            cardHint?.takeIf { it.isNotEmpty() }?.let { hint -> BudgetStore.resolveAccountId(hint, accounts, cardMappings)?.let { return it } }
            return defaultAccountId?.takeIf { id -> accounts.any { it.id == id && !it.closed } }
        }

        /** Seeds the editor with a usable open account when strict automatic approval can't choose one; the user can change it. */
        fun seedAccountId(cardHint: String?, accounts: List<Account>, cardMappings: Map<String, String>, defaultAccountId: String?): String? =
            resolveAccountId(cardHint, accounts, cardMappings, defaultAccountId) ?: accounts.firstOrNull { !it.closed }?.id

        fun financialId(item: PendingImport): String = "actuali-pending-import:${item.id.lowercase()}"
    }
}
