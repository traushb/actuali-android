package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.BudgetType
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.budget.CleanupTemplate
import io.github.traushb.actuali.services.budget.GoalTemplateSheet
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.aliveChildPredicate
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.monthStringToInt
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.nextMonth
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.placeholders
import io.github.traushb.actuali.services.stringOrNull
import io.github.traushb.actuali.services.sync.MerkleNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

private val logger = Logger("BudgetDatabase")

/** The columns of a `categories` row the budget math reads. */
data class CategoryRecord(
    val id: String,
    val name: String?,
    val isIncome: Long?,
    val catGroup: String?,
    val sortOrder: Double?,
    val hidden: Long?,
)

/** The columns of a `category_groups` row the budget math reads. */
data class CategoryGroupRecord(
    val id: String,
    val name: String?,
    val isIncome: Long?,
    val sortOrder: Double?,
    val hidden: Long?,
)

/**
 * Everything the month-by-month budget walk produces — shared by
 * `fetchBudgetMonth` (the Budget tab) and `fetchGoalTemplateSheet` (the
 * goal-template engine), so the two can never disagree about balances.
 */
data class BudgetWalkResult(
    val isEnvelope: Boolean,
    /** Budget rows keyed by YYYYMM month int, then category id. */
    val budgetByMonthCat: Map<Int, Map<String, BudgetRow>>,
    /** Net activity per (YYYYMM, category). */
    val spentByMonthCat: Map<Int, Map<String, Long>>,
    /** End-of-month category balances per (YYYYMM, category). */
    val leftoverByMonthCat: Map<Int, Map<String, Long>>,
    /** Envelope "To Budget" at the target month (0 for tracking). */
    val toBudget: Long,
    val summaryIncome: Long,
    val summaryBudgeted: Long,
    val summaryLastMonthOverspent: Long,
    val summaryBuffered: Long,
    val summaryManualBuffered: Long,
    val incomeCatIds: Set<String>,
    val categories: List<CategoryRecord>,
    val groups: List<CategoryGroupRecord>,
) {
    data class BudgetRow(val amount: Long, val flag: Boolean, val goal: Long?, val longGoal: Long?)
}

/**
 * Which budget table this file uses, or null when it has neither. Real Actual
 * files contain BOTH zero_budgets and reflect_budgets (loot-core's migrations
 * create them unconditionally), so table existence says nothing — the
 * budgetType preference is the only signal of which one is live: 'tracking'
 * ('report' before the Actual 25.5 rename migration) means reflect_budgets;
 * anything else, including no row at all, means envelope, matching
 * upstream's default. When only one table exists the preference can't
 * override it — writing into a missing table would fail.
 */
internal fun budgetTable(db: Db): String? {
    val hasZero = db.tableExists("zero_budgets")
    val hasReflect = db.tableExists("reflect_budgets")
    if (!(hasZero && hasReflect)) {
        return when {
            hasZero -> "zero_budgets"
            hasReflect -> "reflect_budgets"
            else -> null
        }
    }
    var isTracking = false
    if (db.tableExists("preferences")) {
        val value = db.string("SELECT value FROM preferences WHERE id = 'budgetType'")
        isTracking = BudgetType.fromPreference(value) == BudgetType.Tracking
    }
    return if (isTracking) "reflect_budgets" else "zero_budgets"
}

/**
 * The month-by-month walk behind the Budget tab and goal templates: budgets,
 * spending and carryover for every month up to [targetMonthInt].
 */
