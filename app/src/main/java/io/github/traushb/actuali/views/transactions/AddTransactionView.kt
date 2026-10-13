package io.github.traushb.actuali.views.transactions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.NearbyPayee
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.PendingImportReviewRequirement
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionType
import io.github.traushb.actuali.services.AutomaticCategoryPreview
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.PendingImportApprover
import io.github.traushb.actuali.services.SplitLineForm
import io.github.traushb.actuali.services.TransactionForm
import io.github.traushb.actuali.services.automaticCategoryPreview
import io.github.traushb.actuali.services.bool
import io.github.traushb.actuali.services.database.mostRecentCategoryId
import io.github.traushb.actuali.services.fetchSplitChildren
import io.github.traushb.actuali.services.location.LocationAuthStatus
import io.github.traushb.actuali.services.location.LocationProvider
import io.github.traushb.actuali.services.notifications.NotificationRouter
import io.github.traushb.actuali.services.offBudgetAccountIds
import io.github.traushb.actuali.services.saveTransaction
import io.github.traushb.actuali.services.transferLegTakesCategory
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AmountInputField
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.CyclingFormIcon
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.NoteLinkRows
import io.github.traushb.actuali.views.components.TagSuggestionBar
import io.github.traushb.actuali.views.info
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** The form's pure rules, apart from the screen so they can be tested without one. */
object AddTransactionLogic {
    fun type(isTransfer: Boolean, isInflow: Boolean): TransactionType = when {
        isTransfer -> TransactionType.Transfer
        isInflow -> TransactionType.Income
        else -> TransactionType.Expense
    }

    /**
     * The accounts a transfer is written between, source then destination.
     * Money out of this account goes to the partner; money in comes from it.
     * Converting an existing row keeps that row on its own side, whatever the sign.
     */
    fun transferEnds(accountId: String, partnerId: String, isInflow: Boolean, keepsOwnSide: Boolean): Pair<String, String> =
        if (keepsOwnSide || !isInflow) accountId to partnerId else partnerId to accountId

    /**
     * A plain transaction in either flow can split, as can an existing parent
     * mid-"Remove Split" (as an undo). Transfers can't: splitting would orphan
     * the partner leg.
     */
    fun canSplitIntoCategories(isTransfer: Boolean, isEditingSplitParent: Boolean, unsplitRequested: Boolean): Boolean =
        !isTransfer && (!isEditingSplitParent || unsplitRequested)

    /** Whether the payee list may offer accounts as transfers: not mid-split, nor an edit that couldn't become one. */
    fun offersTransfer(isSplitting: Boolean, isEditingSplitParent: Boolean, isEditing: Boolean, isEditingTransfer: Boolean = false, canConvertToTransfer: Boolean): Boolean =
        !isSplitting && !isEditingSplitParent && (!isEditing || isEditingTransfer || canConvertToTransfer)

    /**
     * The sign is the direction, as in Actual. Existing transfers stay fixed by
     * their two legs, a conversion keeps its own side, and a split parent flips
     * only once its lines have loaded.
     */
    fun canToggleDirection(
        isEditingSplitParent: Boolean,
        splitLoaded: Boolean,
        isEditingTransfer: Boolean,
        isConvertingToTransfer: Boolean,
        isTransfer: Boolean,
        hasTransferPartner: Boolean,
    ): Boolean {
        if (isEditingTransfer || isConvertingToTransfer) return false
        if (isEditingSplitParent && !splitLoaded) return false
        return !isTransfer || hasTransferPartner
    }

    fun transferDirectionDescription(source: String?, destination: String?, locale: Locale = Locale.getDefault()): String =
        if (source == null || destination == null) {
            io.github.traushb.actuali.services.ReportStrings.text("Transfer", locale)
        } else {
            io.github.traushb.actuali.services.ReportStrings.format("Transfer from %@ to %@", source, destination, locale = locale)
        }
}

/** Math for the split entry rows, kept off the screen for testability. */
object SplitEntryMath {
    /**
     * Cents still unassigned across the split lines. Blank lines count as
     * zero so the remainder stays visible mid-entry; null while the total or a
     * non-blank line doesn't parse.
     */
    fun remainingCents(total: String, lineAmounts: List<String>): Long? {
        val totalCents = total.toDoubleOrNull()?.let(Transaction::cents) ?: return null
        var assigned = 0L
        for (amount in lineAmounts) {
            if (amount.isEmpty()) continue
            assigned += amount.toDoubleOrNull()?.let(Transaction::cents) ?: return null
        }
        return totalCents - assigned
    }

