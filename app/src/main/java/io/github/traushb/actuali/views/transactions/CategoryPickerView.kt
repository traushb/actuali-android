package io.github.traushb.actuali.views.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ListSectionHeader
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.listTile

/** Visible groups, narrowed to categories whose name (or group's name) matches [search]. */
fun filteredCategoryGroups(groups: List<CategoryGroup>, search: String): List<CategoryGroup> {
    val trimmed = search.trim()
    if (trimmed.isEmpty()) return groups.filter { !it.hidden }
    return groups.mapNotNull { group ->
        val matches = group.categories.filter { !it.hidden && (it.name.contains(trimmed, ignoreCase = true) || group.name.contains(trimmed, ignoreCase = true)) }
        if (matches.isEmpty()) null else group.copy(categories = matches)
    }
}

/**
 * Searchable category list, shared by the transaction form and the
 * uncategorized quick-categorize flow. [onPick] receives the choice (null for
 * None); the caller closes the picker.
 */
@Composable
fun CategoryPickerView(selectedCategoryId: String?, onPick: (String?) -> Unit, onClose: () -> Unit, autofocusSearch: Boolean = false) {
    val store = LocalBudgetStore.current
    var search by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autofocusSearch) LaunchedEffect(Unit) { focus.requestFocus() }
    val groups = filteredCategoryGroups(store.categoryGroups, search)

    Scaffold(topBar = { AppTopBar(L("Category"), onBack = onClose) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                search,
                { search = it },
                L("Search categories"),
                Modifier.focusRequester(focus).testTag("categoryPicker.search"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                clearTag = "categoryPicker.clearSearch",
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                if (search.isEmpty()) {
                    item(key = "none") { CategoryRow(L("None"), selectedCategoryId == null, Modifier.listTile(0, 1)) { onPick(null) } }
                }
                for (group in groups) {
                    item(key = "group-${group.id}") { ListSectionHeader(group.name) }
                    itemsIndexed(group.categories, key = { _, it -> it.id }) { index, category ->
                        CategoryRow(category.name, selectedCategoryId == category.id, Modifier.listTile(index, group.categories.size)) { onPick(category.id) }
                    }
                }
            }
        }
    }
}

/** One choice as a tile; the current one is tinted and checked, so it stands out at a glance. */
@Composable
private fun CategoryRow(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, fontWeight = if (selected) FontWeight.SemiBold else null) },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = L("Selected"), tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}
