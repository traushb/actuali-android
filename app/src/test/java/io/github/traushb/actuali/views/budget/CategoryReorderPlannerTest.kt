package io.github.traushb.actuali.views.budget

import io.github.traushb.actuali.views.budget.CategoryReorderPlanner.Entry
import io.github.traushb.actuali.views.budget.CategoryReorderPlanner.Frame
import io.github.traushb.actuali.views.budget.CategoryReorderPlanner.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a dragged (or stepped) category lands in the Clean budget table, and the order that results. */
class CategoryReorderPlannerTest {
    /**
     * Two groups of 100-pixel rows with a 60-pixel header gap between them:
     * A: a1 0–100, a2 100–200, a3 200–300; B: b1 360–460, b2 460–560.
     */
    private val arrangement = listOf(Entry("A", listOf("a1", "a2", "a3")), Entry("B", listOf("b1", "b2")))

    private val frames = mapOf(
        "a1" to Frame(0f, 100f),
        "a2" to Frame(100f, 200f),
        "a3" to Frame(200f, 300f),
        "b1" to Frame(360f, 460f),
        "b2" to Frame(460f, 560f),
    )

    private fun destination(dragged: String, centerY: Float, entries: List<Entry> = arrangement) =
        CategoryReorderPlanner.destination(dragged, centerY, entries, frames)

    @Test
    fun draggingUpWithinAGroupPassesTheRowsAboveIt() {
        // a3's centre at 120 is above a2's middle (150), below a1's (50).
        assertEquals(Slot("A", 1), destination("a3", 120f))
        assertEquals(Slot("A", 0), destination("a3", 40f))
    }

    @Test
    fun draggingDownWithinAGroupPassesTheRowsBelowIt() {
        assertEquals(Slot("A", 2), destination("a1", 290f))
        assertEquals(Slot("A", 1), destination("a1", 160f))
    }

    @Test
    fun aRowStaysPutWhileItsCentreIsOverItsOwnSlot() {
        assertEquals(Slot("A", 1), destination("a2", 150f))
        assertEquals(Slot("A", 1), CategoryReorderPlanner.slot("a2", arrangement))
    }

    @Test
    fun draggingIntoAnotherGroupLandsAmongItsRows() {
        assertEquals(Slot("B", 0), destination("a2", 400f))
        assertEquals(Slot("B", 2), destination("a2", 520f))
        assertEquals(Slot("B", 1), destination("a2", 440f))
    }

    @Test
    fun theGapBetweenGroupsGoesToTheNearerCard() {
        // 320 is 20 below A's card and 40 above B's: the end of A.
        assertEquals(Slot("A", 3), destination("b1", 320f))
        // 345 is 45 below A and 15 above B: the start of B.
        assertEquals(Slot("B", 0), destination("a1", 345f))
    }

    @Test
    fun pastTheEndsOfTheTableItGoesToTheFirstOrLastGroup() {
        assertEquals(Slot("A", 0), destination("a2", -80f))
        assertEquals(Slot("B", 2), destination("a2", 900f))
    }

    @Test
    fun aGroupItIsAloneInStillCountsAsACard() =
        assertEquals(Slot("A", 0), destination("a1", 50f, listOf(Entry("A", listOf("a1")), Entry("B", listOf("b1", "b2")))))

    @Test
    fun noMeasuredRowsMeansNoDestination() = assertNull(CategoryReorderPlanner.destination("a1", 10f, arrangement, emptyMap()))

    @Test
    fun anEmptyGroupReceivesADropOnItsHeader() {
        val entries = listOf(Entry("A", listOf("a1")), Entry("B", emptyList()))
        val headerFrames = mapOf("a1" to Frame(0f, 100f), "header-B" to Frame(160f, 210f))
        assertEquals(Slot("B", 0), CategoryReorderPlanner.destination("a1", 185f, entries, headerFrames))
        val moved = CategoryReorderPlanner.moved(entries, "a1", Slot("B", 0))
        assertEquals("B" to null, CategoryReorderPlanner.target("a1", moved))
    }

    @Test
    fun aGroupEmptiedDuringTheDragCanReceiveTheCategoryBack() {
        val entries = listOf(Entry("A", listOf("a1")), Entry("B", listOf("b1")))
        val moved = CategoryReorderPlanner.moved(entries, "a1", Slot("B", 0))
        val movedFrames = mapOf("header-A" to Frame(0f, 50f), "a1" to Frame(110f, 210f), "b1" to Frame(210f, 310f))
        assertEquals(Slot("A", 0), CategoryReorderPlanner.destination("a1", 25f, moved, movedFrames))
        assertEquals(entries, CategoryReorderPlanner.moved(moved, "a1", Slot("A", 0)))
    }

