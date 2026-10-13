package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.banksync.ActualServerBankSyncProvider
import io.github.traushb.actuali.services.banksync.BankSyncAccount
import io.github.traushb.actuali.services.banksync.BankSyncDownload
import io.github.traushb.actuali.services.banksync.BankSyncProvider
import io.github.traushb.actuali.services.banksync.BankSyncReconciler
import io.github.traushb.actuali.services.banksync.BankSyncRemoteAccount
import io.github.traushb.actuali.services.banksync.BankSyncSource
import io.github.traushb.actuali.services.banksync.BankSyncTarget
import io.github.traushb.actuali.services.banksync.ExpectedBankSyncLink
import io.github.traushb.actuali.services.banksync.SimpleFINDirectProvider
import io.github.traushb.actuali.services.database.BankSyncDatabaseError
import io.github.traushb.actuali.services.database.BankSyncOpeningInsert
import io.github.traushb.actuali.services.database.BankSyncOpeningUpdate
import io.github.traushb.actuali.services.database.PreparedBankSyncInsert
import io.github.traushb.actuali.services.database.bankSyncWindow
import io.github.traushb.actuali.services.database.earliestMessageDay
import io.github.traushb.actuali.services.database.fetchBankSyncAccounts
import io.github.traushb.actuali.services.database.fetchPayees
import io.github.traushb.actuali.services.database.fetchPreference
import io.github.traushb.actuali.services.database.fetchTransaction
import io.github.traushb.actuali.services.database.oldestTransactionDate
import io.github.traushb.actuali.services.database.startingBalanceTransactionId
import io.github.traushb.actuali.services.network.ActualServerError
import io.github.traushb.actuali.services.network.SimpleFINAccount
import io.github.traushb.actuali.services.network.SimpleFINCredentials
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.sync.SyncClient
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.UUID

// Bank Sync (SimpleFIN)
//
// The iOS app also imports Apple Wallet (FinanceKit) feeds; those links stay
// on the iPhone that made them, so a Wallet-linked account shows here as a
// linked account this device can't refresh, and a sync skips it quietly —
// what iOS itself does on a device without Wallet.

/**
 * The last-resort import lookback, when no day was chosen and the budget has
 * no messages to date it by. 89 days ago through today inclusive is 90 days,
 * the window upstream settled on because several bank integrations won't serve more.
 */
private const val BANK_SYNC_MAX_LOOKBACK_DAYS = 89

/** What one run of [syncBankAccounts] did. */
data class BankSyncResult(
    val accountsSynced: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    /** The rows this run inserted (opening balances excluded). */
    val importedTransactions: List<Transaction> = emptyList(),
    /**
     * Anything worth telling the person about: a bank connection that needs
     * re-authenticating, an account SimpleFIN no longer knows. A run can
     * succeed for some accounts and report problems for others.
     */
    val problems: List<String> = emptyList(),
) {
    /** What to show when the run finishes. Problems come last so the counts above them still read as what did work. */
    val summary: String get() = summary(Locale.getDefault())

    fun summary(locale: Locale): String {
        val lines = mutableListOf<String>()
        if (added > 0) lines += ReportStrings.localized("Imported %lld transactions", added.toLong(), locale = locale) + "."
        if (updated > 0) lines += ReportStrings.localized("Matched %lld transactions you already had.", updated.toLong(), locale = locale)
        // Only claim there was nothing to do when nothing went wrong either —
        // otherwise the problems below say what happened.
        if (lines.isEmpty() && problems.isEmpty()) {
            lines += ReportStrings.text(if (accountsSynced == 0) "No linked accounts to sync." else "Everything is already up to date.", locale)
        }
        return (lines + problems).joinToString("\n\n")
    }

    companion object {
        fun conflictProblem(accountName: String, count: Int, locale: Locale = Locale.getDefault()): String = ReportStrings.localized(
            "%@: Skipped %lld transactions because the bank returned conflicting details for the same transaction.",
            accountName,
            count.toLong(),
            locale = locale,
        )

        /**
         * The message for linked accounts Actuali can't refresh — a run that
         * finds only those should explain why instead of claiming nothing is
         * linked. null when a supported account is present (the run syncs it)
         * or nothing is linked.
         */
        fun unsupportedSourceMessage(accounts: List<BankSyncAccount>, locale: Locale = Locale.getDefault()): String? {
            if (accounts.isEmpty() || accounts.any { it.source != null }) return null
            // Upstream writes exactly 'goCardless' into account_sync_source
            // (app.ts); pluggyai, akahu, enableBanking and future sources stay
            // generic rather than guess a provider name.
            if (accounts.mapTo(HashSet()) { it.syncSource } == setOf("goCardless")) {
                return ReportStrings.text("Actuali can't refresh GoCardless accounts yet. Refresh them from the Actual web app.", locale)
            }
            return ReportStrings.text("Actuali can't refresh accounts from this bank provider yet. Refresh them from the Actual web app.", locale)
        }
    }
}

