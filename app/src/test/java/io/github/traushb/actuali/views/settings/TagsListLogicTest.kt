package io.github.traushb.actuali.views.settings

import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.TagSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TagsListLogicTest {
    private val tags = listOf(
        Tag(tag = "travel", description = "Trips and flights"),
        Tag(tag = "Groceries"),
        Tag(tag = "archive", hidden = true),
        Tag(tag = "deleted", tombstone = true),
    )

    @Test
    fun listsLiveVisibleTagsAlphabeticallyIgnoringCase() =
        assertEquals(listOf("Groceries", "travel"), TagsListLogic.filtered(tags, "", showHidden = false).map { it.tag })

    @Test
    fun showHiddenAddsHiddenTagsButNeverDeletedOnes() =
        assertEquals(listOf("archive", "Groceries", "travel"), TagsListLogic.filtered(tags, "", showHidden = true).map { it.tag })

    @Test
    fun searchMatchesNameOrDescriptionIgnoringCase() {
        assertEquals(listOf("Groceries"), TagsListLogic.filtered(tags, "  GROC ", showHidden = false).map { it.tag })
        assertEquals(listOf("travel"), TagsListLogic.filtered(tags, "flights", showHidden = false).map { it.tag })
    }

    /** An inflow-only tag must not read 0.00: the caption is the signed net, not the outflow total. */
    @Test
    fun captionReadsTheSignedNet() = assertEquals(2500L, TagsListLogic.captionAmount(TagSummary(Tag(tag = "refunds"), 1, totalSpent = 0, netAmount = 2500)))
}
