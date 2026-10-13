package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

/**
 * Presets for the transaction lists' status filter strip (GH #439). `cleared`
 * means cleared-but-not-reconciled on purpose: with `uncleared` and
 * `reconciled` the three status chips partition the list, matching the row's
 * status dot. `unreconciled` is uncleared + cleared together. `uncategorized`
 * is the same pseudo-account filter the Uncategorized list uses.
 */
enum class TransactionStatusFilter(val rawValue: String, val key: String) {
    All("all", "All"),
    Uncategorized("uncategorized", "Uncategorized"),
    Uncleared("uncleared", "Uncleared"),
    Cleared("cleared", "Cleared"),
    Reconciled("reconciled", "Reconciled"),
    Unreconciled("unreconciled", "Unreconciled"),
    ;

    val id: String get() = rawValue

    fun label(locale: Locale = Locale.getDefault()): String = ReportStrings.text(key, locale)

    companion object {
        const val DEFAULTS_KEY = "transactionStatusFilter"
        const val STRIP_VISIBILITY_DEFAULTS_KEY = "showTransactionStatusFilters"

        fun resolved(raw: String?): TransactionStatusFilter = entries.firstOrNull { it.rawValue == raw } ?: All
    }
}
