package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.Schedule
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.mapTransaction
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.placeholders
import io.github.traushb.actuali.services.numberOrNull
import io.github.traushb.actuali.services.rules.RuleContext
import io.github.traushb.actuali.services.rules.RuleRanker
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.RecurConfig
import io.github.traushb.actuali.services.schedules.ScheduleConditions
import io.github.traushb.actuali.services.schedules.ScheduleDiscovery
import io.github.traushb.actuali.services.schedules.ScheduleStatusCalculator
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private val logger = Logger("BudgetDatabase")

// Rules

private const val LIVE_RULES_SQL = """
    SELECT id, stage, conditions_op, conditions, actions
    FROM rules
    WHERE tombstone = 0 OR tombstone IS NULL
    ORDER BY id
"""

private fun parseRule(row: Row): Rule? {
    val id = row.string("id") ?: return null
    // A rule we can't parse is a rule we must not silently half-apply:
    // upstream drops invalid rules on load too (`makeRule` returns null).
    return runCatching {
        Rule.parse(id, row.string("stage"), row.string("conditions_op"), row.string("conditions"), row.string("actions"))
    }.getOrNull()
}

/**
 * Reads every effective rules input in one SQLite snapshot. Length-prefixing
 * keeps NULL and empty values distinct, while ORDER BY id makes the
 * fingerprint bytes independent of SQLite's row-return order.
 */
fun BudgetDatabase.prepareRulesSnapshot(): BankSyncRulesSnapshot = dbQueue.read { db ->
    val rulesTableExists = db.tableExists("rules")
    val ruleRows = if (rulesTableExists) db.fetchAll(LIVE_RULES_SQL) else emptyList()
    val rules = ruleRows.mapNotNull(::parseRule)
    val context = rulesContextRows(db, ruleRows.isNotEmpty())
    BankSyncRulesSnapshot(
        rules = rules,
        context = RuleContext(
            offBudgetAccountIds = context.offBudgetAccountIds.toSet(),
            categoryGroupIds = context.categoryRows.mapNotNull { row ->
                val id = row.string("id") ?: return@mapNotNull null
                val group = row.string("cat_group") ?: return@mapNotNull null
                id to group
            }.toMap(),
            payeeNames = context.payeeRows.mapNotNull { row ->
                val id = row.string("id") ?: return@mapNotNull null
                val name = row.string("name") ?: return@mapNotNull null
                id to name
            }.toMap(),
        ),
        fingerprint = makeRulesFingerprint(rulesTableExists, ruleRows, context),
    )
}

internal fun rulesFingerprint(db: Db): BankSyncRulesFingerprint {
    val rulesTableExists = db.tableExists("rules")
    val ruleRows = if (rulesTableExists) db.fetchAll(LIVE_RULES_SQL) else emptyList()
    return makeRulesFingerprint(rulesTableExists, ruleRows, rulesContextRows(db, ruleRows.isNotEmpty()))
}

private class RulesContextRows(val offBudgetAccountIds: List<String>, val categoryRows: List<Row>, val payeeRows: List<Row>)

private fun rulesContextRows(db: Db, hasRules: Boolean): RulesContextRows {
    if (!hasRules) return RulesContextRows(emptyList(), emptyList(), emptyList())
    fun hasColumns(required: Set<String>, table: String): Boolean = db.tableExists(table) && db.columns(table).toSet().containsAll(required)
    val offBudget = if (hasColumns(setOf("id", "offbudget"), "accounts")) db.strings("SELECT id FROM accounts WHERE offbudget = 1 ORDER BY id") else emptyList()
    val categories = if (hasColumns(setOf("id", "cat_group", "tombstone"), "categories")) {
        db.fetchAll("SELECT id, cat_group FROM categories WHERE tombstone = 0 OR tombstone IS NULL ORDER BY id")
    } else {
        emptyList()
    }
    val payees = if (hasColumns(setOf("id", "name", "tombstone"), "payees")) {
        db.fetchAll("SELECT id, name FROM payees WHERE tombstone = 0 OR tombstone IS NULL ORDER BY id")
    } else {
        emptyList()
    }
    return RulesContextRows(offBudget, categories, payees)
}