// Import start day

/** Device-local and per budget: the setting must not reach the synced file. */
private fun bankSyncImportStartKey(budgetId: String) = "bankSyncImportStart_$budgetId"

/** The person's chosen import start day (`YYYYMMDD`), or null to follow the default. */
private val BudgetStore.storedBankSyncImportStartDay: Int?
    get() = currentBudgetId?.let { (defaults.get(bankSyncImportStartKey(it)) as? Number)?.toInt() }

fun BudgetStore.setBankSyncImportStartDay(day: Int) {
    val budgetId = currentBudgetId ?: return
    defaults.set(bankSyncImportStartKey(budgetId), day)
}

/**
 * The day imports reach back to for an account with no history of its own:
 * the person's chosen day, else the day the budget file began, else the
 * 90-day lookback several bank integrations won't serve more than.
 */
suspend fun BudgetStore.resolvedBankSyncImportStartDay(): Int {
    storedBankSyncImportStartDay?.let { return it }
    runCatching { database?.earliestMessageDay() }.getOrNull()?.let { return it }
    return DayDate.today().addingDays(-BANK_SYNC_MAX_LOOKBACK_DAYS).yyyymmdd
}

/** Whether a bank sync can run at all — through the server's connection or one claimed on this device. */
val BudgetStore.canSyncBanks: Boolean get() = serverProvidesBankSync || isSimpleFINConfigured

/**
 * Ask the server whether it does SimpleFIN, so the setup screen knows which
 * half of itself to show. Failures leave the flag alone: an unreachable
 * server isn't evidence either way.
 */
suspend fun BudgetStore.refreshBankSyncSource() {
    val configured = try {
        serverClient.simpleFINStatus()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return
    }
    serverProvidesBankSync = configured == true
}

/**
 * Where this sync's data comes from. The server's own connection wins
 * whenever it has one: Actual keeps the server's access key in its secrets
 * store, deliberately out of the budget file, so preferring the server is
 * what keeps the web UI and this app in agreement — nobody needs a second
 * setup token, and a link made here is one the server can service.
 */
private suspend fun BudgetStore.makeBankSyncProvider(): BankSyncProvider {
    val deviceKey = simpleFINAccessKeyProvider()
    try {
        // null means the route isn't served here — an older server, or a proxy
        // that strips it. Not a failure, just not an option.
        if (serverClient.simpleFINStatus() == true) {
            serverProvidesBankSync = true
            return ActualServerBankSyncProvider(serverClient)
        }
        serverProvidesBankSync = false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The server couldn't be answered for. A key claimed on this device
        // still reaches the bridge, so use it rather than failing.
        if (deviceKey == null) {
            // With no key either: a server we genuinely couldn't reach is worth
            // saying so, but anything else (no server configured, no session)
            // just means bank sync isn't set up here.
            if (e is ActualServerError && e.isConnectionFailure) throw e
            throw BudgetStoreError.BankSyncNotConfigured
        }
        return SimpleFINDirectProvider(simpleFINClient, deviceKey)
    }
    return SimpleFINDirectProvider(simpleFINClient, deviceKey ?: throw BudgetStoreError.BankSyncNotConfigured)
}

