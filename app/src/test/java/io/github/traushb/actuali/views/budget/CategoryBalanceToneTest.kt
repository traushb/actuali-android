package io.github.traushb.actuali.views.budget

import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.views.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

/** The balance pill's tone: the web's `makeBalanceAmountStyle`, with gray at zero. */
class CategoryBalanceToneTest {
    private fun category(budgeted: Long = 0, available: Long, goal: Long? = null) =
        CategoryBudget("2026-10", "category", "Groceries", "group", "Essentials", 1.0, 1.0, budgeted, spent = 0, available, carryover = 0, goal = goal)

    @Test
    fun overspendingIsRedWhateverTheGoal() {
        assertEquals(StatusTone.Negative, categoryBalanceTone(category(available = -1), goalsEnabled = false))
        assertEquals(StatusTone.Negative, categoryBalanceTone(category(budgeted = 500, available = -1, goal = 100), goalsEnabled = true))
    }

    @Test
    fun withoutGoalsMoneyLeftIsGreenAndNoneIsGray() {
        assertEquals(StatusTone.Positive, categoryBalanceTone(category(available = 1), goalsEnabled = false))
        assertEquals(StatusTone.Neutral, categoryBalanceTone(category(available = 0), goalsEnabled = false))
    }

    /** A template goal measures this month's budgeted amount: short of it is yellow, met is green — even at a zero balance. */
    @Test
    fun goalsColorByFundingOnlyWhileTemplatesAreOn() {
        val underfunded = category(budgeted = 5_000, available = 5_000, goal = 10_000)
        assertEquals(StatusTone.Warning, categoryBalanceTone(underfunded, goalsEnabled = true))
        assertEquals(StatusTone.Positive, categoryBalanceTone(underfunded, goalsEnabled = false))
        assertEquals(StatusTone.Positive, categoryBalanceTone(category(budgeted = 10_000, available = 0, goal = 10_000), goalsEnabled = true))
    }
}
