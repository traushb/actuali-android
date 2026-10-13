package io.github.traushb.actuali.views.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.deleteRule
import io.github.traushb.actuali.services.loadRules
import io.github.traushb.actuali.services.ruleSummary
import io.github.traushb.actuali.services.rules.RuleSummary
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.SearchField
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.info
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

object RuleRowText {
    fun joiner(isAnd: Boolean, locale: Locale = Locale.getDefault()): String = ReportStrings.text(if (isAnd) "and" else "or", locale)

    /** Rules whose IF/THEN summary contains [query] (case-insensitive); all of them for a blank query. */
    fun filtered(rules: List<Rule>, query: String, summary: RuleSummary, locale: Locale = Locale.getDefault()): List<Rule> {
        val needle = query.trim().lowercase(locale)
        return if (needle.isEmpty()) rules else rules.filter { summary.searchText(it, locale).contains(needle) }
    }
}

/**
 * The rules Actual applies to incoming transactions (GH #222), as the web's
 * mobile rules page: a stage badge and an IF/THEN summary per rule, search
 * over that summary, and swipe to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesListView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        store.loadRules()
        loaded = true
    }
    val summary = store.ruleSummary
    val rules = RuleRowText.filtered(store.rules, searchText, summary)

    fun delete(rule: Rule) = scope.launch {
        try {
            store.deleteRule(rule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.localizedMessage
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(L("Rules"), actions = {
                if (store.rulesSupported) IconButton(onClick = { editing = Rule.empty() }) { Icon(Icons.Filled.Add, contentDescription = L("Add Rule")) }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !loaded -> DelayedLoadingIndicator(Modifier.fillMaxSize(), contained = true)
                !store.rulesSupported -> EmptyState(L("Rules Unavailable"), Icons.Filled.Tune, L("This budget file has no rules table. Open it in Actual once to add one."))
                store.rules.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(L("No Rules"), Icons.Filled.Tune, L("Rules rewrite transactions as they're added — renaming payees, setting categories, and more."), Modifier.weight(1f))
                    Button(onClick = { editing = Rule.empty() }, modifier = Modifier.padding(bottom = 48.dp)) { Text(L("Create Rule")) }
                }
                else -> {
                    SearchField(searchText, { searchText = it }, L("Search rules"))
                    RefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                store.loadRules()
                                refreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Searching past every rule shouldn't leave a blank screen.
                        if (rules.isEmpty()) {
                            EmptyState(L("No Results"), Icons.Filled.Search, L("Check the spelling or try a new search."))
                            return@RefreshBox
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(rules, key = { _, it -> it.id }) { index, rule ->
                                // A schedule's rule can't be deleted: the schedule owns it, as upstream's deleteRule.
                                val owned = rule.id in store.scheduleOwnedRuleIds
                                Box(Modifier.listTile(index, rules.size)) {
                                    if (owned) {
                                        RuleRow(rule, summary, owned, Modifier.clickable { editing = rule })
                                    } else {
                                        val state = rememberSwipeToDismissBoxState(confirmValueChange = {
                                            if (it == SwipeToDismissBoxValue.EndToStart) delete(rule)
                                            false
                                        })
                                        SwipeToDismissBox(
                                            state,
                                            enableDismissFromStartToEnd = false,
                                            backgroundContent = {
                                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.negative).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                                                    Icon(Icons.Filled.Delete, contentDescription = L("Delete"), tint = Color.White)
                                                }
                                            },
                                        ) { RuleRow(rule, summary, owned, Modifier.background(MaterialTheme.colorScheme.surface).clickable { editing = rule }) }
                                    }
                                }
                            }
                            item(key = "footer") {
                                Text(
                                    L("Rules run in stage order: Pre, then Default, then Post. Within a stage, the most specific rule runs last."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { rule -> RuleEditorView(rule) { editing = null } }
    MessageAlert(L("Couldn't Delete Rule"), failure) { failure = null }
}

/** One rule: stage badge, the conditions under IF, the actions under THEN. */
@Composable
private fun RuleRow(rule: Rule, summary: RuleSummary, isOwnedBySchedule: Boolean, modifier: Modifier) {
    val stageColor = when (rule.stage) {
        Rule.Stage.Pre -> MaterialTheme.colorScheme.info
        Rule.Stage.Default -> MaterialTheme.colorScheme.onSurfaceVariant
        Rule.Stage.Post -> MaterialTheme.colorScheme.warning
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).testTag("rulesList.row.${rule.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                rule.stage.label().uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = stageColor,
                modifier = Modifier.background(stageColor.copy(alpha = 0.18f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (isOwnedBySchedule) {
                Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(L("Schedule"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Labelled(L("IF"), rule.conditions.map { summary.condition(it) }, RuleRowText.joiner(rule.conditionsOp == Rule.ConditionsOp.And))
        Labelled(L("THEN"), rule.actions.map { summary.action(it) }, null)
    }
}

/** [joiner] prefixes every line but the first ("and"/"or"), as upstream's RuleRow renders it. */
@Composable
private fun Labelled(title: String, lines: List<String>, joiner: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEachIndexed { index, line ->
            Text(if (index > 0 && joiner != null) "$joiner $line" else line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
