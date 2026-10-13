package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.models.RuleValue
import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

/**
 * Field metadata for rules, ported from loot-core `shared/rules.ts` (FIELD_INFO,
 * TYPE_INFO, isValidOp) plus the label maps in `desktop-client/src/util/rule.ts`.
 * One place so the engine, the validator and the editor can't drift apart.
 */
enum class RuleFieldType(val rawValue: String) {
    Id("id"),
    Str("string"),
    Num("number"),
    Date("date"),
    Bool("boolean"),
}

object RuleSchema {
    /**
     * Public field name -> type. `saved` (saved-filter references) is
     * deliberately absent: upstream drops those conditions before evaluating.
     */
    val fieldTypes: Map<String, RuleFieldType> = mapOf(
        "imported_payee" to RuleFieldType.Str,
        "payee" to RuleFieldType.Id,
        "payee_name" to RuleFieldType.Str,
        "date" to RuleFieldType.Date,
        "notes" to RuleFieldType.Str,
        "amount" to RuleFieldType.Num,
        "category" to RuleFieldType.Id,
        "category_group" to RuleFieldType.Id,
        "account" to RuleFieldType.Id,
        "cleared" to RuleFieldType.Bool,
        "reconciled" to RuleFieldType.Bool,
        "transfer" to RuleFieldType.Bool,
        "parent" to RuleFieldType.Bool,
    )

    private val opsByType: Map<RuleFieldType, List<String>> = mapOf(
        RuleFieldType.Date to listOf("is", "isapprox", "gt", "gte", "lt", "lte"),
        RuleFieldType.Id to listOf(
            "is", "isNot", "oneOf", "notOneOf", "contains", "doesNotContain",
            "matches", "onBudget", "offBudget",
        ),
        RuleFieldType.Str to listOf(
            "is", "isNot", "oneOf", "notOneOf", "contains", "doesNotContain",
            "matches", "hasTags", "hasAnyTag",
        ),
        RuleFieldType.Num to listOf("is", "isapprox", "isbetween", "gt", "gte", "lt", "lte"),
        RuleFieldType.Bool to listOf("is"),
    )

    private val disallowedOps: Map<String, Set<String>> = mapOf(
        "imported_payee" to setOf("hasTags", "hasAnyTag"),
        "payee" to setOf("onBudget", "offBudget"),
        "category" to setOf("onBudget", "offBudget"),
        "category_group" to setOf("onBudget", "offBudget"),
        "notes" to setOf("oneOf", "notOneOf"),
    )

    fun fieldType(field: String): RuleFieldType? = fieldTypes[field]

    /** Ops the editor offers for [field], in upstream's declaration order. */
    fun validOps(field: String): List<String> {
        val type = fieldTypes[field] ?: return emptyList()
        val disallowed = disallowedOps[field].orEmpty()
        return opsByType[type].orEmpty().filter { it !in disallowed }
    }

    fun isValidOp(field: String, op: String): Boolean = op in validOps(field)

    // Editor field lists (upstream RuleEditor.tsx)

    val conditionFields = listOf(
        "imported_payee", "account", "category", "category_group",
        "date", "payee", "notes", "amount",
    )

    val actionFields = listOf("category", "payee", "payee_name", "notes", "cleared", "account", "date", "amount")

    /** Mirrors `schemaConfig.views.transactions.fields` in loot-core. */
    private val internalToPublic: Map<String, String> = mapOf(
        "isParent" to "is_parent",
        "isChild" to "is_child",
        "acct" to "account",
        "financial_id" to "imported_id",
        "imported_description" to "imported_payee",
        "transferred_id" to "transfer_id",
        "description" to "payee",
    )

    private val publicToInternal: Map<String, String> = internalToPublic.entries.associate { it.value to it.key }

    fun publicField(internalField: String): String = internalToPublic[internalField] ?: internalField

    fun internalField(publicField: String): String = publicToInternal[publicField] ?: publicField

    // Labels (util/rule.ts mapField / friendlyOp)

