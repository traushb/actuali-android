package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountBalanceBreakdown
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.PayeeLocation
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.PendingImportReviewRequirement
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionStatusFilter
import io.github.traushb.actuali.models.TransactionType
import io.github.traushb.actuali.services.database.BudgetDatabase.AccountsMonthSummary
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.CategoryWriteError
import io.github.traushb.actuali.services.database.LiveTransactionSnapshot
import io.github.traushb.actuali.services.database.balanceBreakdown
import io.github.traushb.actuali.services.database.clearedBalance
import io.github.traushb.actuali.services.database.fetchAccountSpend
import io.github.traushb.actuali.services.database.fetchAllLiveTransactions
import io.github.traushb.actuali.services.database.fetchCategoryTransactions
import io.github.traushb.actuali.services.database.fetchChildTransactions
import io.github.traushb.actuali.services.database.fetchClearedUnreconciledTransactions
import io.github.traushb.actuali.services.database.fetchPayeeLocations
import io.github.traushb.actuali.services.database.fetchRecentStatements
import io.github.traushb.actuali.services.database.fetchTransaction
import io.github.traushb.actuali.services.database.fetchTransactions
import io.github.traushb.actuali.services.database.fetchUncategorizedTransactions
import io.github.traushb.actuali.services.database.importedTransactionIds
import io.github.traushb.actuali.services.database.mostRecentCategoryId
import io.github.traushb.actuali.services.location.Coordinates
import io.github.traushb.actuali.services.location.LocationAuthStatus
import io.github.traushb.actuali.services.location.LocationProvider
import io.github.traushb.actuali.services.location.LocationUtils
import io.github.traushb.actuali.services.rules.RulesEngine
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.sync.SyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

private val logger = Logger("BudgetStore")

// Accounts

/**
 * Create a new local (manually-added) account, matching the PWA's "Create
 * local account" flow (loot-core `createAccount`): an accounts row, the
 * account's transfer payee, and — only for a nonzero balance, like the PWA —
 * an opening-balance transaction from the shared "Starting Balance" payee
 * (Actual has no stored-balance field; the balance is always the sum of its
 * transactions, so this transaction IS the starting balance).
 */
suspend fun BudgetStore.createAccount(name: String, offBudget: Boolean, startingBalanceCents: Long): Account {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw BudgetStoreError.InvalidAccountName
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured

    // New accounts sort after existing ones; a millisecond timestamp keeps
    // concurrent creates from colliding.
    val account = Account(UUID.randomUUID().toString(), trimmedName, AccountType.Checking, offBudget, false, System.currentTimeMillis(), startingBalanceCents)
    val transferPayee = Payee(UUID.randomUUID().toString(), "", account.id)

    try {
        var startingBalanceTransaction: Transaction? = null
        if (startingBalanceCents != 0L) {
            val startingBalancePayee = findOrCreatePayee("Starting Balance")
            // An on-budget opening balance is income the budget can allocate,
            // so it takes the income category the PWA picks. Off-budget money
            // never enters the budget, so no category.
            val category = if (offBudget) null else startingBalanceCategory()
            startingBalanceTransaction = Transaction(
                id = UUID.randomUUID().toString(), accountId = account.id, date = Transaction.yyyymmdd(LocalDate.now()),
                amount = startingBalanceCents, payeeId = startingBalancePayee.id, payeeName = startingBalancePayee.name,
                categoryId = category?.id, categoryName = category?.name, notes = null, cleared = true, reconciled = false,
                transferId = null, isParent = false, parentId = null, tombstone = false, sortOrder = null, importedPayee = null,
                startingBalanceFlag = true,
            )
        }
        syncClient.createAccount(account, transferPayee, startingBalanceTransaction)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BudgetStoreError) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.AccountCreationFailed(e.localizedMessage ?: e.toString())
    }
    // Refresh without recreating the sync client, so the new account appears immediately.
    refreshDataOnly()
    return account
}

/**
 * The category an on-budget opening balance lands in, mirroring the PWA's
 * `getStartingBalancePayee`: the income category named "Starting Balances",
 * else any income category, else null (uncategorized, same as upstream).
 */
internal fun BudgetStore.startingBalanceCategory(): Category? {
    val incomeCategories = categoryGroups.flatMap { it.categories }.filter { it.isIncome }
    return incomeCategories.firstOrNull { it.name.lowercase() == "starting balances" } ?: incomeCategories.firstOrNull()
}

/**
 * Create a category group, mirroring the web UI's "Add group": it lands after
 * every existing group and starts out empty; duplicate names are refused.
 */
suspend fun BudgetStore.createCategoryGroup(name: String): CategoryGroup {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw BudgetStoreError.InvalidCategoryGroupName
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val group = categoryWrite({ BudgetStoreError.CategoryGroupCreationFailed(it) }) { syncClient.createCategoryGroup(UUID.randomUUID().toString(), trimmedName) }
    refreshDataOnly()
    return group
}

/**
 * Create a category at the top of [groupId], where the web UI puts it. It
 * inherits the group's income and hidden flags.
 */
suspend fun BudgetStore.createCategory(name: String, groupId: String): Category {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw BudgetStoreError.InvalidCategoryName
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val category = categoryWrite({ BudgetStoreError.CategoryCreationFailed(it) }) { syncClient.createCategory(UUID.randomUUID().toString(), trimmedName, groupId) }
    refreshDataOnly()
    return category
}

/** Runs a category write; [CategoryWriteError]s are already phrased for the person, anything else gets [wrap]ped. */
private suspend fun <T> categoryWrite(wrap: (String) -> BudgetStoreError, write: suspend () -> T): T = try {
    write()
} catch (e: CancellationException) {
    throw e
} catch (e: CategoryWriteError) {
    throw e
} catch (e: Exception) {
    throw wrap(e.localizedMessage ?: e.toString())
}

/**
 * Rename a category without changing its group, sort order, budget, or
 * transactions. [month] is the month the caller displays: the shared refresh
 * republishes the current calendar month, so a caller browsing another month
 * has it restored.
 */
