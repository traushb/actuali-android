package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.banksync.Bank
import io.github.traushb.actuali.services.banksync.BankSyncAccount
import io.github.traushb.actuali.services.banksync.BankSyncExistingTransaction
import io.github.traushb.actuali.services.banksync.BankSyncSource
import io.github.traushb.actuali.services.banksync.BankSyncUpdate
import io.github.traushb.actuali.services.banksync.ExpectedBankSyncLink
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.placeholders
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.quotedIdentifier
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.upsertValue
import io.github.traushb.actuali.services.sync.CRDTMessage
import io.github.traushb.actuali.services.sync.CRDTValue
import io.github.traushb.actuali.services.sync.HLCTimestamp
import io.github.traushb.actuali.services.sync.MerkleTree
import io.github.traushb.actuali.services.sync.MurmurHash3

private val logger = Logger("BudgetDatabase")

// CRDT Messages

/**
 * CRDT messages are uniquely identified by their HLC timestamp. The server can
 * echo back messages we already have, so a plain INSERT would hit the UNIQUE
 * constraint and abort the batch. INSERT OR IGNORE is correct here — a
 * duplicate timestamp means the same operation, so silently skipping is the
 * convergent outcome. Returns the subset that was actually new.
 */
internal fun insertMessageRows(db: Db, messages: List<CRDTMessage>): List<CRDTMessage> {
    val inserted = mutableListOf<CRDTMessage>()
    for (msg in messages) {
        db.execute(
            "INSERT OR IGNORE INTO messages_crdt (timestamp, dataset, row, column, value) VALUES (?, ?, ?, ?, ?)",
            msg.timestamp.toString(), msg.dataset, msg.row, msg.column, msg.value,
        )
        if (db.changes > 0) inserted += msg
    }
    return inserted
}

/**
 * Inserts messages into messages_crdt and returns the subset that was actually
 * new. The merkle trie hashes with XOR (self-inverse), so callers must only
 * merkle-insert the returned messages — re-inserting an existing timestamp
 * would cancel it back out of the trie.
 */
fun BudgetDatabase.insertMessages(messages: List<CRDTMessage>): List<CRDTMessage> = dbQueue.write { insertMessageRows(it, messages) }

fun BudgetDatabase.getMaxMessageTimestamp(): String? = dbQueue.read { db ->
    db.fetchOne("SELECT MAX(timestamp) AS ts FROM messages_crdt")?.string("ts")
}

/**
 * Rebuild the sync merkle from `messages_crdt`.
 *
 * The trie is nothing but the XOR of every stored message's timestamp hash,
 * so the message log — not the tree cached in `messages_clock` — is its
 * source of truth. Re-deriving lets a persisted tree that drifted from the
 * log be repaired instead of quietly misreporting parity with the server.
 *
 * A long-lived budget's log runs to hundreds of thousands of rows, so this
 * hashes each stored timestamp string directly (`HLCTimestamp.hash()` murmurs
 * `toString()`, which is exactly the text the log holds) and reads the trie's
 * minute off the ISO-8601 prefix arithmetically.
 */
fun BudgetDatabase.deriveMerkleFromMessageLog(): MerkleTree = dbQueue.read { db ->
    if (!db.tableExists("messages_crdt")) return@read MerkleTree()
    val buckets = HashMap<Long, Int>()
    for (timestamp in db.strings("SELECT timestamp FROM messages_crdt")) {
        val minutes = HLCTimestamp.minutesSinceEpoch(timestamp) ?: continue
        val key = minutes * 60000
        buckets[key] = (buckets[key] ?: 0) xor MurmurHash3.hash(timestamp)
    }
    MerkleTree.building(buckets).pruned()
}

fun BudgetDatabase.getMessagesSince(since: String): List<CRDTMessage> = dbQueue.read { db ->
    db.fetchAll(
        "SELECT timestamp, dataset, row, column, value FROM messages_crdt WHERE timestamp > ? ORDER BY timestamp",
        since,
    ).mapNotNull { row ->
        val timestamp = row.string("timestamp")?.let(HLCTimestamp::parse) ?: return@mapNotNull null
        CRDTMessage(timestamp, row.string("dataset") ?: "", row.string("row") ?: "", row.string("column") ?: "", row.string("value") ?: "")
    }
}

/** Compare incoming messages with existing, filtering out already-applied ones. */
fun BudgetDatabase.filterNewMessages(messages: List<CRDTMessage>): List<CRDTMessage> = dbQueue.read { db ->
    messages.filter { msg ->
        db.fetchOne(
            "SELECT timestamp FROM messages_crdt WHERE dataset = ? AND row = ? AND column = ? AND timestamp >= ?",
            msg.dataset, msg.row, msg.column, msg.timestamp.toString(),
        ) == null
    }
}