internal fun budgetWalk(db: Db, targetMonthInt: Int): BudgetWalkResult {
    // Envelope (zero_budgets) clamps negative leftover to 0 unless the
    // carryover flag is set. Tracking (reflect_budgets) drops any prior
    // leftover entirely unless the flag is set.
    val budgetsTable = budgetTable(db)
    val isEnvelope = budgetsTable != "reflect_budgets"

    // Bulk-load all budget rows up to and including the target month.
    // Months are YYYYMM ints in the budgets tables.
    val budgetByMonthCat = HashMap<Int, HashMap<String, BudgetWalkResult.BudgetRow>>()
    if (budgetsTable != null) {
        val rows = db.fetchAll(
            "SELECT month, category, amount, carryover, goal, long_goal FROM $budgetsTable WHERE month <= ?",
            targetMonthInt,
        )
        for (row in rows) {
            val m = row.int("month") ?: 0
            val categoryId = row.string("category")
            if (m <= 0 || categoryId == null) continue
            budgetByMonthCat.getOrPut(m) { HashMap() }[categoryId] = BudgetWalkResult.BudgetRow(
                amount = row.long("amount") ?: 0,
                flag = (row.long("carryover") ?: 0) == 1L,
                goal = row.long("goal"),
                longGoal = row.long("long_goal"),
            )
        }
    }

    // Bulk-load spent per (YYYYMM, category) up to and including the target
    // month. Mirrors Actual's own spent query (loot-core base.ts
    // getSumAmountsByMonth over v_transactions_internal_alive):
    //   * Resolve the category through category_mapping — merged categories
    //     keep the old id on their transactions but point it at the survivor.
    //   * Only count on-budget accounts, and skip deleted accounts (a live
    //     transaction left on one is a sync-race orphan).
    //   * Do NOT filter transfers: on-budget↔on-budget transfers carry no
    //     category; a categorised leg goes off-budget and counts as spent.
    //   * Exclude split parents (a pre-split category lingers on the parent
    //     row) and children of tombstoned parents.
    val spentRows = db.fetchAll(
        """
        SELECT
            (t.date / 100) AS month,
            COALESCE(cm.transferId, t.category) AS category_id,
            SUM(t.amount) AS spent
        FROM transactions t
        LEFT JOIN category_mapping cm ON cm.id = t.category
        LEFT JOIN accounts a ON a.id = t.acct
        LEFT JOIN transactions p ON p.id = t.parent_id
        WHERE (t.tombstone = 0 OR t.tombstone IS NULL)
          AND ${aliveChildPredicate("p")}
          AND (t.isParent = 0 OR t.isParent IS NULL)
          AND t.category IS NOT NULL
          AND a.offbudget = 0
          AND (a.tombstone = 0 OR a.tombstone IS NULL)
          AND (t.date / 100) <= ?
        GROUP BY (t.date / 100), COALESCE(cm.transferId, t.category)
        """,
        targetMonthInt,
    )
    val spentByMonthCat = HashMap<Int, HashMap<String, Long>>()
    for (row in spentRows) {
        val m = row.int("month") ?: 0
        val categoryId = row.string("category_id")
        if (m <= 0 || categoryId == null) continue
        spentByMonthCat.getOrPut(m) { HashMap() }[categoryId] = row.long("spent") ?: 0
    }

    // "Hold for next month" amounts, keyed by YYYYMM. Upstream writes
    // zero_budget_months ids as sheet month strings ("2026-07"); parse digits
    // defensively in case another client wrote "202607".
    val bufferedByMonth = HashMap<Int, Long>()
    if (isEnvelope && db.tableExists("zero_budget_months")) {
        for (row in db.fetchAll("SELECT id, buffered FROM zero_budget_months")) {
            val m = row.string("id")?.filter { it.isDigit() }?.toIntOrNull() ?: continue
            if (m % 100 !in 1..12 || m > targetMonthInt) continue
            bufferedByMonth[m] = row.long("buffered") ?: 0
        }
    }

    // Category id sets for the envelope "to budget" math. Hidden categories
    // still count toward the totals (upstream includes them in the summary
    // sheet); only tombstoned ones drop out.
    val categories = db.fetchAll(
        "SELECT id, name, is_income, cat_group, sort_order, hidden FROM categories WHERE (tombstone = 0 OR tombstone IS NULL)",
    ).map {
        CategoryRecord(it.string("id") ?: "", it.string("name"), it.long("is_income"), it.string("cat_group"), it.double("sort_order"), it.long("hidden"))
    }
    val incomeCatIds = categories.filter { it.isIncome == 1L }.map { it.id }.toSet()
    val expenseCatIds = categories.filter { it.isIncome != 1L }.map { it.id }.toSet()

    // The earliest month we need to walk from: any budget row, spent row, or
    // held amount. If none, just the target.
    val earliestMonth = (budgetByMonthCat.keys + spentByMonthCat.keys + bufferedByMonth.keys).minOrNull() ?: targetMonthInt

    // Walk forward month-by-month, computing leftover per category.
    var runningLeftover: Map<String, Long> = emptyMap()
    // The carryover flag applied at the boundary M -> M+1 is the flag stored
    // on month M (the source month).
    var lastFlag: Map<String, Boolean> = emptyMap()

    // Envelope "To Budget" accumulators (mirrors loot-core envelope.ts
    // createSummary):
    //   to-budget = income + from-last-month + last-month-overspent
    //               - budgeted - buffered
    // where from-last-month = prior to-budget + prior buffered, and
    // last-month-overspent is the negative leftover the clamp below strips
    // from categories — that debt comes out of this month's unallocated
    // funds instead.
    var runningToBudget = 0L
    var priorBuffered = 0L
    var summaryIncome = 0L
    var summaryBudgeted = 0L
    var summaryLastMonthOverspent = 0L
    var summaryBuffered = 0L
    var summaryManualBuffered = 0L
    val leftoverByMonthCat = HashMap<Int, Map<String, Long>>()

    var m = earliestMonth
    while (m <= targetMonthInt) {
        val budgetsForMonth = budgetByMonthCat[m].orEmpty()
        val spentForMonth = spentByMonthCat[m].orEmpty()

        if (isEnvelope) {
            var income = 0L
            var bufferedAuto = 0L
            for (cat in incomeCatIds) {
                val amount = spentForMonth[cat] ?: 0
                income += amount
                // Income marked "carryover" is auto-held for next month
                // unless a manual hold overrides it.
                if (budgetsForMonth[cat]?.flag == true) bufferedAuto += amount
            }
            var budgetedTotal = 0L
            var lastMonthOverspent = 0L
            for (cat in expenseCatIds) {
                budgetedTotal += budgetsForMonth[cat]?.amount ?: 0
                if (lastFlag[cat] != true) lastMonthOverspent += min(0, runningLeftover[cat] ?: 0)
            }
            val manualBuffered = bufferedByMonth[m] ?: 0
            val buffered = if (manualBuffered != 0L) manualBuffered else bufferedAuto
            runningToBudget = income + runningToBudget + priorBuffered + lastMonthOverspent - budgetedTotal - buffered
            priorBuffered = buffered
            if (m == targetMonthInt) {
                summaryIncome = income
                summaryBudgeted = budgetedTotal
                summaryLastMonthOverspent = lastMonthOverspent
                summaryBuffered = buffered
                summaryManualBuffered = manualBuffered
            }
        }

        val touchedCats = budgetsForMonth.keys + spentForMonth.keys + runningLeftover.keys
        val nextLeftover = HashMap<String, Long>()
        val nextFlag = HashMap<String, Boolean>()
        for (cat in touchedCats) {
            val budgeted = budgetsForMonth[cat]?.amount ?: 0
            val spent = spentForMonth[cat] ?: 0
            val prior = runningLeftover[cat] ?: 0
            // Contribution of the prior month's leftover into this month.
            val contribution = when {
                lastFlag[cat] == true -> prior
                isEnvelope -> max(0, prior)
                else -> 0
            }
            nextLeftover[cat] = budgeted + spent + contribution
            nextFlag[cat] = budgetsForMonth[cat]?.flag ?: false
        }
        runningLeftover = nextLeftover
        lastFlag = nextFlag
        leftoverByMonthCat[m] = nextLeftover
        m = nextMonth(m)
    }

    val groups = db.fetchAll(
        "SELECT id, name, is_income, sort_order, hidden FROM category_groups WHERE (tombstone = 0 OR tombstone IS NULL)",
    ).map { CategoryGroupRecord(it.string("id") ?: "", it.string("name"), it.long("is_income"), it.double("sort_order"), it.long("hidden")) }

    return BudgetWalkResult(
        isEnvelope = isEnvelope,
        budgetByMonthCat = budgetByMonthCat,
        spentByMonthCat = spentByMonthCat,
        leftoverByMonthCat = leftoverByMonthCat,
        toBudget = runningToBudget,
        summaryIncome = summaryIncome,
        summaryBudgeted = summaryBudgeted,
        summaryLastMonthOverspent = summaryLastMonthOverspent,
        summaryBuffered = summaryBuffered,
        summaryManualBuffered = summaryManualBuffered,
        incomeCatIds = incomeCatIds,
        categories = categories,
        groups = groups,
    )
}