/** Run a sync and leave its outcome in [BudgetStore.bankSyncSummary]. The button-shaped entry point — [syncBankAccounts] is the one that throws. */
suspend fun BudgetStore.runBankSync(accountIds: List<String> = emptyList()) {
    // A tap that lands while a sync is already running does nothing — the
    // running sync posts its own summary, which this call's empty one would clobber.
    if (isBankSyncing) return
    bankSyncSummary = try {
        syncBankAccounts(accountIds).summary
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.localizedMessage
    }
}

/**
 * Exchange a SimpleFIN setup token for an access key this device keeps. Only
 * needed when the server has no SimpleFIN connection of its own. Setup tokens
 * are single-use, so this runs once per token.
 */
suspend fun BudgetStore.connectSimpleFIN(setupToken: String) {
    val accessKey = simpleFINClient.claimAccessKey(setupToken)
    SimpleFINCredentials.save(accessKey)
    isSimpleFINConfigured = true
}

/**
 * Forget this device's access key. Accounts stay linked — the link lives in
 * the budget file, so the web UI (and this device, once a new token is
 * claimed) can still sync them.
 */
fun BudgetStore.disconnectSimpleFIN() {
    SimpleFINCredentials.clear()
    isSimpleFINConfigured = false
}

/** Every account the active connection covers, for the linking screen. */
suspend fun BudgetStore.fetchBankAccounts(): List<SimpleFINAccount> = makeBankSyncProvider().accounts()

suspend fun BudgetStore.loadBankSyncAccounts() {
    val fetched = fetchBankSyncAccounts() ?: return
    if (bankSyncAccounts != fetched) bankSyncAccounts = fetched
}

/** The linked accounts, or null when the budget changed (or a newer load started) while reading. */
internal suspend fun BudgetStore.fetchBankSyncAccounts(): List<BankSyncAccount>? {
    val capturedDatabase = database
    val capturedBudgetId = currentBudgetId
    bankSyncLoadGeneration += 1
    val capturedGeneration = bankSyncLoadGeneration
    fun isCurrentRequest() = database === capturedDatabase && currentBudgetId == capturedBudgetId && bankSyncLoadGeneration == capturedGeneration
    val synced = capturedDatabase?.let { runCatching { it.fetchBankSyncAccounts() }.getOrNull().orEmpty() }.orEmpty()
    bankSyncAccountsFetchedForTesting?.invoke()
    return synced.takeIf { isCurrentRequest() }
}

/** The bank feed an account is wired up to, if any. */
fun BudgetStore.bankSyncAccount(accountId: String): BankSyncAccount? = bankSyncAccounts.firstOrNull { it.id == accountId }

private fun BankSyncAccount.expectedLink() = ExpectedBankSyncLink(id, externalAccountId, syncSource)

/** Runs a link write; a link that changed under it reloads the linked accounts before the failure surfaces. */
private suspend fun BudgetStore.linkWrite(write: suspend () -> Unit) {
    try {
        write()
    } catch (e: BankSyncDatabaseError.BankSyncMaterializationStale) {
        loadBankSyncAccounts()
        throw e
    }
}

suspend fun BudgetStore.linkBankAccount(accountId: String, remote: BankSyncRemoteAccount) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val expectedOldLink = bankSyncAccount(accountId)?.expectedLink()
    linkWrite {
        syncClient.linkAccount(
            accountId, remote.id, remote.source, remote.institutionId, remote.institutionName,
            expectedOldLink = expectedOldLink, verifyExpectedOldLink = true,
        )
    }
    refreshDataOnly()
}

suspend fun BudgetStore.unlinkBankAccount(accountId: String) {
    val linked = bankSyncAccount(accountId)?.takeIf { it.syncSource.isNotEmpty() } ?: return
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    linkWrite { syncClient.unlinkAccount(accountId, linked.expectedLink()) }
    refreshDataOnly()
}

/**
 * Download and import transactions for the linked accounts. [accountIds]
 * picks which to sync; empty syncs every linked one, which is what the
 * accounts tab's sync button does.
 */