private fun makeRulesFingerprint(rulesTableExists: Boolean, ruleRows: List<Row>, context: RulesContextRows): BankSyncRulesFingerprint {
    val data = ByteArrayOutputStream()
    data.write(if (rulesTableExists) 1 else 0)
    fun appendField(value: String?) {
        if (value == null) {
            data.write(0)
            return
        }
        data.write(1)
        val bytes = value.toByteArray(Charsets.UTF_8)
        data.write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        data.write(bytes)
    }
    fun appendSection(marker: Int, count: Int) {
        data.write(marker)
        data.write(ByteBuffer.allocate(8).putLong(count.toLong()).array())
    }
    appendSection(1, ruleRows.size)
    for (row in ruleRows) {
        appendField(row.string("id"))
        appendField(row.string("stage"))
        appendField(row.string("conditions_op"))
        appendField(row.string("conditions"))
        appendField(row.string("actions"))
    }
    appendSection(2, context.offBudgetAccountIds.size)
    context.offBudgetAccountIds.forEach(::appendField)
    appendSection(3, context.categoryRows.size)
    for (row in context.categoryRows) {
        appendField(row.string("id"))
        appendField(row.string("cat_group"))
    }
    appendSection(4, context.payeeRows.size)
    for (row in context.payeeRows) {
        appendField(row.string("id"))
        appendField(row.string("name"))
    }
    return BankSyncRulesFingerprint(data.toByteArray())
}

fun BudgetDatabase.rulesTableExists(): Boolean = dbQueue.read { it.tableExists("rules") }

/**
 * Budget-level context the rules engine needs for conditions it can't answer
 * from the transaction row (upstream `prepareTransactionForRules`).
 */
fun BudgetDatabase.ruleContext(): RuleContext = dbQueue.read { db ->
    RuleContext(
        offBudgetAccountIds = db.strings("SELECT id FROM accounts WHERE offbudget = 1").toSet(),
        categoryGroupIds = db.fetchAll("SELECT id, cat_group FROM categories WHERE tombstone = 0 OR tombstone IS NULL").mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val group = row.string("cat_group") ?: return@mapNotNull null
            id to group
        }.toMap(),
        payeeNames = db.fetchAll("SELECT id, name FROM payees WHERE tombstone = 0 OR tombstone IS NULL").mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val name = row.string("name") ?: return@mapNotNull null
            id to name
        }.toMap(),
    )
}

/**
 * The live payee with this name, case-insensitively — how a `payee_name`
 * action resolves to an id before we fall back to creating one.
 */
fun BudgetDatabase.payee(named: String): Payee? = dbQueue.read { db ->
    val row = db.fetchOne(
        "SELECT id, name, transfer_acct FROM payees WHERE (tombstone = 0 OR tombstone IS NULL) AND name = ? COLLATE NOCASE LIMIT 1",
        named,
    ) ?: return@read null
    val id = row.string("id") ?: return@read null
    Payee(id, row.string("name") ?: named, row.string("transfer_acct"))
}

fun BudgetDatabase.transferAccountId(payeeId: String?): String? {
    if (payeeId == null) return null
    return dbQueue.read { db ->
        db.string("SELECT transfer_acct FROM payees WHERE id = ? AND (tombstone = 0 OR tombstone IS NULL)", payeeId)
    }
}

fun BudgetDatabase.transferPayeeId(accountId: String): String? = dbQueue.read { db ->
    db.string("SELECT id FROM payees WHERE transfer_acct = ? AND (tombstone = 0 OR tombstone IS NULL) ORDER BY id LIMIT 1", accountId)
}

/** All live rules. Empty when the budget file has no `rules` table. */
fun BudgetDatabase.fetchRules(): List<Rule> = dbQueue.read(::liveRules)

/**
 * Live rules in the order the engine runs them — what the Rules screen shows,
 * matching upstream's `rules-get` (which returns `rankRules(...)`).
 */
suspend fun BudgetDatabase.fetchRulesRanked(): List<Rule> = dbQueue.readAsync { RuleRanker.rank(liveRules(it)) }