data class EnvelopeBudgetSummaryData(
    val availableFunds: Long,
    val lastMonthOverspent: Long,
    val budgeted: Long,
    val toBudget: Long,
    val buffered: Long,
)

suspend fun BudgetDatabase.fetchEnvelopeBudgetSummary(month: String): EnvelopeBudgetSummaryData? = dbQueue.readAsync { db ->
    val walk = budgetWalk(db, monthStringToInt(month))
    if (!walk.isEnvelope) return@readAsync null
    EnvelopeBudgetSummaryData(
        availableFunds = walk.toBudget - walk.summaryLastMonthOverspent + walk.summaryBudgeted + walk.summaryBuffered,
        lastMonthOverspent = walk.summaryLastMonthOverspent,
        budgeted = walk.summaryBudgeted,
        toBudget = walk.toBudget,
        buffered = walk.summaryManualBuffered,
    )
}

suspend fun BudgetDatabase.fetchBudgetMonth(month: String): BudgetMonth = dbQueue.readAsync { db ->
    val targetMonthInt = monthStringToInt(month)
    val walk = budgetWalk(db, targetMonthInt)
    val targetBudgets = walk.budgetByMonthCat[targetMonthInt].orEmpty()
    val targetSpent = walk.spentByMonthCat[targetMonthInt].orEmpty()
    val runningLeftover = walk.leftoverByMonthCat[targetMonthInt].orEmpty()
    val groupsById = walk.groups.associateBy { it.id }

    val allCategoryBudgets = walk.categories.mapNotNull { cat ->
        if (cat.isIncome == 1L) return@mapNotNull null
        val group = groupsById[cat.catGroup ?: ""] ?: return@mapNotNull null
        val budgeted = targetBudgets[cat.id]?.amount ?: 0
        val spent = targetSpent[cat.id] ?: 0
        val available = runningLeftover[cat.id] ?: (budgeted + spent)
        // The carryover into the target month is the prior month's leftover
        // contribution (post clamp / flag), reverse-derived from the totals.
        CategoryBudget(
            month = month,
            categoryId = cat.id,
            categoryName = cat.name ?: "Unknown",
            groupId = cat.catGroup ?: "",
            groupName = group.name ?: "Unknown",
            groupSortOrder = group.sortOrder ?: Double.MAX_VALUE,
            categorySortOrder = cat.sortOrder ?: Double.MAX_VALUE,
            budgeted = budgeted,
            spent = spent,
            available = available,
            carryover = available - budgeted - spent,
            hidden = cat.hidden == 1L,
            groupHidden = group.hidden == 1L,
            goal = targetBudgets[cat.id]?.goal,
            longGoal = targetBudgets[cat.id]?.longGoal == 1L,
            carryoverEnabled = targetBudgets[cat.id]?.flag == true,
        )
    }

    // Income categories, shown as their own section like the web UI's Income
    // group. "Received" is the month's net activity on the category.
    val allIncomeCategories = walk.categories.mapNotNull { cat ->
        if (cat.isIncome != 1L) return@mapNotNull null
        val group = groupsById[cat.catGroup ?: ""] ?: return@mapNotNull null
        IncomeCategory(
            month = month,
            categoryId = cat.id,
            categoryName = cat.name ?: "Unknown",
            groupName = group.name ?: "Income",
            sortOrder = cat.sortOrder ?: Double.MAX_VALUE,
            budgeted = targetBudgets[cat.id]?.amount ?: 0,
            received = targetSpent[cat.id] ?: 0,
            hidden = cat.hidden == 1L,
            groupHidden = group.hidden == 1L,
        )
    }.sortedBy { it.sortOrder }

    BudgetMonth(
        month = month,
        categoryBudgets = allCategoryBudgets.filter { !it.isEffectivelyHidden },
        incomeCategories = allIncomeCategories.filter { !it.isEffectivelyHidden },
        toBudget = if (walk.isEnvelope) walk.toBudget else null,
        buffered = if (walk.isEnvelope) walk.summaryManualBuffered else 0,
        hiddenCategoryBudgets = allCategoryBudgets.filter { it.isEffectivelyHidden },
        hiddenIncomeCategories = allIncomeCategories.filter { it.isEffectivelyHidden },
    )
}