    /** Plain dot-decimal string for non-negative cents, the format the amount field keeps. */
    fun amountString(cents: Long): String = "${cents / 100}.${"%02d".format(cents % 100)}"
}

private const val LOCATION_ASKED_KEY = "locationPermissionRequested"

/** Everything the form edits, as Compose state. */
private class TransactionEditorState(
    accountId: String,
    amount: String,
    isInflow: Boolean,
    payeeName: String,
    transferToAccountId: String?,
    categoryId: String?,
    notes: String,
    date: LocalDate,
    cleared: Boolean,
    userPickedCategory: Boolean,
) {
    var selectedAccountId by mutableStateOf(accountId)
    var amount by mutableStateOf(amount)

    /** The amount's sign, as in Actual: false is money out (an expense, or a transfer to the other account), true is money in. */
    var isInflow by mutableStateOf(isInflow)
    var payeeName by mutableStateOf(payeeName)

    /** The other account of a transfer: choosing an account as the payee makes this a transfer, as in Actual. */
    var transferToAccountId by mutableStateOf(transferToAccountId)
    var selectedCategoryId by mutableStateOf(categoryId)
    var notes by mutableStateOf(notes)
    var date by mutableStateOf(date)
    var cleared by mutableStateOf(cleared)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var userPickedCategory by mutableStateOf(userPickedCategory)
    var automaticCategoryPreview by mutableStateOf<AutomaticCategoryPreview?>(null)
    var nearbyPayees by mutableStateOf<List<NearbyPayee>>(emptyList())
    var saveLocation by mutableStateOf(true)
    val splitLines: SnapshotStateList<SplitLineForm> = mutableStateListOf()

    /**
     * True while the edit form's "Remove Split" is toggled on an existing
     * parent: the lines stay in memory (so re-splitting undoes it instantly)
     * but the form saves as a single transaction.
     */
    var unsplitRequested by mutableStateOf(false)
    var confirmedReviewRequirements by mutableStateOf<Set<PendingImportReviewRequirement>>(emptySet())

    fun reset() {
        amount = ""
        isInflow = false
        payeeName = ""
        transferToAccountId = null
        selectedCategoryId = null
        notes = ""
        date = LocalDate.now()
        cleared = false
        errorMessage = null
        splitLines.clear()
        unsplitRequested = false
        // A fresh form suggests categories from payee history again — a
        // discarded manual pick must not keep suppressing the lookup.
        userPickedCategory = false
        automaticCategoryPreview = null
    }
}

/** What the automatic-category lookup depends on; a change re-runs it after a short pause. */
private data class AutomaticCategoryInput(
    val accountId: String,
    val type: TransactionType,
    val amount: String,
    val payeeId: String?,
    val payeeName: String,
    val notes: String,
    val date: LocalDate,
    val cleared: Boolean,
    val isSplit: Boolean,
    val isEditing: Boolean,
    val categoryIsExplicit: Boolean,
    val applyRules: Boolean,
)

/**
 * The add flow. Prefill parameters carry what an automation passed along
 * (see TransactionPrefill). [onDismiss] is null for the tab-hosted flow,
 * which can't close itself: it resets after a save or cancel instead.
 */
@Composable
fun AddTransactionView(
    accountId: String,
    onDismiss: (() -> Unit)? = null,
    payee: String = "",
    amountCents: Long? = null,
    date: LocalDate = LocalDate.now(),
    notes: String = "",
    categoryId: String? = null,
    isIncome: Boolean = false,
    cleared: Boolean = false,
    autofocusAmount: Boolean? = null,
    saveOverride: (suspend (TransactionForm) -> PendingImportApprover.SaveResult)? = null,
    onSaved: ((String?) -> Unit)? = null,
    reviewRequirements: List<PendingImportReviewRequirement> = emptyList(),
) {
    val state = remember(accountId) {
        TransactionEditorState(
            accountId,
            amountCents?.let { "%.2f".format(Locale.ROOT, abs(it) / 100.0) }.orEmpty(),
            isIncome,
            payee,
            null,
            categoryId,
            notes,
            date,
            cleared,
            // A prefilled category is the automation's explicit choice — the payee-history suggestion mustn't overwrite it.
            userPickedCategory = categoryId != null,
        )
    }
    TransactionEditor(state, null, onDismiss, autofocusAmount, saveOverride, onSaved, reviewRequirements)
}

/**
 * The edit flow. Transfer legs load as transfers: the opened row's account
 * is the form's account, its sign the direction, and the partner account is
 * read off the transfer payee (GH #104).
 */
