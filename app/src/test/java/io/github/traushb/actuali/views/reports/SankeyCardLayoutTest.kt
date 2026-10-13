package io.github.traushb.actuali.views.reports

import io.github.traushb.actuali.services.reports.engines.SankeyData
import io.github.traushb.actuali.services.reports.engines.SankeyLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SankeyCardLayoutTest {
    private fun node(key: String, name: String, layer: SankeyLayer) = SankeyData.Node(key, name, "", layer, isNegative = false)

    @Test
    fun nothingToDrawWithoutPositiveFlows() {
        val data = SankeyData(listOf(node("a", "A", SankeyLayer.IncomeCategory), node("b", "B", SankeyLayer.Category)), listOf(SankeyData.Link(0, 1, -1)), false)
        assertNull(SankeyCardLayout.make(data, 300f, 200f, density = 1f))
    }

    /** Hidden scaffolding never draws; the two layers present become the outer columns, joined by one full-height ribbon. */
    @Test
    fun laysOutVisibleLayersAsColumns() {
        val data = SankeyData(
            listOf(node("a", "Salary", SankeyLayer.IncomeCategory), node("b", "Food", SankeyLayer.Category), node("h__HIDDEN", "", SankeyLayer.Account)),
            listOf(SankeyData.Link(0, 1, 100), SankeyData.Link(0, 2, -1)),
            false,
        )
        val layout = SankeyCardLayout.make(data, 300f, 200f, density = 1f)!!
        assertEquals(listOf("a", "b"), layout.bars.map { it.node.key })
        assertEquals(0f, layout.bars[0].rect.left)
        assertEquals(292f, layout.bars[1].rect.left)
        val ribbon = layout.ribbons.single()
        assertEquals(8f, ribbon.x0)
        assertEquals(292f, ribbon.x1)
        assertEquals(200f, ribbon.thickness)
    }

    /** The last column labels leftward into the gap the column before it labels rightward; facing labels split it. */
    @Test
    fun facingLabelsShareTheirGap() {
        val data = SankeyData(listOf(node("a", "Salary", SankeyLayer.IncomeCategory), node("b", "Food", SankeyLayer.Category)), listOf(SankeyData.Link(0, 1, 100)), false)
        val labels = SankeyCardLayout.make(data, 300f, 200f, density = 1f)!!.labels
        // Gap: 292 - 8 = 284, less 3 of padding each side = 278, halved less padding.
        assertEquals(listOf(136f, 136f), labels.map { it.maxWidth })
        assertEquals(listOf(false, true), labels.map { it.trailing })
    }
}
