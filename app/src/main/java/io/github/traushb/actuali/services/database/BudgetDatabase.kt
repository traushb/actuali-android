package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.banksync.Bank
import io.github.traushb.actuali.services.rules.RuleContext
import io.github.traushb.actuali.services.sync.CRDTMessage
import io.github.traushb.actuali.services.sync.CRDTValue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

private val logger = Logger("BudgetDatabase")

sealed class BankSyncDatabaseError : Exception() {
    object BankSyncLinkChanged : BankSyncDatabaseError()
    object BankSyncRulesChanged : BankSyncDatabaseError()
    object BankSyncMaterializationStale : BankSyncDatabaseError()
    object BankSyncPendingPayeeConflict : BankSyncDatabaseError()
}

data class BankSyncRulesFingerprint(val data: ByteArray) {
    override fun equals(other: Any?): Boolean = other is BankSyncRulesFingerprint && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    companion object {
        val empty = BankSyncRulesFingerprint(ByteArray(0))
    }
}

data class BankSyncRulesSnapshot(
    val rules: List<io.github.traushb.actuali.models.Rule>,
    val context: RuleContext,
    val fingerprint: BankSyncRulesFingerprint,
)

data class BankSyncLinkProposal(val bank: Bank, val created: Boolean, val revived: Boolean)

data class PreparedBankSyncInsert(
    val transaction: Transaction,
    val messages: List<CRDTMessage>,
    val pendingPayees: List<Payee>,
    val maxLiveFinancialIdOccurrences: Int,
)

data class BankSyncOpeningInsert(
    val transaction: Transaction,
    val payee: Payee,
    val messages: List<CRDTMessage>,
    val expectedInsertedIds: Set<String>,
)

data class BankSyncOpeningUpdate(
    val expectedAmount: Long,
    val transaction: Transaction,
    val messages: List<CRDTMessage>,
    val expectedInsertedIds: Set<String>,
)

/**
 * SQLite access for a single budget file.
 *
 * Methods are deliberately split between suspend and blocking, and new
 * methods must pick the side that matches their caller:
 *
 * - **Suspend methods** are UI-facing reads called from `BudgetStore` on the
 *   main thread. They run via `dbQueue.readAsync` so the query executes on
 *   the IO dispatcher and never blocks the main thread. Any new read that
 *   feeds published UI state belongs here.
 *
 * - **Blocking methods** are `SyncClient`'s transactional paths:
 *   single-transaction shapes (insert/apply/filter messages, clock
 *   persistence) that the sync client must complete without a suspension
 *   point. In particular, `saveClock` must stay blocking — `SyncClient`
 *   relies on the clock read → assignment → save sequence running without
 *   interleaving.
 *
 * The queries are split across `BudgetDatabase*.kt` files by area.
 */
class BudgetDatabase(path: String) : AutoCloseable {
    internal val dbQueue: DatabaseQueue = DatabaseQueue(path)

    init {
        try {
            runPendingMigrations()
        } catch (e: Throwable) {
            dbQueue.close()
            throw e
        }
    }

    override fun close() = dbQueue.close()

    /** Test-only access so unit tests can seed the database directly. */
    val dbQueueForTesting: DatabaseQueue get() = dbQueue

    /**
     * Datasets written by CRDT applies (local or synced), so screens can
     * re-read when the rows behind them change — what GRDB's
     * `ValueObservation` gives the iOS app.
     */
    private val changedDatasets = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal fun notifyDatasetsChanged(messages: List<CRDTMessage>) {
        if (messages.isNotEmpty()) changedDatasets.tryEmit(messages.map { it.dataset }.toSet())
    }

    /** Emits once immediately, then whenever a write touches one of [tables]. */
    fun changes(vararg tables: String): Flow<Unit> = flow {
        emit(Unit)
        changedDatasets.filter { changed -> tables.any { it in changed } }.collect { emit(Unit) }
    }

    /** Includes an initial event, then only dashboard definition writes (including sync). */
    fun dashboardChanges(): Flow<Unit> = changes("dashboard", "dashboard_pages")

