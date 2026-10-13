package io.github.traushb.actuali.views.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.PendingImport
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.PendingImportApprover
import io.github.traushb.actuali.services.PendingImportStore
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.transactions.AddTransactionView
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The inbox's pure rules and text, apart from the screen so they can be tested without one. */
object PendingImportsLogic {
    enum class BulkApprovalFailureDisposition { RemovePendingImport, Review, Failure }

    sealed interface BulkApprovalOutcome {
        data object None : BulkApprovalOutcome
        data class Review(val deferredFailureCount: Int) : BulkApprovalOutcome
        data class Failure(val count: Int) : BulkApprovalOutcome
    }

    fun bulkApprovalDisposition(error: Throwable): BulkApprovalFailureDisposition = when (error) {
        PendingImportApprover.ApproveError.AlreadyApproved -> BulkApprovalFailureDisposition.RemovePendingImport
        PendingImportApprover.ApproveError.NoAccountAvailable,
        PendingImportApprover.ApproveError.AccountClosed,
        PendingImportApprover.ApproveError.BudgetMismatch,
        PendingImportApprover.ApproveError.BudgetIdentityRequired,
        PendingImportApprover.ApproveError.SourceCurrencyRequired,
        is PendingImportApprover.ApproveError.SourceCurrencyMismatch,
        PendingImportApprover.ApproveError.ReviewConfirmationRequired,
        -> BulkApprovalFailureDisposition.Review
        else -> BulkApprovalFailureDisposition.Failure
    }

    fun bulkApprovalOutcome(reviewItem: PendingImport?, failedCount: Int): BulkApprovalOutcome = when {
        reviewItem != null -> BulkApprovalOutcome.Review(failedCount)
        failedCount > 0 -> BulkApprovalOutcome.Failure(failedCount)
        else -> BulkApprovalOutcome.None
    }

    fun approvalFailureMessage(count: Int, locale: Locale = Locale.getDefault()): String =
        ReportStrings.localized("%lld transaction could not be approved. Please check its details.", count.toLong(), locale = locale)

    /** The banner links to Currency Settings only when its message is about the budget's currency. */
    fun offersCurrencySettings(item: PendingImport, activeBudgetId: String?, budgetCurrency: String): Boolean {
        if (item.originBudgetId != null && item.originBudgetId != activeBudgetId) return false
        val budget = PendingImport.normalizedCurrencyCode(budgetCurrency)
        val source = item.sourceCurrencyCode ?: return item.originBudgetId != null && budget.isEmpty()
        return PendingImport.normalizedCurrencyCode(source) != budget
    }

    fun currencyContext(item: PendingImport, activeBudgetId: String?, budgetCurrency: String, locale: Locale = Locale.getDefault()): String? {
        if (item.originBudgetId != null && item.originBudgetId != activeBudgetId) {
            return ReportStrings.text("This import belongs to a different budget. Review and confirm adoption into the active budget before saving.", locale)
        }
        val sourceCurrency = item.sourceCurrencyCode
        if (sourceCurrency == null) {
            if (item.originBudgetId == null) return ReportStrings.text("This older import has no budget identity. Review and save it to adopt it into the active budget.", locale)
            val budgetLabel = PendingImport.currencyLabel(PendingImport.normalizedCurrencyCode(budgetCurrency), locale)
            return ReportStrings.format("Currency was not identified. Active budget: %@. Review and confirm before saving.", budgetLabel, locale = locale)
        }
        val source = PendingImport.normalizedCurrencyCode(sourceCurrency)
        val budget = PendingImport.normalizedCurrencyCode(budgetCurrency)
        if (source == budget) return null
        if (budget.isEmpty()) {
            return ReportStrings.format("This import is in %@, but the active budget has no currency set. No currency conversion will be performed.", source, locale = locale)
        }
        return ReportStrings.format("This import is in %@, but the active budget uses %@. No currency conversion will be performed.", source, budget, locale = locale)
    }

    fun unknownPayee(locale: Locale = Locale.getDefault()): String = ReportStrings.text("Unknown Payee", locale)

    fun cardLabel(hint: String, locale: Locale = Locale.getDefault()): String = ReportStrings.format("Card ••%@", hint, locale = locale)

