package io.github.traushb.actuali.services.budget

import kotlin.math.max
import kotlin.math.min

/** The category slice the engine needs (upstream passes full CategoryEntity). */
data class GoalTemplateCategory(val id: String, val name: String, val isIncome: Boolean)

/**
 * Sheet values for the goal-template engine — the subset of loot-core's
 * spreadsheet cells the templates read, precomputed per (month, category)
 * from SQLite. Months are YYYYMM ints; amounts are cents.
 */
class GoalTemplateSheet {
    data class MonthCat(val month: Int, val category: String)

    var isTracking = false
    var hideFraction = false

    /** `to-budget` (envelope) / `total-saved` (tracking) for the target month. */
    var availableStart = 0L

    /** `budget-{cat}`: budgeted amount where a budget row exists. */
    val budgeted = HashMap<MonthCat, Long>()

    /** `sum-amount-{cat}`: the month's net activity (negative = spending). */
    val spent = HashMap<MonthCat, Long>()

    /** `leftover-{cat}`: the category balance at the END of that month. */
    val leftover = HashMap<MonthCat, Long>()

    /** `carryover-{cat}`: rollover flag on that month's budget row. */
    val carryover = HashSet<MonthCat>()

    /** `goal-{cat}`: non-null goal column values. */
    val goals = HashMap<MonthCat, Long>()

    /** Rows whose goal or long_goal is non-null — the orphan-reset set. */
    val goalRows = HashSet<MonthCat>()

    /** `total-income` per month (sum of income categories' activity). */
    val totalIncome = HashMap<Int, Long>()

    /** Earliest YYYYMM with a budget row or on-budget activity, per category. */
    val firstActivityMonth = HashMap<String, Int>()

    private fun key(month: String, category: String) = MonthCat(BudgetMonthMath.monthInt(month), category)

    fun budgeted(month: String, category: String): Long = budgeted[key(month, category)] ?: 0

    fun spent(month: String, category: String): Long = spent[key(month, category)] ?: 0

    fun leftover(month: String, category: String): Long = leftover[key(month, category)] ?: 0

    fun carryover(month: String, category: String): Boolean = key(month, category) in carryover

    fun goal(month: String, category: String): Long = goals[key(month, category)] ?: 0

    fun totalIncome(month: String): Long = totalIncome[BudgetMonthMath.monthInt(month)] ?: 0
}

class GoalTemplateError(message: String) : Exception(message)

/**
 * Per-category template processor — a straight port of loot-core's
 * `CategoryTemplateContext`, synchronous because every sheet value arrives
 * precomputed in [GoalTemplateSheet].
 */