    fun label(field: String, options: Map<String, RuleValue>? = null, locale: Locale = Locale.getDefault()): String =
        when (field) {
            "imported_payee" -> ReportStrings.text("rule.field.importedPayee", locale)
            "payee_name" -> ReportStrings.text("rule.field.payeeName", locale)
            "amount" -> when {
                options?.get("inflow")?.boolValue == true -> ReportStrings.text("rule.field.amountInflow", locale)
                options?.get("outflow")?.boolValue == true -> ReportStrings.text("rule.field.amountOutflow", locale)
                else -> ReportStrings.text("rule.field.amount", locale)
            }
            "category_group" -> ReportStrings.text("rule.field.categoryGroup", locale)
            "payee" -> ReportStrings.text("rule.field.payee", locale)
            "category" -> ReportStrings.text("rule.field.category", locale)
            "account" -> ReportStrings.text("rule.field.account", locale)
            "date" -> ReportStrings.text("rule.field.date", locale)
            "notes" -> ReportStrings.text("rule.field.notes", locale)
            "cleared" -> ReportStrings.text("rule.field.cleared", locale)
            "reconciled" -> ReportStrings.text("rule.field.reconciled", locale)
            "transfer" -> ReportStrings.text("rule.field.transfer", locale)
            "parent" -> ReportStrings.text("rule.field.parent", locale)
            else -> field
        }

    fun summaryLabel(field: String, options: Map<String, RuleValue>? = null, locale: Locale = Locale.getDefault()): String =
        when (field) {
            "payee" -> ReportStrings.text("rule.summary.field.payee", locale)
            "category" -> ReportStrings.text("rule.summary.field.category", locale)
            "account" -> ReportStrings.text("rule.summary.field.account", locale)
            "date" -> ReportStrings.text("rule.summary.field.date", locale)
            "notes" -> ReportStrings.text("rule.summary.field.notes", locale)
            "cleared" -> ReportStrings.text("rule.summary.field.cleared", locale)
            "reconciled" -> ReportStrings.text("rule.summary.field.reconciled", locale)
            "transfer" -> ReportStrings.text("rule.summary.field.transfer", locale)
            "parent" -> ReportStrings.text("rule.summary.field.parent", locale)
            else -> label(field, options, locale)
        }

    fun opLabel(op: String, type: RuleFieldType? = null, locale: Locale = Locale.getDefault()): String {
        val key = when (op) {
            "is" -> "rule.op.is"
            "isNot" -> "rule.op.isNot"
            "oneOf" -> "rule.op.oneOf"
            "notOneOf" -> "rule.op.notOneOf"
            "isapprox" -> "rule.op.isApprox"
            "isbetween" -> "rule.op.isBetween"
            "contains" -> "rule.op.contains"
            "doesNotContain" -> "rule.op.doesNotContain"
            "matches" -> "rule.op.matches"
            "hasTags" -> "rule.op.hasAllTags"
            "hasAnyTag" -> "rule.op.hasAnyTag"
            "onBudget" -> "rule.op.isOnBudget"
            "offBudget" -> "rule.op.isOffBudget"
            "gt" -> if (type == RuleFieldType.Date) "rule.op.isAfter" else "rule.op.isGreaterThan"
            "gte" -> if (type == RuleFieldType.Date) "rule.op.isAfterOrEquals" else "rule.op.isGreaterThanOrEquals"
            "lt" -> if (type == RuleFieldType.Date) "rule.op.isBefore" else "rule.op.isLessThan"
            "lte" -> if (type == RuleFieldType.Date) "rule.op.isBeforeOrEquals" else "rule.op.isLessThanOrEquals"
            "set" -> "rule.op.set"
            "set-split-amount" -> "rule.op.allocate"
            "link-schedule" -> "rule.op.linkSchedule"
            "prepend-notes" -> "rule.op.prependNotes"
            "append-notes" -> "rule.op.appendNotes"
            "delete-transaction" -> "rule.op.deleteTransaction"
            else -> return op
        }
        return ReportStrings.text(key, locale)
    }

    fun sentenceCased(text: String, locale: Locale): String =
        text.take(1).uppercase(locale) + text.drop(1)
}
