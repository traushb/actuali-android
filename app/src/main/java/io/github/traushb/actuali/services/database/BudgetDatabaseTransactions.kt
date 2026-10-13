package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.AccountBalanceBreakdown
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionSearchMatcher
import io.github.traushb.actuali.models.TransactionStatusFilter
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.TRANSACTION_PAGE_SIZE
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.TRANSACTION_SELECT
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.aliveChildPredicate
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.mapTransaction
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.placeholders
import io.github.traushb.actuali.services.schedules.DayDate

/** Single live transaction by id, with display names. Null when missing or tombstoned. */
suspend fun BudgetDatabase.fetchTransaction(id: String): Transaction? = dbQueue.readAsync { db ->
    db.fetchOne("$TRANSACTION_SELECT AND t.id = ?", id)?.let(::mapTransaction)
}

/**
 * Page through transactions newest-first, optionally scoped to one account
 * and/or filtered by a free-text search. `search` applies the
 * TransactionSearchMatcher semantics (payee, category, notes, and
 * progressive amount matching) in SQL so it covers full history, not just
 * the loaded page. `statusFilter` filters in SQL for the same reason.
 */
suspend fun BudgetDatabase.fetchTransactions(
    accountId: String? = null,
    startDate: Int? = null,
    endDate: Int? = null,
    limit: Int = TRANSACTION_PAGE_SIZE,
    offset: Int = 0,
    search: String? = null,
    statusFilter: TransactionStatusFilter = TransactionStatusFilter.All,
): List<Transaction> = dbQueue.readAsync { db ->
    // The list's display payee: own payee first (transfer payees show the
    // linked account's name), else the split children's agreed payee.
    // Referenced by both SELECT and the search filter, which must match what
    // the row visibly shows.
    val payeeNameSQL = "COALESCE(pa.name, p.name, cpa.name, cp.name)"
    val sql = StringBuilder(
        """
        SELECT
            t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
            t.description, t.notes, t.date, t.imported_description,
            t.schedule,
            t.transferred_id, t.cleared, t.reconciled, t.sort_order,
            t.tombstone, t.parent_id,
            $payeeNameSQL as payee_name,
            c.name as category_name,
            p.transfer_acct as transfer_acct
        FROM transactions t
        LEFT JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        LEFT JOIN accounts pa ON pa.id = p.transfer_acct
            AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
        -- Split parents may carry no payee of their own (payees can live on
        -- the children, GH #47). When the live children agree on one payee,
        -- display it; mixed payees resolve NULL and the UI labels the row
        -- "Split".
        LEFT JOIN (
            SELECT ct.parent_id AS parent_id,
                   CASE WHEN COUNT(DISTINCT ct.description) = 1
                        THEN MIN(ct.description) END AS payee
            FROM transactions ct
            WHERE ct.isChild = 1
              AND (ct.tombstone = 0 OR ct.tombstone IS NULL)
              AND ct.description IS NOT NULL
            GROUP BY ct.parent_id
        ) child_payee ON t.isParent = 1 AND child_payee.parent_id = t.id
        LEFT JOIN payee_mapping cpm ON cpm.id = child_payee.payee
        LEFT JOIN payees cp ON cp.id = cpm.targetId
        LEFT JOIN accounts cpa ON cpa.id = cp.transfer_acct
            AND (cpa.tombstone = 0 OR cpa.tombstone IS NULL)
        LEFT JOIN category_mapping cm ON cm.id = t.category
        LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
        ${if (statusFilter == TransactionStatusFilter.Uncategorized) UncategorizedSQL.FILTER_JOINS else ""}
        WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
          AND (t.isChild = 0 OR t.isChild IS NULL)
          AND t.date IS NOT NULL
          AND t.acct IS NOT NULL
        """,
    )
    val arguments = mutableListOf<Any?>()

    accountId?.let {
        sql.append(" AND t.acct = ?")
        arguments += it
    }
    startDate?.let {
        sql.append(" AND t.date >= ?")
        arguments += it
    }
    endDate?.let {
        sql.append(" AND t.date <= ?")
        arguments += it
    }

    when (statusFilter) {
        TransactionStatusFilter.All -> Unit
        // The chip also surfaces split parents the dedicated list excludes:
        // the list renders a split as one collapsed parent row and drops the
        // children, so a parent with a live uncategorized child must appear
        // here or the split is invisible under the filter.
        TransactionStatusFilter.Uncategorized ->
            sql.append(" AND (${UncategorizedSQL.CONDITIONS} OR ${UncategorizedSQL.SPLIT_PARENT_CONDITIONS})")
        TransactionStatusFilter.Uncleared -> sql.append(" AND (t.cleared = 0 OR t.cleared IS NULL)")
        TransactionStatusFilter.Cleared -> sql.append(" AND t.cleared = 1 AND (t.reconciled = 0 OR t.reconciled IS NULL)")
        TransactionStatusFilter.Reconciled -> sql.append(" AND t.reconciled = 1")
        TransactionStatusFilter.Unreconciled -> sql.append(" AND (t.reconciled = 0 OR t.reconciled IS NULL)")
    }

    if (search != null) {
        val matcher = TransactionSearchMatcher(search)
        if (matcher.text.isNotEmpty()) {
            val escaped = matcher.text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%$escaped%"
            val clauses = mutableListOf(
                "$payeeNameSQL LIKE ? ESCAPE '\\'",
                "c.name LIKE ? ESCAPE '\\'",
                "t.notes LIKE ? ESCAPE '\\'",
            )
            arguments.addAll(listOf(pattern, pattern, pattern))
            // ponytail: Keep split-child matching in SQL so pagination still
            // spans full history; the correlated EXISTS only probes children
            // belonging to the current parent row.
            clauses += """
                EXISTS (
                    SELECT 1
                    FROM transactions child
                    LEFT JOIN payee_mapping cpm ON cpm.id = child.description
                    LEFT JOIN payees cpay ON cpay.id = cpm.targetId
                    LEFT JOIN accounts child_account ON child_account.id = cpay.transfer_acct
                        AND (child_account.tombstone = 0 OR child_account.tombstone IS NULL)
                    WHERE child.parent_id = t.id
                      AND (child.tombstone = 0 OR child.tombstone IS NULL)
                      AND (
                          COALESCE(child_account.name, cpay.name) LIKE ? ESCAPE '\'
                          OR child.notes LIKE ? ESCAPE '\'
                      )
                )
            """
            arguments.addAll(listOf(pattern, pattern))
            matcher.amountCentsRange?.let { range ->
                clauses += "ABS(t.amount) BETWEEN ? AND ?"
                arguments += range.first
                arguments += range.last
            }
            sql.append(" AND (" + clauses.joinToString(" OR ") + ")")
        }
    }

    sql.append(" ORDER BY t.date DESC, t.sort_order DESC LIMIT ? OFFSET ?")
    arguments += limit
    arguments += offset

    val rows = db.fetchAll(sql.toString(), arguments)

    // Split parents have no category of their own; carry the live children's
    // category + amount as portions so the list row can show the breakdown
    // ("Food $6.00, Fun $4.00") without opening it.
    val parentIds = rows.filter { it.long("isParent") == 1L }.mapNotNull { it.string("id") }
    val splitPortions = HashMap<String, MutableList<Transaction.SplitPortion>>()
    if (parentIds.isNotEmpty()) {
        val childRows = db.fetchAll(
            """
            SELECT ct.parent_id AS parent_id, ct.amount AS amount,
                   c.name AS category_name
            FROM transactions ct
            LEFT JOIN category_mapping cm ON cm.id = ct.category
            LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, ct.category)
            WHERE ct.parent_id IN (${placeholders(parentIds.size)})
              AND (ct.tombstone = 0 OR ct.tombstone IS NULL)
            ORDER BY ct.sort_order DESC
            """,
            parentIds,
        )
        for (childRow in childRows) {
            val parentId = childRow.string("parent_id") ?: continue
            splitPortions.getOrPut(parentId) { mutableListOf() } +=
                Transaction.SplitPortion(childRow.string("category_name"), childRow.long("amount") ?: 0)
        }
    }

    rows.map { row ->
        val transaction = mapTransaction(row)
        transaction.copy(splitPortions = splitPortions[transaction.id])
    }
}