suspend fun BudgetStore.syncBankAccounts(accountIds: List<String> = emptyList()): BankSyncResult {
    var bankSyncHook = bankSyncBeforeMaterializationHook
    bankSyncBeforeMaterializationHook = null
    fun takeBankSyncHook() = bankSyncHook.also { bankSyncHook = null }
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured
    // A second run on top of the first would re-download the same window and race the first one's writes.
    if (isBankSyncing) return BankSyncResult()

    val linked = bankSyncAccounts.filter { !it.closed && (accountIds.isEmpty() || it.id in accountIds) }
    val targets = linked.filter { it.source == BankSyncSource.SimpleFin }
    if (targets.isEmpty()) return BankSyncResult(problems = listOfNotNull(BankSyncResult.unsupportedSourceMessage(linked)))

    // Nothing may suspend between the isBankSyncing check above and this write
    // — a suspension in that window would let a second call slip past the
    // check and import everything twice.
    isBankSyncing = true
    try {
        var result = BankSyncResult()

        // Three windows. A first import (no history) starts where the person
        // chose — by default, the day the budget file began. An account whose
        // history doesn't reach that day yet asks for it again every run until
        // it does; dedup keeps the overlap safe. Ongoing syncs only need the
        // stretch since the account's earliest transaction, still capped at
        // the rolling 90-day floor.
        val importStart = resolvedBankSyncImportStartDay()
        val lookbackFloor = DayDate.today().addingDays(-BANK_SYNC_MAX_LOOKBACK_DAYS).yyyymmdd
        // Both "the read failed" and "the account has no transactions" mean
        // the same thing here: start at the chosen day.
        val oldestDates = targets.associate { it.id to runCatching { database.oldestTransactionDate(it.id) }.getOrNull() }
        val targetStartDays = targets.associate { target ->
            val oldest = oldestDates[target.id]
            // Reach past existing history only while the chosen day sits below
            // it. Not `min(importStart, incremental)`: the default day is older
            // than the 90-day floor for any budget past its first quarter, and
            // that form would widen every ongoing sync to it.
            target.id to if (oldest == null || importStart < oldest) importStart else maxOf(lookbackFloor, oldest)
        }

        val downloaded = try {
            makeBankSyncProvider().download(targets.map { BankSyncTarget(it.externalAccountId, targetStartDays.getValue(it.id)) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing device key is this device's problem, not the shared
            // feed's, so it stays out of the synced status columns.
            if (e !is BudgetStoreError.BankSyncNotConfigured) {
                runCatching { syncClient.recordBankSyncStatus(targets.map { SyncClient.BankSyncStatusUpdate(it.id, null, "failed", it.expectedLink()) }) }
            }
            throw e
        }

        result = result.copy(problems = result.problems + downloaded.problems)
        // One rules/context fetch for the whole run, not one per row.
        val prepared = syncClient.prepareRules()
        val syncedAt = System.currentTimeMillis().toString()
        val statuses = mutableListOf<SyncClient.BankSyncStatusUpdate>()

        for (target in targets) {
            val download = downloaded.byAccount[target.externalAccountId]
            if (download == null) {
                // A connection-level problem already explains why nothing came
                // back; don't also tell them to relink an account that's fine.
                if (downloaded.problems.isEmpty()) {
                    result = result.copy(problems = result.problems + "${target.name}: SimpleFIN didn't return this account. Unlink it and link it again.")
                }
                statuses += SyncClient.BankSyncStatusUpdate(target.id, null, if (downloaded.problems.isEmpty()) "account-missing" else "failed", target.expectedLink())
                continue
            }
            // A problem doesn't mean nothing came through — import whatever
            // did, and say what went wrong alongside it.
            download.problem?.let { result = result.copy(problems = result.problems + "${target.name}: $it") }
            try {
                val outcome = try {
                    importBankSync(download, target, oldestDates[target.id], targetStartDays.getValue(target.id), prepared, takeBankSyncHook())
                } catch (e: BankSyncDatabaseError.BankSyncRulesChanged) {
                    importBankSync(download, target, oldestDates[target.id], targetStartDays.getValue(target.id), syncClient.prepareRules(), null)
                }
                result = result.copy(
                    added = result.added + outcome.added,
                    updated = result.updated + outcome.updated,
                    importedTransactions = result.importedTransactions + outcome.inserted,
                    problems = if (outcome.rejectedConflicts > 0) {
                        result.problems + BankSyncResult.conflictProblem(target.name, outcome.rejectedConflicts)
                    } else {
                        result.problems
                    },
                    accountsSynced = result.accountsSynced + 1,
                )
                // Upstream `handleSyncResponse` stamps both columns after a
                // completed download, while `persistBankSyncError` preserves
                // `last_sync` (loot-core server/accounts/app.ts). SimpleFIN may
                // attach an attention warning to a complete account payload,
                // so that case still completed.
                val completed = download.status == "ok" || (download.status == "attention-required" && download.accountDataReceived)
                statuses += SyncClient.BankSyncStatusUpdate(target.id, if (completed) syncedAt else null, download.status, target.expectedLink())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = result.copy(problems = result.problems + "${target.name}: ${e.localizedMessage}")
                statuses += SyncClient.BankSyncStatusUpdate(target.id, null, "failed", target.expectedLink())
            }
        }

        // The same two columns every other Actual client stamps, so the web
        // UI's "last synced" and status badge reflect this run. Never worth
        // failing the sync over — the transactions are already in.
        try {
            syncClient.recordBankSyncStatus(statuses)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        refreshDataOnly()
        return result
    } finally {
        isBankSyncing = false
    }
}

private data class BankSyncImportOutcome(val added: Int, val updated: Int, val inserted: List<Transaction>, val rejectedConflicts: Int)

/** Fold one account's download into the budget: match what we already have, insert what we don't. */
private suspend fun BudgetStore.importBankSync(
    download: BankSyncDownload,
    target: BankSyncAccount,
    existingOldestDay: Int?,
    startingDay: Int,
    prepared: SyncClient.PreparedRules,
    bankSyncHook: (() -> Unit)?,
): BankSyncImportOutcome {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured

    // The provider already dropped anything older than this account's own
    // start day — one request covers every account, so it reaches back as far
    // as the hungriest of them.
    if (download.candidates.isEmpty() && existingOldestDay != null) return BankSyncImportOutcome(0, 0, emptyList(), 0)
    val earliest = download.candidates.minOfOrNull { it.date } ?: startingDay
    val latest = download.candidates.maxOfOrNull { it.date } ?: startingDay

    // Resolve payees by name without creating any: the payee pass compares
    // ids, and a name the budget doesn't have yet can't match anything. Keyed
    // case-insensitively, the way `findOrCreatePayee` and upstream's
    // `getPayeeByName` match, so a bank that shouts "AMAZON" still resolves "Amazon".
    val payeeIdsByName = HashMap<String, String>()
    runCatching { database.fetchPayees() }.getOrNull()?.forEach { payeeIdsByName.putIfAbsent(it.name.lowercase(), it.id) }
    val candidates = download.candidates.map { it.copy(payeeId = payeeIdsByName[it.payeeName.lowercase()]) }

    val radius = BankSyncReconciler.FUZZY_MATCH_DAY_RADIUS
    // Actual stores this as a synced per-account preference; its default is
    // true for backwards compatibility.
    val reimportDeleted = (database.fetchPreference("sync-reimport-deleted-${target.id}") ?: "true") == "true"
    val window = database.bankSyncWindow(
        target.id,
        from = DayDate.fromYYYYMMDD(earliest)?.addingDays(-radius)?.yyyymmdd ?: earliest,
        to = DayDate.fromYYYYMMDD(latest)?.addingDays(radius)?.yyyymmdd ?: latest,
        importedIds = candidates.mapTo(HashSet()) { it.importedId },
    )
    val plan = BankSyncReconciler.plan(candidates, window, reimportDeleted)
    val expectedLink = target.expectedLink()

    // Oldest first: sort_order is stamped at insert, so inserting in date
    // order leaves the newest transaction at the top of the account.
    val preparedInserts = mutableListOf<PreparedBankSyncInsert>()
    var pendingPayeesByName = emptyMap<String, Payee>()
    for (candidate in plan.inserts.sortedBy { it.date }) {
        val transaction = Transaction(
            id = UUID.randomUUID().toString(), accountId = target.id, date = candidate.date, amount = candidate.amount,
            payeeId = candidate.payeeId, payeeName = candidate.payeeName, categoryId = null, categoryName = null, notes = candidate.notes,
            cleared = candidate.cleared, reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false,
            sortOrder = null, // set to now during insert
            importedPayee = candidate.payeeName, financialId = candidate.importedId,
        )
        val result = syncClient.prepareBankSyncTransaction(transaction, prepared, pendingPayeesByName) ?: continue
        pendingPayeesByName = result.pendingPayeesByName
        preparedInserts += PreparedBankSyncInsert(result.transaction, result.messages, result.pendingPayees, candidates.count { it == candidate })
    }

    val expectedMaterializedInserts = mutableListOf<PreparedBankSyncInsert>()
    val expectedOccurrences = HashMap<String, Int>()
    for (insert in preparedInserts) {
        val financialId = insert.transaction.financialId
        if (financialId == null) {
            expectedMaterializedInserts += insert
            continue
        }
        val key = "${insert.transaction.accountId}|$financialId"
        val occurrence = expectedOccurrences[key] ?: 0
        if (occurrence >= insert.maxLiveFinancialIdOccurrences) continue
        expectedOccurrences[key] = occurrence + 1
        expectedMaterializedInserts += insert
    }
    val preparedIds = expectedMaterializedInserts.mapTo(HashSet()) { it.transaction.id }

    var openingInsert: BankSyncOpeningInsert? = null
    val balance = download.currentBalanceCents
    if (existingOldestDay == null && balance != null) {
        val openingAmount = balance - expectedMaterializedInserts.filter { it.transaction.accountId == target.id }.sumOf { it.transaction.amount }
        if (openingAmount != 0L) {
            val payee = database.fetchPayees().firstOrNull { it.name.equals("Starting Balance", ignoreCase = true) }
                ?: Payee(UUID.randomUUID().toString(), "Starting Balance", null)
            val category = if (target.offBudget) null else startingBalanceCategory()
            val transaction = Transaction(
                id = UUID.randomUUID().toString(), accountId = target.id, date = earliest, amount = openingAmount, payeeId = payee.id,
                payeeName = payee.name, categoryId = category?.id, categoryName = category?.name, notes = null, cleared = true,
                reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false, sortOrder = null,
                importedPayee = null, startingBalanceFlag = true,
            )
            openingInsert = syncClient.prepareBankSyncOpeningInsert(transaction, payee, preparedIds)
        }
    }

    // Keep a backfill balance-neutral: the opening balance was already standing
    // in for rows older than the account's history, so it gives them back.
    // Only an opening balance can have absorbed them, so an account without one
    // is left alone. Nor does the opening's date change — it carries income for
    // an on-budget account, and moving it would rewrite a past budget month.
    var openingUpdate: BankSyncOpeningUpdate? = null
    if (existingOldestDay != null) {
        val opening = database.startingBalanceTransactionId(target.id)?.let { database.fetchTransaction(it) }
        if (opening != null) {
            val carried = expectedMaterializedInserts
                .filter { it.transaction.accountId == target.id && it.transaction.date < existingOldestDay }
                .sumOf { it.transaction.amount }
            if (carried != 0L) {
                openingUpdate = syncClient.prepareBankSyncOpeningUpdate(opening.copy(amount = opening.amount - carried), opening.amount, preparedIds)
            }
        }
    }

    bankSyncHook?.invoke()
    val materialized = syncClient.materializeBankSync(plan.updates, preparedInserts, openingInsert, openingUpdate, expectedLink, prepared.fingerprint)
    val added = materialized.inserted.size + if (openingInsert == null) 0 else 1
    return BankSyncImportOutcome(added, materialized.updatedCount, materialized.inserted, plan.rejectedConflicts)
}
