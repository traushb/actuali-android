package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.TransactionSearchMatcher
import io.github.traushb.actuali.models.groupedByDate
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.fetchUncategorizedTransactions
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.toggleCleared
import io.github.traushb.actuali.services.updateTransaction
import io.github.traushb.actuali.services.withImpactCue
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.listTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Every transaction still needing a category, for triage (GH #26) — the
 * WebUI's "uncategorized" pseudo-account. Tapping a row opens a category
 * picker that saves immediately, or the full editor when the
 * `uncategorizedTapAction` setting says so (GH #260).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UncategorizedTransactionsView(onClose: (() -> Unit)? = null) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var categorizing by remember { mutableStateOf<Transaction?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun reload() {
        transactions = store.fetchUncategorizedTransactions()
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    fun assignCategory(categoryId: String?, transaction: Transaction) {
        categoryId ?: return
        val updated = transaction.copy(categoryId = categoryId, categoryName = store.categoryGroups.flatMap { it.categories }.firstOrNull { it.id == categoryId }?.name)
        scope.launch {
            try {
                store.withImpactCue(listOf(transaction, updated)) { store.updateTransaction(updated, transaction) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.error = L("Failed to categorize transaction: %@", e.localizedMessage)
            }
            reload()
        }
    }

    val filtered = if (searchText.isEmpty()) transactions else TransactionSearchMatcher(searchText).let { matcher -> transactions.filter(matcher::matches) }

    @Composable
    fun Row(transaction: Transaction, showDate: Boolean, tile: Modifier) {
        TransactionRow(
            transaction,
            tile
                .clickable {
                    if (store.uncategorizedTapAction.opensEditor(transaction)) editing = transaction else categorizing = transaction
                }
                .padding(horizontal = 16.dp),
            showDate = showDate,
            // Split children keep an inert dot: their cleared state follows the parent's.
            onToggleCleared = if (transaction.parentId == null) {
                {
                    scope.launch {
                        store.toggleCleared(transaction)
                        reload()
                    }
                }
            } else {
                null
            },
        )
    }

    Scaffold(topBar = { AppTopBar(L("Uncategorized"), onBack = onClose) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (transactions.isEmpty() && loaded) {
                EmptyState(L("All Categorized"), Icons.Filled.CheckCircle, L("Every transaction has a category"))
                return@Column
            }
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
                    if (transactions.isNotEmpty() && filtered.isEmpty()) {
                        item { Text(L("No matching transactions"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                    }
                    if (store.transactionDisplayMode == TransactionDisplayMode.GroupedByDate) {
                        for (group in filtered.groupedByDate()) {
                            item(key = "date-${group.id}") { ListSectionHeader(group.title) }
                            itemsIndexed(group.transactions, key = { _, it -> it.id }) { index, it -> Row(it, showDate = false, Modifier.listTile(index, group.transactions.size)) }
                        }
                    } else {
                        itemsIndexed(filtered, key = { _, it -> it.id }) { index, it -> Row(it, showDate = true, Modifier.listTile(index, filtered.size)) }
                    }
                }
            }
        }
    }

    categorizing?.let { transaction ->
        FullScreenSheet({ categorizing = null }) {
            CategoryPickerView(null, onPick = {
                categorizing = null
                assignCategory(it, transaction)
            }, onClose = { categorizing = null })
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
}