internal const val CHILD_TRANSACTION_SELECT = """
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
    LEFT JOIN accounts pa ON pa.id = p.transfer_acct
        AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
    LEFT JOIN category_mapping cm ON cm.id = t.category
    LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
    WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
"""

/** All live children of a split parent, in entry order (descending sort_order, matching the list). */
suspend fun BudgetDatabase.fetchChildTransactions(parentId: String): List<Transaction> = dbQueue.readAsync { db ->
    db.fetchAll("$CHILD_TRANSACTION_SELECT AND t.parent_id = ? ORDER BY t.sort_order DESC", parentId).map(::mapTransaction)
}

data class LiveTransactionSnapshot(
    val transactions: List<Transaction>,
    val splitChildren: List<Transaction>,
    /** Highest messages_crdt id at read time: the next snapshot's watermark. */
    val messageID: Long,
    /** Transaction rows another node wrote after the requested watermark. */
    val remoteRowIDs: Set<String>,
)

/**
 * Every live top-level transaction and every live split child, read as one
 * snapshot. History diffs this whole set: diffing the newest page instead
 * reads rows sliding off it as deletions and rows sliding onto it as
 * creations. `remoteRowIDs` comes from the same read, so a sync landing
 * between a publication and this read is still attributed to the node that
 * wrote it (HLC timestamps end in the 16-char node id).
 */
