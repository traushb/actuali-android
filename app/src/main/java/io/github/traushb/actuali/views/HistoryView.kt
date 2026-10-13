package io.github.traushb.actuali.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.HistoryAction
import io.github.traushb.actuali.services.HistoryActionKind
import io.github.traushb.actuali.services.HistoryActionStatus
import io.github.traushb.actuali.services.HistoryStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.listTile
import kotlinx.coroutines.launch

object HistoryText {
    /** What an edit changed, the first difference in the edited transaction (or its split) that a person would notice. */
    fun editedDetail(action: HistoryAction, primary: Transaction, formatCurrency: (Long) -> String): String? {
        if (action.kind != HistoryActionKind.Edited) return null
        val primaryRoot = primary.parentId ?: primary.id
        for (changed in action.after) {
            if ((changed.parentId ?: changed.id) != primaryRoot) continue
            val before = action.before.firstOrNull { it.id == changed.id } ?: continue
            when {
                before.amount != changed.amount -> return L("Amount: %@ → %@", formatCurrency(before.amount), formatCurrency(changed.amount))
                before.categoryId != changed.categoryId -> return L("Category: %@ → %@", before.categoryName ?: L("Uncategorized"), changed.categoryName ?: L("Uncategorized"))
                before.payeeId != changed.payeeId -> return L("Payee: %@ → %@", before.payeeName ?: L("Transaction"), changed.payeeName ?: L("Transaction"))
                before.notes != changed.notes -> return when {
                    !before.notes.isNullOrEmpty() && !changed.notes.isNullOrEmpty() -> L("Note changed")
                    !changed.notes.isNullOrEmpty() -> L("Note added")
                    else -> L("Note removed")
                }
                before.date != changed.date -> return L("Date: %@ → %@", Transaction.formattedDate(before.date), Transaction.formattedDate(changed.date))
                before.cleared != changed.cleared -> return L(if (changed.cleared) "Marked cleared" else "Marked uncleared")
                before.reconciled != changed.reconciled -> return L(if (changed.reconciled) "Marked reconciled" else "Marked unreconciled")
            }
        }
        return null
    }
}

/** The transaction changes made on this device for the open budget, newest first, each undoable while the data still matches it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryView() {
    val store = LocalBudgetStore.current
    val history = HistoryStore.shared
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<HistoryAction?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun load() {
        val budgetID = store.currentBudgetId
        if (budgetID == null) history.clearLoadedActions() else history.load(budgetID)
    }
    LaunchedEffect(store.currentBudgetId) { load() }

    fun detail(action: HistoryAction): String {
        val snapshot = action.primarySnapshot ?: return action.detail
        val account = store.accounts.firstOrNull { it.id == snapshot.accountId }?.name
        HistoryText.editedDetail(action, snapshot) { store.formatCurrency(it) }?.let { return it }
        if (action.after.size == 2) {
            val otherId = action.after.firstOrNull { it.id != snapshot.id }?.accountId
            store.accounts.firstOrNull { it.id == otherId }?.name?.let { return L("%@ → %@", account ?: L("Account"), it) }
        }
        val portions = snapshot.splitPortions
        if (snapshot.isParent && !portions.isNullOrEmpty()) return L("%@ categories · %@", portions.size.toString(), account ?: L("Account"))
        val parts = listOfNotNull(snapshot.categoryName?.takeIf { it.isNotEmpty() }, account, L("Note").takeIf { !snapshot.notes.isNullOrEmpty() })
        return if (parts.isEmpty()) action.detail else parts.joinToString(" · ")
    }

    Scaffold(topBar = { AppTopBar(L("History")) }) { padding ->
        RefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                load()
                refreshing = false
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (history.actions.isEmpty()) {
                EmptyState(L("No History Yet"), Icons.Filled.History, L("Transaction changes appear here."))
                return@RefreshBox
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                itemsIndexed(history.actions, key = { _, it -> it.id }) { index, action ->
                    val undone = action.status == HistoryActionStatus.Undone
                    Row(
                        Modifier.listTile(index, history.actions.size).heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when (action.kind) {
                                HistoryActionKind.Created -> Icons.Filled.AddCircleOutline
                                HistoryActionKind.Edited -> Icons.Outlined.Edit
                                HistoryActionKind.Deleted -> Icons.Filled.DeleteOutline
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                action.primarySnapshot?.amount?.let { Text(store.formatCurrency(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                            }
                            Text(detail(action), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            undone -> Text(L("Undone"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                            history.canUndo(action) -> TextButton(onClick = { selected = action }) { Text(L("Undo")) }
                        }
                    }
                }
            }
        }
    }

    selected?.let { action ->
        HistoryUndoReview(action, detail(action), { store.formatCurrency(it) }, onDismiss = { selected = null }) {
            scope.launch {
                history.undo(action, store)
                if (history.errorMessage == null) selected = null
            }
        }
    }
    MessageAlert(history.errorTitle, history.errorMessage) { history.clearError() }
}

/** What an undo will restore (or remove), confirmed before it runs. */
@Composable
private fun HistoryUndoReview(action: HistoryAction, detail: String, formatAmount: (Long) -> String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FormSheet(L("Review Undo"), onDismiss = onDismiss, confirmLabel = L("Undo"), onConfirm = onConfirm) {
        FormSection {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(action.title, style = MaterialTheme.typography.titleMedium)
                action.primarySnapshot?.amount?.let { Text(formatAmount(it), style = MaterialTheme.typography.titleLargeEmphasized) }
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FormSection(L("Restore"), if (action.before.isEmpty()) L("The transaction(s) created by this action will be removed.") else null) {
            for (snapshot in action.before.ifEmpty { action.after }) {
                FormRow(
                    snapshot.payeeName?.takeIf { it.isNotEmpty() } ?: L("Transaction"),
                    supporting = Transaction.formattedDate(snapshot.date),
                    value = formatAmount(snapshot.amount),
                )
            }
        }
    }
}