    sealed class TransactionWriteError : Exception() {
        object IncompleteFinancialIdMessages : TransactionWriteError()
    }

    fun messageTimestamps(dataset: String, row: String): List<String> = dbQueue.read { db ->
        db.strings(
            "SELECT timestamp FROM messages_crdt WHERE dataset = ? AND row = ? ORDER BY timestamp",
            dataset, row,
        )
    }

    // Schema Migrations

    private data class SchemaMigration(
        val id: Long,
        val table: String,
        val addsColumn: String?,
        val requiresColumns: List<String>,
        val sql: String,
    )

    private fun runPendingMigrations() {
        if (!dbQueue.read { pendingMigrationWork(it) }) return
        dbQueue.write { db ->
            db.execute("CREATE TABLE IF NOT EXISTS __migrations__ (id INTEGER PRIMARY KEY)")
            val appliedIds = db.longs("SELECT id FROM __migrations__").toSet()

            // CREATE migrations: run unconditionally (CREATE IF NOT EXISTS handles existing tables)
            for ((id, sql) in createTableMigrations) {
                if (id in appliedIds) continue
                logger.info("Applying create-table migration $id")
                db.executeScript(sql)
                db.execute("INSERT INTO __migrations__ (id) VALUES (?)", id)
            }

            // Schema-guarded migrations: skip if the source table doesn't exist
            val addedColumns = mutableListOf<Pair<String, String>>()
            for (migration in upstreamSchemaMigrations) {
                if (migration.id in appliedIds) continue
                if (!db.tableExists(migration.table)) continue
                val existing = db.columns(migration.table).toSet()
                if (!existing.containsAll(migration.requiresColumns)) continue
                if (migration.addsColumn != null && migration.addsColumn in existing) {
                    // Downloaded file was already migrated by an up-to-date
                    // client; ALTER would fail with "duplicate column".
                    db.execute("INSERT INTO __migrations__ (id) VALUES (?)", migration.id)
                    continue
                }
                logger.info("Applying upstream schema migration ${migration.id}")
                db.execute(migration.sql)
                db.execute("INSERT INTO __migrations__ (id) VALUES (?)", migration.id)
                migration.addsColumn?.let { addedColumns += migration.table to it }
            }

            replayStoredMessages(db, addedColumns)
        }
    }

