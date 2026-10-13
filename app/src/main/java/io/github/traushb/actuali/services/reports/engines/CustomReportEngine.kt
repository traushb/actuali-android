package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.CustomReportConfig
import io.github.traushb.actuali.services.reports.ReportDateRange
import io.github.traushb.actuali.services.reports.localDate
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import java.text.Collator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CustomReportData(
    val name: String,
    /** "All time", "Year to date", or "" for static */
    val rangeLabel: String,
    val kind: Kind,
) {
    data class Bar(val label: String, val valueUnits: Double)

    data class Stacked(
        val intervalLabels: List<String>,
        /** legend, ordered */
        val seriesNames: List<String>,
        /** [series][interval], currency units */
        val values: List<List<Double>>,
    )

    data class TableRow(val name: String, val totalUnits: Double)

    /** One donut wedge. [group] indexes the `groups` ring for the two-ring Category+Group layout; null on a single-ring donut. */
    data class Slice(val label: String, val valueUnits: Double, val group: Int?)

    /** Least-squares trend through one line series, as its values at the first and last interval. */
    data class Trend(val startUnits: Double, val endUnits: Double)

    sealed interface Kind {
        /** [signed] → color bars by sign (Net) */
        data class Bars(val bars: List<Bar>, val signed: Boolean) : Kind
        data class StackedBars(val stacked: Stacked) : Kind

        /** [trends] empty unless showTrendLines */
        data class Lines(val stacked: Stacked, val trends: List<Trend>) : Kind

        /** one point per interval */
        data class Area(val bars: List<Bar>) : Kind

        /** [groups] empty → single ring */
        data class Donut(val slices: List<Slice>, val groups: List<Bar>) : Kind
        data class Table(val rows: List<TableRow>) : Kind
        data class Unsupported(val reason: String) : Kind
    }
}

/**
 * Port of the webapp's custom-spreadsheet.ts for the option matrix the web UI
 * can save. Everything computes from the shared reports transaction list (or,
 * for the Budgeted balance type, from budget cells); configs outside the
 * matrix return [CustomReportData.Kind.Unsupported] naming the offending
 * option so the card can explain itself.
 */
object CustomReportEngine {
    /**
     * Row keys for the synthetic rows upstream appends after the real
     * categories/groups (ReportOptions.ts: uncategorizedCategory,
     * offBudgetCategory, transferCategory, uncategorizedGroup).
     */
    private const val UNCATEGORIZED = "uncategorized"
    private const val OFF_BUDGET = "off_budget"
    private const val TRANSFER = "transfer"

    data class ReportContext(
        /** in budget sort order */
        val categories: List<Category>,
        /** in budget sort order */
        val groups: List<CategoryGroup>,
        val offBudgetAccountIds: Set<String>,
        val firstDayOfWeekIdx: Int,
        /** groupBy Payee, store order */
        val payees: List<Payee> = emptyList(),
        /** groupBy Account, store order */
        val accounts: List<Account> = emptyList(),
        /** balanceType Budgeted */
        val budgetEntries: List<BudgetAnalysisBudgetEntry> = emptyList(),
    )

    /** Assets/debts sums for one row or bucket (upstream QueryDataEntity split). */
    private data class Cell(val assets: Long = 0, val debts: Long = 0) {
        fun adding(amount: Long) = if (amount > 0) copy(assets = assets + amount) else copy(debts = debts + amount)

        operator fun plus(other: Cell) = Cell(assets + other.assets, debts + other.debts)
    }

    /** One named row (category, group, payee, account) after bucketing. */
    private data class Row(
        val key: String,
        val name: String,
        /** whole-range aggregate */
        val cell: Cell,
        /** metric per interval, currency units */
        val perBucket: List<Double>,
        /** metric over the aggregate, not Σ perBucket */
        val total: Double,
    )

