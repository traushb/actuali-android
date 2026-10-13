package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.TransactionSearchMatcher
import io.github.traushb.actuali.models.groupedByDate
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.fetchCategoryTransactions
import io.github.traushb.actuali.services.fetchNote
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.toggleCleared
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.NoteCard
import io.github.traushb.actuali.views.components.NoteEditorView
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.listTile
import kotlinx.coroutines.launch
import java.util.Locale

object CategoryTransactionsText {
    fun scopeTitle(month: String?, locale: Locale = Locale.getDefault()): String = month?.let { MonthTitles.title(it, locale) } ?: ReportStrings.text("All Time", locale)

    fun emptyStateTitle(locale: Locale = Locale.getDefault()): String = ReportStrings.text("No Transactions", locale)

    fun emptyStateDescription(categoryName: String, month: String?, locale: Locale = Locale.getDefault()): String =
        ReportStrings.format("Nothing in %@ for %@", categoryName, month?.let { MonthTitles.title(it, locale) } ?: ReportStrings.text("any month", locale), locale = locale)
}

/**
 * Every transaction counting toward one category's spend, pushed from the
 * Budget tab (GH #56), with the category's note above it (GH #131). The row
 * set mirrors the budget month's spent query, so a month-scoped list sums to
 * the Spent figure the user tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsView(categoryId: String, categoryName: String, month: String?) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    // Unsupported until the read confirms this file can store notes (GH #131).
    var note by remember { mutableStateOf(EntityNote.unsupported) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var editingNote by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun reload() {
        transactions = store.fetchCategoryTransactions(categoryId, month)
        loaded = true
        note = store.fetchNote(categoryId)
    }
    LaunchedEffect(categoryId, month, store.dataVersion) { reload() }

    val filtered = if (searchText.isEmpty()) transactions else TransactionSearchMatcher(searchText).let { matcher -> transactions.filter(matcher::matches) }

    fun LazyListScope.rows(items: List<Transaction>, showDate: Boolean) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, transaction ->
            // Split children display only: deleting one leg would break the parent's amount.
            val editable = transaction.parentId == null
            TransactionRow(
                transaction,
                Modifier.listTile(index, items.size).then(if (editable) Modifier.clickable { editing = transaction } else Modifier).padding(horizontal = 16.dp),
                showDate = showDate,
                onToggleCleared = if (editable) ({ scope.launch { store.toggleCleared(transaction) } }) else null,
            )
        }
    }

    Scaffold(topBar = { AppTopBar(categoryName) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(searchText, { searchText = it }, L("Search transactions"))
            RefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        store.sync()
                        reload()
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    // Hidden while searching: a search is about finding transactions, not reading guidance.
                    if (note.supported && searchText.isEmpty()) {
                        item(key = "note") { NoteCard(note, "categoryNoteRow", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { editingNote = true } }
                    }
                    if (transactions.isEmpty() && loaded) {
                        // A row rather than the whole screen, so a category with no transactions still shows its note.
                        item(key = "empty") {
                            EmptyState(
                                CategoryTransactionsText.emptyStateTitle(),
                                Icons.AutoMirrored.Filled.ListAlt,
                                CategoryTransactionsText.emptyStateDescription(categoryName, month),
                                Modifier.fillParentMaxHeight(0.6f),
                            )
                        }
                        return@LazyColumn
                    }
                    // The scope total is the point of this screen; it sums the filtered rows, matching what's shown.
                    item(key = "scope") {
                        Row(Modifier.fillMaxWidth()) {
                            ListSectionHeader(CategoryTransactionsText.scopeTitle(month), Modifier.weight(1f))
                            ListSectionHeader(L("Total %@", store.displayBalance(filtered.sumOf { it.amount })))
                        }
                    }
                    if (transactions.isNotEmpty() && filtered.isEmpty()) {
                        item(key = "noMatch") { Text(L("No matching transactions"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
                    }
                    if (store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate) {
                        for (group in filtered.groupedByDate()) {
                            item(key = "date-${group.id}") { ListSectionHeader(group.title) }
                            rows(group.transactions, showDate = false)
                        }
                    } else {
                        rows(filtered, showDate = true)
                    }
                }
            }
        }
    }

    editing?.let { transaction ->
        FullScreenSheet({ editing = null }) {
            EditTransactionView(transaction) {
                editing = null
                scope.launch { reload() }
            }
        }
    }
    if (editingNote) {
        FullScreenSheet({ editingNote = false }) {
            NoteEditorView(categoryId, categoryName, note.text) {
                editingNote = false
                scope.launch { note = store.fetchNote(categoryId) }
            }
        }
    }
}
