package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.reports.CustomReportConfig
import io.github.traushb.actuali.services.reports.DashboardPage
import io.github.traushb.actuali.services.reports.DashboardWidget
import io.github.traushb.actuali.services.reports.WidgetRuleCondition
import io.github.traushb.actuali.services.reports.engines.BalanceForecastBudgetMonth
import io.github.traushb.actuali.services.reports.engines.BudgetAnalysisBudgetEntry
import kotlinx.serialization.builtins.ListSerializer

// Dashboard Widgets

/**
 * Transactions suitable for report aggregation: no tombstoned rows, no split
 * PARENTS (their amount equals the sum of children, so including both would
 * double-count, and parents have no category, which breaks category-based
 * conditions); split children (where category lives) and standalone rows.
 */
suspend fun BudgetDatabase.fetchTransactionsForReports(): List<Transaction> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT
            t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
            t.notes, t.date, t.imported_description,
            t.schedule,
            t.transferred_id, t.cleared, t.reconciled, t.sort_order,
            t.tombstone, t.parent_id,
            -- Merged payees keep their old id on the row; Actual's transaction
            -- view resolves it through payee_mapping, so reports group and
            -- filter by the surviving payee.
            COALESCE(pm.targetId, t.description) AS payee_id,
            COALESCE(pa.name, p.name) as payee_name,
            p.transfer_acct as transfer_acct,
            c.name as category_name
        FROM transactions t
        LEFT JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        -- Transfer payees carry no name; their display name is the linked
        -- account's name (matches Actual's v_payees view).
        LEFT JOIN accounts pa ON pa.id = p.transfer_acct
            AND (pa.tombstone = 0 OR pa.tombstone IS NULL)
        LEFT JOIN category_mapping cm ON cm.id = t.category
        LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, t.category)
        -- Deleting a split tombstones only the parent; its children keep
        -- tombstone = 0, so they must be excluded via the parent (same rule as
        -- the fetchAccounts() balance query).
        LEFT JOIN transactions par ON par.id = t.parent_id
        WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
          AND (t.isParent = 0 OR t.isParent IS NULL)
          AND ${BudgetDatabase.aliveChildPredicate("par")}
          AND t.date IS NOT NULL
          AND t.acct IS NOT NULL
        """,
    ).map { row ->
        Transaction(
            id = row.string("id") ?: "",
            accountId = row.string("acct") ?: "",
            date = row.int("date") ?: 0,
            amount = row.long("amount") ?: 0,
            payeeId = row.string("payee_id"),
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
    }
}

/**
 * Raw (id, type, metaJSON) triples for every live dashboard widget. Useful
 * for sharing exact widget configuration when triaging report rendering bugs.
 */
suspend fun BudgetDatabase.dumpDashboardRows(): List<Triple<String, String, String>> = dbQueue.readAsync { db ->
    db.fetchAll("SELECT id, type, meta FROM dashboard WHERE (tombstone = 0 OR tombstone IS NULL) ORDER BY y ASC, x ASC").mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        val type = row.string("type") ?: return@mapNotNull null
        Triple(id, type, row.string("meta") ?: "null")
    }
}

/**
 * Live dashboard pages in table order — the same order the web app's
 * unordered AQL select (`q('dashboard_pages').select('*')`) yields and its
 * router indexes into for the default dashboard (dashboardPages[0]).
 */
suspend fun BudgetDatabase.fetchDashboardPages(): List<DashboardPage> = dbQueue.readAsync { db ->
    db.fetchAll("SELECT id, name FROM dashboard_pages WHERE (tombstone = 0 OR tombstone IS NULL) ORDER BY rowid ASC").mapNotNull { row ->
        DashboardPage(row.string("id") ?: return@mapNotNull null, row.string("name") ?: "")
    }
}

/**
 * Live widgets on one dashboard page, in reading order (y, then x). The web
 * app treats pages as separate dashboards (GH #120: rendering only one merged
 * view scrambled multi-dashboard budgets), so widgets on other, deleted, or
 * unknown pages must not render for a given page. Upstream's migration mints
 * a fresh "Main" page id on every client that runs it, so a synced budget can
 * carry full duplicate widget sets under orphaned page ids.
 *
 * A null [pageId] selects pageless widgets — budgets from servers that predate
 * multiple dashboards carry no page rows or ids.
 */
suspend fun BudgetDatabase.fetchWidgets(pageId: String?): List<DashboardWidget> = dbQueue.readAsync { db ->
    val rows = if (pageId != null) {
        db.fetchAll(
            "SELECT id, type, meta FROM dashboard WHERE (tombstone = 0 OR tombstone IS NULL) AND dashboard_page_id = ? ORDER BY y ASC, x ASC",
            pageId,
        )
    } else {
        db.fetchAll("SELECT id, type, meta FROM dashboard WHERE (tombstone = 0 OR tombstone IS NULL) AND dashboard_page_id IS NULL ORDER BY y ASC, x ASC")
    }
    rows.mapNotNull { row ->
        DashboardWidget.parse(row.string("id") ?: return@mapNotNull null, row.string("type") ?: return@mapNotNull null, row.string("meta"))
    }
}

/** The referenced `custom_reports` rows keyed by id. Tombstoned rows and unknown ids are simply absent. */
suspend fun BudgetDatabase.fetchCustomReportConfigs(ids: List<String>): Map<String, CustomReportConfig> {
    if (ids.isEmpty()) return emptyMap()
    return dbQueue.readAsync { db ->
        // The app's own migration (1770000000002) creates custom_reports
        // without upstream's later columns (date_static, include_current,
        // sort_by); a synced budget file has all of them. Select what exists
        // and default the rest.
        val existing = db.columns("custom_reports").toSet()
        val wanted = listOf(
            "id", "name", "mode", "group_by", "balance_type", "interval", "graph_type", "date_range", "date_static", "start_date",
            "end_date", "include_current", "show_empty", "show_offbudget", "show_hidden", "show_uncategorized", "sort_by", "conditions",
            "conditions_op", "show_trend_lines", "trim_intervals",
        )
        val select = wanted.joinToString(", ") { if (it in existing) it else "NULL AS $it" }
        val rows = db.fetchAll(
            "SELECT $select FROM custom_reports WHERE id IN (${Db.placeholders(ids.size)}) AND (tombstone = 0 OR tombstone IS NULL)",
            ids,
        )
        fun Row.flag(column: String) = (long(column) ?: 0L) != 0L
        rows.associate { row ->
            val conditions = row.string("conditions")?.let {
                runCatching { AppJson.decodeFromString(ListSerializer(WidgetRuleCondition.serializer()), it) }.getOrNull()
            }
            val config = CustomReportConfig(
                id = row.string("id") ?: "",
                name = row.string("name") ?: "Custom Report",
                mode = row.string("mode") ?: "total",
                groupBy = row.string("group_by") ?: "Category",
                balanceType = row.string("balance_type") ?: "Payment",
                interval = row.string("interval") ?: "Monthly",
                graphType = row.string("graph_type") ?: "BarGraph",
                dateRange = row.string("date_range"),
                dateStatic = row.flag("date_static"),
                startDate = row.string("start_date"),
                endDate = row.string("end_date"),
                includeCurrent = row.flag("include_current"),
                showEmpty = row.flag("show_empty"),
                showOffBudget = row.flag("show_offbudget"),
                showHidden = row.flag("show_hidden"),
                showUncategorized = row.flag("show_uncategorized"),
                sortBy = row.string("sort_by") ?: "desc",
                showTrendLines = row.flag("show_trend_lines"),
                trimIntervals = row.flag("trim_intervals"),
                conditions = conditions,
                conditionsOp = row.string("conditions_op") ?: "and",
            )
            config.id to config
        }
    }
}

/**
 * Budget rows for report engines from the live budgets table (see
 * budgetTable), plus whether that table is reflect_budgets so callers can
 * mirror upstream budgetType checks.
 */
data class ReportBudgetData(val entries: List<BudgetAnalysisBudgetEntry> = emptyList(), val isTracking: Boolean = false)

suspend fun BudgetDatabase.fetchBudgetDataForReports(): ReportBudgetData = dbQueue.readAsync { db ->
    val table = budgetTable(db) ?: return@readAsync ReportBudgetData()
    val entries = db.fetchAll(
        """
        SELECT b.month, COALESCE(cm.transferId, b.category) AS category, b.amount
        FROM $table b
        LEFT JOIN category_mapping cm ON cm.id = b.category
        WHERE b.category IS NOT NULL
        """,
    ).mapNotNull { row ->
        BudgetAnalysisBudgetEntry(row.int("month") ?: return@mapNotNull null, row.string("category") ?: return@mapNotNull null, row.long("amount") ?: 0)
    }
    ReportBudgetData(entries, table == "reflect_budgets")
}

/**
 * Per-month tracking-budget totals for the balance-forecast engine (upstream
 * forecast-tracking-budget.ts: income = budgeted amounts across income
 * categories, expenses = across the rest). Always reads reflect_budgets;
 * callers gate on the budget type.
 */
suspend fun BudgetDatabase.fetchTrackingBudgetMonths(): List<BalanceForecastBudgetMonth> = dbQueue.readAsync { db ->
    if (!db.tableExists("reflect_budgets")) return@readAsync emptyList()
    db.fetchAll(
        """
        SELECT b.month AS month,
               SUM(CASE WHEN c.is_income = 1 THEN b.amount ELSE 0 END) AS income,
               SUM(CASE WHEN c.is_income = 1 THEN 0 ELSE b.amount END) AS expenses
        FROM reflect_budgets b
        LEFT JOIN category_mapping cm ON cm.id = b.category
        LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, b.category)
        WHERE b.category IS NOT NULL
        GROUP BY b.month
        """,
    ).mapNotNull { row ->
        BalanceForecastBudgetMonth(row.int("month") ?: return@mapNotNull null, row.long("income") ?: 0, row.long("expenses") ?: 0)
    }
}

/** Synced pref controlling week bucketing (0 = Sunday … 6 = Saturday). */
suspend fun BudgetDatabase.fetchFirstDayOfWeekIdx(): Int = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync 0
    db.string("SELECT value FROM preferences WHERE id = 'firstDayOfWeekIdx'")?.toIntOrNull() ?: 0
}
