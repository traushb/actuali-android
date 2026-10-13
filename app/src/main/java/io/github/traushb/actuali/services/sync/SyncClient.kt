package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.CategoryMapping
import io.github.traushb.actuali.models.CreditCardConfig
import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.PayeeLocation
import io.github.traushb.actuali.models.PayeeMapping
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.banksync.Bank
import io.github.traushb.actuali.services.banksync.BankSyncSource
import io.github.traushb.actuali.services.banksync.BankSyncUpdate
import io.github.traushb.actuali.services.banksync.ExpectedBankSyncLink
import io.github.traushb.actuali.services.budget.GoalTemplateEngine
import io.github.traushb.actuali.services.database.BankSyncDatabaseError
import io.github.traushb.actuali.services.database.BankSyncMaterialization
import io.github.traushb.actuali.services.database.BankSyncOpeningInsert
import io.github.traushb.actuali.services.database.BankSyncOpeningUpdate
import io.github.traushb.actuali.services.database.BankSyncRulesFingerprint
import io.github.traushb.actuali.services.database.BankSyncStatusEntry
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.ClockRecord
import io.github.traushb.actuali.services.database.PreferenceKeys
import io.github.traushb.actuali.services.database.PreparedBankSyncInsert
import io.github.traushb.actuali.services.database.allTags
import io.github.traushb.actuali.services.database.applyBankSyncLink
import io.github.traushb.actuali.services.database.applyBankSyncStatus
import io.github.traushb.actuali.services.database.applyBankSyncUnlink
import io.github.traushb.actuali.services.database.applyBankSyncUpdates
import io.github.traushb.actuali.services.database.applyMessagesAndInsertMessages
import io.github.traushb.actuali.services.database.budgetCell
import io.github.traushb.actuali.services.database.convertToTransfer
import io.github.traushb.actuali.services.database.deleteTag
import io.github.traushb.actuali.services.database.deriveMerkleFromMessageLog
import io.github.traushb.actuali.services.database.discoverTags
import io.github.traushb.actuali.services.database.filterNewMessages
import io.github.traushb.actuali.services.database.getMaxMessageTimestamp
import io.github.traushb.actuali.services.database.getMessagesSince
import io.github.traushb.actuali.services.database.incomeCategoryIds
import io.github.traushb.actuali.services.database.insertAccount
import io.github.traushb.actuali.services.database.insertBankSyncTransactionWithMessages
import io.github.traushb.actuali.services.database.insertCategory
import io.github.traushb.actuali.services.database.insertCategoryGroup
import io.github.traushb.actuali.services.database.insertMessages
import io.github.traushb.actuali.services.database.insertPayee
import io.github.traushb.actuali.services.database.insertPayeeLocation
import io.github.traushb.actuali.services.database.insertSplit
import io.github.traushb.actuali.services.database.insertTag
import io.github.traushb.actuali.services.database.insertTransaction
import io.github.traushb.actuali.services.database.insertTransactionWithMessages
import io.github.traushb.actuali.services.database.insertTransfer
import io.github.traushb.actuali.services.database.loadClock
import io.github.traushb.actuali.services.database.materializeBankSync
import io.github.traushb.actuali.services.database.moveCategory
import io.github.traushb.actuali.services.database.moveCategoryGroup
import io.github.traushb.actuali.services.database.notesTableExists
import io.github.traushb.actuali.services.database.payee
import io.github.traushb.actuali.services.database.prepareRulesSnapshot
import io.github.traushb.actuali.services.database.proposeBankSyncLink
import io.github.traushb.actuali.services.database.renameTag
import io.github.traushb.actuali.services.database.rulesTableExists
import io.github.traushb.actuali.services.database.saveClock
import io.github.traushb.actuali.services.database.scheduleNameExists
import io.github.traushb.actuali.services.database.tombstonePayeeLocation
import io.github.traushb.actuali.services.database.transferAccountId
import io.github.traushb.actuali.services.database.updateTag
import io.github.traushb.actuali.services.database.updateTransaction
import io.github.traushb.actuali.services.database.updateTransactionWithMessages
import io.github.traushb.actuali.services.database.updateTransactionsWithMessages
import io.github.traushb.actuali.services.database.validateCategoryGroupRename
import io.github.traushb.actuali.services.database.validateCategoryRename
import io.github.traushb.actuali.services.database.writeScheduleJSONPaths
import io.github.traushb.actuali.services.database.zeroBudgetMonthsTableExists
import io.github.traushb.actuali.services.diagnostics.DiagnosticLog
import io.github.traushb.actuali.services.network.ActualServerClient
import io.github.traushb.actuali.services.rules.RuleContext
import io.github.traushb.actuali.services.rules.RulesEngine
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleConditions
import io.github.traushb.actuali.services.schedules.SchedulePostingActions
import io.github.traushb.actuali.services.schedules.ScheduleFormFields
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import io.github.traushb.actuali.services.schedules.ScheduleWriteBuilder
import io.github.traushb.actuali.services.schedules.ScheduleWriteError
import io.github.traushb.actuali.services.schedules.ScheduleWritePlan
import io.github.traushb.actuali.services.schedules.scheduledTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

private val logger = Logger("SyncClient")

sealed class SyncError : Exception() {
    object NotConfigured : SyncError() {
        override val message: String get() = L("Sync isn't configured. Open a budget first.")
    }

    object Offline : SyncError() {
        override val message: String get() = L("You're offline. Sync will resume automatically.")
    }

    object OutOfSync : SyncError() {
        override val message: String
            get() = L("Local data has drifted from the server and couldn't reconcile after several attempts. Tap \"Reset Sync State\" below to recover.")
    }

    object EncodingFailed : SyncError() {
        override val message: String get() = L("Failed to encode the sync request.")
    }

    data class ServerError(val detail: String) : SyncError() {
        override val message: String get() = L("Server error: %@", detail)
    }

    object BudgetTableMissing : SyncError() {
        override val message: String get() = L("This budget file has no budget table to write to.")
    }

    object NotesTableMissing : SyncError() {
        override val message: String get() = L("This budget file has no notes table to write to.")
    }

    object RulesTableMissing : SyncError() {
        override val message: String get() = L("This budget file has no rules table to write to.")
    }

    object TagAlreadyExists : SyncError() {
        // Reuses the existing `error.tagAlreadyExists` catalog entry.
        override val message: String get() = L("error.tagAlreadyExists")
    }
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data object Offline : SyncState
    data class Error(val message: String) : SyncState
}

/**
 * Main sync orchestrator.
 *
 * Every public method runs on [actor], a dispatcher that runs one coroutine
 * at a time — the Swift actor's isolation. State is touched only between
 * suspension points, and a suspension (a network round trip, the clock's
 * lock) lets other calls run: a local write proceeds while a sync is waiting
 * on the server, exactly as the iOS actor's reentrancy allows (issue #125).
 */
