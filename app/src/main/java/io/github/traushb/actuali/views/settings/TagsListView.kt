package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.TagSummary
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.createTag
import io.github.traushb.actuali.services.deleteTag
import io.github.traushb.actuali.services.discoverTags
import io.github.traushb.actuali.services.refreshTagSummaries
import io.github.traushb.actuali.services.renameTag
import io.github.traushb.actuali.services.updateTag
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.composeColor
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.components.parseHexColor
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.Collator

object TagsListLogic {
    /** Live, visible (unless shown) tags matching [query] by name or description, alphabetical. */
    fun filtered(tags: List<Tag>, query: String, showHidden: Boolean): List<Tag> {
        val needle = query.trim().lowercase()
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return tags.filter { tag ->
            !tag.tombstone && (showHidden || !tag.hidden) &&
                (needle.isEmpty() || tag.tag.lowercase().contains(needle) || tag.description?.lowercase()?.contains(needle) == true)
        }.sortedWith { lhs, rhs -> collator.compare(lhs.tag, rhs.tag) }
    }

    /** The row caption reads the signed net, not the outflow-only total: an inflow-only tag mustn't read 0.00. */
    fun captionAmount(summary: TagSummary): Long = summary.netAmount
}

/** The budget's managed #tags: their totals, and add, edit, hide, delete and discovery from notes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TagsListView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tag?>(null) }
    var toDelete by remember { mutableStateOf<Tag?>(null) }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val summaries = store.tagSummaries.associateBy { it.tag.id }
    val tags = TagsListLogic.filtered(store.tags, searchText, showHidden)

    fun discover() = scope.launch {
        try {
            val found = store.discoverTags()
            discoveryMessage = if (found.isEmpty()) {
                L("No new tags found in transaction notes.")
            } else {
                L("Found and added %lld tags: %@", found.size.toLong(), found.joinToString(", ") { it.displayName })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.localizedMessage
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(L("Tags"), actions = {
                IconButton(onClick = { creating = true }, modifier = Modifier.testTag("tagsList.addButton")) { Icon(Icons.Filled.Add, contentDescription = L("Add Tag")) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("tagsList.menuButton")) { Icon(Icons.Filled.MoreVert, contentDescription = L("More")) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(L("Find Existing Tags")) }, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, onClick = {
                            menu = false
                            discover()
                        })
                        DropdownMenuItem(
                            text = { Text(L("Show Hidden Tags")) },
                            trailingIcon = { if (showHidden) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { showHidden = !showHidden },
                        )
                    }
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                store.tags.isEmpty() && !store.isLoading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(L("No Tags"), Icons.Filled.Tag, L("Tags let you flag and categorize transactions with #hashtags in notes."), Modifier.weight(1f))
                    Button(onClick = { creating = true }) { Text(L("Add Tag")) }
                    OutlinedButton(onClick = { discover() }, modifier = Modifier.padding(top = 8.dp, bottom = 48.dp)) { Text(L("Find Existing Tags")) }
                }
                else -> {
                    SearchField(searchText, { searchText = it }, L("Search tags"))
                    RefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                store.refreshTagSummaries()
                                refreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (tags.isEmpty()) {
                            EmptyState(L("No Results"), Icons.Filled.Search, L("Check the spelling or try a new search."))
                            return@RefreshBox
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(tags, key = { _, it -> it.id }) { index, tag ->
                                var rowMenu by remember { mutableStateOf(false) }
                                Box(Modifier.listTile(index, tags.size)) {
                                    TagRow(tag, summaries[tag.id], Modifier.combinedClickable(onLongClick = { rowMenu = true }) { nav.push(Route.TagTransactions(tag.id)) })
                                    DropdownMenu(rowMenu, onDismissRequest = { rowMenu = false }) {
                                        DropdownMenuItem(text = { Text(L("Edit Tag")) }, onClick = {
                                            rowMenu = false
                                            editing = tag
                                        })
                                        DropdownMenuItem(text = { Text(L("Delete Tag")) }, onClick = {
                                            rowMenu = false
                                            toDelete = tag
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) EditTagSheet(null) { creating = false }
    editing?.let { tag -> EditTagSheet(tag) { editing = null } }
    toDelete?.let { tag ->
        ConfirmationDialog(
            title = L("Delete this tag?"),
            confirmLabel = L("Delete Tag"),
            message = L("This removes the tag from your managed tags list. The hashtag will remain on existing transaction notes."),
            destructive = true,
            onDismiss = { toDelete = null },
            onConfirm = {
                scope.launch {
                    try {
                        store.deleteTag(tag.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage
                    }
                }
            },
        )
    }
    MessageAlert(L("Discovery"), discoveryMessage) { discoveryMessage = null }
    MessageAlert(L("Error"), errorMessage) { errorMessage = null }
}

@Composable
private fun TagRow(tag: Tag, summary: TagSummary?, modifier: Modifier) {
    val store = LocalBudgetStore.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}.testTag("tagRow.${tag.tag}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).background(tag.composeColor, CircleShape))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tag.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (tag.hidden) {
                    Text(
                        L("Hidden"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            tag.description?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        summary?.let {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    store.displaySpentCaption(TagsListLogic.captionAmount(it)),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = if (it.netAmount > 0) MaterialTheme.colorScheme.positive else Color.Unspecified,
                )
                Text(L("%lld txs", it.transactionCount.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Add, or edit [tag]: name, color, description, hidden, delete. ponytail:
 * colors come from the preset palette only — Android has no system color
 * picker; the upgrade is a hue picker writing the same hex.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTagSheet(tag: Tag?, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(tag?.tag.orEmpty()) }
    var selectedHex by remember { mutableStateOf(tag?.color) }
    var description by remember { mutableStateOf(tag?.description.orEmpty()) }
    var hidden by remember { mutableStateOf(tag?.hidden ?: false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val normalized = Tag.normalizeTagName(name)
    val previewColor = selectedHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary

    fun run(block: suspend () -> Unit) {
        saving = true
        errorMessage = null
        scope.launch {
            try {
                block()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
                saving = false
            }
        }
    }

    FormSheet(
        L(if (tag != null) "Edit Tag" else "Add Tag"),
        onDismiss = { if (!saving) onDismiss() },
        confirmEnabled = normalized.isNotEmpty() && !saving,
        onConfirm = {
            if (!Tag.isValidTagName(normalized)) {
                errorMessage = L("Tag names cannot contain spaces or the # symbol.")
                return@FormSheet
            }
            val desc = description.trim().ifEmpty { null }
            run {
                if (tag != null) {
                    if (tag.tag != normalized) store.renameTag(tag.id, tag.tag, normalized)
                    store.updateTag(tag.copy(tag = normalized, color = selectedHex, description = desc, hidden = hidden))
                } else {
                    store.createTag(normalized, selectedHex, desc)
                }
            }
        },
    ) {
        FormSection(footer = L("Tag names cannot contain spaces or the # symbol.")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                FormTextFieldRow(
                    name,
                    { name = it },
                    L("Tag Name"),
                    Modifier.testTag("editTag.name"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
            FormRow(L("Preview"), trailing = {
                Row(
                    Modifier.background(previewColor.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(10.dp).background(previewColor, CircleShape))
                    Text(if (normalized.isEmpty()) "#tag" else "#$normalized", style = MaterialTheme.typography.labelLarge)
                }
            })
        }
        FormSection(L("Color")) {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // No custom color: the accent.
                Box(
                    Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { selectedHex = null }
                        .semantics { contentDescription = L("Default Color") },
                    contentAlignment = Alignment.Center,
                ) { if (selectedHex == null) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                for (hex in Tag.presetColors) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(parseHexColor(hex) ?: Color.Gray, CircleShape)
                            .clickable { selectedHex = hex }
                            .semantics { contentDescription = hex },
                        contentAlignment = Alignment.Center,
                    ) { if (selectedHex.equals(hex, ignoreCase = true)) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        FormSection(L("Description")) {
            FormTextFieldRow(description, { description = it }, L("Description (optional)"), Modifier.testTag("editTag.description"))
        }
        if (tag != null) {
            FormSection(footer = L("Hidden tags stay on transactions but are hidden from suggestions and primary tag lists.")) {
                FormToggleRow(L("Hide Tag"), hidden, { hidden = it }, Modifier.testTag("editTag.hiddenToggle"))
            }
            FormSection { FormButtonRow(L("Delete Tag"), { confirmingDelete = true }, Modifier.testTag("editTag.deleteButton"), destructive = true, enabled = !saving) }
        }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
    if (confirmingDelete && tag != null) {
        ConfirmationDialog(
            title = L("Delete this tag?"),
            confirmLabel = L("Delete Tag"),
            message = L("This removes the tag from your managed tags list. The hashtag will remain on existing transaction notes."),
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = { run { store.deleteTag(tag.id) } },
        )
    }
}