    companion object {
        /**
         * Upstream Actual schema migrations we mirror. These only run if the
         * source table exists and every `requiresColumns` column is present
         * (otherwise they stay unapplied and are retried on a later open). When
         * `addsColumn` is already present — a freshly downloaded file migrated
         * by an up-to-date client — the migration is recorded as applied
         * without executing, since the ALTER would fail with "duplicate
         * column". CREATE migrations always run (CREATE TABLE IF NOT EXISTS
         * handles idempotency).
         */
        private val upstreamSchemaMigrations = listOf(
            // Second half of upstream 1765518577215 (multiple dashboards, see
            // createTableMigrations): widgets gain a page pointer.
            SchemaMigration(1_765_518_577_216, "dashboard", "dashboard_page_id", emptyList(), "ALTER TABLE dashboard ADD COLUMN dashboard_page_id TEXT"),
            // Upstream 1694438752000 (goal templates) alters three tables; split
            // here so each waits for its own table.
            SchemaMigration(1_694_438_752_000, "zero_budgets", "goal", emptyList(), "ALTER TABLE zero_budgets ADD COLUMN goal INTEGER DEFAULT null"),
            SchemaMigration(1_694_438_752_001, "reflect_budgets", "goal", emptyList(), "ALTER TABLE reflect_budgets ADD COLUMN goal INTEGER DEFAULT null"),
            SchemaMigration(1_694_438_752_002, "categories", "goal_def", emptyList(), "ALTER TABLE categories ADD COLUMN goal_def TEXT DEFAULT null"),
            // Upstream 1720665000000 (long goal context), same split.
            SchemaMigration(1_720_665_000_000, "zero_budgets", "long_goal", emptyList(), "ALTER TABLE zero_budgets ADD COLUMN long_goal INTEGER DEFAULT null"),
            SchemaMigration(1_720_665_000_001, "reflect_budgets", "long_goal", emptyList(), "ALTER TABLE reflect_budgets ADD COLUMN long_goal INTEGER DEFAULT null"),
            // Upstream 1754611200000 also rewrites NULL template_settings, but no
            // row can be NULL right after the ALTER's default applies.
            SchemaMigration(
                1_754_611_200_000, "categories", "template_settings", emptyList(),
                "ALTER TABLE categories ADD COLUMN template_settings JSON DEFAULT '{\"source\": \"notes\"}'",
            ),
            SchemaMigration(1_769_000_000_000, "schedules", "custom_upcoming_length", emptyList(), "ALTER TABLE schedules ADD COLUMN custom_upcoming_length TEXT DEFAULT NULL"),
            // Upstream 1778510362740 also creates cleanup_groups (see createTableMigrations).
            SchemaMigration(1_778_510_362_741, "categories", "cleanup_def", emptyList(), "ALTER TABLE categories ADD COLUMN cleanup_def TEXT DEFAULT NULL"),
            SchemaMigration(1_780_099_200_000, "custom_reports", "show_trend_lines", emptyList(), "ALTER TABLE custom_reports ADD COLUMN show_trend_lines INTEGER DEFAULT 0"),
            SchemaMigration(1_780_327_681_000, "tags", "hidden", emptyList(), "ALTER TABLE tags ADD COLUMN hidden BOOLEAN DEFAULT 0"),
            // Locally minted: old snapshots can lack transactions.schedule, but
            // the transaction fetches select it. Must precede the index
            // migration below so both apply in one open.
            SchemaMigration(1_780_606_214_999, "transactions", "schedule", emptyList(), "ALTER TABLE transactions ADD COLUMN schedule TEXT"),
            SchemaMigration(1_780_606_215_000, "accounts", "bank_sync_status", emptyList(), "ALTER TABLE accounts ADD COLUMN bank_sync_status TEXT"),
            // Upstream ships both indexes as one migration (1780606215001);
            // split here so each waits for its own columns.
            SchemaMigration(
                1_780_606_215_001, "transactions", null, listOf("acct", "tombstone"),
                "CREATE INDEX IF NOT EXISTS idx_transactions_acct_tombstone ON transactions(acct, tombstone)",
            ),
            SchemaMigration(
                1_780_606_215_002, "transactions", null, listOf("schedule"),
                "CREATE INDEX IF NOT EXISTS idx_transactions_schedule ON transactions(schedule)",
            ),
            // Locally minted ids for the bank-sync columns, which upstream added
            // long before any migration in this list.
            SchemaMigration(1_780_606_215_003, "accounts", "account_sync_source", emptyList(), "ALTER TABLE accounts ADD COLUMN account_sync_source TEXT"),
            SchemaMigration(1_780_606_215_004, "accounts", "last_sync", emptyList(), "ALTER TABLE accounts ADD COLUMN last_sync TEXT"),
        )

        /**
         * Tables added upstream after the original budget file was created.
         * These run unconditionally so CRDT messages targeting these tables
         * have somewhere to land.
         */
        private val createTableMigrations: List<Pair<Long, String>> = listOf(
            1_780_606_215_005L to """
                CREATE TABLE IF NOT EXISTS bank_sync_local_links (
                    account_id TEXT PRIMARY KEY,
                    external_account_id TEXT NOT NULL,
                    source TEXT NOT NULL
                )
            """,
            // Envelope buffers are synced rows in Actual's zero_budget_months
            // table. Older files may not have received the table-creation
            // migration yet, but local writes still need a real CRDT target.
            1_780_606_215_006L to """
                CREATE TABLE IF NOT EXISTS zero_budget_months (
                    id TEXT PRIMARY KEY,
                    buffered INTEGER NOT NULL DEFAULT 0
                )
            """,
            // Upstream 1765518577215 (multiple dashboards): pages table. Only
            // the schema half — upstream also mints a default page and moves
            // widgets onto it, but that half generates no CRDT messages and
            // forks a fresh page id on every client that runs it.
            1_765_518_577_215L to """
                CREATE TABLE IF NOT EXISTS dashboard_pages (
                    id TEXT PRIMARY KEY,
                    name TEXT,
                    tombstone INTEGER DEFAULT 0
                )
            """,
            // Upstream 1768872504000 (Actual 26.4.0): payee locations. Same SQL
            // as upstream's migration, so we reuse its id.
            1_768_872_504_000L to """
                CREATE TABLE IF NOT EXISTS payee_locations (
                    id TEXT PRIMARY KEY,
                    payee_id TEXT,
                    latitude REAL,
                    longitude REAL,
                    created_at INTEGER,
                    tombstone INTEGER DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_payee_locations_payee_id ON payee_locations (payee_id);
                CREATE INDEX IF NOT EXISTS idx_payee_locations_tombstone_payee_created ON payee_locations (tombstone, payee_id, created_at);
                CREATE INDEX IF NOT EXISTS idx_payee_locations_geo_tombstone ON payee_locations (tombstone, latitude, longitude)
            """,
            1_770_000_000_001L to """
                CREATE TABLE IF NOT EXISTS dashboard (
                    id TEXT PRIMARY KEY,
                    type TEXT,
                    dashboard_page_id TEXT,
                    x INTEGER DEFAULT 0,
                    y INTEGER DEFAULT 0,
                    width INTEGER DEFAULT 4,
                    height INTEGER DEFAULT 2,
                    meta TEXT,
                    tombstone INTEGER NOT NULL DEFAULT 0
                )
            """,
            1_770_000_000_002L to """
                CREATE TABLE IF NOT EXISTS custom_reports (
                    id TEXT PRIMARY KEY,
                    name TEXT,
                    start_date TEXT,
                    end_date TEXT,
                    date_range TEXT,
                    mode TEXT,
                    group_by TEXT,
                    interval TEXT,
                    balance_type TEXT,
                    show_empty INTEGER DEFAULT 0,
                    show_offbudget INTEGER DEFAULT 0,
                    show_hidden INTEGER DEFAULT 0,
                    show_uncategorized INTEGER DEFAULT 0,
                    selected_categories TEXT,
                    graph_type TEXT,
                    conditions TEXT,
                    conditions_op TEXT,
                    metadata TEXT,
                    tombstone INTEGER NOT NULL DEFAULT 0
                )
            """,
            1_778_510_362_740L to """
                CREATE TABLE IF NOT EXISTS cleanup_groups (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    tombstone INTEGER DEFAULT 0
                )
            """,
            // Defensive, like the two above: `banks` is upstream base schema,
            // but the bank-sync link writes both the row and the accounts.bank
            // pointer to it.
            1_770_000_000_003L to """
                CREATE TABLE IF NOT EXISTS banks (
                    id TEXT PRIMARY KEY,
                    bank_id TEXT,
                    name TEXT,
                    tombstone INTEGER DEFAULT 0
                )
            """,
            1_770_000_000_004L to """
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT PRIMARY KEY,
                    tag TEXT,
                    color TEXT,
                    description TEXT,
                    hidden BOOLEAN DEFAULT 0,
                    tombstone INTEGER DEFAULT 0
                )
            """,
        )

        /**
         * Migration ids Actuali mints itself; no upstream migration file has
         * them (split halves of upstream migrations, plus defensive
         * backfills). Actual's import validates a file's __migrations__ rows
         * against its migrations directory and rejects unknown ids (loot-core
         * migrations.ts, checkDatabaseValidity), so backups strip these
         * before archiving. Any id added to the lists above that doesn't
         * exist upstream MUST also be listed here.
         */
        val actualiOnlyMigrationIds: List<Long> = listOf(
            1_765_518_577_216, // ALTER half of upstream 1765518577215 (dashboard_page_id)
            1_694_438_752_001, // second ALTER of upstream 1694438752000 (reflect goal)
            1_694_438_752_002, // third ALTER of upstream 1694438752000 (goal_def)
            1_720_665_000_001, // second ALTER of upstream 1720665000000 (reflect long_goal)
            1_770_000_000_001, // defensive CREATE dashboard
            1_770_000_000_002, // defensive CREATE custom_reports
            1_778_510_362_741, // ALTER half of upstream 1778510362740 (cleanup_def)
            1_780_606_214_999, // locally minted transactions.schedule backfill
            1_780_606_215_002, // second half of upstream index migration 1780606215001
            1_780_606_215_003, // locally minted accounts.account_sync_source backfill
            1_780_606_215_004, // locally minted accounts.last_sync backfill
            1_770_000_000_003, // defensive CREATE banks
            1_770_000_000_004, // defensive CREATE tags
            1_780_606_215_005, // device-local bank-sync link identities
            1_780_606_215_006, // envelope buffer rows
        )

        /**
         * Whether `runPendingMigrations()` would perform any write. Mirrors the
         * guards of the write path so a fully migrated file opens without ever
         * taking the write lock. Migrations whose guards aren't satisfied yet
         * are not work: the write path would skip them too.
         */
        fun pendingMigrationWork(db: Db): Boolean {
            if (!db.tableExists("__migrations__")) return true
            val appliedIds = db.longs("SELECT id FROM __migrations__").toSet()
            if (createTableMigrations.any { it.first !in appliedIds }) return true
            for (migration in upstreamSchemaMigrations) {
                if (migration.id in appliedIds) continue
                if (!db.tableExists(migration.table)) continue
                val existing = db.columns(migration.table).toSet()
                if (!existing.containsAll(migration.requiresColumns)) continue
                // Runnable, or the column already exists and needs its
                // bookkeeping row — either way the write path has work.
                return true
            }
            return false
        }

        /**
         * CRDT messages targeting columns the local schema didn't have yet are
         * skipped by applyMessages but kept in messages_crdt. Once a migration
         * adds such a column, materialize the latest stored value per row so
         * the data isn't missing until the next remote edit. HLC timestamp
         * strings order lexicographically, so MAX(timestamp) per row is the
         * winning message.
         */
        private fun replayStoredMessages(db: Db, addedColumns: List<Pair<String, String>>) {
            if (addedColumns.isEmpty() || !db.tableExists("messages_crdt")) return
            for ((table, column) in addedColumns) {
                val rows = db.fetchAll(
                    """
                    SELECT row, value, MAX(timestamp) AS ts
                    FROM messages_crdt
                    WHERE dataset = ? AND column = ?
                    GROUP BY row
                    """,
                    table, column,
                )
                if (rows.isEmpty()) continue
                logger.info("Replaying ${rows.size} stored message(s) into $table.$column")
                val quotedTable = quotedIdentifier(table)
                val quotedColumn = quotedIdentifier(column)
                for (row in rows) {
                    val rowId = row.string("row") ?: continue
                    val value = row.string("value") ?: continue
                    upsertValue(db, quotedTable, quotedColumn, rowId, CRDTValue.deserialize(value))
                }
            }
        }

        internal fun quotedIdentifier(name: String): String = Db.quoteIdentifier(name)

        /**
         * Write one CRDT cell: update the row if it exists, otherwise create it
         * with just the id and this column. `table`/`column` must already be
         * schema-validated and quoted by the caller.
         */
        internal fun upsertValue(db: Db, table: String, column: String, rowId: String, value: Any?) {
            if (db.fetchOne("SELECT id FROM $table WHERE id = ?", rowId) != null) {
                db.execute("UPDATE $table SET $column = ? WHERE id = ?", value, rowId)
            } else {
                db.execute("INSERT INTO $table (id, $column) VALUES (?, ?)", rowId, value)
            }
        }

        /**
         * Alive-child filter for every query that counts split children:
         * mirrors upstream v_transactions_internal_alive, which joins the
         * parent row of every is_child = 1 row and requires it to exist with
         * tombstone = 0, so children of tombstoned or never-materialized
         * parents count nowhere. [parent] is the joined parent row's alias.
         */
        internal fun aliveChildPredicate(parent: String): String =
            "(t.isChild = 0 OR t.isChild IS NULL OR ($parent.id IS NOT NULL AND ($parent.tombstone = 0 OR $parent.tombstone IS NULL)))"

        /**
         * SELECT + display-name joins + liveness filter shared by the
         * creation-detection and single-id transaction queries. The list
         * query (fetchTransactions) carries additional split-aware joins of
         * its own.
         */
        internal const val TRANSACTION_SELECT = """
            SELECT
                t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
                t.description, t.notes, t.date, t.imported_description,
                t.schedule,
                t.transferred_id, t.cleared, t.reconciled, t.sort_order,
                t.tombstone, t.parent_id,
                COALESCE(pa.name, p.name) as payee_name,
                c.name as category_name,
                p.transfer_acct as transfer_acct
            FROM transactions t
            LEFT JOIN payee_mapping pm ON pm.id = t.description
            LEFT JOIN payees p ON p.id = pm.targetId
            -- Transfer payees carry no name; their display name is the
            -- linked account's name (matches Actual's v_payees view).
            LEFT JOIN accounts pa ON pa.id = p.transfer_acct
                AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
            LEFT JOIN category_mapping cm ON cm.id = t.category
            LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
            WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
              AND (t.isChild = 0 OR t.isChild IS NULL)
              AND t.date IS NOT NULL
              AND t.acct IS NOT NULL
        """

        internal fun mapTransaction(row: Row): Transaction = Transaction(
            id = row.string("id") ?: "",
            accountId = row.string("acct") ?: "",
            date = row.int("date") ?: 0,
            amount = row.long("amount") ?: 0,
            payeeId = row.string("description"),
            payeeName = row.string("payee_name"),
            categoryId = row.string("category"),
            categoryName = row.string("category_name"),
            notes = row.string("notes"),
            cleared = row.long("cleared") == 1L,
            reconciled = row.long("reconciled") == 1L,
            transferId = row.string("transferred_id"),
            isParent = row.long("isParent") == 1L,
            parentId = row.string("parent_id"),
            tombstone = row.long("tombstone") == 1L,
            sortOrder = row.double("sort_order"),
            importedPayee = row.string("imported_description"),
            schedule = row.string("schedule"),
            transferAcct = row.string("transfer_acct"),
        )

        /** Rows per page in the transaction lists; a shorter page ends the result set. */
        const val TRANSACTION_PAGE_SIZE = 500

        /** "2025-12" → 202512; 0 when malformed. */
        internal fun monthStringToInt(month: String): Int {
            val parts = month.split("-").filter { it.isNotEmpty() }
            if (parts.size != 2) return 0
            val year = parts[0].toIntOrNull() ?: return 0
            val monthNum = parts[1].toIntOrNull() ?: return 0
            return year * 100 + monthNum
        }

        /** 202512 → 202601. */
        internal fun nextMonth(monthInt: Int): Int {
            val year = monthInt / 100
            val month = monthInt % 100
            return if (month == 12) (year + 1) * 100 + 1 else year * 100 + month + 1
        }

        internal fun duplicateName(name: String, names: List<String>): String? {
            val folded = name.uppercase()
            return names.firstOrNull { it.uppercase() == folded }
        }

        internal fun placeholders(count: Int): String = Db.placeholders(count)
    }