class SyncClient(
    private val serverClient: ActualServerClient,
    nodeId: String? = null,
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.shared,
    /**
     * How long after a successful sync a pull-only [automaticSync] is skipped.
     * Injectable so tests aren't racing the wall clock on a slow runner.
     */
    private val rateLimitWindowMillis: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) : SchedulePostingActions {
    private val actor = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actor)

    private var diagnosticSession: UUID? = null
    private var database: BudgetDatabase? = null
    private val clock = HybridLogicalClock(nodeId)
    private val messageGenerator = MessageGenerator(clock)

    private var merkle = MerkleTree()
    private var encoder = SyncEncoder()
    private var syncTask: Job? = null

    /**
     * Serializes foreground, retry, and write-triggered syncs so only one full
     * sync owns the diagnostic attempt summary and database at a time.
     */
    private var activeSyncTask: Deferred<Boolean>? = null
    private var activeSyncID: UUID? = null
    private var retryDelayMillis = 5000L
    private val maxRetryDelayMillis = 300_000L

    /** The push kicked off by the most recent local write; null when no push is in flight. */
    private var pushTask: Job? = null

    /** Set when a write lands while [pushTask] is running, so its messages get a trailing push. */
    private var pushNeededAfterCurrent = false

    private var fileId: String? = null
    private var groupId: String? = null
    private var encryptKeyId: String? = null
    private var lastSyncedTimestamp: String? = null
    private var lastSuccessfulSyncTime: Long? = null

    /**
     * The server's message high-water mark at budget-load time (max timestamp
     * in `messages_crdt` when [configure] ran, before any local writes). On a
     * fresh download [lastSyncedTimestamp] is null, so this is the floor for
     * deciding which local messages are genuine post-download writes that must
     * be pushed — without it, the first local write is stranded.
     */
    private var downloadBaselineTimestamp: String? = null

    /**
     * Whether the merkle has been re-derived from `messages_crdt` this session.
     * See the top of [fullSync] for why that must happen before the first
     * comparison against the server's tree.
     */
    private var hasDerivedMerkleFromLog = false

    /**
     * This device's HLC node id — the suffix stamped on every message this
     * client authors. NewTransactionDetector uses it to skip local writes.
     */
    val nodeId: String get() = clock.node

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private suspend fun <T> onActor(block: suspend () -> T): T = withContext(actor) { block() }

    private fun requireDatabase(): BudgetDatabase = database ?: throw SyncError.NotConfigured

    // Configuration

    suspend fun configure(
        database: BudgetDatabase,
        fileId: String,
        groupId: String,
        encryptionKey: SymmetricKey? = null,
        keyId: String? = null,
    ) = onActor {
        this.database = database
        this.fileId = fileId
        this.groupId = groupId
        this.encryptKeyId = keyId
        encoder = SyncEncoder(encryptionKey)
        diagnosticSession = diagnosticLog.currentSessionID()
        diagnosticLog.recordSyncConfiguration(diagnosticSession)

        // Load saved clock state
        database.loadClock()?.let { clockRecord ->
            merkle = MerkleTree(clockRecord.merkle)
            // Only set lastSyncedTimestamp if it's valid (non-empty and not epoch)
            if (clockRecord.timestamp.isNotEmpty() && !clockRecord.timestamp.startsWith("1970-")) {
                lastSyncedTimestamp = clockRecord.timestamp
            } else {
                // Recover from invalid/legacy state by taking the high-water mark
                // of messages_crdt. The downloaded budget already contains all of
                // the server's messages, so any new local writes will have
                // timestamps strictly greater than this and are the only thing
                // we should be pushing on the next sync.
                val recovered = runCatching { database.getMaxMessageTimestamp() }.getOrNull()
                if (!recovered.isNullOrEmpty() && !recovered.startsWith("1970-")) {
                    lastSyncedTimestamp = recovered
                    logger.notice("Recovered lastSyncedTimestamp from messages_crdt: $recovered")
                } else {
                    // Nothing trustworthy to recover from (empty budget): leave
                    // null — fullSync syncs from the snapshot's floor.
                    lastSyncedTimestamp = null
                    logger.notice("No recoverable lastSyncedTimestamp - deferring to first sync")
                }
            }
            logger.info("Loaded clock - merkle hash: ${merkle.root.hash}, lastSynced: ${lastSyncedTimestamp ?: "nil"}")
        }

        // Restore the HLC so it is never behind the persisted sync state or the
        // local message high-water mark (mirrors upstream setClock on budget
        // load).
        val maxMessageTimestamp = try {
            database.getMaxMessageTimestamp()
        } catch (e: Exception) {
            logger.warning("Failed to read max message timestamp for HLC restore: $e")
            null
        }
        // The server's state at load is the baseline for the fresh-download
        // sync path (no local writes have happened yet at configure time).
        downloadBaselineTimestamp = maxMessageTimestamp
        for (candidate in listOf(lastSyncedTimestamp, maxMessageTimestamp)) {
            candidate?.let(HLCTimestamp::parse)?.let { clock.advance(it) }
        }
    }

    /**
     * Everything a rules pass needs, fetched once. The import path builds this
     * before its loop instead of paying for a full categories/payees/accounts
     * scan per transaction.
     */
    data class PreparedRules(val rules: List<Rule>, val context: RuleContext, val fingerprint: BankSyncRulesFingerprint)

    sealed interface TransactionCreateResult {
        data class Inserted(val id: String) : TransactionCreateResult
        data object Duplicate : TransactionCreateResult
        data object SuppressedByRule : TransactionCreateResult
    }

    suspend fun prepareRules(): PreparedRules = onActor { prepareRulesOnActor() }

    private fun prepareRulesOnActor(): PreparedRules {
        val database = database ?: return PreparedRules(emptyList(), RuleContext.empty, BankSyncRulesFingerprint.empty)
        val snapshot = database.prepareRulesSnapshot()
        return PreparedRules(snapshot.rules, snapshot.context, snapshot.fingerprint)
    }

    /** Folds newly stored messages into the trie, prunes, and persists the clock. */
    private fun commitMessages(messages: List<CRDTMessage>) {
        for (message in messages) merkle = merkle.inserting(message.timestamp)
        merkle = merkle.pruned()
        saveClock()
    }

    // Public API

    /**
     * Create a transaction (optimistic local-first). `applyRules = false`
     * skips the rules pass — used for split children, whose every field the
     * caller spelled out explicitly.
     */
    suspend fun createTransaction(
        transaction: Transaction,
        applyRules: Boolean = true,
        prepared: PreparedRules? = null,
        preserveCategory: Boolean = false,
    ): TransactionCreateResult = onActor {
        createTransactionOnActor(transaction, applyRules, prepared, preserveCategory, FinancialIdPolicy.Unique, resolveOriginalBankPayee = false)
    }

    /** [SchedulePostingActions]: scheduled posts go through the rules pass, as loot-core's do. */
    override suspend fun createTransaction(transaction: Transaction) {
        createTransaction(transaction, applyRules = true)
    }

    suspend fun createBankSyncTransaction(
        transaction: Transaction,
        maxLiveFinancialIdOccurrences: Int,
        prepared: PreparedRules,
        expectedLink: ExpectedBankSyncLink,
    ): TransactionCreateResult = onActor {
        require(maxLiveFinancialIdOccurrences > 0)
        createTransactionOnActor(
            transaction, applyRules = true, prepared = prepared, preserveCategory = false,
            financialIdPolicy = FinancialIdPolicy.Occurrences(maxLiveFinancialIdOccurrences),
            resolveOriginalBankPayee = true,
            expectedLink = expectedLink,
        )
    }

    data class PreparedBankSyncTransaction(
        val transaction: Transaction,
        val messages: List<CRDTMessage>,
        val pendingPayees: List<Payee>,
        val pendingPayeesByName: Map<String, Payee>,
    )

    suspend fun prepareBankSyncTransaction(
        transaction: Transaction,
        prepared: PreparedRules,
        pendingPayeesByName: Map<String, Payee> = emptyMap(),
    ): PreparedBankSyncTransaction? = onActor {
        requireDatabase()
        val pendingPayees = mutableListOf<Payee>()
        val byName = pendingPayeesByName.toMutableMap()
        val result = RulesEngine.apply(transaction, prepared.rules, prepared.context)
        if (result.isDeleted) return@onActor null
        var finalTransaction = result.transaction
        val pendingPayeeName = result.pendingPayeeName
        val originalPayeeName = transaction.payeeName
        if (pendingPayeeName != null) {
            finalTransaction = finalTransaction.copy(payeeId = resolvePayee(pendingPayeeName, true, pendingPayees, byName))
        } else if ("payee" !in result.changedFields && "payee_name" !in result.changedFields &&
            finalTransaction.payeeId == null && originalPayeeName != null
        ) {
            finalTransaction = finalTransaction.copy(payeeId = resolvePayee(originalPayeeName, true, pendingPayees, byName))
        }
        val messages = messageGenerator.messagesForInsert(finalTransaction).toMutableList()
        for (payee in pendingPayees) {
            messages += messageGenerator.messagesForInsert(payee)
            messages += messageGenerator.messagesForInsert(PayeeMapping(payee.id, payee.id))
        }
        PreparedBankSyncTransaction(finalTransaction, messages, pendingPayees, byName)
    }

    suspend fun prepareBankSyncOpeningInsert(transaction: Transaction, payee: Payee, expectedInsertedIds: Set<String>): BankSyncOpeningInsert =
        onActor {
            val messages = messageGenerator.messagesForInsert(payee) +
                messageGenerator.messagesForInsert(PayeeMapping(payee.id, payee.id)) +
                messageGenerator.messagesForInsert(transaction)
            BankSyncOpeningInsert(transaction, payee, messages, expectedInsertedIds)
        }

    suspend fun prepareBankSyncOpeningUpdate(transaction: Transaction, expectedAmount: Long, expectedInsertedIds: Set<String>): BankSyncOpeningUpdate =
        onActor {
            BankSyncOpeningUpdate(expectedAmount, transaction, messageGenerator.messagesForUpdate(transaction, setOf("amount")), expectedInsertedIds)
        }

    private sealed interface FinancialIdPolicy {
        data object Unique : FinancialIdPolicy
        data class Occurrences(val limit: Int) : FinancialIdPolicy
    }

    private suspend fun createTransactionOnActor(
        transaction: Transaction,
        applyRules: Boolean,
        prepared: PreparedRules?,
        preserveCategory: Boolean,
        financialIdPolicy: FinancialIdPolicy,
        resolveOriginalBankPayee: Boolean,
        expectedLink: ExpectedBankSyncLink? = null,
    ): TransactionCreateResult {
        val database = requireDatabase()
        logger.debug("createTransaction() - id: ${transaction.id}")

        // 0. Apply user-defined rules before insert. Skip for transfers — our
        //    transfer flow already builds both legs explicitly and we don't
        //    want rules rewriting the linked payee/account.
        var finalTransaction = transaction
        val pendingPayees = mutableListOf<Payee>()
        val pendingPayeesByName = mutableMapOf<String, Payee>()
        if (applyRules && transaction.transferId == null) {
            val preparedRules = prepared ?: prepareRulesOnActor()
            val result = RulesEngine.apply(transaction, preparedRules.rules, preparedRules.context)
            if (result.isDeleted) {
                // A `delete-transaction` rule matched. Upstream tombstones the
                // row; for a transaction that doesn't exist yet, not creating
                // it is the same outcome with less to sync.
                logger.notice("Rules deleted the incoming transaction — skipping insert")
                return TransactionCreateResult.SuppressedByRule
            }
            finalTransaction = result.transaction
            if (preserveCategory && transaction.categoryId != null) {
                finalTransaction = finalTransaction.copy(categoryId = transaction.categoryId)
            }
            val pendingPayeeName = result.pendingPayeeName
            val originalPayeeName = transaction.payeeName
            if (pendingPayeeName != null) {
                finalTransaction = finalTransaction.copy(
                    payeeId = resolvePayee(pendingPayeeName, transaction.financialId != null, pendingPayees, pendingPayeesByName),
                )
            } else if (resolveOriginalBankPayee && "payee" !in result.changedFields && "payee_name" !in result.changedFields &&
                finalTransaction.payeeId == null && originalPayeeName != null
            ) {
                finalTransaction = finalTransaction.copy(
                    payeeId = resolvePayee(originalPayeeName, transaction.financialId != null, pendingPayees, pendingPayeesByName),
                )
            }
            if (result.changedFields.isNotEmpty()) logger.info("Rules updated ${result.changedFields.size} field(s) on new transaction")
        }

        // 1. Generate CRDT messages before persistence so a generation failure
        // cannot leave a financial-id row that looks like a completed import.
        val messages = messageGenerator.messagesForInsert(finalTransaction).toMutableList()
        for (payee in pendingPayees) {
            messages += messageGenerator.messagesForInsert(payee)
            messages += messageGenerator.messagesForInsert(PayeeMapping(payee.id, payee.id))
        }

        // 2. Store the row and messages atomically. Financial-id retries can
        // repair a deterministic row that has no messages yet.
        val insertedMessages = if (finalTransaction.financialId != null) {
            when (financialIdPolicy) {
                FinancialIdPolicy.Unique -> database.insertTransactionWithMessages(finalTransaction, messages, pendingPayees)
                is FinancialIdPolicy.Occurrences -> database.insertBankSyncTransactionWithMessages(
                    finalTransaction, messages, financialIdPolicy.limit,
                    expectedLink ?: throw BankSyncDatabaseError.BankSyncMaterializationStale,
                    pendingPayees,
                )
            }
        } else {
            database.insertTransaction(finalTransaction)
            database.insertMessages(messages)
        }
        if (insertedMessages.isEmpty()) return TransactionCreateResult.Duplicate

        // 3. Update merkle
        commitMessages(insertedMessages)

        // 4. Push to the server in the background (never blocks the caller)
        scheduleAutomaticSync()
        return TransactionCreateResult.Inserted(finalTransaction.id)
    }

    /**
     * Create both legs of a transfer atomically (optimistic local-first). The
     * two rows and all of their CRDT messages commit in a single SQLite
     * transaction, so a failure on either leg leaves no orphaned half-transfer.
     */
    override suspend fun createTransfer(source: Transaction, target: Transaction) = onActor {
        val database = requireDatabase()
        val messages = messageGenerator.messagesForInsert(source) + messageGenerator.messagesForInsert(target)
        commitMessages(database.insertTransfer(source, target, messages))
        scheduleAutomaticSync()
    }

    /**
     * Turn an existing transaction into one leg of a transfer and create its
     * partner atomically — an update that landed without its partner would
     * leave `transferred_id` dangling, and push that to every other device.
     */
    suspend fun convertToTransfer(leg: Transaction, changedFields: Set<String>, partner: Transaction) = onActor {
        val database = requireDatabase()
        val messages = messageGenerator.messagesForUpdate(leg, changedFields) + messageGenerator.messagesForInsert(partner)
        commitMessages(database.convertToTransfer(leg, partner, messages))
        scheduleAutomaticSync()
    }

    /**
     * Create a split parent and its children atomically (optimistic
     * local-first). Rules are skipped — the caller builds every row explicitly.
     */
    suspend fun createSplit(parent: Transaction, children: List<Transaction>, transferPartners: List<Transaction> = emptyList()) = onActor {
        val database = requireDatabase()
        val messages = mutableListOf<CRDTMessage>()
        messages += messageGenerator.messagesForInsert(parent)
        for (child in children) messages += messageGenerator.messagesForInsert(child)
        for (partner in transferPartners) messages += messageGenerator.messagesForInsert(partner)
        commitMessages(database.insertSplit(parent, children, transferPartners, messages))
        scheduleAutomaticSync()
    }

    /**
     * Update an existing transaction (optimistic local-first).
     * @param changedFields the CRDT column names that changed ("amount", "date", "category"…)
     */
    suspend fun updateTransaction(transaction: Transaction, changedFields: Set<String>) = onActor {
        val database = requireDatabase()
        if (changedFields.isEmpty()) {
            database.updateTransaction(transaction)
            logger.debug("No changed fields - updated local-only fields")
            return@onActor
        }
        // The database commits the row and messages together so a message
        // failure cannot strand a local-only edit.
        val messages = messageGenerator.messagesForUpdate(transaction, changedFields)
        commitMessages(database.updateTransactionWithMessages(transaction, messages))
        scheduleAutomaticSync()
    }

    /**
     * Update many transactions that share one set of changed fields, in a
     * single pass: one merkle/clock save and one sync for the whole batch.
     * Mirrors upstream's transactions-batch-update.
     */
    suspend fun updateTransactions(transactions: List<Transaction>, changedFields: Set<String>) =
        updateTransactions(transactions.map { it to changedFields })

    /**
     * Update several transaction rows, each with its own changed fields, as one
     * atomic write — for edits that touch rows differently, like a merge.
     */
    suspend fun updateTransactions(updates: List<Pair<Transaction, Set<String>>>) = onActor {
        val database = requireDatabase()
        if (updates.isEmpty()) return@onActor
        val prepared = updates.map { (transaction, fields) ->
            transaction to if (fields.isEmpty()) emptyList() else messageGenerator.messagesForUpdate(transaction, fields)
        }
        commitMessages(database.updateTransactionsWithMessages(prepared))
        scheduleAutomaticSync()
    }

    /** Create a payee (optimistic local-first), with its payee_mapping row. */
    suspend fun createPayee(payee: Payee) = onActor { createPayeeOnActor(payee) }

    private suspend fun createPayeeOnActor(payee: Payee) {
        val database = requireDatabase()
        database.insertPayee(payee)
        val messages = messageGenerator.messagesForInsert(payee) + messageGenerator.messagesForInsert(PayeeMapping(payee.id, payee.id))
        commitMessages(database.insertMessages(messages))
        // Don't schedule sync here - let the transaction sync handle it.
    }

    /**
     * Turn a `payee_name` a rule set into a payee id, creating the payee when
     * it's new — upstream `resolvePayeeNameForRules`.
     */
    private suspend fun resolvePayee(
        name: String,
        deferCreation: Boolean,
        pendingPayees: MutableList<Payee>,
        pendingPayeesByName: MutableMap<String, Payee>,
    ): String? {
        val database = requireDatabase()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val key = trimmed.lowercase()
        pendingPayeesByName[key]?.let {
            pendingPayees += it
            return it.id
        }
        database.payee(trimmed)?.let { return it.id }
        val payee = Payee(UUID.randomUUID().toString(), trimmed, null)
        if (deferCreation) {
            pendingPayeesByName[key] = payee
            pendingPayees += payee
        } else {
            createPayeeOnActor(payee)
        }
        return payee.id
    }

    /**
     * Create a new account, its transfer payee (the empty-named payee every
     * transfer to or from the account resolves through — created here and
     * nowhere else, matching the PWA), and, if given, its opening-balance
     * transaction — atomically.
     */
    suspend fun createAccount(account: Account, transferPayee: Payee, startingBalanceTransaction: Transaction?) {
        onActor {
            val database = requireDatabase()
            database.insertAccount(account, transferPayee, startingBalanceTransaction)
            val messages = mutableListOf<CRDTMessage>()
            messages += messageGenerator.messagesForInsert(account)
            messages += messageGenerator.messagesForInsert(transferPayee)
            messages += messageGenerator.messagesForInsert(PayeeMapping(transferPayee.id, transferPayee.id))
            startingBalanceTransaction?.let { messages += messageGenerator.messagesForInsert(it) }
            commitMessages(database.insertMessages(messages))
        }
        // Sync to push the new account to the server (rate-limited)
        automaticSync()
    }

    // Bank Sync

    /**
     * Point an account at a provider's account (optimistic local-first),
     * writing the same three columns the web UI's own link writes —
     * `account_id`, `account_sync_source`, `bank` — plus the institution row
     * `bank` points at, reusing an existing row for the institution
     * (upstream `findOrCreateBank`).
     */
    suspend fun linkAccount(
        accountId: String,
        externalAccountId: String,
        source: BankSyncSource,
        institutionId: String,
        institutionName: String,
        expectedOldLink: ExpectedBankSyncLink? = null,
        verifyExpectedOldLink: Boolean = false,
    ) {
        onActor {
            val database = requireDatabase()
            val proposedBank = Bank(UUID.randomUUID().toString(), institutionId, institutionName)
            for (attempt in 0 until 2) {
                val link = database.proposeBankSyncLink(proposedBank)
                val messages = messageGenerator.messages(
                    "accounts", accountId,
                    listOf("account_id" to externalAccountId, "account_sync_source" to source.rawValue, "bank" to link.bank.id),
                ).toMutableList()
                if (link.created) {
                    messages += messageGenerator.messagesForInsert(link.bank)
                } else if (link.revived) {
                    messages += messageGenerator.messagesForUpdate(link.bank, setOf("bank_id", "name", "tombstone"))
                }
                try {
                    for (msg in database.applyBankSyncLink(accountId, externalAccountId, source.rawValue, link, expectedOldLink, verifyExpectedOldLink, messages)) {
                        merkle = merkle.inserting(msg.timestamp)
                    }
                    break
                } catch (e: BankSyncDatabaseError.BankSyncLinkChanged) {
                    if (attempt != 0) throw e
                }
            }
            merkle = merkle.pruned()
            saveClock()
        }
        automaticSync()
    }

    /**
     * Cut an account loose from its bank feed. The transactions it already
     * imported stay — only the link goes, matching the web UI's unlink, which
     * clears the cached balances and the status badge along with the pointer.
     */
    suspend fun unlinkAccount(accountId: String, expectedLink: ExpectedBankSyncLink) {
        onActor {
            val database = requireDatabase()
            val messages = messageGenerator.messages(
                "accounts", accountId,
                listOf(
                    "account_id" to null, "account_sync_source" to null, "bank" to null,
                    "balance_current" to null, "balance_available" to null, "balance_limit" to null,
                    "bank_sync_status" to null,
                ),
            )
            commitMessages(database.applyBankSyncUnlink(accountId, expectedLink, messages))
        }
        automaticSync()
    }

    data class BankSyncStatusUpdate(val accountId: String, val lastSync: String?, val status: String, val expectedLink: ExpectedBankSyncLink)

    /**
     * Record what a bank sync did on each account it touched — `last_sync` and
     * `bank_sync_status`, the two columns every Actual client stamps. Callers
     * pass `lastSync` only for completed downloads, following upstream
     * `handleSyncResponse` and `persistBankSyncError`.
     */
    suspend fun recordBankSyncStatus(statuses: List<BankSyncStatusUpdate>) = onActor {
        val database = requireDatabase()
        if (statuses.isEmpty()) return@onActor
        val entries = mutableListOf<BankSyncStatusEntry>()
        for (entry in statuses) {
            if (entry.accountId != entry.expectedLink.accountId) continue
            val fields = mutableListOf<Pair<String, Any?>>("bank_sync_status" to entry.status)
            // Only stamp last_sync when there was a sync to stamp.
            entry.lastSync?.let { fields += "last_sync" to it }
            entries += BankSyncStatusEntry(entry.accountId, entry.lastSync, entry.status, entry.expectedLink, messageGenerator.messages("accounts", entry.accountId, fields))
        }
        commitMessages(database.applyBankSyncStatus(entries))
        scheduleAutomaticSync()
    }

    private suspend fun bankSyncUpdateMessages(updates: List<BankSyncUpdate>): List<CRDTMessage> {
        val messages = mutableListOf<CRDTMessage>()
        for (update in updates) {
            messages += messageGenerator.messages(
                "transactions", update.existingId,
                listOf(
                    "financial_id" to update.importedId,
                    "description" to update.payeeId,
                    "imported_description" to update.importedPayee,
                    "notes" to update.notes,
                    "cleared" to if (update.cleared) 1 else 0,
                ),
            )
        }
        return messages
    }

    /**
     * Fold a bank download into the transactions it matched (optimistic
     * local-first). One merkle/clock save and one sync for the whole batch.
     */
    suspend fun applyBankSyncUpdates(updates: List<BankSyncUpdate>, expectedLink: ExpectedBankSyncLink): Int = onActor {
        val database = requireDatabase()
        if (updates.isEmpty()) return@onActor 0
        val result = database.applyBankSyncUpdates(updates, expectedLink, bankSyncUpdateMessages(updates))
        for (msg in result.messages) merkle = merkle.inserting(msg.timestamp)
        if (result.updatedCount <= 0) return@onActor 0
        merkle = merkle.pruned()
        saveClock()
        scheduleAutomaticSync()
        result.updatedCount
    }

    suspend fun materializeBankSync(
        updates: List<BankSyncUpdate>,
        inserts: List<PreparedBankSyncInsert>,
        openingInsert: BankSyncOpeningInsert?,
        openingUpdate: BankSyncOpeningUpdate?,
        expectedLink: ExpectedBankSyncLink,
        preparedRulesFingerprint: BankSyncRulesFingerprint,
    ): BankSyncMaterialization = onActor {
        val database = requireDatabase()
        val result = database.materializeBankSync(
            updates, bankSyncUpdateMessages(updates), inserts, openingInsert, openingUpdate, expectedLink, preparedRulesFingerprint,
        )
        for (msg in result.messages) merkle = merkle.inserting(msg.timestamp)
        if (result.messages.isNotEmpty()) {
            merkle = merkle.pruned()
            saveClock()
            scheduleAutomaticSync()
        }
        result
    }

    /**
     * Create a category group (optimistic local-first). Placement, the
     * duplicate-name check and the row itself are the database's job — this
     * turns what it wrote into CRDT messages.
     */
    suspend fun createCategoryGroup(id: String, name: String): CategoryGroup {
        val group = onActor {
            val database = requireDatabase()
            val group = database.insertCategoryGroup(id, name)
            commitMessages(database.insertMessages(messageGenerator.messagesForInsert(group)))
            group
        }
        automaticSync()
        return group
    }

    /**
     * Create a category in a group (optimistic local-first), together with the
     * `category_mapping` row upstream pairs with every category and the
     * sort_order updates its placement shoved onto the group's other
     * categories.
     */
    suspend fun createCategory(id: String, name: String, categoryGroupId: String): Category {
        val category = onActor {
            val database = requireDatabase()
            val insertion = database.insertCategory(id, name, categoryGroupId)
            val messages = mutableListOf<CRDTMessage>()
            messages += messageGenerator.messagesForInsert(insertion.category)
            messages += messageGenerator.messagesForInsert(CategoryMapping(insertion.category.id, insertion.category.id))
            // A sort_order message per sibling the shove displaced, matching the
            // `update('categories', ...)` calls upstream makes in the same batch.
            for (sibling in insertion.movedSiblings) {
                messages += messageGenerator.messages(Category.DATASET_NAME, sibling.id, listOf("sort_order" to sibling.sortOrder))
            }
            commitMessages(database.insertMessages(messages))
            insertion.category
        }
        automaticSync()
        return category
    }

    /**
     * Move a category into a group, before [targetId] (last when null), the
     * way upstream's `category/move` does: a sort_order message per displaced
     * sibling, then the category's own `cat_group` and `sort_order`.
     */
    suspend fun moveCategory(id: String, categoryGroupId: String, targetId: String?) = onActor {
        val database = requireDatabase()
        val move = database.moveCategory(id, categoryGroupId, targetId)
        val messages = mutableListOf<CRDTMessage>()
        for (sibling in move.movedSiblings) {
            messages += messageGenerator.messages(Category.DATASET_NAME, sibling.id, listOf("sort_order" to sibling.sortOrder))
        }
        messages += messageGenerator.messages(Category.DATASET_NAME, move.id, listOf("sort_order" to move.sortOrder, "cat_group" to move.groupId))
        commitMessages(database.insertMessages(messages))
        // Push after the local save so an unreachable server cannot hold the drag.
        scheduleAutomaticSync()
    }

    /**
     * Move a category group before [targetId] (last when null), the way
     * upstream's `category-group/move` does.
     */
    suspend fun moveCategoryGroup(id: String, targetId: String?) = onActor {
        val database = requireDatabase()
        val move = database.moveCategoryGroup(id, targetId)
        val messages = mutableListOf<CRDTMessage>()
        for (sibling in move.movedSiblings) {
            messages += messageGenerator.messages(CategoryGroup.DATASET_NAME, sibling.id, listOf("sort_order" to sibling.sortOrder))
        }
        messages += messageGenerator.messages(CategoryGroup.DATASET_NAME, move.id, listOf("sort_order" to move.sortOrder))
        commitMessages(database.insertMessages(messages))
        scheduleAutomaticSync()
    }

    /**
     * Rename a category through the normal CRDT path so the local optimistic
     * edit and every synced client converge on the same name.
     */
    suspend fun renameCategory(id: String, name: String) {
        onActor {
            requireDatabase().validateCategoryRename(id, name)
            writeFields(Category.DATASET_NAME, id, listOf("name" to name))
        }
        automaticSync()
    }

    /** Rename a category group through the same optimistic CRDT path. */
    suspend fun renameCategoryGroup(id: String, name: String) {
        onActor {
            requireDatabase().validateCategoryGroupRename(id, name)
            writeFields(CategoryGroup.DATASET_NAME, id, listOf("name" to name))
        }
        automaticSync()
    }

    suspend fun setCategoryHidden(id: String, hidden: Boolean) = setHidden(Category.DATASET_NAME, id, hidden)

    suspend fun setCategoryGroupHidden(id: String, hidden: Boolean) = setHidden(CategoryGroup.DATASET_NAME, id, hidden)

    private suspend fun setHidden(dataset: String, id: String, hidden: Boolean) {
        onActor { writeFields(dataset, id, listOf("hidden" to if (hidden) 1 else 0)) }
        automaticSync()
    }

    /**
     * Generate messages for a raw cell write, apply them locally through the
     * same LWW upsert incoming messages use (so a local edit and the identical
     * edit arriving from another device converge byte-for-byte), and store them.
     */
    private suspend fun writeFields(dataset: String, row: String, fields: List<Pair<String, Any?>>) {
        val database = requireDatabase()
        // Messages first, before any DB write, so an HLC failure leaves nothing stranded.
        val messages = messageGenerator.messages(dataset, row, fields)
        commitMessages(database.applyMessagesAndInsertMessages(messages))
    }

    /**
     * Record a location for a payee (optimistic local-first). Callers are
     * responsible for the server-version guard and 500 m dedupe.
     */
    suspend fun createPayeeLocation(location: PayeeLocation) = onActor {
        val database = requireDatabase()
        database.insertPayeeLocation(location)
        commitMessages(database.insertMessages(messageGenerator.messagesForInsert(location)))
        scheduleAutomaticSync()
    }

    /** Tombstone a recorded payee location (optimistic local-first). */
    suspend fun deletePayeeLocation(location: PayeeLocation) = deletePayeeLocations(listOf(location))

    /**
     * Tombstone several recorded payee locations at once ("Clear All
     * Locations"): one tombstone message per location, but a single sync.
     */
    suspend fun deletePayeeLocations(locations: List<PayeeLocation>) = onActor {
        val database = requireDatabase()
        if (locations.isEmpty()) return@onActor
        for (location in locations) database.tombstonePayeeLocation(location.id)
        val messages = locations.map { messageGenerator.messageForDelete(it) }
        commitMessages(database.insertMessages(messages))
        scheduleAutomaticSync()
    }

    // Synced Preferences

    /**
     * Generic writer for any key in Actual's `preferences` table: emits CRDT
     * messages, applies locally, updates the Merkle tree, and pushes in the
     * background. A null value clears the preference.
     */
    suspend fun setPreference(key: String, value: String?) = onActor {
        writeFields("preferences", key, listOf("value" to value))
        scheduleAutomaticSync()
    }

    /**
     * Persist the currency code to the budget's `preferences` table (Actual's
     * `defaultCurrencyCode`), so the choice syncs to other clients (GH #59).
     */
    suspend fun updateCurrencyCode(code: String) = setPreference("defaultCurrencyCode", code)

    /**
     * Persists or clears a credit card account configuration under
     * `actuali:credit_card:<accountId>`. Actual's `preferences` table treats
     * unknown keys as passthrough synced items without client-side effects.
     */
    suspend fun setCreditCardConfig(accountId: String, config: CreditCardConfig?) =
        setEncodedPreference(PreferenceKeys.creditCard(accountId), config, CreditCardConfig.serializer())

    /** Persists or clears a loan account configuration under `actuali:loan:<accountId>`. */
    suspend fun setLoanConfig(accountId: String, config: LoanConfig?) = setEncodedPreference(PreferenceKeys.loan(accountId), config, LoanConfig.serializer())

    /** Persists or clears a deposit account configuration under `actuali:deposit:<accountId>`. */
    suspend fun setDepositConfig(accountId: String, config: DepositConfig?) =
        setEncodedPreference(PreferenceKeys.deposit(accountId), config, DepositConfig.serializer())

    private suspend fun <T> setEncodedPreference(key: String, config: T?, serializer: KSerializer<T>) =
        setPreference(key, config?.let { AppJson.encodeToString(serializer, it) })

    /**
     * Persists changed card-to-account mappings one per preference row, so
     * concurrent edits to different keywords converge instead of replacing
     * the whole dictionary.
     */
    suspend fun setCardAccountMappings(mappings: Map<String, String>, previous: Map<String, String>) = onActor {
        val database = requireDatabase()
        val changedKeywords = (mappings.keys + previous.keys).filter { mappings[it] != previous[it] }.sorted()
        if (changedKeywords.isEmpty()) return@onActor
        val messages = mutableListOf<CRDTMessage>()
        for (keyword in changedKeywords) {
            messages += messageGenerator.messages("preferences", PreferenceKeys.cardMapping(keyword), listOf("value" to mappings[keyword]))
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    /** A budget cell's write fields: its month/category identity when new, then [fields]. */
    private fun cellFields(cellExists: Boolean, monthInt: Int, categoryId: String, fields: List<Pair<String, Any?>>): List<Pair<String, Any?>> =
        if (cellExists) fields else listOf("month" to monthInt, "category" to categoryId) + fields

    /**
     * Set the budgeted amount for a category in a month (optimistic
     * local-first). Mirrors upstream setBudget: update the existing (month,
     * category) row's amount, or create the row with the {YYYYMM}-{categoryId}
     * id and its month/category columns.
     */
    suspend fun setBudgetAmount(month: String, categoryId: String, amount: Long) = onActor {
        val database = requireDatabase()
        val cell = database.budgetCell(month, categoryId) ?: throw SyncError.BudgetTableMissing
        writeFields(cell.table, cell.rowId, cellFields(cell.exists, cell.monthInt, categoryId, listOf("amount" to amount)))
        scheduleAutomaticSync()
    }

    /**
     * Write Actual's synced manual next-month buffer — upstream `setBuffer`,
     * which updates or inserts `zero_budget_months`.
     */
    suspend fun setBudgetBuffer(month: String, amount: Long) = onActor {
        val database = requireDatabase()
        if (amount < 0) throw SyncError.ServerError(L("error.invalidAmount"))
        if (!database.zeroBudgetMonthsTableExists()) throw SyncError.BudgetTableMissing
        writeFields("zero_budget_months", month, listOf("buffered" to amount))
        scheduleAutomaticSync()
    }

    /**
     * Move budgeted funds between two categories in a month, or between a
     * category and "To Budget" (null side), optimistic local-first. Mirrors
     * upstream transferCategory / coverOverspending / transferAvailable: the
     * source cell shrinks, the destination grows, and To Budget — being
     * derived — needs no write. Both cells go out in one message batch.
     */
    suspend fun transferBudget(month: String, fromCategoryId: String?, toCategoryId: String?, amount: Long) = onActor {
        val database = requireDatabase()
        val messages = mutableListOf<CRDTMessage>()
        for ((categoryId, delta) in listOf(fromCategoryId to -amount, toCategoryId to amount)) {
            if (categoryId == null) continue // To Budget side is derived
            val cell = database.budgetCell(month, categoryId) ?: throw SyncError.BudgetTableMissing
            messages += messageGenerator.messages(
                cell.table, cell.rowId, cellFields(cell.exists, cell.monthInt, categoryId, listOf("amount" to cell.amount + delta)),
            )
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    /**
     * Set categories' carryover ("rollover overspending") flags on every month
     * in [months], optimistic local-first. Mirrors upstream
     * setCategoryCarryover: all months go out in one message batch.
     */
    suspend fun setBudgetCarryover(months: List<String>, categoryIds: List<String>, flag: Boolean) = onActor {
        setBudgetCarryoverOnActor(months, categoryIds, flag)
    }

    private suspend fun setBudgetCarryoverOnActor(months: List<String>, categoryIds: List<String>, flag: Boolean) {
        val database = requireDatabase()
        if (months.isEmpty() || categoryIds.isEmpty()) return
        val messages = mutableListOf<CRDTMessage>()
        for (month in months) {
            for (categoryId in categoryIds) {
                val cell = database.budgetCell(month, categoryId) ?: throw SyncError.BudgetTableMissing
                messages += messageGenerator.messages(
                    cell.table, cell.rowId, cellFields(cell.exists, cell.monthInt, categoryId, listOf("carryover" to if (flag) 1 else 0)),
                )
            }
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    suspend fun resetIncomeCarryover(month: String) = onActor {
        setBudgetCarryoverOnActor(listOf(month), requireDatabase().incomeCategoryIds(), false)
    }

    /**
     * Store parsed goal templates into `categories.goal_def` (optimistic
     * local-first). Mirrors upstream `storeTemplates`: the JSON template array
     * plus a `template_settings` source marker, batched. A null goalDef clears
     * the column.
     */
    suspend fun storeGoalDefs(updates: List<Triple<String, String?, String>>) = onActor {
        val database = requireDatabase()
        if (updates.isEmpty()) return@onActor
        val messages = mutableListOf<CRDTMessage>()
        for ((categoryId, goalDef, source) in updates) {
            messages += messageGenerator.messages(
                "categories", categoryId,
                listOf("goal_def" to goalDef, "template_settings" to "{\"source\": \"$source\"}"),
            )
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    /**
     * Store cleanup definitions parsed from notes without disturbing goal
     * definitions or the category's automation source marker.
     */
    suspend fun storeCleanupDefs(updates: List<Pair<String, String?>>) = onActor {
        val database = requireDatabase()
        if (updates.isEmpty()) return@onActor
        val messages = mutableListOf<CRDTMessage>()
        for ((categoryId, cleanupDef) in updates) {
            messages += messageGenerator.messages("categories", categoryId, listOf("cleanup_def" to cleanupDef))
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    /**
     * Store one category's automations from the editor UI — goal_def,
     * cleanup_def and the template_settings source marker in one message batch
     * (upstream `budget/set-category-automations`). Null defs clear the columns.
     */
    suspend fun storeCategoryAutomations(categoryId: String, goalDef: String?, cleanupDef: String?, source: String) = onActor {
        writeFields(
            "categories", categoryId,
            listOf("goal_def" to goalDef, "cleanup_def" to cleanupDef, "template_settings" to "{\"source\": \"$source\"}"),
        )
        scheduleAutomaticSync()
    }

    /**
     * Create (or revive) a cleanup pool row — upstream `resolveCleanupGroup`
     * writes the row through the sync engine so other clients see the pool.
     */
    suspend fun upsertCleanupGroup(id: String, name: String) = onActor {
        writeFields("cleanup_groups", id, listOf("name" to name, "tombstone" to 0))
        scheduleAutomaticSync()
    }

    /**
     * Write the budget amounts and goal columns a template run produced, all
     * in one message batch (upstream's setBudgets + setGoals under a single
     * batchMessages). Both write the same (month, category) row, so the two
     * lists are merged per category — creating the row once when it doesn't exist.
     */
    suspend fun applyGoalTemplateWrites(
        month: String,
        budgets: List<GoalTemplateEngine.BudgetWrite>,
        goals: List<GoalTemplateEngine.GoalWrite>,
        writeFalseLongGoalsAsZero: Boolean = false,
    ) = onActor {
        val database = requireDatabase()
        if (budgets.isEmpty() && goals.isEmpty()) return@onActor
        val budgetsByCategory = budgets.associateBy { it.category }
        val goalsByCategory = goals.associateBy { it.category }
        val messages = mutableListOf<CRDTMessage>()
        for (categoryId in (budgetsByCategory.keys + goalsByCategory.keys).sorted()) {
            val cell = database.budgetCell(month, categoryId) ?: throw SyncError.BudgetTableMissing
            val fields = mutableListOf<Pair<String, Any?>>()
            budgetsByCategory[categoryId]?.let { fields += "amount" to it.amount }
            goalsByCategory[categoryId]?.let { goal ->
                fields += "goal" to goal.goal
                // Goal templates store false as null; cleanup explicitly writes
                // 0 when it resets a drained source's goal.
                fields += "long_goal" to if (goal.longGoal) 1 else if (writeFalseLongGoalsAsZero) 0 else null
            }
            messages += messageGenerator.messages(cell.table, cell.rowId, cellFields(cell.exists, cell.monthInt, categoryId, fields))
        }
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        scheduleAutomaticSync()
    }

    /**
     * Save the note attached to a row (a category, account or month),
     * optimistic local-first. Mirrors upstream notes-save: a single `note`
     * cell on the `notes` table, keyed by the annotated row's own id; the CRDT
     * apply upserts, so a never-annotated row gets its row from this write.
     * Clearing saves an empty string rather than tombstoning, again matching
     * upstream. Requires the `notes` table, or the write would look like it
     * worked while changing nothing.
     */
    suspend fun setNote(id: String, note: String) = onActor {
        if (!requireDatabase().notesTableExists()) throw SyncError.NotesTableMissing
        writeFields("notes", id, listOf("note" to note))
        // Push in the background — the Save button awaits this write, and an
        // unreachable server must not hold the sheet open (issue #125).
        scheduleAutomaticSync()
    }

    /**
     * Create or update a rule (optimistic local-first). Mirrors upstream
     * `rule-add` / `rule-update`: the whole row is written every time, so a
     * rule edited on two devices converges on one client's complete rule.
     */
    suspend fun saveRule(rule: Rule) = onActor {
        val database = requireDatabase()
        if (!database.rulesTableExists()) throw SyncError.RulesTableMissing
        commitMessages(database.applyMessagesAndInsertMessages(messageGenerator.messagesForInsert(rule)))
        scheduleAutomaticSync()
    }

    /**
     * Tombstone a rule (optimistic local-first), upstream `rule-delete`. The
     * caller refuses to delete a schedule's rule.
     */
    suspend fun deleteRule(rule: Rule) = onActor {
        val database = requireDatabase()
        if (!database.rulesTableExists()) throw SyncError.RulesTableMissing
        commitMessages(database.applyMessagesAndInsertMessages(listOf(messageGenerator.messageForDelete(rule))))
        scheduleAutomaticSync()
    }

    /**
     * Advance a schedule's next date after posting (optimistic local-first).
     * Mirrors loot-core setNextDate (non-reset branch): `local_next_date` moves,
     * and `local_next_date_ts` copies the CURRENT `base_next_date_ts` so the
     * local override stays valid until another client resets the base.
     */
    override suspend fun advanceScheduleNextDate(nextDateRowId: String, newNextDate: Int, baseNextDateTs: Long?) = onActor {
        // A NULL base ts serializes as "0:", the null loot-core's setNextDate writes.
        writeFields("schedules_next_date", nextDateRowId, listOf("local_next_date" to newNextDate, "local_next_date_ts" to baseNextDateTs))
        scheduleAutomaticSync()
    }

    /**
     * Skip the current occurrence. Port of loot-core `skipNextDate`: search for
     * the next occurrence starting the day AFTER the current one, and move only
     * the local override.
     */
    suspend fun skipScheduleNextDate(schedule: ScheduleSummary) {
        val currentNextDate = schedule.nextDate
        val condition = schedule.dateCondition as? ScheduleDateCondition.Recurring
        if (currentNextDate == null || condition == null) throw ScheduleWriteError.NotRecurring
        val next = ScheduleRecurrence.nextOccurrence(condition.config, ScheduleRecurrence.skipSearchStart(currentNextDate, condition.config))
        if (next == null || next == currentNextDate) return
        setScheduleNextDate(schedule, next, reset = false)
    }

    /**
     * Post a transaction for a schedule now. Port of loot-core
     * `postTransactionForSchedule`. Deliberately does NOT advance the next date
     * — upstream leaves that to the advance service.
     */
    suspend fun postScheduleTransaction(schedule: ScheduleSummary, today: Boolean) = onActor {
        val accountId = schedule.accountId ?: throw ScheduleWriteError.NoAccount
        val database = requireDatabase()
        val date = if (today) DayDate.today() else (schedule.nextDate ?: DayDate.today())
        var transaction = Transaction(
            id = UUID.randomUUID().toString().lowercase(),
            accountId = accountId,
            date = date.yyyymmdd,
            amount = schedule.postAmount,
            payeeId = schedule.payeeId,
            payeeName = null,
            categoryId = schedule.categoryId,
            categoryName = null,
            notes = null,
            cleared = false,
            reconciled = false,
            transferId = null,
            isParent = false,
            parentId = null,
            tombstone = false,
            sortOrder = null,
            importedPayee = null,
            schedule = schedule.id,
        )
        if (database.transferAccountId(transaction.payeeId) != null) {
            val actionResult = RulesEngine.apply(ScheduleConditions.actions(schedule.actionsJSON), transaction, schedule.id)
            if (actionResult.isDeleted) return@onActor
            transaction = actionResult.transaction.copy(schedule = schedule.id)
            val transfer = scheduledTransfer(transaction, database)
            if (transfer != null) createTransfer(transfer.first, transfer.second) else createTransaction(transaction, applyRules = true)
        } else {
            createTransaction(transaction, applyRules = true)
        }
    }

    /**
     * Mark a schedule finished, or restart it. Restarting also resets the next
     * date, matching the web's "restart" (`resetNextDate: true`).
     */
    suspend fun setScheduleCompleted(schedule: ScheduleSummary, completed: Boolean, today: DayDate = DayDate.today()) {
        updateScheduleColumns(schedule.id, listOf("completed" to if (completed) 1 else 0))
        if (completed) return
        val date = schedule.dateCondition ?: return
        val next = ScheduleConditions.nextDate(date, today) ?: return
        setScheduleNextDate(schedule, next, reset = true)
    }

    /**
     * Apply one schedule write plan: local rows, CRDT messages, JSON-path
     * cache, push. All of a plan's rows go out in a single message batch, so a
     * failure can't leave a schedule with a rule but no next date — the state
     * that makes a schedule invisible to the web.
     */
    private suspend fun commit(plan: ScheduleWritePlan) = onActor {
        val database = requireDatabase()
        val messages = mutableListOf<CRDTMessage>()
        for (write in plan.writes) messages += messageGenerator.messages(write.dataset, write.row, write.fields)
        commitMessages(database.applyMessagesAndInsertMessages(messages))
        // Local-only derived cache; never synced.
        plan.conditions?.let { database.writeScheduleJSONPaths(plan.scheduleId, it) }
        // Push in the background — the save button awaits this write (issue #125).
        scheduleAutomaticSync()
    }

    /** Create a schedule and its linked rule (optimistic local-first). Mirrors loot-core `createSchedule`. */
    suspend fun createSchedule(fields: ScheduleFormFields, today: DayDate = DayDate.today()): String = onActor {
        fields.normalizedName?.let { name ->
            if (requireDatabase().scheduleNameExists(name, null)) throw ScheduleWriteError.DuplicateName(name)
        }
        val plan = ScheduleWriteBuilder.createPlan(
            fields = fields,
            scheduleId = UUID.randomUUID().toString().lowercase(),
            ruleId = UUID.randomUUID().toString().lowercase(),
            nextDateRowId = UUID.randomUUID().toString().lowercase(),
            now = System.currentTimeMillis(),
            today = today,
        )
        commit(plan)
        plan.scheduleId
    }

    /** Update a schedule's fields, merging into its existing rule. Mirrors loot-core `updateSchedule`. */
    suspend fun updateSchedule(schedule: ScheduleSummary, fields: ScheduleFormFields, resetNextDate: Boolean = false, today: DayDate = DayDate.today()) =
        onActor {
            fields.normalizedName?.let { name ->
                if (requireDatabase().scheduleNameExists(name, schedule.id)) throw ScheduleWriteError.DuplicateName(name)
            }
            commit(ScheduleWriteBuilder.updatePlan(schedule, fields, System.currentTimeMillis(), today, resetNextDate))
        }

    /** Tombstone a schedule and its rule. Mirrors loot-core `deleteSchedule`. */
    suspend fun deleteSchedule(schedule: ScheduleSummary) = commit(ScheduleWriteBuilder.deletePlan(schedule))

    /** Move a schedule's next date. `reset` bumps the canonical base; otherwise only the local override moves. */
    suspend fun setScheduleNextDate(schedule: ScheduleSummary, newNextDate: DayDate, reset: Boolean) {
        val plan = ScheduleWriteBuilder.nextDatePlan(schedule, newNextDate, reset, System.currentTimeMillis()) ?: return
        commit(plan)
    }

    /** Write plain columns on the schedule row (complete, restart). */
    suspend fun updateScheduleColumns(scheduleId: String, fields: List<Pair<String, Any?>>) =
        commit(ScheduleWriteBuilder.scheduleColumnsPlan(scheduleId, fields))

    /** Force immediate sync (pull-to-refresh). */
    suspend fun syncNow() = onActor {
        logger.info("syncNow() called - forcing immediate sync")
        val retryTask = syncTask
        syncTask = null
        retryTask?.cancelAndJoin()
        performSync()
    }

    /**
     * Recover from a stuck out-of-sync state by discarding the last-synced
     * marker, then running a sync: the next pass re-requests everything after
     * the local message log's high-water mark. The Merkle tree is deliberately
     * left alone — it is re-derived from the message log on the next sync
     * anyway, and blanking it used to let fullSync adopt the server's tree and
     * declare parity it hadn't earned (#99, #121).
     */
    suspend fun resetSyncState() = onActor {
        logger.notice("resetSyncState() - clearing lastSyncedTimestamp")
        val retryTask = syncTask
        syncTask = null
        retryTask?.cancelAndJoin()
        activeSyncTask?.join()
        diagnosticLog.recordResyncTriggered(DiagnosticLog.ResyncReason.ManualReset, diagnosticSession)
        lastSyncedTimestamp = null
        retryDelayMillis = 5000
        runCatching { saveClock() }
        performSync()
    }

    /**
     * Automatic sync with rate limiting (foreground events, after writes…):
     * skipped if the last successful sync was inside the window and nothing
     * new is waiting locally.
     * @return whether the data is freshly synced — true when this call's sync
     *   succeeded, and also on the rate-limited skip, which by construction
     *   only fires when a sync SUCCEEDED within the window.
     */
    suspend fun automaticSync(): Boolean = onActor { automaticSyncOnActor() }

    private suspend fun automaticSyncOnActor(): Boolean {
        if (shouldSkipAutomaticSync()) {
            logger.debug("automaticSync() skipped - rate limited (last sync inside the window, nothing new locally)")
            return true
        }
        return performSync()
    }

    /**
     * Start [automaticSync] in the background and return immediately. Every
     * write path commits its rows and CRDT messages *before* calling this, so
     * pushing them is catch-up work the writer must not wait on — awaiting it
     * made adding a transaction hang for the whole request timeout whenever
     * the server was unreachable (issue #125). Writes that arrive while a push
     * is in flight coalesce onto a single trailing push.
     */
    private fun scheduleAutomaticSync() {
        if (pushTask != null) {
            logger.debug("scheduleAutomaticSync() - push in flight, coalescing onto a trailing sync")
            pushNeededAfterCurrent = true
            return
        }
        startPushTask(rateLimited = true)
    }

    /**
     * Callers that may be suspended right after writing (background work)
     * await this so the deferred push still gets its chance. Drains trailing
     * pushes too.
     */
    suspend fun flushPendingSync() = onActor {
        // finishPushTask has already swapped pushTask by the time a task completes.
        while (true) (pushTask ?: break).join()
    }

    /**
     * Cancel deferred work when the owning budget is being torn down. The push
     * task must be awaited so it cannot continue using the old database.
     */
    suspend fun cancelPendingSync() = onActor {
        val retryTask = syncTask
        val push = pushTask
        val active = activeSyncTask
        syncTask = null
        pushTask = null
        activeSyncTask = null
        activeSyncID = null
        pushNeededAfterCurrent = false
        retryTask?.cancel()
        push?.cancel()
        active?.cancel()
        retryTask?.join()
        push?.join()
        active?.join()
    }

    /**
     * Whether local writes are still waiting to reach the server. Call after
     * [flushPendingSync] to find out whether the push actually landed (issue #139).
     */
    suspend fun hasPendingLocalWrites(): Boolean = onActor { hasUnsyncedLocalMessages() }

    /** Whether this row still has a message newer than the sync watermark. */
    suspend fun hasPendingLocalWrites(dataset: String, row: String): Boolean = onActor {
        val database = database ?: return@onActor false
        val timestamps = runCatching { database.messageTimestamps(dataset, row) }.getOrNull()
        if (timestamps.isNullOrEmpty()) return@onActor false
        val watermark = lastSyncedTimestamp ?: downloadBaselineTimestamp
        if (watermark.isNullOrEmpty()) return@onActor true
        timestamps.any { it > watermark }
    }

    private fun startPushTask(rateLimited: Boolean) {
        pushNeededAfterCurrent = false
        // Assigned synchronously on the actor, so the body can't observe a
        // stale pushTask before this returns.
        lateinit var task: Job
        task = scope.launch {
            try {
                if (rateLimited) {
                    automaticSyncOnActor()
                } else {
                    // This push exists *because* new local messages landed
                    // during the previous one, so the rate limiter must not
                    // swallow it.
                    performSync()
                }
            } finally {
                finishPushTask(task)
            }
        }
        pushTask = task
    }

    private fun finishPushTask(task: Job) {
        if (pushTask !== task) return
        pushTask = null
        if (pushNeededAfterCurrent) {
            logger.debug("finishPushTask() - running the coalesced trailing sync")
            startPushTask(rateLimited = false)
        }
    }

    // Sync Logic

    /**
     * True if automatic sync should be skipped due to rate limiting. The window
     * only ever suppresses a redundant *pull*; it must never suppress a *push*:
     * a skipped sync drops the local write until something else syncs (issue #139).
     */
    private fun shouldSkipAutomaticSync(): Boolean {
        val lastSync = lastSuccessfulSyncTime ?: return false
        if (now() - lastSync >= rateLimitWindowMillis) return false
        return !hasUnsyncedLocalMessages()
    }

    /**
     * True when messages_crdt holds a message the last successful sync didn't
     * cover. HLC timestamps are fixed-width and sort lexicographically, so the
     * high-water mark comparison is exact.
     */
    private fun hasUnsyncedLocalMessages(): Boolean {
        val database = database ?: return false
        val maxTimestamp = runCatching { database.getMaxMessageTimestamp() }.getOrNull()
        if (maxTimestamp.isNullOrEmpty()) return false
        val lastSynced = lastSyncedTimestamp
        if (lastSynced.isNullOrEmpty()) return true
        return maxTimestamp > lastSynced
    }

    /** @return true iff the sync completed successfully. */
    private suspend fun performSync(): Boolean {
        // A caller may have committed messages after the running pass read its
        // batch. Wait for it, then give this caller a fresh pass.
        while (true) {
            val inFlight = activeSyncTask ?: break
            val id = activeSyncID
            inFlight.join()
            if (activeSyncID == id) {
                activeSyncTask = null
                activeSyncID = null
            }
        }
        currentCoroutineContext().ensureActive()

        val id = UUID.randomUUID()
        val task = scope.async { runSync() }
        activeSyncTask = task
        activeSyncID = id
        val result = try {
            task.await()
        } catch (e: CancellationException) {
            // Our caller was cancelled: take the pass down with it. Otherwise
            // the pass alone was cancelled (cancelPendingSync), which reads as
            // an unsuccessful sync, as the Swift task's value does.
            if (!currentCoroutineContext().isActive) {
                task.cancel()
                throw e
            }
            false
        } finally {
            if (activeSyncID == id) {
                activeSyncTask = null
                activeSyncID = null
            }
        }
        return result
    }

    private suspend fun runSync(): Boolean {
        logger.info("performSync() starting...")
        diagnosticLog.recordSyncStarted(diagnosticSession)
        _state.value = SyncState.Syncing
        return try {
            currentCoroutineContext().ensureActive()
            fullSync(null, 0)
            currentCoroutineContext().ensureActive()
            logger.info("performSync() completed successfully")
            diagnosticLog.recordSyncFinished(DiagnosticLog.SyncResult.Success, diagnosticSession)
            _state.value = SyncState.Idle
            retryDelayMillis = 5000 // reset on success
            lastSuccessfulSyncTime = now()
            true
        } catch (e: CancellationException) {
            diagnosticLog.recordSyncFinished(DiagnosticLog.SyncResult.Cancelled, diagnosticSession)
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                diagnosticLog.recordSyncFinished(DiagnosticLog.SyncResult.Cancelled, diagnosticSession)
                return false
            }
            if (e is SyncError.Offline) {
                logger.notice("performSync() failed - offline")
                diagnosticLog.recordSyncFinished(DiagnosticLog.SyncResult.Offline, diagnosticSession)
                _state.value = SyncState.Offline
            } else {
                logger.error("performSync() failed: ${e.message}")
                diagnosticLog.recordSyncFinished(DiagnosticLog.SyncResult.Failed, diagnosticSession)
                _state.value = SyncState.Error(e.message ?: e.toString())
            }
            scheduleRetry()
            false
        }
    }

    private suspend fun fullSync(since: String?, attemptCount: Int) {
        val database = database
        val fileId = fileId
        val groupId = groupId
        if (database == null || fileId == null || groupId == null) {
            logger.error("fullSync() - not configured!")
            throw SyncError.NotConfigured
        }
        logger.debug("fullSync() attempt #$attemptCount, since: ${since ?: "nil"}, lastSynced: ${lastSyncedTimestamp ?: "nil"}")

        // The merkle must describe our own message log and nothing else: a
        // mismatch with the server's tree is the ONLY signal that we're missing
        // messages, so a tree claiming more than we hold makes the missing
        // window unreachable. Two ways the persisted tree drifts from the log:
        //  - an older build overwrote it with the server's tree whenever the
        //    first sync had no lastSyncedTimestamp, recording parity for
        //    messages sync had skipped (#99, #121).
        //  - `receiveMessages` commits to messages_crdt but the tree is only
        //    persisted at the end of a successful pass, so being killed in
        //    between drops timestamps from the tree that the log kept.
        // Re-deriving from the log before the first comparison of a session
        // repairs both, and the diff recursion below then heals the data.
        if (!hasDerivedMerkleFromLog) {
            hasDerivedMerkleFromLog = true
            try {
                val derived = database.deriveMerkleFromMessageLog()
                if (derived.root.hash != merkle.root.hash) {
                    logger.notice("Merkle disagreed with the message log (persisted ${merkle.root.hash}, derived ${derived.root.hash}) - re-deriving")
                    merkle = derived
                }
            } catch (e: Exception) {
                // Keep the persisted tree: a sync with a possibly stale merkle
                // still beats no sync at all.
                logger.warning("Could not derive merkle from message log: $e")
            }
        }

        // Sync starting point: provided `since`, then lastSyncedTimestamp, then
        // the download baseline, then the epoch.
        val effectiveLastSynced = lastSyncedTimestamp?.takeIf { it.isNotEmpty() }
        val baseline = downloadBaselineTimestamp
        val sinceTimestamp = when {
            since != null -> since
            effectiveLastSynced != null -> effectiveLastSynced
            !baseline.isNullOrEmpty() -> {
                // No valid lastSynced (fresh download, or reset sync state). The
                // snapshot's message high-water mark is the true sync floor:
                // everything the server has after it is missing locally no
                // matter how old (issue #99).
                logger.notice("No valid lastSyncedTimestamp, using snapshot high-water mark")
                baseline
            }
            else -> {
                // No local messages at all — ask for the server's entire log,
                // which only spans back to the file's last upload/reset.
                logger.notice("No valid lastSyncedTimestamp and empty message log, requesting full server log")
                HLCTimestamp.zero.toString()
            }
        }

        // Local messages to send. On recursion this starts from the merkle diff
        // point, so the server also receives local messages older than
        // lastSyncedTimestamp that it turned out to be missing (matches
        // upstream fullSync, which sends getMessagesSince(since)). With no
        // valid lastSynced, everything newer than the download baseline is a
        // genuine local write made after download.
        val localMessages = if (since != null || effectiveLastSynced != null) {
            database.getMessagesSince(sinceTimestamp)
        } else {
            database.getMessagesSince(downloadBaselineTimestamp ?: "")
        }
        logger.debug("Found ${localMessages.size} local messages to send")

        val requestData = encoder.encode(localMessages, fileId, groupId, encryptKeyId, sinceTimestamp)

        diagnosticLog.recordSyncRequestMessageCount(localMessages.size, diagnosticSession)
        val responseData = serverClient.postSync(requestData)

        val (remoteMessages, remoteMerkle) = encoder.decode(responseData)
        diagnosticLog.recordSyncResponseMessageCount(remoteMessages.size, diagnosticSession)
        logger.debug("Decoded ${remoteMessages.size} remote messages, merkle hash: ${remoteMerkle.hash}")

        if (remoteMessages.isNotEmpty()) receiveMessages(remoteMessages)

        val diffTime = merkle.diff(MerkleTree(remoteMerkle))
        if (diffTime != null) {
            // Not in sync - recurse from the divergence point. This is the only
            // way a missing window is ever recovered, so it runs no matter how
            // the sync state got here; `attemptCount` bounds a pathological case.
            logger.debug("Merkle diff found at time: $diffTime, recursing...")
            diagnosticLog.recordMerkleMismatch(diagnosticSession)
            if (attemptCount >= 10) {
                logger.error("Too many sync attempts, giving up")
                throw SyncError.OutOfSync
            }
            val diffTimestamp = HLCTimestamp(diffTime, 0, "0").toString()
            diagnosticLog.recordResyncTriggered(DiagnosticLog.ResyncReason.MerkleMismatch, diagnosticSession)
            fullSync(diffTimestamp, attemptCount + 1)
        } else {
            // Fully synced — persist the HLC as the high-water mark (matches
            // upstream, which stores getClock().timestamp). The HLC was seeded
            // from persisted state in configure, so even on a fresh download it
            // can't regress to the epoch and re-push the entire CRDT history.
            //
            // Reentrancy: the assignment and saveClock run without suspending
            // after the clock read, so a local write can only interleave during
            // `clock.current()`. If its message lands before the read, its
            // timestamp is in the local merkle (and the server's isn't), so the
            // next sync's diff recursion re-sends it; if after, its timestamp is
            // strictly greater than lastSyncedTimestamp and the next sync's
            // since-window sends it. Either way nothing is dropped.
            logger.info("Merkle trees match - fully synced!")
            lastSyncedTimestamp = clock.current().toString()
            saveClock()
        }
    }

    private suspend fun receiveMessages(messages: List<CRDTMessage>) {
        val database = requireDatabase()
        // Update clock for each received message
        for (msg in messages) clock.receive(msg.timestamp)
        // Apply new messages and persist all received messages atomically. The
        // merkle hash is XOR-based, so re-inserting an existing timestamp
        // (server echo, multi-pass recursion, retry) would cancel it out of
        // the trie and force a permanent divergence from the server.
        val newMessages = database.filterNewMessages(messages)
        val insertedMessages = database.applyMessagesAndInsertMessages(messages, newMessages)
        for (msg in insertedMessages) merkle = merkle.inserting(msg.timestamp)
        if (insertedMessages.isNotEmpty()) merkle = merkle.pruned()
        logger.debug("Inserted ${insertedMessages.size}/${messages.size} messages, merkle hash: ${merkle.root.hash}")
    }

    // Retry Logic

    private fun scheduleRetry() {
        syncTask = scope.launch {
            delay(retryDelayMillis)
            // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 300s cap
            retryDelayMillis = min(retryDelayMillis * 2, maxRetryDelayMillis)
            performSync()
        }
    }

    /**
     * Blocking on purpose: callers must be able to persist lastSyncedTimestamp
     * in the same actor section that computed it, with no suspension point a
     * local write could interleave into.
     */
    private fun saveClock() {
        val database = database ?: return
        // Persist the sync high-water mark, not the local HLC: clock.current is
        // the last logical event we generated/received, which on a fresh
        // download is the epoch and would poison the next sync.
        database.saveClock(ClockRecord(lastSyncedTimestamp ?: "", merkle.root))
    }

    // Tags

    suspend fun createTag(name: String, color: String? = null, description: String? = null): Tag = onActor { createTagOnActor(name, color, description) }

    private suspend fun createTagOnActor(name: String, color: String?, description: String?): Tag {
        val database = requireDatabase()
        val normalized = Tag.normalizeTagName(name)
        // Upstream tags-create parity: a name already held by a row —
        // tombstoned included — reactivates that row instead of inserting a
        // duplicate, which the server's UNIQUE(tags.tag) would reject.
        val existing = database.allTags().firstOrNull { it.tag == normalized }
        if (existing != null) {
            val revived = existing.copy(color = color, description = description, tombstone = false)
            database.updateTag(revived)
            commitMessages(database.insertMessages(messageGenerator.messagesForUpdate(revived, setOf("tag", "color", "description", "tombstone"))))
            scheduleAutomaticSync()
            return revived
        }
        val tag = Tag(tag = normalized, color = color, description = description)
        database.insertTag(tag)
        commitMessages(database.insertMessages(messageGenerator.messagesForInsert(tag)))
        scheduleAutomaticSync()
        return tag
    }

    suspend fun updateTag(tag: Tag) = onActor {
        val database = requireDatabase()
        // Upstream emits only the fields actually passed; diff against the
        // stored row so no-op updates don't produce same-value messages.
        var changed = listOf("tag", "color", "description", "hidden")
        database.allTags().firstOrNull { it.id == tag.id }?.let { prev ->
            changed = changed.filter { field ->
                when (field) {
                    "tag" -> prev.tag != tag.tag
                    "color" -> prev.color != tag.color
                    "description" -> prev.description != tag.description
                    else -> prev.hidden != tag.hidden
                }
            }
        }
        if (changed.isEmpty()) return@onActor
        database.updateTag(tag)
        commitMessages(database.insertMessages(messageGenerator.messagesForUpdate(tag, changed.toSet())))
        scheduleAutomaticSync()
    }

    suspend fun deleteTag(id: String) = onActor {
        val database = requireDatabase()
        database.deleteTag(id)
        commitMessages(database.insertMessages(listOf(messageGenerator.messageForDelete(Tag(id = id, tag = "", tombstone = true)))))
        scheduleAutomaticSync()
    }

    suspend fun renameTag(id: String, oldName: String, newName: String) = onActor {
        val database = requireDatabase()
        val normalizedNew = Tag.normalizeTagName(newName)
        // Upstream renameTag checks getAllTags(), tombstones included — the
        // server's UNIQUE(tags.tag) spans tombstoned rows.
        if (database.allTags().any { it.id != id && it.tag == normalizedNew }) throw SyncError.TagAlreadyExists
        val updatedTxs = database.renameTag(id, oldName, normalizedNew)
        val messages = messageGenerator.messages(Tag.DATASET_NAME, id, listOf("tag" to normalizedNew)).toMutableList()
        for ((txId, newNotes) in updatedTxs) {
            messages += messageGenerator.messages(Transaction.DATASET_NAME, txId, listOf("notes" to newNotes))
        }
        commitMessages(database.insertMessages(messages))
        scheduleAutomaticSync()
    }

    suspend fun importDiscoveredTags(): List<Tag> = onActor {
        val database = requireDatabase()
        // One createTag per name (upstream discoverTags does the same), so a
        // discovered name matching a tombstoned tag reactivates it.
        database.discoverTags().map { createTagOnActor(it, null, null) }
    }
}
