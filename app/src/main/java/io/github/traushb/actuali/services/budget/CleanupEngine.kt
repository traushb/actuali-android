package io.github.traushb.actuali.services.budget

import kotlin.math.max
import kotlin.math.min

/**
 * Pure port of Actual's end-of-month cleanup ordering. Budget writes update
 * the in-memory sheet as they go, matching the web spreadsheet recalculation.
 */
object CleanupEngine {
    data class Category(val id: String, val name: String, val isIncome: Boolean = false, val cleanup: List<CleanupTemplate>)

    sealed interface Warning {
        data class NoAvailableFunds(val category: String) : Warning
        data class NoMatchingSinks(val group: String) : Warning
        data object NoGlobalFunds : Warning
    }

    sealed interface Notification {
        data class Applied(val sourceCount: Int, val sinkCount: Int) : Notification
        data object UpToDate : Notification
        data class Warnings(val warnings: List<Warning>) : Notification
    }

    data class Result(
        val budgets: List<GoalTemplateEngine.BudgetWrite>,
        val goals: List<GoalTemplateEngine.GoalWrite>,
        val notification: Notification,
    )

    private class SheetState(val month: String, val sheet: GoalTemplateSheet) {
        var available = sheet.availableStart
        private val currentBudgets = HashMap<String, Long>()
        private val currentLeftovers = HashMap<String, Long>()
        val writes = mutableListOf<GoalTemplateEngine.BudgetWrite>()
        private val writeIndices = HashMap<String, Int>()

        fun budget(category: String): Long = currentBudgets[category] ?: sheet.budgeted(month, category)

        fun leftover(category: String): Long = currentLeftovers[category] ?: sheet.leftover(month, category)

        fun setBudget(amount: Long, category: String) {
            val delta = amount - budget(category)
            currentLeftovers[category] = leftover(category) + delta
            currentBudgets[category] = amount
            available -= delta
            if (delta == 0L) return
            val write = GoalTemplateEngine.BudgetWrite(category, amount)
            val index = writeIndices[category]
            if (index != null) {
                writes[index] = write
            } else {
                writeIndices[category] = writes.size
                writes += write
            }
        }
    }

    fun run(month: String, categories: List<Category>, groupNames: Map<String, String>, sheet: GoalTemplateSheet): Result {
        val state = SheetState(month, sheet)
        val goals = mutableListOf<GoalTemplateEngine.GoalWrite>()
        val warnings = mutableListOf<Warning>()

        val groupSources = categories.flatMap { category ->
            category.cleanup.mapNotNull { row -> if (row.role == CleanupTemplate.Role.Source) row.groupId?.let { category to it } else null }
        }
        val groupSinks = categories.flatMap { category ->
            category.cleanup.mapNotNull { row ->
                if (row.role == CleanupTemplate.Role.Sink) row.groupId?.let { Triple(category, it, validWeight(row.weight)) } else null
            }
        }
        val groupOverspending = categories.flatMap { category ->
            category.cleanup.mapNotNull { row -> if (row.role == CleanupTemplate.Role.Overspend) row.groupId?.let { category to it } else null }
        }

        val processedGroups = HashSet<String>()
        for ((_, groupId) in groupSources) {
            if (!processedGroups.add(groupId)) continue
            val sources = groupSources.filter { it.second == groupId }
            val sinks = groupSinks.filter { it.second == groupId }
            val overspending = groupOverspending.filter { it.second == groupId }
            if (sinks.isEmpty() && overspending.isEmpty()) {
                warnings += Warning.NoMatchingSinks(groupNames[groupId] ?: groupId)
                continue
            }

            var pool = 0L
            for ((category, _) in sources) {
                val balance = state.leftover(category.id)
                if (balance < 0) {
                    warnings += Warning.NoAvailableFunds(category.name)
                    continue
                }
                state.setBudget(state.budget(category.id) - balance, category.id)
                pool += balance
            }

            for ((category, _) in overspending) {
                if (pool <= 0) continue
                val balance = state.leftover(category.id)
                if (balance >= 0 || sheet.carryover(month, category.id)) continue
                val amount = min(-balance, pool)
                state.setBudget(state.budget(category.id) + amount, category.id)
                pool -= amount
            }

            val totalWeight = sinks.sumOf { it.third }
            var remainingPool = pool
            for ((index, sink) in sinks.withIndex()) {
                if (remainingPool <= 0) continue
                val share = if (index == sinks.size - 1) {
                    remainingPool
                } else {
                    min(BudgetMonthMath.jsRound(sink.third / totalWeight * pool), remainingPool)
                }
                state.setBudget(state.budget(sink.first.id) + share, sink.first.id)
                remainingPool -= share
            }
        }

        val globalSinks = mutableListOf<Pair<Category, Double>>()
        var sourceCount = 0
        for (category in categories) {
            if (category.cleanup.any { it.role == CleanupTemplate.Role.Source && it.groupId == null }) {
                val balance = state.leftover(category.id)
                if (balance >= 0) {
                    val amount = state.budget(category.id) - balance
                    state.setBudget(amount, category.id)
                    goals += GoalTemplateEngine.GoalWrite(category.id, amount, false)
                    sourceCount += 1
                } else {
                    warnings += Warning.NoAvailableFunds(category.name)
                }
            }
            category.cleanup.firstOrNull { it.role == CleanupTemplate.Role.Sink && it.groupId == null }?.let {
                globalSinks += category to validWeight(it.weight)
            }
        }

        for (category in categories) {
            if (state.available <= 0) break
            val balance = state.leftover(category.id)
            if (balance >= 0 || category.isIncome || sheet.carryover(month, category.id)) continue
            state.setBudget(state.budget(category.id) + min(-balance, state.available), category.id)
        }

        val budgetAvailable = state.available
        if (budgetAvailable < 0) warnings += Warning.NoGlobalFunds

        val totalWeight = globalSinks.sumOf { it.second }
        var remainingBudget = max(budgetAvailable, 0)
        for ((index, sink) in globalSinks.withIndex()) {
            if (remainingBudget <= 0) continue
            val share = if (index == globalSinks.size - 1) {
                remainingBudget
            } else {
                min(BudgetMonthMath.jsRound(sink.second / totalWeight * budgetAvailable), remainingBudget)
            }
            state.setBudget(state.budget(sink.first.id) + share, sink.first.id)
            remainingBudget -= share
        }

        val notification = when {
            warnings.isNotEmpty() -> Notification.Warnings(warnings)
            state.writes.isEmpty() && goals.isEmpty() -> Notification.UpToDate
            else -> Notification.Applied(sourceCount, globalSinks.size)
        }
        return Result(state.writes, goals, notification)
    }

    private fun validWeight(weight: Double): Double = if (weight.isFinite() && weight > 0) weight else 1.0
}
