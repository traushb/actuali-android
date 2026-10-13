package io.github.traushb.actuali.views.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** One line on a [LineChart] (or over a [BarChart]): x positions in any unit the caller picks (days, indices), y in display units. */
data class ChartSeries(
    /** Empty keeps the line out of the legend, as for a trend drawn beside its series. */
    val label: String,
    val points: List<Pair<Double, Double>>,
    val color: Color,
    val dashed: Boolean = false,
    val fillsArea: Boolean = false,
    /** Holds each value until the next point, then jumps — for amounts that change in steps, like credited interest. */
    val stepped: Boolean = false,
    /** A curve that never overshoots its points (Swift Charts' `.monotone`), for trends sampled from a smooth quantity. */
    val smooth: Boolean = false,
    /** A dot on each point, for lines over a handful of intervals. */
    val showsPoints: Boolean = false,
)

/** A shaded range between two curves, as (x, low, high) — the Monte Carlo fan. */
data class ChartBand(val points: List<Triple<Double, Double, Double>>, val color: Color)

/** One series of a [BarChart]: a value per slot, colored [color] or bar by bar from [colors] (signed bars). */
data class BarSeries(val label: String, val values: List<Double>, val color: Color, val colors: List<Color>? = null)

/** iOS's system hues, which the report charts use where a color names a category rather than a theme role. */
object ChartColors {
    val Blue = Color(0xFF007AFF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Teal = Color(0xFF30B0C7)
    val Indigo = Color(0xFF5856D6)
    val Yellow = Color(0xFFFFCC00)
    val Mint = Color(0xFF00C7BE)
    val Cyan = Color(0xFF32ADE6)
    val Brown = Color(0xFFA2845E)
    val Red = Color(0xFFFF3B30)
    val Gray = Color(0xFF8E8E93)
}

/** "Nice" tick values covering [min]…[max]: steps of 1, 2 or 5 × 10ⁿ, about [count] of them. */
fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (min == max) return listOf(min)
    val raw = (max - min) / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val first = floor(min / step) * step
    val last = ceil(max / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= last + step / 2 }.toList()
}

/** Positions for at most [count] evenly spaced labels across [values]. */
fun <T> sparseLabels(values: List<T>, count: Int = 4): List<T> {
    if (values.size <= count) return values
    if (count <= 1) return values.take(1)
    val step = (values.size - 1).toDouble() / (count - 1)
    return (0 until count).map { values[(it * step).toInt().coerceAtMost(values.lastIndex)] }.distinct()
}

/**
 * The line chart the loan, deposit and report screens draw: y-axis labels
 * from [yLabel] (hidden with [showsYAxis] false, as when balances are
 * masked), x labels at the positions in [xLabels], a legend when there's
 * more than one series. Dashed series are the recessive comparison track;
 * [bands] shade ranges beneath the lines and [xRules] mark moments on them.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xLabels: List<Pair<Double, String>>,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    showsYAxis: Boolean = true,
    showsLegend: Boolean = series.size > 1,
    height: Dp = 180.dp,
    bands: List<ChartBand> = emptyList(),
    xRules: List<Pair<Double, Color>> = emptyList(),
) {
    val xs = series.flatMap { line -> line.points.map { it.first } } + bands.flatMap { band -> band.points.map { it.first } }
    val ys = series.flatMap { line -> line.points.map { it.second } } + bands.flatMap { band -> band.points.flatMap { listOf(it.second, it.third) } }
    ChartFrame(
        xRange = (xs.minOrNull() ?: 0.0) to (xs.maxOrNull() ?: 0.0),
        yValues = ys,
        xLabels = xLabels,
        yLabel = yLabel,
        showsYAxis = showsYAxis,
        height = height,
        legend = if (showsLegend) legendEntries(series.map { it.label to it.color }) else emptyList(),
        modifier = modifier,
    ) { scale ->
        for (band in bands) drawBand(band, scale)
        for ((position, color) in xRules) {
            val x = scale.x(position)
            drawLine(color, Offset(x, 0f), Offset(x, scale.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        for (line in series) drawSeries(line, scale)
    }
}

/**
 * Bars per slot — side by side, or [stacked] (positives up from zero,
 * negatives down) — with optional [lines] drawn over them at slot indices.
 */
