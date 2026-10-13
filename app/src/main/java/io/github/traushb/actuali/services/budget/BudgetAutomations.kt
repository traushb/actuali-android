package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale
import java.util.UUID

/**
 * The automation editor's grouping of template types — port of the web's
 * `DisplayTemplateType` (goals/constants.ts). One display type can cover
 * several storage types (historical = average/copy, by = by/spend, fixed =
 * periodic, absorbing legacy simple).
 */
enum class AutomationDisplayType(val rawValue: String) {
    Fixed("fixed"),
    Schedule("schedule"),
    By("by"),
    Percentage("percentage"),
    Historical("historical"),
    Limit("limit"),
    Refill("refill"),
    Remainder("remainder"),
    Goal("goal"),
    ;

    val id: String get() = rawValue

    fun label(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
        when (this) {
            Fixed -> "Fixed amount"
            Schedule -> "Cover schedule"
            By -> "Save by date"
            Percentage -> "% of income"
            Historical -> "From history"
            Limit -> "Balance cap"
            Refill -> "Refill to cap"
            Remainder -> "Whatever is left"
            Goal -> "Long-term goal"
        },
        locale,
    )

    fun explanation(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
        when (this) {
            Fixed -> "Add a set amount every month, week, day, or year."
            Schedule -> "Save up for a scheduled transaction."
            By -> "Spread a target amount across the months until a deadline."
            Percentage -> "A share of this month's or last month's income."
            Historical -> "Use past months: average, a specific month, or a copy."
            Limit -> "Stop budgeting to this category once the balance reaches a cap."
            Refill -> "Top the category back up to the balance cap each month."
            Remainder -> "Split any remaining To Budget across these categories."
            Goal ->
                "Set a long-term savings target. This changes the coloring of the balance on the budget page to be based on progress towards the target rather than the current month funding progress."
        },
        locale,
    )

    companion object {
        /** Types managed in the Options section rather than as contributions. */
        val nonContribution = setOf(Limit, Goal)

        /** At most one of each of these per category (web's SINGLETON_TYPES, plus goal). */
        val singleton = setOf(Limit, Refill, Remainder, Goal)

        /** Which display type edits a stored template — the web's `getDisplayTypeFromTemplate`; null for errors. */
        fun forTemplate(template: GoalTemplate): AutomationDisplayType? = when (template.type) {
            GoalTemplate.Kind.Percentage -> Percentage
            GoalTemplate.Kind.Schedule -> Schedule
            GoalTemplate.Kind.Periodic, GoalTemplate.Kind.Simple -> Fixed
            GoalTemplate.Kind.Limit -> Limit
            GoalTemplate.Kind.Refill -> Refill
            GoalTemplate.Kind.Average, GoalTemplate.Kind.Copy -> Historical
            GoalTemplate.Kind.By, GoalTemplate.Kind.Spend -> By
            GoalTemplate.Kind.Remainder -> Remainder
            GoalTemplate.Kind.Goal -> Goal
            GoalTemplate.Kind.Error -> null
        }
    }
}

/** One editable automation row — the web's `AutomationEntry`. */
data class AutomationEntry(
    val template: GoalTemplate,
    val displayType: AutomationDisplayType,
    val id: String = UUID.randomUUID().toString(),
)

object BudgetAutomations {
    /** The web reducer's DEFAULT_PRIORITY for new automations. */
    const val DEFAULT_PRIORITY = 1

    /** Port of reducer.ts changeType / automationExamples. */
    fun defaultTemplate(displayType: AutomationDisplayType, currentMonth: String = BudgetMonthMath.currentMonth()): GoalTemplate =
        when (displayType) {
            AutomationDisplayType.Fixed -> GoalTemplate(GoalTemplate.Kind.Periodic, GoalTemplate.Directive.Template, DEFAULT_PRIORITY).apply {
                amount = 100.0
                period = GoalTemplate.Period(GoalTemplate.PeriodUnit.Month, 1)
                starting = BudgetMonthMath.firstDayOfMonth(currentMonth)
            }
            AutomationDisplayType.By -> GoalTemplate(GoalTemplate.Kind.By, GoalTemplate.Directive.Template, DEFAULT_PRIORITY).apply {
                amount = 1200.0
                // 12 months out so late-year users don't get a passed target.
                month = BudgetMonthMath.addMonths(currentMonth, 12)
                annual = true
                repeatCount = 1
            }
            AutomationDisplayType.Schedule -> GoalTemplate(GoalTemplate.Kind.Schedule, GoalTemplate.Directive.Template, DEFAULT_PRIORITY).apply { name = "" }
            AutomationDisplayType.Percentage -> GoalTemplate(GoalTemplate.Kind.Percentage, GoalTemplate.Directive.Template, DEFAULT_PRIORITY).apply {
                percent = 15.0
                previous = false
                category = "all income"
            }
            AutomationDisplayType.Historical -> GoalTemplate(GoalTemplate.Kind.Average, GoalTemplate.Directive.Template, DEFAULT_PRIORITY).apply { numMonths = 3 }
            AutomationDisplayType.Limit -> GoalTemplate(GoalTemplate.Kind.Limit, GoalTemplate.Directive.Template, null).apply {
                limit = GoalTemplate.Limit(500.0, false, GoalTemplate.LimitPeriod.Monthly, null)
                amount = 500.0
            }
            AutomationDisplayType.Refill -> GoalTemplate(GoalTemplate.Kind.Refill, GoalTemplate.Directive.Template, DEFAULT_PRIORITY)
            AutomationDisplayType.Remainder -> GoalTemplate(GoalTemplate.Kind.Remainder, GoalTemplate.Directive.Template, null).apply { weight = 1.0 }
            AutomationDisplayType.Goal -> GoalTemplate(GoalTemplate.Kind.Goal, GoalTemplate.Directive.Goal, null).apply { amount = 1000.0 }
        }