suspend fun BudgetStore.renameCategory(id: String, name: String, month: String) {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw BudgetStoreError.InvalidCategoryName
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    categoryWrite({ BudgetStoreError.CategoryUpdateFailed(it) }) { syncClient.renameCategory(id, trimmedName) }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

/** Move a category into a group, before [targetId] (last when null); [month] is restored afterwards. */
suspend fun BudgetStore.moveCategory(id: String, groupId: String, targetId: String?, month: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    categoryWrite({ BudgetStoreError.CategoryUpdateFailed(it) }) { syncClient.moveCategory(id, groupId, targetId) }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

/** Move a category group before [targetId] (last when null); [month] is restored afterwards. */
suspend fun BudgetStore.moveCategoryGroup(id: String, targetId: String?, month: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    categoryWrite({ BudgetStoreError.CategoryUpdateFailed(it) }) { syncClient.moveCategoryGroup(id, targetId) }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

suspend fun BudgetStore.renameCategoryGroup(id: String, name: String, month: String) {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) throw BudgetStoreError.InvalidCategoryGroupName
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    categoryWrite({ BudgetStoreError.CategoryGroupUpdateFailed(it) }) { syncClient.renameCategoryGroup(id, trimmedName) }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

suspend fun BudgetStore.setCategoryHidden(id: String, hidden: Boolean, month: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    try {
        syncClient.setCategoryHidden(id, hidden)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.CategoryUpdateFailed(e.localizedMessage ?: e.toString())
    }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

suspend fun BudgetStore.setCategoryGroupHidden(id: String, hidden: Boolean, month: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    try {
        syncClient.setCategoryGroupHidden(id, hidden)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.CategoryUpdateFailed(e.localizedMessage ?: e.toString())
    }
    refreshDataOnly()
    fetchBudgetMonth(month)
}

/**
 * Money in and out across every account for one "yyyy-MM" month (GH #256).
 * null when there's no budget open or the query failed, so the card keeps its
 * last figures rather than flashing zeroes.
 */
suspend fun BudgetStore.fetchAccountsMonthSummary(month: String): AccountsMonthSummary? = try {
    database?.fetchAccountsMonthSummary(month, excludedFromSpentCategoryIds)
} catch (e: CancellationException) {
    null
} catch (e: Exception) {
    error = e.localizedMessage
    null
}

// Transactions

/** One page of transactions (newest first), optionally scoped to an account and/or filtered by free-text search. */
suspend fun BudgetStore.fetchTransactions(
    accountId: String? = null,
    limit: Int = BudgetDatabase.TRANSACTION_PAGE_SIZE,
    offset: Int = 0,
    search: String? = null,
    statusFilter: TransactionStatusFilter = TransactionStatusFilter.All,
): List<Transaction> = try {
    database?.fetchTransactions(accountId = accountId, limit = limit, offset = offset, search = search, statusFilter = statusFilter).orEmpty()
} catch (e: CancellationException) {
    // A superseded search reload. Nothing failed — never alarm the user.
    emptyList()
} catch (e: Exception) {
    error = e.localizedMessage
    emptyList()
}

/** Every transaction counting toward a category's spend, optionally narrowed to one "yyyy-MM" month. */
suspend fun BudgetStore.fetchCategoryTransactions(categoryId: String, month: String? = null): List<Transaction> = try {
    database?.fetchCategoryTransactions(categoryId, month).orEmpty()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    error = e.localizedMessage
    emptyList()
}

/** All transactions still needing a category. */
suspend fun BudgetStore.fetchUncategorizedTransactions(): List<Transaction> = try {
    database?.fetchUncategorizedTransactions().orEmpty()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    error = e.localizedMessage
    emptyList()
}

/** Create a new transaction (optimistic local-first). */
suspend fun BudgetStore.createTransaction(
    transaction: Transaction,
    preserveCategory: Boolean = false,
    showImpact: Boolean = false,
): SyncClient.TransactionCreateResult {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    // Use the same rules snapshot for the cue and the write: rules can change
    // the category, account and date before insertion.
    val prepared = if (showImpact && showTransactionImpactCue && !hideBalances) syncClient.prepareRules() else null
    var targets = emptySet<TransactionImpactTarget>()
    if (prepared != null) {
        var final = RulesEngine.apply(transaction, prepared.rules, prepared.context).transaction
        if (preserveCategory && transaction.categoryId != null) final = final.copy(categoryId = transaction.categoryId)
        targets = impactTargets(listOf(final))
    }
    return withImpactCue(targets) {
        val result = syncClient.createTransaction(transaction, applyRules = true, prepared = prepared, preserveCategory = preserveCategory)
        // Publish the persisted row before the full refresh so local observers
        // such as History see the transaction immediately.
        publishTransactionsImmediately(listOf(transaction.id))
        refreshDataOnly()
        result
    }
}

/**
 * Create a paired transfer between two accounts: both legs with linked
 * `transferId`s, each using the other account's transfer payee. [categoryId]
 * lands on whichever leg may carry one — Actual allows a category only on an
 * on-budget leg whose partner account is off-budget — and is dropped
 * otherwise. That is the shape a loan payment takes.
 */
suspend fun BudgetStore.createTransfer(
    fromAccountId: String,
    toAccountId: String,
    amountCents: Long,
    date: Int,
    notes: String?,
    cleared: Boolean,
    categoryId: String? = null,
    sourceId: String? = null,
    financialId: String? = null,
) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val (fromTransferPayee, toTransferPayee) = transferPayees(fromAccountId, toAccountId, amountCents)
    val resolvedSourceId = sourceId ?: UUID.randomUUID().toString()
    val targetId = UUID.randomUUID().toString()
    val offBudgetIds = offBudgetAccountIds
    fun categorizable(accountId: String, partner: String) = categoryId.takeIf { transferLegTakesCategory(accountId, partner, offBudgetIds) }

    val source = Transaction(
        id = resolvedSourceId, accountId = fromAccountId, date = date, amount = -amountCents, payeeId = toTransferPayee.id,
        payeeName = toTransferPayee.name, categoryId = categorizable(fromAccountId, toAccountId), categoryName = null, notes = notes,
        cleared = cleared, reconciled = false, transferId = targetId, isParent = false, parentId = null, tombstone = false,
        sortOrder = null, importedPayee = null, financialId = financialId,
    )
    val target = Transaction(
        id = targetId, accountId = toAccountId, date = date, amount = amountCents, payeeId = fromTransferPayee.id,
        payeeName = fromTransferPayee.name, categoryId = categorizable(toAccountId, fromAccountId), categoryName = null, notes = notes,
        cleared = cleared, reconciled = false, transferId = resolvedSourceId, isParent = false, parentId = null, tombstone = false,
        sortOrder = null, importedPayee = null,
    )
    syncClient.createTransfer(source, target)
    publishTransactionsImmediately(listOf(resolvedSourceId, targetId))
    refreshDataOnly()
}

/**
 * Everything that can refuse a [createTransfer], checked without writing
 * anything — so a caller posting other rows alongside one can fail first.
 */
fun BudgetStore.transferPayees(fromAccountId: String, toAccountId: String, amountCents: Long): Pair<Payee, Payee> {
    if (syncClient == null) throw BudgetStoreError.SyncNotConfigured
    if (fromAccountId == toAccountId) throw BudgetStoreError.TransferAccountsMatch
    if (amountCents <= 0) throw BudgetStoreError.TransferAmountNotPositive
    val from = transferPayee(fromAccountId) ?: throw BudgetStoreError.TransferPayeeMissing
    val to = transferPayee(toAccountId) ?: throw BudgetStoreError.TransferPayeeMissing
    return from to to
}

internal fun BudgetStore.transferPayee(accountId: String): Payee? = payees.firstOrNull { it.transferAccountId == accountId && !it.tombstone }

/**
 * Actual's rule: a transfer leg carries a category only when its account is
 * on-budget and its partner's is off-budget — money entering or leaving the
 * budget (`clearCategory` in loot-core's transfer.ts). A missing account
 * (partner not picked yet) takes none.
 */
fun transferLegTakesCategory(leg: String?, partner: String?, offBudgetAccountIds: Set<String>): Boolean =
    leg != null && partner != null && leg !in offBudgetAccountIds && partner in offBudgetAccountIds

/**
 * Re-save an existing transfer: both legs take the new accounts, amount, date,
 * notes and cleared state, with payees remapped to the (possibly re-targeted)
 * accounts' transfer payees. [original] is whichever leg the user opened. A
 * category survives only on an on-budget leg whose partner is off-budget.
 */
suspend fun BudgetStore.updateTransfer(
    original: Transaction,
    fromAccountId: String,
    toAccountId: String,
    amountCents: Long,
    date: Int,
    notes: String?,
    cleared: Boolean,
    categoryId: String?,
) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    if (fromAccountId == toAccountId) throw BudgetStoreError.TransferAccountsMatch
    if (amountCents <= 0) throw BudgetStoreError.TransferAmountNotPositive
    val partnerId = original.transferId ?: throw BudgetStoreError.TransferPartnerMissing
    val partner = database.fetchTransaction(partnerId) ?: throw BudgetStoreError.TransferPartnerMissing
    val fromTransferPayee = transferPayee(fromAccountId) ?: throw BudgetStoreError.TransferPayeeMissing
    val toTransferPayee = transferPayee(toAccountId) ?: throw BudgetStoreError.TransferPayeeMissing

    val offBudgetIds = offBudgetAccountIds
    // The edited leg takes the form's category, the partner keeps its own —
    // then both are cleared unless that leg is the categorizable side.
    fun resolvedCategory(leg: Transaction, accountId: String, otherAccountId: String): String? {
        if (!transferLegTakesCategory(accountId, otherAccountId, offBudgetIds)) return null
        return if (leg.id == original.id) categoryId else leg.categoryId
    }

    // The opened row can be either leg; the negative one is the source.
    val (sourceLeg, targetLeg) = if (original.amount < 0) original to partner else partner to original
    val source = sourceLeg.copy(
        accountId = fromAccountId, amount = -amountCents, payeeId = toTransferPayee.id,
        categoryId = resolvedCategory(sourceLeg, fromAccountId, toAccountId), date = date, notes = notes, cleared = cleared,
    )
    val target = targetLeg.copy(
        accountId = toAccountId, amount = amountCents, payeeId = fromTransferPayee.id,
        categoryId = resolvedCategory(targetLeg, toAccountId, fromAccountId), date = date, notes = notes, cleared = cleared,
    )
    changedFields(sourceLeg, source).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(source, it) }
    changedFields(targetLeg, target).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(target, it) }
    refreshDataOnly()
}

