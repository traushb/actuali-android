package io.github.traushb.actuali.views.reports

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.traushb.actuali.services.reports.engines.CustomReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomReportChartAccessibilityTest {
    private val amount = { units: Double -> "$%.2f".format(units) }

    @Test
    fun barsReadAsLabelAndAmount() {
        val rows = CustomReportChartAccessibility.rows(CustomReportData.Kind.Bars(listOf(CustomReportData.Bar("Food", 12.5)), signed = false), amount)
        assertEquals(listOf(CustomReportChartAccessibilityRow(null, "Food", "$12.50")), rows)
    }

    @Test
    fun stackedSeriesReadSeriesByInterval() {
        val stacked = CustomReportData.Stacked(listOf("Jan", "Feb"), listOf("Food", "Rent"), listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)))
        val rows = CustomReportChartAccessibility.rows(CustomReportData.Kind.StackedBars(stacked), amount)
        assertEquals(
            listOf("Food Jan $1.00", "Food Feb $2.00", "Rent Jan $3.00", "Rent Feb $4.00"),
            rows.map { "${it.series} ${it.label} ${it.value}" },
        )
    }

    /** Two rings read the groups first, then each category under its group's name. */
    @Test
    fun twoRingDonutReadsGroupsThenCategories() {
        val kind = CustomReportData.Kind.Donut(
            listOf(CustomReportData.Slice("Food", 5.0, 0), CustomReportData.Slice("Rent", 7.0, 1)),
            listOf(CustomReportData.Bar("Daily", 5.0), CustomReportData.Bar("Housing", 7.0)),
        )
        val rows = CustomReportChartAccessibility.rows(kind, amount)
        assertEquals(listOf(null, null, "Daily", "Housing"), rows.map { it.series })
        assertEquals(listOf("Daily", "Housing", "Food", "Rent"), rows.map { it.label })
    }

    @Test
    fun tablesAndUnsupportedHaveNoChartRows() {
        assertTrue(CustomReportChartAccessibility.rows(CustomReportData.Kind.Table(listOf(CustomReportData.TableRow("Food", 1.0))), amount).isEmpty())
        assertTrue(CustomReportChartAccessibility.rows(CustomReportData.Kind.Unsupported("nope"), amount).isEmpty())
    }

    @Test
    fun singleRingColorsCycleThePalette() {
        val slices = (0 until 13).map { CustomReportData.Slice("s$it", 1.0, null) }
        val colors = CustomReportChartAccessibility.sliceColors(slices, emptyList())
        assertEquals(CustomReportChartAccessibility.paletteColor(0), colors[0])
        assertEquals(colors[0], colors[12])
    }

    /** Upstream DonutGraph buildColorMap: the group's color, lightened 0.15 + index / count × 0.5 within the group. */
    @Test
    fun twoRingColorsShadeEachGroupsCategories() {
        val slices = listOf(CustomReportData.Slice("a", 1.0, 0), CustomReportData.Slice("b", 1.0, 0), CustomReportData.Slice("c", 1.0, 1))
        val colors = CustomReportChartAccessibility.sliceColors(slices, listOf(CustomReportData.Bar("G0", 2.0), CustomReportData.Bar("G1", 1.0)))
        val group0 = CustomReportChartAccessibility.paletteColor(0)
        assertEquals(lerp(group0, Color.White, 0.15f), colors[0])
        assertEquals(lerp(group0, Color.White, 0.4f), colors[1])
        assertEquals(lerp(CustomReportChartAccessibility.paletteColor(1), Color.White, 0.15f), colors[2])
    }
}
