package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionPager
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.deleteTransaction
import io.github.traushb.actuali.services.duplicateTransaction
import io.github.traushb.actuali.services.offBudgetAccountIds
import io.github.traushb.actuali.services.rules.TagFilter
import io.github.traushb.actuali.services.toggleCleared
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.MonogramAvatar
import io.github.traushb.actuali.views.components.composeColor
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.onPositive
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.launch
import java.util.Locale

/** The words the transaction rows use, resolved per locale so tests can check each translation. */
object TransactionsListLocalization {
    fun statusLabel(cleared: Boolean, reconciled: Boolean, locale: Locale = Locale.getDefault()): String =
        ReportStrings.text(if (reconciled) "Reconciled" else if (cleared) "Cleared" else "Uncleared", locale)

    fun selectionLabel(isSelected: Boolean, locale: Locale = Locale.getDefault()): String =
        ReportStrings.text(if (isSelected) "Selected" else "Not selected", locale)

    /**
     * Split parents may resolve no payee (mixed child payees) — label them
     * "Split" like the desktop app, not "Unknown". Off-budget rows say "No
     * payee": they're commonly payee-less (balance adjustments) and "Unknown"
     * read as a bug (GH #123).
     */
    fun payeeLabel(payeeName: String?, isParent: Boolean, isInOffBudgetAccount: Boolean, locale: Locale = Locale.getDefault()): String =
        payeeName ?: ReportStrings.text(
            when {
                isParent -> "Split"
                isInOffBudgetAccount -> "No payee"
                else -> "Unknown"
            },
            locale,
        )

    /**
     * Caption under the payee. Off-budget accounts aren't categorized at all
     * ("Off budget", GH #123); split parents say "Split" with each child's
     * line beneath it (GH #552); transfers that can't take a category show
     * "Transfer" instead of nagging "Uncategorized" (GH #104).
     */
    fun categoryLabel(
        categoryName: String?,
        isParent: Boolean,
        splitBreakdown: String?,
        isInOffBudgetAccount: Boolean,
        isTransfer: Boolean,
        needsCategory: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (isInOffBudgetAccount) return ReportStrings.text("Off budget", locale)
        if (isParent) {
            val split = ReportStrings.text("Split", locale)
            return splitBreakdown?.let { "$split\n$it" } ?: split
        }
        if (categoryName == null && isTransfer && !needsCategory) return ReportStrings.text("Transfer", locale)
        return categoryName ?: ReportStrings.text("Uncategorized", locale)
    }
}

/**
 * The cleared / reconciled status badged on a transaction row's avatar: a
 * green disc with a check — a lock once reconciled — or an empty ring while
 * uncleared, set in the row's own color so it reads over the avatar.
 */
