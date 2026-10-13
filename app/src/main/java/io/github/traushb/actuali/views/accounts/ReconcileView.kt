package io.github.traushb.actuali.views.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.DateStyle
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.groupedByDate
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.clearedBalance
import io.github.traushb.actuali.services.createReconciliationAdjustment
import io.github.traushb.actuali.services.fetchRecentStatements
import io.github.traushb.actuali.services.fetchStatementTransactions
import io.github.traushb.actuali.services.lockClearedTransactions
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AnimatedAmountText
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.transactions.EditTransactionView
import io.github.traushb.actuali.views.transactions.TransactionListRow
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Reconcile an account against the real bank balance, Actual's desktop flow:
 * compare the entered bank balance with the cleared balance, offer a
 * balance-adjustment transaction while they differ, and lock every cleared
 * transaction once they match.
 */
@Composable
fun ReconcileView(account: Account, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var clearedBalance by remember { mutableStateOf<Long?>(null) }
    var balanceText by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }

    suspend fun loadClearedBalance(prefill: Boolean) {
        clearedBalance = store.clearedBalance(account.id)
        val cleared = clearedBalance
        // A plain editable number, prefilled to "already matches" like upstream's menu.
        if (prefill && cleared != null && balanceText.isEmpty()) balanceText = "%.2f".format(Locale.ROOT, cleared / 100.0)
    }
    LaunchedEffect(account.id) { loadClearedBalance(prefill = true) }

    val target = AmountParser.parse(balanceText)?.let(Transaction::cents)
    val difference = clearedBalance?.let { cleared -> target?.let { it - cleared } }

    Scaffold(topBar = { AppTopBar(L("Reconcile"), onBack = onDismiss) }) { padding ->
        FormColumn(padding) {
            FormSection(footer = L("reconcile.balancePrompt")) {
                // Deliberately bypasses the hide-balances mask: comparing exact amounts against the bank is the whole point.
                FormRow(L("reconcile.clearedBalance"), trailing = {
                    clearedBalance?.let { AnimatedAmountText(store.formatCurrency(it), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)) }
                        ?: LoadingIndicator(Modifier.size(32.dp))
                })
                // Credit-card and overdrawn accounts reconcile against a negative bank balance, so the sign toggle is needed.
                FormAmountRow(L("reconcile.bankBalance"), balanceText, { balanceText = it }, allowsNegative = true)
            }
            when {
                difference == 0L -> FormSection(footer = L("Locking marks every cleared transaction as reconciled so it can't be changed by accident.")) {
                    FormRow(
                        L("All reconciled!"),
                        leading = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.positive) },
                    )
                    FormButtonRow(L("Lock Cleared Transactions"), {
                        scope.launch {
                            isWorking = true
                            store.lockClearedTransactions(account.id)
                            isWorking = false
                            onDismiss()
                        }
                    }, enabled = !isWorking)
                }
                difference != null -> FormSection(
                    footer = L(
                        "Your cleared balance needs %@ to match the bank. The adjustment is a cleared transaction for that amount; you can lock afterwards.",
                        store.formatCurrency(difference),
                    ),
                ) {
                    // Signed, so a shortfall and a surplus read differently at a glance.
                    FormRow(L("Difference"), trailing = {
                        AnimatedAmountText((if (difference > 0) "+" else "") + store.formatCurrency(difference), color = MaterialTheme.colorScheme.warning)
                    })
                    FormButtonRow(L("Create Adjustment Transaction"), {
                        scope.launch {
                            isWorking = true
                            // The cleared balance then includes the adjustment, so the difference collapses to zero and Lock appears.
                            if (store.createReconciliationAdjustment(account.id, difference)) loadClearedBalance(prefill = false)
                            isWorking = false
                        }
                    }, enabled = !isWorking)
                }
                clearedBalance != null && balanceText.isNotEmpty() -> FormSection { FormRow(L("Enter a valid amount to compare balances.")) }
            }
        }
    }
}

/** One closed credit card statement: its figures, then the transactions inside its period. */
@Composable
fun CreditCardStatementDetailView(account: Account, initial: CreditCardCycle.StatementRecord, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var statement by remember { mutableStateOf(initial) }
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    suspend fun load() {
        isLoading = true
        transactions = store.fetchStatementTransactions(account.id, statement.startDate.yyyymmdd, statement.endDate.yyyymmdd)
        isLoading = false
    }
    LaunchedEffect(Unit) { load() }
    fun date(day: io.github.traushb.actuali.services.schedules.DayDate) = Transaction.formattedDate(day.yyyymmdd, DateStyle.Abbreviated)

    Scaffold(topBar = { AppTopBar(L("Statement Details"), onBack = onDismiss) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    FormSection {
                        FormRow(L("Statement Period"), value = "${date(statement.startDate)} – ${date(statement.endDate)}")
                        FormRow(L("Statement Balance"), value = store.displayBalance(statement.statementBalance))
                        FormRow(L("Payment Due"), value = date(statement.dueDate))
                        if (statement.isPaid) {
                            FormRow(L("Status"), trailing = { Text(L("Paid"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.positive) })
                        } else {
                            FormRow(L("Remaining Due"), value = store.displayBalance(statement.remainingDue))
                        }
                        FormRow(L("Cycle Spend"), value = store.displayBalance(statement.totalSpend))
                        if (statement.paymentsSince > 0) FormRow(L("Payments & Credits"), value = store.displayBalance(statement.paymentsSince))
                    }
                }
            }
            when {
                transactions.isEmpty() && isLoading -> item { DelayedLoadingIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                transactions.isEmpty() -> item { EmptyState(L("No transactions in this statement"), Icons.Filled.Inbox) }
                else -> {
                    val grouped = store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate
                    val sections = if (grouped) transactions.groupedByDate().map { it.title to it.transactions } else listOf(L("Transactions") to transactions)
                    // Each day (or the flat list) is one segmented group of tiles, as in the account's own list.
                    for ((title, rows) in sections) {
                        item(key = "title-$title") { ListSectionHeader(title) }
                        itemsIndexed(rows, key = { _, it -> it.id }) { index, transaction ->
                            TransactionListRow(transaction, isSelectionMode = false, onSelectionModeChange = {}, onEdit = { editing = it }, Modifier.listTile(index, rows.size), showAccount = false, showDate = !grouped)
                        }
                    }
                }
            }
        }
    }

    editing?.let { transaction ->
        FullScreenSheet(onDismiss = { editing = null }) {
            EditTransactionView(transaction) {
                editing = null
                scope.launch {
                    load()
                    // An edit can move the statement's figures — or the statement itself, which then closes.
                    store.fetchRecentStatements(account.id).firstOrNull { it.id == statement.id }?.let { statement = it } ?: onDismiss()
                }
            }
        }
    }
}
