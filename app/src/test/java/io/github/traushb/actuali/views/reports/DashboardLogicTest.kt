package io.github.traushb.actuali.views.reports

import io.github.traushb.actuali.services.reports.SpendingMeta
import io.github.traushb.actuali.services.reports.engines.BalanceForecastMeta
import io.github.traushb.actuali.support.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DashboardLogicTest {
    /** WebUI spending-spreadsheet.ts drops off-budget accounts and income categories; uncategorized spending stays. */
    @Test
    fun spendingScopeDropsOffBudgetAndIncome() {
        val kept = tx(20_260_501, -1000, id = "kept", categoryId = "food")
        val uncategorized = tx(20_260_502, -500, id = "uncategorized")
        val offBudget = tx(20_260_503, -700, accountId = "investments", categoryId = "food")
        val income = tx(20_260_504, 9000, categoryId = "salary")
        val scoped = DashboardLogic.spendingScope(listOf(kept, uncategorized, offBudget, income), offBudget = setOf("investments"), income = setOf("salary"))
        assertEquals(listOf("kept", "uncategorized"), scoped.map { it.id })
    }

    @Test
    fun forecastFallsBackToSchedulesOutsideTrackingBudgets() {
        val meta = BalanceForecastMeta(name = "Forecast", source = BalanceForecastMeta.Source.TrackingBudget)
        assertEquals(BalanceForecastMeta.Source.Schedules, DashboardLogic.forecastMeta(meta, isTracking = false)?.source)
        assertEquals("Forecast", DashboardLogic.forecastMeta(meta, isTracking = false)?.name)
    }

    @Test
    fun forecastKeepsTrackingSourceInTrackingBudgets() {
        val meta = BalanceForecastMeta(source = BalanceForecastMeta.Source.TrackingBudget)
        assertEquals(meta, DashboardLogic.forecastMeta(meta, isTracking = true))
    }

    @Test
    fun forecastLeavesOtherSourcesAlone() {
        assertNull(DashboardLogic.forecastMeta(null, isTracking = false))
        val schedules = BalanceForecastMeta(source = BalanceForecastMeta.Source.Schedules)
        assertEquals(schedules, DashboardLogic.forecastMeta(schedules, isTracking = false))
    }

    /** A null mode is single-month upstream (SpendingCard.tsx), compared against the prior month. */
    @Test
    fun comparisonLabelFollowsMode() {
        assertEquals("vs prior", DashboardLogic.comparisonLabel(null, Locale.US))
        assertEquals("vs 2026-03", DashboardLogic.comparisonLabel(SpendingMeta(mode = SpendingMeta.Mode.SingleMonth, compareTo = "2026-03"), Locale.US))
        assertEquals("vs budget", DashboardLogic.comparisonLabel(SpendingMeta(mode = SpendingMeta.Mode.Budget), Locale.US))
        assertEquals("vs avg", DashboardLogic.comparisonLabel(SpendingMeta(mode = SpendingMeta.Mode.Average), Locale.US))
    }
}
