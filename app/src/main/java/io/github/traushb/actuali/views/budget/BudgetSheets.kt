package io.github.traushb.actuali.views.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.NoteLinkText
import io.github.traushb.actuali.models.QuickAssignSuggestion
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.BudgetStoreError
import io.github.traushb.actuali.services.EnvelopeBudgetSummary
import io.github.traushb.actuali.services.GoalTemplateAction
import io.github.traushb.actuali.services.GoalTemplateOutcome
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.budgetAmountCents
import io.github.traushb.actuali.services.budgetHistory
import io.github.traushb.actuali.services.createCategory
import io.github.traushb.actuali.services.createCategoryGroup
import io.github.traushb.actuali.services.disableAutomaticBudgetBuffer
import io.github.traushb.actuali.services.fetchEnvelopeBudgetSummary
import io.github.traushb.actuali.services.fetchNote
import io.github.traushb.actuali.services.holdBudgetForNextMonth
import io.github.traushb.actuali.services.renameCategory
import io.github.traushb.actuali.services.renameCategoryGroup
import io.github.traushb.actuali.services.resetBudgetBuffer
import io.github.traushb.actuali.services.runGoalTemplates
import io.github.traushb.actuali.services.setBudgetAmount
import io.github.traushb.actuali.services.setBudgetCarryover
import io.github.traushb.actuali.services.shiftBudgetMonth
import io.github.traushb.actuali.services.transferBudget
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.SystemBarsAppearance
import io.github.traushb.actuali.views.budget.automations.BudgetAutomationsSheet
import io.github.traushb.actuali.views.components.AmountInputField
import io.github.traushb.actuali.views.components.ChoiceDialog
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.NoteEditorView
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** "12.50" for 1250 cents — the plain decimal the amount field reads; blank for zero when [blankZero]. */
private fun amountText(cents: Long, blankZero: Boolean = false): String =
    if (blankZero && cents == 0L) "" else String.format(Locale.ROOT, "%.2f", cents / 100.0)

/** A sheet's red error line under its form. */
@Composable
private fun ErrorText(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.negative, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
}

/** The big centered amount field the budget sheets edit with. */
@Composable
private fun SheetAmountField(text: String, onChange: (String) -> Unit, allowsNegative: Boolean = false, autofocus: Boolean = false) {
    val store = LocalBudgetStore.current
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        AmountInputField(
            text,
            onChange,
            Modifier.widthIn(min = 120.dp),
            conventionalAmountEntry = store.conventionalAmountEntry,
            allowsNegative = allowsNegative,
            textStyle = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            autofocus = autofocus,
        )
    }
}