private fun liveRules(db: Db): List<Rule> {
    if (!db.tableExists("rules")) return emptyList()
    return db.fetchAll("SELECT id, stage, conditions_op, conditions, actions FROM rules WHERE tombstone = 0 OR tombstone IS NULL")
        .mapNotNull(::parseRule)
}

/**
 * Rule ids a schedule owns. Upstream refuses to delete these, and the list
 * badges them so it's clear why.
 */
fun BudgetDatabase.scheduleOwnedRuleIds(): Set<String> = dbQueue.read { db ->
    if (!db.tableExists("schedules")) return@read emptySet()
    db.strings("SELECT rule FROM schedules WHERE rule IS NOT NULL AND (tombstone = 0 OR tombstone IS NULL)").toSet()
}

// Schedules

/**
 * Schedules eligible for auto-posting: alive, not completed, and flagged
 * `posts_transaction = 1`, with their rule conditions extracted (port of
 * loot-core `extractScheduleConds`). The poster writes to users' real
 * servers, so doubt about WHAT to post (conditions, account, next date) is a
 * logged skip — never a throw. Doubt about the recurrence alone is
 * `Unsupported`, not a skip: upstream posts the stored due occurrence either
 * way and only its advance fails. Empty when the schedule tables don't exist.
 */
fun BudgetDatabase.fetchPostableSchedules(): List<Schedule> = dbQueue.read { schedules(it, postableOnly = true) }

/**
 * Schedules for the balance-forecast engine: same parsing as the poster, but
 * manual (posts_transaction = 0) schedules forecast too, matching upstream
 * forecast-schedules.ts.
 */
suspend fun BudgetDatabase.fetchForecastSchedules(): List<Schedule> = dbQueue.readAsync { schedules(it, postableOnly = false) }

private fun schedules(db: Db, postableOnly: Boolean): List<Schedule> {
    if (!db.tableExists("schedules") || !db.tableExists("schedules_next_date") || !db.tableExists("rules")) return emptyList()
    val closedAccounts = db.strings("SELECT id FROM accounts WHERE closed = 1").toSet()
    val rows = db.fetchAll(
        """
        SELECT s.id, s.name, nd.id AS nd_id,
               nd.local_next_date, nd.local_next_date_ts,
               nd.base_next_date, nd.base_next_date_ts,
               r.conditions, r.actions
        FROM schedules s
        JOIN schedules_next_date nd ON nd.schedule_id = s.id
        JOIN rules r ON r.id = s.rule
        WHERE (s.tombstone = 0 OR s.tombstone IS NULL)
          AND (s.completed = 0 OR s.completed IS NULL)
          AND (s.posts_transaction = 1 OR ${if (postableOnly) 0 else 1})
          AND (r.tombstone = 0 OR r.tombstone IS NULL)
        ORDER BY s.id, nd.id
        """,
    )

    // A duplicated schedules_next_date row (bad sync) would otherwise return
    // the same schedule twice and the poster could double-post. First row
    // wins, deterministically via ORDER BY nd.id above.
    val seenScheduleIds = HashSet<String>()
    return rows.mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        if (!seenScheduleIds.add(id)) {
            logger.notice("Skipping duplicate schedules_next_date row for schedule $id")
            return@mapNotNull null
        }
        val nextDateRowId = row.string("nd_id")
        if (nextDateRowId == null) {
            logger.notice("Skipping schedule $id: incomplete schedules_next_date row")
            return@mapNotNull null
        }
        // NULL is postable: the web's v_schedules CASE falls through to
        // base_next_date for NULL timestamps, and its advance writes
        // local_next_date_ts = NULL — so must ours.
        val baseNextDateTs = row.long("base_next_date_ts")

        val conditions = parseConditionsArray(row.string("conditions"))
        if (conditions == null) {
            logger.notice("Skipping schedule $id: unparseable rule conditions")
            return@mapNotNull null
        }

        // Account: required, and must be open.
        val accountId = firstCondition(conditions, setOf("is"), listOf("account", "acct"))?.get("value").stringOrNull
        if (accountId == null) {
            logger.notice("Skipping schedule $id: no account condition")
            return@mapNotNull null
        }
        if (accountId in closedAccounts) {
            logger.notice("Skipping schedule $id: account is closed")
            return@mapNotNull null
        }

        // Date: informs only the ADVANCE. Upstream posting works off the
        // stored next_date regardless of this condition, so a missing or
        // unparseable date condition still posts — once, without advancing.
        val dateCondition = parseDateCondition(conditions)
        if (dateCondition == ScheduleDateCondition.Unsupported) {
            logger.notice("Schedule $id: date condition missing or unsupported - due occurrence will post without advancing")
        }

        // Effective next date, per loot-core's v_schedules view: local when
        // local_next_date_ts = base_next_date_ts, else base (NULL timestamps
        // fall through to base, matching SQL NULL-comparison semantics).
        val localTs = row.long("local_next_date_ts")
        val effectiveRaw = if (localTs != null && localTs == baseNextDateTs) row.int("local_next_date") else row.int("base_next_date")
        val nextDate = effectiveRaw?.let(DayDate::fromYYYYMMDD)
        if (nextDate == null) {
            logger.notice("Skipping schedule $id: missing or invalid next date")
            return@mapNotNull null
        }

        // Payee: optional. loot-core's v_schedules resolves the raw condition
        // value through payee_mapping (LEFT JOIN), so an unmapped payee
        // yields null there too.
        val payeeId = firstCondition(conditions, setOf("is"), listOf("payee", "description"))?.get("value").stringOrNull
            ?.let { db.string("SELECT targetId FROM payee_mapping WHERE id = ?", it) }

        Schedule(
            id = id,
            name = row.string("name"),
            nextDate = nextDate,
            nextDateRowId = nextDateRowId,
            baseNextDateTs = baseNextDateTs,
            accountId = accountId,
            payeeId = payeeId,
            categoryId = parseCategoryAction(row.string("actions")),
            amount = parseAmountCondition(conditions, id),
            dateCondition = dateCondition,
            actions = ScheduleConditions.actions(row.string("actions")),
        )
    }
}

