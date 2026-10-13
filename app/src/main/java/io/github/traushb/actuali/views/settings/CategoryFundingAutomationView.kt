package io.github.traushb.actuali.views.settings

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.traushb.actuali.services.CategoryFundingAutomation
import io.github.traushb.actuali.services.CategoryFundingAutomationConfiguration
import io.github.traushb.actuali.services.CategoryFundingSource
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow

@Composable
fun CategoryFundingAutomationView() {
    val store = LocalBudgetStore.current
    var configuration by remember(store.currentBudgetId) {
        mutableStateOf(CategoryFundingAutomation.loadConfiguration(store.currentBudgetId, Defaults.standard) ?: CategoryFundingAutomationConfiguration())
    }
    fun update(new: CategoryFundingAutomationConfiguration) {
        // No account, nothing to trigger on.
        configuration = if (new.accountId == null) new.copy(isEnabled = false) else new
        CategoryFundingAutomation.saveConfiguration(configuration, store.currentBudgetId, Defaults.standard)
    }
    val accounts = store.accounts.filter { !it.closed && !it.offBudget }
    // Every non-income category is a valid source. The automation checks the
    // source balance for the transaction's own month, so this doesn't depend
    // on the month the Budget tab is viewing.
    val fundingCategories = store.categoryGroups.flatMap { it.categories }.filter { !it.isIncome && !it.hidden }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    Scaffold(topBar = { AppTopBar(L("Category Funding")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Trigger")) {
                FormPickerRow(
                    L("Account"),
                    listOf(null) + accounts.map { it.id },
                    configuration.accountId,
                    { id -> accounts.firstOrNull { it.id == id }?.name ?: L("None") },
                    { update(configuration.copy(accountId = it)) },
                    Modifier.testTag("categoryFunding.accountPicker"),
                )
            }
            FormSection(footer = L("When a new expense is manually entered from the selected account and would overdraw its category, Actuali automatically funds only the amount needed to cover that expense.")) {
                FormToggleRow(L("Enable Automation"), configuration.isEnabled, { update(configuration.copy(isEnabled = it)) }, enabled = configuration.accountId != null)
            }
            FormSection(L("Funding"), L("To Budget is the default. You can also fund the category from another budget category that has available money.")) {
                FormPickerRow(
                    L("Funding Source"),
                    listOf<CategoryFundingSource>(CategoryFundingSource.ToBudget) + fundingCategories.map { CategoryFundingSource.Category(it.id) },
                    configuration.fundingSource,
                    { source ->
                        when (source) {
                            CategoryFundingSource.ToBudget -> L("To Budget")
                            is CategoryFundingSource.Category -> fundingCategories.firstOrNull { it.id == source.id }?.name ?: L("Unknown")
                        }
                    },
                    { update(configuration.copy(fundingSource = it)) },
                )
            }
        }
    }
}
