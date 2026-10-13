package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.RecurConfig
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import kotlin.math.abs

/** A schedule as the goal-template engine sees it — the slice of `ScheduleSummary` the math needs. */
data class GoalScheduleInfo(
    val id: String,
    val name: String?,
    val completed: Boolean,
    /** The rule's amount condition; null posts as 0 upstream. */
    val amount: ScheduledAmount?,
    val dateCondition: ScheduleDateCondition?,
)

/**
 * Port of loot-core `schedule-template.ts`: how much a category's schedule
 * templates want budgeted this month. Pure — all schedule data arrives
 * pre-fetched.
 *
 * ponytail: upstream also runs the schedule's *rule actions* here so a rule
 * that splits the scheduled transaction budgets only this category's split
 * legs. The RulesEngine doesn't support split actions yet, so this port
 * targets the schedule's plain amount condition; upgrade path is wiring
 * RulesEngine in once it learns `set-split-amount`.
 */
object GoalTemplateSchedules {
    data class ScheduleTarget(
        /** Position of the source template in the caller's template array. */
        val templateIndex: Int,
        val template: GoalTemplate,
        val target: Long,
        /** "yyyy-MM-dd". */
        val nextDateString: String,
        val targetInterval: Int,
        /** RecurConfig frequency, null for one-offs. */
        val targetFrequency: String?,
        val numMonths: Int,
        val full: Boolean,
        val repeating: Boolean,
    )

    /**
     * Upstream's `isbetween` average: `Math.round(num1 + num2) / 2`, adjusted,
     * then rounded — kept in that exact order because percent adjustments see
     * the unrounded midpoint.
     */
    fun scheduleAmount(amount: ScheduledAmount?, template: GoalTemplate): Long {
        var value = when (amount) {
            is ScheduledAmount.Fixed -> amount.cents.toDouble()
            is ScheduledAmount.Range -> (amount.num1 + amount.num2).toDouble() / 2
            null -> 0.0
        }
        val adjustment = template.adjustment
        if (adjustment != null) {
            when (template.adjustmentType) {
                GoalTemplate.AdjustmentType.Percent -> value *= 1 + adjustment / 100
                GoalTemplate.AdjustmentType.Fixed -> {
                    val sign = if (value < 0) -1.0 else 1.0
                    value += sign * BudgetMonthMath.amountToInteger(adjustment)
                }
                null -> Unit
            }
        }
        return BudgetMonthMath.jsRound(value)
    }

    /**
     * Port of `createScheduleList`. Completed and past-dated schedules drop out
     * (upstream records errors for them that the caller then discards).
     */
    fun createScheduleList(
        templates: List<IndexedValue<GoalTemplate>>,
        currentMonth: String,
        categoryIsIncome: Boolean,
        schedules: List<GoalScheduleInfo>,
    ): List<ScheduleTarget> {
        val monthStart = BudgetMonthMath.day(currentMonth) ?: DayDate.today()
        val targets = mutableListOf<ScheduleTarget>()

        for ((templateIndex, template) in templates) {
            if (template.type != GoalTemplate.Kind.Schedule) continue
            // Prefer scheduleId (UI-managed templates) so renames don't break
            // the lookup; fall back to the trimmed name for notes templates.
            val scheduleId = template.scheduleId
            val name = template.name?.trim { it == ' ' || it == '\t' }
            val schedule = when {
                scheduleId != null -> schedules.firstOrNull { it.id == scheduleId }
                name != null -> schedules.firstOrNull { it.name?.trim { c -> c == ' ' || c == '\t' } == name }
                else -> null
            } ?: continue

            val amount = scheduleAmount(schedule.amount, template)

            var nextDate: DayDate? = null
            var targetInterval = 1
            var targetFrequency: String? = null
            var repeating = false
            var recurConfig: RecurConfig? = null
            when (val condition = schedule.dateCondition) {
                is ScheduleDateCondition.Fixed -> nextDate = condition.day
                is ScheduleDateCondition.Recurring -> {
                    nextDate = ScheduleRecurrence.nextOccurrence(condition.config, monthStart)
                    targetInterval = condition.config.interval
                    targetFrequency = condition.config.frequency.rawValue
                    repeating = true
                    recurConfig = condition.config
                }
                ScheduleDateCondition.Unsupported, null -> nextDate = null
            }
            if (nextDate == null) continue

            val numMonths = BudgetMonthMath.differenceInCalendarMonths(nextDate.iso, currentMonth)
            // Past one-offs error out upstream ("Schedule X is in the Past"),
            // completed ones error as inactive — both contribute nothing.
            if (numMonths < 0 || schedule.completed) continue

            val sign = if (categoryIsIncome) 1 else -1
            var target = sign * amount
            if (repeating && recurConfig != null) {
                target = monthlyRepeatTarget(
                    recurConfig,
                    monthStart,
                    BudgetMonthMath.addMonths(currentMonth, numMonths + 1),
                    target,
                )
            }

            targets += ScheduleTarget(
                templateIndex = templateIndex,
                template = template,
                target = target,
                nextDateString = nextDate.iso,
                targetInterval = targetInterval,
                targetFrequency = targetFrequency,
                numMonths = numMonths,
                full = template.full ?: false,
                repeating = repeating,
            )
        }
        return targets
    }

