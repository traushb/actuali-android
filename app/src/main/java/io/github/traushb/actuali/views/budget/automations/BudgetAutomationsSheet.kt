package io.github.traushb.actuali.views.budget.automations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.AutomationEditorData
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.budget.AutomationDisplayType
import io.github.traushb.actuali.services.budget.AutomationEntry
import io.github.traushb.actuali.services.budget.AutomationSentences
import io.github.traushb.actuali.services.budget.AutomationValidation
import io.github.traushb.actuali.services.budget.BudgetAutomations
import io.github.traushb.actuali.services.budget.BudgetMonthMath
import io.github.traushb.actuali.services.budget.CleanupConfig
import io.github.traushb.actuali.services.budget.GoalTemplate
import io.github.traushb.actuali.services.dryRunAutomations
import io.github.traushb.actuali.services.loadAutomationEditor
import io.github.traushb.actuali.services.renderUnmigrateNote
import io.github.traushb.actuali.services.resolveCleanupGroup
import io.github.traushb.actuali.services.saveAutomations
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.unmigrateAutomations
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormStepperRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

val AutomationDisplayType.icon: ImageVector
    get() = when (this) {
        AutomationDisplayType.Fixed -> Icons.Filled.AccountBalance
        AutomationDisplayType.Schedule -> Icons.Filled.CalendarMonth
        AutomationDisplayType.By -> Icons.Filled.TrackChanges
        AutomationDisplayType.Percentage -> Icons.Filled.Percent
        AutomationDisplayType.Historical -> Icons.Filled.History
        AutomationDisplayType.Limit -> Icons.Outlined.RemoveCircleOutline
        AutomationDisplayType.Refill -> Icons.Filled.Autorenew
        AutomationDisplayType.Remainder -> Icons.Filled.GridView
        AutomationDisplayType.Goal -> Icons.Filled.Flag
    }

/** Which sub-screen of the sheet is up. */
private sealed interface AutomationScreen {
    data class Entry(val id: String) : AutomationScreen
    data object Cleanup : AutomationScreen
    data object Unmigrate : AutomationScreen
}

/**
 * The visual budget-automations editor (GH #371 follow-up), the web's
 * BudgetAutomationsModal beta: the category's automations with live projected
 * contributions, per-type editors, options (balance cap, long-term goal,
 * end-of-month cleanup), notes → UI migration and back.
 */