    fun amountString(amount: Double, isIncome: Boolean, currencyCode: String, sourceCurrencyCode: String?, narrowSymbol: Boolean, numberFormat: ActualNumberFormat, locale: Locale): String {
        val cents = Transaction.cents(amount) ?: return ""
        return CurrencyAmountFormat.string(
            if (isIncome) cents else -cents,
            PendingImport.normalizedCurrencyCode(sourceCurrencyCode ?: currencyCode),
            narrowSymbol,
            numberFormat = numberFormat,
            locale = locale,
        )
    }
}

/**
 * Pending transaction imports queued from shared bank messages: approve
 * (logs to the budget), edit (opens the add-transaction form), or dismiss each.
 */
@Composable
fun PendingImportsView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val imports = PendingImportStore.shared
    var editingItem by remember { mutableStateOf<PendingImport?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var deferredFailureCount by remember { mutableStateOf<Int?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    fun reportError(e: Throwable) {
        editingItem = null
        deferredFailureCount = null
        errorMessage = PendingImportApprover.localizedErrorMessage(e, Locale.getDefault())
    }

    fun remove(item: PendingImport) = try {
        imports.remove(item.id)
    } catch (e: Exception) {
        reportError(e)
    }

    fun approve(item: PendingImport) {
        // Ignore a per-row approve while Approve All runs: both log and remove by id, so overlapping could log twice.
        if (isProcessing) return
        isProcessing = true
        scope.launch {
            try {
                PendingImportApprover(store).approve(item)
                remove(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (PendingImportsLogic.bulkApprovalDisposition(e)) {
                    PendingImportsLogic.BulkApprovalFailureDisposition.RemovePendingImport -> remove(item)
                    // Can't confidently file it: open the review form rather than dead-ending on an error.
                    PendingImportsLogic.BulkApprovalFailureDisposition.Review ->
                        if (e is PendingImportApprover.ApproveError.AccountClosed || e is PendingImportApprover.ApproveError.ReviewConfirmationRequired) reportError(e) else editingItem = item
                    PendingImportsLogic.BulkApprovalFailureDisposition.Failure -> reportError(e)
                }
            } finally {
                isProcessing = false
            }
        }
    }

    fun approveAll() {
        val items = imports.visibleImports()
        deferredFailureCount = null
        errorMessage = null
        isProcessing = true
        scope.launch {
            var failedCount = 0
            var reviewItem: PendingImport? = null
            for (item in items) {
                try {
                    PendingImportApprover(store).approve(item)
                    runCatching { imports.remove(item.id) }.onFailure { failedCount += 1 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when (PendingImportsLogic.bulkApprovalDisposition(e)) {
                        PendingImportsLogic.BulkApprovalFailureDisposition.RemovePendingImport -> runCatching { imports.remove(item.id) }.onFailure { failedCount += 1 }
                        PendingImportsLogic.BulkApprovalFailureDisposition.Review -> reviewItem = reviewItem ?: item
                        PendingImportsLogic.BulkApprovalFailureDisposition.Failure -> failedCount += 1
                    }
                }
            }
            isProcessing = false
            when (val outcome = PendingImportsLogic.bulkApprovalOutcome(reviewItem, failedCount)) {
                PendingImportsLogic.BulkApprovalOutcome.None -> Unit
                is PendingImportsLogic.BulkApprovalOutcome.Review -> {
                    errorMessage = null
                    deferredFailureCount = outcome.deferredFailureCount.takeIf { it > 0 }
                    editingItem = reviewItem
                }
                is PendingImportsLogic.BulkApprovalOutcome.Failure -> {
                    editingItem = null
                    deferredFailureCount = null
                    errorMessage = PendingImportsLogic.approvalFailureMessage(outcome.count)
                }
            }
        }
    }

    fun closeEditor() {
        editingItem = null
        // Failures from Approve All wait until the review form closes.
        deferredFailureCount?.let {
            deferredFailureCount = null
            errorMessage = PendingImportsLogic.approvalFailureMessage(it)
        }
    }

    val visible = imports.visibleImports()
    Scaffold(topBar = { AppTopBar(L("Pending Imports")) }) { padding ->
        if (visible.isEmpty()) {
            EmptyState(L("No Pending Imports"), Icons.Filled.Inbox, L("Share a bank message to Actuali to import transactions"), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                    PendingImportRow(
                        item,
                        onOpen = { editingItem = item },
                        onApprove = { approve(item) },
                        onDismiss = { remove(item) },
                        modifier = Modifier.listTile(index, visible.size),
                    )
                }
                if (visible.size > 1) {
                    item {
                        val height = ButtonDefaults.MediumContainerHeight
                        Button(
                            onClick = ::approveAll,
                            shapes = ButtonDefaults.shapesFor(height),
                            enabled = !isProcessing,
                            contentPadding = ButtonDefaults.contentPaddingFor(height),
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = height),
                        ) {
                            if (isProcessing) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current) else Text(L("Approve All"), style = ButtonDefaults.textStyleFor(height))
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        FullScreenSheet(::closeEditor) {
            val accountId = PendingImportApprover.seedAccountId(item.cardHint, store.accounts, store.cardAccountMappings, store.defaultAccountId)
            if (accountId == null) {
                Scaffold(topBar = { AppTopBar(L("Pending Imports"), onBack = ::closeEditor) }) { padding ->
                    EmptyState(L("No Accounts"), Icons.Filled.Inbox, L("Please add an account before editing this import."), Modifier.padding(padding))
                }
            } else {
                Column {
                    PendingImportsLogic.currencyContext(item, store.currentBudgetId, store.currencyCode)?.let { context ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.warning.copy(alpha = 0.12f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(context, color = MaterialTheme.colorScheme.warning)
                            if (PendingImportsLogic.offersCurrencySettings(item, store.currentBudgetId, store.currencyCode)) {
                                TextButton(onClick = {
                                    closeEditor()
                                    nav.push(Route.DisplaySettings)
                                }, modifier = Modifier.testTag("pendingImport.currencySettings")) { Text(L("Currency Settings"), fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                    AddTransactionView(
                        accountId = accountId,
                        onDismiss = ::closeEditor,
                        payee = item.payee.orEmpty(),
                        amountCents = item.amount?.let(Transaction::cents),
                        date = Instant.ofEpochMilli(item.date).atZone(ZoneId.systemDefault()).toLocalDate(),
                        notes = item.rawText,
                        isIncome = item.isIncome,
                        saveOverride = { form -> PendingImportApprover(store).saveEdited(item, form) },
                        onSaved = { imports.remove(item.id) },
                        reviewRequirements = item.reviewRequirements(store.currentBudgetId, store.currencyCode),
                    )
                }
            }
        }
    }
    MessageAlert(L("Import Failed"), errorMessage) { errorMessage = null }
}

/** One queued import; swiping right approves it, left dismisses it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingImportRow(item: PendingImport, onOpen: () -> Unit, onApprove: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> onApprove()
            SwipeToDismissBoxValue.EndToStart -> onDismiss()
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false
    })
    SwipeToDismissBox(
        state,
        modifier = modifier,
        backgroundContent = {
            val (color, icon, alignment) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.positive, Icons.Filled.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.negative, Icons.Filled.Delete, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> return@SwipeToDismissBox
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = alignment) {
                Icon(icon, contentDescription = if (icon == Icons.Filled.Check) L("Approve") else L("Dismiss"), tint = Color.White)
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(item.payee ?: PendingImportsLogic.unknownPayee(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                item.amount?.let { amount ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            PendingImportsLogic.amountString(
                                amount,
                                item.isIncome,
                                PendingImport.normalizedCurrencyCode(store.currencyCode),
                                item.sourceCurrencyCode,
                                store.useNarrowCurrencySymbol,
                                store.numberFormat,
                                Locale.getDefault(),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (item.isIncome) MaterialTheme.colorScheme.positive else Color.Unspecified,
                        )
                        if (item.sourceCurrencyCode == null) {
                            Text(
                                L("Currency unknown: %@ budget", PendingImport.normalizedCurrencyCode(store.currencyCode)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.warning,
                            )
                        }
                    }
                }
            }
            Row {
                item.cardHint?.let { Text(PendingImportsLogic.cardLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.weight(1f))
                Text(
                    Instant.ofEpochMilli(item.date).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.rawText.isNotEmpty()) {
                Text(item.rawText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}