class GoalTemplateContext(
    templates: List<GoalTemplate>,
    val category: GoalTemplateCategory,
    private val month: String,
    budgeted: Long,
    private val sheet: GoalTemplateSheet,
    private val schedules: List<GoalScheduleInfo>,
    private val allCategories: List<GoalTemplateCategory>,
    private val currentMonth: String,
    private val skipAvailableClamp: Boolean = false,
) {
    /**
     * Template lists keep the index into the caller's template array so
     * per-template contributions (dry-run projections) can be attributed back.
     */
    private val templates = mutableListOf<IndexedValue<GoalTemplate>>()
    private val remainder = mutableListOf<IndexedValue<GoalTemplate>>()
    private val goals = mutableListOf<GoalTemplate>()
    private val priorities = LinkedHashSet<Int>()
    private var remainderWeight = 0.0
    var toBudgetAmount = 0L
        private set

    /** Amount budgeted per input-template index. */
    private val perTemplateContribution = HashMap<Int, Long>()
    private var fullAmount: Long? = null
    private var isLongGoal: Boolean? = null
    private var goalAmount: Long? = null
    private var fromLastMonth = 0L
    private var limitMet = false
    var limitExcess = 0L
        private set
    private var limitAmount = 0L
    private var limitCheck = false
    private var limitHold = false
    val previouslyBudgeted: Long = budgeted
    private val hideDecimal = sheet.hideFraction
    private val inputTemplateCount = templates.size

    data class Values(
        val budgeted: Long,
        val goal: Long?,
        val longGoal: Boolean?,
        /** Contribution per input template (0 for goals, limits, and templates that budgeted nothing). */
        val perTemplate: List<Long>,
    )

    init {
        templates.firstOrNull { it.type == GoalTemplate.Kind.Error || it.directive == GoalTemplate.Directive.Error }?.let { invalid ->
            throw GoalTemplateError("${invalid.line ?: "Template"}: ${invalid.error ?: "Invalid template syntax"}")
        }

        val lastMonth = BudgetMonthMath.subMonths(month, 1)
        var fromLastMonth = sheet.leftover(lastMonth, category.id)
        val carryover = sheet.carryover(lastMonth, category.id)
        if ((fromLastMonth < 0 && !carryover) || // overspend without rollover
            category.isIncome || // tracking income categories
            (sheet.isTracking && !carryover) // tracking regular categories
        ) {
            fromLastMonth = 0
        }
        this.fromLastMonth = fromLastMonth

        checkByAndScheduleAndSpend(templates, month, schedules)
        checkPercentage(templates, allCategories)

        for ((index, template) in templates.withIndex()) {
            if (template.directive == GoalTemplate.Directive.Template && template.type != GoalTemplate.Kind.Remainder && template.type != GoalTemplate.Kind.Limit) {
                this.templates += IndexedValue(index, template)
                template.priority?.let { priorities += it }
            } else if (template.directive == GoalTemplate.Directive.Template && template.type == GoalTemplate.Kind.Remainder) {
                remainder += IndexedValue(index, template)
                remainderWeight += template.weight ?: 1.0
            } else if (template.directive == GoalTemplate.Directive.Goal && template.type == GoalTemplate.Kind.Goal) {
                goals += template
            }
        }

        checkLimit(templates)
        checkSpend()
        checkGoal()
    }

    // Interface

    fun isGoalOnly(): Boolean = templates.isEmpty() && remainder.isEmpty() && goals.isNotEmpty()

    fun getPriorities(): List<Int> = priorities.toList()

    fun hasRemainder(): Boolean = remainderWeight > 0 && !limitMet

    fun getRemainderWeight(): Double = remainderWeight

    fun runTemplatesForPriority(priority: Int, budgetAvail: Long, availStart: Long): Long {
        if (priority !in priorities || limitMet) return 0

        val priorityTemplates = templates.filter { it.value.directive == GoalTemplate.Directive.Template && it.value.priority == priority }
        var available = budgetAvail
        var toBudget = 0L
        var byFlag = false
        var scheduleFlag = false
        // Per-template budgets for this priority pass, in iteration order —
        // the batched by/schedule totals land on the first sibling and are
        // redistributed below, mirroring upstream's perTemplateLocal map.
        val perTemplateLocal = mutableListOf<LongArray>()
        var byPerTemplate: Map<Int, Long> = emptyMap()
        var schedulePerTemplate: Map<Int, Double> = emptyMap()

        for ((index, template) in priorityTemplates) {
            var newBudget = 0L
            when (template.type) {
                GoalTemplate.Kind.Simple -> newBudget = runSimple(template)
                GoalTemplate.Kind.Refill -> newBudget = limitAmount - fromLastMonth
                GoalTemplate.Kind.Copy -> newBudget = runCopy(template)
                GoalTemplate.Kind.Periodic -> newBudget = runPeriodic(template)
                GoalTemplate.Kind.Spend -> newBudget = runSpend(template)
                GoalTemplate.Kind.Percentage -> newBudget = runPercentage(template, availStart)
                GoalTemplate.Kind.By -> {
                    // All `by` templates in the priority run as one batch.
                    if (!byFlag) {
                        val result = runBy()
                        newBudget = result.first
                        byPerTemplate = result.second
                    }
                    byFlag = true
                }
                GoalTemplate.Kind.Schedule -> if (!scheduleFlag) {
                    val lastMonth = BudgetMonthMath.subMonths(month, 1)
                    val result = GoalTemplateSchedules.run(
                        templates = priorityTemplates,
                        currentMonth = month,
                        balance = fromLastMonth + toBudget,
                        lastMonthBalance = fromLastMonth,
                        toBudget = toBudget,
                        lastMonthGoal = sheet.goal(lastMonth, category.id),
                        categoryIsIncome = category.isIncome,
                        isTracking = sheet.isTracking,
                        schedules = schedules,
                    )
                    // The schedule run returns the whole to-budget figure, so
                    // strip what earlier templates already contributed.
                    newBudget = result.first - toBudget
                    schedulePerTemplate = result.second
                    scheduleFlag = true
                }
                GoalTemplate.Kind.Average -> newBudget = runAverage(template)
                else -> Unit
            }
            available -= newBudget
            toBudget += newBudget
            perTemplateLocal += longArrayOf(index.toLong(), newBudget)
        }

        // Split the batched by/schedule totals across their siblings by each
        // template's own need (upstream redistributeBatch).
        redistributeBatch(
            perTemplateLocal,
            priorityTemplates.filter { it.value.type == GoalTemplate.Kind.By }.map { it.index },
        ) { max(0L, byPerTemplate[it] ?: 0).toDouble() }
        redistributeBatch(
            perTemplateLocal,
            priorityTemplates.filter { it.value.type == GoalTemplate.Kind.Schedule }.map { it.index },
        ) { max(0.0, schedulePerTemplate[it] ?: 0.0) }

        var scale = 1.0

        // Limit cap for the running total (balance carried in counts).
        if (limitCheck && toBudget + toBudgetAmount + fromLastMonth >= limitAmount) {
            val original = toBudget
            toBudget = limitAmount - toBudgetAmount - fromLastMonth
            limitMet = true
            available = available + original - toBudget
            if (original > 0) scale *= toBudget.toDouble() / original.toDouble()
        }

        if (hideDecimal) {
            // Track the rounding delta so per-row contributions sum to the
            // engine's actual budgeted amount.
            val preRound = toBudget
            toBudget = removeFraction(toBudget)
            if (preRound != 0L) scale *= toBudget.toDouble() / preRound.toDouble()
        }

        // Don't overbudget at a positive priority unless this is an income
        // category (which credits rather than spends the pool). Dry runs skip
        // the clamp so future months show the templates' intended amount.
        if (priority > 0 && available < 0 && !category.isIncome && !skipAvailableClamp) {
            fullAmount = (fullAmount ?: 0) + toBudget
            val adjusted = max(0, toBudget + available)
            if (toBudget > 0) scale *= adjusted.toDouble() / toBudget.toDouble()
            toBudget = adjusted
            toBudgetAmount += toBudget
        } else {
            fullAmount = (fullAmount ?: 0) + toBudget
            toBudgetAmount += toBudget
        }

        // Distribute the priority's final budget across its templates. The
        // limit branch can produce a negative scale when the carried balance
        // already exceeds the cap; floor at 0 so projections never go
        // negative. The last entry takes the residual so shares sum exactly.
        val perRowScale = max(0.0, scale)
        var remaining = max(0, toBudget)
        for ((position, entry) in perTemplateLocal.withIndex()) {
            val isLast = position == perTemplateLocal.size - 1
            val share = if (isLast) remaining else max(0, min(remaining, BudgetMonthMath.jsRound(entry[1].toDouble() * perRowScale)))
            perTemplateContribution[entry[0].toInt()] = (perTemplateContribution[entry[0].toInt()] ?: 0) + share
            remaining -= share
        }
        return if (category.isIncome) -toBudget else toBudget
    }

    /**
     * Port of upstream `redistributeBatch`: zero the siblings' local values
     * and re-split their total by weight, last sibling absorbing rounding.
     */
    private fun redistributeBatch(perTemplateLocal: MutableList<LongArray>, siblings: List<Int>, weightOf: (Int) -> Double) {
        if (siblings.size < 2) return
        val siblingSet = siblings.toSet()
        var total = 0L
        for (entry in perTemplateLocal) {
            if (entry[0].toInt() in siblingSet) {
                total += entry[1]
                entry[1] = 0
            }
        }
        if (total == 0L) return

        val totalWeight = siblings.sumOf { weightOf(it) }
        val shares = HashMap<Int, Long>()
        var remaining = total
        for ((position, index) in siblings.withIndex()) {
            val isLast = position == siblings.size - 1
            val share = when {
                isLast -> remaining
                totalWeight > 0 -> BudgetMonthMath.jsRound(total.toDouble() * weightOf(index) / totalWeight)
                // Equal split fallback when no weights are usable.
                else -> BudgetMonthMath.jsRound(total.toDouble() / siblings.size)
            }
            val allocated = max(0, min(share, remaining))
            shares[index] = allocated
            remaining -= allocated
        }
        for (entry in perTemplateLocal) {
            shares[entry[0].toInt()]?.let { entry[1] = it }
        }
    }

    fun runRemainder(budgetAvail: Long, perWeight: Double): Long {
        if (remainder.isEmpty()) return 0
        var toBudget = BudgetMonthMath.jsRound(remainderWeight * perWeight)

        var smallest = 1L
        if (hideDecimal) {
            toBudget = removeFraction(toBudget)
            smallest = 100
        }

        // Absorb rounding overshoot and sub-cent leftovers.
        if (toBudget > budgetAvail || budgetAvail - toBudget <= smallest) toBudget = budgetAvail

        if (limitCheck && toBudget + toBudgetAmount + fromLastMonth >= limitAmount) {
            toBudget = limitAmount - toBudgetAmount - fromLastMonth
            limitMet = true
        }

        // Attribute the pass across the remainder templates by weight, the
        // last one absorbing rounding (upstream runRemainder's share loop).
        if (toBudget > 0 && remainderWeight > 0) {
            var remaining = toBudget
            for ((position, entry) in remainder.withIndex()) {
                val isLast = position == remainder.size - 1
                val share = if (isLast) remaining else BudgetMonthMath.jsRound(toBudget.toDouble() * (entry.value.weight ?: 1.0) / remainderWeight)
                val allocated = max(0, min(share, remaining))
                perTemplateContribution[entry.index] = (perTemplateContribution[entry.index] ?: 0) + allocated
                remaining -= allocated
            }
        }

        toBudgetAmount += toBudget
        return toBudget
    }

    fun getValues(): Values {
        runGoal()
        val perTemplate = MutableList(inputTemplateCount) { 0L }
        for ((index, contribution) in perTemplateContribution) {
            if (index in 0 until inputTemplateCount) perTemplate[index] = contribution
        }
        return Values(toBudgetAmount, goalAmount, isLongGoal, perTemplate)
    }

    private fun runGoal() {
        val goal = goals.firstOrNull()
        if (goal != null) {
            if (isGoalOnly()) toBudgetAmount = previouslyBudgeted
            isLongGoal = true
            goalAmount = BudgetMonthMath.amountToInteger(goal.amount ?: 0.0)
            return
        }
        goalAmount = fullAmount
    }

    // Validation

    private fun checkLimit(templates: List<GoalTemplate>) {
        for (template in templates) {
            if (template.type != GoalTemplate.Kind.Simple && template.type != GoalTemplate.Kind.Periodic &&
                template.type != GoalTemplate.Kind.Limit && template.type != GoalTemplate.Kind.Remainder
            ) {
                continue
            }
            // limit-type templates carry the limit itself; the others may have an attached one.
            val limit = template.limit ?: continue
            if (limitCheck) throw GoalTemplateError("Only one `up to` allowed per category")

            when (limit.period) {
                GoalTemplate.LimitPeriod.Daily -> {
                    val numDays = BudgetMonthMath.daysInMonth(month)
                    limitAmount += BudgetMonthMath.amountToInteger(limit.amount) * numDays
                }
                GoalTemplate.LimitPeriod.Weekly -> {
                    // Also rejects unparseable dates: addWeeks returns them
                    // unchanged, so the walk below would never terminate.
                    val start = limit.start
                    if (start == null || BudgetMonthMath.day(start) == null) {
                        throw GoalTemplateError("Weekly limit requires a start date (YYYY-MM-DD)")
                    }
                    val nextMonth = BudgetMonthMath.nextMonth(month)
                    var week: String = start
                    val baseLimit = BudgetMonthMath.amountToInteger(limit.amount)
                    while (week < nextMonth) {
                        if (week >= month) limitAmount += baseLimit
                        week = BudgetMonthMath.addWeeks(week, 1)
                    }
                }
                GoalTemplate.LimitPeriod.Monthly -> limitAmount = BudgetMonthMath.amountToInteger(limit.amount)
            }

            limitCheck = true
            limitHold = limit.hold
            // Already over the cap: release (or hold) the excess.
            if (fromLastMonth >= limitAmount) {
                limitMet = true
                if (limitHold) {
                    limitExcess = 0
                    toBudgetAmount = 0
                    fullAmount = 0
                } else {
                    limitExcess = fromLastMonth - limitAmount
                    toBudgetAmount = -limitExcess
                    fullAmount = -limitExcess
                }
            }
        }
    }

    private fun checkSpend() {
        if (templates.count { it.value.type == GoalTemplate.Kind.Spend } > 1) {
            throw GoalTemplateError("Only one spend template is allowed per category")
        }
    }

    private fun checkGoal() {
        if (goals.size > 1) throw GoalTemplateError("Only one #goal is allowed per category")
    }

    private fun removeFraction(amount: Long): Long = BudgetMonthMath.jsRound(amount.toDouble() / 100) * 100

    // Processors

    private fun runSimple(template: GoalTemplate): Long =
        template.monthly?.let { BudgetMonthMath.amountToInteger(it) } ?: (limitAmount - fromLastMonth)

    private fun runCopy(template: GoalTemplate): Long =
        sheet.budgeted(BudgetMonthMath.subMonths(month, template.lookBack ?: 0), category.id)

    private fun runPeriodic(template: GoalTemplate): Long {
        val period = template.period ?: return 0
        var toBudget = 0L
        val amount = BudgetMonthMath.amountToInteger(template.amount ?: 0.0)
        val numPeriods = period.amount
        var date = template.starting?.takeIf { it.isNotEmpty() } ?: BudgetMonthMath.firstDayOfMonth(month)
        // A zero period or an unparseable start date would make the date walks
        // below never advance (the shift helpers return malformed input
        // unchanged), hanging the app.
        if (numPeriods <= 0 || BudgetMonthMath.day(date) == null) return 0

        // `addMonths` collapses a day string to "yyyy-MM" exactly like
        // upstream's month utils — the lexicographic compares below rely on it.
        val shift: (String, Int) -> String = when (period.period) {
            GoalTemplate.PeriodUnit.Day -> BudgetMonthMath::addDays
            GoalTemplate.PeriodUnit.Week -> BudgetMonthMath::addWeeks
            GoalTemplate.PeriodUnit.Month -> BudgetMonthMath::addMonths
            GoalTemplate.PeriodUnit.Year -> { value, n -> BudgetMonthMath.addMonths(value, n * 12) }
        }
        while (month > date) date = shift(date, numPeriods)
        if (BudgetMonthMath.differenceInCalendarMonths(month, date) < 0) return 0 // nothing due this month
        val nextMonth = BudgetMonthMath.addMonths(month, 1)
        while (date < nextMonth) {
            toBudget += amount
            date = shift(date, numPeriods)
        }
        return toBudget
    }

    private fun runSpend(template: GoalTemplate): Long {
        var fromMonth = template.from ?: ""
        var toMonth = template.month ?: ""
        var alreadyBudgeted = fromLastMonth
        var firstMonth = true

        val repeatInterval = repeatInterval(template)
        var monthsAway = BudgetMonthMath.differenceInCalendarMonths(toMonth, month)
        if (repeatInterval != null && repeatInterval > 0 && monthsAway < 0) {
            while (monthsAway < 0) {
                toMonth = BudgetMonthMath.addMonths(toMonth, repeatInterval)
                fromMonth = BudgetMonthMath.addMonths(fromMonth, repeatInterval)
                monthsAway = BudgetMonthMath.differenceInCalendarMonths(toMonth, month)
            }
        }

        var walker = fromMonth
        while (BudgetMonthMath.differenceInCalendarMonths(month, walker) > 0) {
            if (firstMonth) {
                val spent = sheet.spent(walker, category.id)
                val balance = sheet.leftover(walker, category.id)
                alreadyBudgeted = balance - spent
                firstMonth = false
            } else {
                alreadyBudgeted += sheet.budgeted(walker, category.id)
            }
            walker = BudgetMonthMath.addMonths(walker, 1)
        }

        val numMonths = BudgetMonthMath.differenceInCalendarMonths(toMonth, month)
        val target = BudgetMonthMath.amountToInteger(template.amount ?: 0.0)
        if (numMonths < 0) return 0
        return BudgetMonthMath.jsRound((target - alreadyBudgeted).toDouble() / (numMonths + 1))
    }

    private fun runPercentage(template: GoalTemplate, availableFunds: Long): Long {
        val percent = template.percent ?: 0.0
        val source = (template.category ?: "").lowercase()
        val sheetMonth = if (template.previous == true) BudgetMonthMath.subMonths(month, 1) else month
        val monthlyIncome = when (source) {
            "all income" -> sheet.totalIncome(sheetMonth)
            "available funds" -> availableFunds
            else -> {
                val incomeCategory = allCategories.firstOrNull {
                    it.isIncome && (it.id == template.category || it.name.lowercase() == source)
                } ?: throw GoalTemplateError("Income category \"${template.category ?: ""}\" not found for percentage template")
                sheet.spent(sheetMonth, incomeCategory.id)
            }
        }
        return max(0, BudgetMonthMath.jsRound(monthlyIncome.toDouble() * percent / 100))
    }

    private fun runAverage(template: GoalTemplate): Long {
        var average = categoryAverage(template.numMonths ?: 0).toDouble()
        // Sheet activity is cost (negative); budget the positive amount.
        if (average < 0) average *= -1
        val adjustment = template.adjustment
        when (template.adjustmentType) {
            GoalTemplate.AdjustmentType.Percent -> if (adjustment != null) average *= 1 + adjustment / 100
            GoalTemplate.AdjustmentType.Fixed -> if (adjustment != null) average += BudgetMonthMath.amountToInteger(adjustment).toDouble()
            null -> Unit
        }
        return BudgetMonthMath.jsRound(average)
    }

    /**
     * Port of `getCategoryAverage` + `getAverageMonths`: average the activity
     * of up to [maxMonths] months ending the month before this one (never
     * beyond last month of real time), stopping at the category's first
     * month of activity.
     */
    private fun categoryAverage(maxMonths: Int): Long {
        var startMonth = BudgetMonthMath.prevMonth(month)
        if (startMonth >= currentMonth) startMonth = BudgetMonthMath.prevMonth(currentMonth)
        val firstActivity = sheet.firstActivityMonth[category.id]

        val months = mutableListOf<String>()
        var walker = startMonth
        for (i in 0 until max(0, maxMonths)) {
            if (firstActivity != null && BudgetMonthMath.monthInt(walker) < firstActivity) break
            months += walker
            walker = BudgetMonthMath.prevMonth(walker)
        }
        if (months.isEmpty()) return 0
        val sum = months.sumOf { sheet.spent(it, category.id) }
        return BudgetMonthMath.jsRound(sum.toDouble() / months.size)
    }

    private fun runBy(): Pair<Long, Map<Int, Long>> {
        val byTemplates = templates.filter { it.value.type == GoalTemplate.Kind.By }
        val savedInfo = mutableListOf<Pair<Int, Int?>>()
        var shortestMonths: Int? = null

        for ((_, template) in byTemplates) {
            var targetMonth = template.month ?: ""
            val period = repeatInterval(template)
            var numMonths = BudgetMonthMath.differenceInCalendarMonths(targetMonth, month)
            if (period != null && period > 0) {
                while (numMonths < 0) {
                    targetMonth = BudgetMonthMath.addMonths(targetMonth, period)
                    numMonths = BudgetMonthMath.differenceInCalendarMonths(targetMonth, month)
                }
            }
            savedInfo += numMonths to period
            if (shortestMonths == null || numMonths < shortestMonths) shortestMonths = numMonths
        }

        val shortNumMonths = shortestMonths ?: 0
        var totalNeeded = 0L
        val perTemplateNeed = HashMap<Int, Long>()
        for ((position, entry) in byTemplates.withIndex()) {
            val (numMonths, period) = savedInfo[position]
            val target = BudgetMonthMath.amountToInteger(entry.value.amount ?: 0.0)
            val amount = when {
                // Back-interpolate what the longer-window template needs during the short window.
                numMonths > shortNumMonths && period != null && period > 0 ->
                    BudgetMonthMath.jsRound(target.toDouble() / period * (period - numMonths + shortNumMonths))
                numMonths > shortNumMonths ->
                    BudgetMonthMath.jsRound(target.toDouble() / (numMonths + 1) * (shortNumMonths + 1))
                else -> target
            }
            perTemplateNeed[entry.index] = amount
            totalNeeded += amount
        }
        val toBudget = BudgetMonthMath.jsRound((totalNeeded - fromLastMonth).toDouble() / (shortNumMonths + 1))
        return toBudget to perTemplateNeed
    }

    companion object {
        fun checkByAndScheduleAndSpend(templates: List<GoalTemplate>, month: String, schedules: List<GoalScheduleInfo>) {
            val byAndSchedule = templates.filter { it.type == GoalTemplate.Kind.Schedule || it.type == GoalTemplate.Kind.By }
            if (byAndSchedule.isEmpty()) return

            val scheduleIds = schedules.map { it.id }.toSet()
            val scheduleNames = schedules.mapNotNull { it.name?.trim { c -> c == ' ' || c == '\t' } }.filter { it.isNotEmpty() }.toSet()
            for (template in templates) {
                if (template.type != GoalTemplate.Kind.Schedule) continue
                val scheduleId = template.scheduleId
                val name = template.name
                if (scheduleId != null) {
                    if (scheduleId !in scheduleIds) throw GoalTemplateError("Schedule ${template.name ?: scheduleId} does not exist")
                } else if (name != null) {
                    val trimmed = name.trim { it == ' ' || it == '\t' }
                    if (trimmed !in scheduleNames) throw GoalTemplateError("Schedule $trimmed does not exist")
                } else {
                    throw GoalTemplateError("Schedule template has no scheduleId or name")
                }
            }

            val lowestPriority = byAndSchedule.mapNotNull { it.priority }.minOrNull() ?: 0
            if (byAndSchedule.any { it.priority != lowestPriority }) {
                throw GoalTemplateError(
                    "Schedule and By templates must be the same priority level. Fix by setting all Schedule and By templates to priority level $lowestPriority",
                )
            }

            for (template in templates) {
                if (template.type != GoalTemplate.Kind.By && template.type != GoalTemplate.Kind.Spend) continue
                val range = BudgetMonthMath.differenceInCalendarMonths(template.month ?: "", month)
                if (range < 0 && (repeatInterval(template) ?: 0) <= 0) {
                    throw GoalTemplateError("Target month has passed, remove or update the target month")
                }
            }
        }

        private fun repeatInterval(template: GoalTemplate): Int? {
            if (template.annual != true) return template.repeatCount
            val count = template.repeatCount ?: 1
            return (if (count == 0) 1 else count) * 12
        }

        fun checkPercentage(templates: List<GoalTemplate>, categories: List<GoalTemplateCategory>) {
            val percentageTemplates = templates.filter { it.type == GoalTemplate.Kind.Percentage }
            if (percentageTemplates.isEmpty()) return
            val incomeCategories = categories.filter { it.isIncome }
            val names = incomeCategories.map { it.name.lowercase() }.toSet()
            val ids = incomeCategories.map { it.id }.toSet()
            val specialSources = setOf("all income", "available funds")
            for (template in percentageTemplates) {
                val raw = template.category ?: ""
                val lowered = raw.lowercase()
                if (lowered !in specialSources && lowered !in names && raw !in ids) {
                    throw GoalTemplateError("Category \"$raw\" is not found in available income categories")
                }
            }
        }
    }
}

