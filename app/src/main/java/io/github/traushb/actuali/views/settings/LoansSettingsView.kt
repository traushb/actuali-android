package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

/**
 * Which accounts are tracked as loans and the terms behind each — the same
 * synced `preferences` table credit cards use. A closed account keeps its
 * config but drops out of the list.
 */
@Composable
fun LoansSettingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<LoanEditorMode?>(null) }
    val accountsById = store.accounts.associateBy { it.id }
    val loans = store.activeLoanConfigs.mapNotNull { (id, config) -> accountsById[id]?.let { it to config } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.name })
    val canAddLoan = LoanEditor.eligibleAccounts(store.accounts, store.trackedAccountIds).isNotEmpty()
    val detached = store.syncDetachedByRestore

    Scaffold(topBar = { AppTopBar(L("Loans")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Configured Loans")) {
                if (loans.isEmpty()) FormRow(L("Track a loan's balance, interest rate and payoff date, and watch the principal come down as you pay."))
                loans.forEach { (account, config) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoanSummaryRow(
                            account,
                            config,
                            Modifier.weight(1f).clickable(enabled = !detached) { editor = LoanEditorMode.Edit(account.id, config) }
                                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("loanRow.${account.id}"),
                        )
                        if (!detached) {
                            IconButton(onClick = { scope.launch { store.setLoan(account.id, null) } }) { Icon(Icons.Filled.Delete, contentDescription = L("Remove Loan Tracking")) }
                        }
                    }
                }
            }
            when {
                detached -> FormSection { FormRow(L("Loan settings sync with your budget. Re-download this budget to change them.")) }
                canAddLoan -> FormSection { FormButtonRow(L("Add Loan"), { editor = LoanEditorMode.Add() }, Modifier.testTag("loanSettings.add")) }
                // Without this the Add row just vanishes, and an on-budget loan account gives no hint why it can't be picked.
                else -> FormSection { FormRow(L("Loans are tracked on open, off-budget accounts. Add one, or mark your loan's account off-budget, to track it here.")) }
            }
        }
    }
    editor?.let { LoanEditorView(it) { editor = null } }
}

