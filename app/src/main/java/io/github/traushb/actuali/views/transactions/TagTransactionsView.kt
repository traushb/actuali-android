package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionSearchMatcher
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.fetchTransactions
import io.github.traushb.actuali.services.refreshTagSummaries
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.composeColor
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.settings.EditTagSheet
import kotlinx.coroutines.launch

/** Every transaction a tag is on, under the tag's totals and the dates they span. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagTransactionsView(tagId: String) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val tag = store.tags.firstOrNull { it.id == tagId } ?: return
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var editingTag by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        transactions = store.fetchTransactions(taggedWith = tag.tag)
        loaded = true
    }
    LaunchedEffect(tag.tag, store.dataVersion) { load() }

    val summary = store.tagSummaries.firstOrNull { it.tag.id == tag.id }
    val totalSpent = summary?.totalSpent ?: transactions.sumOf { if (it.amount < 0) -it.amount else 0 }
    val net = summary?.netAmount ?: transactions.sumOf { it.amount }
    val count = summary?.transactionCount ?: transactions.size
    val dates = transactions.mapNotNull { DayDate.fromYYYYMMDD(it.date) }
    val dateRange = dates.minOrNull()?.let { first ->
        val last = dates.max()
        if (first == last) first.iso else "${first.iso} – ${last.iso}"
    }
    val filtered = if (searchText.isEmpty()) transactions else TransactionSearchMatcher(searchText).let { matcher -> transactions.filter(matcher::matches) }

    Scaffold(topBar = { AppTopBar(tag.displayName) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(searchText, { searchText = it }, L("Search tagged transactions"))
            RefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        store.refreshTagSummaries()
                        load()
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.background(tag.composeColor.copy(alpha = 0.15f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(Modifier.size(10.dp).background(tag.composeColor, CircleShape))
                                        Text(tag.displayName, style = MaterialTheme.typography.titleLargeEmphasized)
                                    }
                                    Box(Modifier.weight(1f))
                                    IconButton(onClick = { editingTag = true }) { Icon(Icons.Filled.Edit, contentDescription = L("Edit Tag")) }
                                }
                                tag.description?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                HorizontalDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Stat(L("Total Spent"), store.displayBalance(totalSpent))
                                    Stat(L("Net Amount"), store.displayBalance(net), if (net > 0) MaterialTheme.colorScheme.positive else Color.Unspecified)
                                    Stat(L("Transactions"), count.toString())
                                }
                                dateRange?.let {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "title") { ListSectionHeader(L("Transactions")) }
                    when {
                        !loaded && transactions.isEmpty() -> item(key = "loading") { DelayedLoadingIndicator(Modifier.fillMaxWidth().padding(24.dp), contained = true) }
                        filtered.isEmpty() -> item(key = "empty") {
                            EmptyState(L("No Transactions"), Icons.Filled.Tag, L("No transactions tagged with %@", tag.displayName), Modifier.fillParentMaxHeight(0.4f))
                        }
                        else -> itemsIndexed(filtered, key = { _, it -> it.id }) { index, transaction ->
                            TransactionListRow(
                                transaction,
                                isSelectionMode = false,
                                onSelectionModeChange = {},
                                onEdit = { editing = it },
                                modifier = Modifier.listTile(index, filtered.size),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { transaction ->
        FullScreenSheet({ editing = null }) {
            EditTransactionView(transaction) { editing = null }
        }
    }
    if (editingTag) EditTagSheet(tag) { editingTag = false }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMediumEmphasized, color = color)
    }
}