@Composable
fun BarChart(
    slots: List<String>,
    series: List<BarSeries>,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    lines: List<ChartSeries> = emptyList(),
    showsYAxis: Boolean = true,
    showsLegend: Boolean = series.size + lines.size > 1,
    height: Dp = 180.dp,
) {
    val ys = if (stacked) {
        slots.indices.flatMap { slot ->
            val values = series.map { it.values.getOrElse(slot) { 0.0 } }
            listOf(values.filter { it > 0 }.sum(), values.filter { it < 0 }.sum())
        }
    } else {
        series.flatMap { it.values }
    } + lines.flatMap { line -> line.points.map { it.second } }
    ChartFrame(
        xRange = -0.5 to slots.size - 0.5,
        yValues = ys,
        xLabels = slots.mapIndexed { index, label -> index.toDouble() to label },
        yLabel = yLabel,
        showsYAxis = showsYAxis,
        height = height,
        legend = if (showsLegend) legendEntries(series.map { it.label to it.color } + lines.map { it.label to it.color }) else emptyList(),
        modifier = modifier,
    ) { scale ->
        val slotWidth = scale.x(1.0) - scale.x(0.0)
        val perSlot = if (stacked) 1 else series.size.coerceAtLeast(1)
        val gap = if (perSlot < 2) 0f else min(2.dp.toPx(), slotWidth * 0.7f / perSlot / 4)
        // Bars fill 70% of their slot, but no wider than still reads as a bar when there are only a few slots.
        val barWidth = min((slotWidth * 0.7f - gap * (perSlot - 1)) / perSlot, 28.dp.toPx())
        val groupWidth = barWidth * perSlot + gap * (perSlot - 1)
        val radius = CornerRadius(min(4.dp.toPx(), barWidth / 2))
        fun bar(left: Float, from: Double, to: Double, color: Color) {
            val top = min(scale.y(from), scale.y(to))
            val barHeight = abs(scale.y(from) - scale.y(to))
            if (barHeight > 0f) drawRoundRect(color, Offset(left, top), Size(barWidth, barHeight), radius)
        }
        for (slot in slots.indices) {
            val groupLeft = scale.x(slot.toDouble()) - groupWidth / 2
            var positive = 0.0
            var negative = 0.0
            series.forEachIndexed { index, bars ->
                val value = bars.values.getOrElse(slot) { 0.0 }
                val color = bars.colors?.getOrNull(slot) ?: bars.color
                when {
                    !stacked -> bar(groupLeft + index * (barWidth + gap), 0.0, value, color)
                    value >= 0 -> bar(groupLeft, positive, (positive + value).also { positive = it }, color)
                    else -> bar(groupLeft, negative, (negative + value).also { negative = it }, color)
                }
            }
        }
        for (line in lines) drawSeries(line, scale)
    }
}

/**
 * A ring of wedges sized by magnitude, starting at twelve o'clock; with
 * [inner] a second, thinner ring sits inside it (custom report Category
 * within Group).
 */
@Composable
fun DonutChart(slices: List<Pair<Double, Color>>, modifier: Modifier = Modifier, inner: List<Pair<Double, Color>> = emptyList(), height: Dp = 180.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val outer = size.minDimension / 2
        if (inner.isEmpty()) {
            drawRing(slices, outer * 0.6f, outer)
        } else {
            drawRing(inner, outer * 0.45f, outer * 0.65f)
            drawRing(slices, outer * 0.68f, outer)
        }
    }
}