// Notes

/**
 * The note stored for one row of the budget — a category or an account.
 * One read reports both whether the file has the table and what it holds, so
 * the caller can distinguish "this file can't store notes" from "this row has
 * none".
 */
suspend fun BudgetDatabase.fetchNote(id: String): EntityNote = dbQueue.readAsync { db ->
    if (!db.tableExists("notes")) return@readAsync EntityNote.unsupported
    // A row can exist with a NULL note; that reads as empty, same as no row.
    EntityNote(true, db.string("SELECT note FROM notes WHERE id = ?", id) ?: "")
}

/** Whether this file has the `notes` table, for `SyncClient`'s write guard. Blocking: the write path can't suspend. */
fun BudgetDatabase.notesTableExists(): Boolean = dbQueue.read { it.tableExists("notes") }

fun BudgetDatabase.zeroBudgetMonthsTableExists(): Boolean = dbQueue.read { it.tableExists("zero_budget_months") }

fun BudgetDatabase.incomeCategoryIds(): List<String> = dbQueue.read { db ->
    db.strings("SELECT id FROM categories WHERE is_income = 1 AND (tombstone = 0 OR tombstone IS NULL)")
}

/** Where a budget amount write for (month, category) must land. */
data class BudgetCellRef(
    /** "zero_budgets" (envelope) or "reflect_budgets" (tracking). */
    val table: String,
    val rowId: String,
    /** YYYYMM. */
    val monthInt: Int,
    val exists: Boolean,
    /**
     * Current budgeted amount in cents (0 when the row doesn't exist), read in
     * the same transaction as the row lookup so transfer writes compute
     * source-minus / destination-plus from a consistent snapshot.
     */
    val amount: Long,
)