/** Compact loan row: name and what's still owed on top, payoff progress beneath. */
@Composable
fun LoanSummaryRow(account: Account, config: LoanConfig, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val balance = store.accounts.firstOrNull { it.id == account.id }?.balance ?: account.balance
    val fraction = config.fractionPaidOff(balance)
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(account.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(store.displayBalance(balance), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        Row {
            Text(LoanSummaryText.progressText(fraction), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(config.payoffSummary(balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

object LoanSummaryText {
    /** "10.0% Paid Off", matching YNAB's wording on the loan overview. */
    fun progressText(fraction: Double, locale: Locale = Locale.getDefault()): String = L("%@ Paid Off", percentText(fraction, locale))

    fun percentText(fraction: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(fraction)
}

/** Adding picks the account; editing already knows it and primes the fields from the stored config. */
sealed interface LoanEditorMode {
    data class Add(val defaultAccountId: String = "") : LoanEditorMode

    data class Edit(val accountId: String, val config: LoanConfig) : LoanEditorMode
}

/** The loan editor's pure rules, kept apart from the screen so they can be tested without one. */
object LoanEditor {
    /**
     * Open, off-budget accounts not yet tracked as anything, loan-ish types
     * first. Off-budget because a payment's category rides the on-budget leg
     * of a transfer into an off-budget account.
     */
    fun eligibleAccounts(accounts: List<Account>, tracked: Set<String>): List<Account> =
        accounts.filter { !it.closed && it.offBudget && it.id !in tracked }.sortedWith(compareBy({ typeRank(it.type) }, { it.name }))

    /** Mortgage and debt accounts lead the picker; cards go last — YNAB steers card debt to the card type. */
    fun typeRank(type: AccountType): Int = when (type) {
        AccountType.Mortgage, AccountType.Debt -> 0
        AccountType.Credit -> 2
        else -> 1
    }

    /** Dot-decimal, matching what the amount field round-trips. */
    fun amountText(cents: Long): String = "%.2f".format(Locale.ROOT, cents / 100.0)

    /** Trailing zeros trimmed so a whole-number rate reads "6", not "6.00". */
    fun rateText(percent: Double): String = if (percent == Math.rint(percent)) percent.toLong().toString() else percent.toString()

    /**
     * A rate is a plain decimal, never grouped, so it skips AmountParser, whose
     * grouping heuristic reads "6.125" as six thousand. Infinity and NaN can't
     * be rounded to cents by the interest math.
     */
    fun rate(text: String): Double? = text.trim().replace(",", ".").toDoubleOrNull()?.takeIf { it.isFinite() }

    fun cents(text: String): Long? = AmountParser.parse(text)?.let(Transaction::cents)

    /**
     * null when the entry can't make a usable loan, which disables Save: a
     * positive original balance and a payment are required; the rate and
     * escrow may be absent (a 0% family loan is real; escrow is mortgages only).
     */
    fun config(originalBalance: String, rate: String, payment: String, escrow: String, existing: LoanConfig? = null): LoanConfig? {
        val balance = cents(originalBalance)?.takeIf { it > 0 } ?: return null
        val monthly = cents(payment)?.takeIf { it > 0 } ?: return null
        return LoanConfig(
            originalBalance = balance,
            annualRatePercent = max(0.0, rate(rate) ?: 0.0),
            minimumPayment = monthly,
            escrowOrFees = cents(escrow)?.takeIf { it > 0 },
            // The form never shows these, so an edit carries them through
            // rather than silently unpairing the loan or clearing its snooze.
            categoryId = existing?.categoryId,
            targetSnoozedMonth = existing?.targetSnoozedMonth,
        )
    }
}

/** Add or edit one loan's terms; the account screen opens the same editor for the loan it shows. */
@Composable
fun LoanEditorView(mode: LoanEditorMode, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val eligible = LoanEditor.eligibleAccounts(store.accounts, store.trackedAccountIds)
    val existing = (mode as? LoanEditorMode.Edit)?.config
    var accountId by remember {
        mutableStateOf(
            when (mode) {
                is LoanEditorMode.Edit -> mode.accountId
                is LoanEditorMode.Add -> mode.defaultAccountId.ifEmpty { eligible.firstOrNull()?.id.orEmpty() }
            },
        )
    }
    var originalBalance by remember { mutableStateOf(existing?.let { LoanEditor.amountText(it.originalBalance) }.orEmpty()) }
    var payment by remember { mutableStateOf(existing?.let { LoanEditor.amountText(it.minimumPayment) }.orEmpty()) }
    var escrow by remember { mutableStateOf(existing?.escrowOrFees?.let(LoanEditor::amountText).orEmpty()) }
    var rate by remember { mutableStateOf(existing?.let { LoanEditor.rateText(it.annualRatePercent) }.orEmpty()) }
    val entered = LoanEditor.config(originalBalance, rate, payment, escrow, existing)

    FormSheet(
        title = if (existing != null) L("Edit Loan") else L("Add Loan"),
        onDismiss = onDismiss,
        confirmEnabled = accountId.isNotEmpty() && entered != null,
        onConfirm = {
            entered?.let { config -> scope.launch { store.setLoan(accountId, config) } }
            onDismiss()
        },
    ) {
        FormSection(
            L("Loan Details"),
            footer = L(
                "The original balance is what you owed when the loan started — payoff progress is measured against it. Interest is compounded monthly, and each payment covers interest and any escrow before the rest comes off the principal.\n\nEscrow or fees applies to mortgages whose payment bundles them in. Leave it empty to skip.",
            ),
        ) {
            if (existing != null) {
                FormRow(L("Account"), value = store.accounts.firstOrNull { it.id == accountId }?.name)
            } else {
                FormPickerRow(L("Account"), eligible.map { it.id }, accountId, { id -> eligible.firstOrNull { it.id == id }?.name.orEmpty() }, { accountId = it }, Modifier.testTag("loanEditor.account"))
            }
            FormAmountRow(L("Original Balance"), originalBalance, { originalBalance = it }, fieldModifier = Modifier.testTag("loanEditor.originalBalance"))
            ListItem(
                headlineContent = { Text(L("Interest Rate")) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            rate,
                            { rate = it },
                            placeholder = { Text("0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.widthIn(max = 100.dp).testTag("loanEditor.interestRate"),
                        )
                        Text("%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            FormAmountRow(L("Monthly Payment"), payment, { payment = it }, fieldModifier = Modifier.testTag("loanEditor.monthlyPayment"))
            FormAmountRow(L("Escrow or Fees"), escrow, { escrow = it }, fieldModifier = Modifier.testTag("loanEditor.escrow"))
        }
        if (existing != null) {
            FormSection {
                FormButtonRow(L("Remove Loan Tracking"), {
                    scope.launch { store.setLoan(accountId, null) }
                    onDismiss()
                }, Modifier.testTag("loanEditor.remove"), destructive = true)
            }
        }
    }
}
