package io.github.traushb.actuali.support

import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.database.BudgetMetadata
import io.github.traushb.actuali.services.database.DatabaseQueue
import io.github.traushb.actuali.services.database.Row
import io.github.traushb.actuali.services.network.ActualServerClient
import io.github.traushb.actuali.services.sync.SyncClient
import java.io.File
import java.nio.file.Files

/**
 * Upstream table DDL for suites that hand-build a budget file. Each table is
 * the union of the columns those suites need. Which tables a suite creates
 * still matters (`BudgetDatabase` probes for notes, preferences, rules,
 * schedules, tags, zero_budget_months and the two budget tables), so suites
 * pick tables rather than getting everything.
 */
object TestSchema {
    const val ACCOUNTS = """
    CREATE TABLE accounts (
        id TEXT PRIMARY KEY, name TEXT, type TEXT, offbudget INTEGER DEFAULT 0,
        closed INTEGER DEFAULT 0, sort_order REAL, tombstone INTEGER DEFAULT 0,
        account_id TEXT, account_sync_source TEXT, bank TEXT, balance_current INTEGER,
        balance_available INTEGER, balance_limit INTEGER, mask TEXT, official_name TEXT,
        subtype TEXT, bank_sync_status TEXT
    )
    """
    const val TRANSACTIONS = """
    CREATE TABLE transactions (
        id TEXT PRIMARY KEY, starting_balance_flag INTEGER DEFAULT 0,
        isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0, acct TEXT, category TEXT,
        amount INTEGER, description TEXT, notes TEXT, date INTEGER,
        imported_description TEXT, financial_id TEXT, transferred_id TEXT, schedule TEXT,
        sort_order REAL, tombstone INTEGER DEFAULT 0, cleared INTEGER DEFAULT 0,
        reconciled INTEGER DEFAULT 0, parent_id TEXT, type TEXT, location TEXT, error TEXT
    )
    """
    const val PAYEES = """
    CREATE TABLE payees (
        id TEXT PRIMARY KEY, name TEXT, category TEXT, transfer_acct TEXT,
        tombstone INTEGER DEFAULT 0
    )
    """
    const val PAYEE_MAPPING = "CREATE TABLE payee_mapping (id TEXT PRIMARY KEY, targetId TEXT)"
    const val CATEGORIES = """
    CREATE TABLE categories (
        id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, cat_group TEXT,
        sort_order REAL, hidden INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0
    )
    """
    const val CATEGORY_GROUPS = """
    CREATE TABLE category_groups (
        id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, sort_order REAL,
        hidden INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0
    )
    """
    const val CATEGORY_MAPPING = "CREATE TABLE category_mapping (id TEXT PRIMARY KEY, transferId TEXT)"
    const val MESSAGES_CRDT = """
    CREATE TABLE messages_crdt (
        id INTEGER PRIMARY KEY, timestamp TEXT NOT NULL UNIQUE, dataset TEXT NOT NULL,
        row TEXT NOT NULL, column TEXT NOT NULL, value BLOB NOT NULL
    )
    """
    const val MESSAGES_CLOCK = "CREATE TABLE messages_clock (id INTEGER PRIMARY KEY, clock TEXT)"
    const val PREFERENCES = "CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT)"
    const val NOTES = "CREATE TABLE notes (id TEXT PRIMARY KEY, note TEXT)"
    const val ZERO_BUDGETS = """
    CREATE TABLE zero_budgets (
        id TEXT PRIMARY KEY, month INTEGER, category TEXT, amount INTEGER DEFAULT 0,
        carryover INTEGER DEFAULT 0
    )
    """
    const val REFLECT_BUDGETS = """
    CREATE TABLE reflect_budgets (
        id TEXT PRIMARY KEY, month INTEGER, category TEXT, amount INTEGER DEFAULT 0,
        carryover INTEGER DEFAULT 0
    )
    """
    const val ZERO_BUDGET_MONTHS = "CREATE TABLE zero_budget_months (id TEXT PRIMARY KEY, buffered INTEGER DEFAULT 0)"
    const val RULES = """
    CREATE TABLE rules (
        id TEXT PRIMARY KEY, stage TEXT, conditions TEXT, actions TEXT,
        tombstone INTEGER DEFAULT 0, conditions_op TEXT DEFAULT 'and'
    )
    """
    const val SCHEDULES = """
    CREATE TABLE schedules (
        id TEXT PRIMARY KEY, name TEXT, rule TEXT, active INTEGER DEFAULT 0,
        completed INTEGER DEFAULT 0, posts_transaction INTEGER DEFAULT 0,
        tombstone INTEGER DEFAULT 0
    )
    """
    const val SCHEDULES_NEXT_DATE = """
    CREATE TABLE schedules_next_date (
        id TEXT PRIMARY KEY, schedule_id TEXT, local_next_date INTEGER,
        local_next_date_ts INTEGER, base_next_date INTEGER, base_next_date_ts INTEGER
    )
    """
    const val TAGS = """
    CREATE TABLE tags (
        id TEXT PRIMARY KEY, tag TEXT, color TEXT, description TEXT,
        hidden BOOLEAN DEFAULT 0, tombstone INTEGER DEFAULT 0
    )
    """

    /** The tables every transaction-writing suite needs. */
    val core = listOf(ACCOUNTS, TRANSACTIONS, PAYEES, PAYEE_MAPPING, CATEGORIES, CATEGORY_GROUPS, CATEGORY_MAPPING, MESSAGES_CRDT)

