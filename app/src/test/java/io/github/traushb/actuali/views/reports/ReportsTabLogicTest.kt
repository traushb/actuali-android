package io.github.traushb.actuali.views.reports

import io.github.traushb.actuali.services.reports.DashboardPage
import io.github.traushb.actuali.services.reports.DashboardWidget
import io.github.traushb.actuali.services.reports.SummaryMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTabLogicTest {
    private val pages = listOf(DashboardPage("page-main", "Main"), DashboardPage("page-second", "Second"))

    @Test
    fun keepsSelectionWhenStillLive() = assertEquals("page-second", ReportsTabLogic.resolvePageId("page-second", pages = pages))

    @Test
    fun fallsBackToFirstPageWhenSelectionGone() = assertEquals("page-main", ReportsTabLogic.resolvePageId("page-deleted", pages = pages))

    @Test
    fun defaultsToFirstPageWhenNothingSelected() = assertEquals("page-main", ReportsTabLogic.resolvePageId(null, pages = pages))

    @Test
    fun isNullWhenNoLivePages() = assertNull(ReportsTabLogic.resolvePageId("anything", pages = emptyList()))

    @Test
    fun opensConfiguredDefaultWhenNothingSelected() = assertEquals("page-second", ReportsTabLogic.resolvePageId(null, "page-second", pages))

    /** A live in-session selection is a deliberate switch, so it outranks the setting until the tab is rebuilt. */
    @Test
    fun selectionOutranksConfiguredDefault() = assertEquals("page-main", ReportsTabLogic.resolvePageId("page-main", "page-second", pages))

    @Test
    fun ignoresConfiguredDefaultForDeletedPage() = assertEquals("page-main", ReportsTabLogic.resolvePageId(null, "page-deleted", pages))

    @Test
    fun ignoresConfiguredDefaultWhenNoLivePages() = assertNull(ReportsTabLogic.resolvePageId(null, "page-main", emptyList()))

    @Test
    fun pageRenameKeepsComputedCards() {
        val widgets = listOf(DashboardWidget.Summary("s", null))
        assertFalse(ReportsTabLogic.dashboardIdentityChanged(widgets, widgets, "p", "p"))
    }

    @Test
    fun widgetMetaEditRecreatesCardsOnSamePage() {
        assertTrue(
            ReportsTabLogic.dashboardIdentityChanged(
                listOf(DashboardWidget.Summary("s", null)),
                listOf(DashboardWidget.Summary("s", SummaryMeta(name = "Changed"))),
                "p",
                "p",
            ),
        )
    }

    @Test
    fun pageSwitchRecreatesCardsEvenWithEqualWidgets() {
        val widgets = listOf(DashboardWidget.Summary("s", null))
        assertTrue(ReportsTabLogic.dashboardIdentityChanged(widgets, widgets, "p", "q"))
    }
}