    fun compute(
        config: CustomReportConfig?,
        transactions: List<Transaction>,
        reportContext: ReportContext,
        filterContext: ConditionsFilter.Context,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): CustomReportData {
        if (config == null) {
            return CustomReportData(
                ReportStrings.text("Custom Report", locale), "",
                CustomReportData.Kind.Unsupported(ReportStrings.text("Report not found — try syncing", locale)),
            )
        }
        val name = config.name
        val rangeLabel = if (config.dateStatic) "" else localizedRangeLabel(config.dateRange ?: "All time", locale)
        fun data(kind: CustomReportData.Kind) = CustomReportData(name, rangeLabel, kind)

        // Supported-matrix guard (upstream ReportOptions.ts): name the first offending option.
        for ((value, supported, label) in listOf(
            Triple(config.mode, listOf("total", "time"), "mode"),
            Triple(config.groupBy, listOf("Category", "Group", "CategoryGroup", "Payee", "Account", "Interval"), "group by"),
            Triple(config.balanceType, listOf("Payment", "Deposit", "Net", "Net Payment", "Net Deposit", "Budgeted"), "balance type"),
            Triple(config.interval, listOf("Daily", "Weekly", "Monthly", "Yearly"), "interval"),
            Triple(config.graphType, listOf("BarGraph", "StackedBarGraph", "LineGraph", "AreaGraph", "DonutGraph", "TableGraph"), "graph"),
        )) {
            if (value !in supported) {
                return data(
                    CustomReportData.Kind.Unsupported(
                        ReportStrings.format("%@ %@ isn't supported yet", value, ReportStrings.text(label, locale), locale = locale),
                    ),
                )
            }
        }

        // Date range from actual history (also for Budgeted, as upstream's card does).
        val live = transactions.filter { !it.tombstone }
        val earliest = live.minOfOrNull { it.date }?.let(::localDate)
        val latest = live.maxOfOrNull { it.date }?.let(::localDate)
        val (start, end) = ReportDateRange.resolve(
            config.dateRange, config.dateStatic, config.startDate, config.endDate, config.includeCurrent,
            earliest, latest, today, reportContext.firstDayOfWeekIdx,
        )
        val startYMD = start.ymd
        val endYMD = end.ymd
        val categoriesById = reportContext.categories.associateBy { it.id }
        val groupsById = reportContext.groups.associateBy { it.id }

        // Dataset: transactions in range, or budget cells for Budgeted
        // (upstream fetchSpreadsheetQueryData). Budget cells only honour
        // category conditions (budgetDataQuery.filterCategoriesByConditions).
        val budgeted = config.balanceType == "Budgeted"
        val source = if (budgeted) budgetRows(reportContext, startYMD, endYMD) else live.filter { it.date in startYMD..endYMD }
        val conditions = if (budgeted) config.conditions?.filter { it.field in setOf("category", "category_group") } else config.conditions

        // Filter (upstream: conditions, then filterHiddenItems) — single pass.
        // Category resolves through the lookup, mirroring upstream's query
        // join: a dangling categoryId behaves exactly like no category.
        val pool = source.filter { tx ->
            if (!ConditionsFilter.matches(tx, conditions, config.conditionsOp, filterContext)) return@filter false
            val category = tx.categoryId?.let { categoriesById[it] }
            if (!config.showHidden && category != null && (category.hidden || groupsById[category.groupId]?.hidden == true)) return@filter false
            val offBudget = tx.accountId in reportContext.offBudgetAccountIds
            if (!config.showOffBudget && offBudget) return@filter false
            !((!config.showUncategorized) && category == null && !offBudget)
        }

        // Interval buckets, chronological.
        val buckets = intervalBuckets(start, end, config.interval, reportContext.firstDayOfWeekIdx, locale)
        val bucketIndex = buckets.withIndex().associate { it.value.first to it.index }
        var labels = buckets.map { it.second }

        // Accumulate assets/debts per (row, bucket). Row key "" = whole dataset (groupBy Interval).
        val cells = HashMap<String, HashMap<Int, Cell>>()
        for (tx in pool) {
            val idx = bucketIndex[bucketKey(tx.date, config.interval, reportContext.firstDayOfWeekIdx)] ?: continue
            // Upstream filterHiddenItems: only categorized on-budget txs land on
            // regular rows; everything else goes to the synthetic rows —
            // off-budget txs (even categorized ones) to "Off budget", then
            // uncategorized transfers to "Transfers", the rest to
            // "Uncategorized". Group mode folds all three into one group.
            // Payee/Account have no synthetic rows: a tx with no payee matches
            // nothing (upstream `payee === item.id`).
            val category = tx.categoryId?.let { categoriesById[it] }
            val offBudget = tx.accountId in reportContext.offBudgetAccountIds
            val rowKey = when (config.groupBy) {
                "Category", "CategoryGroup" -> when {
                    category != null && !offBudget -> category.id
                    offBudget -> OFF_BUDGET
                    tx.transferAcct != null -> TRANSFER
                    else -> UNCATEGORIZED
                }
                "Group" -> if (category != null && !offBudget) category.groupId else UNCATEGORIZED
                "Payee" -> tx.payeeId ?: continue
                "Account" -> tx.accountId
                else -> "" // Interval
            }
            val row = cells.getOrPut(rowKey) { HashMap() }
            row[idx] = (row[idx] ?: Cell()).adding(tx.amount)
        }

        val value = { cell: Cell -> metric(cell, config.balanceType) }
        fun bucketCells(rowKey: String) = buckets.indices.map { cells[rowKey]?.get(it) ?: Cell() }
        val signed = config.balanceType in setOf("Net", "Budgeted")

        // groupBy Interval → one row per bucket (web renders intervalData here).
        if (config.groupBy == "Interval") {
            var values = bucketCells("").map(value)
            if (config.trimIntervals) {
                val range = trimmedRange(listOf(values))
                labels = labels.slice(range)
                values = values.slice(range)
            }
            val pairs = labels.zip(values)
            return data(
                when (config.graphType) {
                    "TableGraph" -> CustomReportData.Kind.Table(pairs.map { CustomReportData.TableRow(it.first, it.second) })
                    "AreaGraph" -> CustomReportData.Kind.Area(pairs.map { CustomReportData.Bar(it.first, it.second) })
                    // Upstream allows Interval on a total-mode donut: one wedge
                    // per interval, and a wedge can't have a non-positive angle.
                    "DonutGraph" -> CustomReportData.Kind.Donut(pairs.filter { it.second > 0 }.map { CustomReportData.Slice(it.first, it.second, null) }, emptyList())
                    else -> CustomReportData.Kind.Bars(pairs.map { CustomReportData.Bar(it.first, it.second) }, signed)
                },
            )
        }

        // Named rows in store order. Upstream (ReportOptions.categoryLists)
        // always appends the synthetic rows; when their txs are filtered out
        // they total zero and fall to the showEmpty filter like any other row.
        val uncategorizedGroup = UNCATEGORIZED to ReportStrings.text("Uncategorized & Off budget", locale)
        val orderedRows: List<Pair<String, String>> = when (config.groupBy) {
            "Payee" -> {
                // Transfer payees carry no name of their own; upstream's
                // v_payees shows the linked account.
                val accountNames = HashMap<String, String>()
                for (account in reportContext.accounts) accountNames.putIfAbsent(account.id, account.name)
                reportContext.payees.map { it.id to (it.transferAccountId?.let(accountNames::get) ?: it.name) }
            }
            "Account" -> reportContext.accounts.map { it.id to it.name }
            "Category", "CategoryGroup" -> reportContext.categories.map { it.id to it.name } + listOf(
                UNCATEGORIZED to ReportStrings.text("Uncategorized", locale),
                OFF_BUDGET to ReportStrings.text("Off budget", locale),
                TRANSFER to ReportStrings.text("Transfers", locale),
            )
            else -> reportContext.groups.map { it.id to it.name } + uncategorizedGroup // Group
        }

        var rows = orderedRows.map { (key, rowName) ->
            val perBucket = bucketCells(key)
            val cell = perBucket.fold(Cell(), Cell::plus)
            Row(key, rowName, cell, perBucket.map(value), value(cell))
        }
        // Upstream filterEmptyRows: Net/Budgeted keep any row with activity;
        // the rest keep rows whose metric is non-zero. For Net Payment / Net
        // Deposit that is the row's whole-range net, so a row that is negative
        // in one month but positive overall hides under Net Payment.
        if (!config.showEmpty) rows = rows.filter { if (signed) it.cell.assets != 0L || it.cell.debts != 0L else it.total != 0.0 }
        // Upstream trimIntervals.ts: span from the first to the last interval
        // where any surviving row, or the overall total, is non-zero.
        if (config.trimIntervals) {
            val overall = buckets.indices.map { i -> value(cells.values.fold(Cell()) { acc, row -> acc + (row[i] ?: Cell()) }) }
            val range = trimmedRange(rows.map { it.perBucket } + listOf(overall))
            labels = labels.slice(range)
            rows = rows.map { it.copy(perBucket = it.perBucket.slice(range)) }
        }
        rows = sorted(rows, config.sortBy, { it.total }, { it.name }, locale)

        return data(
            when (config.graphType) {
                "TableGraph" -> CustomReportData.Kind.Table(rows.map { CustomReportData.TableRow(it.name, it.total) })
                "StackedBarGraph" -> CustomReportData.Kind.StackedBars(series(rows, labels))
                "LineGraph" -> {
                    val s = series(rows, labels)
                    CustomReportData.Kind.Lines(s, if (config.showTrendLines) s.values.mapNotNull(::trend) else emptyList())
                }
                "DonutGraph" -> if (config.groupBy == "CategoryGroup") {
                    twoRingDonut(rows, reportContext, categoriesById, config.sortBy, locale)
                } else {
                    // A wedge can't have a negative angle; upstream disables Net
                    // here, so every allowed metric is already non-negative.
                    CustomReportData.Kind.Donut(rows.filter { it.total > 0 }.map { CustomReportData.Slice(it.name, it.total, null) }, emptyList())
                }
                else -> CustomReportData.Kind.Bars(rows.map { CustomReportData.Bar(it.name, it.total) }, signed)
            },
        )
    }

