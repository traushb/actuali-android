package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.RuleValue
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val logger = Logger("RulesEngine")

/**
 * Budget-level context for conditions that can't be answered from the
 * transaction row alone. Mirrors what upstream's `prepareTransactionForRules`
 * hangs off the transaction before the rules run.
 */
data class RuleContext(
    val offBudgetAccountIds: Set<String> = emptySet(),
    /** Category id -> group id. */
    val categoryGroupIds: Map<String, String> = emptyMap(),
    /** Payee id -> name. */
    val payeeNames: Map<String, String> = emptyMap(),
) {
    companion object {
        val empty = RuleContext()
    }
}

/** What a rules pass produced. */
data class RuleRunResult(
    val transaction: Transaction,
    /**
     * Public rule field names the rules changed ("payee", "category", …), for
     * logging and for deciding whether a write is needed at all. The CRDT
     * column set for an update still comes from `BudgetStore.changedFields`,
     * which speaks the internal column names.
     */
    val changedFields: Set<String>,
    /**
     * Set when an action assigned `payee_name`: the caller resolves it to a
     * payee id, creating the payee if necessary, like upstream's
     * `resolvePayeeNameForRules`.
     */
    val pendingPayeeName: String?,
    /** A `delete-transaction` action fired. */
    val isDeleted: Boolean = false,
)

/**
 * Evaluates Actual Budget rules against a Transaction.
 * Port of loot-core `runRules` (server/transactions/transaction-rules.ts) with
 * the condition/action semantics of `server/rules/{condition,action}.ts`.
 *
 * Not supported (logged, treated as a no-op): `set-split-amount`, Handlebars
 * templates and formula actions, and recurring-date conditions.
 */
object RulesEngine {
    fun apply(transaction: Transaction, rules: List<Rule>, context: RuleContext = RuleContext.empty): RuleRunResult {
        val bag = TransactionBag(transaction, context)
        val original = bag.snapshot()

        for (rule in RuleRanker.rank(rules)) {
            if (!evalConditions(rule, bag)) continue
            for (action in rule.actions) apply(action, bag, rule.id)
        }

        val changed = bag.changedFields(original)
        if (changed.isNotEmpty()) logger.info("Rules applied: changed fields ${changed.sorted().joinToString(", ")}")

        return RuleRunResult(
            transaction = bag.toTransaction(transaction),
            changedFields = changed,
            pendingPayeeName = bag.pendingPayeeName,
            isDeleted = bag.isDeleted,
        )
    }

    /**
     * Apply a schedule rule's actions directly. The schedule poster already
     * selected a due schedule, so its recurring date condition cannot be
     * re-evaluated here; the actions still need to shape the posted row.
     */
    fun apply(actions: List<Rule.Action>, to: Transaction, ruleId: String): RuleRunResult {
        val bag = TransactionBag(to)
        val original = bag.snapshot()
        for (action in actions) apply(action, bag, ruleId)
        val changed = bag.changedFields(original)
        if (changed.isNotEmpty()) logger.info("Schedule actions applied: changed fields ${changed.sorted().joinToString(", ")}")
        return RuleRunResult(
            transaction = bag.toTransaction(to),
            changedFields = changed,
            pendingPayeeName = bag.pendingPayeeName,
            isDeleted = bag.isDeleted,
        )
    }

    // Condition evaluation

    private fun evalConditions(rule: Rule, bag: TransactionBag): Boolean {
        if (rule.conditions.isEmpty()) return false
        return when (rule.conditionsOp) {
            Rule.ConditionsOp.And -> rule.conditions.all { eval(it, bag) }
            Rule.ConditionsOp.Or -> rule.conditions.any { eval(it, bag) }
        }
    }

    private fun eval(condition: Rule.Condition, bag: TransactionBag): Boolean {
        val type = RuleSchema.fieldType(condition.field)

        // onBudget/offBudget read the account, not the field value.
        when (condition.op) {
            "onBudget" -> return bag.isOnBudget == true
            "offBudget" -> return bag.isOnBudget == false
        }

        return when (type) {
            RuleFieldType.Date -> {
                val date = bag.date ?: return false
                val value = condition.value.stringValue ?: return false
                RuleDateMatcher.matches(date, condition.op, value) ?: false
            }
            RuleFieldType.Num -> evalNumber(condition, bag)
            RuleFieldType.Bool -> {
                if (condition.op != "is") return false
                val expected = condition.value.boolValue ?: return false
                val actual = bag.bool(condition.field) ?: return false
                actual == expected
            }
            RuleFieldType.Str, RuleFieldType.Id, null -> evalText(condition, bag, type)
        }
    }

