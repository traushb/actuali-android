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
import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.roundedHalfAwayFromZero
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.deposits.DepositGrowth
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ChartSeries
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.LineChart
import io.github.traushb.actuali.views.components.sparseLabels
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Which accounts are tracked as deposits and the terms behind each — the
 * deposit-side twin of the Loans screen.
 */
@Composable
fun DepositsSettingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<DepositEditorMode?>(null) }
    val accountsById = store.accounts.associateBy { it.id }
    val deposits = store.activeDepositConfigs.mapNotNull { (id, config) -> accountsById[id]?.let { it to config } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.name })
    val canAdd = DepositEditor.eligibleAccounts(store.accounts, store.trackedAccountIds).isNotEmpty()
    val detached = store.syncDetachedByRestore

    Scaffold(topBar = { AppTopBar(L("Deposits")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Configured Deposits")) {
                if (deposits.isEmpty()) FormRow(L("Track a fixed or recurring deposit's term, rate and maturity, and watch the interest build up."))
                deposits.forEach { (account, config) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DepositSummaryRow(
                            account,
                            config,
                            Modifier.weight(1f).clickable(enabled = !detached) { editor = DepositEditorMode.Edit(account.id, config) }
                                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("depositRow.${account.id}"),
                        )
                        if (!detached) {
                            IconButton(onClick = { scope.launch { store.setDeposit(account.id, null) } }) { Icon(Icons.Filled.Delete, contentDescription = L("Remove Deposit Tracking")) }
                        }
                    }
                }
            }
            if (detached) {
                FormSection { FormRow(L("Deposit settings sync with your budget. Re-download this budget to change them.")) }
            } else if (canAdd) {
                FormSection { FormButtonRow(L("Add Deposit"), { editor = DepositEditorMode.Add }, Modifier.testTag("depositSettings.add")) }
            }
        }
    }
    editor?.let { DepositEditorView(it) { editor = null } }
}