/**
 * Apply CRDT messages to the database.
 *
 * dataset/column are server-controlled identifiers, so they are validated
 * against the live SQLite schema before being interpolated into SQL, and
 * quoted as a second layer of defense. Messages are applied in timestamp
 * order so the outcome doesn't depend on the order the server sent them.
 */
fun BudgetDatabase.applyMessages(messages: List<CRDTMessage>) {
    dbQueue.write { applyMessageRows(it, messages) }
    notifyDatasetsChanged(messages)
}

/**
 * Applies messages and persists their original input ordering in one SQLite
 * transaction. [applying] is used by receive paths that must apply only
 * messages not already materialized while still persisting every received
 * message for deduplication and replay.
 */
fun BudgetDatabase.applyMessagesAndInsertMessages(messages: List<CRDTMessage>, applying: List<CRDTMessage>? = null): List<CRDTMessage> {
    val toApply = applying ?: messages
    val inserted = dbQueue.write { db ->
        applyMessageRows(db, toApply)
        insertMessageRows(db, messages)
    }
    notifyDatasetsChanged(toApply)
    return inserted
}

internal fun applyMessageRows(db: Db, messages: List<CRDTMessage>) {
    val schema = syncableSchema(db)
    for (msg in messages.sortedBy { it.timestamp.toString() }) {
        // Unknown identifiers are either upstream schema we don't have yet or
        // a hostile server. Skip the message but let sync continue:
        // insertMessages still records it in messages_crdt so a later schema
        // migration can replay it.
        val columns = schema[msg.dataset]
        if (columns == null || msg.column !in columns) {
            logger.warning("Skipping CRDT message for unknown schema ${msg.dataset}.${msg.column}")
            continue
        }
        upsertValue(db, quotedIdentifier(msg.dataset), quotedIdentifier(msg.column), msg.row, CRDTValue.deserialize(msg.value))
    }
}

/**
 * Table -> columns whitelist for CRDT applies, derived from the live SQLite
 * schema (computed once per batch, not per message). Internal bookkeeping
 * tables are never valid sync targets, and a table must have an `id` column
 * for the row-based apply to make sense.
 */
private fun syncableSchema(db: Db): Map<String, Set<String>> {
    val internalTables = setOf("messages_crdt", "messages_clock", "migrations", "__migrations__", "bank_sync_local_links")
    val schema = HashMap<String, Set<String>>()
    for (table in db.strings("SELECT name FROM sqlite_master WHERE type = 'table'")) {
        if (table in internalTables || table.startsWith("sqlite_")) continue
        val columns = db.columns(table).toSet()
        if ("id" in columns) schema[table] = columns
    }
    return schema
}

// Transaction Insert

fun BudgetDatabase.insertTransaction(transaction: Transaction) = dbQueue.write { insertTransactionRow(it, transaction) }

private sealed interface FinancialIdPolicy {
    data object Unique : FinancialIdPolicy
    data class Occurrences(val limit: Int) : FinancialIdPolicy
}

fun BudgetDatabase.insertTransactionWithMessages(
    transaction: Transaction,
    messages: List<CRDTMessage>,
    pendingPayees: List<Payee> = emptyList(),
): List<CRDTMessage> = dbQueue.write { db ->
    insertBankSyncTransactionWithMessages(db, transaction, messages, pendingPayees, FinancialIdPolicy.Unique, HashMap()).second
}

fun BudgetDatabase.insertBankSyncTransactionWithMessages(
    transaction: Transaction,
    messages: List<CRDTMessage>,
    maxLiveFinancialIdOccurrences: Int,
    expectedLink: ExpectedBankSyncLink,
    pendingPayees: List<Payee> = emptyList(),
): List<CRDTMessage> {
    require(maxLiveFinancialIdOccurrences > 0)
    return dbQueue.write { db ->
        insertBankSyncTransactionWithMessages(
            db, transaction, messages, pendingPayees, FinancialIdPolicy.Occurrences(maxLiveFinancialIdOccurrences), HashMap(), expectedLink,
        ).second
    }
}