/** Edit the budgeted amount for one category-month; saving writes through the sync engine and refreshes the month. */
@Composable
fun EditBudgetAmountSheet(category: CategoryBudget, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(amountText(category.budgeted, blankZero = true)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    FormSheet(
        category.categoryName,
        onDismiss = { if (!saving) onDismiss() },
        confirmEnabled = !saving,
        onConfirm = {
            saving = true
            errorMessage = null
            scope.launch {
                try {
                    // An emptied field means "no longer budgeted", i.e. zero.
                    val cents = BudgetStore.budgetAmountCents(text.ifEmpty { "0" }, allowNegative = true)
                    store.setBudgetAmount(category.month, category.categoryId, cents)
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    saving = false
                }
            }
        },
    ) {
        FormSection(L("Budgeted in %@", MonthTitles.title(category.month))) {
            SheetAmountField(text, { text = it }, allowsNegative = true, autofocus = true)
        }
        ErrorText(errorMessage)
    }
}

/** The PWA's editor for an envelope budget's manual next-month buffer. */
@Composable
fun BudgetBufferSheet(month: String, available: Long, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(if (available > 0) amountText(available) else "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    FormSheet(
        L("Hold for next month"),
        onDismiss = { if (!saving) onDismiss() },
        confirmLabel = L("Hold"),
        confirmEnabled = !saving,
        onConfirm = {
            saving = true
            errorMessage = null
            scope.launch {
                try {
                    store.holdBudgetForNextMonth(month, BudgetStore.budgetAmountCents(text.ifEmpty { "0" }))
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    saving = false
                }
            }
        },
    ) {
        FormSection(L("Hold this amount"), L("This amount will be removed from this month's To Budget and available to budget next month.")) {
            SheetAmountField(text, { text = it }, autofocus = true)
        }
        ErrorText(errorMessage)
    }
}

/** The other side of a move: the month's unallocated pool, or a category. */
private sealed interface TransferEndpoint {
    data object ToBudget : TransferEndpoint

    data class Category(val id: String) : TransferEndpoint

    val categoryId: String? get() = (this as? Category)?.id
}

/**
 * Move budgeted funds between categories (GH #128). In the red it covers
 * the overspending from To Budget or a category with funds; in the green it
 * sends the surplus to another category or back to To Budget — a paired
 * budgeted adjustment, saved through the sync engine.
 */
@Composable
fun BudgetTransferSheet(context: BudgetTransferContext, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val candidates = remember(context) { context.rankedCategories }
    // Overspent: cover from To Budget, like the web; tracking budgets have no
    // To Budget, so the first category with funds. Surplus: back to To Budget.
    var endpoint by remember(context) {
        mutableStateOf<TransferEndpoint>(
            if (context.canUseToBudget) TransferEndpoint.ToBudget else candidates.firstOrNull()?.let { TransferEndpoint.Category(it.categoryId) } ?: TransferEndpoint.ToBudget,
        )
    }
    // Prefilled with the full amount in play: the overspending to cover, or the surplus to move.
    var text by remember(context) { mutableStateOf(amountText(abs(context.amount))) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val covering = context.amount < 0
    val hasOptions = context.canUseToBudget || candidates.isNotEmpty()
    val category = context.category
    // Clamped to the amount being covered: this month's budget can offset part of the rolled-over overspending.
    val rolledOver = category?.let { min(abs(it.rolledOverOverspending), abs(it.available)) } ?: 0
    val currentShortfall = max(0, abs(context.amount) - rolledOver)
    val sourceAvailable = when {
        !covering -> context.amount
        endpoint is TransferEndpoint.Category -> candidates.firstOrNull { it.categoryId == endpoint.categoryId }?.available
        else -> context.budget.toBudget
    }

    FormSheet(
        L(if (covering) "Cover Overspending" else "Move Money"),
        onDismiss = { if (!saving) onDismiss() },
        confirmLabel = L("Move"),
        confirmEnabled = !saving && hasOptions,
        onConfirm = {
            saving = true
            errorMessage = null
            scope.launch {
                try {
                    val cents = BudgetStore.budgetAmountCents(text)
                    if (sourceAvailable != null && cents > sourceAvailable) throw BudgetStoreError.TransferAmountExceedsSource
                    // Covering pulls money into the tapped category; moving a surplus pushes it out.
                    val from = if (covering) endpoint.categoryId else category?.categoryId
                    val to = if (covering) category?.categoryId else endpoint.categoryId
                    store.transferBudget(context.budget.month, from, to, cents)
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    saving = false
                }
            }
        },
    ) {
        if (covering && category != null) {
            FormSection(footer = L("Covering moves budgeted money only. It does not change or duplicate any transactions.")) {
                FormRow(L("Amount to cover"), trailing = { Text(store.displayBalance(abs(category.available)), color = MaterialTheme.colorScheme.negative, style = MaterialTheme.typography.titleMediumEmphasized) })
                if (currentShortfall > 0) FormRow(L("This month"), value = store.displayBalance(currentShortfall))
                if (rolledOver > 0) FormRow(L("From earlier months"), value = store.displayBalance(rolledOver))
            }
        }
        FormSection(
            L(if (covering) "Cover from" else "Move to"),
            category?.let {
                val month = MonthTitles.title(it.month)
                if (covering) L("%@ is overspent by %@ in %@.", it.categoryName, store.displayBalance(abs(it.available)), month) else L("%@ has %@ available in %@.", it.categoryName, store.displayBalance(it.available), month)
            },
        ) {
            if (hasOptions) {
                val options = buildList {
                    if (context.canUseToBudget && context.budget.toBudget != null) add(TransferEndpoint.ToBudget)
                    candidates.forEach { add(TransferEndpoint.Category(it.categoryId)) }
                }
                FormPickerRow(
                    L(if (covering) "From" else "To"),
                    options,
                    endpoint,
                    { option ->
                        when (option) {
                            TransferEndpoint.ToBudget -> L("To Budget (%@)", store.displayBalance(context.budget.toBudget ?: 0))
                            is TransferEndpoint.Category -> {
                                val candidate = candidates.first { it.categoryId == option.id }
                                val amount = store.displayBalance(candidate.available)
                                if (covering && candidates.firstOrNull() == candidate) L("Recommended: %@ (%@)", candidate.categoryName, amount) else L("%@ (%@)", candidate.categoryName, amount)
                            }
                        }
                    },
                    { endpoint = it },
                    Modifier.testTag("budgetTransfer.endpoint"),
                )
            } else {
                FormRow(
                    L(
                        if (covering) {
                            "Nothing can cover this right now: To Budget is empty and no other category has available funds."
                        } else {
                            "There are no other categories to move this to."
                        },
                    ),
                    enabled = false,
                )
            }
        }
        FormSection(L("Amount")) { SheetAmountField(text, { text = it }) }
        ErrorText(errorMessage)
    }
}

/**
 * The envelope budget summary as a centered card: where this month's money
 * came from and went, with To Budget as the action that holds, moves or
 * covers it.
 */
@Composable
fun BudgetSummarySheet(month: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<EnvelopeBudgetSummary?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showingActions by remember { mutableStateOf(false) }
    var showingHold by remember { mutableStateOf(false) }
    var showingTransfer by remember { mutableStateOf(false) }
    var bufferError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(month, reload) { summary = store.fetchEnvelopeBudgetSummary(month) }
    val resultTitle = L(if ((summary?.toBudget ?: 0) < 0) "Overbudgeted" else "To Budget")
    val previousMonthTitle = BudgetStore.shiftBudgetMonth(month, -1)?.substringAfter('-')?.toIntOrNull()?.takeIf { it in 1..12 }?.let {
        L("Overspent in %@", DateFormatSymbols.getInstance().shortMonths[it - 1])
    } ?: L("Overspent")

    fun runBuffer(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bufferError = e.localizedMessage ?: L("The buffer could not be updated.")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        SystemBarsAppearance()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Budget Summary"), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = L("Close Budget Summary")) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val current = summary
                if (current == null) {
                    DelayedLoadingIndicator(Modifier.fillMaxWidth().heightIn(min = 180.dp), contained = true)
                } else {
                    @Composable
                    fun SummaryLine(title: String, amount: Long, tint: Color = Color.Unspecified) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, modifier = Modifier.weight(1f))
                            Text(store.displayBalance(amount), color = tint, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant
                    SummaryLine(L("Available Funds"), current.availableFunds)
                    SummaryLine(previousMonthTitle, current.lastMonthOverspent, if (current.lastMonthOverspent < 0) MaterialTheme.colorScheme.negative else Color.Unspecified)
                    SummaryLine(L("Budgeted"), -current.budgeted, if (current.budgeted > 0) Color.Unspecified else dim)
                    SummaryLine(L("For next month"), -current.forNextMonth, if (current.forNextMonth > 0) Color.Unspecified else dim)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    val resultColor = if (current.toBudget < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.positive
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = resultColor.copy(alpha = 0.12f),
                        onClick = { showingActions = true },
                        modifier = Modifier.fillMaxWidth().testTag("budgetSummaryResultAction"),
                    ) {
                        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(resultTitle, style = MaterialTheme.typography.titleSmall, color = resultColor)
                            Text(store.displayBalance(current.toBudget), style = MaterialTheme.typography.headlineSmallEmphasized, color = resultColor, textDecoration = TextDecoration.Underline)
                        }
                    }
                }
            }
        }
    }

    val current = summary
    if (showingActions && current != null) {
        val actions = EnvelopeBudgetSummaryAction.available(current)
        ChoiceDialog(
            resultTitle,
            actions,
            null,
            { action ->
                L(
                    when (action) {
                        EnvelopeBudgetSummaryAction.ResetBuffer -> "Reset next month's buffer"
                        EnvelopeBudgetSummaryAction.DisableAutoBuffer -> "Disable current auto hold"
                        EnvelopeBudgetSummaryAction.MoveToCategory -> "Move to a category"
                        EnvelopeBudgetSummaryAction.HoldForNextMonth -> "Hold for next month"
                        EnvelopeBudgetSummaryAction.CoverFromCategory -> "Cover from a category"
                    },
                )
            },
            onDismiss = { showingActions = false },
        ) { action ->
            showingActions = false
            when (action) {
                EnvelopeBudgetSummaryAction.ResetBuffer -> runBuffer { store.resetBudgetBuffer(month) }
                EnvelopeBudgetSummaryAction.DisableAutoBuffer -> runBuffer { store.disableAutomaticBudgetBuffer(month) }
                EnvelopeBudgetSummaryAction.MoveToCategory, EnvelopeBudgetSummaryAction.CoverFromCategory -> showingTransfer = true
                EnvelopeBudgetSummaryAction.HoldForNextMonth -> showingHold = true
            }
        }
    }
    if (showingHold && current != null && current.toBudget > 0) {
        BudgetBufferSheet(month, current.toBudget) {
            showingHold = false
            reload++
        }
    }
    val budget = store.currentBudgetMonth
    if (showingTransfer && budget != null && budget.toBudget != null) {
        BudgetTransferSheet(BudgetTransferContext.toBudgetIn(budget)) {
            showingTransfer = false
            reload++
        }
    }
    MessageAlert(L("Unable to update buffer"), bufferError) { bufferError = null }
}

