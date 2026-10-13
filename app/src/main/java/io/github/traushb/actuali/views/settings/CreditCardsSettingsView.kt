package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.fetchCycleSpend
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.balanceColor
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.launch

private enum class DueDateMode { DaysAfter, DayOfMonth }

/** The card form's fields; [limitText] is the dot-decimal amount as typed, empty for "no limit". */
private data class CardDraft(
    val accountId: String,
    val statementDay: Int = 15,
    val dueMode: DueDateMode = DueDateMode.DaysAfter,
    val dueOffset: Int = CreditCardCycle.DEFAULT_DUE_OFFSET_DAYS,
    val dueDay: Int = 1,
    val limitText: String = "",
) {
    val paymentDue: CreditCardCycle.PaymentDue
        get() = if (dueMode == DueDateMode.DayOfMonth) CreditCardCycle.PaymentDue.DayOfMonth(dueDay) else CreditCardCycle.PaymentDue.DaysAfter(dueOffset)

    /** null for an empty, unparseable or non-positive entry — all of which mean "no limit", not an error worth blocking the save on. */
    val limitCents: Long? get() = AmountParser.parse(limitText)?.let(Transaction::cents)?.takeIf { it > 0 }
}

/**
 * Soonest payment first for cards with an unpaid balance; accounts with a
 * zero (or overpaid) balance sort at the very end. The name tie-break makes
 * this a total order: `daysUntilDue` clamps at 0, so every past-due card ties
 * there. [today] is sampled once so the order can't change mid-sort at midnight.
 */
fun sortedCards(cards: List<Pair<Account, CreditCardCycle>>, today: DayDate = DayDate.today()): List<Pair<Account, CreditCardCycle>> =
    cards.sortedWith(compareBy({ if (it.first.balance >= 0) 1 else 0 }, { it.second.daysUntilDue(today) }, { it.first.name }))

