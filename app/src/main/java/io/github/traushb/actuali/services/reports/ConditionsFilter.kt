package io.github.traushb.actuali.services.reports

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.boolOrNull
import io.github.traushb.actuali.services.numberOrNull
import io.github.traushb.actuali.services.objectOrNull
import io.github.traushb.actuali.services.rules.RuleDateMatcher
import io.github.traushb.actuali.services.rules.TagFilter
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A condition value that is a JSON array of strings, or null when it's anything else. */
internal fun JsonElement?.conditionStrings(): List<String>? =
    (this as? JsonArray)?.map { it.stringOrNull ?: return null }

object ConditionsFilter {
    /**
     * Budget-level context some conditions need: `onBudget`/`offBudget` ops
     * and account-name matching can't be answered from the transaction alone.
     */
    data class Context(
        val offBudgetAccountIds: Set<String> = emptySet(),
        /** account id -> name */
        val accountNames: Map<String, String> = emptyMap(),
        /** category id -> group id */
        val categoryGroupIds: Map<String, String> = emptyMap(),
        /** group id -> name */
        val categoryGroupNames: Map<String, String> = emptyMap(),
    ) {
        companion object {
            val empty = Context()
        }
    }

    /**
     * Whether the transaction matches the condition set under the given
     * combinator. Empty/null conditions always match.
     *
     * Mirrors upstream `conditionsToAQL` (transaction-rules.ts). Conditions
     * with a `customName` are saved-filter references; upstream drops them
     * before building filters, so we skip them too.
     */
    fun matches(transaction: Transaction, conditions: List<WidgetRuleCondition>?, op: String?, context: Context = Context.empty): Boolean {
        val active = conditions.orEmpty().filter { it.customName == null }
        if (active.isEmpty()) return true
        return if ((op ?: "and").lowercase() == "or") {
            active.any { matches(transaction, it, context) }
        } else {
            active.all { matches(transaction, it, context) }
        }
    }

    // Single condition

    private enum class FieldType { Id, Str, Num, Date, Bool, Transfer, Parent }

    private fun fieldType(field: String): FieldType? = when (field) {
        "category", "category_group", "account", "payee", "description" -> FieldType.Id
        "notes", "imported_payee" -> FieldType.Str
        "amount", "amount-inflow", "amount-outflow" -> FieldType.Num
        "date" -> FieldType.Date
        "cleared", "reconciled" -> FieldType.Bool
        "transfer" -> FieldType.Transfer
        "parent" -> FieldType.Parent
        else -> null
    }

    private fun matches(tx: Transaction, c: WidgetRuleCondition, context: Context): Boolean =
        // Unknown fields behave like upstream's invalid conditions, which are
        // dropped from the filter set: pass everything through.
        when (fieldType(c.field)) {
            null -> true
            FieldType.Num -> matchNumber(tx, c)
            FieldType.Date -> matchDate(tx, c)
            FieldType.Id -> matchId(tx, c, context)
            FieldType.Str -> matchString(tx, c)
            FieldType.Bool -> matchBool(if (c.field == "cleared") tx.cleared else tx.reconciled, c)
            FieldType.Transfer -> matchBool(tx.transferId != null, c)
            FieldType.Parent -> matchBool(tx.isParent, c)
        }

    private fun matchBool(txValue: Boolean, c: WidgetRuleCondition): Boolean {
        val want = c.value.boolOrNull
        if (c.op != "is" || want == null) return true
        return txValue == want
    }

    // Amount conditions

    private fun matchNumber(tx: Transaction, c: WidgetRuleCondition): Boolean {
        val options = c.options.objectOrNull
        // Legacy serialized field names carry the direction in the field itself.
        val inflow = c.field == "amount-inflow" || options?.get("inflow").boolOrNull == true
        val outflow = c.field == "amount-outflow" || options?.get("outflow").boolOrNull == true

        // "isbetween" ignores inflow/outflow and compares the raw amount,
        // matching upstream (it bypasses the option-aware `apply` helper).
        if (c.op == "isbetween") {
            val range = c.value.objectOrNull
            val num1 = range?.get("num1").numberOrNull
            val num2 = range?.get("num2").numberOrNull
            if (num1 == null || num2 == null) return true
            val amount = tx.amount.toDouble()
            return amount >= min(num1, num2) && amount <= max(num1, num2)
        }

        val value = c.value.numberOrNull ?: return true

        // Directional filters gate on sign and compare the magnitude: outflow
        // negates the (negative) amount so the user-entered positive value
        // lines up.
        fun apply(cmp: (Double, Double) -> Boolean): Boolean = when {
            outflow -> tx.amount < 0 && cmp((-tx.amount).toDouble(), value)
            inflow -> tx.amount > 0 && cmp(tx.amount.toDouble(), value)
            else -> cmp(tx.amount.toDouble(), value)
        }

        return when (c.op) {
            "is" -> apply { a, b -> a == b }
            "isapprox" -> {
                val threshold = io.github.traushb.actuali.models.roundHalfAwayFromZero(abs(value) * 0.075).toDouble()
                apply { magnitude, _ -> magnitude >= value - threshold && magnitude <= value + threshold }
            }
            "gt" -> apply { a, b -> a > b }
            "gte" -> apply { a, b -> a >= b }
            "lt" -> apply { a, b -> a < b }
            "lte" -> apply { a, b -> a <= b }
            else -> true
        }
    }