/**
 * Port of loot-core `goal-template.ts` `computeTemplates`/`processTemplate`:
 * run every category's templates for one month and produce the budget and
 * goal writes.
 */
object GoalTemplateEngine {
    data class GoalWrite(val category: String, val goal: Long?, val longGoal: Boolean)

    data class BudgetWrite(val category: String, val amount: Long)

    sealed interface RunResult {
        data class UpToDate(val goalResets: List<GoalWrite>) : RunResult
        data class Applied(val count: Int, val budgets: List<BudgetWrite>, val goals: List<GoalWrite>) : RunResult
        data class Errors(val messages: List<String>) : RunResult
    }

    fun distributeRemainder(contexts: List<GoalTemplateContext>, availBudget: Long): Long {
        var avail = availBudget
        var remainderContexts = contexts.filter { it.hasRemainder() }
        while (avail > 0 && remainderContexts.isNotEmpty()) {
            val remainderWeight = remainderContexts.sumOf { it.getRemainderWeight() }
            val perWeight = avail.toDouble() / remainderWeight
            val beforePass = avail
            for (context in remainderContexts) avail -= context.runRemainder(avail, perWeight)
            if (avail == beforePass) break
            remainderContexts = contexts.filter { it.hasRemainder() }
        }
        return avail
    }