private fun insertBankSyncTransactionWithMessages(
    db: Db,
    transaction: Transaction,
    messages: List<CRDTMessage>,
    pendingPayees: List<Payee>,
    financialIdPolicy: FinancialIdPolicy,
    insertedPendingPayees: MutableMap<String, Payee>,
    expectedLink: ExpectedBankSyncLink? = null,
): Pair<Boolean, List<CRDTMessage>> {
    if (expectedLink != null) requireBankSyncLink(db, expectedLink)
    val financialId = transaction.financialId
    if (financialId == null) {
        insertTransactionRow(db, transaction)
        return true to insertMessageRows(db, messages)
    }

    val existing = db.fetchOne("SELECT acct, tombstone FROM transactions WHERE id = ? AND financial_id = ?", transaction.id, financialId)
    if (existing != null) {
        // A retry must not resurrect a deleted import or undo an account move.
        if (existing.string("acct") != transaction.accountId || (existing.long("tombstone") ?: 0) != 0L) return false to emptyList()
        val columns = db.strings("SELECT column FROM messages_crdt WHERE dataset = 'transactions' AND row = ?", transaction.id).toSet()
        if (columns.isEmpty()) {
            applyMessageRows(db, messages)
            return false to insertMessageRows(db, messages)
        }
        if (columns == transaction.syncableFields.keys) return false to emptyList()
        throw BudgetDatabase.TransactionWriteError.IncompleteFinancialIdMessages
    }

    val liveCount = db.long(
        "SELECT COUNT(*) FROM transactions WHERE acct IS ? AND financial_id = ? AND (tombstone = 0 OR tombstone IS NULL)",
        transaction.accountId, financialId,
    ) ?: 0
    when (financialIdPolicy) {
        FinancialIdPolicy.Unique -> if (liveCount > 0) return false to emptyList()
        is FinancialIdPolicy.Occurrences -> if (liveCount >= financialIdPolicy.limit) return false to emptyList()
    }

    for (payee in pendingPayees) {
        val inserted = insertedPendingPayees[payee.id]
        if (inserted != null) {
            if (inserted != payee) throw BankSyncDatabaseError.BankSyncPendingPayeeConflict
            continue
        }
        db.execute(
            "INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, ?, ?, ?)",
            payee.id, payee.name, payee.transferAccountId, if (payee.tombstone) 1 else 0,
        )
        db.execute("INSERT INTO payee_mapping (id, targetId) VALUES (?, ?)", payee.id, payee.id)
        insertedPendingPayees[payee.id] = payee
    }
    insertTransactionRow(db, transaction)
    return true to insertMessageRows(db, messages)
}

/**
 * Inserts a newly-created account, its transfer payee (plus the payee's
 * self-mapping row the transaction joins need), and its opening-balance
 * transaction (if any) in a single SQLite transaction.
 */
fun BudgetDatabase.insertAccount(account: Account, transferPayee: Payee, startingBalanceTransaction: Transaction?) = dbQueue.write { db ->
    db.execute(
        "INSERT INTO accounts (id, name, type, offbudget, closed, tombstone, sort_order) VALUES (?, ?, ?, ?, ?, 0, ?)",
        account.id, account.name, account.type.rawValue, if (account.offBudget) 1 else 0, if (account.closed) 1 else 0, account.sortOrder,
    )
    db.execute(
        "INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, ?, ?, ?)",
        transferPayee.id, transferPayee.name, transferPayee.transferAccountId, if (transferPayee.tombstone) 1 else 0,
    )
    db.execute("INSERT INTO payee_mapping (id, targetId) VALUES (?, ?)", transferPayee.id, transferPayee.id)
    startingBalanceTransaction?.let { insertTransactionRow(db, it) }
}

/**
 * Inserts both legs of a transfer and their CRDT messages in a single SQLite
 * transaction, so no orphaned half-transfer can persist. Returns the subset
 * of messages that was actually new.
 */
fun BudgetDatabase.insertTransfer(source: Transaction, target: Transaction, messages: List<CRDTMessage>): List<CRDTMessage> = dbQueue.write { db ->
    insertTransactionRow(db, source)
    insertTransactionRow(db, target)
    insertMessageRows(db, messages)
}

/**
 * Repoints an existing transaction at a new partner leg and inserts that leg,
 * with both rows' CRDT messages, in a single SQLite transaction — a partial
 * write would leave the edited row's `transferred_id` dangling.
 */
fun BudgetDatabase.convertToTransfer(leg: Transaction, partner: Transaction, messages: List<CRDTMessage>): List<CRDTMessage> = dbQueue.write { db ->
    updateTransactionRow(db, leg)
    insertTransactionRow(db, partner)
    insertMessageRows(db, messages)
}

/**
 * Inserts a split parent, its children and their CRDT messages in a single
 * SQLite transaction, so no partial split can persist.
 */
fun BudgetDatabase.insertSplit(
    parent: Transaction,
    children: List<Transaction>,
    transferPartners: List<Transaction> = emptyList(),
    messages: List<CRDTMessage>,
): List<CRDTMessage> = dbQueue.write { db ->
    insertTransactionRow(db, parent)
    children.forEach { insertTransactionRow(db, it) }
    transferPartners.forEach { insertTransactionRow(db, it) }
    insertMessageRows(db, messages)
}

