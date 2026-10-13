package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.ReportStrings
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * A transaction extracted from a shared bank message, queued for user review
 * before being logged to the budget. Lives in a lightweight JSON file, not the
 * CRDT database — these aren't real transactions until approved.
 */
@Serializable
data class PendingImport(
    val id: String = UUID.randomUUID().toString(),
    val originBudgetId: String? = null,
    val amount: Double? = null,
    val sourceCurrencyCode: String? = null,
    val payee: String? = null,
    val cardHint: String? = null,
    /** Epoch millis. */
    val date: Long = System.currentTimeMillis(),
    val isIncome: Boolean = false,
    val rawText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun reviewRequirements(activeBudgetId: String?, budgetCurrency: String): List<PendingImportReviewRequirement> {
        val requirements = mutableListOf<PendingImportReviewRequirement>()
        if (originBudgetId == null || originBudgetId != activeBudgetId) requirements += PendingImportReviewRequirement.AdoptIntoActiveBudget

        val normalizedBudget = normalizedCurrencyCode(budgetCurrency)
        val source = sourceCurrencyCode
        if (source == null) {
            requirements += PendingImportReviewRequirement.ConfirmActiveBudgetCurrency(null, normalizedBudget)
            return requirements
        }
        val normalizedSource = normalizedCurrencyCode(source)
        if (normalizedSource != normalizedBudget) {
            requirements += PendingImportReviewRequirement.ConfirmActiveBudgetCurrency(normalizedSource, normalizedBudget)
        }
        return requirements
    }

    companion object {
        fun normalizedCurrencyCode(code: String): String = code.trim().uppercase()

        fun currencyLabel(code: String, locale: Locale): String = code.ifEmpty { ReportStrings.text("None", locale) }
    }
}

sealed interface PendingImportReviewRequirement {
    data object AdoptIntoActiveBudget : PendingImportReviewRequirement

    data class ConfirmActiveBudgetCurrency(val source: String?, val budget: String) : PendingImportReviewRequirement

    fun prompt(locale: Locale): String = when (this) {
        AdoptIntoActiveBudget -> ReportStrings.text("I confirm that this import should be adopted into the active budget.", locale)
        is ConfirmActiveBudgetCurrency -> {
            val budgetLabel = PendingImport.currencyLabel(budget, locale)
            if (source != null) {
                ReportStrings.format(
                    "I confirm that the numeric amount is in the active budget currency (%@); no conversion from %@ will be performed.",
                    budgetLabel, source, locale = locale,
                )
            } else {
                ReportStrings.format(
                    "I confirm that the numeric amount should be treated as the active budget currency (%@); no conversion will be performed.",
                    budgetLabel, locale = locale,
                )
            }
        }
    }
}