/**
 * Every live schedule, for the schedules screen — including completed and
 * manual ones. Unlike the poster's fetch, the rule and next-date joins are
 * LEFT joins: a schedule whose rule or next-date row went missing must still
 * appear so it can be fixed or deleted.
 */
suspend fun BudgetDatabase.fetchSchedules(): List<ScheduleSummary> = dbQueue.readAsync { db ->
    if (!db.tableExists("schedules") || !db.tableExists("schedules_next_date") || !db.tableExists("rules")) return@readAsync emptyList()
    val rows = db.fetchAll(
        """
        SELECT s.*,
               nd.id AS nd_id,
               nd.local_next_date, nd.local_next_date_ts,
               nd.base_next_date, nd.base_next_date_ts,
               r.id AS rule_id, r.conditions, r.actions
        FROM schedules s
        LEFT JOIN schedules_next_date nd ON nd.schedule_id = s.id
        LEFT JOIN rules r ON r.id = s.rule
            AND (r.tombstone = 0 OR r.tombstone IS NULL)
        WHERE (s.tombstone = 0 OR s.tombstone IS NULL)
        ORDER BY s.id, nd.id
        """,
    )
    // A duplicated schedules_next_date row would list the same schedule twice.
    val seen = HashSet<String>()
    rows.mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        if (!seen.add(id)) return@mapNotNull null

        val conditions = parseConditionsArray(row.string("conditions")).orEmpty()
        val actions = parseConditionsArray(row.string("actions")).orEmpty()
        val accountCond = firstCondition(conditions, setOf("is"), listOf("account", "acct"))
        val payeeCond = firstCondition(conditions, setOf("is"), listOf("payee", "description"))
        val amountCond = firstCondition(conditions, setOf("is", "isapprox", "isbetween"), listOf("amount"))
        val dateCond = firstCondition(conditions, setOf("is", "isapprox"), listOf("date"))

        val localTs = row.long("local_next_date_ts")
        val baseTs = row.long("base_next_date_ts")
        val effectiveRaw = if (localTs != null && localTs == baseTs) row.int("local_next_date") else row.int("base_next_date")

        // Payee ids resolve through payee_mapping, so a merged payee reads as
        // its surviving target — same as the v_schedules LEFT JOIN.
        val payeeId = payeeCond?.get("value").stringOrNull?.let { db.string("SELECT targetId FROM payee_mapping WHERE id = ?", it) }

        // "Custom" = the rule says more than the four conditions a schedule
        // owns, or does something other than link itself.
        val recognised = listOfNotNull(accountCond, payeeCond, amountCond, dateCond).size
        val isCustom = conditions.size > recognised || actions.any { it["op"].stringOrNull != "link-schedule" }

        ScheduleSummary(
            id = id,
            name = row.string("name"),
            ruleId = row.string("rule_id"),
            nextDate = effectiveRaw?.let(DayDate::fromYYYYMMDD),
            nextDateRowId = row.string("nd_id"),
            baseNextDateTs = baseTs,
            accountId = accountCond?.get("value").stringOrNull,
            payeeId = payeeId,
            amount = parseAmountCondition(conditions, id),
            amountOp = ScheduleAmountOp.fromRaw(amountCond?.get("op").stringOrNull) ?: ScheduleAmountOp.IsApprox,
            dateOp = dateCond?.get("op").stringOrNull,
            dateCondition = parseDateCondition(conditions),
            postsTransaction = row.long("posts_transaction") == 1L,
            completed = row.long("completed") == 1L,
            customUpcomingLength = row.string("custom_upcoming_length"),
            sortOrder = row.double("sort_order"),
            isCustom = isCustom,
            conditionsJSON = row.string("conditions"),
            actionsJSON = row.string("actions"),
            categoryId = parseCategoryAction(row.string("actions")),
        )
    }
}

