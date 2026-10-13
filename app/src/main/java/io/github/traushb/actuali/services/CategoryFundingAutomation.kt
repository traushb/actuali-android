package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.database.fetchBudgetMonth
import io.github.traushb.actuali.services.database.fetchTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/** Where a category shortfall is funded from. */
@Serializable
sealed interface CategoryFundingSource {
    @Serializable
    @SerialName("toBudget")
    data object ToBudget : CategoryFundingSource

    @Serializable
    @SerialName("category")
    data class Category(val id: String) : CategoryFundingSource
}

@Serializable
data class CategoryFundingAutomationConfiguration(
    val isEnabled: Boolean = false,
    val accountId: String? = null,
    val fundingSource: CategoryFundingSource = CategoryFundingSource.ToBudget,
)

sealed interface CategoryFundingDecision {
    data object None : CategoryFundingDecision
    data class Fund(val amount: Long) : CategoryFundingDecision
    data object InsufficientSource : CategoryFundingDecision
    data object InvalidSource : CategoryFundingDecision
    data object SameSourceAndTarget : CategoryFundingDecision
}

object CategoryFundingAutomation {
    /**
     * Existing overspending is intentionally preserved. The calculation only
     * considers positive money that was available before the new expense.
     */
    fun shortfall(transactionAmount: Long, availableAfterTransaction: Long): Long {
        if (transactionAmount >= 0) return 0
        val expense = abs(transactionAmount)
        val availableBeforeTransaction = availableAfterTransaction - transactionAmount
        return max(0, expense - max(0, availableBeforeTransaction))
    }

    /**
     * Decide whether the configured source can cover the amount required by
     * this new expense. To Budget may go negative; another category may not.
     */
    fun fundingDecision(
        transactionAmount: Long,
        availableAfterTransaction: Long,
        targetCategoryId: String? = null,
        fundingSource: CategoryFundingSource,
        sourceAvailable: Long = 0,
        isTrackingBudget: Boolean = false,
    ): CategoryFundingDecision {
        val amount = shortfall(transactionAmount, availableAfterTransaction)
        if (amount <= 0) return CategoryFundingDecision.None
        return when (fundingSource) {
            CategoryFundingSource.ToBudget -> if (isTrackingBudget) CategoryFundingDecision.InvalidSource else CategoryFundingDecision.Fund(amount)
            is CategoryFundingSource.Category -> when {
                fundingSource.id == targetCategoryId -> CategoryFundingDecision.SameSourceAndTarget
                sourceAvailable >= amount -> CategoryFundingDecision.Fund(amount)
                else -> CategoryFundingDecision.InsufficientSource
            }
        }
    }

    fun shouldProcess(transaction: Transaction, selectedAccountId: String, isIncomeCategory: Boolean): Boolean =
        transaction.accountId == selectedAccountId &&
            transaction.amount < 0 &&
            transaction.categoryId != null &&
            !transaction.isParent &&
            transaction.parentId == null &&
            transaction.transferId == null &&
            !isIncomeCategory &&
            !transaction.tombstone

    /**
     * Runs only after a successful manual Add Transaction save. The id is the
     * exact row created by the form, so backdated transactions, rows on other
     * accounts, and newer rows elsewhere in the budget cannot be mistaken for
     * the triggering transaction.
     */
    fun processIfNeeded(savedTransactionId: String?, budgetStore: BudgetStore, defaults: Defaults = budgetStore.defaults) {
        budgetStore.enqueueCategoryFunding(savedTransactionId ?: return, defaults)
    }

    suspend fun process(savedTransactionId: String, budgetStore: BudgetStore, defaults: Defaults = budgetStore.defaults) {
        val processingBudgetId = budgetStore.currentBudgetId ?: return
        val configuration = loadConfiguration(processingBudgetId, defaults)?.takeIf { it.isEnabled } ?: return
        val selectedAccountId = configuration.accountId ?: return
        val database = budgetStore.databaseForLogger ?: return
        val selectedAccount = budgetStore.accounts.firstOrNull { it.id == selectedAccountId } ?: return
        if (selectedAccount.closed || selectedAccount.offBudget) return

        val transaction = try {
            // A delete-transaction rule may legitimately remove the row.
            database.fetchTransaction(savedTransactionId) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            budgetStore.error = L("Category funding automation couldn't verify the saved transaction: %@", e.localizedMessage)
            return
        }

        val targetCategory = budgetStore.categoryGroups.flatMap { it.categories }.firstOrNull { it.id == transaction.categoryId } ?: return
        if (!shouldProcess(transaction, selectedAccountId, targetCategory.isIncome)) return

        val month = "%04d-%02d".format(transaction.date / 10000, (transaction.date / 100) % 100)
        val budgetMonth: BudgetMonth = try {
            database.fetchBudgetMonth(month)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            budgetStore.error = L("Category funding automation couldn't load the budget month: %@", e.localizedMessage)
            return
        }

        val category = budgetMonth.allCategoryBudgets.firstOrNull { it.categoryId == transaction.categoryId } ?: return
        if (shortfall(transaction.amount, category.available) <= 0) return

        val source = configuration.fundingSource
        val sourceCategory = if (source is CategoryFundingSource.Category) {
            budgetMonth.allCategoryBudgets.firstOrNull { it.categoryId == source.id } ?: run {
                budgetStore.error = L("Couldn't automatically fund %@: the selected funding category is unavailable.", category.categoryName)
                return
            }
        } else {
            null
        }

        when (
            val decision = fundingDecision(
                transaction.amount, category.available, category.categoryId, source, sourceCategory?.available ?: 0, budgetMonth.isTrackingBudget,
            )
        ) {
            CategoryFundingDecision.None, CategoryFundingDecision.SameSourceAndTarget -> return
            CategoryFundingDecision.InvalidSource -> budgetStore.error =
                L("Couldn't automatically fund %@: To Budget is not available for tracking budgets. Choose a funding category.", category.categoryName)
            CategoryFundingDecision.InsufficientSource -> budgetStore.error =
                L("Couldn't automatically fund %@: the funding category doesn't have enough available.", category.categoryName)
            is CategoryFundingDecision.Fund -> {
                if (budgetStore.currentBudgetId != processingBudgetId) return
                val displayedMonth = budgetStore.currentBudgetMonth?.month
                try {
                    budgetStore.transferBudget(month, sourceCategory?.categoryId, category.categoryId, decision.amount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    budgetStore.error = L("Couldn't automatically fund %@: %@", category.categoryName, e.localizedMessage)
                }
                // transferBudget refreshes the transaction's month. Restore the
                // month the user was viewing when the automation started.
                if (displayedMonth != null && displayedMonth != month) budgetStore.fetchBudgetMonth(displayedMonth)
            }
        }
    }

    fun loadConfiguration(budgetId: String?, defaults: Defaults): CategoryFundingAutomationConfiguration? {
        val json = defaults.string(key(budgetId ?: return null)) ?: return null
        return runCatching { AppJson.decodeFromString(CategoryFundingAutomationConfiguration.serializer(), json) }.getOrNull()
    }

    fun saveConfiguration(configuration: CategoryFundingAutomationConfiguration, budgetId: String?, defaults: Defaults) {
        defaults.set(key(budgetId ?: return), AppJson.encodeToString(CategoryFundingAutomationConfiguration.serializer(), configuration))
    }

    private fun key(budgetId: String) = "categoryFundingAutomation_$budgetId"
}