    private fun evalNumber(condition: Rule.Condition, bag: TransactionBag): Boolean {
        // Upstream applies inflow/outflow before the switch, so it gates every
        // numeric op, `isbetween` included.
        var amount = bag.number(condition.field)?.toDouble() ?: return false
        if (condition.options?.get("outflow")?.boolValue == true) {
            if (amount > 0) return false
            amount = -amount
        } else if (condition.options?.get("inflow")?.boolValue == true) {
            if (amount < 0) return false
        }

        if (condition.op == "isbetween") {
            val (num1, num2) = condition.value.betweenValue ?: return false
            return amount >= min(num1, num2) && amount <= max(num1, num2)
        }

        val target = condition.value.numberValue ?: return false
        return when (condition.op) {
            "is" -> amount == target
            "isapprox" -> {
                // getApproxNumberThreshold: Math.round(|n| * 0.075).
                val threshold = roundHalfAwayFromZero(abs(target) * 0.075).toDouble()
                amount >= target - threshold && amount <= target + threshold
            }
            "gt" -> amount > target
            "gte" -> amount >= target
            "lt" -> amount < target
            "lte" -> amount <= target
            else -> false
        }
    }

    private fun evalText(condition: Rule.Condition, bag: TransactionBag, type: RuleFieldType?): Boolean {
        // Upstream coerces a missing string field to "" before comparing; id
        // fields keep their null so `isNot` still matches an empty payee.
        var fieldValue = bag.string(condition.field)
        if (type == RuleFieldType.Str) fieldValue = fieldValue ?: ""

        return when (condition.op) {
            "is" -> {
                val condValue = condition.value.stringValue ?: return fieldValue == null
                fieldValue?.lowercase() == condValue.lowercase()
            }
            "isNot" -> {
                val condValue = condition.value.stringValue ?: return fieldValue != null
                fieldValue?.lowercase() != condValue.lowercase()
            }
            "contains" -> {
                val condValue = condition.value.stringValue ?: return false
                fieldValue?.lowercase()?.contains(condValue.lowercase()) ?: false
            }
            "doesNotContain" -> {
                val condValue = condition.value.stringValue ?: return false
                fieldValue?.let { !it.lowercase().contains(condValue.lowercase()) } ?: false
            }
            "oneOf", "notOneOf" -> {
                if (fieldValue == null) return false
                val list = condition.value.listValue ?: return false
                val hit = list.mapNotNull { it.stringValue }.any { it.lowercase() == fieldValue.lowercase() }
                if (condition.op == "oneOf") hit else !hit
            }
            "matches" -> {
                if (fieldValue == null) return false
                val pattern = condition.value.stringValue ?: return false
                // Both sides lowercased, not a case-insensitive match: upstream
                // lowercases the pattern in condition.ts's string parse and the
                // field value at the top of eval. Copying both is what makes a
                // rule written on the web behave identically here — including the
                // quirk that `\D` becomes `\d` and inverts its meaning.
                val regex = runCatching { Regex(pattern.lowercase()) }.getOrElse {
                    logger.debug("invalid regexp in matches condition")
                    return false
                }
                regex.containsMatchIn(fieldValue.lowercase())
            }
            "hasTags", "hasAnyTag" -> {
                if (fieldValue == null) return false
                val condValue = condition.value.stringValue ?: return false
                val tags = TagFilter.extractTags(condValue)
                val hit = { tag: String -> TagFilter.notesContainTag(fieldValue, tag, caseSensitive = false) }
                if (condition.op == "hasTags") tags.all(hit) else tags.any(hit)
            }
            else -> {
                logger.debug("Unknown condition op '${condition.op}'")
                false
            }
        }
    }

    // Action execution

    private fun apply(action: Rule.Action, bag: TransactionBag, ruleId: String) {
        when (action.op) {
            "set" -> {
                val field = action.field ?: return
                if (action.options?.get("template") != null || action.options?.get("formula") != null) {
                    logger.notice("Rule $ruleId: skipping set on '$field' — template/formula actions aren't supported")
                    return
                }
                if (action.options?.get("splitIndex")?.numberValue?.let { it > 0 } == true) {
                    logger.notice("Rule $ruleId: skipping split action on '$field'")
                    return
                }
                bag.set(field, action.value)
            }
            "prepend-notes" -> {
                val value = action.value.stringValue ?: return
                val existing = bag.string("notes").orEmpty()
                bag.set("notes", RuleValue.Str(if (existing.isEmpty()) value else value + existing))
            }
            "append-notes" -> {
                val value = action.value.stringValue ?: return
                val existing = bag.string("notes").orEmpty()
                bag.set("notes", RuleValue.Str(if (existing.isEmpty()) value else existing + value))
            }
            "link-schedule" -> {
                val value = action.value.stringValue ?: return
                bag.set("schedule", RuleValue.Str(value))
            }
            "delete-transaction" -> bag.isDeleted = true
            "set-split-amount" -> logger.notice("Rule $ruleId: skipping unsupported action op 'set-split-amount'")
            else -> logger.notice("Rule $ruleId: unknown action op '${action.op}'")
        }
    }
}