    /**
     * @param categories the categories to process (already filtered the way
     *   the caller wants: month-apply passes visible categories — income only
     *   on tracking budgets — while single-category apply passes just that one).
     * @param allCategories every live category, for percentage source lookups.
     */
    fun run(
        month: String,
        force: Boolean,
        categoryTemplates: Map<String, List<GoalTemplate>>,
        categories: List<GoalTemplateCategory>,
        allCategories: List<GoalTemplateCategory>,
        schedules: List<GoalScheduleInfo>,
        sheet: GoalTemplateSheet,
        currentMonth: String = BudgetMonthMath.currentMonth(),
    ): RunResult {
        val contexts = mutableListOf<GoalTemplateContext>()
        var availBudget = sheet.availableStart
        val prioritySet = HashSet<Int>()
        val errors = mutableListOf<String>()
        val orphanGoals = mutableListOf<GoalWrite>()
        val monthInt = BudgetMonthMath.monthInt(month)

        for (category in categories) {
            val templates = categoryTemplates[category.id]
            val budgeted = sheet.budgeted(month, category.id)
            if (templates != null && (budgeted == 0L || force)) {
                try {
                    val context = GoalTemplateContext(templates, category, month, budgeted, sheet, schedules, allCategories, currentMonth)
                    // Funds not managed by templates stay out of the pool.
                    if (!context.isGoalOnly()) availBudget += budgeted
                    availBudget += context.limitExcess
                    prioritySet += context.getPriorities()
                    contexts += context
                } catch (e: Exception) {
                    errors += "${category.name}: ${e.message}"
                }
            } else if (templates == null) {
                // Orphaned goal: the templates were removed, so clear the
                // stored goal. ponytail: upstream emits this reset for every
                // template-less category on every apply; writing only where a
                // goal value actually exists converges to the same state
                // without the message churn.
                if (GoalTemplateSheet.MonthCat(monthInt, category.id) in sheet.goalRows) {
                    orphanGoals += GoalWrite(category.id, null, false)
                }
            }
        }
        if (errors.isNotEmpty()) return RunResult.Errors(errors)

        // Run each priority level across every category before moving on.
        for (priority in prioritySet.sorted()) {
            val availStart = availBudget
            for (context in contexts) {
                try {
                    availBudget -= context.runTemplatesForPriority(priority, availBudget, availStart)
                } catch (e: Exception) {
                    errors += "${context.category.name}: ${e.message}"
                }
            }
            if (errors.isNotEmpty()) return RunResult.Errors(errors)
        }

        distributeRemainder(contexts, availBudget)

        if (contexts.isEmpty()) return RunResult.UpToDate(orphanGoals)

        val budgets = mutableListOf<BudgetWrite>()
        val goals = orphanGoals.toMutableList()
        for (context in contexts) {
            val values = context.getValues()
            budgets += BudgetWrite(context.category.id, values.budgeted)
            goals += GoalWrite(context.category.id, values.goal, values.longGoal == true)
        }
        return RunResult.Applied(contexts.size, budgets, goals)
    }

