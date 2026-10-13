package io.github.traushb.actuali.models

import java.time.LocalDate

/**
 * Details carried into the add-transaction form by an automation — a shared
 * bank message the parser couldn't file on its own — which opens the form
 * pre-filled for the user to finish (GH #91).
 */
data class TransactionPrefill(
    val accountId: String?,
    val payee: String,
    val amountCents: Long?,
    val date: LocalDate,
    val notes: String = "",
    val categoryId: String? = null,
    val isIncome: Boolean = false,
    val cleared: Boolean = false,
)
