package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.NearbyPayee
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.location.LocationUtils
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.SearchFieldShape
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.components.searchFieldColors
import io.github.traushb.actuali.views.negative

/** The payee picker's pure rules, apart from the screen so they can be tested without one. */
object PayeePickerLogic {
    /** The accounts matching the search, in the order given (on-budget first). An empty search lists them all. */
    fun transferAccounts(searchText: String, accounts: List<Account>): List<Account> =
        if (searchText.isEmpty()) accounts else accounts.filter { it.name.contains(searchText, ignoreCase = true) }

    fun allowedPayees(payees: List<Payee>): List<Payee> = payees.filter { !it.tombstone && it.transferAccountId == null }

    /**
     * Payees for the list. With [transferFromAccountId] (a split line), other
     * open accounts' transfer payees are offered too. Twenty at most; a
     * search puts prefix matches first.
     */
    fun filteredPayees(payees: List<Payee>, accounts: List<Account>, transferFromAccountId: String?, searchText: String): List<Payee> {
        val accountNames = accounts.associate { it.id to it.name }
        val openAccountIds = accounts.filter { !it.closed }.mapTo(HashSet()) { it.id }
        val usable = payees.filter { payee ->
            if (payee.tombstone) return@filter false
            val transferAccountId = payee.transferAccountId ?: return@filter true
            transferFromAccountId != null && transferAccountId != transferFromAccountId && transferAccountId in openAccountIds
        }
        fun name(payee: Payee) = displayName(payee, accountNames)
        if (searchText.isEmpty()) {
            val sorted = usable.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { name(it) })
            if (transferFromAccountId == null) return sorted.take(20)
            return sorted.filter { it.transferAccountId == null }.take(20) + sorted.filter { it.transferAccountId != null }
        }
        val lower = searchText.lowercase()
        return usable.filter { name(it).contains(searchText, ignoreCase = true) }
            .sortedWith(compareBy<Payee> { !name(it).lowercase().startsWith(lower) }.thenBy(String.CASE_INSENSITIVE_ORDER) { name(it) })
            .take(20)
    }

    fun displayName(payee: Payee, accounts: List<Account>): String = displayName(payee, accounts.associate { it.id to it.name })

    private fun displayName(payee: Payee, accountNames: Map<String, String>): String {
        val accountName = payee.transferAccountId?.let { accountNames[it] } ?: return payee.name
        return "${L("Transfer")}: $accountName"
    }

    /** A typed name can be committed as a new payee unless an existing one already has it. */
    fun canCommitCustomPayee(searchText: String, payees: List<Payee>): Boolean =
        searchText.isNotEmpty() && payees.none { !it.tombstone && it.transferAccountId == null && it.name.equals(searchText, ignoreCase = true) }

    fun committedPayeeId(currentName: String, currentId: String?, committedName: String): String? = if (currentName == committedName) currentId else null
}

