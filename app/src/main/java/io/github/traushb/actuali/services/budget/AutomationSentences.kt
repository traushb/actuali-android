package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.ReportStrings
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Human-readable one-line summaries of templates (port of the web's
 * TemplateSentence / *ReadOnly components) and the template → `#template`
 * note-line renderer (port of template-notes.ts `unparse`) used by the
 * un-migrate flow.
 */
object AutomationSentences {
    private fun localized(key: String, vararg args: Any?, locale: Locale): String = ReportStrings.localized(key, *args, locale = locale)

    fun trimTrailingZeros(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else value.toString()

    /** "MMM yyyy" for a YYYY-MM string, "—" when empty (web formatMonthLabel). */
    fun monthLabel(month: String?, locale: Locale = Locale.getDefault()): String {
        if (month.isNullOrEmpty()) return "—"
        val (year, monthNumber) = BudgetMonthMath.yearAndMonth(month) ?: return month
        return LocalDate.of(year, monthNumber, 1).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
    }

    private fun adjustmentSuffix(template: GoalTemplate, amount: (Double) -> String, locale: Locale): String {
        val adjustment = template.adjustment ?: return ""
        val type = template.adjustmentType ?: return ""
        val direction = if (adjustment >= 0) localized("increased", locale = locale) else localized("decreased", locale = locale)
        val value = abs(adjustment)
        val formatted = if (type == GoalTemplate.AdjustmentType.Percent) "${trimTrailingZeros(value)}%" else amount(value)
        return localized(", %@ by %@", direction, formatted, locale = locale)
    }

    /**
     * The list row / tooltip sentence for one template. [amount] formats a
     * whole-currency-unit value for display; [categoryName] resolves income
     * category ids for percentage sources.
     */
    fun sentence(
        template: GoalTemplate,
        amount: (Double) -> String,
        categoryName: (String) -> String?,
        locale: Locale = Locale.getDefault(),
    ): String = when (template.type) {
        GoalTemplate.Kind.Periodic -> {
            val value = amount(template.amount ?: 0.0)
            val count = (template.period?.amount ?: 1).toLong()
            when (template.period?.period ?: GoalTemplate.PeriodUnit.Month) {
                GoalTemplate.PeriodUnit.Day -> localized("Budget %@ every %lld days", value, count, locale = locale)
                GoalTemplate.PeriodUnit.Week -> localized("Budget %@ every %lld weeks", value, count, locale = locale)
                GoalTemplate.PeriodUnit.Month -> localized("Budget %@ every %lld months", value, count, locale = locale)
                GoalTemplate.PeriodUnit.Year -> localized("Budget %@ every %lld years", value, count, locale = locale)
            }
        }
        GoalTemplate.Kind.By, GoalTemplate.Kind.Spend -> {
            val value = amount(template.amount ?: 0.0)
            val month = monthLabel(template.month, locale)
            val earlySpending = localized(", early spending from %@", monthLabel(template.from, locale), locale = locale)
            var sentence = localized("Save %@ by %@", value, month, locale = locale)
            if (template.type == GoalTemplate.Kind.Spend) sentence += earlySpending
            val repeatTarget = if (template.type == GoalTemplate.Kind.Spend) month + earlySpending else month
            val repeats = template.repeatCount
            if (template.annual == true) {
                sentence = localized("Save %@ by %@, repeating every %lld years", value, repeatTarget, (repeats ?: 1).toLong(), locale = locale)
            } else if (repeats != null && repeats > 0) {
                sentence = localized("Save %@ by %@, repeating every %lld months", value, repeatTarget, repeats.toLong(), locale = locale)
            }
            sentence
        }
        GoalTemplate.Kind.Schedule -> {
            val name = template.name
            if (name.isNullOrEmpty()) {
                localized("Budget for a schedule", locale = locale)
            } else {
                val base = if (template.full == true) {
                    localized("Cover the occurrences of the schedule ‘%@’ this month", name, locale = locale)
                } else {
                    localized("Save up for the schedule ‘%@’", name, locale = locale)
                }
                base + adjustmentSuffix(template, amount, locale)
            }
        }
        GoalTemplate.Kind.Percentage -> {
            val percent = trimTrailingZeros(template.percent ?: 0.0)
            val time = if (template.previous == true) localized("last month", locale = locale) else localized("this month", locale = locale)
            val source = template.category ?: ""
            when (source.lowercase()) {
                "all income" -> localized("Budget %@%% of total income %@", percent, time, locale = locale)
                "available funds" -> localized("Budget %@%% of available funds to budget %@", percent, time, locale = locale)
                else -> localized("Budget %@%% of ‘%@’ %@", percent, categoryName(source) ?: source, time, locale = locale)
            }
        }
        GoalTemplate.Kind.Copy -> localized("Budget the same amount as %lld months ago", (template.lookBack ?: 0).toLong(), locale = locale)
        GoalTemplate.Kind.Average ->
            localized("Budget the average of the last %lld complete months", (template.numMonths ?: 0).toLong(), locale = locale) +
                adjustmentSuffix(template, amount, locale)
        GoalTemplate.Kind.Remainder ->
            localized("Share remaining funds to budget (weight %@)", trimTrailingZeros(template.weight ?: 1.0), locale = locale)
        GoalTemplate.Kind.Goal -> localized("Long-term goal of %@", amount(template.amount ?: 0.0), locale = locale)
        GoalTemplate.Kind.Refill -> localized("Refill to balance limit", locale = locale)
        GoalTemplate.Kind.Limit -> {
            val limit = template.limit
            if (limit == null) {
                localized("Set a balance limit", locale = locale)
            } else {
                val value = amount(limit.amount)
                val cap = if (limit.hold) localized("soft cap", locale = locale) else localized("hard cap", locale = locale)
                when (limit.period) {
                    GoalTemplate.LimitPeriod.Daily -> localized("Set a balance limit of %@/day (%@)", value, cap, locale = locale)
                    GoalTemplate.LimitPeriod.Weekly -> localized("Set a balance limit of %@/week (%@)", value, cap, locale = locale)
                    GoalTemplate.LimitPeriod.Monthly -> localized("Set a balance limit of %@/month (%@)", value, cap, locale = locale)
                }
            }
        }
        GoalTemplate.Kind.Simple -> template.monthly?.let { localized("Budget %@ per month", amount(it), locale = locale) }
            ?: localized("Unsupported template type: %@", template.type.rawValue, locale = locale)
        GoalTemplate.Kind.Error -> localized("Invalid automation: %@", template.error ?: template.line ?: template.type.rawValue, locale = locale)
    }

    // Note rendering (port of template-notes.ts unparse)

    /** Priority 0 is the parser's "unset" default and drops the suffix. */
    private fun templatePrefix(priority: Int?): String = if (priority == null || priority == 0) "#template" else "#template-$priority"

    private fun limitToString(limit: GoalTemplate.Limit): String {
        val base = when (limit.period) {
            GoalTemplate.LimitPeriod.Weekly -> "up to ${trimTrailingZeros(limit.amount)} per week starting ${limit.start ?: ""}"
            GoalTemplate.LimitPeriod.Daily -> "up to ${trimTrailingZeros(limit.amount)} per day"
            GoalTemplate.LimitPeriod.Monthly -> "up to ${trimTrailingZeros(limit.amount)}"
        }
        return if (limit.hold) "$base hold" else base
    }

    private fun adjustmentToString(template: GoalTemplate): String {
        val adjustment = template.adjustment ?: return ""
        val op = if (adjustment >= 0) "increase" else "decrease"
        val suffix = if (template.adjustmentType == GoalTemplate.AdjustmentType.Percent) "%" else ""
        return " [$op ${trimTrailingZeros(abs(adjustment))}$suffix]"
    }

    private fun repeatToString(annual: Boolean?, repeatCount: Int?): String? {
        annual ?: return null
        return if (annual) {
            if (repeatCount == null || repeatCount == 1) "year" else "$repeatCount years"
        } else {
            if (repeatCount == null || repeatCount == 1) "month" else "$repeatCount months"
        }
    }

    /**
     * Render one template as a `#template`/`#goal` note line; null for error
     * templates and a standalone refill (which merges into the limit).
     */
    fun noteLine(template: GoalTemplate, refill: GoalTemplate?, categoryName: (String) -> String?): String? {
        val prefix = templatePrefix(template.priority)
        return when (template.type) {
            GoalTemplate.Kind.Goal -> "#goal ${trimTrailingZeros(template.amount ?: 0.0)}"
            GoalTemplate.Kind.Simple -> buildString {
                append(prefix)
                template.monthly?.let { append(" ${trimTrailingZeros(it)}") }
                template.limit?.let { append(" ${limitToString(it)}") }
            }
            GoalTemplate.Kind.Schedule -> buildString {
                append("$prefix schedule")
                if (template.full == true) append(" full")
                append(" ${template.name ?: ""}")
                append(adjustmentToString(template))
            }
            GoalTemplate.Kind.Percentage -> {
                val previous = if (template.previous == true) "previous " else ""
                // The UI stores income category ids; note grammar wants names.
                val source = template.category?.let { categoryName(it) ?: it } ?: ""
                "$prefix ${trimTrailingZeros(template.percent ?: 0.0)}% of $previous$source"
            }
            GoalTemplate.Kind.Periodic -> {
                val period = template.period ?: GoalTemplate.Period(GoalTemplate.PeriodUnit.Month, 1)
                buildString {
                    append("$prefix ${trimTrailingZeros(template.amount ?: 0.0)} repeat every ${period.amount} ${period.period.rawValue}s starting ${template.starting ?: ""}")
                    template.limit?.let { append(" ${limitToString(it)}") }
                }
            }
            GoalTemplate.Kind.By, GoalTemplate.Kind.Spend -> buildString {
                append("$prefix ${trimTrailingZeros(template.amount ?: 0.0)} by ${template.month ?: ""}")
                if (template.type == GoalTemplate.Kind.Spend) template.from?.let { append(" spend from $it") }
                repeatToString(template.annual, template.repeatCount)?.let { append(" repeat every $it") }
            }
            GoalTemplate.Kind.Remainder -> buildString {
                append("$prefix remainder")
                template.weight?.takeIf { it != 1.0 }?.let { append(" ${trimTrailingZeros(it)}") }
                template.limit?.let { append(" ${limitToString(it)}") }
            }
            GoalTemplate.Kind.Average -> "$prefix average ${template.numMonths ?: 0} months" + adjustmentToString(template)
            GoalTemplate.Kind.Copy -> "$prefix copy from ${template.lookBack ?: 0} months ago"
            GoalTemplate.Kind.Limit -> {
                val limit = template.limit ?: return null
                if (refill != null) "${templatePrefix(refill.priority)} ${limitToString(limit)}" else "$prefix 0 ${limitToString(limit)}"
            }
            GoalTemplate.Kind.Refill, GoalTemplate.Kind.Error -> null
        }
    }

    private fun String.trimTrailingSpaces(): String = trimEnd { it == ' ' || it == '\t' }

    /**
     * Render a template list as note lines, merging a refill into the limit
     * line and keeping descriptions above their template line — the web's
     * `unparse`.
     */
    fun renderNoteTemplates(templates: List<GoalTemplate>, categoryName: (String) -> String? = { null }): String {
        val refill = templates.firstOrNull { it.type == GoalTemplate.Kind.Refill }
        return templates.filter { it.type != GoalTemplate.Kind.Refill }.flatMap { template ->
            val line = noteLine(template, refill, categoryName) ?: return@flatMap emptyList()
            val descriptionLines = (template.description ?: "").split("\n").map { it.trimTrailingSpaces() }.filter { it.isNotEmpty() }
            descriptionLines + line
        }.joinToString("\n")
    }

    /**
     * The un-migrate note preview: append rendered template/cleanup lines
     * that the existing note doesn't already contain — port of the seeding
     * logic in UnmigrateBudgetAutomationsModal.
     */
    fun mergeIntoNote(existingNote: String, rendered: String): String {
        val base = existingNote.trimEnd()
        if (rendered.isEmpty()) return base
        val existingLines = base.split("\n").map { it.trim { c -> c == ' ' || c == '\t' } }.filter { it.isNotEmpty() }.toSet()
        val newLines = rendered.split("\n").map { it.trimTrailingSpaces() }
            .filter { it.isNotEmpty() && it.trim { c -> c == ' ' || c == '\t' } !in existingLines }
        if (newLines.isEmpty()) return base
        val separator = if (base.isEmpty()) "" else "\n\n"
        return base + separator + "Export from automations UI:\n" + newLines.joinToString("\n")
    }
}