/**
 * Schedules that already have a transaction covering their current occurrence
 * — the `paid` input to the status calculator. Port of loot-core
 * `getHasTransactionsQuery`, collapsed into one grouped query: each
 * schedule's own lower bound is applied against the latest linked
 * transaction date.
 */
suspend fun BudgetDatabase.fetchPaidScheduleIds(schedules: List<ScheduleSummary>, today: DayDate = DayDate.today()): Set<String> {
    val bounds = schedules.mapNotNull { schedule ->
        val nextDate = schedule.nextDate ?: return@mapNotNull null
        // A future occurrence must not absorb a late payment that still
        // belongs to the current one.
        val condition = schedule.dateCondition
        val frequency = if (nextDate <= today && condition is ScheduleDateCondition.Recurring) condition.config.frequency else null
        val start = ScheduleStatusCalculator.occurrenceMatchStartDate(nextDate, schedule.dateOp, schedule.postsTransaction, frequency)
        schedule.id to start.yyyymmdd
    }
    if (bounds.isEmpty()) return emptySet()
    return dbQueue.readAsync { db ->
        val latestDate = db.fetchAll(
            """
            SELECT schedule, MAX(date) AS max_date
            FROM transactions
            WHERE schedule IN (${placeholders(bounds.size)})
              AND (tombstone = 0 OR tombstone IS NULL)
            GROUP BY schedule
            """,
            bounds.map { it.first },
        ).mapNotNull { row ->
            val scheduleId = row.string("schedule") ?: return@mapNotNull null
            val maxDate = row.int("max_date") ?: return@mapNotNull null
            scheduleId to maxDate
        }.toMap()
        bounds.filter { (id, start) -> (latestDate[id] ?: Int.MIN_VALUE) >= start }.map { it.first }.toSet()
    }
}

/** Live transaction dates linked to each schedule, used to render past calendar occurrences. */
suspend fun BudgetDatabase.fetchSchedulePaymentDates(schedules: List<ScheduleSummary>): Map<String, Set<DayDate>> {
    val ids = schedules.map { it.id }
    if (ids.isEmpty()) return emptyMap()
    return dbQueue.readAsync { db ->
        val dates = HashMap<String, MutableSet<DayDate>>()
        for (row in db.fetchAll(
            "SELECT schedule, date FROM transactions WHERE schedule IN (${placeholders(ids.size)}) AND (tombstone = 0 OR tombstone IS NULL)",
            ids,
        )) {
            val scheduleId = row.string("schedule") ?: continue
            val date = row.int("date")?.let(DayDate::fromYYYYMMDD) ?: continue
            dates.getOrPut(scheduleId) { HashSet() } += date
        }
        dates
    }
}