/**
 * Picks a transaction's payee: nearby and suggested payees, accounts to
 * transfer to or from (choosing one makes the transaction a transfer, as in
 * Actual), or a new name typed in the search field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayeePickerView(
    payeeName: String,
    nearbyPayees: List<NearbyPayee>,
    onSelect: (Payee) -> Unit,
    onCommit: (String) -> Unit,
    onDeleteNearby: (NearbyPayee) -> Unit,
    onClose: () -> Unit,
    transferFromAccountId: String? = null,
    transferAccounts: List<Account> = emptyList(),
    accountsOnly: Boolean = false,
    onSelectAccount: (Account) -> Unit = {},
) {
    val store = LocalBudgetStore.current
    // The pre-filled name starts selected, so the first keystroke replaces it (GH #486).
    var search by remember { mutableStateOf(TextFieldValue(payeeName, TextRange(0, payeeName.length))) }
    var suggested by remember { mutableStateOf<List<Payee>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        suggested = PayeePickerLogic.allowedPayees(store.fetchCommonPayees())
    }
    val query = search.text.trim()
    val filtered = PayeePickerLogic.filteredPayees(store.payees, store.accounts, transferFromAccountId, query)
    val matchingAccounts = PayeePickerLogic.transferAccounts(query, transferAccounts)

    Scaffold(
        topBar = {
            AppTopBar(L("Payee"), actions = {
                TextButton(onClick = { if (accountsOnly) onClose() else onCommit(query) }) { Text(L("Done")) }
            }, onBack = onClose)
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TextField(
                search,
                { search = it },
                placeholder = { Text(L("Search payees")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.text.isNotEmpty()) {
                        IconButton(onClick = { search = TextFieldValue("") }, modifier = Modifier.testTag("payeePicker.clearSearch")) {
                            Icon(Icons.Filled.Cancel, contentDescription = L("Clear text"))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (accountsOnly) onClose() else onCommit(query) }),
                shape = SearchFieldShape,
                colors = searchFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!accountsOnly && query.isEmpty()) {
                    if (nearbyPayees.isNotEmpty()) {
                        val nearby = nearbyPayees.take(5)
                        sectionHeader(L("Nearby"))
                        itemsIndexed(nearby, key = { _, it -> "nearby-${it.id}" }) { index, item ->
                            NearbyRow(item, Modifier.listTile(index, nearby.size), onSelect = { onSelect(item.payee) }, onDelete = { onDeleteNearby(item) })
                        }
                    }
                    if (suggested.isNotEmpty()) {
                        val top = suggested.take(5)
                        sectionHeader(L("Suggested Payees"))
                        itemsIndexed(top, key = { _, it -> "suggested-${it.id}" }) { index, payee -> PayeeRow(payee, Modifier.listTile(index, top.size), onSelect) }
                    }
                }
                if (matchingAccounts.isNotEmpty()) {
                    sectionHeader(L("Transfer to / from"))
                    itemsIndexed(matchingAccounts, key = { _, it -> "account-${it.id}" }) { index, account ->
                        TileItem(
                            Modifier.listTile(index, matchingAccounts.size).testTag("payeePicker.transfer.${account.id}"),
                            onClick = { onSelectAccount(account) },
                            leading = { Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        ) { Text(account.name) }
                    }
                }
                if (!accountsOnly) {
                    val rows = if (query.isEmpty()) filtered.filter { payee -> suggested.none { it.id == payee.id } } else filtered
                    if (rows.isNotEmpty()) {
                        sectionHeader(if (query.isEmpty()) L("Payees") else L("Suggestions"))
                        itemsIndexed(rows, key = { _, it -> "payee-${it.id}" }) { index, payee -> PayeeRow(payee, Modifier.listTile(index, rows.size), onSelect) }
                    }
                    if (PayeePickerLogic.canCommitCustomPayee(query, store.payees)) {
                        item(key = "commit") {
                            TileItem(
                                Modifier.padding(top = 16.dp).listTile(0, 1),
                                onClick = { onCommit(query) },
                                leading = { Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            ) { Text(L("Use \"%@\"", query)) }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") { ListSectionHeader(title) }
}

/** A picker row as one tile of its section. */
@Composable
private fun TileItem(
    modifier: Modifier,
    onClick: () -> Unit,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = headline,
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PayeeRow(payee: Payee, modifier: Modifier, onSelect: (Payee) -> Unit) {
    val store = LocalBudgetStore.current
    TileItem(modifier, onClick = { onSelect(payee) }, leading = { Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        Text(PayeePickerLogic.displayName(payee, store.accounts))
    }
}

/** A nearby suggestion; swiping it away forgets that one recorded location. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyRow(nearby: NearbyPayee, modifier: Modifier, onSelect: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(
        state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Text(L("Delete"), color = Color.White, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.negative).padding(16.dp))
        },
    ) {
        ListItem(
            headlineContent = { Text(nearby.payee.name) },
            leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Text(LocationUtils.formatDistance(nearby.distanceMeters), style = MaterialTheme.typography.labelSmall) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable(onClick = onSelect),
        )
    }
}