/** Credit card accounts and their monthly billing cycles. */
@Composable
fun CreditCardsSettingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<CardDraft?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    val accountsById = store.accounts.associateBy { it.id }
    val cards = sortedCards(store.activeCreditCardStatementDays.keys.mapNotNull { id -> accountsById[id]?.let { account -> store.creditCardCycle(id)?.let { account to it } } })
    val configuredIds = store.creditCardStatementDays.keys
    val unconfigured = store.accounts.filter { !it.closed && it.id !in configuredIds }
        .sortedWith(compareBy({ if (it.type == AccountType.Credit) 0 else 1 }, { it.name }))
    val detached = store.syncDetachedByRestore

    Scaffold(topBar = { AppTopBar(L("Credit Cards")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Configured Credit Cards")) {
                if (cards.isEmpty()) {
                    FormRow(L("Mark accounts as credit cards and track their monthly billing cycles, cycle spend, and upcoming payment due dates."))
                }
                cards.forEach { (account, cycle) ->
                    CreditCardCycleRow(
                        account,
                        cycle,
                        Modifier.clickable(enabled = !detached) {
                            val due = cycle.paymentDue
                            draft = CardDraft(
                                accountId = account.id,
                                statementDay = cycle.statementDay,
                                dueMode = if (due is CreditCardCycle.PaymentDue.DayOfMonth) DueDateMode.DayOfMonth else DueDateMode.DaysAfter,
                                dueOffset = (due as? CreditCardCycle.PaymentDue.DaysAfter)?.days ?: CreditCardCycle.DEFAULT_DUE_OFFSET_DAYS,
                                dueDay = (due as? CreditCardCycle.PaymentDue.DayOfMonth)?.day ?: 1,
                                limitText = store.creditCardLimits[account.id]?.takeIf { it != 0L }?.let { "%.2f".format(java.util.Locale.ROOT, it / 100.0) }.orEmpty(),
                            )
                            isEditing = true
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        trailing = if (detached) null else {
                            {
                                IconButton(onClick = { scope.launch { store.setCreditCard(account.id, statementDay = null, limit = null) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = L("Remove Credit Card Tracking"))
                                }
                            }
                        },
                    )
                }
            }
            if (detached) {
                FormSection { FormRow(L("Credit card settings sync with your budget. Re-download this budget to change them.")) }
            } else if (unconfigured.isNotEmpty()) {
                FormSection {
                    FormButtonRow(L("Add Credit Card"), {
                        draft = CardDraft(unconfigured.first().id)
                        isEditing = false
                    })
                }
            }
        }
    }

    draft?.let { current ->
        FormSheet(
            title = if (isEditing) L("Edit Card") else L("Add Credit Card"),
            onDismiss = { draft = null },
            confirmEnabled = current.accountId.isNotEmpty(),
            onConfirm = {
                scope.launch { store.setCreditCard(current.accountId, current.statementDay, current.paymentDue, current.limitCents) }
                draft = null
            },
        ) {
            FormSection(
                L("Card Details"),
                footer = L(
                    "The payment due date is either a set number of days after the statement closes, or a fixed day of the month. Your issuer sets it — check a recent statement, as it varies by card and country.\n\nA credit limit shows available credit on the account. Leave it empty to skip.",
                ),
            ) {
                if (isEditing) {
                    FormRow(L("Account"), value = accountsById[current.accountId]?.name)
                } else {
                    FormPickerRow(L("Account"), unconfigured.map { it.id }, current.accountId, { id -> accountsById[id]?.name.orEmpty() }, { draft = current.copy(accountId = it) })
                }
                FormPickerRow(L("Statement Closing Day"), (1..31).toList(), current.statementDay, { LocaleFormatting.ordinal(it) }, { draft = current.copy(statementDay = it) })
                FormSegmentedRow(
                    DueDateMode.entries,
                    current.dueMode,
                    { if (it == DueDateMode.DaysAfter) L("Days After") else L("Day of Month") },
                    { draft = current.copy(dueMode = it) },
                )
                if (current.dueMode == DueDateMode.DaysAfter) {
                    FormPickerRow(
                        L("Payment Due After"),
                        (1..CreditCardCycle.MAX_DUE_OFFSET_DAYS).toList(),
                        current.dueOffset,
                        { if (it == 1) L("1 Day") else L("%lld days", it.toLong()) },
                        { draft = current.copy(dueOffset = it) },
                    )
                } else {
                    FormPickerRow(L("Payment Due Day"), (1..31).toList(), current.dueDay, { LocaleFormatting.ordinal(it) }, { draft = current.copy(dueDay = it) })
                }
                FormAmountRow(L("Credit Limit"), current.limitText, { draft = current.copy(limitText = it) })
            }
            if (isEditing) {
                FormSection {
                    FormButtonRow(L("Remove Credit Card Tracking"), {
                        scope.launch { store.setCreditCard(current.accountId, statementDay = null, limit = null) }
                        draft = null
                    }, destructive = true)
                }
            }
        }
    }
}

/**
 * Compact card row: name and balance on top, cycle spend and a due pill
 * beneath. Urgency color lives in the pill: red ≤3 days, orange ≤7, yellow
 * otherwise.
 */
@Composable
fun CreditCardCycleRow(account: Account, cycle: CreditCardCycle, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val store = LocalBudgetStore.current
    var cycleSpend by remember { mutableLongStateOf(0L) }
    val range = cycle.cycleRange()
    // dataVersion is a key so a transaction landing while this screen is open refreshes the spend.
    LaunchedEffect(cycle.statementDay, store.dataVersion) { cycleSpend = store.fetchCycleSpend(account.id, range.first, range.second) }

    val statementDue = store.creditCardStatementDues[account.id]?.let { CreditCardCycle.pendingStatementDue(it) }
    val isPaid = statementDue?.isPaid ?: false
    fun dueText(short: Boolean): String {
        if (isPaid) return L("Paid")
        val summary = if (short) cycle.dueShortSummary(dueDate = statementDue?.dueDate) else cycle.dueSummary(dueDate = statementDue?.dueDate)
        val due = statementDue ?: return summary
        return CreditCardCycle.duePillText(store.displayBalance(due.remainingDue), summary, due.remainingDue)
    }
    val days = cycle.daysUntilDue(dueDate = statementDue?.dueDate)
    val pillColor = when {
        isPaid -> MaterialTheme.colorScheme.positive
        days <= 3 -> MaterialTheme.colorScheme.negative
        days <= 7 -> MaterialTheme.colorScheme.warning
        else -> Color(0xFFFFCC00)
    }
    val description = L("%@, balance %@, cycle spend %@, %@", account.name, store.displayBalance(account.balance), store.displayBalance(cycleSpend), dueText(short = false))
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row {
                Text(account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Text(store.displayBalance(account.balance), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = balanceColor(account.balance))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L("Spend %@ · %lldd left", store.displayBalance(cycleSpend), cycle.daysRemainingInCycle().toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    dueText(short = true),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(pillColor.copy(alpha = 0.22f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}