    // Metric

    /**
     * Upstream balanceTypeMap: Payment = |debts|, Deposit = assets, Net
     * Payment = |net| when negative, Net Deposit = net when positive, Net and
     * Budgeted = signed net (totalBudgeted mirrors totalTotals; only the
     * dataset differs).
     */
    private fun metric(cell: Cell, balanceType: String): Double {
        val net = cell.assets + cell.debts
        return when (balanceType) {
            "Payment" -> -cell.debts / 100.0
            "Deposit" -> cell.assets / 100.0
            "Net Payment" -> if (net < 0) -net / 100.0 else 0.0
            "Net Deposit" -> if (net > 0) net / 100.0 else 0.0
            else -> net / 100.0
        }
    }

    /**
     * Upstream sortData.ts sorts by the signed metric (its debts reversal is
     * already baked in because Payment is stored positive here).
     */
    private fun <T> sorted(items: List<T>, sortBy: String, total: (T) -> Double, name: (T) -> String, locale: Locale): List<T> = when (sortBy) {
        "asc" -> items.sortedBy(total)
        "name" -> {
            val loose = Collator.getInstance(locale).apply { strength = Collator.PRIMARY } // case- and accent-insensitive
            val caseless = Collator.getInstance(locale).apply { strength = Collator.SECONDARY } // accents count, case doesn't
            items.withIndex().sortedWith { lhs, rhs ->
                loose.compare(name(lhs.value), name(rhs.value)).takeIf { it != 0 }
                    ?: caseless.compare(name(lhs.value), name(rhs.value)).takeIf { it != 0 }
                    ?: lhs.index.compareTo(rhs.index)
            }.map { it.value }
        }
        "budget" -> items // keep store order
        else -> items.sortedByDescending(total) // desc
    }