/** Mutable key-value view of a Transaction keyed by *public* rule field names. */
class TransactionBag(transaction: Transaction, context: RuleContext = RuleContext.empty) {
    private val strings = HashMap<String, String?>()
    private val numbers = HashMap<String, Long?>()
    private val bools = HashMap<String, Boolean>()

    /** Null when the transaction's account isn't known to the context. */
    val isOnBudget: Boolean?

    /**
     * Set when a rule assigned `payee_name`; upstream parks `payee = 'new'` and
     * resolves the name to an id after every rule has run.
     */
    var pendingPayeeName: String? = null
        private set
    var isDeleted = false

    init {
        strings["account"] = transaction.accountId
        strings["payee"] = transaction.payeeId
        strings["payee_name"] = transaction.payeeId?.let { context.payeeNames[it] } ?: transaction.payeeName
        strings["category"] = transaction.categoryId
        strings["category_group"] = transaction.categoryId?.let { context.categoryGroupIds[it] }
        strings["notes"] = transaction.notes
        strings["imported_payee"] = transaction.importedPayee
        strings["transfer_id"] = transaction.transferId
        strings["parent_id"] = transaction.parentId
        strings["schedule"] = transaction.schedule
        numbers["date"] = transaction.date.toLong()
        numbers["amount"] = transaction.amount
        // `cleared` and `reconciled` only. `transfer` and `parent` are absent on
        // purpose: they are not keys of upstream's prepared transaction either,
        // so `Condition.eval` hits `fieldValue === undefined` and returns false
        // for them. Only the query path (`conditionsToAQL`, mirrored by
        // ConditionsFilter) maps them to real columns.
        bools["cleared"] = transaction.cleared
        bools["reconciled"] = transaction.reconciled
        // Upstream reads `_account.offbudget`, and an unknown account means the
        // condition can't match either way.
        isOnBudget = if (transaction.accountId.isEmpty()) null else transaction.accountId !in context.offBudgetAccountIds
    }

    val date: Int? get() = numbers["date"]?.toInt()

    fun string(field: String): String? = strings[field]

    fun number(field: String): Long? = numbers[field]

    fun bool(field: String): Boolean? = bools[field]

    fun set(field: String, value: RuleValue) {
        when (RuleSchema.fieldType(field)) {
            RuleFieldType.Num -> {
                // Garbage in a rule (a non-finite or out-of-range amount) is not a
                // change: skip the write so the original amount survives and
                // `changedFields` doesn't claim an edit that didn't happen.
                val number = value.numberValue
                if (number != null && number.isFinite() && abs(number) < 9.2e18) {
                    numbers[field] = roundHalfAwayFromZero(number)
                }
            }
            RuleFieldType.Bool -> value.boolValue?.let { bools[field] = it }
            RuleFieldType.Date -> {
                // A `set date` action carries "yyyy-MM-dd"; the column is YYYYMMDD.
                val day = value.stringValue?.replace("-", "")?.toIntOrNull()
                if (day != null && day > 9_999_999) numbers["date"] = day.toLong()
            }
            else -> {
                strings[field] = value.stringValue
                if (field == "payee_name") {
                    // Upstream: setting payee_name parks payee = 'new'.
                    pendingPayeeName = value.stringValue
                    strings["payee"] = null
                }
                if (field == "payee") pendingPayeeName = null
            }
        }
    }

    fun snapshot(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((key, value) in strings) out[key] = value?.let { "s:$it" } ?: "nil"
        for ((key, value) in numbers) out[key] = value?.let { "i:$it" } ?: "nil"
        for ((key, value) in bools) out[key] = "b:$value"
        return out
    }

    fun changedFields(prior: Map<String, String>): Set<String> =
        snapshot().filter { (key, value) -> prior[key] != value }.keys

    fun toTransaction(base: Transaction): Transaction = base.copy(
        accountId = string("account") ?: base.accountId,
        date = date ?: base.date,
        amount = number("amount") ?: base.amount,
        payeeId = string("payee"),
        categoryId = string("category"),
        notes = string("notes"),
        importedPayee = string("imported_payee"),
        transferId = string("transfer_id"),
        parentId = string("parent_id"),
        schedule = string("schedule"),
        cleared = bool("cleared") ?: base.cleared,
        reconciled = bool("reconciled") ?: base.reconciled,
        tombstone = isDeleted || base.tombstone,
    )
}