/** Compact deposit row: name and what it's worth today on top, rate, maturity and progress through the term beneath. */
@Composable
fun DepositSummaryRow(account: Account, config: DepositConfig, modifier: Modifier = Modifier, today: DayDate = DayDate.today()) {
    val store = LocalBudgetStore.current
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(account.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(store.displayBalance(config.value(today)), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { config.fractionElapsed(today).toFloat() }, modifier = Modifier.fillMaxWidth())
        Row {
            Text(LoanSummaryText.percentText(config.annualRatePercent / 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(DepositEditor.maturitySummary(config, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

sealed interface DepositEditorMode {
    data object Add : DepositEditorMode

    data class Edit(val accountId: String, val config: DepositConfig) : DepositEditorMode
}

/** The deposit editor's pure rules, apart from the screen so they can be tested without one. */
object DepositEditor {
    /** Open accounts not yet tracked as anything, savings-ish types first. */
    fun eligibleAccounts(accounts: List<Account>, tracked: Set<String>): List<Account> =
        accounts.filter { !it.closed && it.id !in tracked }.sortedWith(compareBy({ typeRank(it.type) }, { it.name }))

    /** Savings and investment accounts lead: a deposit is money put aside. Debt types go last — they run the other way. */
    fun typeRank(type: AccountType): Int = when (type) {
        AccountType.Savings, AccountType.Investment -> 0
        AccountType.Credit, AccountType.Mortgage, AccountType.Debt -> 2
        else -> 1
    }

    fun months(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }?.let { min(it, DepositGrowth.MAX_TERM_MONTHS) }

    /** null when the entry can't make a usable deposit: it needs a positive amount and a term; a zero rate is real if joyless. */
    fun config(kind: DepositConfig.Kind, amount: String, rate: String, compounding: DepositConfig.Compounding, openedOn: DayDate, term: String): DepositConfig? {
        val cents = LoanEditor.cents(amount)?.takeIf { it > 0 } ?: return null
        val termMonths = months(term) ?: return null
        return DepositConfig(kind, cents, max(0.0, LoanEditor.rate(rate) ?: 0.0), compounding, openedOn, termMonths)
    }

    fun compoundingLabel(frequency: DepositConfig.Compounding): String = when (frequency) {
        DepositConfig.Compounding.Annually -> L("Annually")
        DepositConfig.Compounding.SemiAnnually -> L("Semi-annually")
        DepositConfig.Compounding.Quarterly -> L("Quarterly")
        DepositConfig.Compounding.Monthly -> L("Monthly")
    }

    /** "Matures 15 Jan 2028", or "Matured" — the deposit keeps its row after maturity because it still has a final value. */
    fun maturitySummary(config: DepositConfig, today: DayDate = DayDate.today(), locale: Locale = Locale.getDefault()): String =
        if (config.hasMatured(today)) L("Matured") else L("Matures %@", config.maturityDate.displayText(locale))
}

/** Add or edit one deposit's terms. */
@Composable
fun DepositEditorView(mode: DepositEditorMode, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val existing = (mode as? DepositEditorMode.Edit)?.config
    val eligible = DepositEditor.eligibleAccounts(store.accounts, store.trackedAccountIds)
    var accountId by remember { mutableStateOf((mode as? DepositEditorMode.Edit)?.accountId ?: eligible.firstOrNull()?.id.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: DepositConfig.Kind.Fixed) }
    var amountText by remember { mutableStateOf(existing?.let { LoanEditor.amountText(it.amount) }.orEmpty()) }
    var rateText by remember { mutableStateOf(existing?.let { LoanEditor.rateText(it.annualRatePercent) }.orEmpty()) }
    var compounding by remember { mutableStateOf(existing?.compounding ?: DepositConfig.Compounding.Quarterly) }
    var openedOn by remember { mutableStateOf(existing?.openedOn?.localDate ?: LocalDate.now()) }
    var termText by remember { mutableStateOf(existing?.termMonths?.toString().orEmpty()) }
    val entered = DepositEditor.config(kind, amountText, rateText, compounding, DayDate.of(openedOn), termText)

    FormSheet(
        title = if (existing != null) L("Edit Deposit") else L("Add Deposit"),
        onDismiss = onDismiss,
        confirmEnabled = accountId.isNotEmpty() && entered != null,
        onConfirm = {
            entered?.let { config -> scope.launch { store.setDeposit(accountId, config) } }
            onDismiss()
        },
    ) {
        FormSection(
            L("Deposit Details"),
            footer = L(
                "A fixed deposit is one lump sum left for the term. A recurring deposit takes the same amount every month.\n\nInterest is credited on whole compounding periods, so the value steps up at the end of each one rather than growing smoothly — the same way your bank reports it.",
            ),
        ) {
            if (existing != null) {
                FormRow(L("Account"), value = store.accounts.firstOrNull { it.id == accountId }?.name)
            } else {
                FormPickerRow(L("Account"), eligible.map { it.id }, accountId, { id -> eligible.firstOrNull { it.id == id }?.name.orEmpty() }, { accountId = it }, Modifier.testTag("depositEditor.account"))
            }
            FormPickerRow(
                L("Type"),
                DepositConfig.Kind.entries,
                kind,
                { if (it == DepositConfig.Kind.Recurring) L("Recurring Deposit") else L("Fixed Deposit") },
                { kind = it },
                Modifier.testTag("depositEditor.kind"),
            )
            // The instalment on a recurring deposit, the lump sum on a fixed one.
            FormAmountRow(if (kind == DepositConfig.Kind.Recurring) L("Monthly Instalment") else L("Deposit Amount"), amountText, { amountText = it }, fieldModifier = Modifier.testTag("depositEditor.amount"))
            NumberRow(L("Interest Rate"), rateText, { rateText = it }, "%", KeyboardType.Decimal, "depositEditor.interestRate")
            FormPickerRow(L("Compounding"), DepositConfig.Compounding.entries, compounding, DepositEditor::compoundingLabel, { compounding = it }, Modifier.testTag("depositEditor.compounding"))
            FormDateRow(L("Opened"), openedOn, { openedOn = it }, Modifier.testTag("depositEditor.openedOn"))
            // An over-long term snaps back where it shows, rather than clamping unseen at save.
            NumberRow(L("Term"), termText, { text ->
                termText = text.toIntOrNull()?.takeIf { it > DepositGrowth.MAX_TERM_MONTHS }?.let { DepositGrowth.MAX_TERM_MONTHS.toString() } ?: text
            }, L("months"), KeyboardType.Number, "depositEditor.term")
            // The term is entered in months but thought about as a date, so the maturity is shown.
            entered?.let {
                FormRow(L("Matures"), value = it.maturityDate.displayText(), modifier = Modifier.testTag("depositEditor.matures"))
            }
        }
        if (existing != null) {
            FormSection {
                FormButtonRow(L("Remove Deposit Tracking"), {
                    scope.launch { store.setDeposit(accountId, null) }
                    onDismiss()
                }, Modifier.testTag("depositEditor.remove"), destructive = true)
            }
        }
    }
}

/** A label with a short right-aligned number field and its unit ("%", "months"). */
@Composable
internal fun NumberRow(label: String, text: String, onChange: (String) -> Unit, unit: String, keyboardType: KeyboardType, tag: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    text,
                    onChange,
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    modifier = Modifier.widthIn(max = 100.dp).testTag(tag),
                )
                Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Value against money paid in over the deposit's whole term. The interest is
 * the gap between the two lines; the value steps at each compounding period,
 * when the bank actually credits it.
 */
@Composable
fun DepositGrowthChart(config: DepositConfig) {
    val store = LocalBudgetStore.current
    val points = DepositGrowth.curve(config)
    fun x(day: DayDate) = day.localDate.toEpochDay().toDouble()
    LineChart(
        listOf(
            ChartSeries(L("Deposited"), points.map { x(it.month) to it.deposited / 100.0 }, MaterialTheme.colorScheme.onSurfaceVariant, dashed = true, stepped = true),
            ChartSeries(L("Value"), points.map { x(it.month) to it.value / 100.0 }, MaterialTheme.colorScheme.primary, stepped = true),
        ),
        xLabels = sparseLabels(points.map { it.month }).map { x(it) to "${it.year}" },
        yLabel = { CurrencyAmountFormat.string(roundedHalfAwayFromZero(it * 100).toLong(), store.currencyCode, store.useNarrowCurrencySymbol, numberFormat = store.numberFormat) },
        showsYAxis = !store.hideBalances,
        modifier = Modifier.padding(8.dp),
    )
}