/**
 * Is another live schedule already using this name? Mirrors loot-core
 * `checkIfScheduleExists`, which enforces unique names so the "link to
 * schedule" pickers stay unambiguous.
 */
fun BudgetDatabase.scheduleNameExists(name: String, excluding: String?): Boolean = dbQueue.read { db ->
    db.string(
        "SELECT id FROM schedules WHERE (tombstone = 0 OR tombstone IS NULL) AND name = ? AND (? IS NULL OR id <> ?) LIMIT 1",
        name, excluding, excluding,
    ) != null
}

/**
 * Refresh the local `schedules_json_paths` cache for one schedule. This table
 * is NOT synced — loot-core rebuilds it locally from a sync listener whenever
 * a rule changes. Nothing here reads it, but keeping the local file
 * self-consistent costs one statement.
 */
fun BudgetDatabase.writeScheduleJSONPaths(scheduleId: String, conditions: List<JsonObject>) = dbQueue.write { db ->
    if (!db.tableExists("schedules_json_paths")) return@write
    val paths = ScheduleConditions.jsonPaths(conditions)
    db.execute(
        "INSERT OR REPLACE INTO schedules_json_paths (schedule_id, payee, account, amount, date) VALUES (?, ?, ?, ?, ?)",
        scheduleId, paths.payee, paths.account, paths.amount, paths.date,
    )
}

/** Dedup guard for the poster: does an alive transaction linked to this schedule already exist on/after [date]? */
fun BudgetDatabase.hasTransaction(scheduleId: String, onOrAfter: Int): Boolean = dbQueue.read { db ->
    (db.long(
        "SELECT EXISTS(SELECT 1 FROM transactions WHERE schedule = ? AND date >= ? AND (tombstone = 0 OR tombstone IS NULL))",
        scheduleId, onOrAfter,
    ) ?: 0) != 0L
}

private fun parseConditionsArray(json: String?): List<JsonObject>? {
    if (json == null) return null
    val array = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
    if (array.any { it !is JsonObject }) return null
    return array.map { it as JsonObject }
}

/**
 * loot-core `extractScheduleConds` lookup: fields are tried in order (a
 * `payee` condition wins over an earlier `description` one), first array
 * match wins within a field.
 */
private fun firstCondition(conditions: List<JsonObject>, ops: Set<String>, fields: List<String>): JsonObject? {
    for (field in fields) {
        conditions.firstOrNull { it["op"].stringOrNull in ops && it["field"].stringOrNull == field }?.let { return it }
    }
    return null
}

/**
 * The linked rule's `set category` action, when present. Malformed actions
 * yield null — an uncategorized post, never a skipped schedule.
 */
private fun parseCategoryAction(json: String?): String? =
    parseConditionsArray(json)?.let { firstCondition(it, setOf("set"), listOf("category")) }?.get("value").stringOrNull

private fun parseAmountCondition(conditions: List<JsonObject>, scheduleId: String): ScheduledAmount? {
    val cond = firstCondition(conditions, setOf("is", "isapprox", "isbetween"), listOf("amount")) ?: return null
    val value = cond["value"]
    value.numberOrNull?.let { return ScheduledAmount.Fixed(it.toLong()) }
    val range = value as? JsonObject
    val num1 = range?.get("num1").numberOrNull
    val num2 = range?.get("num2").numberOrNull
    if (num1 != null && num2 != null) return ScheduledAmount.Range(num1.toLong(), num2.toLong())
    // "Amount condition present but malformed" — a surprise worth logging.
    logger.notice("Schedule $scheduleId: amount condition has unrecognized value shape, treating as no amount")
    return null
}