@Composable
fun ClearedIndicator(cleared: Boolean, reconciled: Boolean, modifier: Modifier = Modifier) {
    val label = TransactionsListLocalization.statusLabel(cleared, reconciled)
    Box(
        modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .then(if (cleared) Modifier.background(MaterialTheme.colorScheme.positive, CircleShape) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (cleared) Icon(if (reconciled) Icons.Filled.Lock else Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPositive, modifier = Modifier.size(11.dp))
    }
}

/**
 * A row's lead: the payee's monogram, colored per payee so a list reads at a
 * glance, with the cleared status badged on its corner — where it's tapped
 * to toggle.
 */
@Composable
private fun PayeeStatusAvatar(payee: String, cleared: Boolean, reconciled: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp)) {
        MonogramAvatar(payee, payee, Modifier.align(Alignment.TopStart))
        ClearedIndicator(cleared, reconciled, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * One transaction: payee avatar and status, payee over category and note, amount over
 * account, running balance and date. [onToggleCleared] makes the dot
 * tappable; reconciled rows confirm first, since the store unlocks them
 * instead. null leaves the dot inert (split children).
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier,
    showAccount: Boolean = true,
    showDate: Boolean = true,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleCleared: (() -> Unit)? = null,
) {
    val store = LocalBudgetStore.current
    var confirmingUnlock by remember { mutableStateOf(false) }
    // Selection mode removes the dot's button, so an unlock confirmation still
    // pending from a long press that landed on the dot would surface later
    // with nothing behind it.
    LaunchedEffect(isSelectionMode) { if (isSelectionMode) confirmingUnlock = false }

    val offBudgetIds = store.offBudgetAccountIds
    val isInOffBudgetAccount = transaction.accountId in offBudgetIds
    val splitBreakdown = transaction.splitPortions?.takeIf { it.isNotEmpty() }?.joinToString("\n") { portion ->
        "${portion.categoryName ?: L("Uncategorized")} ${store.displaySpentCaption(portion.amount)}"
    }
    val categoryLabel = TransactionsListLocalization.categoryLabel(
        transaction.categoryName,
        transaction.isParent,
        splitBreakdown,
        isInOffBudgetAccount,
        isTransfer = transaction.transferId != null || transaction.transferAcct != null,
        needsCategory = transaction.needsCategory(offBudgetIds),
    )

    val payeeLabel = TransactionsListLocalization.payeeLabel(transaction.payeeName, transaction.isParent, isInOffBudgetAccount)
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            isSelectionMode -> Row(
                Modifier.semantics { stateDescription = TransactionsListLocalization.selectionLabel(isSelected) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PayeeStatusAvatar(payeeLabel, transaction.cleared, transaction.reconciled)
            }
            onToggleCleared != null -> PayeeStatusAvatar(
                payeeLabel,
                transaction.cleared,
                transaction.reconciled,
                // An unbounded ripple, as an icon button's: clipping to a circle would cut the badge off its corner.
                Modifier
                    .clickable(interactionSource = null, indication = ripple(bounded = false), role = Role.Button) {
                        if (transaction.reconciled) confirmingUnlock = true else onToggleCleared()
                    }
                    .testTag("transaction.status.${transaction.id}"),
            )
            else -> PayeeStatusAvatar(payeeLabel, transaction.cleared, transaction.reconciled)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(payeeLabel, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (transaction.isParent) {
                    Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(categoryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            transaction.notes?.takeIf { it.isNotEmpty() }?.let { NoteLine(it, store.tagsByName) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                store.displayBalance(transaction.amount),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (transaction.isOutflow) Color.Unspecified else MaterialTheme.colorScheme.positive,
            )
            if (showAccount) {
                Text(
                    store.accounts.firstOrNull { it.id == transaction.accountId }?.name ?: L("Unknown Account"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            // Quiet beside the amount it follows; red only once the account dips below zero.
            transaction.runningBalance?.let {
                Text(store.displayBalance(it), style = MaterialTheme.typography.bodySmall, color = if (it < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showDate) {
                Text(transaction.dateFormatted, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmingUnlock && onToggleCleared != null) {
        AlertDialog(
            onDismissRequest = { confirmingUnlock = false },
            text = { Text(L("This transaction is reconciled. Unlock it to make changes?")) },
            confirmButton = {
                TextButton({
                    confirmingUnlock = false
                    onToggleCleared()
                }) { Text(L("Unlock")) }
            },
            dismissButton = { TextButton({ confirmingUnlock = false }) { Text(L("Cancel")) } },
        )
    }
}

/** The note, with each `#tag` drawn in its tag's color so the tags read as part of the note. */
@Composable
private fun NoteLine(notes: String, tagsByName: Map<String, Tag>) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val tagColors = tagsByName.mapValues { (_, tag) -> tag.composeColor }
    val text = buildAnnotatedString {
        for (segment in TagFilter.noteSegments(notes)) {
            when (segment) {
                is TagFilter.NoteSegment.Text -> withStyle(SpanStyle(color = secondary)) { append(segment.text) }
                is TagFilter.NoteSegment.TagSegment -> {
                    val color = tagColors[Tag.normalizeTagName(segment.tag).lowercase()] ?: accent
                    withStyle(SpanStyle(color = color, background = color.copy(alpha = 0.15f), fontWeight = FontWeight.SemiBold)) { append(segment.tag) }
                }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = notes })
}

/**
 * Tappable [TransactionRow] with the standard row gestures, shared by the
 * all-accounts and account-detail lists: tap edits (or toggles selection),
 * long press enters selection mode — or leaves it, clearing the selection —
 * and swiping duplicates (start to end) or deletes (end to start).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionListRow(
    transaction: Transaction,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    onEdit: (Transaction) -> Unit,
    modifier: Modifier = Modifier,
    showAccount: Boolean = true,
    showDate: Boolean = true,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipeState = rememberSwipeToDismissBoxState(
        // Spring back either way: the list refreshes once the store has applied the action.
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> scope.launch { store.duplicateTransaction(transaction) }
                SwipeToDismissBoxValue.EndToStart -> scope.launch { store.deleteTransaction(transaction) }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        // Half the row, so a scroll gesture that drifts sideways can't delete.
        positionalThreshold = { it * 0.5f },
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode,
        backgroundContent = { SwipeBackground(swipeState.dismissDirection) },
        modifier = modifier,
    ) {
        TransactionRow(
            transaction,
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { if (isSelectionMode) onToggleSelect?.invoke() else onEdit(transaction) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isSelectionMode) {
                            onSelectionModeChange(false)
                        } else {
                            onSelectionModeChange(true)
                            onToggleSelect?.invoke()
                        }
                    },
                )
                .padding(horizontal = 16.dp)
                .testTag("transactionRow.${transaction.id}"),
            showAccount = showAccount,
            showDate = showDate,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            onToggleCleared = { scope.launch { store.toggleCleared(transaction) } },
        )
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, icon, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeAction(MaterialTheme.colorScheme.primary, Icons.Filled.ContentCopy, L("Duplicate"), Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> SwipeAction(MaterialTheme.colorScheme.negative, Icons.Filled.Delete, L("Delete"), Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = alignment) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

private data class SwipeAction(val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val alignment: Alignment)

/** Sentinel row: coming into view near the bottom of the list pulls in the next page. */
@Composable
fun TransactionPagingSentinel(pager: TransactionPager) {
    LaunchedEffect(pager, pager.transactions.size) { pager.loadNextPage() }
    // Delayed: this comes into view mid-scroll, and a quick page load shouldn't build an indicator just to drop it.
    DelayedLoadingIndicator(Modifier.fillMaxWidth().padding(16.dp).height(48.dp))
}
