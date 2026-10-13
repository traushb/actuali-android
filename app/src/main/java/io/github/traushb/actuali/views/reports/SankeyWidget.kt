package io.github.traushb.actuali.views.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.reports.engines.SankeyData
import io.github.traushb.actuali.views.components.ChartColors
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import kotlin.math.abs
import kotlin.math.max

/**
 * The card's geometry at one size, in pixels: a column of node bars per
 * layer present, ribbons stacked along each bar's edge in link order, and
 * labels for the nodes tall enough to carry one. Dashboard-tile fidelity —
 * this deterministic top-aligned layout stands in for upstream's d3-style
 * one; the node and link data is faithful.
 */
class SankeyCardLayout private constructor(val bars: List<Bar>, val ribbons: List<Ribbon>, val labels: List<Label>) {
    data class Bar(val node: SankeyData.Node, val rect: Rect)

    /** A link's band from its source bar's right edge at [sourceY] to its target's left edge at [targetY]. */
    data class Ribbon(val source: SankeyData.Node, val x0: Float, val sourceY: Float, val x1: Float, val targetY: Float, val thickness: Float)

    /**
     * Text centered on [centerY] beside [anchorX]: to its left in the last
     * column ([trailing]), to its right elsewhere, at most [maxWidth] wide.
     */
    data class Label(val text: String, val anchorX: Float, val centerY: Float, val trailing: Boolean, val maxWidth: Float)

    companion object {
        /** [density] is pixels per dp, which the layout's fixed widths and gaps are given in. */
        fun make(data: SankeyData, width: Float, height: Float, density: Float): SankeyCardLayout? {
            val nodeWidth = 8 * density
            val nodeGap = 4 * density
            val nodeCount = data.nodes.size
            if (nodeCount == 0 || width <= 40 * density || height <= 20 * density) return null

            // Node value = max(inflow, outflow) over positive links; hidden
            // scaffolding (-1 links) contributes nothing and stays invisible.
            val inflow = LongArray(nodeCount)
            val outflow = LongArray(nodeCount)
            for (link in data.links) {
                if (link.value <= 0) continue
                outflow[link.source] += link.value
                inflow[link.target] += link.value
            }
            val values = LongArray(nodeCount) { max(inflow[it], outflow[it]) }
            val visible = (0 until nodeCount).filter { values[it] > 0 && !data.nodes[it].isHidden }
            if (visible.isEmpty()) return null

            // One column per layer present, in layer order; nodes keep the engine's (sorted) order within a column.
            val layers = visible.map { data.nodes[it].layer }.distinct().sortedBy { it.ordinal }
            val columnOfLayer = layers.withIndex().associate { it.value to it.index }
            val columns = List(layers.size) { mutableListOf<Int>() }
            for (index in visible) columns[columnOfLayer.getValue(data.nodes[index].layer)] += index

            val maxColumnTotal = columns.maxOf { column -> column.sumOf { values[it] } }
            if (maxColumnTotal <= 0) return null
            val maxGaps = (columns.maxOf { it.size } - 1) * nodeGap
            val scale = max(height - maxGaps, 10 * density) / maxColumnTotal

            fun columnX(column: Int) = if (layers.size == 1) (width - nodeWidth) / 2 else column.toFloat() / (layers.size - 1) * (width - nodeWidth)

            val frames = HashMap<Int, Rect>()
            columns.forEachIndexed { column, nodes ->
                var y = 0f
                val x = columnX(column)
                for (index in nodes) {
                    val barHeight = max(values[index] * scale, 1.5f * density)
                    frames[index] = Rect(x, y, x + nodeWidth, y + barHeight)
                    y += barHeight + nodeGap
                }
            }

            // A label fills the gap beside its bar: rightward to the next column,
            // or leftward from the last column back to the one before it.
            val padding = 3 * density
            fun gapEnd(column: Int) = if (column < 0) 0f else columnX(column) + nodeWidth
            val bars = mutableListOf<Bar>()
            val labeled = mutableListOf<Pair<Int, Label>>()
            for (index in visible) {
                val rect = frames[index] ?: continue
                val node = data.nodes[index]
                bars += Bar(node, rect)
                if (rect.height >= 11 * density && node.name.isNotEmpty()) {
                    val text = if (data.showPercentages && node.percentageLabel.isNotEmpty()) "${node.name} ${node.percentageLabel}" else node.name
                    val column = columnOfLayer.getValue(node.layer)
                    val trailing = column == layers.size - 1
                    labeled += column to if (trailing) {
                        Label(text, rect.left - padding, rect.center.y, true, rect.left - gapEnd(column - 1) - 2 * padding)
                    } else {
                        Label(text, rect.right + padding, rect.center.y, false, columnX(column + 1) - rect.right - 2 * padding)
                    }
                }
            }
            // The last column's labels run into the same gap the column before
            // it labels rightward; where two would meet, each takes half.
            val labelHeight = 12 * density
            val labels = labeled.map { (column, label) ->
                val neighbor = if (label.trailing) column - 1 else column + 1
                val meets = labeled.any { (otherColumn, other) ->
                    otherColumn == neighbor && other.trailing != label.trailing && abs(other.centerY - label.centerY) < labelHeight
                }
                if (meets) label.copy(maxWidth = label.maxWidth / 2 - padding) else label
            }.filter { it.maxWidth >= 12 * density }

            val ribbons = mutableListOf<Ribbon>()
            val sourceOffset = FloatArray(nodeCount)
            val targetOffset = FloatArray(nodeCount)
            for (link in data.links) {
                if (link.value <= 0) continue
                val source = frames[link.source] ?: continue
                val target = frames[link.target] ?: continue
                val thickness = link.value * scale
                ribbons += Ribbon(data.nodes[link.source], source.right, source.top + sourceOffset[link.source], target.left, target.top + targetOffset[link.target], thickness)
                sourceOffset[link.source] += thickness
                targetOffset[link.target] += thickness
            }
            return SankeyCardLayout(bars, ribbons, labels)
        }

        private val palette = listOf(ChartColors.Blue, ChartColors.Teal, ChartColors.Indigo, ChartColors.Orange, ChartColors.Purple, ChartColors.Pink, ChartColors.Cyan, ChartColors.Mint)

        /** A node's color: the fixed ones for budget aggregates, otherwise stable per key. */
        fun color(node: SankeyData.Node, positive: Color, negative: Color): Color {
            if (node.isNegative) return negative
            when (node.key) {
                "to_budget" -> return positive
                "budgeted", "available_income", "all_income" -> return ChartColors.Blue
                "last_month_overspent" -> return negative
                "from_previous_month", "for_next_month" -> return ChartColors.Gray
            }
            if (node.key.endsWith("__OTHER_BUCKET")) return ChartColors.Gray
            // Upstream colorIndexFromKey: the first 3 hex digits of the (UUID) key as a stable index.
            val hex = node.key.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(3)
            return palette[(hex.toIntOrNull(16) ?: 0) % palette.size]
        }
    }
}