    /**
     * Sum of all occurrences that land before [boundaryMonth] — upstream's
     * while-loop over `getNextDate(..., noSkipWeekend: true)` with the weekend
     * solve applied manually to the comparison date.
     */
    private fun monthlyRepeatTarget(config: RecurConfig, monthStart: DayDate, boundaryMonth: String, perOccurrence: Long): Long {
        // Base dates iterate without the weekend shift; only the compared date
        // is shifted, mirroring getNextDate's noSkipWeekend flag.
        val baseConfig = config.copy(skipWeekend = false)

        fun solved(date: DayDate): DayDate {
            if (!config.skipWeekend || !date.isWeekend) return date
            return if (config.weekendSolveMode == "before") previousFriday(date) else ScheduleRecurrence.nextMonday(date)
        }

        var total = 0L
        var baseDate = ScheduleRecurrence.nextOccurrence(baseConfig, monthStart) ?: return 0
        var comparisonDate = solved(baseDate)
        // Lexicographic "yyyy-MM-dd" < "yyyy-MM" boundary compare, as upstream.
        while (comparisonDate.iso < boundaryMonth) {
            total += perOccurrence
            val currentDate = baseDate
            val next = ScheduleRecurrence.nextOccurrence(baseConfig, baseDate.addingDays(1)) ?: break
            baseDate = next
            comparisonDate = solved(baseDate)
            // An exhausted bounded schedule keeps returning its last
            // occurrence; upstream breaks on the zero-day difference.
            if (currentDate.days(baseDate) == 0) break
        }
        return total
    }

    private fun previousFriday(date: DayDate): DayDate {
        var result = date
        while (result.weekday != 6) result = result.addingDays(-1)
        return result
    }