suspend fun BudgetDatabase.fetchAllLiveTransactions(
    remoteChangesAfter: Long? = null,
    localNode: String? = null,
): LiveTransactionSnapshot = dbQueue.readAsync { db ->
    val splitChildren = db.fetchAll("$CHILD_TRANSACTION_SELECT AND t.parent_id IS NOT NULL ORDER BY t.sort_order DESC").map(::mapTransaction)
    val portions = HashMap<String, MutableList<Transaction.SplitPortion>>()
    for (child in splitChildren) {
        val parentId = child.parentId ?: continue
        portions.getOrPut(parentId) { mutableListOf() } += Transaction.SplitPortion(child.categoryName, child.amount)
    }
    val transactions = db.fetchAll(TRANSACTION_SELECT).map { row ->
        val transaction = mapTransaction(row)
        if (transaction.isParent) transaction.copy(splitPortions = portions[transaction.id]) else transaction
    }
    val messageID = db.long("SELECT MAX(id) FROM messages_crdt") ?: 0
    val remoteRowIDs = if (remoteChangesAfter != null && localNode != null) {
        db.strings(
            """
            SELECT DISTINCT row FROM messages_crdt
            WHERE dataset = 'transactions' AND id > ? AND substr(timestamp, -16) <> ?
            """,
            remoteChangesAfter, localNode,
        ).toSet()
    } else {
        emptySet()
    }
    LiveTransactionSnapshot(transactions, splitChildren, messageID, remoteRowIDs)
}

/** Highest messages_crdt rowid — the watermark for new-transaction detection. 0 when empty. */
suspend fun BudgetDatabase.fetchMaxMessageId(): Long = dbQueue.readAsync { db ->
    db.long("SELECT MAX(id) FROM messages_crdt") ?: 0
}

/**
 * Transactions whose first-ever CRDT message landed after [watermark] and was
 * authored by another device (HLC timestamps end in the 16-char node id).
 * First message > watermark means the row itself is new, not an edit; the
 * creator's node id keeps this device's own writes out of the result.
 */
suspend fun BudgetDatabase.fetchTransactionsCreated(watermark: Long, excludingNode: String): List<Transaction> =
    dbQueue.readAsync { db ->
        db.fetchAll(
            """
            $TRANSACTION_SELECT
              AND t.id IN (
                  SELECT row FROM messages_crdt
                  WHERE dataset = 'transactions'
                  GROUP BY row
                  HAVING MIN(id) > ? AND substr(MIN(timestamp), -16) <> ?
              )
            ORDER BY t.date DESC, t.sort_order DESC
            """,
            watermark, excludingNode,
        ).map(::mapTransaction)
    }