@Composable
fun BudgetAutomationsSheet(categoryId: String, month: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AutomationEditorData?>(null) }
    var entries by remember { mutableStateOf<List<AutomationEntry>>(emptyList()) }
    var cleanup by remember { mutableStateOf(CleanupConfig()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<AutomationScreen?>(null) }
    var totalMonthly by remember { mutableStateOf(0L) }
    var contributions by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(categoryId) {
        try {
            val loaded = store.loadAutomationEditor(categoryId, month)
            data = loaded
            entries = loaded.entries
            cleanup = loaded.cleanup
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.localizedMessage
        }
    }
    val templates = entries.map { it.template }
    // Debounced like the web's 200 ms dry run.
    LaunchedEffect(templates, data) {
        val loaded = data ?: return@LaunchedEffect
        delay(200)
        val (budgeted, perTemplate) = dryRunAutomations(month, loaded, templates)
        totalMonthly = budgeted
        contributions = entries.zip(perTemplate).associate { (entry, amount) -> entry.id to amount }
    }

    val validSources = buildSet {
        add("all income")
        add("available funds")
        data?.incomeSources?.forEach { (id, name) ->
            add(id)
            add(name.lowercase())
        }
    }
    fun error(entry: AutomationEntry) = AutomationValidation.validate(entry, templates, data?.schedules.orEmpty(), BudgetMonthMath.currentMonth(), validSources)
    val conflicts = listOfNotNull(AutomationValidation.percentageAllocationConflict(templates), AutomationValidation.schedulePriorityConflict(templates))
    val hasErrors = entries.any { error(it) != null }

    fun addOption(type: AutomationDisplayType) {
        if (entries.any { it.displayType == type }) return
        val entry = AutomationEntry(BudgetAutomations.defaultTemplate(type), type)
        entries = entries + entry
        screen = AutomationScreen.Entry(entry.id)
    }

    when (val current = screen) {
        is AutomationScreen.Entry -> {
            val entry = entries.firstOrNull { it.id == current.id }
            if (entry != null) {
                BackHandler { screen = null }
                AutomationEntryEditor(
                    entry,
                    { updated -> entries = entries.map { if (it.id == updated.id) updated else it } },
                    error(entry),
                    data ?: AutomationEditorData(),
                    entries,
                    onAddLimit = { addOption(AutomationDisplayType.Limit) },
                    onDelete = {
                        entries = entries.filter { it.id != current.id }
                        screen = null
                    },
                    onBack = { screen = null },
                )
                return
            }
        }
        AutomationScreen.Cleanup -> {
            BackHandler { screen = null }
            CleanupEditor(
                cleanup,
                { cleanup = it },
                data?.cleanupGroups.orEmpty(),
                onCreateGroup = { name ->
                    val id = store.resolveCleanupGroup(name)
                    data = data?.let { loaded -> if (loaded.cleanupGroups.any { it.first == id }) loaded else loaded.copy(cleanupGroups = loaded.cleanupGroups + (id to name)) }
                    id
                },
                onDelete = {
                    cleanup = CleanupConfig()
                    screen = null
                },
                onBack = { screen = null },
            )
            return
        }
        AutomationScreen.Unmigrate -> data?.let { loaded ->
            BackHandler { screen = null }
            UnmigrateAutomationsScreen(categoryId, renderUnmigrateNote(loaded, templates, cleanup.toCleanupTemplates()), onBack = { screen = null }, onDone = onDismiss)
            return
        }
        null -> Unit
    }

    Scaffold(
        topBar = {
            AppTopBar(L("Automations"), onBack = onDismiss, actions = {
                TextButton(
                    enabled = data != null && data?.hasUnsupportedTemplates != true && !hasErrors && conflicts.isEmpty() && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                store.saveAutomations(categoryId, templates, cleanup.toCleanupTemplates(), data?.cleanupGroups.orEmpty())
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                loadError = e.localizedMessage
                                saving = false
                            }
                        }
                    },
                ) { Text(L("Save")) }
            })
        },
    ) { padding ->
        val loaded = data
        when {
            loadError != null -> EmptyState(L("Couldn't Load Automations"), Icons.Filled.WarningAmber, loadError, Modifier.padding(padding))
            loaded == null -> DelayedLoadingIndicator(Modifier.padding(padding).fillMaxSize())
            loaded.hasUnsupportedTemplates -> Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState(
                    L("Unsupported Templates"),
                    Icons.Filled.WarningAmber,
                    L("This category has template lines the visual editor can't represent. Fix or remove them in the category note first."),
                    Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onDismiss) { Text(L("Close")) }
            }
            else -> FormColumn(padding) {
                if (loaded.needsMigration) {
                    FormSection {
                        FormRow(L("Imported from notes-based templates. Review and Save to complete the migration."), leading = { Icon(Icons.Outlined.Info, contentDescription = null) })
                        if (loaded.originalNoteLines.isNotEmpty()) {
                            var showingOriginal by remember { mutableStateOf(false) }
                            FormRow(L("Show original templates"), onClick = { showingOriginal = !showingOriginal })
                            if (showingOriginal) Text(loaded.originalNoteLines, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                for (conflict in conflicts) {
                    FormSection { FormRow(conflict.message(), leading = { Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.negative) }) }
                }
                val contributionEntries = entries.filter { it.displayType !in AutomationDisplayType.nonContribution }
                FormSection(
                    L("Contributions"),
                    footer = if (contributionEntries.isNotEmpty()) L("Estimated monthly total: %@", store.displayBalance(totalMonthly)) else null,
                ) {
                    contributionEntries.forEach { entry ->
                        EntryRow(entry, error(entry)?.shortMessage(), contributions[entry.id]?.let(store::displayBalance), loaded) { screen = AutomationScreen.Entry(entry.id) }
                    }
                    FormButtonRow(L("Add Automation"), {
                        val template = BudgetAutomations.defaultTemplate(AutomationDisplayType.Fixed).copy(priority = BudgetAutomations.DEFAULT_PRIORITY)
                        val entry = AutomationEntry(template, AutomationDisplayType.Fixed)
                        entries = entries + entry
                        screen = AutomationScreen.Entry(entry.id)
                    })
                }
                FormSection(L("Options")) {
                    entries.firstOrNull { it.displayType == AutomationDisplayType.Limit }?.let { entry ->
                        EntryRow(entry, error(entry)?.shortMessage(), null, loaded) { screen = AutomationScreen.Entry(entry.id) }
                    } ?: FormButtonRow(L("Add Balance Cap"), { addOption(AutomationDisplayType.Limit) })
                    entries.firstOrNull { it.displayType == AutomationDisplayType.Goal }?.let { entry ->
                        EntryRow(entry, error(entry)?.shortMessage(), null, loaded) { screen = AutomationScreen.Entry(entry.id) }
                    } ?: FormButtonRow(L("Add Long-Term Goal"), { addOption(AutomationDisplayType.Goal) })
                    if (cleanup.isConfigured) {
                        val global = cleanup.global.send || cleanup.global.take
                        val scopes = (if (global) 1 else 0) + cleanup.groups.count { it.send || it.take }
                        val summary = when {
                            scopes > 1 -> L("Active in %lld scopes", scopes.toLong())
                            global -> L("Active globally")
                            else -> L("Active in a pool")
                        }
                        AutomationRow(Icons.Filled.Loop, L("End of month cleanup"), summary, null, isError = false) { screen = AutomationScreen.Cleanup }
                    } else {
                        FormButtonRow(L("Add End of Month Cleanup"), {
                            cleanup = CleanupConfig()
                            screen = AutomationScreen.Cleanup
                        })
                    }
                }
                if (!loaded.needsMigration) {
                    FormSection(footer = L("Renders these automations as #template lines into the category note and hands control back to notes-based templates.")) {
                        FormButtonRow(L("Switch Back to Notes Templates"), { screen = AutomationScreen.Unmigrate })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: AutomationEntry, errorText: String?, trailing: String?, data: AutomationEditorData, onClick: () -> Unit) {
    val store = LocalBudgetStore.current
    val sentence = errorText ?: AutomationSentences.sentence(entry.template, { store.displayBalance(BudgetMonthMath.amountToInteger(it)) }, { data.categoryNames[it] })
    AutomationRow(entry.displayType.icon, entry.displayType.label(), sentence, trailing, errorText != null, onClick)
}

@Composable
private fun AutomationRow(icon: ImageVector, title: String, subtitle: String, trailing: String?, isError: Boolean, onClick: () -> Unit) {
    val tint = if (isError) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.primary
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium, color = if (isError) MaterialTheme.colorScheme.negative else Color.Unspecified) },
        supportingContent = { Text(subtitle, maxLines = 2, color = if (isError) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * One automation's editor: type picker, the type's configuration, priority,
 * note and delete — the web's AutomationEditorPane.
 */
@Composable
private fun AutomationEntryEditor(
    entry: AutomationEntry,
    onChange: (AutomationEntry) -> Unit,
    error: io.github.traushb.actuali.services.budget.AutomationError?,
    data: AutomationEditorData,
    entries: List<AutomationEntry>,
    onAddLimit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val template = entry.template
    fun update(transform: (GoalTemplate) -> GoalTemplate) = onChange(entry.copy(template = transform(template)))
    val isContribution = entry.displayType !in AutomationDisplayType.nonContribution
    // Singleton types already used by another entry can't be picked.
    val disabledTypes = entries.filter { it.id != entry.id && it.displayType in AutomationDisplayType.singleton }.mapTo(HashSet()) { it.displayType }

    Scaffold(topBar = { AppTopBar(entry.displayType.label(), onBack = onBack) }) { padding ->
        FormColumn(padding) {
            error?.let {
                FormSection {
                    FormRow(it.title(), supporting = it.detail(), leading = { Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.negative) })
                }
            }
            if (isContribution) {
                FormSection(L("Automation Type")) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutomationDisplayType.entries.filter { it !in AutomationDisplayType.nonContribution }.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (type in pair) {
                                    val isActive = type == entry.displayType
                                    val isDisabled = !isActive && type in disabledTypes
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.weight(1f).alpha(if (isDisabled) 0.45f else 1f).clickable(enabled = !isDisabled) { onChange(BudgetAutomations.convert(entry, type)) },
                                    ) {
                                        Column(Modifier.padding(8.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(type.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Text(type.label(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            }
                                            Text(type.explanation(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                        }
                                    }
                                }
                                if (pair.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                FormSection { FormRow(entry.displayType.explanation()) }
            }

            ConfigurationForm(entry, ::update, data, entries, onAddLimit)

            template.priority?.let { priority ->
                FormSection(footer = L("Lower priorities budget first; priorities above 0 stop once available funds run out.")) {
                    FormStepperRow(L("Priority"), priority, 0..99, { value -> update { it.copy(priority = max(0, value)) } })
                }
            }
            FormSection(L("Note")) {
                TextField(
                    template.description.orEmpty(),
                    { text -> update { it.copy(description = text.ifEmpty { null }) } },
                    placeholder = { Text(L("Note")) },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FormSection { FormButtonRow(L("Delete Automation"), onDelete, destructive = true) }
        }
    }
}

@Composable
private fun ConfigurationForm(entry: AutomationEntry, update: ((GoalTemplate) -> GoalTemplate) -> Unit, data: AutomationEditorData, entries: List<AutomationEntry>, onAddLimit: () -> Unit) {
    val template = entry.template
    when (entry.displayType) {
        AutomationDisplayType.Fixed -> FormSection(L("Configuration")) {
            AutomationAmountField(L("Amount"), template.amount) { value -> update { it.copy(amount = value) } }
            FormStepperRow(L("Every"), template.period?.amount ?: 1, 1..999, { value ->
                update { it.copy(period = GoalTemplate.Period(it.period?.period ?: GoalTemplate.PeriodUnit.Month, max(1, value))) }
            })
            FormPickerRow(
                L("Period"),
                GoalTemplate.PeriodUnit.entries,
                template.period?.period ?: GoalTemplate.PeriodUnit.Month,
                {
                    when (it) {
                        GoalTemplate.PeriodUnit.Day -> L("Days")
                        GoalTemplate.PeriodUnit.Week -> L("Weeks")
                        GoalTemplate.PeriodUnit.Month -> L("Months")
                        GoalTemplate.PeriodUnit.Year -> L("Years")
                    }
                },
                { unit -> update { it.copy(period = GoalTemplate.Period(unit, it.period?.amount ?: 1)) } },
            )
        }
        AutomationDisplayType.Schedule -> {
            val selectable = data.schedules.filter { !it.completed && !it.name.isNullOrEmpty() }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
            FormSection(L("Configuration")) {
                if (selectable.isEmpty()) {
                    FormRow(L("No schedules found. Create one in the Schedules tab first."))
                } else {
                    val selectedId = template.scheduleId ?: selectable.firstOrNull { it.name == template.name }?.id ?: ""
                    FormPickerRow(L("Schedule"), listOf("") + selectable.map { it.id }, selectedId, { id -> selectable.firstOrNull { it.id == id }?.name ?: L("Select a schedule") }, { id ->
                        selectable.firstOrNull { it.id == id }?.let { schedule -> update { it.copy(scheduleId = schedule.id, name = schedule.name.orEmpty()) } }
                    })
                    FormPickerRow(
                        L("Savings mode"),
                        listOf(false, true),
                        template.full == true,
                        { if (it) L("Cover each occurrence when it occurs") else L("Save up for the next occurrence") },
                        { full -> update { it.copy(full = if (full) true else null) } },
                    )
                    AutomationAdjustmentFields(template.adjustment, template.adjustmentType) { adjustment, type -> update { it.copy(adjustment = adjustment, adjustmentType = type) } }
                }
            }
        }
        AutomationDisplayType.By -> FormSection(
            L("Configuration"),
            footer = L("Without early spending, purchases before the target month leave a gap the next contribution has to make up. Turn it on to tell Actual spending is expected from a chosen month onwards."),
        ) {
            AutomationAmountField(L("Total amount"), template.amount) { value -> update { it.copy(amount = value) } }
            YearMonthPicker(L("Target month"), template.month ?: BudgetMonthMath.currentMonth()) { month -> update { it.copy(month = month) } }
            FormToggleRow(L("Repeats"), template.annual != null, { repeats ->
                update { if (repeats) it.copy(annual = false, repeatCount = it.repeatCount ?: 1) else it.copy(annual = null, repeatCount = null) }
            })
            if (template.annual != null) {
                FormStepperRow(L("Repeat every"), template.repeatCount ?: 1, 1..99, { value -> update { it.copy(repeatCount = max(1, value)) } })
                FormPickerRow(L("Period"), listOf(false, true), template.annual == true, { if (it) L("Years") else L("Months") }, { annual -> update { it.copy(annual = annual) } })
            }
            FormToggleRow(L("Allow early spending"), template.type == GoalTemplate.Kind.Spend, { enabled -> update { BudgetAutomations.setEarlySpending(it, enabled) } })
            if (template.type == GoalTemplate.Kind.Spend) {
                YearMonthPicker(L("Start spending in"), template.from ?: template.month ?: BudgetMonthMath.currentMonth()) { month -> update { it.copy(from = month) } }
            }
        }
        AutomationDisplayType.Percentage -> FormSection(L("Configuration")) {
            val options = buildList {
                if (template.category.isNullOrEmpty()) add("")
                add("all income")
                // Available funds has no "last month" figure.
                if (template.previous != true) add("available funds")
                data.incomeSources.forEach { add(it.first) }
            }
            FormPickerRow(
                L("Category"),
                options,
                template.category.orEmpty(),
                { option ->
                    when (option) {
                        "" -> L("Select a category")
                        "all income" -> L("Total of all income")
                        "available funds" -> L("Available funds to budget")
                        else -> data.incomeSources.firstOrNull { it.first == option }?.second ?: option
                    }
                },
                { option -> update { it.copy(category = option.ifEmpty { null }) } },
            )
            DecimalRow(L("Percentage"), template.percent ?: 0.0, "%") { value -> update { it.copy(percent = value) } }
            FormPickerRow(L("Percentage of"), listOf(false, true), template.previous == true, { if (it) L("Last month") else L("This month") }, { previous ->
                update { if (previous && it.category == "available funds") it.copy(previous = true, category = null) else it.copy(previous = previous) }
            })
        }
        AutomationDisplayType.Historical -> FormSection(L("Configuration")) {
            val copyMode = template.type == GoalTemplate.Kind.Copy
            FormPickerRow(L("Mode"), listOf(true, false), copyMode, { if (it) L("Copy a previous month") else L("Average of previous months") }, { copy -> update { BudgetAutomations.setHistoricalMode(it, copy) } })
            val monthsBack = if (copyMode) template.lookBack ?: 1 else template.numMonths ?: 1
            FormStepperRow(L("Number of months back"), monthsBack, 1..99, { value ->
                update { if (it.type == GoalTemplate.Kind.Copy) it.copy(lookBack = max(1, value)) else it.copy(numMonths = max(1, value)) }
            })
            if (template.type == GoalTemplate.Kind.Average) {
                AutomationAdjustmentFields(template.adjustment, template.adjustmentType) { adjustment, type -> update { it.copy(adjustment = adjustment, adjustmentType = type) } }
            }
        }
        AutomationDisplayType.Limit -> {
            val limit = template.limit ?: GoalTemplate.Limit(500.0, false, GoalTemplate.LimitPeriod.Monthly, null)
            // Limit-type templates mirror the amount at the top level.
            fun setLimit(updated: GoalTemplate.Limit) = update { it.copy(limit = updated, amount = updated.amount) }
            // Earliest fixed-automation start or save-by target, else the first of this month — the web's getDefaultWeeklyStart.
            val defaultWeeklyStart = entries.mapNotNull { other ->
                when (other.template.type) {
                    GoalTemplate.Kind.Periodic -> other.template.starting
                    GoalTemplate.Kind.By, GoalTemplate.Kind.Spend -> other.template.month?.let { "$it-01" }
                    else -> null
                }
            }.minOrNull() ?: BudgetMonthMath.firstDayOfMonth(BudgetMonthMath.currentMonth())
            FormSection(L("Configuration"), footer = L("A weekly or daily cap is multiplied by the number of weeks or days in the month, so the effective monthly cap changes with each month.")) {
                AutomationAmountField(L("Amount"), limit.amount) { value -> setLimit(limit.copy(amount = value ?: 0.0)) }
                FormPickerRow(
                    L("Every"),
                    GoalTemplate.LimitPeriod.entries,
                    limit.period,
                    {
                        when (it) {
                            GoalTemplate.LimitPeriod.Daily -> L("Day")
                            GoalTemplate.LimitPeriod.Weekly -> L("Week")
                            GoalTemplate.LimitPeriod.Monthly -> L("Month")
                        }
                    },
                    { period -> setLimit(limit.copy(period = period, start = if (period == GoalTemplate.LimitPeriod.Weekly && limit.start == null) defaultWeeklyStart else limit.start)) },
                )
                if (limit.period == GoalTemplate.LimitPeriod.Weekly) {
                    // Weekday of the weekly start date (0 = Sunday), moved within its week on change — the web's setDay.
                    val start = DayDate.fromIso(limit.start ?: defaultWeeklyStart)
                    FormPickerRow(
                        L("Weekday"),
                        (0 until 7).toList(),
                        (start?.weekday ?: 1) - 1,
                        { index -> DayOfWeek.of(if (index == 0) 7 else index).getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                        { index -> start?.let { day -> setLimit(limit.copy(start = day.addingDays(index + 1 - day.weekday).iso)) } },
                    )
                }
                FormToggleRow(L("Retain existing funds over the cap"), limit.hold, { setLimit(limit.copy(hold = it)) })
            }
        }
        AutomationDisplayType.Refill -> FormSection {
            FormRow(L("Each month, this tops the category back up to the balance cap."))
            if (entries.none { it.displayType == AutomationDisplayType.Limit }) FormButtonRow(L("Add Balance Cap"), onAddLimit)
        }
        AutomationDisplayType.Remainder -> FormSection(L("Configuration"), footer = L("Categories splitting the remainder receive it proportionally to their weights.")) {
            FormStepperRow(L("Weight"), (template.weight ?: 1.0).toInt(), 1..99, { value -> update { it.copy(weight = max(1, value).toDouble()) } }, valueText = AutomationSentences.trimTrailingZeros(template.weight ?: 1.0))
        }
        AutomationDisplayType.Goal -> FormSection(L("Configuration")) {
            AutomationAmountField(L("Target amount"), template.amount) { value -> update { it.copy(amount = value) } }
        }
    }
}

/** A whole-unit amount the template stores as a Double. */
@Composable
private fun AutomationAmountField(title: String, amount: Double?, onChange: (Double?) -> Unit) = DecimalRow(title, amount ?: 0.0, null) { onChange(it) }

/** A decimal number typed into a short trailing field, with an optional unit. */
@Composable
private fun DecimalRow(title: String, value: Double, unit: String?, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(AutomationSentences.trimTrailingZeros(value)) }
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text,
                    { typed ->
                        text = typed
                        typed.replace(",", ".").toDoubleOrNull()?.let(onChange)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                unit?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Increase/decrease adjustment on schedule and average automations. */
@Composable
private fun AutomationAdjustmentFields(adjustment: Double?, adjustmentType: GoalTemplate.AdjustmentType?, onChange: (Double?, GoalTemplate.AdjustmentType?) -> Unit) {
    // "none", "increase", "decrease".
    val direction = when {
        adjustment == null -> 0
        adjustment < 0 -> 2
        else -> 1
    }
    FormPickerRow(L("Adjustment"), listOf(0, 1, 2), direction, {
        when (it) {
            0 -> L("None")
            1 -> L("Increase")
            else -> L("Decrease")
        }
    }, { selected ->
        when (selected) {
            0 -> onChange(null, null)
            1 -> onChange(abs(adjustment ?: 5.0), adjustmentType ?: GoalTemplate.AdjustmentType.Percent)
            else -> onChange(-abs(adjustment ?: 5.0), adjustmentType ?: GoalTemplate.AdjustmentType.Percent)
        }
    })
    if (adjustment != null) {
        DecimalRow(L("Adjust by"), abs(adjustment), null) { value -> onChange((if (direction == 2) -1 else 1) * abs(value), adjustmentType) }
        FormPickerRow(L("Unit"), GoalTemplate.AdjustmentType.entries, adjustmentType ?: GoalTemplate.AdjustmentType.Percent, {
            if (it == GoalTemplate.AdjustmentType.Percent) L("Percent") else L("Fixed amount")
        }, { onChange(adjustment, it) })
    }
}

/** A "YYYY-MM" month as paired month and year pickers. */
@Composable
private fun YearMonthPicker(title: String, month: String, onChange: (String) -> Unit) {
    val (year, monthNumber) = BudgetMonthMath.yearAndMonth(month) ?: (2026 to 1)
    val current = BudgetMonthMath.yearAndMonth(BudgetMonthMath.currentMonth())?.first ?: 2026
    val years = (minOf(current - 10, year)..maxOf(current + 15, year)).toList()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f).padding(start = 16.dp))
        FormPickerRow(L("Month"), (1..12).toList(), monthNumber, { LocaleFormatting.monthName(it, Locale.getDefault(), TextStyle.SHORT) }, { onChange("%04d-%02d".format(year, it)) }, Modifier.weight(1f))
        FormPickerRow(L("Year"), years, year, { it.toString() }, { onChange("%04d-%02d".format(it, monthNumber)) }, Modifier.weight(1f))
    }
}

/**
 * End-of-month cleanup — the web's CleanupAutomation: the category can give
 * up leftovers (source) and/or receive them (sink), globally and per pool.
 */
@Composable
private fun CleanupEditor(
    cleanup: CleanupConfig,
    onChange: (CleanupConfig) -> Unit,
    groups: List<Pair<String, String>>,
    onCreateGroup: suspend (String) -> String,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var newPool by remember { mutableStateOf<String?>(null) }
    var poolError by remember { mutableStateOf<String?>(null) }
    fun groupName(id: String) = groups.firstOrNull { it.first == id }?.second ?: L("Unknown pool")

    Scaffold(topBar = { AppTopBar(L("End of Month Cleanup"), onBack = onBack) }) { padding ->
        FormColumn(padding) {
            FormSection { FormRow(L("At the end of the month, sources give up their leftover balance and sinks receive it, split by weight. Pools scope the exchange to their members.")) }
            FormSection(L("Globally")) {
                FormToggleRow(L("Give up leftover funds"), cleanup.global.send, { onChange(cleanup.copy(global = cleanup.global.copy(send = it))) })
                FormToggleRow(L("Receive shared funds"), cleanup.global.take, { onChange(cleanup.copy(global = cleanup.global.copy(take = it))) })
                if (cleanup.global.take) {
                    FormStepperRow(L("Weight"), cleanup.global.weight.toInt(), 1..99, { onChange(cleanup.copy(global = cleanup.global.copy(weight = max(1, it).toDouble()))) }, valueText = AutomationSentences.trimTrailingZeros(cleanup.global.weight))
                }
            }
            for (group in cleanup.groups) {
                fun set(updated: CleanupConfig.Group) = onChange(cleanup.copy(groups = cleanup.groups.map { if (it.groupId == group.groupId) updated else it }))
                FormSection(groupName(group.groupId)) {
                    FormToggleRow(L("Give up leftover funds"), group.send, { set(group.copy(send = it)) })
                    FormToggleRow(L("Receive shared funds"), group.take, { set(group.copy(take = it)) })
                    if (group.take) {
                        FormToggleRow(L("Only enough to cover any overspending"), group.overspendOnly, { set(group.copy(overspendOnly = it)) })
                        if (!group.overspendOnly) {
                            FormStepperRow(L("Weight"), group.weight.toInt(), 1..99, { set(group.copy(weight = max(1, it).toDouble())) }, valueText = AutomationSentences.trimTrailingZeros(group.weight))
                        }
                    }
                    FormButtonRow(L("Remove Pool"), { onChange(cleanup.copy(groups = cleanup.groups.filter { it.groupId != group.groupId })) }, destructive = true)
                }
            }
            FormSection(footer = poolError) {
                Box {
                    FormButtonRow(L("Add to a Pool"), { addMenu = true })
                    DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                        for ((id, name) in groups.filter { group -> cleanup.groups.none { it.groupId == group.first } }) {
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                addMenu = false
                                onChange(cleanup.copy(groups = cleanup.groups + CleanupConfig.Group(id)))
                            })
                        }
                        DropdownMenuItem(text = { Text(L("New Pool…")) }, onClick = {
                            addMenu = false
                            newPool = ""
                        })
                    }
                }
            }
            FormSection { FormButtonRow(L("Remove End of Month Cleanup"), onDelete, destructive = true) }
        }
    }

    newPool?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newPool = null },
            title = { Text(L("New Pool")) },
            text = {
                Column {
                    Text(L("Pools scope the cleanup exchange to their member categories."))
                    OutlinedTextField(name, { newPool = it }, placeholder = { Text(L("Pool name")) }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton({
                    val trimmed = name.trim()
                    newPool = null
                    if (trimmed.isEmpty()) return@TextButton
                    scope.launch {
                        try {
                            val id = onCreateGroup(trimmed)
                            if (cleanup.groups.none { it.groupId == id }) onChange(cleanup.copy(groups = cleanup.groups + CleanupConfig.Group(id)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            poolError = e.localizedMessage
                        }
                    }
                }) { Text(L("Create")) }
            },
            dismissButton = { TextButton({ newPool = null }) { Text(L("Cancel")) } },
        )
    }
}

/** Hand a UI-managed category back to notes: review the merged note, then save and un-migrate. */
@Composable
private fun UnmigrateAutomationsScreen(categoryId: String, initialNote: String, onBack: () -> Unit, onDone: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(initialNote) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            AppTopBar(L("Un-migrate Automations"), onBack = onBack, actions = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            store.unmigrateAutomations(categoryId, note)
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage
                            saving = false
                        }
                    }
                }) { Text(L("Save & Un-migrate")) }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("We have merged your existing automations with the notes for this category. Please review and edit as needed."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(note, { note = it }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.negative, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