/**
 * Resolve the budget cell for a month ("2026-07") and category. Mirrors
 * upstream setBudget (loot-core budget/actions.ts): look the row up by
 * (month, category) and reuse its id — rows written by other clients may not
 * follow the {YYYYMM}-{categoryId} convention, and inserting a second row for
 * the same cell would fork it. Null when the file has no budget table or the
 * month string is malformed.
 */
fun BudgetDatabase.budgetCell(month: String, categoryId: String): BudgetCellRef? {
    val monthInt = monthStringToInt(month)
    if (monthInt <= 0) return null
    return dbQueue.read { db ->
        val table = budgetTable(db) ?: return@read null
        val existing = db.fetchOne("SELECT id, amount FROM $table WHERE month = ? AND category = ?", monthInt, categoryId)
        BudgetCellRef(
            table = table,
            rowId = existing?.string("id") ?: "$monthInt-$categoryId",
            monthInt = monthInt,
            exists = existing != null,
            amount = existing?.long("amount") ?: 0,
        )
    }
}

// Goal Templates

/**
 * One category as the goal-template pipeline sees it: identity, whether its
 * templates are UI-managed (web's template editor) or notes-managed, the
 * stored `goal_def`, and its note text for the notes → goal_def sync.
 */
data class GoalTemplateCategoryRow(
    val id: String,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val groupHidden: Boolean,
    val sourceIsUI: Boolean,
    val goalDef: String?,
    val cleanupDef: String?,
    val note: String?,
)