private fun parseDateCondition(conditions: List<JsonObject>): ScheduleDateCondition {
    val cond = firstCondition(conditions, setOf("is", "isapprox"), listOf("date")) ?: return ScheduleDateCondition.Unsupported
    // Fixed dates inside conditions JSON are "YYYY-MM-DD" strings (unlike the
    // schedules_next_date columns, which are YYYYMMDD ints).
    cond["value"].stringOrNull?.let(DayDate::fromIso)?.let { return ScheduleDateCondition.Fixed(it) }
    (cond["value"] as? JsonObject)?.let(RecurConfig::parse)?.let { return ScheduleDateCondition.Recurring(it) }
    return ScheduleDateCondition.Unsupported
}

/** Transactions linked to a schedule, newest first. Powers the editor's linked-transactions section. */
fun BudgetDatabase.fetchTransactions(scheduleId: String, limit: Int = 50): List<Transaction> = dbQueue.read { db ->
    db.fetchAll(
        """
        SELECT t.id, t.isParent, t.isChild, t.acct, t.category, t.amount,
               t.description, t.notes, t.date, t.imported_description,
               t.schedule, t.transferred_id, t.cleared, t.reconciled,
               t.sort_order, t.tombstone, t.parent_id,
               COALESCE(pa.name, p.name) AS payee_name,
               c.name AS category_name
        FROM transactions t
        LEFT JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        LEFT JOIN payees pa ON pa.id = t.description
        LEFT JOIN categories c ON c.id = t.category
        WHERE t.schedule = ?
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND t.date IS NOT NULL
          AND t.acct IS NOT NULL
        ORDER BY t.date DESC, t.sort_order DESC
        LIMIT ?
        """,
        scheduleId, limit,
    ).map(::mapTransaction)
}

/**
 * Set or clear the schedule link on transactions. Deliberately narrow rather
 * than adding `schedule` to `updateTransaction`: the transaction editor
 * rebuilds its row without carrying that column, so widening the shared
 * UPDATE would clear the link whenever a scheduled transaction is edited.
 */
fun BudgetDatabase.setTransactionSchedule(transactionIds: List<String>, scheduleId: String?) {
    if (transactionIds.isEmpty()) return
    dbQueue.write { db ->
        db.execute("UPDATE transactions SET schedule = ? WHERE id IN (${placeholders(transactionIds.size)})", listOf<Any?>(scheduleId) + transactionIds)
    }
}

/**
 * One account's transactions that are eligible to form a schedule. Mirrors
 * the filters in upstream's `getTransactions`: already-scheduled rows,
 * transfers and split children are excluded.
 */
fun BudgetDatabase.fetchDiscoveryTransactions(accountId: String, notBefore: Int): List<ScheduleDiscovery.Candidate> = dbQueue.read { db ->
    db.fetchAll(
        """
        SELECT t.id, t.date, t.amount, pm.targetId AS payee_id
        FROM transactions t
        JOIN payee_mapping pm ON pm.id = t.description
        LEFT JOIN payees p ON p.id = pm.targetId
        WHERE t.acct = ?
          AND t.date >= ?
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND t.schedule IS NULL
          AND (t.isChild = 0 OR t.isChild IS NULL)
          AND t.transferred_id IS NULL
          AND p.transfer_acct IS NULL
        ORDER BY t.date ASC
        """,
        accountId, notBefore,
    ).mapNotNull { row ->
        val id = row.string("id") ?: return@mapNotNull null
        val date = row.int("date")?.let(DayDate::fromYYYYMMDD) ?: return@mapNotNull null
        val payeeId = row.string("payee_id") ?: return@mapNotNull null
        val amount = row.long("amount") ?: return@mapNotNull null
        ScheduleDiscovery.Candidate(id, date, amount, payeeId, accountId)
    }
}

/** Latest transaction date on an account — the anchor every pattern sweep measures back from. */
fun BudgetDatabase.latestTransactionDate(accountId: String): DayDate? = dbQueue.read { db ->
    db.long(
        "SELECT date FROM transactions WHERE acct = ? AND (tombstone = 0 OR tombstone IS NULL) AND parent_id IS NULL ORDER BY date DESC LIMIT 1",
        accountId,
    )?.toInt()?.let(DayDate::fromYYYYMMDD)
}