    /**
     * Change an entry to another display type, keeping the description and
     * resetting the rest to that type's defaults (matching the web reducer).
     */
    fun convert(entry: AutomationEntry, displayType: AutomationDisplayType, currentMonth: String = BudgetMonthMath.currentMonth()): AutomationEntry {
        if (entry.displayType == displayType) return entry
        val template = defaultTemplate(displayType, currentMonth).apply { description = entry.template.description }
        return entry.copy(template = template, displayType = displayType)
    }

    /** Toggle a by template's early-spending mode (by ↔ spend), carrying the shared fields. */
    fun setEarlySpending(template: GoalTemplate, enabled: Boolean): GoalTemplate {
        val next = template.copy()
        if (enabled && template.type == GoalTemplate.Kind.By) {
            next.type = GoalTemplate.Kind.Spend
            next.from = template.from ?: template.month
        } else if (!enabled && template.type == GoalTemplate.Kind.Spend) {
            next.type = GoalTemplate.Kind.By
            next.from = null
        }
        return next
    }

    /** Toggle a historical template's mode (average ↔ copy), carrying the month count across. */
    fun setHistoricalMode(template: GoalTemplate, copyMode: Boolean): GoalTemplate {
        val next = template.copy()
        if (copyMode && template.type == GoalTemplate.Kind.Average) {
            next.type = GoalTemplate.Kind.Copy
            next.lookBack = template.numMonths
            next.numMonths = null
            next.adjustment = null
            next.adjustmentType = null
        } else if (!copyMode && template.type == GoalTemplate.Kind.Copy) {
            next.type = GoalTemplate.Kind.Average
            next.numMonths = template.lookBack
            next.lookBack = null
        }
        return next
    }

    /**
     * Port of migrateTemplatesToAutomations.ts: legacy `simple` templates split
     * into limit (+refill for limit-only) and periodic parts; attached limits
     * on periodic/remainder split out; schedule templates hydrated with the
     * schedule id.
     */
    fun migrateToEntries(templates: List<GoalTemplate>, schedules: List<GoalScheduleInfo>): List<AutomationEntry> {
        val entries = mutableListOf<AutomationEntry>()
        fun limitEntry(limit: GoalTemplate.Limit, description: String? = null): AutomationEntry {
            val template = GoalTemplate(GoalTemplate.Kind.Limit, GoalTemplate.Directive.Template, null).apply {
                this.limit = limit.copy()
                amount = limit.amount
                this.description = description
            }
            return AutomationEntry(template, AutomationDisplayType.Limit)
        }

        for (template in templates) {
            when (template.type) {
                GoalTemplate.Kind.Schedule -> {
                    val hydrated = template.copy()
                    val schedule = template.scheduleId?.let { id -> schedules.firstOrNull { it.id == id } }
                        ?: template.name?.let { name ->
                            schedules.firstOrNull { it.name?.trim { c -> c == ' ' || c == '\t' } == name.trim { c -> c == ' ' || c == '\t' } }
                        }
                    if (schedule != null) {
                        hydrated.scheduleId = schedule.id
                        hydrated.name = schedule.name ?: template.name
                    }
                    entries += AutomationEntry(hydrated, AutomationDisplayType.Schedule)
                }
                GoalTemplate.Kind.Simple -> {
                    val monthly = template.monthly
                    val hasMonthly = monthly != null && monthly != 0.0
                    template.limit?.let { limit ->
                        // A description on a limit-only simple template belongs
                        // to the limit; with a monthly amount it goes there.
                        entries += limitEntry(limit, if (hasMonthly) null else template.description)
                        if (monthly == null) {
                            entries += AutomationEntry(GoalTemplate(GoalTemplate.Kind.Refill, GoalTemplate.Directive.Template, template.priority), AutomationDisplayType.Refill)
                        }
                    }
                    val contribution = if (hasMonthly) monthly else if (monthly == 0.0 && template.limit == null) 0.0 else null
                    if (contribution != null) {
                        val periodic = GoalTemplate(GoalTemplate.Kind.Periodic, GoalTemplate.Directive.Template, template.priority).apply {
                            amount = contribution
                            period = GoalTemplate.Period(GoalTemplate.PeriodUnit.Month, 1)
                            starting = BudgetMonthMath.firstDayOfMonth(BudgetMonthMath.currentMonth())
                            description = template.description
                        }
                        entries += AutomationEntry(periodic, AutomationDisplayType.Fixed)
                    }
                    // A simple with neither monthly nor limit is a no-op; drop it.
                }
                GoalTemplate.Kind.Periodic, GoalTemplate.Kind.Remainder -> {
                    val limit = template.limit
                    if (limit != null) {
                        val base = template.copy(limit = null)
                        AutomationDisplayType.forTemplate(base)?.let { entries += AutomationEntry(base, it) }
                        entries += limitEntry(limit)
                    } else {
                        AutomationDisplayType.forTemplate(template)?.let { entries += AutomationEntry(template, it) }
                    }
                }
                else -> AutomationDisplayType.forTemplate(template)?.let { entries += AutomationEntry(template, it) }
            }
        }
        return entries
    }
}