private fun DrawScope.drawRing(wedges: List<Pair<Double, Color>>, innerRadius: Float, outerRadius: Float) {
    val total = wedges.sumOf { abs(it.first) }
    if (total <= 0) return
    val thickness = outerRadius - innerRadius
    val radius = innerRadius + thickness / 2
    // A 1 dp inset on both sides of each wedge, as Swift Charts' angularInset.
    val inset = if (wedges.count { it.first != 0.0 } > 1) (2.dp.toPx() / radius * 180 / PI).toFloat() else 0f
    var start = -90f
    for ((value, color) in wedges) {
        val sweep = (abs(value) / total * 360).toFloat()
        if (sweep > inset) {
            drawArc(
                color,
                startAngle = start + inset / 2,
                sweepAngle = sweep - inset,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = thickness),
            )
        }
        start += sweep
    }
}

/** Maps chart values to the plot area's pixels. */
private class ChartScale(val width: Float, val height: Float, private val minX: Double, private val maxX: Double, private val minY: Double, private val maxY: Double) {
    fun x(value: Double): Float = if (maxX == minX) width / 2 else ((value - minX) / (maxX - minX) * width).toFloat()

    fun y(value: Double): Float = if (maxY == minY) height / 2 else (height - (value - minY) / (maxY - minY) * height).toFloat()
}

/**
 * The axes every chart shares: gridlines at nice ticks spanning [yValues]
 * and zero, y labels on the trailing edge, x labels thinned until they fit,
 * and the legend underneath. [draw] paints the marks in the plot area.
 */
@Composable
private fun ChartFrame(
    xRange: Pair<Double, Double>,
    yValues: List<Double>,
    xLabels: List<Pair<Double, String>>,
    yLabel: (Double) -> String,
    showsYAxis: Boolean,
    height: Dp,
    legend: List<Pair<String, Color>>,
    modifier: Modifier,
    draw: DrawScope.(ChartScale) -> Unit,
) {
    // Room for every tick and x label a chart draws (up to a dozen x labels),
    // so a redraw finds them all measured rather than laying out text again.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val baselineColor = MaterialTheme.colorScheme.outline
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (yValues.isNotEmpty()) {
            val ticks = niceTicks(minOf(0.0, yValues.min()), maxOf(0.0, yValues.max()))
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val axisWidth = if (showsYAxis) ticks.maxOf { measurer.measure(yLabel(it), labelStyle).size.width }.toFloat() + 8.dp.toPx() else 0f
                val bottomGap = if (xLabels.isEmpty()) 0f else 20.dp.toPx()
                val scale = ChartScale(size.width - axisWidth, size.height - bottomGap, xRange.first, xRange.second, ticks.first(), ticks.last())
                for (tick in ticks) {
                    val y = scale.y(tick)
                    // Zero is the baseline once values dip below it, so it stands out from the grid.
                    drawLine(if (tick == 0.0 && ticks.first() < 0) baselineColor else gridColor, Offset(0f, y), Offset(scale.width, y), strokeWidth = 1f)
                    if (showsYAxis) {
                        val result = measurer.measure(yLabel(tick), labelStyle)
                        drawText(result, topLeft = Offset(scale.width + 6.dp.toPx(), y - result.size.height / 2))
                    }
                }
                if (xLabels.isNotEmpty()) {
                    val spacing = 8.dp.toPx()
                    val measured = xLabels.map { (position, label) -> position to measurer.measure(label, labelStyle) }
                    val fitting = (scale.width / (measured.maxOf { it.second.size.width } + spacing)).toInt()
                    // A label centers on its position but stays inside the plot, so one near an edge
                    // shifts inward; any that would then crowd the label before it is skipped.
                    var previousRight = Float.NEGATIVE_INFINITY
                    for ((position, result) in sparseLabels(measured, fitting)) {
                        val left = (scale.x(position) - result.size.width / 2).coerceIn(0f, (scale.width - result.size.width).coerceAtLeast(0f))
                        if (left < previousRight + spacing) continue
                        drawText(result, topLeft = Offset(left, scale.height + 4.dp.toPx()))
                        previousRight = left + result.size.width
                    }
                }
                draw(scale)
            }
        }
        if (legend.isNotEmpty()) ChartLegend(legend)
    }
}