    /**
     * Port of `runSchedule`'s budgeting math. Returns the new to-budget total
     * for the category (the caller subtracts what was already accumulated),
     * plus each schedule template's monthly contribution — the weights the
     * caller uses to split the batched total for per-row projections.
     */
    fun run(
        templates: List<IndexedValue<GoalTemplate>>,
        currentMonth: String,
        balance: Long,
        lastMonthBalance: Long,
        toBudget: Long,
        lastMonthGoal: Long,
        categoryIsIncome: Boolean,
        isTracking: Boolean,
        schedules: List<GoalScheduleInfo>,
    ): Pair<Long, Map<Int, Double>> {
        var result = toBudget
        val targets = createScheduleList(templates, currentMonth, categoryIsIncome, schedules)

        fun isPayMonthOf(c: ScheduleTarget): Boolean =
            c.full ||
                ((c.targetFrequency == "monthly" || c.targetFrequency == null) && c.targetInterval == 1 && c.numMonths == 0) ||
                (c.targetFrequency == "weekly" && c.targetInterval <= 4) ||
                (c.targetFrequency == "daily" && c.targetInterval <= 31) ||
                isTracking

        fun isSubMonthly(c: ScheduleTarget): Boolean = c.targetFrequency == "weekly" || c.targetFrequency == "daily"

        val payMonthOf = targets.filter(::isPayMonthOf)
        val sinking = targets.filter { !isPayMonthOf(it) }.sortedBy { it.nextDateString }
        val numSubMonthly = targets.count(::isSubMonthly)

        val totalPayMonthOf = payMonthOf.filter { it.numMonths == 0 }.sumOf { it.target.toDouble() }
        val totalSinking = sinking.sumOf { it.target.toDouble() }
        val totalSinkingBaseContribution = sinking.sumOf { monthlyBaseContribution(it) }

        // Per-schedule monthly contributions, keyed by the source template's index.
        val perTemplate = HashMap<Int, Double>()
        fun addContribution(target: ScheduleTarget, amount: Double) {
            perTemplate[target.templateIndex] = (perTemplate[target.templateIndex] ?: 0.0) + amount
        }

        if (balance.toDouble() >= totalSinking + totalPayMonthOf ||
            (lastMonthGoal.toDouble() < totalSinking + totalPayMonthOf && lastMonthGoal != 0L && balance >= lastMonthGoal && numSubMonthly > 0)
        ) {
            result += BudgetMonthMath.jsRound(totalPayMonthOf + totalSinkingBaseContribution)
            for (target in payMonthOf) if (target.numMonths == 0) addContribution(target, target.target.toDouble())
            for (target in sinking) addContribution(target, monthlyBaseContribution(target))
        } else {
            val (totalSinkingContribution, sinkingPerTemplate) = sinkingContributionBreakdown(sinking, lastMonthBalance)
            result += if (sinking.isEmpty()) {
                BudgetMonthMath.jsRound(totalPayMonthOf + totalSinkingContribution) - lastMonthBalance
            } else {
                BudgetMonthMath.jsRound(totalPayMonthOf + totalSinkingContribution)
            }
            for (target in payMonthOf) if (target.numMonths == 0) addContribution(target, target.target.toDouble())
            for ((index, amount) in sinkingPerTemplate) perTemplate[index] = (perTemplate[index] ?: 0.0) + amount
        }
        return result to perTemplate
    }

    /** Port of `getMonthlyBaseContribution`. */
    private fun monthlyBaseContribution(schedule: ScheduleTarget): Double {
        val target = schedule.target.toDouble()
        val interval = schedule.targetInterval.toDouble()
        return when (schedule.targetFrequency) {
            "yearly" -> target / interval / 12
            "monthly" -> target / interval
            "weekly", "daily" -> {
                val previous = if (schedule.targetFrequency == "weekly") {
                    BudgetMonthMath.subWeeks(schedule.nextDateString, schedule.targetInterval)
                } else {
                    BudgetMonthMath.subDays(schedule.nextDateString, schedule.targetInterval)
                }
                var intervalMonths = BudgetMonthMath.differenceInCalendarMonths(schedule.nextDateString, previous)
                if (intervalMonths == 0) intervalMonths = 1
                target / intervalMonths
            }
            else -> target / interval
        }
    }

    /**
     * Port of `getSinkingContributionBreakdown`: each schedule needs its target
     * minus what earlier ones left behind, spread across the months until it's
     * due — with each schedule's own contribution recorded.
     */
    private fun sinkingContributionBreakdown(sinking: List<ScheduleTarget>, lastMonthBalance: Long): Pair<Double, Map<Int, Double>> {
        var total = 0.0
        var remainder = 0.0
        val perTemplate = HashMap<Int, Double>()
        for ((index, schedule) in sinking.withIndex()) {
            remainder = if (index == 0) (schedule.target - lastMonthBalance).toDouble() else schedule.target - remainder
            var contributionTarget = 0.0
            if (remainder >= 0) {
                contributionTarget = remainder
                remainder = 0.0
            } else {
                remainder = abs(remainder)
            }
            val contribution = contributionTarget / (schedule.numMonths + 1)
            total += contribution
            perTemplate[schedule.templateIndex] = (perTemplate[schedule.templateIndex] ?: 0.0) + contribution
        }
        return total to perTemplate
    }
}
