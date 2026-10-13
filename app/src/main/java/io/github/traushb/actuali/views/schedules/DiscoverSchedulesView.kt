package io.github.traushb.actuali.views.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.createSchedules
import io.github.traushb.actuali.services.discoverSchedules
import io.github.traushb.actuali.services.schedules.ScheduleDescription
import io.github.traushb.actuali.services.schedules.ScheduleDiscovery
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.listTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Offers recurring transactions as schedules ("Find schedules" on the web). */
@Composable
fun DiscoverSchedulesView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var proposals by remember { mutableStateOf<List<ScheduleDiscovery.Proposal>>(emptyList()) }
    val selected = remember { mutableStateListOf<String>() }
    var isSearching by remember { mutableStateOf(true) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        proposals = store.discoverSchedules()
        isSearching = false
    }

    Scaffold(
        topBar = {
            AppTopBar(L("Find Schedules"), actions = {
                TextButton(
                    enabled = selected.isNotEmpty() && !isCreating,
                    onClick = {
                        scope.launch {
                            isCreating = true
                            try {
                                store.createSchedules(proposals.filter { it.id in selected }.map { it.formFields })
                                nav.pop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorMessage = e.localizedMessage
                            } finally {
                                isCreating = false
                            }
                        }
                    },
                ) { Text(L("Create")) }
            })
        },
    ) { padding ->
        when {
            isSearching -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Text(L("Looking for repeating transactions…"), modifier = Modifier.padding(top = 16.dp))
            }
            proposals.isEmpty() -> EmptyState(L("Nothing Found"), Icons.Filled.Search, L("No repeating transactions were found in your history."), Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                itemsIndexed(proposals, key = { _, it -> it.id }) { index, proposal ->
                    val checked = proposal.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .listTile(index, proposals.size)
                            .toggleable(checked, role = Role.Checkbox) { if (it) selected.add(proposal.id) else selected.remove(proposal.id) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(
                                    store.payees.firstOrNull { it.id == proposal.payeeId }?.name ?: L("Unknown payee"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(store.displayBalance(proposal.amount))
                            }
                            Text(ScheduleDescription.recurring(proposal.config), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                store.accounts.firstOrNull { it.id == proposal.accountId }?.name ?: L("Unknown account"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    MessageAlert(L("Couldn't Create Schedules"), errorMessage) { errorMessage = null }
}
