package io.github.traushb.actuali.views.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionType
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.BudgetStoreError
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.createSchedule
import io.github.traushb.actuali.services.deleteSchedule
import io.github.traushb.actuali.services.fetchScheduleTransactions
import io.github.traushb.actuali.services.linkTransactions
import io.github.traushb.actuali.services.resolvePayeeId
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleConditions
import io.github.traushb.actuali.services.schedules.ScheduleDescription
import io.github.traushb.actuali.services.schedules.ScheduleFormFields
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import io.github.traushb.actuali.services.stringOrNull
import io.github.traushb.actuali.services.updateSchedule
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.MessageAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** "Use the budget default" rides in the picker as an empty string. */
private const val DEFAULT_UPCOMING_LENGTH = ""

/**
 * Create or edit a schedule (GH #221), mirroring the web's schedule form:
 * name, payee or transfer destination, account, amount with an operator, a
 * one-off or recurring date, and the auto-post flag.
 */
@Composable
fun ScheduleEditView(editing: ScheduleSummary?) {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val initial = remember(editing) { ScheduleFormState.from(editing, store) }
    var form by remember(editing) { mutableStateOf(initial) }
    var editingRecurrence by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var linkedTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    // The rule carries a date condition that couldn't be parsed: the form would
    // fall back to a one-off today, and saving that would overwrite the stored
    // recurrence on the server. Refuse instead. No date condition at all is
    // the broken-rule repair case, and still saves.
    val hasUnreadableDate = editing?.dateCondition == ScheduleDateCondition.Unsupported && editing.dateOp != null

    suspend fun loadLinked(id: String) {
        linkedTransactions = store.fetchScheduleTransactions(id)
    }
    if (editing != null) LaunchedEffect(editing.id) { loadLinked(editing.id) }

    fun run(work: suspend () -> Unit) = scope.launch {
        isSaving = true
        try {
            work()
            nav.pop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.localizedMessage
        } finally {
            isSaving = false
        }
    }

    if (editingRecurrence) {
        RecurrenceEditorView(form.recurrence, { form = form.copy(recurrence = it) }, onBack = { editingRecurrence = false })
        return
    }

    val openAccounts = store.accounts.filter { !it.closed }
    Scaffold(
        topBar = {
            AppTopBar(
                if (editing != null) L("Edit Schedule") else L("New Schedule"),
                actions = {
                    TextButton(
                        onClick = { run { form.buildFields(store, editing).let { if (editing != null) store.updateSchedule(editing, it) else store.createSchedule(it) } } },
                        enabled = !isSaving && form.accountId != null && !hasUnreadableDate,
                    ) { Text(L("Save")) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            FormColumn(padding) {
                if (hasUnreadableDate) {
                    Notice(Icons.Outlined.WarningAmber, L("This schedule repeats on a pattern Actuali can't read, so it can't be edited here — saving would replace the pattern. Edit it in Actual instead."))
                } else if (editing?.isCustom == true) {
                    Notice(Icons.Outlined.Info, L("This schedule has extra rule conditions set up in Actual. They're preserved when you save, but can't be edited here."))
                }
                FormSection(footer = if (form.txType != TransactionType.Transfer) L("A schedule needs an account. Leaving the payee blank matches only transactions that have no payee.") else null) {
                    FormTextFieldRow(form.name, { form = form.copy(name = it) }, L("Name"))
                    if (form.txType == TransactionType.Transfer) {
                        val eligible = openAccounts.filter { it.id != form.accountId }
                        FormPickerRow(
                            L("Transfer to"),
                            listOf<String?>(null) + eligible.map { it.id },
                            form.transferToAccountId,
                            optionLabel = { id -> id?.let { store.accounts.firstOrNull { it.id == id }?.name } ?: L("Select an account") },
                            onSelect = { form = form.copy(transferToAccountId = it) },
                        )
                    } else {
                        FormTextFieldRow(
                            form.payeeName,
                            { form = form.copy(payeeName = it) },
                            L("Payee"),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        )
                    }
                    FormPickerRow(
                        L("Account"),
                        listOf<String?>(null) + openAccounts.map { it.id },
                        form.accountId,
                        optionLabel = { id -> id?.let { store.accounts.firstOrNull { it.id == id }?.name } ?: L("Select an account") },
                        onSelect = { id -> form = form.copy(accountId = id, transferToAccountId = form.transferToAccountId.takeIf { it != id }) },
                    )
                }

                FormSection(L("Amount")) {
                    FormSegmentedRow(
                        listOf(TransactionType.Expense, TransactionType.Income, TransactionType.Transfer),
                        form.txType,
                        optionLabel = {
                            when (it) {
                                TransactionType.Expense -> L("Expense")
                                TransactionType.Income -> L("Income")
                                TransactionType.Transfer -> L("Transfer")
                            }
                        },
                        onSelect = { form = form.copy(txType = it) },
                    )
                    FormPickerRow(L("Matches"), ScheduleAmountOp.entries, form.amountOp, { it.label() }, { form = form.copy(amountOp = it) })
                    FormAmountRow(if (form.amountOp == ScheduleAmountOp.IsBetween) L("From") else L("Amount"), form.amountText, { form = form.copy(amountText = it) })
                    if (form.amountOp == ScheduleAmountOp.IsBetween) {
                        FormAmountRow(L("To"), form.amountHighText, { form = form.copy(amountHighText = it) })
                    }
                }

                FormSection(
                    L("Date"),
                    footer = if (form.repeats) {
                        "${L("Next: ")}${ScheduleRecurrence.upcomingDates(form.recurrence.config, 1).joinToString("") { ScheduleDescription.mediumDate(it) }}"
                    } else {
                        null
                    },
                ) {
                    FormToggleRow(L("Repeats"), form.repeats, { form = form.copy(repeats = it) })
                    if (form.repeats) {
                        FormRow(L("Repeat"), value = ScheduleDescription.recurring(form.recurrence.config), navigates = true, onClick = { editingRecurrence = true })
                    } else {
                        FormDateRow(L("Date"), form.oneOffDate, { form = form.copy(oneOffDate = it) })
                    }
                }

                FormSection(footer = L("Automatically added transactions are created on your server when the app opens.")) {
                    FormToggleRow(L("Automatically Add Transaction"), form.postsTransaction, { form = form.copy(postsTransaction = it) })
                    FormPickerRow(
                        L("Upcoming Window"),
                        listOf(DEFAULT_UPCOMING_LENGTH, "1", "7", "14", "oneMonth", "currentMonth"),
                        form.upcomingLength,
                        optionLabel = {
                            when (it) {
                                "1" -> L("1 Day")
                                "7" -> L("1 Week")
                                "14" -> L("2 Weeks")
                                "oneMonth" -> L("1 Month")
                                "currentMonth" -> L("Rest of Month")
                                else -> L("Budget Default")
                            }
                        },
                        onSelect = { form = form.copy(upcomingLength = it) },
                    )
                }

                if (editing != null) {
                    FormSection(L("Linked Transactions")) {
                        if (linkedTransactions.isEmpty()) FormRow(L("No transactions linked yet."))
                        linkedTransactions.forEach { transaction ->
                            ListItem(
                                headlineContent = { Text(transaction.payeeName ?: L("No payee")) },
                                supportingContent = { Text(transaction.dateFormatted) },
                                trailingContent = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(store.displayBalance(transaction.amount))
                                        TextButton({
                                            scope.launch {
                                                runCatching { store.linkTransactions(listOf(transaction), null) }
                                                loadLinked(editing.id)
                                            }
                                        }) { Text(L("Unlink")) }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    FormSection { FormButtonRow(L("Delete Schedule"), { confirmingDelete = true }, destructive = true) }
                }
            }
            if (isSaving) ContainedLoadingIndicator(Modifier.align(Alignment.Center))
        }
    }

    MessageAlert(L("Couldn't Save Schedule"), errorMessage) { errorMessage = null }
    if (confirmingDelete && editing != null) {
        ConfirmationDialog(
            title = L("Delete this schedule?"),
            message = L("Transactions this schedule already created are kept."),
            confirmLabel = L("Delete Schedule"),
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = { run { store.deleteSchedule(editing) } },
        )
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** The schedule form's fields. Amounts are edited as a magnitude plus a direction, the way the transaction form does. */
private data class ScheduleFormState(
    val name: String,
    val payeeName: String,
    val accountId: String?,
    val transferToAccountId: String?,
    val txType: TransactionType,
    val amountOp: ScheduleAmountOp,
    val amountText: String,
    val amountHighText: String,
    val repeats: Boolean,
    val oneOffDate: LocalDate,
    val recurrence: RecurrenceDraft,
    val postsTransaction: Boolean,
    val upcomingLength: String,
) {
    /**
     * The form in the write path's shape, resolving (or creating) the payee on
     * the way — same as the transaction form. A transfer uses the destination
     * account's existing transfer payee.
     */
    suspend fun buildFields(store: BudgetStore, editing: ScheduleSummary?): ScheduleFormFields {
        val sign = if (txType == TransactionType.Income) 1 else -1
        val amount = if (amountOp == ScheduleAmountOp.IsBetween) {
            val low = cents(amountText) ?: throw BudgetStoreError.InvalidAmount
            val high = cents(amountHighText) ?: throw BudgetStoreError.InvalidAmount
            // Signing flips the ordering for expenses; Actual stores num1 <= num2.
            val a = sign * low
            val b = sign * high
            ScheduledAmount.Range(min(a, b), max(a, b))
        } else {
            ScheduledAmount.Fixed(sign * (cents(amountText) ?: throw BudgetStoreError.InvalidAmount))
        }
        val date = if (repeats) ScheduleDateCondition.Recurring(recurrence.config) else ScheduleDateCondition.Fixed(DayDate.of(oneOffDate))

        val payeeId = if (txType == TransactionType.Transfer) {
            val destination = transferToAccountId
            if (accountId == null || destination == null || accountId == destination) throw BudgetStoreError.MissingTransferDestination
            val transferPayee = store.payees.firstOrNull { it.transferAccountId == destination && !it.tombstone } ?: throw BudgetStoreError.TransferPayeeMissing
            // Schedule conditions store the payee_mapping id while the summary
            // exposes its target payee. Keep the raw mapping when the
            // destination is unchanged so imported mappings survive an
            // otherwise unrelated edit.
            val existingRawPayeeId = ScheduleConditions.parse(editing?.conditionsJSON).firstOrNull { condition ->
                condition["field"].stringOrNull in setOf("payee", "description") && condition["op"].stringOrNull == "is"
            }?.get("value").stringOrNull
            if (editing?.payeeId == transferPayee.id) existingRawPayeeId ?: transferPayee.id else transferPayee.id
        } else {
            store.resolvePayeeId(payeeName.trim(), null)
        }

        return ScheduleFormFields(
            name = name,
            payeeId = payeeId,
            accountId = accountId,
            amount = amount,
            amountOp = amountOp,
            date = date,
            postsTransaction = postsTransaction,
            customUpcomingLength = upcomingLength.ifEmpty { null },
        )
    }

    private fun cents(text: String): Long? = AmountParser.parse(text)?.let { Transaction.cents(abs(it)) }

    companion object {
        fun from(editing: ScheduleSummary?, store: BudgetStore): ScheduleFormState {
            val payee = editing?.payeeId?.let { id -> store.payees.firstOrNull { it.id == id } }
            val transferAccountId = payee?.transferAccountId
            val isIncome = (editing?.postAmount ?: -1) > 0
            val (low, high) = when (val amount = editing?.amount) {
                is ScheduledAmount.Range -> dollars(min(abs(amount.num1), abs(amount.num2))) to dollars(max(abs(amount.num1), abs(amount.num2)))
                is ScheduledAmount.Fixed -> dollars(abs(amount.cents)) to ""
                else -> "" to ""
            }
            val condition = editing?.dateCondition
            return ScheduleFormState(
                name = editing?.name.orEmpty(),
                payeeName = payee?.name.orEmpty(),
                accountId = editing?.accountId ?: store.accounts.firstOrNull { !it.closed }?.id,
                transferToAccountId = transferAccountId,
                txType = when {
                    transferAccountId != null -> TransactionType.Transfer
                    isIncome -> TransactionType.Income
                    else -> TransactionType.Expense
                },
                amountOp = editing?.amountOp ?: ScheduleAmountOp.IsApprox,
                amountText = low,
                amountHighText = high,
                repeats = condition is ScheduleDateCondition.Recurring,
                oneOffDate = (condition as? ScheduleDateCondition.Fixed)?.day?.localDate ?: LocalDate.now(),
                recurrence = RecurrenceDraft.from((condition as? ScheduleDateCondition.Recurring)?.config),
                postsTransaction = editing?.postsTransaction ?: false,
                upcomingLength = editing?.customUpcomingLength ?: DEFAULT_UPCOMING_LENGTH,
            )
        }

        private fun dollars(cents: Long): String = if (cents == 0L) "" else "%.2f".format(java.util.Locale.ROOT, cents / 100.0)
    }
}
