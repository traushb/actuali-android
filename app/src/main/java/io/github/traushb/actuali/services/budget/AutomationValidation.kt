package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

/**
 * Per-automation and cross-automation validation — port of the web's
 * `validateAutomation.ts`. Errors block saving, exactly as on the web.
 */
sealed interface AutomationError {
    data class ScheduleNotFound(val name: String) : AutomationError
    data object RefillNoCap : AutomationError
    data object LimitNoContributor : AutomationError
    data class PercentageOutOfRange(val percent: Double) : AutomationError
    data object PercentageNoSource : AutomationError
    data class PercentageSourceNotFound(val source: String) : AutomationError
    data object ByNoMonth : AutomationError
    data class ByTargetPast(val month: String) : AutomationError
    data object SpendNoFrom : AutomationError
    data object SpendFromAfterTarget : AutomationError
    data object AdjustmentOutOfRange : AutomationError

    fun title(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
        when (this) {
            is ScheduleNotFound -> "Schedule not found"
            RefillNoCap -> "Refill needs a balance cap"
            LimitNoContributor -> "Balance cap needs a contributing automation"
            is PercentageOutOfRange -> "Percentage out of range"
            PercentageNoSource -> "Source category missing"
            is PercentageSourceNotFound -> "Source category not recognised"
            ByNoMonth -> "Target month missing"
            is ByTargetPast -> "Target is in the past"
            SpendNoFrom -> "Early-spending month missing"
            SpendFromAfterTarget -> "Early spending starts after target"
            AdjustmentOutOfRange -> "Adjustment out of range"
        },
        locale,
    )

    fun shortMessage(locale: Locale = Locale.getDefault()): String = when (this) {
        is ScheduleNotFound -> if (name.isEmpty()) {
            ReportStrings.text("Pick a schedule", locale)
        } else {
            ReportStrings.format("No schedule named “%@”", name, locale = locale)
        }
        RefillNoCap -> ReportStrings.text("Add a balance cap", locale)
        LimitNoContributor -> ReportStrings.text("Add an automation that contributes funds", locale)
        is PercentageOutOfRange -> ReportStrings.format("%@ must be between 0 and 100", "${AutomationSentences.trimTrailingZeros(percent)}%", locale = locale)
        PercentageNoSource -> ReportStrings.text("Pick a source category", locale)
        is PercentageSourceNotFound -> ReportStrings.text("Pick a valid income category", locale)
        ByNoMonth -> ReportStrings.text("Pick a target month", locale)
        is ByTargetPast -> ReportStrings.format("%@ has already passed", AutomationSentences.monthLabel(month), locale = locale)
        SpendNoFrom -> ReportStrings.text("Pick an early-spending start month", locale)
        SpendFromAfterTarget -> ReportStrings.text("Early spending must start before the target", locale)
        AdjustmentOutOfRange -> ReportStrings.text("Adjustment out of range", locale)
    }

    fun detail(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
        when (this) {
            is ScheduleNotFound -> "Pick an existing schedule, or create one in Schedules. This automation can't run until it's linked to a schedule."
            RefillNoCap -> "Refill automations must have a “Balance cap” automation added to use as the target."
            LimitNoContributor ->
                "A balance cap on its own does nothing. Add a contributing automation (such as a fixed amount, save by date, or whatever is left) so the cap has something to clamp."
            is PercentageOutOfRange -> "Set a value greater than 0% and at most 100%."
            PercentageNoSource -> "Pick which income the percentage is taken from."
            is PercentageSourceNotFound -> "The source must be an income category, total income, or available funds."
            ByNoMonth -> "Pick the month the target amount should be saved by."
            is ByTargetPast -> "One-shot targets must be in the future. Turn on Repeats to roll a past anchor forward."
            SpendNoFrom -> "Pick the month spending is expected to start."
            SpendFromAfterTarget -> "The early-spending month must be on or before the target month."
            AdjustmentOutOfRange -> "Percentage adjustments must be between -100% and 1000%."
        },
        locale,
    )
}

sealed interface AutomationConflict {
    data class PercentOver100(val total: Double) : AutomationConflict
    data object SchedulePriorityMismatch : AutomationConflict

    fun message(locale: Locale = Locale.getDefault()): String = when (this) {
        is PercentOver100 -> ReportStrings.format(
            "Percentage automations for one source add up to %@. Together they must stay at or below 100%.",
            "${AutomationSentences.trimTrailingZeros(total)}%",
            locale = locale,
        )
        SchedulePriorityMismatch -> ReportStrings.text("Schedule and save-by-date automations must all share one priority, or none of them will budget.", locale)
    }
}

