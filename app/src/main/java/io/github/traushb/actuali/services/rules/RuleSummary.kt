package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.RuleValue
import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

/**
 * Human-readable rule text, ported from `desktop-client/src/components/rules/
 * {Condition,Action}Expression.tsx` and `ManageRules.tsx`'s `ruleToString`
 * (which is also what the web's rule search matches against).
 */
class RuleSummary(
    val names: Names,
    /** Formats cents the way the rest of the app does (`BudgetStore.formatCurrency`). */
    val formatAmount: (Long, Locale) -> String,
) {
    /** Names for the ids a rule references, so the summary reads "Groceries" rather than a UUID. */
    data class Names(
        val payees: Map<String, String> = emptyMap(),
        val categories: Map<String, String> = emptyMap(),
        val categoryGroups: Map<String, String> = emptyMap(),
        val accounts: Map<String, String> = emptyMap(),
    ) {
        companion object {
            val empty = Names()
        }
    }

    fun condition(condition: Rule.Condition, locale: Locale = Locale.getDefault()): String {
        val field = RuleSchema.summaryLabel(condition.field, condition.options, locale)
        val op = RuleSchema.opLabel(condition.op, RuleSchema.fieldType(condition.field), locale)
        return when (condition.op) {
            "onBudget", "offBudget" -> ReportStrings.format("%@ %@", field, op, locale = locale)
            else -> ReportStrings.format("%@ %@ %@", field, op, value(condition.value, condition.field, locale), locale = locale)
        }
    }

    fun action(action: Rule.Action, locale: Locale = Locale.getDefault()): String = when (action.op) {
        "set" -> {
            val field = action.field
            if (field == null) {
                RuleSchema.opLabel(action.op, locale = locale)
            } else {
                val fieldLabel = RuleSchema.summaryLabel(field, locale = locale)
                val template = action.options?.get("template")?.stringValue
                val formula = action.options?.get("formula")?.stringValue
                when {
                    template != null -> ReportStrings.format("set %@ to template %@", fieldLabel, template, locale = locale)
                    formula != null -> ReportStrings.format("set %@ to formula %@", fieldLabel, formula, locale = locale)
                    else -> ReportStrings.format("set %@ to %@", fieldLabel, value(action.value, field, locale), locale = locale)
                }
            }
        }
        "prepend-notes", "append-notes" -> ReportStrings.format(
            "%@ %@", RuleSchema.opLabel(action.op, locale = locale), action.value.stringValue.orEmpty(), locale = locale,
        )
        "link-schedule" -> ReportStrings.text("link schedule", locale)
        "delete-transaction" -> ReportStrings.text("delete transaction", locale)
        "set-split-amount" -> ReportStrings.text("allocate a split amount", locale)
        else -> RuleSchema.opLabel(action.op, locale = locale)
    }

    /** Everything a rule says, in one line — the string the search box filters on. */
    fun searchText(rule: Rule, locale: Locale = Locale.getDefault()): String =
        (rule.conditions.map { condition(it, locale) } + rule.actions.map { action(it, locale) })
            .joinToString(" ")
            .lowercase(locale)

    private fun value(value: RuleValue, field: String, locale: Locale): String = when (value) {
        RuleValue.Null -> ReportStrings.text("nothing", locale)
        is RuleValue.Bool -> ReportStrings.text(if (value.value) "yes" else "no", locale)
        is RuleValue.ListValue -> {
            val rendered = value.items.map { value(it, field, locale) }
            if (rendered.isEmpty()) ReportStrings.text("nothing", locale) else rendered.joinToString(", ")
        }
        is RuleValue.Obj -> value.betweenValue?.let { (num1, num2) ->
            ReportStrings.format(
                "%@ and %@", formatAmount(num1.toLong(), locale), formatAmount(num2.toLong(), locale), locale = locale,
            )
        } ?: ""
        is RuleValue.Num ->
            if (RuleSchema.fieldType(field) != RuleFieldType.Num) value.value.toLong().toString()
            else formatAmount(value.value.toLong(), locale)
        is RuleValue.Str -> when (field) {
            "payee" -> names.payees[value.value] ?: value.value
            "category" -> names.categories[value.value] ?: value.value
            "category_group" -> names.categoryGroups[value.value] ?: value.value
            "account" -> names.accounts[value.value] ?: value.value
            else -> value.value.ifEmpty { ReportStrings.text("nothing", locale) }
        }
    }
}
