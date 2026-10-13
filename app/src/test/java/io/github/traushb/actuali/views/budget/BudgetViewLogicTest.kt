package io.github.traushb.actuali.views.budget

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.models.QuickAssignSuggestion
import io.github.traushb.actuali.services.GoalTemplateOutcome
import io.github.traushb.actuali.services.MonthTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BudgetViewLogicTest {
    @Test
    fun displayedGroupsIncludeIncomeWhenPresent() =
        assertEquals(setOf("essentials", "lifestyle", BudgetViewLogic.INCOME_GROUP_COLLAPSE_ID), BudgetViewLogic.displayedGroupIDs(listOf("essentials", "lifestyle"), hasIncome = true))

    @Test
    fun displayedGroupsExcludeIncomeWhenAbsent() =
        assertEquals(setOf("essentials", "lifestyle"), BudgetViewLogic.displayedGroupIDs(listOf("essentials", "lifestyle"), hasIncome = false))

    // Hide Income Group

    private fun incomeMonth() = BudgetMonth(
        "2026-10",
        emptyList(),
        incomeCategories = listOf(IncomeCategory("2026-10", "salary", "Salary", "Income", 0.0, 0, 300_000)),
        hiddenIncomeCategories = listOf(IncomeCategory("2026-10", "old", "Old", "Income", 1.0, 0, 0, hidden = true)),
    )

    @Test
    fun incomeRowsShowWhenGroupIsNotHidden() =
        assertEquals(listOf("salary"), BudgetViewLogic.displayedIncomeCategories(incomeMonth(), showHidden = false, hideIncomeGroup = false).map { it.categoryId })

    @Test
    fun incomeRowsIncludeHiddenCategoriesWhenRequested() =
        assertEquals(listOf("salary", "old"), BudgetViewLogic.displayedIncomeCategories(incomeMonth(), showHidden = true, hideIncomeGroup = false).map { it.categoryId })

    @Test
    fun hidingTheIncomeGroupRemovesEveryIncomeRow() {
        assertTrue(BudgetViewLogic.displayedIncomeCategories(incomeMonth(), showHidden = false, hideIncomeGroup = true).isEmpty())
        assertTrue(BudgetViewLogic.displayedIncomeCategories(incomeMonth(), showHidden = true, hideIncomeGroup = true).isEmpty())
    }

    // Group template scope

    private fun groupCategory(id: String, group: String, available: Long, hidden: Boolean = false) =
        CategoryBudget("2026-10", id, id, group, group, 0.0, 0.0, 0, 0, available, 0, hidden = hidden)

    /** "Hide Spent Categories" drops the zero-available row from the table, but that row is the one that needs its template. */
    @Test
    fun groupTemplateRunCoversRowsTheTableFiltersOut() {
        val month = BudgetMonth(
            "2026-10",
            listOf(groupCategory("rent", "bills", 0), groupCategory("power", "bills", 500), groupCategory("games", "fun", 0)),
            hiddenCategoryBudgets = listOf(groupCategory("old", "bills", 0, hidden = true)),
        )
        assertEquals(setOf("rent", "power", "old"), BudgetViewLogic.groupTemplateCategoryIds("bills", month))
    }

    // Month note (GH #567)

    @Test
    fun monthNoteOffersTheNoteReadForTheSelectedMonth() {
        val note = EntityNote(true, "Holiday month")
        assertEquals(note, BudgetViewLogic.monthNote(note, "2026-09", "2026-09"))
    }

    /** Right after a month change the previous month's note is still in state; offering it would let a save overwrite the new month's note. */
    @Test
    fun monthNoteHidesAnotherMonthsNote() {
        val note = EntityNote(true, "Holiday month")
        assertEquals(EntityNote.unsupported, BudgetViewLogic.monthNote(note, "2026-08", "2026-09"))
        assertEquals(EntityNote.unsupported, BudgetViewLogic.monthNote(note, null, "2026-09"))
    }

    @Test
    fun monthNoteStaysUnsupportedWithoutANotesTable() =
        assertEquals(EntityNote.unsupported, BudgetViewLogic.monthNote(EntityNote.unsupported, "2026-09", "2026-09"))

    @Test
    fun monthTitlesUseRequestedLocale() {
        assertEquals("September 2026", MonthTitles.title("2026-09", Locale.US))
        assertEquals("septembre 2026", MonthTitles.title("2026-09", Locale.FRANCE))
        assertEquals("Sep 2026", MonthTitles.shortTitle("2026-09", Locale.US))
        assertEquals("sept. 2026", MonthTitles.shortTitle("2026-09", Locale.FRANCE))
    }

    @Test
    fun monthTitlesLeaveMalformedMonthsAsTyped() {
        for (month in listOf("2026-00", "2026-13", "2026-9", "26-09", "2026--09", "2026-09-", "2026-+9")) {
            assertEquals(month, MonthTitles.title(month, Locale.US))
        }
    }

    @Test
    fun quickAssignTitlesUseRequestedLocale() {
        val french = Locale.FRANCE
        assertEquals("Dépenses du mois dernier", CategoryDetailText.quickAssignTitle(QuickAssignSuggestion.Kind.SpentLastMonth, isTracking = false, historyCount = 2, locale = french))
        assertEquals("Dépense moyenne (sur 2 mois)", CategoryDetailText.quickAssignTitle(QuickAssignSuggestion.Kind.AverageSpent, isTracking = false, historyCount = 2, locale = french))
        assertEquals("Budget du mois dernier", CategoryDetailText.quickAssignTitle(QuickAssignSuggestion.Kind.AssignedLastMonth, isTracking = true, historyCount = 2, locale = french))
    }

    @Test
    fun templateAlertMonthRunReportsUpToDateAsSuccess() =
        assertEquals(BudgetViewLogic.TemplateAlert("Templates Applied", "All templates are up to date."), BudgetViewLogic.templateAlert(GoalTemplateOutcome.UpToDate, locale = Locale.US))

    /** A single-category run on a templateless category must not read as a month-wide success — GH #577 review. */
    @Test
    fun templateAlertSingleCategoryUpToDateNamesTheCategory() =
        assertEquals(
            BudgetViewLogic.TemplateAlert("Apply Budget Template", "No templates to apply for this category."),
            BudgetViewLogic.templateAlert(GoalTemplateOutcome.UpToDate, singleCategory = true, locale = Locale.US),
        )

    @Test
    fun templateAlertAppliedAndFailureCases() {
        assertEquals(
            BudgetViewLogic.TemplateAlert("Templates Applied", "Successfully applied templates to 3 categories."),
            BudgetViewLogic.templateAlert(GoalTemplateOutcome.Applied(3), singleCategory = true, locale = Locale.US),
        )
        assertEquals(BudgetViewLogic.TemplateAlert("Template Error", "sync unavailable"), BudgetViewLogic.templateAlert(GoalTemplateOutcome.Failed("sync unavailable"), locale = Locale.US))
    }

    // Rename (BudgetViewRenameTests)

    @Test
    fun blankDraftHasNoRenameName() {
        for (draft in listOf("", " ", "\t\n", " \r\n ")) assertNull(BudgetViewLogic.renameName(draft))
    }

    @Test
    fun renameNameTrimsSurroundingWhitespace() {
        for (draft in listOf("Food", "  Food  ", "\nFood\t")) assertEquals("Food", BudgetViewLogic.renameName(draft))
        assertEquals("Dining Out", BudgetViewLogic.renameName("  Dining Out  "))
    }

    // Month navigation

    @Test
    fun monthSwipeNeedsAMostlySidewaysDragPastTheThreshold() {
        assertEquals("2026-08", BudgetViewLogic.monthSwipeTarget("2026-09", dx = 120f, dy = 10f, threshold = 80f))
        assertEquals("2026-10", BudgetViewLogic.monthSwipeTarget("2026-09", dx = -120f, dy = 10f, threshold = 80f))
        assertNull(BudgetViewLogic.monthSwipeTarget("2026-09", dx = 60f, dy = 0f, threshold = 80f))
        assertNull(BudgetViewLogic.monthSwipeTarget("2026-09", dx = 120f, dy = 100f, threshold = 80f))
    }

    /** Next month back through the prior year, newest first; a month swiped past that window joins the list in order. */
    @Test
    fun monthOptionsCoverTheYearAndKeepTheSelection() {
        val options = BudgetViewLogic.monthOptions("2026-09", current = "2026-10")
        assertEquals("2026-11", options.first())
        assertEquals("2025-10", options.last())
        assertEquals(14, options.size)
        val older = BudgetViewLogic.monthOptions("2024-01", current = "2026-10")
        assertEquals("2024-01", older.last())
        assertEquals(15, older.size)
    }
}