/**
 * Cleared balance for one account: what the bank should agree with during
 * reconciliation. Same aggregate semantics as the fetchAccounts() balance
 * query, narrowed to cleared rows.
 */
suspend fun BudgetDatabase.clearedBalance(accountId: String): Long = dbQueue.readAsync { db ->
    db.long(
        """
        SELECT COALESCE(SUM(t.amount), 0)
        FROM transactions t
        LEFT JOIN transactions p ON p.id = t.parent_id
        WHERE t.acct = ?
          AND t.cleared = 1
          AND t.date IS NOT NULL
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND ${aliveChildPredicate("p")}
          AND (t.isParent = 0 OR t.isParent IS NULL)
        """,
        accountId,
    ) ?: 0
}

/**
 * Cleared / uncleared / reconciled totals for one account in a single
 * consistent read (GH #134). Reconciled rows are a subset of cleared, so
 * cleared + uncleared equals the account balance.
 */
suspend fun BudgetDatabase.balanceBreakdown(accountId: String): AccountBalanceBreakdown = dbQueue.readAsync { db ->
    val row = db.fetchOne(
        """
        SELECT
            COALESCE(SUM(CASE WHEN t.cleared = 1 THEN t.amount ELSE 0 END), 0) AS cleared,
            COALESCE(SUM(CASE WHEN t.cleared = 0 OR t.cleared IS NULL THEN t.amount ELSE 0 END), 0) AS uncleared,
            COALESCE(SUM(CASE WHEN t.reconciled = 1 THEN t.amount ELSE 0 END), 0) AS reconciled
        FROM transactions t
        LEFT JOIN transactions p ON p.id = t.parent_id
        WHERE t.acct = ?
          AND t.date IS NOT NULL
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND ${aliveChildPredicate("p")}
          AND (t.isParent = 0 OR t.isParent IS NULL)
        """,
        accountId,
    )
    AccountBalanceBreakdown(row?.long("cleared") ?: 0, row?.long("uncleared") ?: 0, row?.long("reconciled") ?: 0)
}

/**
 * Total charges / debits in cents for an account between two dates
 * (inclusive). Amounts in Actual are negative for expenses, so this sums
 * negative transactions and returns positive cents.
 */
suspend fun BudgetDatabase.fetchAccountSpend(accountId: String, fromDate: Int, toDate: Int): Long = dbQueue.readAsync { db ->
    db.long(
        """
        SELECT COALESCE(SUM(CASE WHEN t.amount < 0 THEN -t.amount ELSE 0 END), 0)
        FROM transactions t
        LEFT JOIN transactions p ON p.id = t.parent_id
        WHERE t.acct = ?
          AND t.date >= ?
          AND t.date <= ?
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND ${aliveChildPredicate("p")}
          AND (t.isParent = 0 OR t.isParent IS NULL)
        """,
        accountId, fromDate, toDate,
    ) ?: 0
}

data class StatementDueRequest(val accountId: String, val statementDate: DayDate, val dueDate: DayDate, val liveBalance: Long)

/**
 * Statement balance, payments made since statement closing, and remaining
 * statement due for credit card accounts, in a single read.
 */