suspend fun BudgetDatabase.fetchGoalTemplateCategories(): List<GoalTemplateCategoryRow> = dbQueue.readAsync { db ->
    val hasNotes = db.tableExists("notes")
    val noteSelect = if (hasNotes) ", n.note AS note" else ""
    val noteJoin = if (hasNotes) "LEFT JOIN notes n ON n.id = c.id" else ""
    db.fetchAll(
        """
        SELECT c.id, c.name, c.is_income, c.hidden, c.goal_def, c.cleanup_def,
               c.template_settings, g.hidden AS group_hidden$noteSelect
        FROM categories c
        LEFT JOIN category_groups g ON g.id = c.cat_group
        $noteJoin
        WHERE (c.tombstone = 0 OR c.tombstone IS NULL)
        """,
    ).mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        // template_settings is a JSON blob; upstream treats anything that
        // isn't explicitly source:'ui' as notes-managed.
        val settings = row.string("template_settings")?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        GoalTemplateCategoryRow(
            id = id,
            name = row.string("name") ?: "Unknown",
            isIncome = (row.long("is_income") ?: 0) == 1L,
            hidden = (row.long("hidden") ?: 0) == 1L,
            groupHidden = (row.long("group_hidden") ?: 0) == 1L,
            sourceIsUI = settings?.get("source").stringOrNull == "ui",
            goalDef = row.string("goal_def"),
            cleanupDef = row.string("cleanup_def"),
            note = if (hasNotes) row.string("note") else null,
        )
    }
}

/** Live cleanup pools (`cleanup_groups`), for the automation editor. */
suspend fun BudgetDatabase.fetchCleanupGroups(): List<Pair<String, String>> = dbQueue.readAsync { db ->
    if (!db.tableExists("cleanup_groups")) return@readAsync emptyList()
    db.fetchAll("SELECT id, name FROM cleanup_groups WHERE (tombstone = 0 OR tombstone IS NULL) ORDER BY name").mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        val name = row.string("name") ?: return@mapNotNull null
        id to name
    }
}

/**
 * Find a pool by name, including tombstoned rows so an editor save can revive
 * the existing id instead of creating a duplicate.
 */
suspend fun BudgetDatabase.findCleanupGroupId(name: String): String? = dbQueue.readAsync { db ->
    if (!db.tableExists("cleanup_groups")) return@readAsync null
    val target = name.lowercase()
    db.fetchAll("SELECT id, name FROM cleanup_groups ORDER BY tombstone, name").firstOrNull { row ->
        row.string("id") != null && row.string("name")?.lowercase() == target
    }?.string("id")
}

/**
 * Tombstone cleanup pools no live category references any more. Local-only
 * like upstream's `tombstoneOrphanCleanupGroups` (a plain UPDATE, no CRDT
 * messages) — every client re-derives it from the synced cleanup_defs.
 */
suspend fun BudgetDatabase.tombstoneOrphanCleanupGroups() = dbQueue.writeAsync { db ->
    if (!db.tableExists("cleanup_groups")) return@writeAsync
    val defs = db.strings(
        "SELECT cleanup_def FROM categories WHERE (tombstone = 0 OR tombstone IS NULL) AND cleanup_def IS NOT NULL",
    )
    val referenced = defs.flatMap { CleanupTemplate.decodeArray(it).orEmpty() }.mapNotNull { it.groupId }.toSet()
    if (referenced.isEmpty()) {
        db.execute("UPDATE cleanup_groups SET tombstone = 1 WHERE tombstone = 0")
    } else {
        db.execute(
            "UPDATE cleanup_groups SET tombstone = 1 WHERE tombstone = 0 AND id NOT IN (${placeholders(referenced.size)})",
            referenced.toList(),
        )
    }
}

/**
 * The sheet-value snapshot the goal-template engine runs against: every cell
 * it can read, for all months up to and including [month].
 */