/** Named entries once each, in order: a trend shares its series' label-less slot. */
private fun legendEntries(entries: List<Pair<String, Color>>): List<Pair<String, Color>> =
    entries.filter { it.first.isNotEmpty() }.distinctBy { it.first }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(entries: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun DrawScope.drawSeries(line: ChartSeries, scale: ChartScale) {
    if (line.points.isEmpty()) return
    val offsets = line.points.map { (x, y) -> Offset(scale.x(x), scale.y(y)) }
    val path = Path().apply {
        when {
            line.smooth -> monotoneThrough(offsets)
            line.stepped -> offsets.forEachIndexed { index, point ->
                if (index == 0) {
                    moveTo(point.x, point.y)
                } else {
                    lineTo(point.x, offsets[index - 1].y)
                    lineTo(point.x, point.y)
                }
            }
            else -> offsets.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        }
    }
    if (line.fillsArea) {
        val baseline = scale.y(0.0)
        val area = Path().apply {
            addPath(path)
            lineTo(offsets.last().x, baseline)
            lineTo(offsets.first().x, baseline)
            close()
        }
        val top = offsets.minOf { it.y }.coerceAtMost(baseline)
        drawPath(area, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.4f), line.color.copy(alpha = 0.04f)), startY = top, endY = scale.height))
    }
    drawPath(
        path,
        line.color,
        style = Stroke(width = 2.dp.toPx(), pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null),
    )
    if (line.showsPoints) for (point in offsets) drawCircle(line.color, radius = 2.5.dp.toPx(), center = point)
}

private fun DrawScope.drawBand(band: ChartBand, scale: ChartScale) {
    if (band.points.size < 2) return
    val path = Path().apply {
        monotoneThrough(band.points.map { Offset(scale.x(it.first), scale.y(it.third)) })
        monotoneThrough(band.points.asReversed().map { Offset(scale.x(it.first), scale.y(it.second)) }, continuing = true)
        close()
    }
    drawPath(path, band.color)
}

/**
 * d3's curveMonotoneX, which Swift Charts' `.monotone` matches: cubic
 * segments with tangents limited so the curve never overshoots a point.
 * Symmetric in direction, so a band's lower edge can run back right to left.
 */
private fun Path.monotoneThrough(points: List<Offset>, continuing: Boolean = false) {
    val first = points.first()
    if (continuing) lineTo(first.x, first.y) else moveTo(first.x, first.y)
    if (points.size < 3) {
        points.drop(1).forEach { lineTo(it.x, it.y) }
        return
    }
    fun secant(index: Int): Float {
        val dx = points[index + 1].x - points[index].x
        return if (dx == 0f) 0f else (points[index + 1].y - points[index].y) / dx
    }
    val tangents = FloatArray(points.size)
    for (index in 1 until points.lastIndex) {
        val before = secant(index - 1)
        val after = secant(index)
        val h0 = points[index].x - points[index - 1].x
        val h1 = points[index + 1].x - points[index].x
        val weighted = if (h0 + h1 == 0f) 0f else (before * h1 + after * h0) / (h0 + h1)
        tangents[index] = (sign(before) + sign(after)) * minOf(abs(before), abs(after), 0.5f * abs(weighted))
    }
    tangents[0] = (3 * secant(0) - tangents[1]) / 2
    tangents[points.lastIndex] = (3 * secant(points.lastIndex - 1) - tangents[points.lastIndex - 1]) / 2
    for (index in 0 until points.lastIndex) {
        val from = points[index]
        val to = points[index + 1]
        val third = (to.x - from.x) / 3
        cubicTo(from.x + third, from.y + third * tangents[index], to.x - third, to.y - third * tangents[index + 1], to.x, to.y)
    }
}