    // Backup Support

    /** Writes a consistent single-file snapshot of the live database, regardless of journal mode. */
    suspend fun snapshotDatabase(path: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        dbQueue.writeWithoutTransaction { db -> db.execute("VACUUM INTO ?", path) }
    }

    // Accounts

    suspend fun fetchAccounts(): List<Account> = dbQueue.readAsync { db ->
        val records = db.fetchAll(
            """
            SELECT id, name, type, offbudget, closed, sort_order FROM accounts
            WHERE (tombstone = 0 OR tombstone IS NULL)
            ORDER BY sort_order ASC
            """,
        )
        // Balances in one grouped query instead of a SUM per account (N+1).
        // Split transactions are stored as a parent row carrying the full
        // amount plus child rows carrying each portion, so the children sum
        // to the parent. Parents are excluded or every split would count
        // twice; children whose parent is tombstoned or missing are excluded
        // too (upstream's alive view joins the parent row itself). Transfer
        // legs still count; accounts with no transactions get 0.
        //
        // date IS NOT NULL mirrors upstream v_transactions_internal: a CRDT
        // update for a row whose insert messages are gone materializes a
        // half-applied row with no date, which official clients never show or
        // count (GH #275).
        val balances = db.fetchAll(
            """
            SELECT t.acct AS acct, COALESCE(SUM(t.amount), 0) AS balance
            FROM transactions t
            LEFT JOIN transactions p ON p.id = t.parent_id
            WHERE t.acct IS NOT NULL
              AND t.date IS NOT NULL
              AND (t.tombstone = 0 OR t.tombstone IS NULL)
              AND ${aliveChildPredicate("p")}
              AND (t.isParent = 0 OR t.isParent IS NULL)
            GROUP BY t.acct
            """,
        ).mapNotNull { row -> row.string("acct")?.let { it to (row.long("balance") ?: 0L) } }.toMap()

        records.map { record ->
            val id = record.string("id") ?: ""
            Account(
                id = id,
                name = record.string("name") ?: "Unknown",
                type = AccountType.fromRaw(record.string("type") ?: "checking") ?: AccountType.Checking,
                offBudget = record.long("offbudget") == 1L,
                closed = record.long("closed") == 1L,
                sortOrder = (record.double("sort_order") ?: 0.0).toLong(),
                balance = balances[id] ?: 0,
            )
        }
    }

