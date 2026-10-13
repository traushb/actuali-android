package io.github.traushb.actuali.services.budgetstore

import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.EnvelopeBudgetSummary
import io.github.traushb.actuali.services.isValidHoldAmount
import io.github.traushb.actuali.services.shiftBudgetMonth
import io.github.traushb.actuali.views.budget.EnvelopeBudgetSummaryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetStoreEnvelopeBudgetSummaryTest {
    @Test
    fun budgetMonthsAcceptOnlyYearDashMonth() {
        assertTrue(EnvelopeBudgetSummary.isValidBudgetMonth("2026-09"))
        for (month in listOf("2026-9", "09-2026", "2026-13", "2026/09")) assertFalse(month, EnvelopeBudgetSummary.isValidBudgetMonth(month))
    }

    @Test
    fun januaryAndDecemberShiftsCrossTheYear() {
        assertEquals("2025-12", BudgetStore.shiftBudgetMonth("2026-01", -1))
        assertEquals("2027-01", BudgetStore.shiftBudgetMonth("2026-12", 1))
    }

    @Test
    fun summaryReconcilesFundsOverspendingBudgetedAndToBudget() {
        val summary = EnvelopeBudgetSummary.make(availableFunds = 1500, lastMonthOverspent = -200, budgeted = 800, toBudget = 100, manualBuffered = 0)
        assertEquals(EnvelopeBudgetSummary(1500, -200, 800, forNextMonth = 400, toBudget = 100, manualBuffered = 0, autoBuffered = 400), summary)
    }

    @Test
    fun manualBufferSuppressesTheInferredAutoBuffer() {
        val summary = EnvelopeBudgetSummary.make(availableFunds = 1500, lastMonthOverspent = 0, budgeted = 500, toBudget = 750, manualBuffered = 250)
        assertEquals(250L, summary.forNextMonth)
        assertEquals(250L, summary.manualBuffered)
        assertEquals(0L, summary.autoBuffered)
    }

    @Test
    fun negativeForNextMonthNeverCreatesAnAutoBuffer() {
        val summary = EnvelopeBudgetSummary.make(availableFunds = 100, lastMonthOverspent = -200, budgeted = 300, toBudget = 0, manualBuffered = 0)
        assertEquals(-400L, summary.forNextMonth)
        assertEquals(0L, summary.autoBuffered)
    }

    @Test
    fun zeroToBudgetCanStillCarryAManualBuffer() {
        val summary = EnvelopeBudgetSummary.make(availableFunds = 600, lastMonthOverspent = 0, budgeted = 500, toBudget = 0, manualBuffered = 100)
        assertEquals(100L, summary.forNextMonth)
        assertEquals(0L, summary.autoBuffered)
    }

    @Test
    fun positiveToBudgetWithoutABufferCanMoveOrHold() {
        val summary = EnvelopeBudgetSummary.make(availableFunds = 1000, lastMonthOverspent = 0, budgeted = 500, toBudget = 500, manualBuffered = 0)
        assertEquals(listOf(EnvelopeBudgetSummaryAction.MoveToCategory, EnvelopeBudgetSummaryAction.HoldForNextMonth), EnvelopeBudgetSummaryAction.available(summary))
    }

    @Test
    fun holdingRejectsAmountsAboveToBudgetInsteadOfClamping() {
        assertTrue(BudgetStore.isValidHoldAmount(300, toBudget = 300))
        assertTrue(BudgetStore.isValidHoldAmount(299, toBudget = 300))
        assertFalse(BudgetStore.isValidHoldAmount(301, toBudget = 300))
        assertFalse(BudgetStore.isValidHoldAmount(1, toBudget = 0))
    }

    @Test
    fun anAutomaticBufferCanBeDisabled() {
        val summary = EnvelopeBudgetSummary(1500, 0, 500, forNextMonth = 500, toBudget = 500, manualBuffered = 0, autoBuffered = 500)
        assertEquals(listOf(EnvelopeBudgetSummaryAction.MoveToCategory, EnvelopeBudgetSummaryAction.DisableAutoBuffer), EnvelopeBudgetSummaryAction.available(summary))
    }

    @Test
    fun aManualBufferOffersResetEvenWithZeroToBudget() {
        val summary = EnvelopeBudgetSummary(500, 0, 500, forNextMonth = 100, toBudget = 0, manualBuffered = 100, autoBuffered = 0)
        assertEquals(listOf(EnvelopeBudgetSummaryAction.ResetBuffer), EnvelopeBudgetSummaryAction.available(summary))
    }

    @Test
    fun aManualBufferWithNegativeToBudgetOffersCoverAndReset() {
        val summary = EnvelopeBudgetSummary(100, -200, 300, forNextMonth = 50, toBudget = -100, manualBuffered = 50, autoBuffered = 0)
        assertEquals(listOf(EnvelopeBudgetSummaryAction.CoverFromCategory, EnvelopeBudgetSummaryAction.ResetBuffer), EnvelopeBudgetSummaryAction.available(summary))
    }

    @Test
    fun positiveToBudgetWithAManualBufferCanHoldMoreOrReset() {
        val summary = EnvelopeBudgetSummary(1500, 0, 500, forNextMonth = 250, toBudget = 500, manualBuffered = 250, autoBuffered = 0)
        assertEquals(
            listOf(EnvelopeBudgetSummaryAction.MoveToCategory, EnvelopeBudgetSummaryAction.HoldForNextMonth, EnvelopeBudgetSummaryAction.ResetBuffer),
            EnvelopeBudgetSummaryAction.available(summary),
        )
    }
}