/** Update an existing transaction (optimistic local-first). */
suspend fun BudgetStore.updateTransaction(updated: Transaction, original: Transaction) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.updateTransaction(updated, changedFields(original, updated))
    refreshDataOnly()
}

/** Restore one transaction row to its recorded earlier state; only fields that actually differ are written. */
suspend fun BudgetStore.restoreTransaction(transaction: Transaction, recordedAfter: Transaction) = updateTransaction(transaction, recordedAfter)

/**
 * Update several transaction rows as one write per distinct changed-field set:
 * a row whose amount didn't change must not have `amount` rewritten just
 * because another row in the batch changed its amount — that would stamp a
 * fresh HLC timestamp on an unchanged value and could clobber a concurrent
 * edit from another device.
 */
private suspend fun BudgetStore.updateTransactions(transactions: List<Transaction>, originals: List<Transaction>) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val batches = LinkedHashMap<Set<String>, MutableList<Transaction>>()
    for ((updated, original) in transactions.zip(originals)) {
        val fields = changedFields(original, updated)
        if (fields.isNotEmpty()) batches.getOrPut(fields) { mutableListOf() } += updated
    }
    if (batches.isEmpty()) return
    for ((fields, rows) in batches) syncClient.updateTransactions(rows, fields)
    refreshDataOnly()
}

/**
 * Publish rows that have just been committed before the normal refresh.
 * History observes `transactions`, so this keeps every creation shape
 * consistent without changing the database's authoritative read path.
 */
internal suspend fun BudgetStore.publishTransactionsImmediately(ids: List<String>) {
    val database = database ?: return
    for (id in ids) {
        val saved = runCatching { database.fetchTransaction(id) }.getOrNull() ?: continue
        transactions = transactions.filter { it.id != saved.id } + saved
    }
}

/**
 * Children share their parent's account, date and cleared state; keep them
 * aligned after a parent edit (reports read the children, so a stale child
 * date would misfile them). A payee change follows Actual's rule: children
 * whose payee matched the parent's old payee follow it; overrides keep theirs.
 */
private suspend fun BudgetStore.cascadeSharedFieldsToChildren(parent: Transaction, originalPayeeId: String?) {
    val database = database ?: return
    val children = database.fetchChildTransactions(parent.id)
    val updated = children.map { child ->
        child.copy(
            accountId = parent.accountId, date = parent.date, cleared = parent.cleared,
            payeeId = if (child.payeeId == originalPayeeId) parent.payeeId else child.payeeId,
        )
    }
    updateTransactions(updated, children)
}

/** Split children of a parent, for the edit sheet's editable split lines; failures collapse to an empty list. */
suspend fun BudgetStore.fetchSplitChildren(parentId: String): List<Transaction> =
    runCatching { database?.fetchChildTransactions(parentId) }.getOrNull().orEmpty()

/**
 * Every live transaction, for History: `transactions` holds only the newest
 * page. [remoteChangesAfter] also reports the rows other devices wrote since
 * that messages_crdt id.
 */
suspend fun BudgetStore.fetchAllLiveTransactions(remoteChangesAfter: Long? = null): LiveTransactionSnapshot {
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    return database.fetchAllLiveTransactions(remoteChangesAfter, syncClient?.nodeId)
}

/** Soft-delete a transaction (tombstone, CRDT-compatible); failures surface through [BudgetStore.error]. */
suspend fun BudgetStore.deleteTransaction(transaction: Transaction) = deleteTransactions(listOf(transaction))

/** Bulk soft-delete in one batch write — one merkle/clock save and one sync for the whole selection. */
suspend fun BudgetStore.deleteTransactions(transactions: List<Transaction>) {
    withImpactCue(transactions) { performDeleteTransactions(transactions) }
}

