package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.PendingImport
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.PendingImportApprover
import io.github.traushb.actuali.services.PendingImportStore
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.launch
import java.text.Collator

/** The pure parts of the card-to-account routing screen. */
object CardAccountMappings {
    data class Suggestion(val keyword: String, val count: Int, val samplePayee: String?)

    data class MappedAccount(val accountId: String, val accountName: String, val keywords: List<String>)

    fun groupByAccount(cardMappings: Map<String, String>, accounts: List<Account>, unknownName: String = L("Unknown Account")): List<MappedAccount> {
        val names = accounts.associate { it.id to it.name }
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return cardMappings.entries.groupBy({ it.value }, { it.key }).map { (accountId, keywords) ->
            MappedAccount(accountId, names[accountId] ?: unknownName, keywords.sortedWith(collator))
        }.sortedWith { lhs, rhs -> collator.compare(lhs.accountName, rhs.accountName) }
    }

    /** Card hints in pending transactions that don't route anywhere yet, through the same chain real routing uses. */
    fun suggestions(pendingImports: List<PendingImport>, activeBudgetId: String?, accounts: List<Account>, cardMappings: Map<String, String>): List<Suggestion> {
        val grouped = LinkedHashMap<String, Suggestion>()
        for (item in pendingImports) {
            if (item.originBudgetId != null && item.originBudgetId != activeBudgetId) continue
            val hint = item.cardHint?.trim().orEmpty()
            if (hint.isEmpty() || BudgetStore.resolveAccountId(hint, accounts, cardMappings) != null) continue
            val key = hint.lowercase()
            val existing = grouped[key]
            grouped[key] = Suggestion(existing?.keyword ?: hint, (existing?.count ?: 0) + 1, existing?.samplePayee ?: item.payee)
        }
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return grouped.values.sortedWith { lhs, rhs -> if (lhs.count != rhs.count) rhs.count - lhs.count else collator.compare(lhs.keyword, rhs.keyword) }
    }

    /** Keywords a save drops: originals no longer in the edited set. */
    fun keywordsRemovedBySave(originalKeywords: List<String>, cleanedKeywords: List<String>): List<String> = originalKeywords.filter { it !in cleanedKeywords.toSet() }
}

/** The values the add/edit sheet opens with. */
private data class CardMappingDraft(val accountId: String, val keywords: List<String>, val originalKeywords: List<String>) {
    val isEditing: Boolean get() = originalKeywords.isNotEmpty()
}

/** Card last-4 digits and bank keywords, and the account each routes shared messages to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardAccountMappingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<CardMappingDraft?>(null) }
    val mapped = CardAccountMappings.groupByAccount(store.cardAccountMappings, store.accounts)
    val suggestions = CardAccountMappings.suggestions(PendingImportStore.shared.imports, store.currentBudgetId, store.accounts, store.cardAccountMappings)

    fun add(keyword: String) {
        draft = CardMappingDraft(
            PendingImportApprover.seedAccountId(keyword.ifEmpty { null }, store.accounts, store.cardAccountMappings, store.defaultAccountId).orEmpty(),
            listOf(keyword),
            emptyList(),
        )
    }

    Scaffold(topBar = { AppTopBar(L("cardMappings.title")) }) { padding ->
        FormColumn(padding) {
            Text(L("cardMappings.explanation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            if (suggestions.isNotEmpty()) {
                FormSection(L("Suggestions"), L("Unmapped cards found in pending transactions. Tap to create a mapping.")) {
                    for (suggestion in suggestions) {
                        FormRow(
                            suggestion.keyword,
                            value = L("%lld pending", suggestion.count.toLong()),
                            supporting = suggestion.samplePayee?.takeIf { it.isNotEmpty() },
                            trailing = { Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { add(suggestion.keyword) },
                        )
                    }
                }
            }
            FormSection(L("cardMappings.title")) {
                if (mapped.isEmpty()) FormRow(L("cardMappings.empty"), enabled = false)
                for (item in mapped) {
                    Row(
                        Modifier.testTag("cardMappings.row.${item.keywords.firstOrNull() ?: item.accountId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            FormRow(
                                item.accountName,
                                onClick = { draft = CardMappingDraft(item.accountId, item.keywords.ifEmpty { listOf("") }, item.keywords) },
                            )
                            FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (keyword in item.keywords) {
                                    Text(
                                        keyword,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .testTag("cardMappings.badge.$keyword"),
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { scope.launch { store.deleteCardAccountMappings(item.keywords) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = L("Delete"), tint = MaterialTheme.colorScheme.negative)
                        }
                    }
                }
            }
            FormSection {
                FormRow(L("cardMappings.add"), leading = { Icon(Icons.Filled.Add, contentDescription = null) }, onClick = { add("") })
            }
        }
    }

    draft?.let { current -> CardMappingEditor(current) { draft = null } }
}

@Composable
private fun CardMappingEditor(draft: CardMappingDraft, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val keywords = remember { mutableStateListOf(*draft.keywords.toTypedArray()) }
    var selectedAccountId by remember { mutableStateOf(draft.accountId) }
    val cleaned = keywords.map { it.trim() }.filter { it.isNotEmpty() }
    val accountId = selectedAccountId.ifEmpty {
        PendingImportApprover.seedAccountId(null, store.accounts, store.cardAccountMappings, store.defaultAccountId)
            ?: store.accounts.firstOrNull { !it.closed }?.id.orEmpty()
    }
    val accounts = store.accounts.filter { !it.closed || it.id == accountId }

    FormSheet(
        L(if (draft.isEditing) "Edit Mapping" else "cardMappings.addTitle"),
        onDismiss = onDismiss,
        confirmEnabled = cleaned.isNotEmpty() && accountId.isNotEmpty(),
        onConfirm = {
            val removed = CardAccountMappings.keywordsRemovedBySave(draft.originalKeywords, cleaned)
            scope.launch { store.setCardAccountMappings(accountId, cleaned, removed) }
            onDismiss()
        },
    ) {
        FormSection(L("cardMappings.targetAccount")) {
            FormPickerRow(
                L("cardMappings.targetAccount"),
                accounts.map { it.id },
                accountId,
                { id -> accounts.firstOrNull { it.id == id }?.name.orEmpty() },
                { selectedAccountId = it },
                Modifier.testTag("cardMappings.accountPicker"),
            )
        }
        FormSection(L("cardMappings.keywordsSection"), L("cardMappings.footer")) {
            keywords.forEachIndexed { index, keyword ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormTextFieldRow(
                        keyword,
                        { keywords[index] = it },
                        L("cardMappings.keywordPrompt"),
                        Modifier.weight(1f).testTag(if (index == 0) "cardMappings.keywordField" else "cardMappings.keywordField.$index"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    if (keywords.size > 1) {
                        IconButton(onClick = { keywords.removeAt(index) }, modifier = Modifier.testTag("cardMappings.removeKeyword.$index")) {
                            Icon(Icons.Filled.RemoveCircle, contentDescription = L("cardMappings.removeKeyword"), tint = MaterialTheme.colorScheme.negative)
                        }
                    }
                }
            }
            FormRow(L("cardMappings.addKeyword"), Modifier.testTag("cardMappings.addKeywordButton"), leading = { Icon(Icons.Filled.Add, contentDescription = null) }, onClick = { keywords.add("") })
        }
    }
}