suspend fun BudgetDatabase.fetchCreditCardStatementDues(requests: List<StatementDueRequest>): Map<String, List<CreditCardCycle.StatementDue>> {
    if (requests.isEmpty()) return emptyMap()
    return dbQueue.readAsync { db ->
        val results = LinkedHashMap<String, MutableList<CreditCardCycle.StatementDue>>()
        for (req in requests) {
            val row = db.fetchOne(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN t.date <= ? THEN t.amount ELSE 0 END), 0) AS statementRawBalance,
                    COALESCE(SUM(CASE WHEN t.date > ? AND t.amount > 0 THEN t.amount ELSE 0 END), 0) AS paymentsSince
                FROM transactions t
                LEFT JOIN transactions p ON p.id = t.parent_id
                WHERE t.acct = ?
                  AND t.date IS NOT NULL
                  AND (t.tombstone = 0 OR t.tombstone IS NULL)
                  AND ${aliveChildPredicate("p")}
                  AND (t.isParent = 0 OR t.isParent IS NULL)
                """,
                req.statementDate.yyyymmdd, req.statementDate.yyyymmdd, req.accountId,
            )
            results.getOrPut(req.accountId) { mutableListOf() } += CreditCardCycle.calculateStatementDue(
                statementRawBalance = row?.long("statementRawBalance") ?: 0,
                paymentsSince = row?.long("paymentsSince") ?: 0,
                liveBalance = req.liveBalance,
                dueDate = req.dueDate,
            )
        }
        results
    }
}

/**
 * Statement records for the given closed cycles on a credit card account.
 * Cycles with no recorded transactions and zero statement balance are excluded.
 */
suspend fun BudgetDatabase.fetchRecentStatements(
    accountId: String,
    cycles: List<CreditCardCycle.Cycle>,
    liveBalance: Long,
): List<CreditCardCycle.StatementRecord> {
    if (cycles.isEmpty()) return emptyList()
    return dbQueue.readAsync { db ->
        cycles.mapNotNull { cycle ->
            val row = db.fetchOne(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN t.date <= ? THEN t.amount ELSE 0 END), 0) AS statementRawBalance,
                    COALESCE(SUM(CASE WHEN t.date > ? AND t.amount > 0 THEN t.amount ELSE 0 END), 0) AS paymentsSince,
                    COALESCE(SUM(CASE WHEN t.date >= ? AND t.date <= ? AND t.amount < 0 THEN -t.amount ELSE 0 END), 0) AS totalSpend,
                    COUNT(CASE WHEN t.date >= ? AND t.date <= ? THEN 1 ELSE NULL END) AS transactionCount
                FROM transactions t
                LEFT JOIN transactions p ON p.id = t.parent_id
                WHERE t.acct = ?
                  AND t.date IS NOT NULL
                  AND (t.tombstone = 0 OR t.tombstone IS NULL)
                  AND ${aliveChildPredicate("p")}
                  AND (t.isParent = 0 OR t.isParent IS NULL)
                """,
                cycle.end.yyyymmdd, cycle.end.yyyymmdd,
                cycle.start.yyyymmdd, cycle.end.yyyymmdd,
                cycle.start.yyyymmdd, cycle.end.yyyymmdd,
                accountId,
            )
            val totalSpend = row?.long("totalSpend") ?: 0
            val transactionCount = row?.long("transactionCount") ?: 0
            val statementDue = CreditCardCycle.calculateStatementDue(
                statementRawBalance = row?.long("statementRawBalance") ?: 0,
                paymentsSince = row?.long("paymentsSince") ?: 0,
                liveBalance = liveBalance,
                dueDate = cycle.dueDate,
            )
            // Only include if data is available (has transactions or non-zero statement balance)
            if (transactionCount > 0 || statementDue.statementBalance > 0) {
                CreditCardCycle.StatementRecord(
                    startDate = cycle.start,
                    endDate = cycle.end,
                    dueDate = cycle.dueDate,
                    statementBalance = statementDue.statementBalance,
                    paymentsSince = statementDue.paymentsSince,
                    remainingDue = statementDue.remainingDue,
                    totalSpend = totalSpend,
                )
            } else {
                null
            }
        }
    }
}

/** Builds a Transaction from a row without display joins (names stay null). */
internal fun bareTransaction(row: Row): Transaction = Transaction(
    id = row.string("id") ?: "",
    accountId = row.string("acct") ?: "",
    date = row.int("date") ?: 0,
    amount = row.long("amount") ?: 0,
    payeeId = row.string("description"),
    payeeName = null,
    categoryId = row.string("category"),
    categoryName = null,
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
)

/**
 * Every live cleared-but-not-yet-reconciled row in an account — parents and
 * children included, because locking marks each stored row the way
 * upstream's ungrouped batch update does. No display joins: callers write
 * these rows back verbatim with only `reconciled` changed.
 */