@Composable
fun EditTransactionView(editing: Transaction, onDismiss: () -> Unit) {
    val state = remember(editing.id) {
        TransactionEditorState(
            editing.accountId,
            "%.2f".format(Locale.ROOT, abs(editing.amount) / 100.0),
            editing.amount >= 0,
            editing.payeeName.orEmpty(),
            if (editing.transferId != null) editing.transferAcct else null,
            editing.categoryId,
            editing.notes.orEmpty(),
            io.github.traushb.actuali.services.schedules.DayDate.fromYYYYMMDD(editing.date)?.localDate ?: LocalDate.now(),
            editing.cleared,
            userPickedCategory = false,
        )
    }
    TransactionEditor(state, editing, onDismiss, null, null, null, emptyList())
}

@Composable
private fun TransactionEditor(
    state: TransactionEditorState,
    editing: Transaction?,
    onDismiss: (() -> Unit)?,
    autofocusAmount: Boolean?,
    saveOverride: (suspend (TransactionForm) -> PendingImportApprover.SaveResult)?,
    onSaved: ((String?) -> Unit)?,
    reviewRequirements: List<PendingImportReviewRequirement>,
) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var showPayeePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val isEditing = editing != null
    val canDismiss = onDismiss != null
    val isEditingSplitParent = editing?.isParent == true
    val isEditingTransfer = editing?.transferId != null
    val isTransfer = state.transferToAccountId != null || isEditingTransfer
    val txType = AddTransactionLogic.type(isTransfer, state.isInflow)
    // Split parents and children can't become transfers: a parent's amount is its children's, a child has no row of its own to pair.
    val canConvertToTransfer = editing != null && editing.transferId == null && !editing.isParent && editing.parentId == null
    val isConvertingToTransfer = isTransfer && canConvertToTransfer
    val isSplitting = state.splitLines.isNotEmpty() && !state.unsplitRequested
    val offBudgetIds = store.offBudgetAccountIds
    val showsStandardCategoryFields = store.accounts.firstOrNull { it.id == state.selectedAccountId }?.offBudget != true
    // Whether the transfer being entered takes a category, tracking the live pickers.
    val transferTakesCategory = isTransfer && if (editing == null) {
        // createTransfer puts it on whichever leg may carry one.
        transferLegTakesCategory(state.selectedAccountId, state.transferToAccountId, offBudgetIds) ||
            transferLegTakesCategory(state.transferToAccountId, state.selectedAccountId, offBudgetIds)
    } else {
        // An edit writes the form's category to the opened row alone.
        transferLegTakesCategory(state.selectedAccountId, state.transferToAccountId, offBudgetIds)
    }
    // A hidden pick is kept (so it reappears with the row) but never saved (GH #561).
    val showsCategoryRow = if (isTransfer) transferTakesCategory else showsStandardCategoryFields
    val showsSplitEntry = isSplitting && !isTransfer && showsStandardCategoryFields
    val canSplit = AddTransactionLogic.canSplitIntoCategories(isTransfer, isEditingSplitParent, state.unsplitRequested)
    // Opposite-direction lines hand their amount back to the remainder instead of consuming it (GH #216).
    val splitRemaining = SplitEntryMath.remainingCents(state.amount, state.splitLines.map { if (it.isOpposite && it.amount.isNotEmpty()) "-${it.amount}" else it.amount })
    val hasBlankSplitLine = state.splitLines.any { it.amount.isEmpty() }
    // On-budget first, then off-budget, each by sort order — the webapp's AccountAutocomplete.
    val orderedOpenAccounts = store.accounts.filter { !it.closed }.sortedWith(compareBy({ it.offBudget }, { it.sortOrder }))
    val offersTransfer = AddTransactionLogic.offersTransfer(isSplitting, isEditingSplitParent, isEditing, isEditingTransfer, canConvertToTransfer)
    val canToggleDirection = AddTransactionLogic.canToggleDirection(
        isEditingSplitParent,
        // Before the lines load, a save would keep the parent's old amount.
        splitLoaded = state.unsplitRequested || state.splitLines.isNotEmpty(),
        isEditingTransfer = isEditingTransfer,
        isConvertingToTransfer = isConvertingToTransfer,
        isTransfer = isTransfer,
        hasTransferPartner = state.transferToAccountId != null,
    )

    fun matchingPayee(name: String): Payee? {
        val trimmed = name.trim().ifEmpty { return null }
        return store.payees.firstOrNull { !it.tombstone && it.transferAccountId == null && it.name.equals(trimmed, ignoreCase = true) }
    }

    fun currentInput() = AutomaticCategoryInput(
        state.selectedAccountId, txType, state.amount, matchingPayee(state.payeeName)?.id, state.payeeName, state.notes, state.date, state.cleared,
        isSplitting, isEditing, state.userPickedCategory, applyRules = !isEditing && saveOverride == null,
    )

    /** The accounts the save path gets: a transfer's in source/destination order, or the account alone. */
    fun formAccounts(): Pair<String, String?> {
        val partner = state.transferToAccountId
        if (!isTransfer || partner == null) return state.selectedAccountId to partner
        return AddTransactionLogic.transferEnds(state.selectedAccountId, partner, state.isInflow, isConvertingToTransfer)
    }

    fun currentForm(): TransactionForm {
        val (accountId, transferTo) = formAccounts()
        return TransactionForm(
            accountId = accountId,
            type = txType,
            amount = state.amount,
            payeeName = state.payeeName,
            transferToAccountId = transferTo,
            categoryId = state.selectedCategoryId,
            notes = state.notes,
            date = state.date,
            cleared = state.cleared,
            splits = if (isTransfer || state.unsplitRequested) emptyList() else state.splitLines.toList(),
            collapseSplit = state.unsplitRequested,
            recordLocation = state.saveLocation,
            reviewConfirmations = state.confirmedReviewRequirements,
            categoryIsExplicit = state.userPickedCategory,
            automaticCategoryPreview = state.automaticCategoryPreview,
        )
    }

    suspend fun applyAutomaticCategory(input: AutomaticCategoryInput) {
        if (input.categoryIsExplicit || input.isEditing || input.type == TransactionType.Transfer || input.isSplit || input != currentInput()) return
        val preview = try {
            store.automaticCategoryPreview(currentForm(), input.applyRules)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (state.userPickedCategory || input != currentInput()) return
        state.automaticCategoryPreview = preview
        state.selectedCategoryId = preview.resultCategoryId
    }

    fun applyEditCategoryFromHistory(payeeId: String) {
        if (!isEditing || state.userPickedCategory) return
        val database = store.databaseForLogger ?: return
        scope.launch {
            val categoryId = runCatching { database.mostRecentCategoryId(payeeId) }.getOrNull() ?: return@launch
            if (state.userPickedCategory || matchingPayee(state.payeeName)?.id != payeeId) return@launch
            state.selectedCategoryId = categoryId
        }
    }

    // Nearby payees, loaded when the payee picker opens. Permission is asked once; every failure degrades to "no suggestions".
    suspend fun fetchNearby() {
        val position = runCatching { LocationProvider.shared.currentPosition() }.getOrNull()
        state.nearbyPayees = position?.let { store.fetchNearbyPayees(it.latitude, it.longitude) }.orEmpty()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) scope.launch { fetchNearby() }
    }
    fun loadNearbyPayees() {
        if (isEditing) return
        val provider = runCatching { LocationProvider.shared }.getOrNull() ?: return
        if (provider.authorizationStatus() == LocationAuthStatus.Granted) {
            scope.launch { fetchNearby() }
        } else if (!Defaults.standard.bool(LOCATION_ASKED_KEY)) {
            Defaults.standard.set(LOCATION_ASKED_KEY, true)
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    /**
     * Load a split parent's children as editable lines. Inherited payees load
     * as empty so a parent payee edit follows through to them, mirroring
     * Actual's cascade rule.
     */
    suspend fun loadSplitChildren() {
        if (editing == null || !editing.isParent || state.splitLines.isNotEmpty()) return
        state.splitLines.addAll(
            store.fetchSplitChildren(editing.id).map { child ->
                val overridesParentPayee = child.payeeId != editing.payeeId
                SplitLineForm(
                    childId = child.id,
                    categoryId = child.categoryId,
                    amount = SplitEntryMath.amountString(abs(child.amount)),
                    // A child running against the parent's direction — a refund inside a spend split — keeps its flip (GH #216).
                    isOpposite = (child.amount < 0) != (editing.amount < 0),
                    notes = child.notes.orEmpty(),
                    payeeName = if (overridesParentPayee) {
                        store.payees.firstOrNull { it.id == child.payeeId }?.let { PayeePickerLogic.displayName(it, store.accounts) } ?: child.payeeName.orEmpty()
                    } else {
                        ""
                    },
                    payeeId = if (overridesParentPayee) child.payeeId else null,
                )
            },
        )
    }
    LaunchedEffect(editing?.id) { loadSplitChildren() }
    val automaticInput = currentInput()
    LaunchedEffect(automaticInput) {
        // Every rule input is kept; cancellation skips the lookup while typing. Save runs it directly, without the pause.
        delay(300)
        applyAutomaticCategory(automaticInput)
    }

    /**
     * Begin a split. The add flow starts from two empty lines; editing a plain
     * transaction seeds the first line with its category and full amount, as
     * Actual's desktop moves the row's category onto the first sub-row. An
     * existing parent undoing "Remove Split" re-enters split mode.
     */
    fun startSplit() {
        state.unsplitRequested = false
        if (isEditingSplitParent) {
            if (state.splitLines.isEmpty()) scope.launch { loadSplitChildren() }
            return
        }
        state.splitLines.clear()
        if (isEditing) {
            state.splitLines.addAll(listOf(SplitLineForm(categoryId = state.selectedCategoryId, amount = state.amount), SplitLineForm()))
        } else {
            state.splitLines.addAll(listOf(SplitLineForm(), SplitLineForm()))
        }
    }

    fun toggleDirection() {
        if (canToggleDirection) state.isInflow = !state.isInflow
    }

    fun cancelEntry() {
        if (onDismiss != null) {
            onDismiss()
        } else {
            // The tab-hosted flow has nothing to dismiss to: discard the entry and return to the Start Page (GH #281).
            state.reset()
            focusManager.clearFocus()
            NotificationRouter.pendingTabNavigation = store.startTab.tabTag
        }
    }

    val saveDisabled = state.isLoading || state.amount.isEmpty() ||
        (reviewRequirements.isNotEmpty() && !state.confirmedReviewRequirements.containsAll(reviewRequirements)) ||
        (isTransfer && state.transferToAccountId == null) ||
        // A blank line reads as zero for the remainder, but the store rejects zero-amount children.
        (showsSplitEntry && (splitRemaining != 0L || hasBlankSplitLine))

    fun save() = scope.launch {
        state.isLoading = true
        state.errorMessage = null
        try {
            applyAutomaticCategory(currentInput())
            val form = currentForm()
            val savedId = if (saveOverride != null) {
                when (val result = saveOverride(form)) {
                    is PendingImportApprover.SaveResult.Inserted -> result.id
                    PendingImportApprover.SaveResult.Duplicate -> null
                    PendingImportApprover.SaveResult.SuppressedByRule -> throw PendingImportApprover.ApproveError.SuppressedByRule
                }
            } else {
                store.saveTransaction(form, editing)
            }
            onSaved?.invoke(savedId)
            if (onDismiss != null) {
                onDismiss()
            } else {
                // Reset for the next entry and land on the saved transaction's account list, like every add flow.
                val accountId = state.selectedAccountId
                state.reset()
                focusManager.clearFocus()
                NotificationRouter.pendingAccountNavigation = accountId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.errorMessage = PendingImportApprover.localizedErrorMessage(e, Locale.getDefault())
        } finally {
            state.isLoading = false
        }
    }

    val title = when {
        isEditing -> L("Edit Transaction")
        else -> L("Add Transaction")
    }
    Scaffold(topBar = { AppTopBar(title, onBack = onDismiss) }) { padding ->
        FormColumn(padding) {
            AmountHeader(state, txType, canToggleDirection, ::toggleDirection, autofocus = autofocusAmount ?: (!isEditing && state.amount.isEmpty()))

            FormSection {
                FormPickerRow(
                    L("Account"),
                    orderedOpenAccounts.map { it.id },
                    state.selectedAccountId,
                    { id -> store.accounts.firstOrNull { it.id == id }?.name.orEmpty() },
                    { newValue ->
                        // Keep the pair when the partner becomes the form's account.
                        if (state.transferToAccountId == newValue) state.transferToAccountId = state.selectedAccountId
                        state.selectedAccountId = newValue
                    },
                    Modifier.testTag("addTransaction.account"),
                    leading = { CyclingFormIcon(Icons.Filled.AccountBalance, 0) },
                )
                val partnerName = store.accounts.firstOrNull { it.id == state.transferToAccountId }?.name
                FormRow(
                    L("Payee"),
                    leading = { CyclingFormIcon(if (isTransfer) Icons.Filled.SwapHoriz else Icons.Filled.Person, 1) },
                    value = when {
                        isTransfer -> partnerName?.let { "${if (state.isInflow) L("Transfer from") else L("Transfer to")}: $it" } ?: L("Select account")
                        else -> state.payeeName.ifEmpty { null }
                    },
                    modifier = Modifier.testTag("addTransaction.payee"),
                    onClick = {
                        loadNearbyPayees()
                        focusManager.clearFocus()
                        showPayeePicker = true
                    },
                )
            }

            if (isEditingSplitParent || showsCategoryRow || showsSplitEntry) {
                FormSection {
                    if (isEditingSplitParent && !isSplitting && !state.unsplitRequested) {
                        // Placeholder while the children load into the split lines below.
                        FormRow(L("Category"), leading = { CyclingFormIcon(Icons.Outlined.Sell, 2) }, value = L("Split"))
                    } else if (showsCategoryRow && !isSplitting) {
                        FormRow(
                            L("Category"),
                            leading = { CyclingFormIcon(Icons.Outlined.Sell, 2) },
                            value = state.selectedCategoryId?.let { id -> store.categoryGroups.flatMap { it.categories }.firstOrNull { it.id == id }?.name } ?: L("None"),
                            navigates = true,
                            modifier = Modifier.testTag("addTransaction.category"),
                            onClick = {
                                focusManager.clearFocus()
                                showCategoryPicker = true
                            },
                        )
                        if (canSplit) {
                            FormRow(L("Split into multiple categories"), leading = { CyclingFormIcon(Icons.AutoMirrored.Filled.CallSplit, 3) }, onClick = ::startSplit)
                        }
                    }
                    if (showsSplitEntry) {
                        state.splitLines.forEachIndexed { index, line ->
                            SplitLineRow(
                                line,
                                { updated -> state.splitLines[index] = updated },
                                onRemove = {
                                    val removed = state.splitLines.removeAt(index)
                                    // Removing every line of an existing parent is "Remove Split":
                                    // seed the collapse category from a removed line (the parent carries none).
                                    if (isEditingSplitParent && state.splitLines.isEmpty()) {
                                        state.unsplitRequested = true
                                        removed.categoryId?.let { state.selectedCategoryId = it }
                                    }
                                },
                                accountId = state.selectedAccountId,
                                txType = txType,
                                remainingCents = splitRemaining,
                                nearbyPayees = state.nearbyPayees,
                                onOpenPayeePicker = ::loadNearbyPayees,
                            )
                        }
                        FormRow(L("Add Line"), leading = { CyclingFormIcon(Icons.Filled.Add, 3) }, onClick = { state.splitLines.add(SplitLineForm()) })
                        // On an existing parent this switches to single-transaction mode, keeping the lines for an instant undo.
                        FormRow(L("Remove Split"), onClick = {
                            if (isEditingSplitParent) {
                                state.unsplitRequested = true
                                state.splitLines.firstOrNull { it.categoryId != null }?.let { state.selectedCategoryId = it.categoryId }
                            } else {
                                state.splitLines.clear()
                            }
                        })
                        // Right under the lines it counts, so a disabled Save is never a mystery.
                        val remainingText = when {
                            splitRemaining != null && splitRemaining != 0L -> L("%@ left to assign", store.formatCurrency(splitRemaining))
                            splitRemaining == 0L && hasBlankSplitLine -> L("Fill in or remove the empty line")
                            else -> null
                        }
                        remainingText?.let { Text(it, color = MaterialTheme.colorScheme.negative, modifier = Modifier.padding(16.dp)) }
                    }
                }
            }

            FormSection {
                FormDateRow(L("Date"), state.date, { state.date = it }, leading = { CyclingFormIcon(Icons.Filled.CalendarToday, 4) })
                FormToggleRow(L("Cleared"), state.cleared, { state.cleared = it }, leading = { CyclingFormIcon(Icons.Filled.CheckCircleOutline, 5) })
                // Only the paths that record locations (adds and split edits) get the per-save opt-out.
                if ((!isEditing || isEditingSplitParent) && !isTransfer && store.payeeLocationWritesEnabled && store.recordPayeeLocations) {
                    FormToggleRow(L("Save Location"), state.saveLocation, { state.saveLocation = it }, leading = { CyclingFormIcon(Icons.Filled.LocationOn, 6) })
                }
            }

            FormSection {
                TextField(
                    state.notes,
                    { state.notes = it },
                    placeholder = { Text(L("Notes")) },
                    leadingIcon = { CyclingFormIcon(Icons.AutoMirrored.Filled.Notes, 7) },
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("addTransaction.notes"),
                )
                TagSuggestionBar(state.notes, store.tags) { state.notes = it }
                // Links in the note stay openable while it's being edited (GH #190).
                NoteLinkRows(state.notes)
            }

            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.negative, modifier = Modifier.padding(horizontal = 16.dp)) }

            if (reviewRequirements.isNotEmpty()) {
                FormSection {
                    reviewRequirements.forEach { requirement ->
                        FormToggleRow(
                            requirement.prompt(Locale.getDefault()),
                            requirement in state.confirmedReviewRequirements,
                            { confirmed ->
                                state.confirmedReviewRequirements = if (confirmed) state.confirmedReviewRequirements + requirement else state.confirmedReviewRequirements - requirement
                            },
                        )
                    }
                }
            }

            val saveHeight = ButtonDefaults.MediumContainerHeight
            Button(
                onClick = { save() },
                shapes = ButtonDefaults.shapesFor(saveHeight),
                enabled = !saveDisabled,
                contentPadding = ButtonDefaults.contentPaddingFor(saveHeight),
                modifier = Modifier.fillMaxWidth().heightIn(min = saveHeight).testTag("addTransaction.save"),
            ) {
                Text(
                    when {
                        isEditing -> L("Save Changes")
                        isTransfer -> L("Add Transfer")
                        else -> L("Add Transaction")
                    },
                    style = ButtonDefaults.textStyleFor(saveHeight),
                )
            }
            // Cancel sits under Save: the two decisions belong together at the end of the form.
            TextButton(onClick = ::cancelEntry, modifier = Modifier.fillMaxWidth()) {
                Text(L("Cancel"), color = MaterialTheme.colorScheme.negative, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showPayeePicker) {
        FullScreenSheet(onDismiss = { showPayeePicker = false }) {
            PayeePickerView(
                payeeName = if (isTransfer) "" else state.payeeName,
                nearbyPayees = state.nearbyPayees,
                transferAccounts = if (offersTransfer) orderedOpenAccounts.filter { it.id != state.selectedAccountId } else emptyList(),
                accountsOnly = isEditingTransfer,
                onSelectAccount = { account: Account ->
                    state.transferToAccountId = account.id
                    // Converting a row keeps it on its own side, so the sign goes back to the row's.
                    if (editing != null && canConvertToTransfer) state.isInflow = editing.amount >= 0
                    showPayeePicker = false
                },
                onSelect = { payee ->
                    state.transferToAccountId = null
                    state.payeeName = payee.name
                    applyEditCategoryFromHistory(payee.id)
                    showPayeePicker = false
                },
                onCommit = { name ->
                    if (!isTransfer || name.isNotEmpty()) {
                        state.transferToAccountId = null
                        state.payeeName = name
                        matchingPayee(name)?.let { applyEditCategoryFromHistory(it.id) }
                    }
                    showPayeePicker = false
                },
                onDeleteNearby = { nearby ->
                    scope.launch {
                        if (store.deletePayeeLocation(nearby.location)) {
                            state.nearbyPayees = state.nearbyPayees.filter { it.id != nearby.id }
                            // The payee legitimately reappears if it has another recorded location in range.
                            loadNearbyPayees()
                        }
                    }
                },
                onClose = { showPayeePicker = false },
            )
        }
    }
    if (showCategoryPicker) {
        FullScreenSheet(onDismiss = { showCategoryPicker = false }) {
            CategoryPickerView(state.selectedCategoryId, onPick = {
                state.selectedCategoryId = it
                state.userPickedCategory = true
                showCategoryPicker = false
            }, onClose = { showCategoryPicker = false }, autofocusSearch = true)
        }
    }
}

/**
 * The entry header: Outflow or Inflow as a connected button pair — the keyboard's
 * ± key flips the same choice — over the big amount, colored by direction. A
 * transfer's pair reads to/from, with the accounts spelled out under the amount.
 */
@Composable
private fun AmountHeader(state: TransactionEditorState, txType: TransactionType, canToggle: Boolean, onToggle: () -> Unit, autofocus: Boolean) {
    val store = LocalBudgetStore.current
    val isTransfer = txType == TransactionType.Transfer
    val color = when (txType) {
        TransactionType.Expense -> MaterialTheme.colorScheme.negative
        TransactionType.Income -> MaterialTheme.colorScheme.positive
        TransactionType.Transfer -> MaterialTheme.colorScheme.info
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FormSegmentedRow(
                listOf(false, true),
                selected = state.isInflow,
                optionLabel = { inflow ->
                    when {
                        isTransfer -> L(if (inflow) "Transfer from" else "Transfer to")
                        else -> L(if (inflow) "Inflow" else "Outflow")
                    }
                },
                onSelect = { inflow -> if (inflow != state.isInflow) onToggle() },
                enabled = canToggle,
                modifier = Modifier.testTag("addTransaction.sign"),
            )
            AmountInputField(
                state.amount,
                { state.amount = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                conventionalAmountEntry = store.conventionalAmountEntry,
                textStyle = MaterialTheme.typography.displayMediumEmphasized.copy(color = color),
                textAlign = TextAlign.Center,
                autofocus = autofocus,
                onToggleSign = onToggle,
                signToggleEnabled = canToggle,
            )
            if (isTransfer) {
                val own = store.accounts.firstOrNull { it.id == state.selectedAccountId }?.name
                val partner = store.accounts.firstOrNull { it.id == state.transferToAccountId }?.name
                Text(
                    AddTransactionLogic.transferDirectionDescription(if (state.isInflow) partner else own, if (state.isInflow) own else partner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** One split line: category, signed amount, an optional payee of its own, and notes. */
@Composable
private fun SplitLineRow(
    line: SplitLineForm,
    onChange: (SplitLineForm) -> Unit,
    onRemove: () -> Unit,
    accountId: String,
    txType: TransactionType,
    remainingCents: Long?,
    nearbyPayees: List<NearbyPayee>,
    onOpenPayeePicker: () -> Unit,
) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showPayeePicker by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf(nearbyPayees) }
    val focusManager = LocalFocusManager.current
    // Whether the line runs as an outflow once the transaction's direction and the line's flip combine.
    val isOutflow = (txType == TransactionType.Expense) != line.isOpposite
    val categoryName = line.categoryId?.let { id -> store.categoryGroups.flatMap { it.categories }.firstOrNull { it.id == id }?.name }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                categoryName ?: L("Category"),
                color = if (line.categoryId == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clickable {
                    focusManager.clearFocus()
                    showCategoryPicker = true
                }.padding(vertical = 8.dp).testTag("addTransaction.splitLine.category"),
            )
            // Every line carries a sign like the total's; tapping it flips the line — how a refund goes inside a spend split (GH #216).
            TextButton(
                onClick = { onChange(line.copy(isOpposite = !line.isOpposite)) },
                modifier = Modifier.semantics {
                    contentDescription = if (isOutflow) L("Outflow") else L("Inflow")
                },
            ) {
                Text(if (isOutflow) "-" else "+", color = if (isOutflow) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.positive, fontWeight = FontWeight.Bold)
            }
            AmountInputField(
                line.amount,
                { onChange(line.copy(amount = it)) },
                Modifier.width(110.dp),
                conventionalAmountEntry = store.conventionalAmountEntry,
                onToggleSign = { onChange(line.copy(isOpposite = !line.isOpposite)) },
            )
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = L("Delete")) }
        }
        // No fill offer on a flipped line: the remainder is stated in the transaction's direction.
        if (line.amount.isEmpty() && !line.isOpposite && remainingCents != null && remainingCents > 0) {
            TextButton(onClick = { onChange(line.copy(amount = SplitEntryMath.amountString(remainingCents))) }, modifier = Modifier.align(Alignment.End)) {
                Text(L("Use remaining %@", store.formatCurrency(remainingCents)))
            }
        }
        // An empty payee inherits the transaction's.
        Text(
            line.payeeName.ifEmpty { L("Payee (optional)") },
            color = if (line.payeeName.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clickable {
                onOpenPayeePicker()
                focusManager.clearFocus()
                nearby = nearbyPayees
                showPayeePicker = true
            }.padding(vertical = 6.dp).testTag("addTransaction.splitLine.payee"),
        )
        TextField(
            line.notes,
            { onChange(line.copy(notes = it)) },
            placeholder = { Text(L("Notes (optional)")) },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().testTag("addTransaction.splitLine.notes"),
        )
        TagSuggestionBar(line.notes, store.tags) { onChange(line.copy(notes = it)) }
        NoteLinkRows(line.notes)
    }

    if (showCategoryPicker) {
        FullScreenSheet(onDismiss = { showCategoryPicker = false }) {
            CategoryPickerView(line.categoryId, onPick = {
                onChange(line.copy(categoryId = it))
                showCategoryPicker = false
            }, onClose = { showCategoryPicker = false }, autofocusSearch = true)
        }
    }
    if (showPayeePicker) {
        FullScreenSheet(onDismiss = { showPayeePicker = false }) {
            PayeePickerView(
                payeeName = line.payeeName,
                nearbyPayees = nearby,
                transferFromAccountId = accountId,
                onSelect = { payee ->
                    onChange(line.copy(payeeId = payee.id, payeeName = PayeePickerLogic.displayName(payee, store.accounts)))
                    showPayeePicker = false
                },
                onCommit = { name ->
                    onChange(line.copy(payeeId = PayeePickerLogic.committedPayeeId(line.payeeName, line.payeeId, name), payeeName = name))
                    showPayeePicker = false
                },
                onDeleteNearby = { item ->
                    scope.launch { if (store.deletePayeeLocation(item.location)) nearby = nearby.filter { it.id != item.id } }
                },
                onClose = { showPayeePicker = false },
            )
        }
    }
}
