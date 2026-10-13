package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.PendingImport
import java.text.NumberFormat
import java.util.Locale

/**
 * A bank message shared into the app: parsed into transaction fields and
 * queued in the pending imports inbox for review — the Android counterpart of
 * the iOS "Import Transaction from Text" shortcut.
 */
object SharedTextImport {
    suspend fun queue(text: String, store: BudgetStore, pendingImportStore: PendingImportStore): PendingImport {
        store.ensureBudgetReady()
        if (store.currentBudgetId == null || store.databaseForLogger == null) throw TransactionLogger.LoggerError.NoBudgetLoaded
        val pending = TransactionTextParser.parse(text).toPendingImport(store.currentBudgetId)
        pendingImportStore.add(pending)
        return pending
    }

    fun confirmationText(amount: Double?, payee: String?, locale: Locale = Locale.getDefault()): String {
        val amountText = amount?.let {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }.format(it)
        } ?: "?"
        return ReportStrings.format("Queued %@ at %@ for review", amountText, payee ?: ReportStrings.text("Unknown", locale), locale = locale)
    }
}
