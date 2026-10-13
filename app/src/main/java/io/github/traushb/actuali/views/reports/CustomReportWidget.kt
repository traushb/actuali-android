package io.github.traushb.actuali.views.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.reports.engines.CustomReportData
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.BarChart
import io.github.traushb.actuali.views.components.BarSeries
import io.github.traushb.actuali.views.components.ChartColors
import io.github.traushb.actuali.views.components.ChartSeries
import io.github.traushb.actuali.views.components.DonutChart
import io.github.traushb.actuali.views.components.LineChart
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive

/** One spoken data point of a custom report chart: its series (if any), its label, and the amount. */
data class CustomReportChartAccessibilityRow(val series: String?, val label: String, val value: String)

/** The data points a custom report chart reads out, standing in for the marks a screen reader can't see. */
object CustomReportChartAccessibility {
    fun rows(kind: CustomReportData.Kind, amount: (Double) -> String): List<CustomReportChartAccessibilityRow> {
        fun stacked(stacked: CustomReportData.Stacked) = stacked.seriesNames.flatMapIndexed { seriesIndex, series ->
            stacked.intervalLabels.mapIndexed { intervalIndex, label -> CustomReportChartAccessibilityRow(series, label, amount(stacked.values[seriesIndex][intervalIndex])) }
        }
        fun bars(bars: List<CustomReportData.Bar>) = bars.map { CustomReportChartAccessibilityRow(null, it.label, amount(it.valueUnits)) }
        return when (kind) {
            is CustomReportData.Kind.Bars -> bars(kind.bars)
            is CustomReportData.Kind.StackedBars -> stacked(kind.stacked)
            is CustomReportData.Kind.Lines -> stacked(kind.stacked)
            is CustomReportData.Kind.Area -> bars(kind.bars)
            is CustomReportData.Kind.Donut -> if (kind.groups.isEmpty()) {
                kind.slices.map { CustomReportChartAccessibilityRow(null, it.label, amount(it.valueUnits)) }
            } else {
                bars(kind.groups) + kind.slices.map { slice ->
                    CustomReportChartAccessibilityRow(slice.group?.let { kind.groups.getOrNull(it)?.label }, slice.label, amount(slice.valueUnits))
                }
            }
            is CustomReportData.Kind.Table, is CustomReportData.Kind.Unsupported -> emptyList()
        }
    }

    /**
     * Single ring: the palette by position. Two rings: each category takes
     * its group's color lightened by its position within the group,
     * matching upstream's DonutGraph buildColorMap (0.15 + index / count × 0.5).
     */
    fun sliceColors(slices: List<CustomReportData.Slice>, groups: List<CustomReportData.Bar>): List<Color> {
        if (groups.isEmpty()) return slices.indices.map { paletteColor(it) }
        val groupSizes = slices.mapNotNull { it.group }.groupingBy { it }.eachCount()
        val positionInGroup = mutableMapOf<Int, Int>()
        return slices.map { slice ->
            val group = slice.group ?: 0
            val position = positionInGroup.getOrDefault(group, 0)
            positionInGroup[group] = position + 1
            lerp(paletteColor(group), Color.White, 0.15f + position.toFloat() / maxOf(groupSizes[group] ?: 1, 1) * 0.5f)
        }
    }

    /**
     * Fixed cycle standing in for upstream's qualitative color scale; the
     * donut colors its own wedges by it, as a category can share its group's
     * name and a lookup by name would merge the two.
     */
    private val palette = listOf(
        ChartColors.Blue, ChartColors.Green, ChartColors.Orange, ChartColors.Purple, ChartColors.Pink, ChartColors.Teal,
        ChartColors.Indigo, ChartColors.Yellow, ChartColors.Mint, ChartColors.Cyan, ChartColors.Brown, ChartColors.Red,
    )

    fun paletteColor(index: Int): Color = palette[index % palette.size]
}

