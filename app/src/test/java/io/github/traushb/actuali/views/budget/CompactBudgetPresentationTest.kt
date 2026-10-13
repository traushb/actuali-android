package io.github.traushb.actuali.views.budget

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.IncomeCategory
import io.github.traushb.actuali.views.budget.CompactBudgetOverview.Stat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CompactBudgetPresentationTest {
    private fun category(id: String = "category", budgeted: Long, spent: Long, available: Long) =
        CategoryBudget("2026-08", id, "Groceries", "group", "Essentials", 1.0, 1.0, budgeted, spent, available, 0)

    private fun income(budgeted: Long, received: Long) = IncomeCategory("2026-08", "income", "Salary", "Income", 1.0, budgeted, received)

    @Test
    fun envelopeOverviewUsesToBudgetAndHidesSpentWithoutAPlaceholder() {
        val budget = BudgetMonth("2026-08", listOf(category(budgeted = 50000, spent = -31500, available = 18500)), toBudget = 12500)
        val overview = CompactBudgetOverview.of(budget, showsSpent = false, currentMonth = "2026-08")
        assertEquals(Stat(Stat.Kind.ToBudget, 12500), overview.leading)
        assertEquals(listOf(Stat(Stat.Kind.Budgeted, 50000), Stat(Stat.Kind.Balance, 18500)), overview.columns)
        val layout = CompactBudgetTableLayout.of(isTrackingBudget = false, showsSpent = false)
        assertEquals(listOf(CompactBudgetColumn.Budgeted, CompactBudgetColumn.Balance), layout.expenseColumns)
        assertEquals(listOf(null, CompactBudgetColumn.Received), layout.incomeColumns)
    }

    /** GH #562: hiding budgeted amounts drops the Budgeted column everywhere, income included, so income stays aligned with expense rows. */
    @Test
    fun hidingBudgetedDropsTheColumnFromLayoutAndOverview() {
        assertEquals(
            listOf(CompactBudgetColumn.Spent, CompactBudgetColumn.Balance),
            CompactBudgetTableLayout.of(isTrackingBudget = true, showsSpent = true, showsBudgeted = false).expenseColumns,
        )
        assertEquals(listOf(null, CompactBudgetColumn.Received), CompactBudgetTableLayout.of(isTrackingBudget = true, showsSpent = true, showsBudgeted = false).incomeColumns)
        assertEquals(listOf(CompactBudgetColumn.Received), CompactBudgetTableLayout.of(isTrackingBudget = true, showsSpent = false, showsBudgeted = false).incomeColumns)

        val budget = BudgetMonth("2026-08", listOf(category(budgeted = 50000, spent = -31500, available = 18500)), toBudget = 12500)
        val overview = CompactBudgetOverview.of(budget, showsSpent = true, showsBudgeted = false, currentMonth = "2026-08")
        assertEquals(Stat(Stat.Kind.ToBudget, 12500), overview.leading)
        assertEquals(listOf(Stat(Stat.Kind.Spent, -31500), Stat(Stat.Kind.Balance, 18500)), overview.columns)
    }

    @Test
    fun trackingOverviewUsesIncomeAndProjectedSavingsForCurrentMonth() {
        val budget = BudgetMonth(
            "2026-08",
            listOf(category(budgeted = 80000, spent = -60000, available = 20000)),
            incomeCategories = listOf(income(budgeted = 125_000, received = 110_000)),
        )
        val overview = CompactBudgetOverview.of(budget, showsSpent = true, currentMonth = "2026-08")
        assertEquals(Stat(Stat.Kind.Income, 110_000), overview.leading)
        assertEquals(listOf(Stat(Stat.Kind.Budgeted, 80000), Stat(Stat.Kind.Spent, -60000), Stat(Stat.Kind.Projected, 45000)), overview.columns)
        assertEquals(listOf(CompactBudgetColumn.Budgeted, null, CompactBudgetColumn.Received), CompactBudgetTableLayout.of(isTrackingBudget = true, showsSpent = true).incomeColumns)
    }

    @Test
    fun pastTrackingOverviewUsesActualSavedAmount() {
        val budget = BudgetMonth(
            "2026-07",
            listOf(category(budgeted = 80000, spent = -60000, available = 20000)),
            incomeCategories = listOf(income(budgeted = 125_000, received = 110_000)),
        )
        assertEquals(Stat(Stat.Kind.Saved, 50000), CompactBudgetOverview.of(budget, showsSpent = false, currentMonth = "2026-08").columns.last())
    }

    @Test
    fun excludedCategoriesDropOutOfSpentAndSaved() {
        val budget = BudgetMonth(
            "2026-07",
            listOf(category("groceries", 80000, -60000, 20000), category("investments", 30000, -30000, 0)),
            incomeCategories = listOf(income(budgeted = 125_000, received = 110_000)),
        )
        val overview = CompactBudgetOverview.of(budget, showsSpent = true, excludedFromSpentCategoryIds = setOf("investments"), currentMonth = "2026-08")
        assertEquals(listOf(Stat(Stat.Kind.Budgeted, 110_000), Stat(Stat.Kind.Spent, -60000), Stat(Stat.Kind.Saved, 50000)), overview.columns)
    }

    @Test
    fun balanceToneDistinguishesEverySemanticStateAndPrivacyMasking() {
        assertEquals(CompactBalanceTone.Negative, CompactBalanceTone.of(-1, isMasked = false))
        assertEquals(CompactBalanceTone.Zero, CompactBalanceTone.of(0, isMasked = false))
        assertEquals(CompactBalanceTone.Positive, CompactBalanceTone.of(1, isMasked = false))
        for (amount in listOf(-1L, 0L, 1L)) assertEquals(CompactBalanceTone.Masked, CompactBalanceTone.of(amount, isMasked = true))
    }

    @Test
    fun compactLabelsLocalizeInEveryLanguage() {
        val locales = listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("pt-BR"), Locale.GERMANY, Locale.ITALY, Locale.forLanguageTag("nl-NL"))
        val columns = mapOf(
            CompactBudgetColumn.Budgeted to listOf("Budgeted", "Budgété", "Presupuestado", "Orçado", "Budgetiert", "Budgetizzato", "Begroot"),
            CompactBudgetColumn.Spent to listOf("Spent", "Dépensé", "Gastado", "Gasto", "Ausgegeben", "Speso", "Besteed"),
            CompactBudgetColumn.Balance to listOf("Balance", "Solde", "Saldo", "Saldo", "Saldo", "Saldo", "Saldo"),
            CompactBudgetColumn.Received to listOf("Received", "Reçu", "Recibido", "Recebido", "Erhalten", "Ricevuto", "Ontvangen"),
        )
        val stats = mapOf(
            Stat.Kind.ToBudget to listOf("To Budget", "À budgéter", "Por presupuestar", "A orçar", "Zu budgetieren", "Da assegnare", "Te budgetteren"),
            Stat.Kind.Income to listOf("Income", "Revenus", "Ingresos", "Receitas", "Einkommen", "Reddito", "Inkomen"),
            Stat.Kind.Saved to listOf("Saved", "Enregistré", "Guardado", "Salvo", "Gespeichert", "Salvato", "Opgeslagen"),
            Stat.Kind.Projected to listOf("Projected", "Prévisionnel", "Previsto", "Projetado", "Prognose", "Previsto", "Verwacht"),
        )
        locales.forEachIndexed { index, locale ->
            for ((column, values) in columns) assertEquals("$column in $locale", values[index], column.label(locale))
            for ((kind, values) in stats) assertEquals("$kind in $locale", values[index], Stat(kind, 0).label(locale))
        }
    }
}