private suspend fun BudgetStore.performDeleteTransactions(transactions: List<Transaction>) {
    val syncClient = syncClient ?: run {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    val database = database
    val deleted = mutableListOf<Transaction>()
    for (tx in transactions) {
        // Deleting a split deletes its children too — orphaned children would
        // be invisible in the list but still feed reports.
        if (tx.isParent && database != null) {
            try {
                for (child in database.fetchChildTransactions(tx.id)) {
                    deleted += child.copy(tombstone = true)
                    child.transferId?.let { database.fetchTransaction(it) }?.let { deleted += it.copy(tombstone = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Skip the parent when its children couldn't be read —
                // tombstoning it anyway would orphan them.
                error = L("Failed to delete transaction: %@", e.localizedMessage)
                continue
            }
        }
        deleted += tx.copy(tombstone = true)
    }
    try {
        syncClient.updateTransactions(deleted, setOf("tombstone"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to delete transaction: %@", e.localizedMessage)
    }
    refreshDataOnly()
}

/** Duplicate a transaction (and its split children if a parent, or paired transfer if a transfer). */
suspend fun BudgetStore.duplicateTransaction(transaction: Transaction) = duplicateTransactions(listOf(transaction))

suspend fun BudgetStore.duplicateTransactions(transactions: List<Transaction>) {
    withImpactCue(transactions) { performDuplicateTransactions(transactions) }
}

private suspend fun BudgetStore.performDuplicateTransactions(transactions: List<Transaction>) {
    if (syncClient == null) {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    val baseSortOrder = System.currentTimeMillis().toDouble()
    val handledTransferIds = HashSet<String>()
    for ((index, tx) in transactions.withIndex()) {
        // The partner leg may already have been copied as part of its pair —
        // test this first: a half-linked leg carries no transferId of its own.
        if (tx.id in handledTransferIds) continue
        tx.transferId?.takeIf { it.isNotEmpty() }?.let { handledTransferIds += it }
        try {
            duplicateSingleTransaction(tx, baseSortOrder + index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = L("Failed to duplicate transaction: %@", e.localizedMessage)
        }
    }
    refreshDataOnly()
}

private fun makeDuplicateTransaction(source: Transaction, id: String = UUID.randomUUID().toString(), sortOrder: Double): Transaction = Transaction(
    id = id, accountId = source.accountId, date = source.date, amount = source.amount, payeeId = source.payeeId,
    payeeName = source.payeeName, categoryId = source.categoryId, categoryName = source.categoryName, notes = source.notes,
    cleared = false, reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false,
    sortOrder = sortOrder, importedPayee = source.importedPayee, schedule = null,
)

private suspend fun BudgetStore.duplicateSingleTransaction(transaction: Transaction, sortOrder: Double) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val database = database

    // Transfers: duplicate both legs. A missing partner falls through to the
    // standard branch; a read error must surface, not silently degrade.
    val transferId = transaction.transferId
    if (!transferId.isNullOrEmpty() && database != null) {
        val partner = database.fetchTransaction(transferId)
        if (partner != null) {
            val newSourceId = UUID.randomUUID().toString()
            val newTargetId = UUID.randomUUID().toString()
            syncClient.createTransfer(
                makeDuplicateTransaction(transaction, newSourceId, sortOrder).copy(transferId = newTargetId),
                makeDuplicateTransaction(partner, newTargetId, sortOrder).copy(transferId = newSourceId),
            )
            return
        }
    }

    // Split parent
    if (transaction.isParent && database != null) {
        val newParentId = UUID.randomUUID().toString()
        val newChildren = database.fetchChildTransactions(transaction.id).mapIndexed { index, child ->
            // Fractional offsets keep children just below their parent without
            // landing on the integer slots bulk duplication hands out.
            var newChild = makeDuplicateTransaction(child, sortOrder = sortOrder - (index + 1) * 0.001).copy(parentId = newParentId)
            // A transfer-leg child loses its partner in the copy, so it can't
            // keep the transfer payee either.
            if (child.transferAcct != null) newChild = newChild.copy(payeeId = null, payeeName = null)
            newChild
        }
        syncClient.createSplit(makeDuplicateTransaction(transaction, newParentId, sortOrder).copy(isParent = true), newChildren)
        return
    }

    // Standard: a row with a transfer payee but no partner leg can't keep that payee on the copy.
    var newTx = makeDuplicateTransaction(transaction, sortOrder = sortOrder)
    if (transaction.transferAcct != null) newTx = newTx.copy(payeeId = null, payeeName = null)
    // Rules are skipped — every field of the copy comes from the source row.
    syncClient.createTransaction(newTx, applyRules = false)
}

/**
 * Bulk update the cleared status in one batch write. Reconciled rows are
 * locked: bulk edits leave them alone. Split children follow their parent's
 * cleared state, in the same write.
 */
suspend fun BudgetStore.setClearedStatus(transactions: List<Transaction>, cleared: Boolean) {
    val syncClient = syncClient ?: run {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    val database = database
    val updated = mutableListOf<Transaction>()
    for (tx in transactions) {
        if (tx.reconciled || tx.cleared == cleared) continue
        val copy = tx.copy(cleared = cleared)
        if (!tx.isParent || database == null) {
            updated += copy
            continue
        }
        try {
            // Reconciled children are locked for the same reason as their parents.
            updated += listOf(copy) + database.fetchChildTransactions(tx.id).filter { !it.reconciled && it.cleared != cleared }.map { it.copy(cleared = cleared) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A parent that flips without its children leaves the split inconsistent.
            error = L("Failed to update cleared status: %@", e.localizedMessage)
        }
    }
    // The reconciled lock is silent otherwise: say which part of the selection stayed put.
    val locked = transactions.count { it.reconciled && it.cleared != cleared }
    if (locked > 0) error = lockedReconciledMessage(locked)
    if (updated.isEmpty()) return
    try {
        syncClient.updateTransactions(updated, setOf("cleared"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to update cleared status: %@", e.localizedMessage)
    }
    refreshDataOnly()
}

/**
 * Set (or clear) the category of several transactions as one write.
 * Reconciled rows stay locked, and transfers and split parents take no
 * category; how many were skipped is reported through [BudgetStore.error].
 */
suspend fun BudgetStore.setCategory(categoryId: String?, transactions: List<Transaction>) {
    val syncClient = syncClient ?: run {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    val name = categoryGroups.flatMap { it.categories }.firstOrNull { it.id == categoryId }?.name
    val updated = mutableListOf<Transaction>()
    var locked = 0
    var unsupported = 0
    for (tx in transactions) {
        if (tx.categoryId == categoryId) continue
        when {
            tx.reconciled -> locked += 1
            tx.isParent || tx.transferId != null || tx.transferAcct != null -> unsupported += 1
            else -> updated += tx.copy(categoryId = categoryId, categoryName = name)
        }
    }
    reportSkipped(locked, unsupported)
    if (updated.isEmpty()) return
    try {
        syncClient.updateTransactions(updated, setOf("category"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to categorize transaction: %@", e.localizedMessage)
    }
    refreshDataOnly()
}

/** Add a `#tag` to the note of several transactions as one write; reconciled rows stay locked. */
suspend fun BudgetStore.addTag(tag: String, transactions: List<Transaction>) {
    val syncClient = syncClient ?: run {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    if (!Tag.isValidTagName(tag)) return
    val updated = mutableListOf<Transaction>()
    var locked = 0
    for (tx in transactions) {
        val notes = TransactionBulkEdit.notesAdding(tag, tx.notes) ?: continue
        if (tx.reconciled) locked += 1 else updated += tx.copy(notes = notes)
    }
    reportSkipped(locked, 0)
    if (updated.isEmpty()) return
    try {
        syncClient.updateTransactions(updated, setOf("notes"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to add tag: %@", e.localizedMessage)
    }
    refreshDataOnly()
}

/**
 * Merge two transactions the way Actual does: the bank-imported one (else the
 * earlier one) is kept and fills in its blank payee, category and notes from
 * the other, which is then deleted. A split on the dropped transaction moves
 * to the kept one when that has none. All of it is one atomic write.
 */
suspend fun BudgetStore.mergeTransactions(first: Transaction, second: Transaction) {
    val syncClient = syncClient
    val database = database
    if (syncClient == null || database == null) {
        error = BudgetStoreError.SyncNotConfigured.message
        return
    }
    try {
        // Selection values can predate a sync or another local edit.
        val freshFirst = database.fetchTransaction(first.id)
        val freshSecond = database.fetchTransaction(second.id)
        if (freshFirst == null || freshSecond == null || !TransactionBulkEdit.canMerge(freshFirst, freshSecond)) {
            error = L("Only two transactions in the same account with the same amount can be merged.")
            return
        }
        val imported = database.importedTransactionIds(listOf(freshFirst.id, freshSecond.id))
        val (keep, drop) = TransactionBulkEdit.keepAndDrop(freshFirst, freshSecond, imported)
        val keepChildren = if (keep.isParent) database.fetchChildTransactions(keep.id) else emptyList()
        val dropChildren = if (drop.isParent) database.fetchChildTransactions(drop.id) else emptyList()

        var merged = TransactionBulkEdit.merged(keep, drop)
        val updates = mutableListOf<Pair<Transaction, Set<String>>>()
        val deleted = mutableListOf(drop)
        if (keepChildren.isEmpty() && dropChildren.isNotEmpty()) {
            // The split moves over; the kept row becomes its parent.
            merged = merged.copy(isParent = true, categoryId = null, categoryName = null)
            dropChildren.mapTo(updates) { it.copy(parentId = keep.id) to setOf("parent_id") }
        } else {
            for (child in dropChildren) {
                deleted += child
                // Split transfers must lose both legs, as in deleteTransactions.
                child.transferId?.let { database.fetchTransaction(it) }?.let { deleted += it }
            }
        }
        val keepFields = changedFields(keep, merged).toMutableSet()
        if (keep.schedule != merged.schedule) keepFields += "schedule"
        if (keepFields.isNotEmpty()) updates.add(0, merged to keepFields)
        deleted.mapTo(updates) { it.copy(tombstone = true) to setOf("tombstone") }
        syncClient.updateTransactions(updates)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to merge transactions: %@", e.localizedMessage)
    }
    refreshDataOnly()
}

/** Tell the user which part of a batch edit stayed as it was. */
private fun BudgetStore.reportSkipped(locked: Int, unsupported: Int) {
    val messages = mutableListOf<String>()
    if (locked > 0) messages += lockedReconciledMessage(locked)
    if (unsupported > 0) messages += skippedTransfersAndSplitsMessage(unsupported)
    if (messages.isNotEmpty()) error = messages.joinToString("\n")
}

fun skippedTransfersAndSplitsMessage(count: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    ReportStrings.localized("%lld transfer or split transaction was left as it is.", count.toLong(), locale = locale)

fun lockedReconciledMessage(count: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    ReportStrings.localized("%lld reconciled transaction stayed locked. Unlock from the status dot to change it.", count.toLong(), locale = locale)

// Reconciliation

/**
 * Toggle a transaction's cleared status from the list's status dot. A
 * reconciled (locked) transaction unlocks instead — callers confirm that
 * first — and keeps its cleared flag, matching Actual's desktop behavior.
 */
suspend fun BudgetStore.toggleCleared(transaction: Transaction) {
    try {
        val updated = if (transaction.reconciled) transaction.copy(reconciled = false) else transaction.copy(cleared = !transaction.cleared)
        updateTransaction(updated, transaction)
        if (updated.isParent) cascadeSharedFieldsToChildren(updated, transaction.payeeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to update cleared status: %@", e.localizedMessage)
    }
}

/** Cleared balance for one account — the figure reconciliation compares against the bank. */
suspend fun BudgetStore.clearedBalance(accountId: String): Long? {
    val database = database ?: return null
    return try {
        database.clearedBalance(accountId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.localizedMessage
        null
    }
}

/**
 * Cleared / uncleared / reconciled totals for the account-detail breakdown
 * (GH #134); omitted silently on failure, as it is purely informational.
 */
suspend fun BudgetStore.balanceBreakdown(accountId: String): AccountBalanceBreakdown? = runCatching { database?.balanceBreakdown(accountId) }.getOrNull()

/** Total charges in cents for an account within a billing cycle window. */
suspend fun BudgetStore.fetchCycleSpend(accountId: String, start: DayDate, end: DayDate): Long =
    runCatching { database?.fetchAccountSpend(accountId, start.yyyymmdd, end.yyyymmdd) }.getOrNull() ?: 0

/** Closed statements for a credit card account (up to 3), filtered to those with recorded data. */
suspend fun BudgetStore.fetchRecentStatements(accountId: String): List<CreditCardCycle.StatementRecord> {
    val database = database ?: return emptyList()
    val cycle = activeCreditCardCycle(accountId) ?: return emptyList()
    val account = accounts.firstOrNull { it.id == accountId } ?: return emptyList()
    return runCatching { database.fetchRecentStatements(accountId, cycle.recentStatementCycles(), account.balance) }.getOrDefault(emptyList())
}

/** Transactions within a credit card statement's date range [startDate, endDate]. */
suspend fun BudgetStore.fetchStatementTransactions(accountId: String, startDate: Int, endDate: Int): List<Transaction> =
    runCatching { database?.fetchTransactions(accountId = accountId, startDate = startDate, endDate = endDate, limit = Int.MAX_VALUE) }.getOrNull().orEmpty()

/**
 * Finish reconciling: lock every cleared, not-yet-reconciled transaction in
 * the account, like upstream's lockTransactions. Returns how many rows were
 * locked; 0 with [BudgetStore.error] set on failure.
 */
suspend fun BudgetStore.lockClearedTransactions(accountId: String): Int = try {
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val locked = database.fetchClearedUnreconciledTransactions(accountId).map { it.copy(reconciled = true) }
    syncClient.updateTransactions(locked, setOf("reconciled"))
    refreshDataOnly()
    locked.size
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    error = L("Failed to lock transactions: %@", e.localizedMessage)
    0
}

/**
 * Create the balance-adjustment transaction reconciliation offers when the
 * cleared balance doesn't match the bank: a cleared, uncategorized entry for
 * the difference, the same shape as upstream's.
 */
suspend fun BudgetStore.createReconciliationAdjustment(accountId: String, amountCents: Long): Boolean = try {
    createTransaction(
        Transaction(
            id = UUID.randomUUID().toString(), accountId = accountId, date = Transaction.yyyymmdd(LocalDate.now()), amount = amountCents,
            payeeId = null, payeeName = null, categoryId = null, categoryName = null, notes = "Reconciliation balance adjustment",
            cleared = true, reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false,
            sortOrder = System.currentTimeMillis().toDouble(), importedPayee = null,
        ),
    )
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    error = L("Failed to create adjustment: %@", e.localizedMessage)
    false
}

// Transaction Form

data class AutomaticCategoryPreview(val sourceCategoryId: String?, val resultCategoryId: String?)

/**
 * Input gathered by the add/edit transaction form. [amount] is the raw field
 * text, always unsigned — [type] determines the sign and whether the save is a
 * transfer.
 */
data class TransactionForm(
    val accountId: String,
    val type: TransactionType,
    val amount: String,
    val payeeName: String,
    val transferToAccountId: String?,
    val categoryId: String?,
    val notes: String,
    val date: LocalDate,
    val cleared: Boolean,
    val splits: List<SplitLineForm> = emptyList(),
    /**
     * The edit form's "Remove Split": collapse an existing split parent into
     * a single transaction. Only meaningful when editing a parent.
     */
    val collapseSplit: Boolean = false,
    /** Per-save opt-out for payee location recording (GH #24). */
    val recordLocation: Boolean = true,
    val reviewConfirmations: Set<PendingImportReviewRequirement> = emptySet(),
    /** True for a picker choice or prefill; false for payee-history suggestions. */
    val categoryIsExplicit: Boolean = false,
    val automaticCategoryPreview: AutomaticCategoryPreview? = null,
)

/**
 * One line of a split entered in the form. [amount] is raw field text,
 * unsigned like [TransactionForm.amount]; [isOpposite] runs the line against
 * the transaction's direction — a refund inside a spend split (GH #216). An
 * empty [payeeName] means the line inherits the transaction's payee (Actual's
 * makeChild rule). [childId] links the line to an existing child row when
 * editing a split parent; null means the line is new.
 */
data class SplitLineForm(
    val id: String = UUID.randomUUID().toString(),
    val childId: String? = null,
    val categoryId: String? = null,
    val amount: String = "",
    val isOpposite: Boolean = false,
    val notes: String = "",
    val payeeName: String = "",
    val payeeId: String? = null,
)

/** A validated split line: signed cents, ready to become a child row. A null [payeeName] inherits the parent's payee. */
data class SplitPlanLine(
    val categoryId: String?,
    val amountCents: Long,
    val notes: String?,
    val payeeName: String?,
    val payeeId: String?,
    val childId: String?,
)

/** The store-side action a form resolves to. Validation and routing are pure so they can be tested without a sync client. */
sealed interface TransactionFormPlan {
    data class Transfer(val toAccountId: String, val amountCents: Long) : TransactionFormPlan
    data class Standard(val amountCents: Long) : TransactionFormPlan
    data class Split(val amountCents: Long, val lines: List<SplitPlanLine>) : TransactionFormPlan

    companion object {
        fun of(form: TransactionForm): TransactionFormPlan {
            val unsignedCents = form.amount.toDoubleOrNull()?.let(Transaction::cents) ?: throw BudgetStoreError.InvalidAmount
            return when (form.type) {
                TransactionType.Transfer -> Transfer(form.transferToAccountId ?: throw BudgetStoreError.MissingTransferDestination, unsignedCents)
                TransactionType.Expense -> standardOrSplit(form, -unsignedCents, -1)
                TransactionType.Income -> standardOrSplit(form, unsignedCents, 1)
            }
        }

        /**
         * Resolve an expense/income form to [Standard], or [Split] when split
         * lines are present: every line must parse to a positive amount and
         * the lines must add up exactly to the total.
         */
        private fun standardOrSplit(form: TransactionForm, amountCents: Long, sign: Int): TransactionFormPlan {
            if (form.splits.isEmpty()) return Standard(amountCents)
            if (form.splits.size < 2) throw BudgetStoreError.SplitNeedsTwoLines
            val lines = form.splits.map { line ->
                val cents = line.amount.toDoubleOrNull()?.let(Transaction::cents)?.takeIf { it > 0 } ?: throw BudgetStoreError.InvalidAmount
                val payeeName = line.payeeName.trim()
                SplitPlanLine(
                    categoryId = line.categoryId,
                    amountCents = sign * (if (line.isOpposite) -cents else cents),
                    notes = line.notes.ifEmpty { null },
                    payeeName = payeeName.ifEmpty { null },
                    payeeId = line.payeeId,
                    childId = line.childId,
                )
            }
            if (lines.sumOf { it.amountCents } != amountCents) throw BudgetStoreError.SplitAmountMismatch
            return Split(amountCents, lines)
        }
    }
}

/**
 * Category the add form should show before the user makes an explicit choice:
 * payee history first, then the same rules pass used on save.
 */
suspend fun BudgetStore.automaticCategoryPreview(form: TransactionForm, applyRules: Boolean = true): AutomaticCategoryPreview {
    if (form.type == TransactionType.Transfer || form.splits.isNotEmpty() || form.accountId in offBudgetAccountIds) {
        return AutomaticCategoryPreview(null, null)
    }
    val trimmedPayee = form.payeeName.trim()
    val payeeId = payees.firstOrNull { !it.tombstone && it.transferAccountId == null && it.name.equals(trimmedPayee, ignoreCase = true) }?.id
    val historyCategoryId = payeeId?.let { id -> database?.mostRecentCategoryId(id) }
    val unsignedCents = form.amount.toDoubleOrNull()?.let(Transaction::cents) ?: 0
    val payeeName = trimmedPayee.ifEmpty { null }
    val transaction = Transaction(
        id = "category-preview", accountId = form.accountId, date = Transaction.yyyymmdd(form.date),
        amount = if (form.type == TransactionType.Income) unsignedCents else -unsignedCents, payeeId = payeeId, payeeName = payeeName,
        categoryId = historyCategoryId, categoryName = null, notes = form.notes.ifEmpty { null }, cleared = form.cleared, reconciled = false,
        transferId = null, isParent = false, parentId = null, tombstone = false, sortOrder = null, importedPayee = payeeName,
    )
    val syncClient = syncClient
    if (!applyRules || syncClient == null) return AutomaticCategoryPreview(historyCategoryId, historyCategoryId)
    val prepared = syncClient.prepareRules()
    return AutomaticCategoryPreview(historyCategoryId, RulesEngine.apply(transaction, prepared.rules, prepared.context).transaction.categoryId)
}

/**
 * Save the add/edit form: transfers become a paired transfer, everything else
 * resolves its payee and creates or (when [original] is non-null) updates the
 * transaction. [newId] and [financialId] stamp a created row (a transfer's
 * source leg, a split's parent): the pending-import editor passes its
 * import's so a retry finds the row. Edits ignore both. Returns a new
 * standard row's id.
 */
suspend fun BudgetStore.saveTransaction(form: TransactionForm, original: Transaction? = null, newId: String? = null, financialId: String? = null): String? {
    // New plain rows collect their target after resolving the payee and
    // applying rules in createTransaction; edits and splits skip rules.
    if (original == null && form.type != TransactionType.Transfer && form.splits.isEmpty()) {
        return performSaveTransaction(form, null, newId, financialId)
    }
    var targets = impactTargets(form)
    if (original != null) targets = targets + impactTargets(listOf(original))
    return withImpactCue(targets) { performSaveTransaction(form, original, newId, financialId) }
}

private suspend fun BudgetStore.performSaveTransaction(form: TransactionForm, original: Transaction?, newId: String?, financialId: String?): String? {
    var form = form
    // The form hides categories for off-budget accounts; normalize here too
    // so stale picker or split state can't bypass that rule.
    if (form.type != TransactionType.Transfer && form.accountId in offBudgetAccountIds) {
        form = form.copy(categoryId = null, splits = emptyList(), collapseSplit = original?.isParent == true)
    }
    val date = Transaction.yyyymmdd(form.date)
    val notes = form.notes.ifEmpty { null }

    when (val plan = TransactionFormPlan.of(form)) {
        is TransactionFormPlan.Transfer -> {
            if (original != null) {
                // An existing transfer re-saves both legs; an ordinary row is
                // converted in place (GH #259) — pairing it into a brand new
                // transfer would orphan it.
                if (original.transferId == null) {
                    convertToTransfer(original, form, plan.toAccountId, plan.amountCents, date, notes)
                } else {
                    updateTransfer(original, form.accountId, plan.toAccountId, plan.amountCents, date, notes, form.cleared, form.categoryId)
                }
                return null
            }
            createTransfer(form.accountId, plan.toAccountId, plan.amountCents, date, notes, form.cleared, form.categoryId, newId, financialId)
            return null
        }
        is TransactionFormPlan.Split -> {
            if (original != null) {
                // Editing an existing split parent reconciles its children with
                // the lines; a plain transaction becomes a split's parent.
                if (original.isParent) updateSplit(original, form, plan.amountCents, plan.lines, date, notes)
                else convertToSplit(original, form, plan.amountCents, plan.lines, date, notes)
                return null
            }
            createSplit(form, plan, date, notes, newId, financialId)
            return null
        }
        is TransactionFormPlan.Standard -> {
            val payeeId = resolvePayeeId(form.payeeName, original)
            val payeeName = form.payeeName.ifEmpty { null }
            if (original != null) {
                // "Remove Split": demote the parent and tombstone its children.
                if (original.isParent && form.collapseSplit) {
                    collapseSplit(original, form, plan.amountCents, date, notes)
                    return null
                }
                // Split parents: the amount is the children's sum and the
                // category lives on the children — never overwrite either.
                val updated = Transaction(
                    id = original.id, accountId = form.accountId, date = date,
                    amount = if (original.isParent) original.amount else plan.amountCents, payeeId = payeeId, payeeName = payeeName,
                    categoryId = if (original.isParent) null else form.categoryId, categoryName = null, notes = notes, cleared = form.cleared,
                    reconciled = original.reconciled, transferId = original.transferId, isParent = original.isParent, parentId = original.parentId,
                    tombstone = original.tombstone, sortOrder = original.sortOrder, importedPayee = null,
                )
                updateTransaction(updated, original)
                if (original.isParent) cascadeSharedFieldsToChildren(updated, original.payeeId)
                return null
            }
            val categoryId = when {
                form.categoryIsExplicit -> form.categoryId
                form.automaticCategoryPreview != null -> form.automaticCategoryPreview.sourceCategoryId
                else -> form.categoryId
            }
            val transaction = Transaction(
                id = newId ?: UUID.randomUUID().toString(), accountId = form.accountId, date = date, amount = plan.amountCents,
                payeeId = payeeId, payeeName = payeeName, categoryId = categoryId, categoryName = null, notes = notes, cleared = form.cleared,
                reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false,
                sortOrder = null, // set to now during insert
                importedPayee = payeeName, financialId = financialId,
            )
            createTransaction(transaction, preserveCategory = form.categoryIsExplicit, showImpact = true)
            if (form.recordLocation && payeeId != null) recordPayeeLocationIfAppropriate(payeeId)
            return transaction.id
        }
    }
}

/** A new split: the parent plus one child per line, with transfer partners for lines whose payee is an account. */
private suspend fun BudgetStore.createSplit(form: TransactionForm, plan: TransactionFormPlan.Split, date: Int, notes: String?, newId: String?, financialId: String?) {
    val payeeId = resolvePayeeId(form.payeeName, null)
    val payeeName = form.payeeName.ifEmpty { null }
    val parentId = newId ?: UUID.randomUUID().toString()
    // Explicit sort orders keep the children in entry order under the parent.
    val parentSort = System.currentTimeMillis().toDouble()
    val parent = Transaction(
        id = parentId, accountId = form.accountId, date = date, amount = plan.amountCents, payeeId = payeeId, payeeName = payeeName,
        categoryId = null, // split parents never carry a category
        categoryName = null, notes = notes, cleared = form.cleared, reconciled = false, transferId = null, isParent = true, parentId = null,
        tombstone = false, sortOrder = parentSort, importedPayee = payeeName, financialId = financialId,
    )
    val children = mutableListOf<Transaction>()
    val transferPartners = mutableListOf<Transaction>()
    for ((index, line) in plan.lines.withIndex()) {
        // Children inherit the parent's payee unless the line names its own
        // (Actual's makeChild semantics).
        val (childPayeeId, childPayeeName) = when {
            line.payeeId != null -> line.payeeId to line.payeeName
            line.payeeName != null && line.payeeName != payeeName -> resolvePayeeId(line.payeeName, null) to line.payeeName
            else -> payeeId to payeeName
        }
        val childId = UUID.randomUUID().toString()
        val transferAccountId = childPayeeId?.let { selected -> payees.firstOrNull { it.id == selected }?.transferAccountId }
        val partnerId = transferAccountId?.let { UUID.randomUUID().toString() }
        val childCategoryId = if (transferAccountId != null) {
            line.categoryId.takeIf { transferLegTakesCategory(form.accountId, transferAccountId, offBudgetAccountIds) }
        } else {
            line.categoryId
        }
        children += Transaction(
            id = childId, accountId = form.accountId, date = date, amount = line.amountCents, payeeId = childPayeeId, payeeName = childPayeeName,
            categoryId = childCategoryId, categoryName = null, notes = line.notes, cleared = form.cleared, reconciled = false,
            transferId = partnerId, isParent = false, parentId = parentId, tombstone = false, sortOrder = parentSort - (index + 1), importedPayee = null,
        )
        if (transferAccountId != null && partnerId != null) {
            if (transferAccountId == form.accountId) throw BudgetStoreError.TransferAccountsMatch
            val sourcePayee = transferPayee(form.accountId) ?: throw BudgetStoreError.TransferPayeeMissing
            transferPartners += Transaction(
                id = partnerId, accountId = transferAccountId, date = date, amount = -line.amountCents, payeeId = sourcePayee.id,
                payeeName = null, categoryId = null, categoryName = null, notes = line.notes, cleared = form.cleared, reconciled = false,
                transferId = childId, isParent = false, parentId = null, tombstone = false, sortOrder = null, importedPayee = null,
            )
        }
    }
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.createSplit(parent, children, transferPartners)
    publishTransactionsImmediately(listOf(parent.id))
    refreshDataOnly()
    if (form.recordLocation && payeeId != null) recordPayeeLocationIfAppropriate(payeeId)
}

private data class ResolvedSplitPayee(val id: String?, val name: String?, val transferAccountId: String?)

private suspend fun BudgetStore.resolveSplitPayee(line: SplitPlanLine, inheritedId: String?, inheritedName: String?, editing: Transaction?): ResolvedSplitPayee {
    var (id, name) = knownSplitPayee(line, inheritedId, inheritedName, editing)
    if (id == null && line.payeeName != null && line.payeeName != inheritedName) id = resolvePayeeId(line.payeeName, editing)
    return ResolvedSplitPayee(id, name, splitTransferAccountId(id, editing))
}

private fun BudgetStore.knownSplitPayee(line: SplitPlanLine, inheritedId: String?, inheritedName: String?, editing: Transaction?): Pair<String?, String?> {
    line.payeeId?.let { return it to line.payeeName }
    val name = line.payeeName
    if (name != null && name != inheritedName) {
        if (name == editing?.payeeName) return editing.payeeId to name
        return payees.firstOrNull { it.name.lowercase() == name.lowercase() }?.id to name
    }
    return inheritedId to inheritedName
}

private fun BudgetStore.splitTransferAccountId(payeeId: String?, editing: Transaction?): String? {
    payeeId ?: return null
    return payees.firstOrNull { it.id == payeeId }?.transferAccountId ?: editing?.takeIf { it.payeeId == payeeId }?.transferAcct
}

private fun BudgetStore.splitTransferPartner(id: String, child: Transaction, destinationAccountId: String): Transaction {
    if (destinationAccountId == child.accountId) throw BudgetStoreError.TransferAccountsMatch
    val sourcePayee = transferPayee(child.accountId) ?: throw BudgetStoreError.TransferPayeeMissing
    return Transaction(
        id = id, accountId = destinationAccountId, date = child.date, amount = -child.amount, payeeId = sourcePayee.id, payeeName = null,
        categoryId = null, categoryName = null, notes = child.notes, cleared = child.cleared, reconciled = false, transferId = child.id,
        isParent = false, parentId = null, tombstone = false, sortOrder = null, importedPayee = null,
    )
}

/**
 * Apply an edited split form to an existing split parent: the parent takes
 * the form's total/payee/notes/date/cleared, lines with a `childId` update
 * their child row, lines without one become new children, and children
 * missing from the form are tombstoned.
 */
private suspend fun BudgetStore.updateSplit(original: Transaction, form: TransactionForm, amountCents: Long, lines: List<SplitPlanLine>, date: Int, notes: String?) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val database = database ?: throw BudgetStoreError.SyncNotConfigured

    val existingChildren = database.fetchChildTransactions(original.id)
    val childrenById = existingChildren.associateBy { it.id }
    val existingPartners = HashMap<String, Transaction>()
    val inheritedName = form.payeeName.ifEmpty { null }
    val inheritedId = when {
        form.payeeName.isEmpty() -> null
        form.payeeName == original.payeeName -> original.payeeId
        else -> payees.firstOrNull { it.name.lowercase() == form.payeeName.lowercase() }?.id
    }
    val transferLines = lines.mapNotNull { line ->
        val editing = line.childId?.let { childrenById[it] }
        val (payeeId, _) = knownSplitPayee(line, inheritedId, inheritedName, editing)
        splitTransferAccountId(payeeId, editing)?.let { line to it }
    }
    if (transferLines.isNotEmpty() && transferPayee(form.accountId) == null) throw BudgetStoreError.TransferPayeeMissing
    for ((line, destinationId) in transferLines) {
        if (destinationId == form.accountId) throw BudgetStoreError.TransferAccountsMatch
        val partnerId = line.childId?.let { childrenById[it]?.transferId } ?: continue
        existingPartners[partnerId] = database.fetchTransaction(partnerId) ?: throw BudgetStoreError.TransferPartnerMissing
    }

    val payeeId = resolvePayeeId(form.payeeName, original)
    val payeeName = form.payeeName.ifEmpty { null }
    val parent = Transaction(
        id = original.id, accountId = form.accountId, date = date, amount = amountCents, payeeId = payeeId, payeeName = payeeName,
        categoryId = null, // split parents never carry a category
        categoryName = null, notes = notes, cleared = form.cleared, reconciled = original.reconciled, transferId = original.transferId,
        isParent = true, parentId = null, tombstone = original.tombstone, sortOrder = original.sortOrder, importedPayee = null,
    )
    changedFields(original, parent).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(parent, it) }

    // Existing children keep their sort_order (updates never move rows); new
    // lines slot in below the current minimum, in the order they were added.
    var nextNewSort = existingChildren.mapNotNull { it.sortOrder }.minOrNull() ?: original.sortOrder ?: System.currentTimeMillis().toDouble()
    for (line in lines) {
        val existing = line.childId?.let { childrenById[it] }
        // Children inherit the parent's payee unless the line names its own.
        // A line whose payee matched the parent's loads back as "inherit", so a
        // parent payee edit follows through here like the cascade does.
        val resolvedPayee = resolveSplitPayee(line, payeeId, payeeName, existing)
        if (existing == null) nextNewSort -= 1
        var updated = Transaction(
            id = existing?.id ?: UUID.randomUUID().toString(), accountId = form.accountId, date = date, amount = line.amountCents,
            payeeId = resolvedPayee.id, payeeName = resolvedPayee.name,
            categoryId = resolvedPayee.transferAccountId?.let { destination ->
                line.categoryId.takeIf { transferLegTakesCategory(form.accountId, destination, offBudgetAccountIds) }
            } ?: if (resolvedPayee.transferAccountId == null) line.categoryId else null,
            categoryName = null, notes = line.notes, cleared = form.cleared, reconciled = existing?.reconciled ?: false,
            transferId = existing?.transferId, isParent = false, parentId = original.id, tombstone = false,
            sortOrder = existing?.sortOrder ?: nextNewSort, importedPayee = null,
        )

        val destinationAccountId = resolvedPayee.transferAccountId
        if (destinationAccountId != null) {
            if (destinationAccountId == form.accountId) throw BudgetStoreError.TransferAccountsMatch
            val sourcePayee = transferPayee(form.accountId) ?: throw BudgetStoreError.TransferPayeeMissing
            val partnerId = existing?.transferId ?: UUID.randomUUID().toString()
            updated = updated.copy(transferId = partnerId)
            val existingPartnerId = existing?.transferId
            if (existing != null && existingPartnerId != null) {
                val originalPartner = existingPartners[existingPartnerId] ?: throw BudgetStoreError.TransferPartnerMissing
                val partner = originalPartner.copy(
                    accountId = destinationAccountId, amount = -updated.amount, payeeId = sourcePayee.id, date = date,
                    notes = line.notes, cleared = form.cleared,
                )
                changedFields(existing, updated).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(updated, it) }
                changedFields(originalPartner, partner).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(partner, it) }
            } else {
                val partner = splitTransferPartner(partnerId, updated, destinationAccountId)
                if (existing != null) syncClient.convertToTransfer(updated, changedFields(existing, updated), partner)
                else syncClient.createTransfer(updated, partner)
            }
        } else if (existing != null) {
            updated = updated.copy(transferId = null)
            changedFields(existing, updated).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(updated, it) }
            existing.transferId?.let { database.fetchTransaction(it) }?.let { syncClient.updateTransaction(it.copy(tombstone = true), setOf("tombstone")) }
        } else {
            // Rules are skipped, matching createSplit — every field is explicit.
            syncClient.createTransaction(updated, applyRules = false)
        }
    }

    // Lines removed from the form tombstone their child rows — orphaned
    // children would be invisible in the list but still feed reports.
    val keptIds = lines.mapNotNull { it.childId }.toSet()
    for (child in existingChildren) {
        if (child.id in keptIds) continue
        syncClient.updateTransaction(child.copy(tombstone = true), setOf("tombstone"))
        child.transferId?.let { database.fetchTransaction(it) }?.let { syncClient.updateTransaction(it.copy(tombstone = true), setOf("tombstone")) }
    }

    refreshDataOnly()
    if (form.recordLocation && payeeId != null) recordPayeeLocationIfAppropriate(payeeId)
}

/**
 * Convert an ordinary transaction into one leg of a transfer (GH #259): the
 * row keeps its id and history, its payee becomes the other account's
 * transfer payee, and a new partner leg is created in that account for the
 * opposite amount. Mirrors upstream `addTransfer`. The row keeps its direction
 * too, so the amount the bank reported can't flip sign. Split parents and
 * children are refused, matching upstream's `is_parent` bail-out.
 */
private suspend fun BudgetStore.convertToTransfer(original: Transaction, form: TransactionForm, otherAccountId: String, amountCents: Long, date: Int, notes: String?) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    if (original.isParent || original.parentId != null) throw BudgetStoreError.CannotConvertToTransfer
    if (form.accountId == otherAccountId) throw BudgetStoreError.TransferAccountsMatch
    if (amountCents <= 0) throw BudgetStoreError.TransferAmountNotPositive
    val legTransferPayee = transferPayee(form.accountId) ?: throw BudgetStoreError.TransferPayeeMissing
    val otherTransferPayee = transferPayee(otherAccountId) ?: throw BudgetStoreError.TransferPayeeMissing

    val signedAmount = if (original.amount < 0) -amountCents else amountCents
    // The new partner leg has no category of its own to keep either way.
    val legCategoryId = form.categoryId.takeIf { transferLegTakesCategory(form.accountId, otherAccountId, offBudgetAccountIds) }
    val partnerId = UUID.randomUUID().toString()
    val leg = original.copy(
        accountId = form.accountId, amount = signedAmount, payeeId = otherTransferPayee.id, categoryId = legCategoryId,
        date = date, notes = notes, cleared = form.cleared, transferId = partnerId,
    )
    val partner = Transaction(
        id = partnerId, accountId = otherAccountId, date = date, amount = -signedAmount, payeeId = legTransferPayee.id, payeeName = null,
        categoryId = null, categoryName = null, notes = notes,
        // Upstream's addTransfer inserts the partner uncleared: it's a row the
        // bank never reported, whatever the imported side says.
        cleared = false, reconciled = false, transferId = original.id, isParent = false, parentId = null, tombstone = false,
        sortOrder = null, importedPayee = null,
    )
    // Both rows commit together: a row whose transferred_id outlived a failed
    // partner insert would be a half-transfer, already on its way to the server.
    syncClient.convertToTransfer(leg, changedFields(original, leg), partner)
    refreshDataOnly()
}

/**
 * Convert an ordinary (non-split) transaction into a split: the original row
 * becomes the parent and the form's lines its children. Transfers are refused
 * because the paired row references this one; split children because there's
 * no row of their own to promote.
 */
private suspend fun BudgetStore.convertToSplit(original: Transaction, form: TransactionForm, amountCents: Long, lines: List<SplitPlanLine>, date: Int, notes: String?) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    if (original.transferId != null || original.parentId != null) throw BudgetStoreError.CannotConvertToSplit
    val payeeId = resolvePayeeId(form.payeeName, original)
    val payeeName = form.payeeName.ifEmpty { null }
    val parent = Transaction(
        id = original.id, accountId = form.accountId, date = date, amount = amountCents, payeeId = payeeId, payeeName = payeeName,
        categoryId = null, categoryName = null, notes = notes, cleared = form.cleared, reconciled = original.reconciled, transferId = null,
        isParent = true, parentId = null, tombstone = original.tombstone, sortOrder = original.sortOrder, importedPayee = original.importedPayee,
    )
    changedFields(original, parent).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(parent, it) }

    // Children inherit the parent's payee unless the line names its own;
    // rules are skipped — every field came from the form.
    var nextSort = original.sortOrder ?: System.currentTimeMillis().toDouble()
    for (line in lines) {
        nextSort -= 1
        val resolvedPayee = resolveSplitPayee(line, payeeId, payeeName, null)
        val partnerId = resolvedPayee.transferAccountId?.let { UUID.randomUUID().toString() }
        val child = Transaction(
            id = UUID.randomUUID().toString(), accountId = form.accountId, date = date, amount = line.amountCents,
            payeeId = resolvedPayee.id, payeeName = resolvedPayee.name,
            categoryId = resolvedPayee.transferAccountId?.let { destination ->
                line.categoryId.takeIf { transferLegTakesCategory(form.accountId, destination, offBudgetAccountIds) }
            } ?: if (resolvedPayee.transferAccountId == null) line.categoryId else null,
            categoryName = null, notes = line.notes, cleared = form.cleared, reconciled = false, transferId = partnerId, isParent = false,
            parentId = original.id, tombstone = false, sortOrder = nextSort, importedPayee = null,
        )
        val destination = resolvedPayee.transferAccountId
        if (destination != null && partnerId != null) syncClient.createTransfer(child, splitTransferPartner(partnerId, child, destination))
        else syncClient.createTransaction(child, applyRules = false)
    }
    refreshDataOnly()
    if (form.recordLocation && payeeId != null) recordPayeeLocationIfAppropriate(payeeId)
}

/**
 * Collapse a split parent back into a single transaction (the edit form's
 * "Remove Split"): the parent keeps its id and history, takes the form's
 * amount and category, and is demoted. Every live child is tombstoned in the
 * same save, or they'd double-count the collapsed amount in reports.
 */
private suspend fun BudgetStore.collapseSplit(original: Transaction, form: TransactionForm, amountCents: Long, date: Int, notes: String?) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    if (!original.isParent) return
    val payeeId = resolvePayeeId(form.payeeName, original)
    val updated = Transaction(
        id = original.id, accountId = form.accountId, date = date, amount = amountCents, payeeId = payeeId,
        payeeName = form.payeeName.ifEmpty { null }, categoryId = form.categoryId, categoryName = null, notes = notes, cleared = form.cleared,
        reconciled = original.reconciled, transferId = original.transferId, isParent = false, parentId = null, tombstone = original.tombstone,
        sortOrder = original.sortOrder, importedPayee = original.importedPayee,
    )
    changedFields(original, updated).takeIf { it.isNotEmpty() }?.let { syncClient.updateTransaction(updated, it) }
    for (child in database.fetchChildTransactions(original.id)) {
        syncClient.updateTransaction(child.copy(tombstone = true), setOf("tombstone"))
        child.transferId?.let { database.fetchTransaction(it) }?.let { syncClient.updateTransaction(it.copy(tombstone = true), setOf("tombstone")) }
    }
    refreshDataOnly()
    if (form.recordLocation && payeeId != null) recordPayeeLocationIfAppropriate(payeeId)
}

/**
 * Payee id for a standard (non-transfer) save: an empty name clears the
 * payee, a name unchanged from the transaction being edited keeps it, and
 * anything else is matched case-insensitively or created.
 */
suspend fun BudgetStore.resolvePayeeId(name: String, original: Transaction?): String? {
    if (name.isEmpty()) return null
    if (name == original?.payeeName) return original.payeeId
    return try {
        findOrCreatePayee(name).id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.PayeeCreationFailed(e.localizedMessage ?: e.toString())
    }
}

/** Record only when no existing location for the payee is within 500 m (upstream dedupe rule). */
fun shouldRecordLocation(position: Coordinates, existing: List<PayeeLocation>): Boolean = existing.none { location ->
    LocationUtils.calculateDistanceMeters(position.latitude, position.longitude, location.latitude, location.longitude) <= LocationUtils.DEFAULT_MAX_DISTANCE_METERS
}

/**
 * Fire-and-forget: attach the current position to [payeeId]. All guards and
 * failures collapse to "do nothing" — recording a location must never affect
 * the save that triggered it.
 */
fun BudgetStore.recordPayeeLocationIfAppropriate(payeeId: String) {
    if (!payeeLocationWritesEnabled || !recordPayeeLocations) return
    scope.launch {
        val provider = runCatching { LocationProvider.shared }.getOrNull() ?: return@launch
        if (provider.authorizationStatus() != LocationAuthStatus.Granted) return@launch
        val position = runCatching { provider.currentPosition() }.getOrNull() ?: return@launch
        if (!LocationUtils.isValidCoordinate(position.latitude, position.longitude)) return@launch
        val database = database ?: return@launch
        val existing = runCatching { database.fetchPayeeLocations(payeeId) }.getOrNull() ?: return@launch
        if (!shouldRecordLocation(position, existing)) return@launch
        val syncClient = syncClient ?: return@launch
        try {
            syncClient.createPayeeLocation(PayeeLocation(UUID.randomUUID().toString(), payeeId, position.latitude, position.longitude, System.currentTimeMillis()))
            logger.debug("Recorded payee location")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to record payee location: ${e.message}")
        }
    }
}

/** The CRDT columns that differ between two versions of a row. */
internal fun changedFields(original: Transaction, updated: Transaction): Set<String> {
    val changed = LinkedHashSet<String>()
    if (original.accountId != updated.accountId) changed += "acct"
    if (original.date != updated.date) changed += "date"
    if (original.payeeId != updated.payeeId) changed += "description"
    if (original.categoryId != updated.categoryId) changed += "category"
    if (original.amount != updated.amount) changed += "amount"
    if (original.notes != updated.notes) changed += "notes"
    if (original.cleared != updated.cleared) changed += "cleared"
    if (original.reconciled != updated.reconciled) changed += "reconciled"
    if (original.transferId != updated.transferId) changed += "transferred_id"
    if (original.isParent != updated.isParent) changed += "isParent"
    if (original.parentId != updated.parentId) changed += "parent_id"
    if (original.tombstone != updated.tombstone) changed += "tombstone"
    return changed
}