    /** A freshly downloaded upstream file: the tables `loadLocalBudget` opens, with upstream's own column types and bookkeeping tables. */
    const val UPSTREAM = """
    CREATE TABLE accounts (
        id TEXT PRIMARY KEY, name TEXT, type TEXT, offbudget INTEGER DEFAULT 0,
        closed INTEGER DEFAULT 0, tombstone INTEGER DEFAULT 0, sort_order REAL,
        account_id TEXT, balance_current INTEGER, balance_available INTEGER,
        balance_limit INTEGER, mask TEXT, official_name TEXT, subtype TEXT, bank TEXT
    );
    CREATE TABLE transactions (
        id TEXT PRIMARY KEY, isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0,
        acct TEXT, category TEXT, amount INTEGER, description TEXT, notes TEXT,
        date INTEGER, financial_id TEXT, type TEXT, location TEXT, error TEXT,
        imported_description TEXT, starting_balance_flag INTEGER DEFAULT 0,
        transferred_id TEXT, sort_order REAL, tombstone INTEGER DEFAULT 0,
        cleared INTEGER DEFAULT 0, reconciled INTEGER DEFAULT 0, parent_id TEXT,
        schedule TEXT
    );
    CREATE TABLE categories (
        id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, cat_group TEXT,
        sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN NOT NULL DEFAULT 0
    );
    CREATE TABLE category_groups (
        id TEXT PRIMARY KEY, name TEXT UNIQUE, is_income INTEGER DEFAULT 0,
        sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN NOT NULL DEFAULT 0
    );
    CREATE TABLE payees (
        id TEXT PRIMARY KEY, name TEXT, category TEXT, tombstone INTEGER DEFAULT 0,
        transfer_acct TEXT
    );
    CREATE TABLE payee_mapping (id TEXT PRIMARY KEY, targetId TEXT);
    CREATE TABLE category_mapping (id TEXT PRIMARY KEY, transferId TEXT);
    CREATE TABLE zero_budgets (
        id TEXT PRIMARY KEY, month INTEGER, category TEXT, amount INTEGER DEFAULT 0,
        carryover INTEGER DEFAULT 0
    );
    CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT);
    CREATE TABLE messages_crdt (
        id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT NOT NULL UNIQUE,
        dataset TEXT NOT NULL, row TEXT NOT NULL, column TEXT NOT NULL, value BLOB NOT NULL
    );
    CREATE TABLE messages_clock (id INTEGER PRIMARY KEY, clock TEXT);
    CREATE TABLE db_version (version TEXT PRIMARY KEY);
    CREATE TABLE __migrations__ (id INT PRIMARY KEY NOT NULL);
    """
}

fun tempFile(prefix: String, suffix: String): File = File.createTempFile(prefix, suffix).also { it.delete() }

fun tempDir(): File = Files.createTempDirectory("actuali-test").toFile()

/** A fresh temp-file budget built from [sql] (DDL and seed rows alike), opened through BudgetDatabase so its migrations run as on device. */
fun makeTestDatabase(vararg sql: String): Pair<BudgetDatabase, File> = makeTestDatabase(sql.toList())

fun makeTestDatabase(sql: List<String>): Pair<BudgetDatabase, File> {
    val file = tempFile("test-", ".sqlite")
    DatabaseQueue(file.path).use { queue -> queue.write { db -> sql.filter { it.isNotEmpty() }.forEach(db::executeScript) } }
    return BudgetDatabase(file.path) to file
}

fun cleanup(file: File) {
    file.deleteRecursively()
}

/** Every CRDT message a write produced, oldest first. */
fun messageRows(file: File): List<Row> =
    DatabaseQueue(file.path).use { queue -> queue.read { db -> db.fetchAll("SELECT * FROM messages_crdt ORDER BY timestamp") } }

/** A sync client over [database] with no server configured, so writes queue locally and never push. */
suspend fun makeTestSyncClient(database: BudgetDatabase): SyncClient =
    SyncClient(ActualServerClient(), nodeId = "89e0e8e90b203f9e").also { it.configure(database, "test-file", "test-group") }

/** Writes a budget `loadLocalBudget(id)` can open: the database built from [sql] plus its metadata. */
fun seedBudget(id: String, manager: BudgetFileManager, sql: String = TestSchema.UPSTREAM) {
    manager.budgetDirectory(id).mkdirs()
    DatabaseQueue(manager.databasePath(id).path).use { queue -> queue.write { db -> db.executeScript(sql) } }
    manager.metadataPath(id).writeBytes(
        BudgetMetadata(id = id, budgetName = "Seed", cloudFileId = "cf-1", groupId = "group-1").encoded(),
    )
}

/** A plain transaction for engine tests; only the fields a test names differ from the blank row. */
fun tx(
    date: Int,
    amount: Long,
    accountId: String = "a1",
    id: String = java.util.UUID.randomUUID().toString(),
    categoryId: String? = null,
    categoryName: String? = null,
    payeeId: String? = null,
    payeeName: String? = null,
    notes: String? = null,
    transferId: String? = null,
    transferAcct: String? = null,
    isParent: Boolean = false,
    tombstone: Boolean = false,
    cleared: Boolean = false,
    importedPayee: String? = null,
    sortOrder: Double? = null,
    schedule: String? = null,
) = io.github.traushb.actuali.models.Transaction(
    id = id, accountId = accountId, date = date, amount = amount, payeeId = payeeId, payeeName = payeeName,
    categoryId = categoryId, categoryName = categoryName, notes = notes, cleared = cleared, reconciled = false,
    transferId = transferId, isParent = isParent, parentId = null, tombstone = tombstone, sortOrder = sortOrder,
    importedPayee = importedPayee, schedule = schedule, transferAcct = transferAcct,
)