object AutomationValidation {
    private fun adjustmentOutOfRange(template: GoalTemplate): Boolean {
        if (template.type != GoalTemplate.Kind.Schedule && template.type != GoalTemplate.Kind.Average) return false
        val adjustment = template.adjustment ?: return false
        if (template.adjustmentType != GoalTemplate.AdjustmentType.Percent) return false
        return adjustment <= -100 || adjustment > 1000
    }

    /**
     * @param validPercentageSources income category ids, lower-cased names,
     *   and the special aliases ("all income", "available funds").
     */
    fun validate(
        entry: AutomationEntry,
        allTemplates: List<GoalTemplate>,
        schedules: List<GoalScheduleInfo>,
        currentMonth: String,
        validPercentageSources: Set<String>,
    ): AutomationError? {
        val template = entry.template
        return when (entry.displayType) {
            AutomationDisplayType.Schedule -> {
                if (template.type != GoalTemplate.Kind.Schedule) return null
                if (template.scheduleId.isNullOrEmpty() && template.name.isNullOrEmpty()) return AutomationError.ScheduleNotFound("")
                val match = schedules.firstOrNull { if (template.scheduleId != null) it.id == template.scheduleId else it.name == template.name }
                if (match == null || match.completed) return AutomationError.ScheduleNotFound(template.name ?: "")
                if (adjustmentOutOfRange(template)) AutomationError.AdjustmentOutOfRange else null
            }
            AutomationDisplayType.Historical -> if (adjustmentOutOfRange(template)) AutomationError.AdjustmentOutOfRange else null
            AutomationDisplayType.Refill -> if (allTemplates.any { it.type == GoalTemplate.Kind.Limit }) null else AutomationError.RefillNoCap
            AutomationDisplayType.Limit -> {
                val hasContributor = allTemplates.any {
                    it.type != GoalTemplate.Kind.Limit && it.type != GoalTemplate.Kind.Goal && it.type != GoalTemplate.Kind.Error
                }
                if (hasContributor) null else AutomationError.LimitNoContributor
            }
            AutomationDisplayType.Percentage -> {
                if (template.type != GoalTemplate.Kind.Percentage) return null
                val source = template.category?.takeIf { it.isNotEmpty() } ?: return AutomationError.PercentageNoSource
                val percent = template.percent ?: 0.0
                if (percent <= 0 || percent > 100) return AutomationError.PercentageOutOfRange(percent)
                if (source !in validPercentageSources && source.lowercase() !in validPercentageSources) {
                    return AutomationError.PercentageSourceNotFound(source)
                }
                null
            }
            AutomationDisplayType.By -> {
                if (template.type != GoalTemplate.Kind.By && template.type != GoalTemplate.Kind.Spend) return null
                val month = template.month?.takeIf { BudgetMonthMath.yearAndMonth(it) != null } ?: return AutomationError.ByNoMonth
                val monthsRemaining = BudgetMonthMath.differenceInCalendarMonths(month, currentMonth)
                // Recurring targets anchored in the past roll forward; only flag one-shot goals.
                if (monthsRemaining < 0 && template.annual != true && template.repeatCount == null) return AutomationError.ByTargetPast(month)
                if (template.type == GoalTemplate.Kind.Spend) {
                    val from = template.from?.takeIf { BudgetMonthMath.yearAndMonth(it) != null } ?: return AutomationError.SpendNoFrom
                    if (BudgetMonthMath.differenceInCalendarMonths(month, from) < 0) return AutomationError.SpendFromAfterTarget
                }
                null
            }
            AutomationDisplayType.Fixed, AutomationDisplayType.Remainder, AutomationDisplayType.Goal -> null
        }
    }

    /** Sum of percentage templates per (previous, source) must stay ≤ 100. */
    fun percentageAllocationConflict(templates: List<GoalTemplate>): AutomationConflict? {
        val percentBySource = HashMap<String, Double>()
        for (template in templates) {
            if (template.type != GoalTemplate.Kind.Percentage) continue
            val source = template.category?.takeIf { it.isNotEmpty() } ?: continue
            val key = "${template.previous == true}|${source.lowercase()}"
            percentBySource[key] = (percentBySource[key] ?: 0.0) + (template.percent ?: 0.0)
        }
        val maxPercent = percentBySource.values.maxOrNull() ?: 0.0
        return if (maxPercent > 100) AutomationConflict.PercentOver100(maxPercent) else null
    }

    /**
     * The engine requires every schedule and by template in a category to
     * share one priority; a mismatch means none of them budget.
     */
    fun schedulePriorityConflict(templates: List<GoalTemplate>): AutomationConflict? {
        val priorities = templates.filter { it.type == GoalTemplate.Kind.Schedule || it.type == GoalTemplate.Kind.By }
            .map { it.priority ?: 0 }.toSet()
        return if (priorities.size > 1) AutomationConflict.SchedulePriorityMismatch else null
    }
}
