package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.TransactionBulkEdit
import io.github.traushb.actuali.services.addTag
import io.github.traushb.actuali.services.deleteTransactions
import io.github.traushb.actuali.services.duplicateTransactions
import io.github.traushb.actuali.services.mergeTransactions
import io.github.traushb.actuali.services.setCategory
import io.github.traushb.actuali.services.setClearedStatus
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.launch
import java.util.Locale

object TransactionBulkActionLocalization {
    fun duplicateLabel(count: Int, locale: Locale = Locale.getDefault()) = ReportStrings.localized("Duplicate %lld selected transactions", count.toLong(), locale = locale)

    fun deleteLabel(count: Int, locale: Locale = Locale.getDefault()) = ReportStrings.localized("Delete %lld selected transactions", count.toLong(), locale = locale)

    fun deleteConfirmationTitle(count: Int, locale: Locale = Locale.getDefault()) = ReportStrings.localized("Delete %lld transactions?", count.toLong(), locale = locale)
}

/**
 * The bar a transaction list shows in selection mode: select all, categorize,
 * tag, merge (exactly two mergeable rows), cleared status, duplicate and
 * delete. With more than one selected it grows the count and the selection's total.
 */
@Composable
fun TransactionBulkActionBar(transactions: List<Transaction>, selectedIds: Set<String>, onSelectionChange: (Set<String>) -> Unit, onFinish: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    var pickingTag by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val selected = transactions.filter { it.id in selectedIds }
    val count = selectedIds.size
    val allSelected = transactions.isNotEmpty() && count == transactions.size
    val canMerge = count == 2 && selected.size == 2 && TransactionBulkEdit.canMerge(selected[0], selected[1])

    // Drop ids the list no longer holds (refilter, search), so the counts match what the actions touch.
    LaunchedEffect(transactions) {
        val visible = transactions.mapTo(HashSet()) { it.id }
        if (!visible.containsAll(selectedIds)) onSelectionChange(selectedIds intersect visible)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (count > 1) {
            val total = TransactionBulkEdit.total(selected)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 2.dp) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("transactionBulkBar.summary"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(L("%lld selected", count.toLong()), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(L("Total"), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                    Text(store.displayBalance(total), style = MaterialTheme.typography.titleSmallEmphasized, color = if (total < 0) Color.Unspecified else MaterialTheme.colorScheme.positive)
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            leadingContent = {
                TextButton(onClick = { onSelectionChange(if (allSelected) emptySet() else transactions.mapTo(HashSet()) { it.id }) }) {
                    Text(L(if (allSelected) "Deselect All" else "Select All"), fontWeight = FontWeight.SemiBold)
                }
            },
        ) {
            IconButton(onClick = { pickingCategory = true }, enabled = count > 0, modifier = Modifier.testTag("transactionBulkBar.categorize")) {
                Icon(Icons.Outlined.Sell, contentDescription = L("Categorize"))
            }
            IconButton(onClick = { pickingTag = true }, enabled = count > 0, modifier = Modifier.testTag("transactionBulkBar.tag")) {
                Icon(Icons.Filled.Tag, contentDescription = L("Add Tag"))
            }
            if (canMerge) {
                IconButton(onClick = {
                    scope.launch {
                        store.mergeTransactions(selected[0], selected[1])
                        onSelectionChange(emptySet())
                    }
                }, modifier = Modifier.testTag("transactionBulkBar.merge")) { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = L("Merge")) }
            }
            Box {
                IconButton(onClick = { moreMenu = true }, enabled = count > 0, modifier = Modifier.testTag("transactionBulkBar.more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = L("More"))
                }
                DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text(L("Mark Cleared")) }, onClick = {
                        moreMenu = false
                        scope.launch { store.setClearedStatus(selected, true) }
                    })
                    DropdownMenuItem(text = { Text(L("Mark Uncleared")) }, onClick = {
                        moreMenu = false
                        scope.launch { store.setClearedStatus(selected, false) }
                    })
                    DropdownMenuItem(text = { Text(TransactionBulkActionLocalization.duplicateLabel(count)) }, onClick = {
                        moreMenu = false
                        scope.launch {
                            store.duplicateTransactions(selected)
                            onFinish()
                        }
                    })
                }
            }
            IconButton(onClick = { confirmingDelete = true }, enabled = count > 0) {
                Icon(Icons.Filled.Delete, contentDescription = TransactionBulkActionLocalization.deleteLabel(count), tint = if (count > 0) MaterialTheme.colorScheme.negative else Color.Unspecified)
            }
        }
    }

    if (confirmingDelete) {
        ConfirmationDialog(
            title = TransactionBulkActionLocalization.deleteConfirmationTitle(count),
            confirmLabel = TransactionBulkActionLocalization.deleteLabel(count),
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                scope.launch {
                    store.deleteTransactions(selected)
                    onFinish()
                }
            },
        )
    }
    if (pickingCategory) {
        FullScreenSheet(onDismiss = { pickingCategory = false }) {
            CategoryPickerView(null, onPick = { categoryId ->
                pickingCategory = false
                scope.launch { store.setCategory(categoryId, selected) }
            }, onClose = { pickingCategory = false })
        }
    }
    if (pickingTag) {
        FullScreenSheet(onDismiss = { pickingTag = false }) {
            TransactionTagPickerSheet(onPick = { tag ->
                pickingTag = false
                scope.launch { store.addTag(tag, selected) }
            }, onClose = { pickingTag = false })
        }
    }
}

/** The tag to add to a batch of selected transactions: an existing tag from the list, or a new name typed in the field. */
@Composable
fun TransactionTagPickerSheet(onPick: (String) -> Unit, onClose: () -> Unit) {
    val store = LocalBudgetStore.current
    var text by remember { mutableStateOf("") }
    val typedName = Tag.normalizeTagName(text)
    val typedNameIsInvalid = typedName.isNotEmpty() && !Tag.isValidTagName(text)
    val tags = store.tags.filter { !it.hidden && !it.tombstone }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.tag })
        .filter { typedName.isEmpty() || it.tag.contains(typedName, ignoreCase = true) }
    fun addTyped() {
        if (typedName.isNotEmpty() && !typedNameIsInvalid) onPick(typedName)
    }

    Scaffold(topBar = { AppTopBar(L("Add Tag"), onBack = onClose) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        placeholder = { Text(L("Tag Name")) },
                        singleLine = true,
                        isError = typedNameIsInvalid,
                        supportingText = if (typedNameIsInvalid) ({ Text(L("Tag names cannot contain spaces or the # symbol.")) }) else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTyped() }),
                        modifier = Modifier.weight(1f).testTag("transactionTagPicker.field"),
                    )
                    TextButton(onClick = ::addTyped, enabled = typedName.isNotEmpty() && !typedNameIsInvalid, modifier = Modifier.testTag("transactionTagPicker.add")) { Text(L("Add")) }
                }
            }
            if (tags.isNotEmpty()) {
                item { Text(L("Tags"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) }
                items(tags, key = { it.id }) { tag ->
                    ListItem(headlineContent = { Text(tag.displayName) }, modifier = Modifier.fillMaxWidth().clickable { onPick(tag.tag) })
                }
            }
        }
    }
}