    /**
     * Port of upstream `dryRunCategoryTemplate`: how much would these templates
     * budget for one category this month, and how much does each template
     * contribute? Skips the available-funds clamp so future months (where To
     * Budget is empty) show the templates' intended amounts. Validation errors
     * return zeros, matching upstream.
     */
    fun dryRun(
        month: String,
        category: GoalTemplateCategory,
        templates: List<GoalTemplate>,
        allCategories: List<GoalTemplateCategory>,
        schedules: List<GoalScheduleInfo>,
        sheet: GoalTemplateSheet,
        currentMonth: String = BudgetMonthMath.currentMonth(),
    ): Pair<Long, List<Long>> {
        val zeros = List(templates.size) { 0L }
        if (templates.isEmpty()) return 0L to zeros
        return try {
            val budgeted = sheet.budgeted(month, category.id)
            val context = GoalTemplateContext(templates, category, month, budgeted, sheet, schedules, allCategories, currentMonth, skipAvailableClamp = true)
            var availBudget = sheet.availableStart
            if (!context.isGoalOnly()) availBudget += budgeted
            availBudget += context.limitExcess
            for (priority in context.getPriorities().sorted()) {
                val availStart = availBudget
                availBudget -= context.runTemplatesForPriority(priority, availBudget, availStart)
            }
            distributeRemainder(listOf(context), availBudget)
            val values = context.getValues()
            values.budgeted to values.perTemplate
        } catch (_: Exception) {
            0L to zeros
        }
    }
}