    @Test
    fun movedTakesTheRowOutAndPutsItAtTheSlot() {
        assertEquals(listOf(Entry("A", listOf("a3", "a1", "a2")), Entry("B", listOf("b1", "b2"))), CategoryReorderPlanner.moved(arrangement, "a3", Slot("A", 0)))
        assertEquals(listOf(Entry("A", listOf("a2", "a3")), Entry("B", listOf("b1", "b2", "a1"))), CategoryReorderPlanner.moved(arrangement, "a1", Slot("B", 2)))
    }

    @Test
    fun movedIgnoresAnUnknownGroupAndClampsTheIndex() {
        assertEquals(arrangement, CategoryReorderPlanner.moved(arrangement, "a1", Slot("Z", 0)))
        assertEquals(listOf("b1", "b2", "a1"), CategoryReorderPlanner.moved(arrangement, "a1", Slot("B", 99))[1].ids)
    }

    @Test
    fun theSavedTargetIsTheRowNowBelowIt() =
        assertEquals("B" to "b2", CategoryReorderPlanner.target("a3", CategoryReorderPlanner.moved(arrangement, "a3", Slot("B", 1))))

    @Test
    fun theLastRowInAGroupSavesWithoutATarget() =
        assertEquals("A" to null, CategoryReorderPlanner.target("a1", CategoryReorderPlanner.moved(arrangement, "a1", Slot("A", 2))))

    // Steps (the TalkBack and button path)

    @Test
    fun aStepMovesWithinTheGroup() {
        assertEquals(Slot("A", 0), CategoryReorderPlanner.step(-1, "a2", arrangement))
        assertEquals(Slot("A", 2), CategoryReorderPlanner.step(1, "a2", arrangement))
    }

    /** Up from the top of B lands at the end of A; down from the bottom of A at the start of B. */
    @Test
    fun aStepAtAGroupEdgeSpillsIntoTheNeighbouringGroup() {
        assertEquals(Slot("A", 3), CategoryReorderPlanner.step(-1, "b1", arrangement))
        assertEquals(Slot("B", 0), CategoryReorderPlanner.step(1, "a3", arrangement))
    }

    @Test
    fun aStepOffTheTableDoesNothing() {
        assertNull(CategoryReorderPlanner.step(-1, "a1", arrangement))
        assertNull(CategoryReorderPlanner.step(1, "b2", arrangement))
    }

    // Groups

    private val groupFrames = mapOf("header-A" to Frame(0f, 50f), "header-B" to Frame(50f, 100f), "header-C" to Frame(100f, 150f))

    @Test
    fun aDraggedGroupCountsTheHeadersAboveItsCentre() {
        val order = listOf("A", "B", "C")
        assertEquals(0, CategoryReorderPlanner.groupDestination("C", 20f, order, groupFrames))
        // Between A's middle (25) and B's (75).
        assertEquals(1, CategoryReorderPlanner.groupDestination("C", 60f, order, groupFrames))
        assertEquals(2, CategoryReorderPlanner.groupDestination("A", 140f, order, groupFrames))
    }

    @Test
    fun movedGroupTakesTheGroupOutAndPutsItAtTheIndex() {
        assertEquals(listOf("C", "A", "B"), CategoryReorderPlanner.movedGroup(listOf("A", "B", "C"), "C", 0))
        assertEquals(listOf("B", "C", "A"), CategoryReorderPlanner.movedGroup(listOf("A", "B", "C"), "A", 2))
        assertEquals(listOf("A", "C", "B"), CategoryReorderPlanner.movedGroup(listOf("A", "B", "C"), "B", 99))
    }

    @Test
    fun aGroupSavesBeforeTheGroupNowBelowIt() {
        assertEquals("A", CategoryReorderPlanner.groupTarget("C", listOf("C", "A", "B")))
        assertNull(CategoryReorderPlanner.groupTarget("A", listOf("B", "C", "A")))
    }

    @Test
    fun aGroupStepStaysWithinTheGroups() {
        assertEquals(0, CategoryReorderPlanner.groupStep(-1, "B", listOf("A", "B", "C")))
        assertEquals(2, CategoryReorderPlanner.groupStep(1, "B", listOf("A", "B", "C")))
        assertNull(CategoryReorderPlanner.groupStep(-1, "A", listOf("A", "B", "C")))
        assertNull(CategoryReorderPlanner.groupStep(1, "C", listOf("A", "B", "C")))
    }
}
