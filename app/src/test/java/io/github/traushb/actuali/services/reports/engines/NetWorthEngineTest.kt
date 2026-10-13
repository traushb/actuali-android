package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.services.reports.NetWorthMeta
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.support.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetWorthEngineTest {
    private val asOf = LocalDate.of(2026, 5, 14)
    private val yearToDate = NetWorthMeta(timeFrame = WidgetTimeFrame(mode = WidgetTimeFrame.Mode.YearToDate), interval = NetWorthMeta.Interval.Monthly)

    @Test
    fun emptyTransactionsReturnsZeros() {
        val result = NetWorthEngine.compute(NetWorthMeta(interval = NetWorthMeta.Interval.Monthly), emptyList(), asOf)
        assertTrue(result.points.isNotEmpty())
        assertTrue(result.points.all { it.balanceCents == 0L })
    }

    @Test
    fun cumulativeBalanceAcrossMonths() {
        val transactions = listOf(tx(20_260_115, 10000), tx(20_260_215, 10000), tx(20_260_315, 10000))
        // Last point covers May 2026 — all 3 transactions counted.
        assertEquals(30000L, NetWorthEngine.compute(yearToDate, transactions, asOf).points.last().balanceCents)
    }

    @Test
    fun tombstonedTransactionsExcluded() {
        val transactions = listOf(tx(20_260_115, 10000), tx(20_260_215, 10000, tombstone = true))
        assertEquals(10000L, NetWorthEngine.compute(yearToDate, transactions, asOf).points.last().balanceCents)
    }

    /** For yearToDate from May 14 2026, expect 5 monthly points (Jan … May). */
    @Test
    fun monthlyIntervalProducesFivePointsForYearToDate() = assertEquals(5, NetWorthEngine.compute(yearToDate, emptyList(), asOf).points.size)

    /** The default dashboard's net worth card has no meta; upstream shows it the last six months, not one. */
    @Test
    fun widgetWithoutTimeFrameCoversLastSixMonths() {
        val points = NetWorthEngine.compute(null, listOf(tx(20_251_215, 10000), tx(20_260_415, 5000)), asOf).points
        assertEquals(6, points.size)
        assertEquals(LocalDate.of(2025, 12, 31), points.first().date)
        assertEquals(10000L, points.first().balanceCents)
        assertEquals(15000L, points.last().balanceCents)
    }
}