/** New Group, or renaming [group]. */
@Composable
fun CategoryGroupSheet(group: CategoryGroup?, month: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    FormSheet(
        L(if (group == null) "New Group" else "Rename Group"),
        onDismiss = { if (!saving) onDismiss() },
        confirmLabel = L(if (group == null) "Create" else "Save"),
        confirmEnabled = !saving && name.isNotBlank(),
        onConfirm = {
            // One tap, one group: a second tap can't race the disabled state.
            if (saving) return@FormSheet
            saving = true
            errorMessage = null
            scope.launch {
                try {
                    if (group == null) {
                        store.createCategoryGroup(name)
                    } else if (name.trim() != group.name) {
                        store.renameCategoryGroup(group.id, name, month)
                    }
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    saving = false
                }
            }
        },
    ) {
        FormSection(footer = errorMessage ?: if (group == null) L("The group is added below your existing ones, ready for categories.") else null) {
            FormTextFieldRow(
                name,
                { name = it },
                L("Group Name"),
                Modifier.testTag("categoryGroupEditor.name"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                enabled = !saving,
            )
        }
    }
}

/** New Category, filed under a picked group (hidden groups left out: a category there would be invisible). */
@Composable
fun NewCategorySheet(initialGroupId: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(initialGroupId) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val groups = store.categoryGroups.filter { !it.hidden }.sortedBy { it.sortOrder }
    FormSheet(
        L("New Category"),
        onDismiss = { if (!saving) onDismiss() },
        confirmLabel = L("Create"),
        confirmEnabled = !saving && name.isNotBlank() && groupId.isNotEmpty(),
        onConfirm = {
            if (saving) return@FormSheet
            saving = true
            errorMessage = null
            scope.launch {
                try {
                    store.createCategory(name, groupId)
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    saving = false
                }
            }
        },
    ) {
        FormSection(footer = errorMessage) {
            FormTextFieldRow(
                name,
                { name = it },
                L("Category Name"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                enabled = !saving,
            )
        }
        FormSection(footer = L("The category is added at the top of its group, the same as the web app.")) {
            FormPickerRow(L("Group"), groups.map { it.id }, groupId, { id -> groups.firstOrNull { it.id == id }?.name.orEmpty() }, { groupId = it })
        }
    }
}

/**
 * A category's own editor: name, note, goal status, rollover, quick-assign
 * shortcuts and the Spent exclusion. Amount cells in the table keep their
 * actions; the name is for this.
 */
@Composable
fun CategoryBudgetDetailSheet(category: CategoryBudget, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(category.categoryName) }
    var note by remember { mutableStateOf(EntityNote.unsupported) }
    var history by remember { mutableStateOf<List<CategoryBudget>>(emptyList()) }
    var rollover by remember { mutableStateOf(category.carryoverEnabled) }
    var savingName by remember { mutableStateOf(false) }
    var savingRollover by remember { mutableStateOf(false) }
    var applyingSuggestion by remember { mutableStateOf(false) }
    var applyingTemplate by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var editingAutomations by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isTracking = store.currentBudgetMonth?.isTrackingBudget == true
    val monthTitle = MonthTitles.title(category.month)
    val suggestions = category.quickAssignSuggestions(history)

    LaunchedEffect(category.id) {
        note = store.fetchNote(category.categoryId)
        history = store.budgetHistory(category)
    }

    FormSheet(
        L("Edit Category"),
        onDismiss = { if (!savingName) onDismiss() },
        confirmEnabled = !savingName && name.isNotBlank(),
        onConfirm = {
            val trimmed = name.trim()
            if (trimmed == category.categoryName) return@FormSheet onDismiss()
            savingName = true
            errorMessage = null
            scope.launch {
                try {
                    store.renameCategory(category.categoryId, trimmed, category.month)
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                    savingName = false
                }
            }
        },
    ) {
        FormSection(L("Name")) {
            FormTextFieldRow(
                name,
                { name = it },
                L("Category Name"),
                Modifier.testTag("categoryEditor.name"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
            )
        }

        if (note.supported) {
            FormSection(L("Note")) {
                if (note.isEmpty) {
                    FormRow(L("Add Note"), Modifier.testTag("categoryEditor.note"), leading = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) }, onClick = { editingNote = true })
                } else {
                    Row(Modifier.fillMaxWidth().clickable { editingNote = true }.padding(16.dp).testTag("categoryEditor.note"), verticalAlignment = Alignment.Top) {
                        Text(NoteLinkText.annotated(note.text), modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Edit, contentDescription = L("Edit"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (store.goalTemplatesEnabled) {
            FormSection(
                L("Goal"),
                if (category.goal == null && !store.goalTemplatesUIEnabled) L("Define templates with #template or #goal lines in this category's note, then apply them here.") else null,
            ) {
                val goal = category.goal
                val difference = category.differenceToGoal
                if (goal != null && difference != null) {
                    FormRow(L("Status"), trailing = {
                        when {
                            difference == 0L -> Text(L("Fully Funded"), color = MaterialTheme.colorScheme.positive)
                            difference > 0 -> Text(L("Overfunded (%@)", store.displayBalance(difference)), color = MaterialTheme.colorScheme.positive)
                            else -> Text(L("Underfunded (%@)", store.displayBalance(difference)), color = MaterialTheme.colorScheme.warning)
                        }
                    })
                    FormRow(L("Goal Type"), value = L(if (category.longGoal) "Goal" else "Automation"))
                    FormRow(L("Goal"), value = store.displayBalance(goal))
                    FormRow(L(if (category.longGoal) "Balance" else "Budgeted"), value = store.displayBalance(category.goalTrackedAmount))
                }
                if (store.goalTemplatesUIEnabled) {
                    FormRow(L("Edit Automations"), leading = { Icon(Icons.Filled.Tune, contentDescription = null) }, onClick = { editingAutomations = true })
                }
                FormRow(L("Apply Budget Template"), leading = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) }, enabled = !applyingTemplate, onClick = {
                    applyingTemplate = true
                    errorMessage = null
                    scope.launch {
                        when (val outcome = store.runGoalTemplates(category.month, GoalTemplateAction.Apply, categoryId = category.categoryId)) {
                            is GoalTemplateOutcome.Applied -> return@launch onDismiss()
                            GoalTemplateOutcome.UpToDate -> errorMessage = L("No templates to apply for this category.")
                            is GoalTemplateOutcome.Errors -> errorMessage = outcome.messages.joinToString("\n")
                            is GoalTemplateOutcome.Failed -> errorMessage = outcome.message
                            GoalTemplateOutcome.CheckPassed -> Unit
                        }
                        applyingTemplate = false
                    }
                })
            }
        }

        FormSection(
            footer = L(
                if (isTracking) "Carry this category's balance into next month. Applies from %@ onward." else "Carry overspending into next month instead of taking it from To Budget. Applies from %@ onward.",
                monthTitle,
            ),
        ) {
            // The toggle, not an effect, starts the write: a failed save reverts the state directly, which mustn't re-save.
            FormToggleRow(
                L("Rollover Overspending"),
                rollover,
                { enabled ->
                    if (savingRollover) return@FormToggleRow
                    savingRollover = true
                    rollover = enabled
                    errorMessage = null
                    scope.launch {
                        try {
                            store.setBudgetCarryover(category.month, category.categoryId, enabled)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage
                            rollover = !enabled
                        }
                        savingRollover = false
                    }
                },
                Modifier.testTag("categoryEditor.rollover"),
                enabled = !savingRollover,
            )
        }

        // A suggestion overwrites this month's amount, so name the month and show what's there now.
        FormSection(
            L(if (isTracking) "Quick Budget" else "Quick Assign"),
            L("Suggestions use this category's existing Actual history and replace the amount shown above."),
        ) {
            FormRow(monthTitle, Modifier.testTag("categoryEditor.currentAmount"), value = store.displayBalance(category.budgeted))
            if (suggestions.isEmpty()) FormRow(L("No suggestions available"), enabled = false)
            for (suggestion in suggestions) {
                FormRow(
                    CategoryDetailText.quickAssignTitle(suggestion.kind, isTracking, history.size),
                    Modifier.testTag("categoryEditor.quickAssign.${suggestion.kind.rawValue}"),
                    value = store.displayBalance(suggestion.amount),
                    enabled = !applyingSuggestion,
                    onClick = {
                        applyingSuggestion = true
                        errorMessage = null
                        scope.launch {
                            try {
                                store.setBudgetAmount(category.month, category.categoryId, suggestion.amount)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorMessage = e.localizedMessage
                                applyingSuggestion = false
                            }
                        }
                    },
                )
            }
        }

        FormSection(footer = L("Excluded categories stay in the budget and transaction totals, but are left out of Spent, Saved, and Net on the Budget and Accounts tabs.")) {
            FormToggleRow(
                L("Include in Spent"),
                store.isCategoryIncludedInSpent(category.categoryId),
                { store.setCategoryIncludedInSpent(it, category.categoryId) },
                Modifier.testTag("categoryEditor.includeInSpent"),
            )
        }
        ErrorText(errorMessage)
    }

    if (editingNote) {
        FullScreenSheet({ editingNote = false }) {
            NoteEditorView(category.categoryId, name.trim().ifEmpty { category.categoryName }, note.text) {
                editingNote = false
                scope.launch { note = store.fetchNote(category.categoryId) }
            }
        }
    }
    if (editingAutomations) {
        FullScreenSheet({ editingAutomations = false }) {
            BudgetAutomationsSheet(category.categoryId, category.month) { editingAutomations = false }
        }
    }
}

object CategoryDetailText {
    fun quickAssignTitle(kind: QuickAssignSuggestion.Kind, isTracking: Boolean, historyCount: Int, locale: Locale = Locale.getDefault()): String = when (kind) {
        QuickAssignSuggestion.Kind.SpentLastMonth -> ReportStrings.text("Spent Last Month", locale)
        QuickAssignSuggestion.Kind.AverageSpent -> ReportStrings.localized("Average Spent (%lld Months)", historyCount.toLong(), locale = locale)
        QuickAssignSuggestion.Kind.AssignedLastMonth -> ReportStrings.text(if (isTracking) "Budgeted Last Month" else "Assigned Last Month", locale)
        QuickAssignSuggestion.Kind.ResetAvailable -> ReportStrings.text(if (isTracking) "Reset Balance to Zero" else "Reset Available to Zero", locale)
        QuickAssignSuggestion.Kind.SetToZero -> ReportStrings.text(if (isTracking) "Set Budget to Zero" else "Set Assigned to Zero", locale)
    }
}

/** The template-run outcome alert for the Budget tab's actions. */
@Composable
fun TemplateResultAlert(result: BudgetViewLogic.TemplateAlert?, onDismiss: () -> Unit) {
    result ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.title) },
        text = { Text(result.message) },
        confirmButton = { TextButton(onDismiss) { Text(L("OK")) } },
    )
}

/** iOS's rename alert: a name field with Cancel and Save. */
@Composable
fun RenameCategoryDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Rename Category")) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                placeholder = { Text(L("Category Name")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.testTag("categoryRename.name"),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                BudgetViewLogic.renameName(text)?.let(onSave)
            }, enabled = BudgetViewLogic.renameName(text) != null, modifier = Modifier.testTag("categoryRename.save")) { Text(L("Save")) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("categoryRename.cancel")) { Text(L("Cancel")) } },
    )
}