    private fun series(rows: List<Row>, labels: List<String>) = CustomReportData.Stacked(labels, rows.map { it.name }, rows.map { it.perBucket })

    /** First…last interval where any series is non-zero; empty when none is. */
    private fun trimmedRange(series: List<List<Double>>): IntRange {
        val count = series.firstOrNull()?.size ?: 0
        val nonEmpty = (0 until count).map { i -> series.any { it[i] != 0.0 } }
        val first = nonEmpty.indexOf(true)
        if (first < 0) return IntRange.EMPTY
        return first..nonEmpty.lastIndexOf(true)
    }

    /** Least-squares line through the series (upstream computeTrendLines.ts) over x = 0…n-1. Needs two points. */
    fun trend(ys: List<Double>): CustomReportData.Trend? {
        if (ys.size < 2) return null
        val n = ys.size.toDouble()
        val sumX = n * (n - 1) / 2
        val sumX2 = (n - 1) * n * (2 * n - 1) / 6
        val sumY = ys.sum()
        val sumXY = ys.withIndex().sumOf { it.index * it.value }
        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n
        return CustomReportData.Trend(intercept, intercept + slope * (n - 1))
    }

    // Budgeted dataset

    /**
     * Upstream budgetDataQuery.fetchBudgetData: one row per month in range per
     * non-income category with a non-zero budget, keyed to the month. Emitted
     * as synthetic transactions so the same bucketing and row filters apply.
     */
    private fun budgetRows(context: ReportContext, startYMD: Int, endYMD: Int): List<Transaction> {
        val income = context.categories.filter { it.isIncome }.mapTo(HashSet()) { it.id }
        return context.budgetEntries.mapNotNull { entry ->
            if (entry.amountCents == 0L || entry.categoryId in income || entry.month < startYMD / 100 || entry.month > endYMD / 100) return@mapNotNull null
            // ponytail: cells sit on the 1st, so Daily/Weekly intervals show a
            // month's budget on its first day where upstream shows nothing.
            Transaction(
                id = "${entry.month}-${entry.categoryId}", accountId = "", date = entry.month * 100 + 1, amount = entry.amountCents,
                payeeId = null, payeeName = null, categoryId = entry.categoryId, categoryName = null, notes = null,
                cleared = true, reconciled = false, transferId = null, isParent = false, parentId = null, tombstone = false,
                sortOrder = null, importedPayee = null,
            )
        }
    }