suspend fun BudgetDatabase.fetchClearedUnreconciledTransactions(accountId: String): List<Transaction> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT
            t.id, t.isParent, t.acct, t.category, t.amount,
            t.description, t.notes, t.date, t.imported_description,
            t.schedule,
            t.transferred_id, t.cleared, t.reconciled, t.sort_order,
            t.tombstone, t.parent_id
        FROM transactions t
        LEFT JOIN transactions p ON p.id = t.parent_id
        WHERE t.acct = ?
          AND t.cleared = 1
          AND t.date IS NOT NULL
          AND (t.reconciled = 0 OR t.reconciled IS NULL)
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND ${aliveChildPredicate("p")}
        ORDER BY t.date DESC, t.sort_order DESC
        """,
        accountId,
    ).map(::bareTransaction)
}

/**
 * The uncategorized filter's SQL. Mirrors the WebUI's "uncategorized"
 * pseudo-account filter (desktop-client accountFilter('uncategorized')):
 * on-budget account, no category, not a split parent (children are where
 * categories live), and not a transfer unless the other side is off-budget —
 * money leaving the budget still needs a category. Children of tombstoned
 * split parents are excluded like fetchTransactionsForReports().
 */
internal object UncategorizedSQL {
    const val JOINS = """
        FROM transactions t
        JOIN accounts a ON a.id = t.acct
        LEFT JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        LEFT JOIN accounts ta ON ta.id = p.transfer_acct
        LEFT JOIN transactions par ON par.id = t.parent_id
    """

    /**
     * The extra joins [CONDITIONS] needs, for callers that already join
     * transactions t and payees p themselves. `a` is an inner join like
     * [JOINS]: a live transaction whose account row is missing is a sync-race
     * orphan the dedicated list excludes, so the chip must exclude it too.
     */
    const val FILTER_JOINS = """
        JOIN accounts a ON a.id = t.acct
        LEFT JOIN accounts ta ON ta.id = p.transfer_acct
    """

    /**
     * Account-side half of the uncategorized filter: on-budget, live account,
     * and not an on-budget transfer.
     */
    const val ACCOUNT_CONDITIONS = """
        (a.offbudget = 0 OR a.offbudget IS NULL)
        AND (a.tombstone = 0 OR a.tombstone IS NULL)
        AND (p.transfer_acct IS NULL OR ta.offbudget = 1)
    """

    /** WHERE-body of the uncategorized filter. Requires t, p, a, and ta in scope. */
    const val CONDITIONS = """
        t.category IS NULL
        AND (t.isParent = 0 OR t.isParent IS NULL)
        AND $ACCOUNT_CONDITIONS
    """

    /**
     * The transaction lists' uncategorized chip additionally shows split
     * parents with a live uncategorized child, which the dedicated list counts
     * through the children instead ([WHERE] must not use this). Children share
     * the parent's account, but their payees can differ, so the transfer check
     * stays in the child subquery.
     */
    const val SPLIT_PARENT_CONDITIONS = """
        t.isParent = 1
        AND EXISTS (
            SELECT 1
            FROM transactions tc
            LEFT JOIN payee_mapping tcpm ON tcpm.id = tc.description
            LEFT JOIN payees tcp ON tcp.id = tcpm.targetId
            LEFT JOIN accounts tca ON tca.id = tcp.transfer_acct
            WHERE tc.parent_id = t.id
              AND tc.isChild = 1
              AND (tc.tombstone = 0 OR tc.tombstone IS NULL)
              AND tc.category IS NULL
              AND (tcp.transfer_acct IS NULL OR tca.offbudget = 1)
        )
        AND (a.offbudget = 0 OR a.offbudget IS NULL)
        AND (a.tombstone = 0 OR a.tombstone IS NULL)
    """

    val WHERE = """
        WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
          AND t.date IS NOT NULL
          AND ${aliveChildPredicate("par")}
          AND $CONDITIONS
    """
}

/**
 * All transactions still needing a category, newest first (GH #26). Split
 * children carry no payee of their own, so their display name falls back to
 * the parent's payee.
 */
suspend fun BudgetDatabase.fetchUncategorizedTransactions(): List<Transaction> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT
            t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
            t.description, t.notes, t.date, t.imported_description,
            t.schedule,
            t.transferred_id, t.cleared, t.reconciled, t.sort_order,
            t.tombstone, t.parent_id,
            COALESCE(pa.name, p.name, ppa.name, pp.name) as payee_name,
            p.transfer_acct as transfer_acct
        ${UncategorizedSQL.JOINS}
        LEFT JOIN accounts pa ON pa.id = p.transfer_acct
            AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
        -- Parent's payee, as the fallback for split children.
        LEFT JOIN payee_mapping ppm ON ppm.id = par.description
        LEFT JOIN payees pp ON pp.id = ppm.targetId
        LEFT JOIN accounts ppa ON ppa.id = pp.transfer_acct
            AND (ppa.tombstone = 0 OR ppa.tombstone IS NULL)
        ${UncategorizedSQL.WHERE}
        ORDER BY t.date DESC, t.sort_order DESC
        """,
    ).map { row ->
        bareTransaction(row).copy(
            categoryId = null,
            payeeName = row.string("payee_name"),
            transferAcct = row.string("transfer_acct"),
        )
    }
}