    /** A month's income and spending for the accounts tab's summary group (GH #256). */
    data class AccountsMonthSummary(
        val incomeCents: Long = 0,
        /**
         * Spending sign-flipped to read as money out, so a normal month is
         * positive. Net activity, like the budget tab's Spent (GH #212):
         * refunds offset spending.
         */
        val expenseCents: Long = 0,
    ) {
        /** What the month kept: income less what actually went out. */
        val netCents: Long get() = incomeCents - expenseCents
    }

    /**
     * Income and spending for one "yyyy-MM" month, on the same footing as the
     * budget tab's Income and Spent (GH #256): categorised transactions in
     * on-budget accounts only, hidden categories and groups left out. Income
     * is the income categories' activity, expenses the rest. Split parents are
     * excluded and their children counted; deleted accounts are excluded as
     * sync-race orphans.
     */
    suspend fun fetchAccountsMonthSummary(month: String, excludingCategoryIds: Set<String> = emptySet()): AccountsMonthSummary =
        dbQueue.readAsync { db ->
            // Categories the user left out of Spent drop out of expense only,
            // so Net stays Income less the Spent shown beside it.
            val excluded = excludingCategoryIds.sorted()
            val excludedClause = if (excluded.isEmpty()) "" else " OR c.id IN (${placeholders(excluded.size)})"
            val row = db.fetchOne(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN c.is_income = 1 THEN t.amount ELSE 0 END), 0) AS income,
                    COALESCE(SUM(CASE WHEN c.is_income = 1$excludedClause THEN 0 ELSE -t.amount END), 0) AS expense
                FROM transactions t
                LEFT JOIN category_mapping cm ON cm.id = t.category
                JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
                JOIN category_groups g ON g.id = c.cat_group
                JOIN accounts a ON a.id = t.acct
                LEFT JOIN transactions par ON par.id = t.parent_id
                WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
                  AND (t.isParent = 0 OR t.isParent IS NULL)
                  AND ${aliveChildPredicate("par")}
                  AND (c.tombstone = 0 OR c.tombstone IS NULL)
                  AND (c.hidden = 0 OR c.hidden IS NULL)
                  AND (g.tombstone = 0 OR g.tombstone IS NULL)
                  AND (g.hidden = 0 OR g.hidden IS NULL)
                  AND a.offbudget = 0
                  AND (a.tombstone = 0 OR a.tombstone IS NULL)
                  AND (t.date / 100) = ?
                """,
                excluded + monthStringToInt(month),
            ) ?: return@readAsync AccountsMonthSummary()
            AccountsMonthSummary(row.long("income") ?: 0, row.long("expense") ?: 0)
        }
}