suspend fun BudgetDatabase.fetchGoalTemplateSheet(month: String): GoalTemplateSheet = dbQueue.readAsync { db ->
    val targetMonthInt = monthStringToInt(month)
    val walk = budgetWalk(db, targetMonthInt)
    val sheet = GoalTemplateSheet()
    sheet.isTracking = !walk.isEnvelope
    sheet.availableStart = if (walk.isEnvelope) {
        walk.toBudget
    } else {
        // tracking `total-saved`: budgeted income minus budgeted expenses for
        // the month (loot-core tracking.ts).
        walk.budgetByMonthCat[targetMonthInt].orEmpty().entries.sumOf { (categoryId, row) ->
            if (categoryId in walk.incomeCatIds) row.amount else -row.amount
        }
    }
    if (db.tableExists("preferences")) {
        sheet.hideFraction = db.string("SELECT value FROM preferences WHERE id = 'hideFraction'") == "true"
    }
    fun noteActivity(categoryId: String, monthInt: Int) {
        sheet.firstActivityMonth[categoryId] = sheet.firstActivityMonth[categoryId]?.let { min(it, monthInt) } ?: monthInt
    }
    for ((monthInt, rowsByCategory) in walk.budgetByMonthCat) {
        for ((categoryId, budgetRow) in rowsByCategory) {
            val key = GoalTemplateSheet.MonthCat(monthInt, categoryId)
            sheet.budgeted[key] = budgetRow.amount
            if (budgetRow.flag) sheet.carryover += key
            budgetRow.goal?.let { sheet.goals[key] = it }
            if (budgetRow.goal != null || budgetRow.longGoal != null) sheet.goalRows += key
            noteActivity(categoryId, monthInt)
        }
    }
    for ((monthInt, spentByCategory) in walk.spentByMonthCat) {
        var income = 0L
        for ((categoryId, amount) in spentByCategory) {
            sheet.spent[GoalTemplateSheet.MonthCat(monthInt, categoryId)] = amount
            if (categoryId in walk.incomeCatIds) income += amount
            noteActivity(categoryId, monthInt)
        }
        sheet.totalIncome[monthInt] = income
    }
    for ((monthInt, leftoverByCategory) in walk.leftoverByMonthCat) {
        for ((categoryId, amount) in leftoverByCategory) {
            sheet.leftover[GoalTemplateSheet.MonthCat(monthInt, categoryId)] = amount
        }
    }
    sheet
}

/**
 * Clear `goal_def` on categories whose notes no longer hold templates.
 * Deliberately not CRDT-synced: upstream's
 * `resetCategoryGoalDefsWithNoTemplates` is a plain UPDATE too — every client
 * re-derives the reset from the synced notes.
 */
suspend fun BudgetDatabase.resetGoalDefs(categoryIds: List<String>) {
    if (categoryIds.isEmpty()) return
    dbQueue.writeAsync { db ->
        db.execute("UPDATE categories SET goal_def = NULL WHERE id IN (${placeholders(categoryIds.size)})", categoryIds)
    }
}

/** A synced preference value (`preferences` table), null when unset or the table is missing. */
suspend fun BudgetDatabase.fetchPreference(id: String): String? = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync null
    db.string("SELECT value FROM preferences WHERE id = ?", id)
}

// Clock Storage

data class ClockRecord(val timestamp: String, val merkle: MerkleNode)

fun BudgetDatabase.loadClock(): ClockRecord? = dbQueue.read { db ->
    if (!db.tableExists("messages_clock")) {
        logger.info("messages_clock table doesn't exist, starting fresh")
        return@read null
    }
    val clockJson = db.string("SELECT clock FROM messages_clock WHERE id = 1") ?: return@read null
    val json = runCatching { AppJson.parseToJsonElement(clockJson) }.getOrNull() as? JsonObject
    if (json == null) {
        logger.notice("Could not decode clock data, starting fresh")
        return@read null
    }
    // Our {timestamp, merkle} record first (what upstream writes too).
    val timestamp = (json["timestamp"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val merkle = json["merkle"] as? JsonObject
    if (timestamp != null && merkle != null) {
        runCatching { MerkleNode.fromJson(merkle) }.getOrNull()?.let { return@read ClockRecord(timestamp, it) }
    }
    // Fallback: a bare merkle tree, not wrapped in a record.
    runCatching { MerkleNode.fromJson(json) }.getOrNull()?.let {
        logger.info("Loaded legacy clock format (merkle only)")
        return@read ClockRecord("", it)
    }
    logger.notice("Could not decode clock data, starting fresh")
    null
}

fun BudgetDatabase.saveClock(clock: ClockRecord) {
    val json = JsonObject(linkedMapOf("timestamp" to JsonPrimitive(clock.timestamp), "merkle" to clock.merkle.toJson())).toString()
    dbQueue.write { db ->
        db.execute("CREATE TABLE IF NOT EXISTS messages_clock (id INTEGER PRIMARY KEY, clock TEXT)")
        db.execute("INSERT OR REPLACE INTO messages_clock (id, clock) VALUES (1, ?)", json)
    }
}