/** Number of transactions [fetchUncategorizedTransactions] would return, without materializing the rows. */
suspend fun BudgetDatabase.fetchUncategorizedCount(): Int = dbQueue.readAsync { db ->
    db.long("SELECT COUNT(*) ${UncategorizedSQL.JOINS} ${UncategorizedSQL.WHERE}")?.toInt() ?: 0
}

/**
 * Every transaction that counts toward a category's spend, newest first,
 * optionally narrowed to one "yyyy-MM" month (GH #56). Mirrors the budget
 * month's spent query so the list reconciles with the "Spent" figure the user
 * tapped: split children included, split parents excluded, category ids
 * resolved through category_mapping, and tombstoned rows / orphaned children /
 * off-budget accounts filtered out.
 */
suspend fun BudgetDatabase.fetchCategoryTransactions(categoryId: String, month: String?): List<Transaction> = dbQueue.readAsync { db ->
    val sql = StringBuilder(
        """
        SELECT
            t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
            t.description, t.notes, t.date, t.imported_description,
            t.schedule,
            t.transferred_id, t.cleared, t.reconciled, t.sort_order,
            t.tombstone, t.parent_id,
            COALESCE(pa.name, p.name, ppa.name, pp.name) as payee_name,
            c.name as category_name
        FROM transactions t
        JOIN accounts a ON a.id = t.acct
        LEFT JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        LEFT JOIN accounts pa ON pa.id = p.transfer_acct
            AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
        LEFT JOIN transactions par ON par.id = t.parent_id
        LEFT JOIN payee_mapping ppm ON ppm.id = par.description
        LEFT JOIN payees pp ON pp.id = ppm.targetId
        LEFT JOIN accounts ppa ON ppa.id = pp.transfer_acct
            AND (ppa.tombstone = 0 OR ppa.tombstone IS NULL)
        LEFT JOIN category_mapping cm ON cm.id = t.category
        LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
        WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
          AND t.date IS NOT NULL
          AND ${aliveChildPredicate("par")}
          AND (t.isParent = 0 OR t.isParent IS NULL)
          AND COALESCE(cm.transferId, t.category) = ?
          AND a.offbudget = 0
          AND (a.tombstone = 0 OR a.tombstone IS NULL)
        """,
    )
    val arguments = mutableListOf<Any?>(categoryId)
    // Dates are YYYYMMDD ints, so date/100 is the YYYYMM month.
    month?.replace("-", "")?.toIntOrNull()?.let {
        sql.append(" AND (t.date / 100) = ?")
        arguments += it
    }
    sql.append(" ORDER BY t.date DESC, t.sort_order DESC")
    db.fetchAll(sql.toString(), arguments).map { row ->
        bareTransaction(row).copy(payeeName = row.string("payee_name"), categoryName = row.string("category_name"))
    }
}