    // Date conditions

    /**
     * Condition dates are strings: "yyyy-MM-dd", "yyyy-MM", or "yyyy".
     * Transactions store dates as YYYYMMDD ints. Recurring-date conditions
     * (objects with a frequency) are not supported and pass through. An
     * unevaluable date condition is a dropped condition here, matching
     * upstream's filter builder — the report shows everything else.
     */
    private fun matchDate(tx: Transaction, c: WidgetRuleCondition): Boolean {
        val value = c.value.stringOrNull ?: return true
        return RuleDateMatcher.matches(tx.date, c.op, value) ?: true
    }

    // Id conditions (category / account / payee)

    private fun matchId(tx: Transaction, c: WidgetRuleCondition, context: Context): Boolean {
        val txId = when (c.field) {
            "category" -> tx.categoryId
            // Upstream rewrites category_group to category.group (transaction-rules.ts).
            "category_group" -> tx.categoryId?.let { context.categoryGroupIds[it] }
            "account" -> tx.accountId
            else -> tx.payeeId // "payee" / legacy "description"
        }

        return when (c.op) {
            "is" -> {
                val condValue = c.value.stringOrNull
                if (condValue.isNullOrEmpty()) {
                    // "is nothing" matches missing values. For category,
                    // upstream additionally excludes transfers and split
                    // parents (conditionSpecialCases) so transfer legs don't
                    // count as "uncategorized".
                    val isEmpty = txId.isNullOrEmpty()
                    if (c.field == "category") isEmpty && tx.transferId == null && !tx.isParent else isEmpty
                } else {
                    txId == condValue
                }
            }
            "isNot" -> {
                val condValue = c.value.stringOrNull
                // SQL `!=` lets NULL rows through (`x != v OR x IS NULL`).
                if (condValue.isNullOrEmpty()) !txId.isNullOrEmpty() else txId != condValue
            }
            "oneOf" -> {
                val list = stringArray(c.value)
                !list.isNullOrEmpty() && !txId.isNullOrEmpty() && txId in list
            }
            "notOneOf" -> {
                val list = stringArray(c.value)
                when {
                    list.isNullOrEmpty() -> false
                    txId.isNullOrEmpty() -> true // NULL passes $ne
                    else -> txId !in list
                }
            }
            "contains", "doesNotContain", "matches" -> {
                // Id fields match against the referenced row's *name*.
                val name = when (c.field) {
                    "category" -> tx.categoryName
                    "category_group" -> txId?.let { context.categoryGroupNames[it] }
                    "account" -> context.accountNames[tx.accountId]
                    else -> tx.payeeName
                }
                matchText(name, c.op, c.value.stringOrNull)
            }
            "onBudget" -> tx.accountId !in context.offBudgetAccountIds
            "offBudget" -> tx.accountId in context.offBudgetAccountIds
            else -> true
        }
    }

    // String conditions (notes / imported_payee)

    private fun matchString(tx: Transaction, c: WidgetRuleCondition): Boolean {
        val txValue = if (c.field == "notes") tx.notes else tx.importedPayee

        return when (c.op) {
            "is" -> {
                val condValue = c.value.stringOrNull
                if (condValue.isNullOrEmpty()) txValue.isNullOrEmpty() else txValue?.lowercase() == condValue.lowercase()
            }
            "isNot" -> {
                val condValue = c.value.stringOrNull
                if (condValue.isNullOrEmpty()) !txValue.isNullOrEmpty() else txValue?.lowercase() != condValue.lowercase()
            }
            "oneOf" -> {
                val list = stringArray(c.value)
                !list.isNullOrEmpty() && txValue != null && txValue.lowercase() in list.map { it.lowercase() }
            }
            "notOneOf" -> {
                val list = stringArray(c.value)
                when {
                    list.isNullOrEmpty() -> false
                    txValue == null -> true
                    else -> txValue.lowercase() !in list.map { it.lowercase() }
                }
            }
            "contains", "doesNotContain", "matches" -> matchText(txValue, c.op, c.value.stringOrNull)
            "hasTags", "hasAnyTag" -> {
                val tags = TagFilter.extractTags(c.value.stringOrNull ?: "")
                if (tags.isEmpty() || txValue == null) return false
                val hit = { tag: String -> TagFilter.notesContainTag(txValue, tag, caseSensitive = true) }
                if (c.op == "hasTags") tags.all(hit) else tags.any(hit)
            }
            else -> true
        }
    }

    /**
     * contains / doesNotContain / matches share NULL semantics with SQL:
     * `NOT LIKE` and negated regex let NULL rows through.
     */
    private fun matchText(haystack: String?, op: String, value: String?): Boolean {
        if (value.isNullOrEmpty()) return true
        return when (op) {
            "contains" -> haystack != null && haystack.contains(value, ignoreCase = true)
            "doesNotContain" -> haystack == null || !haystack.contains(value, ignoreCase = true)
            "matches" -> {
                if (haystack == null) return false
                // Upstream would fail the whole query on a bad regex; be
                // permissive instead of blanking the widget.
                val regex = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.getOrNull() ?: return true
                regex.containsMatchIn(haystack)
            }
            else -> true
        }
    }

    /** Strings, or integers read as their decimal strings. */
    private fun stringArray(value: JsonElement?): List<String>? =
        value.conditionStrings()
            ?: (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.toString() ?: return null }
}