internal fun insertTransactionRow(db: Db, transaction: Transaction) {
    // sort_order defaults to the current timestamp (ms) so new transactions
    // appear at the top; split rows pass explicit values so children keep
    // their entry order under the parent.
    val sortOrder = transaction.sortOrder ?: System.currentTimeMillis().toDouble()
    db.execute(
        """
        INSERT INTO transactions (id, acct, date, description, category, amount, notes, cleared, reconciled, transferred_id, isParent, isChild, parent_id, tombstone, sort_order, imported_description, schedule, financial_id, starting_balance_flag)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        transaction.id,
        transaction.accountId,
        transaction.date,
        transaction.payeeId,
        transaction.categoryId,
        transaction.amount,
        transaction.notes,
        if (transaction.cleared) 1 else 0,
        if (transaction.reconciled) 1 else 0,
        transaction.transferId,
        if (transaction.isParent) 1 else 0,
        if (transaction.parentId != null) 1 else 0,
        transaction.parentId,
        if (transaction.tombstone) 1 else 0,
        sortOrder,
        transaction.importedPayee,
        transaction.schedule,
        transaction.financialId,
        if (transaction.startingBalanceFlag) 1 else 0,
    )
}

/**
 * All bank-import dedup keys (`financial_id`) already present on an account.
 * Tombstoned rows are deliberately included: a user who deleted an imported
 * transaction shouldn't see it resurrected by a re-import.
 */
fun BudgetDatabase.existingFinancialIds(accountId: String): Set<String> = dbQueue.read { db ->
    db.strings("SELECT financial_id FROM transactions WHERE acct = ? AND financial_id IS NOT NULL", accountId).toSet()
}

/** Which of these transactions came from a bank import (carry a `financial_id`). */
suspend fun BudgetDatabase.importedTransactionIds(ids: List<String>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    return dbQueue.readAsync { db ->
        db.strings("SELECT id FROM transactions WHERE financial_id IS NOT NULL AND id IN (${placeholders(ids.size)})", ids).toSet()
    }
}

/**
 * Whether any transaction row already exists matching either the given id or
 * financial_id (including tombstoned rows, so retries do not resurrect
 * deleted imports).
 */
suspend fun BudgetDatabase.transactionExists(id: String, financialId: String): Boolean = dbQueue.readAsync { db ->
    (db.long("SELECT COUNT(*) FROM transactions WHERE id = ? OR financial_id = ?", id, financialId) ?: 0) > 0
}

// Bank Sync

private fun Db.bool(sql: String, vararg args: Any?): Boolean = (long(sql, *args) ?: 0) != 0L

internal fun bankSyncLinkMatches(db: Db, expected: ExpectedBankSyncLink?, accountId: String? = null): Boolean {
    if (expected == null) {
        return db.bool(
            """
            SELECT NOT EXISTS(
                SELECT 1 FROM accounts
                WHERE id IS ? AND account_id IS NOT NULL AND account_id <> ''
                  AND account_sync_source IS NOT NULL AND account_sync_source <> ''
            ) AND NOT EXISTS(
                SELECT 1 FROM bank_sync_local_links WHERE account_id IS ?
            )
            """,
            accountId, accountId,
        )
    }
    if (expected.source == BankSyncSource.FinanceKit.rawValue) {
        return db.bool(
            """
            SELECT EXISTS(
                SELECT 1
                FROM bank_sync_local_links AS local
                JOIN accounts ON accounts.id = local.account_id
                WHERE local.account_id IS ?
                  AND local.external_account_id IS ?
                  AND local.source IS ?
                  AND (accounts.tombstone = 0 OR accounts.tombstone IS NULL)
            ) AND NOT EXISTS(
                SELECT 1 FROM accounts
                WHERE id IS ? AND account_id IS NOT NULL AND account_id <> ''
                  AND account_sync_source IS NOT NULL AND account_sync_source <> ''
            )
            """,
            expected.accountId, expected.externalAccountId, expected.source, expected.accountId,
        )
    }
    return bankSyncColumnsMatch(db, expected)
}

private fun requireBankSyncLink(db: Db, expected: ExpectedBankSyncLink?, accountId: String? = null) {
    if (!bankSyncLinkMatches(db, expected, accountId)) throw BankSyncDatabaseError.BankSyncMaterializationStale
}

private fun requireLiveBankSyncAccount(db: Db, accountId: String) {
    val liveAccountCount = db.long("SELECT COUNT(*) FROM accounts WHERE id IS ? AND (tombstone = 0 OR tombstone IS NULL)", accountId) ?: 0
    if (liveAccountCount != 1L) throw BankSyncDatabaseError.BankSyncMaterializationStale
}

private fun bankSyncColumnsMatch(db: Db, expected: ExpectedBankSyncLink): Boolean = db.bool(
    """
    SELECT EXISTS(
        SELECT 1 FROM accounts
        WHERE id IS ? AND account_id IS ? AND account_sync_source IS ?
          AND (tombstone = 0 OR tombstone IS NULL)
    )
    """,
    expected.accountId, expected.externalAccountId, expected.source,
)

fun BudgetDatabase.requireLiveBankSyncAccount(accountId: String) = dbQueue.read { requireLiveBankSyncAccount(it, accountId) }

/**
 * The day (`YYYYMMDD`) of the budget's earliest CRDT message — the day the
 * budget file began, wherever it began: the messages travel with the file,
 * so every device answers the same. Null for a budget with no messages yet.
 * That day is UTC, deliberately: agreeing across devices is the whole point.
 */
suspend fun BudgetDatabase.earliestMessageDay(): Int? = dbQueue.readAsync { db ->
    val timestamp = db.string("SELECT MIN(timestamp) FROM messages_crdt") ?: return@readAsync null
    if (timestamp.length < 10) null else timestamp.take(10).replace("-", "").toIntOrNull()
}

/** The id of an account's opening-balance row, if it has one. */
suspend fun BudgetDatabase.startingBalanceTransactionId(accountId: String): String? = dbQueue.readAsync { db ->
    db.string(
        "SELECT id FROM transactions WHERE acct = ? AND starting_balance_flag = 1 AND (tombstone = 0 OR tombstone IS NULL) ORDER BY date",
        accountId,
    )
}

/**
 * Every account wired up to a bank feed, in the order the accounts tab lists
 * them. Empty on a budget file old enough to predate the columns.
 */
suspend fun BudgetDatabase.fetchBankSyncAccounts(): List<BankSyncAccount> = dbQueue.readAsync { db ->
    if ("account_sync_source" !in db.columns("accounts")) return@readAsync emptyList()
    db.fetchAll(
        """
        SELECT id, name, account_id, account_sync_source, offbudget, closed
        FROM accounts
        WHERE (tombstone = 0 OR tombstone IS NULL)
          AND account_id IS NOT NULL AND account_id <> ''
          AND account_sync_source IS NOT NULL AND account_sync_source <> ''
        ORDER BY offbudget, sort_order
        """,
    ).map { row ->
        BankSyncAccount(
            id = row.string("id") ?: "",
            name = row.string("name") ?: "Unknown",
            externalAccountId = row.string("account_id") ?: "",
            syncSource = row.string("account_sync_source") ?: "",
            offBudget = row.long("offbudget") == 1L,
            closed = row.long("closed") == 1L,
        )
    }
}

/**
 * The transactions a download could be matching, projected to the columns
 * `BankSyncReconciler` reads. Fuzzy candidates are bounded by date; exact
 * provider IDs are included account-wide. Split children are excluded (a
 * download matches the parent). Tombstoned rows are included so the
 * reconciler can use them as exact-ID dedupe keys.
 */
suspend fun BudgetDatabase.bankSyncWindow(accountId: String, from: Int, to: Int, importedIds: Set<String>): List<BankSyncExistingTransaction> =
    dbQueue.readAsync { db ->
        val ids = importedIds.toList()
        db.fetchAll(
            """
            SELECT id, date, amount, description, financial_id, imported_description,
                   notes, cleared, reconciled, tombstone
            FROM transactions
            WHERE acct = ?
              AND ((date IS NOT NULL AND date >= ? AND date <= ?)
                   OR financial_id IN (${placeholders(ids.size)}))
              AND (starting_balance_flag = 0 OR starting_balance_flag IS NULL)
              AND (isChild = 0 OR isChild IS NULL)
            """,
            listOf<Any?>(accountId, from, to) + ids,
        ).map { row ->
            BankSyncExistingTransaction(
                id = row.string("id") ?: "",
                date = row.int("date") ?: 0,
                amount = row.long("amount") ?: 0,
                payeeId = row.string("description"),
                importedId = row.string("financial_id"),
                importedPayee = row.string("imported_description"),
                notes = row.string("notes"),
                cleared = row.long("cleared") == 1L,
                reconciled = row.long("reconciled") == 1L,
                tombstone = row.long("tombstone") == 1L,
            )
        }
    }

/** The date of an account's earliest transaction, or null when it has none. */
suspend fun BudgetDatabase.oldestTransactionDate(accountId: String): Int? = dbQueue.readAsync { db ->
    db.long("SELECT MIN(date) FROM transactions WHERE acct = ? AND date IS NOT NULL AND (tombstone = 0 OR tombstone IS NULL)", accountId)?.toInt()
}

private const val BANK_SYNC_UPDATE_GUARDS = """
    AND (tombstone = 0 OR tombstone IS NULL)
    AND (reconciled = 0 OR reconciled IS NULL)
    AND (starting_balance_flag = 0 OR starting_balance_flag IS NULL)
    AND (isChild = 0 OR isChild IS NULL)
    AND COALESCE(date, 0) = ?
    AND COALESCE(amount, 0) = ?
    AND description IS ?
    AND financial_id IS ?
    AND imported_description IS ?
    AND notes IS ?
    AND COALESCE(cleared, 0) = ?
"""

private fun expectedArguments(update: BankSyncUpdate): List<Any?> = listOf(
    update.expected.date,
    update.expected.amount,
    update.expected.payeeId,
    update.expected.importedId,
    update.expected.importedPayee,
    update.expected.notes,
    if (update.expected.cleared) 1 else 0,
)

data class BankSyncUpdateResult(val updatedCount: Int, val messages: List<CRDTMessage>)

/**
 * Apply the reconciler's updates to transactions the download matched, with
 * their CRDT messages, in one SQLite transaction. Each update only lands if
 * the row still holds exactly what the reconciler saw.
 */
fun BudgetDatabase.applyBankSyncUpdates(updates: List<BankSyncUpdate>, expectedLink: ExpectedBankSyncLink, messages: List<CRDTMessage>): BankSyncUpdateResult =
    dbQueue.write { db ->
        requireBankSyncLink(db, expectedLink)
        val appliedIds = HashSet<String>()
        for (update in updates) {
            db.execute(
                """
                UPDATE transactions
                SET financial_id = ?, description = ?, imported_description = ?, notes = ?, cleared = ?
                WHERE id = ? AND (? IS NULL OR acct IS ?)
                $BANK_SYNC_UPDATE_GUARDS
                """,
                listOf<Any?>(
                    update.importedId, update.payeeId, update.importedPayee, update.notes, if (update.cleared) 1 else 0,
                    update.existingId, expectedLink.accountId, expectedLink.accountId,
                ) + expectedArguments(update),
            )
            if (db.changes > 0) appliedIds += update.existingId
        }
        BankSyncUpdateResult(appliedIds.size, insertMessageRows(db, messages.filter { it.row in appliedIds }))
    }

data class BankSyncMaterialization(val updatedCount: Int, val inserted: List<Transaction>, val messages: List<CRDTMessage>)

fun BudgetDatabase.materializeBankSync(
    updates: List<BankSyncUpdate>,
    updateMessages: List<CRDTMessage>,
    inserts: List<PreparedBankSyncInsert>,
    openingInsert: BankSyncOpeningInsert?,
    openingUpdate: BankSyncOpeningUpdate?,
    expectedLink: ExpectedBankSyncLink,
    rulesFingerprint: BankSyncRulesFingerprint,
): BankSyncMaterialization = dbQueue.write { db ->
    if (rulesFingerprint(db) != rulesFingerprint) throw BankSyncDatabaseError.BankSyncRulesChanged
    requireBankSyncLink(db, expectedLink)
    val allMessages = mutableListOf<CRDTMessage>()
    val appliedIds = HashSet<String>()
    for (update in updates) {
        db.execute(
            """
            UPDATE transactions
            SET financial_id = ?, description = ?, imported_description = ?, notes = ?, cleared = ?
            WHERE id = ? AND acct IS ?
            $BANK_SYNC_UPDATE_GUARDS
            """,
            listOf<Any?>(
                update.importedId, update.payeeId, update.importedPayee, update.notes, if (update.cleared) 1 else 0,
                update.existingId, expectedLink.accountId,
            ) + expectedArguments(update),
        )
        if (db.changes > 0) appliedIds += update.existingId
    }
    allMessages += insertMessageRows(db, updateMessages.filter { it.row in appliedIds })

    val inserted = mutableListOf<Transaction>()
    val insertedPendingPayees = HashMap<String, Payee>()
    for (prepared in inserts) {
        val (didInsert, messages) = insertBankSyncTransactionWithMessages(
            db,
            prepared.transaction,
            prepared.messages,
            prepared.pendingPayees,
            FinancialIdPolicy.Occurrences(prepared.maxLiveFinancialIdOccurrences),
            insertedPendingPayees,
            expectedLink,
        )
        allMessages += messages
        if (didInsert) inserted += prepared.transaction
    }
    val insertedIds = inserted.map { it.id }.toSet()
    if (openingInsert != null) {
        if (insertedIds != openingInsert.expectedInsertedIds) throw BankSyncDatabaseError.BankSyncMaterializationStale
        if (!db.bool("SELECT EXISTS(SELECT 1 FROM payees WHERE id IS ?)", openingInsert.payee.id)) {
            db.execute(
                "INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, ?, ?, ?)",
                openingInsert.payee.id, openingInsert.payee.name, openingInsert.payee.transferAccountId,
                if (openingInsert.payee.tombstone) 1 else 0,
            )
            db.execute("INSERT INTO payee_mapping (id, targetId) VALUES (?, ?)", openingInsert.payee.id, openingInsert.payee.id)
        }
        insertTransactionRow(db, openingInsert.transaction)
        allMessages += insertMessageRows(db, openingInsert.messages)
    }
    if (openingUpdate != null) {
        if (insertedIds != openingUpdate.expectedInsertedIds) throw BankSyncDatabaseError.BankSyncMaterializationStale
        val opening = openingUpdate.transaction
        db.execute(
            """
            UPDATE transactions SET amount = ?
            WHERE id = ? AND acct IS ? AND amount = ? AND date = ?
                AND COALESCE(tombstone, 0) = 0
                AND COALESCE(reconciled, 0) = ?
                AND COALESCE(isParent, 0) = 0 AND COALESCE(isChild, 0) = 0
                AND starting_balance_flag = 1
            """,
            opening.amount, opening.id, opening.accountId, openingUpdate.expectedAmount, opening.date, if (opening.reconciled) 1 else 0,
        )
        if (db.changes != 1) throw BankSyncDatabaseError.BankSyncMaterializationStale
        allMessages += insertMessageRows(db, openingUpdate.messages)
    }
    BankSyncMaterialization(appliedIds.size, inserted, allMessages)
}

/**
 * Point an account at a provider's account, writing the institution row it
 * points at alongside it, with all of their CRDT messages, in one SQLite
 * transaction. Existing live institution rows are reused; tombstoned rows are
 * revived when the proposal wins.
 */
fun BudgetDatabase.applyBankSyncLink(
    accountId: String,
    externalAccountId: String,
    syncSource: String,
    proposal: BankSyncLinkProposal,
    expectedOldLink: ExpectedBankSyncLink? = null,
    verifyExpectedOldLink: Boolean = false,
    messages: List<CRDTMessage>,
): List<CRDTMessage> = dbQueue.write { db ->
    requireLiveBankSyncAccount(db, accountId)
    if (verifyExpectedOldLink) requireBankSyncLink(db, expectedOldLink, accountId)
    val canonical = db.fetchAll(
        """
        SELECT id, bank_id, name, tombstone
        FROM banks
        WHERE bank_id = ?
        ORDER BY CASE WHEN tombstone = 0 OR tombstone IS NULL THEN 0 ELSE 1 END, id
        """,
        proposal.bank.bankId,
    ).firstOrNull()
    val canonicalIsLive = canonical?.let { (it.long("tombstone") ?: 0) == 0L } ?: false
    val proposalStillWins = when {
        proposal.created -> canonical == null
        canonical != null -> canonical.string("id") == proposal.bank.id && canonicalIsLive == !proposal.revived
        else -> false
    }
    if (!proposalStillWins) throw BankSyncDatabaseError.BankSyncLinkChanged
    if (proposal.created) {
        db.execute("INSERT INTO banks (id, bank_id, name, tombstone) VALUES (?, ?, ?, 0)", proposal.bank.id, proposal.bank.bankId, proposal.bank.name)
    } else if (proposal.revived) {
        db.execute("UPDATE banks SET bank_id = ?, name = ?, tombstone = 0 WHERE id = ?", proposal.bank.bankId, proposal.bank.name, proposal.bank.id)
    }
    db.execute("UPDATE accounts SET account_id = ?, account_sync_source = ?, bank = ? WHERE id = ?", externalAccountId, syncSource, proposal.bank.id, accountId)
    db.execute("DELETE FROM bank_sync_local_links WHERE account_id IS ?", accountId)
    insertMessageRows(db, messages)
}

/**
 * Select the canonical institution without changing any materialized row.
 * Live rows win over tombstones; otherwise the oldest tombstoned row is
 * proposed for revival before a new id is proposed.
 */
fun BudgetDatabase.proposeBankSyncLink(proposedBank: Bank): BankSyncLinkProposal = dbQueue.read { db ->
    val row = db.fetchAll(
        """
        SELECT id, bank_id, name, tombstone
        FROM banks
        WHERE bank_id = ?
        ORDER BY CASE WHEN tombstone = 0 OR tombstone IS NULL THEN 0 ELSE 1 END, id
        """,
        proposedBank.bankId,
    ).firstOrNull()
    when {
        row != null && (row.long("tombstone") ?: 0) == 0L ->
            BankSyncLinkProposal(Bank(row.string("id") ?: "", row.string("bank_id") ?: "", row.string("name") ?: ""), created = false, revived = false)
        row != null -> BankSyncLinkProposal(Bank(row.string("id") ?: "", proposedBank.bankId, proposedBank.name), created = false, revived = true)
        else -> BankSyncLinkProposal(proposedBank, created = true, revived = false)
    }
}

/**
 * Cut an account loose from its bank feed. Clears every column upstream's own
 * unlink clears, not just the three that point at the provider: a
 * left-behind `bank_sync_status` would keep showing an error badge in the web
 * UI for an account that no longer syncs at all.
 */
fun BudgetDatabase.applyBankSyncUnlink(accountId: String, expectedLink: ExpectedBankSyncLink, messages: List<CRDTMessage>): List<CRDTMessage> =
    dbQueue.write { db ->
        if (expectedLink.source == BankSyncSource.FinanceKit.rawValue) {
            // Wallet links an iOS device once wrote into the synced columns.
            if (!bankSyncColumnsMatch(db, expectedLink)) throw BankSyncDatabaseError.BankSyncMaterializationStale
        } else {
            requireBankSyncLink(db, expectedLink)
        }
        db.execute(
            """
            UPDATE accounts
            SET account_id = NULL, account_sync_source = NULL, bank = NULL,
                balance_current = NULL, balance_available = NULL, balance_limit = NULL,
                bank_sync_status = NULL
            WHERE id = ?
            """,
            accountId,
        )
        if (expectedLink.source != BankSyncSource.FinanceKit.rawValue) {
            db.execute("DELETE FROM bank_sync_local_links WHERE account_id IS ?", accountId)
        }
        insertMessageRows(db, messages)
    }

data class BankSyncStatusEntry(
    val accountId: String,
    val lastSync: String?,
    val status: String,
    val expectedLink: ExpectedBankSyncLink,
    val messages: List<CRDTMessage>,
)

/**
 * Stamp what a sync did on the account, the way every other Actual client
 * does, so the web UI's "last synced" and status badge reflect a sync this
 * device ran.
 */
fun BudgetDatabase.applyBankSyncStatus(entries: List<BankSyncStatusEntry>): List<CRDTMessage> = dbQueue.write { db ->
    val appliedMessages = mutableListOf<CRDTMessage>()
    for (entry in entries) {
        if (entry.accountId != entry.expectedLink.accountId || !bankSyncLinkMatches(db, entry.expectedLink)) continue
        // A failed sync leaves last_sync alone rather than nulling it: "we
        // last had good data at X" stays true, and upstream does the same.
        if (entry.lastSync == null) {
            db.execute("UPDATE accounts SET bank_sync_status = ? WHERE id = ?", entry.status, entry.accountId)
        } else {
            db.execute("UPDATE accounts SET last_sync = ?, bank_sync_status = ? WHERE id = ?", entry.lastSync, entry.status, entry.accountId)
        }
        appliedMessages += entry.messages
    }
    insertMessageRows(db, appliedMessages)
}

/**
 * The institution row a provider's institution id already has, if any —
 * upstream's `findOrCreateBank` lookup half, so two accounts at the same bank
 * share one row.
 */
suspend fun BudgetDatabase.bank(bankId: String): Bank? = dbQueue.readAsync { db ->
    val row = db.fetchOne("SELECT id, bank_id, name FROM banks WHERE bank_id = ? AND (tombstone = 0 OR tombstone IS NULL)", bankId)
        ?: return@readAsync null
    Bank(row.string("id") ?: "", bankId, row.string("name") ?: "")
}

// Transaction Update

/** Update an existing transaction's columns in place. The caller emits CRDT messages for the same fields. */
fun BudgetDatabase.updateTransaction(transaction: Transaction) = dbQueue.write { updateTransactionRow(it, transaction) }

/**
 * Updates a transaction and stores its CRDT messages in one SQLite
 * transaction. A failed message insert must roll the row update back, or
 * another device can never learn about the local edit.
 */
fun BudgetDatabase.updateTransactionWithMessages(transaction: Transaction, messages: List<CRDTMessage>): List<CRDTMessage> = dbQueue.write { db ->
    updateTransactionRow(db, transaction)
    insertMessageRows(db, messages)
}

/**
 * Updates every row and persists every generated message in one SQLite
 * transaction. Message generation must happen before this is called so a
 * failure cannot leave only part of a bulk edit applied.
 */
fun BudgetDatabase.updateTransactionsWithMessages(updates: List<Pair<Transaction, List<CRDTMessage>>>): List<CRDTMessage> = dbQueue.write { db ->
    for ((transaction, messages) in updates) {
        updateTransactionRow(db, transaction)
        // The editor omits schedule, so only explicit schedule edits write it.
        if (messages.any { it.column == "schedule" }) {
            db.execute("UPDATE transactions SET schedule = ? WHERE id = ?", transaction.schedule, transaction.id)
        }
    }
    insertMessageRows(db, updates.flatMap { it.second })
}

internal fun updateTransactionRow(db: Db, transaction: Transaction) {
    db.execute(
        """
        UPDATE transactions
        SET acct = ?, date = ?, description = ?, category = ?, amount = ?,
            notes = ?, cleared = ?, reconciled = ?, transferred_id = ?,
            isParent = ?, parent_id = ?, tombstone = ?
        WHERE id = ?
        """,
        transaction.accountId,
        transaction.date,
        transaction.payeeId,
        transaction.categoryId,
        transaction.amount,
        transaction.notes,
        if (transaction.cleared) 1 else 0,
        if (transaction.reconciled) 1 else 0,
        transaction.transferId,
        if (transaction.isParent) 1 else 0,
        transaction.parentId,
        if (transaction.tombstone) 1 else 0,
        transaction.id,
    )
}