    // Two-ring donut (groupBy CategoryGroup)

    /**
     * Upstream grouped-spreadsheet.ts + DonutGraph.tsx adjustedGroupData: the
     * inner ring is the category groups in store order (the synthetic rows
     * form "Uncategorized & Off budget"), each group's value being the sum of
     * its visible categories so the rings line up; zero groups drop out, and
     * groups and their categories sort by the same rule.
     */
    private fun twoRingDonut(
        rows: List<Row>,
        context: ReportContext,
        categoriesById: Map<String, Category>,
        sortBy: String,
        locale: Locale,
    ): CustomReportData.Kind {
        data class Group(val name: String, val total: Double, val members: List<Row>)

        val order = context.groups.map { it.id to it.name } + (UNCATEGORIZED to ReportStrings.text("Uncategorized & Off budget", locale))
        var groups = order.mapNotNull { (id, name) ->
            val members = sorted(
                rows.filter { it.total > 0 && (categoriesById[it.key]?.groupId ?: UNCATEGORIZED) == id },
                sortBy, { it.total }, { it.name }, locale,
            )
            if (members.isEmpty()) null else Group(name, members.sumOf { it.total }, members)
        }
        groups = sorted(groups, sortBy, { it.total }, { it.name }, locale)
        return CustomReportData.Kind.Donut(
            groups.flatMapIndexed { gi, group -> group.members.map { CustomReportData.Slice(it.name, it.total, gi) } },
            groups.map { CustomReportData.Bar(it.name, it.total) },
        )
    }

    // Interval bucketing

    /** Bucket key: Daily = YYYYMMDD, Weekly = YYYYMMDD of week start, Monthly = YYYYMM, Yearly = YYYY. */
    private fun bucketKey(ymd: Int, interval: String, firstDayOfWeekIdx: Int): Int = when (interval) {
        "Daily" -> ymd
        "Weekly" -> localDate(ymd)?.let { ReportDateRange.weekStart(it, firstDayOfWeekIdx).ymd } ?: ymd
        "Yearly" -> ymd / 10000
        else -> ymd / 100 // Monthly
    }

    /** (key, label) per interval, chronological. */
    private fun intervalBuckets(start: LocalDate, end: LocalDate, interval: String, firstDayOfWeekIdx: Int, locale: Locale): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        when (interval) {
            "Daily" -> {
                val formatter = DateTimeFormatter.ofPattern("yy-MM-dd", locale)
                var d = start
                while (d <= end) {
                    out += d.ymd to d.format(formatter)
                    d = d.plusDays(1)
                }
            }
            "Weekly" -> {
                val formatter = DateTimeFormatter.ofPattern("yy-MM-dd", locale)
                var d = ReportDateRange.weekStart(start, firstDayOfWeekIdx)
                val last = ReportDateRange.weekStart(end, firstDayOfWeekIdx)
                while (d <= last) {
                    out += d.ymd to d.format(formatter)
                    d = d.plusDays(7)
                }
            }
            "Yearly" -> for (y in start.year..end.year) out += y to LocalDate.of(y, 1, 1).format(DateTimeFormatter.ofPattern("yyyy", locale))
            else -> {
                // Monthly — label "MMM ''yy" → Sep '25 (upstream intervalFormat)
                val formatter = DateTimeFormatter.ofPattern("MMM ''yy", locale)
                var d = start.monthStart()
                val last = end.monthStart()
                while (d <= last) {
                    out += (d.year * 100 + d.monthValue) to d.format(formatter)
                    d = d.plusMonths(1)
                }
            }
        }
        return out
    }

    private fun localizedRangeLabel(value: String, locale: Locale): String = when (value) {
        "All time" -> ReportStrings.text("All Time", locale)
        "Year to date" -> ReportStrings.text("Year to date", locale)
        else -> value
    }
}