@Composable
fun CustomReportWidget(data: CustomReportData, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val yLabel = store::axisLabel
    // Read out as data points, unless balances are hidden: then the chart is silent, as the axis is gone.
    val chartModifier = if (store.hideBalances) {
        Modifier.clearAndSetSemantics { }
    } else {
        val rows = CustomReportChartAccessibility.rows(data.kind) { store.formatCurrency(roundHalfAwayFromZero(it * 100)) }
        Modifier.semantics { contentDescription = rows.joinToString("\n") { row -> listOfNotNull(row.series, row.label, row.value).joinToString(", ") } }
    }
    ReportCard(modifier) {
        CardHeader(data.name, Icons.Filled.QueryStats)
        if (data.rangeLabel.isNotEmpty()) Text(data.rangeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val kind = data.kind) {
            is CustomReportData.Kind.Bars -> if (kind.bars.isEmpty()) EmptyChartText() else ReportBars(kind.bars, kind.signed, chartModifier)
            is CustomReportData.Kind.StackedBars -> if (kind.stacked.seriesNames.isEmpty()) {
                EmptyChartText()
            } else {
                BarChart(
                    kind.stacked.intervalLabels,
                    kind.stacked.seriesNames.mapIndexed { index, name -> BarSeries(name, kind.stacked.values[index], CustomReportChartAccessibility.paletteColor(index)) },
                    yLabel,
                    chartModifier,
                    stacked = true,
                    showsYAxis = !store.hideBalances,
                    showsLegend = true,
                    height = 200.dp,
                )
            }
            is CustomReportData.Kind.Lines -> if (kind.stacked.seriesNames.isEmpty()) {
                EmptyChartText()
            } else {
                val stacked = kind.stacked
                val last = (stacked.intervalLabels.size - 1).toDouble()
                LineChart(
                    stacked.seriesNames.mapIndexed { index, name ->
                        ChartSeries(name, stacked.values[index].mapIndexed { interval, value -> interval.toDouble() to value }, CustomReportChartAccessibility.paletteColor(index), showsPoints = true)
                    } +
                        // Upstream draws each series' least-squares trend dashed, in the series' color, first interval to last.
                        kind.trends.take(stacked.seriesNames.size).mapIndexed { index, trend ->
                            ChartSeries("", listOf(0.0 to trend.startUnits, last to trend.endUnits), CustomReportChartAccessibility.paletteColor(index), dashed = true)
                        },
                    xLabels = stacked.intervalLabels.mapIndexed { index, label -> index.toDouble() to label },
                    yLabel = yLabel,
                    modifier = chartModifier,
                    showsYAxis = !store.hideBalances,
                    showsLegend = true,
                    height = 200.dp,
                )
            }
            is CustomReportData.Kind.Area -> when {
                kind.bars.isEmpty() -> EmptyChartText()
                // An area has no width with one interval; it shows as a bar.
                kind.bars.size < 2 -> ReportBars(kind.bars, false, chartModifier)
                else -> LineChart(
                    listOf(ChartSeries("", kind.bars.mapIndexed { index, bar -> index.toDouble() to bar.valueUnits }, MaterialTheme.colorScheme.primary, fillsArea = true, smooth = true)),
                    xLabels = kind.bars.mapIndexed { index, bar -> index.toDouble() to bar.label },
                    yLabel = yLabel,
                    modifier = chartModifier,
                    showsYAxis = !store.hideBalances,
                    showsLegend = false,
                )
            }
            is CustomReportData.Kind.Donut -> if (kind.slices.isEmpty()) EmptyChartText() else ReportDonut(kind.slices, kind.groups, chartModifier)
            is CustomReportData.Kind.Table -> if (kind.rows.isEmpty()) {
                EmptyChartText()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in kind.rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(store.displayBalance(roundHalfAwayFromZero(row.totalUnits * 100)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            is CustomReportData.Kind.Unsupported -> Box(Modifier.padding(vertical = 16.dp).align(Alignment.CenterHorizontally)) {
                Text(kind.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReportBars(bars: List<CustomReportData.Bar>, signed: Boolean, modifier: Modifier) {
    val store = LocalBudgetStore.current
    val positive = MaterialTheme.colorScheme.positive
    val negative = MaterialTheme.colorScheme.negative
    BarChart(
        bars.map { it.label },
        listOf(BarSeries("", bars.map { it.valueUnits }, MaterialTheme.colorScheme.primary, colors = if (signed) bars.map { if (it.valueUnits < 0) negative else positive } else null)),
        yLabel = store::axisLabel,
        modifier = modifier,
        showsYAxis = !store.hideBalances,
        showsLegend = false,
    )
}

/** The donut, with its legend beneath: each group, then its categories indented under it. */
@Composable
private fun ReportDonut(slices: List<CustomReportData.Slice>, groups: List<CustomReportData.Bar>, modifier: Modifier) {
    val colors = CustomReportChartAccessibility.sliceColors(slices, groups)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DonutChart(
            slices.mapIndexed { index, slice -> slice.valueUnits to colors[index] },
            modifier,
            inner = groups.mapIndexed { index, group -> group.valueUnits to CustomReportChartAccessibility.paletteColor(index) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (groups.isEmpty()) {
                slices.forEachIndexed { index, slice -> LegendRow(colors[index], slice.label, slice.valueUnits) }
            } else {
                groups.forEachIndexed { groupIndex, group ->
                    LegendRow(CustomReportChartAccessibility.paletteColor(groupIndex), group.label, group.valueUnits)
                    slices.forEachIndexed { index, slice -> if (slice.group == groupIndex) LegendRow(colors[index], slice.label, slice.valueUnits, indent = 14.dp) }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, units: Double, indent: Dp = 0.dp) {
    val store = LocalBudgetStore.current
    Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(store.displayBalance(roundHalfAwayFromZero(units * 100)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