@Composable
fun SankeyWidget(name: String, data: SankeyData, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val positive = MaterialTheme.colorScheme.positive
    val negative = MaterialTheme.colorScheme.negative
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.AccountTree)
        if (data.links.none { it.value > 0 }) {
            Placeholder(ReportStrings.text("No data"), 80.dp)
            return@ReportCard
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val layout = SankeyCardLayout.make(data, size.width, size.height, density) ?: return@Canvas
            for (ribbon in layout.ribbons) {
                val midX = (ribbon.x0 + ribbon.x1) / 2
                val path = Path().apply {
                    moveTo(ribbon.x0, ribbon.sourceY)
                    cubicTo(midX, ribbon.sourceY, midX, ribbon.targetY, ribbon.x1, ribbon.targetY)
                    lineTo(ribbon.x1, ribbon.targetY + ribbon.thickness)
                    cubicTo(midX, ribbon.targetY + ribbon.thickness, midX, ribbon.sourceY + ribbon.thickness, ribbon.x0, ribbon.sourceY + ribbon.thickness)
                    close()
                }
                drawPath(path, SankeyCardLayout.color(ribbon.source, positive, negative).copy(alpha = 0.3f))
            }
            for (bar in layout.bars) {
                drawRoundRect(SankeyCardLayout.color(bar.node, positive, negative), bar.rect.topLeft, bar.rect.size, CornerRadius(1.5.dp.toPx()))
            }
            for (label in layout.labels) {
                val result = measurer.measure(label.text, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = label.maxWidth.toInt()))
                val x = if (label.trailing) label.anchorX - result.size.width else label.anchorX
                drawText(result, topLeft = Offset(x, label.centerY - result.size.height / 2))
            }
        }
    }
}
