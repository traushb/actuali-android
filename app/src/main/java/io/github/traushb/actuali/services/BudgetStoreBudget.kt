package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.budget.AutomationEntry
import io.github.traushb.actuali.services.budget.AutomationSentences
import io.github.traushb.actuali.services.budget.BudgetAutomations
import io.github.traushb.actuali.services.budget.BudgetMonthMath
import io.github.traushb.actuali.services.budget.CleanupConfig
import io.github.traushb.actuali.services.budget.CleanupEngine
import io.github.traushb.actuali.services.budget.CleanupNotes
import io.github.traushb.actuali.services.budget.CleanupTemplate
import io.github.traushb.actuali.services.budget.GoalScheduleInfo
import io.github.traushb.actuali.services.budget.GoalTemplate
import io.github.traushb.actuali.services.budget.GoalTemplateCategory
import io.github.traushb.actuali.services.budget.GoalTemplateEngine
import io.github.traushb.actuali.services.budget.GoalTemplateNotes
import io.github.traushb.actuali.services.budget.GoalTemplateSheet
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.GoalTemplateCategoryRow
import io.github.traushb.actuali.services.database.fetchBudgetMonth
import io.github.traushb.actuali.services.database.fetchCleanupGroups
import io.github.traushb.actuali.services.database.fetchGoalTemplateCategories
import io.github.traushb.actuali.services.database.fetchGoalTemplateSheet
import io.github.traushb.actuali.services.database.fetchNote
import io.github.traushb.actuali.services.database.fetchRulesRanked
import io.github.traushb.actuali.services.database.fetchSchedules
import io.github.traushb.actuali.services.database.findCleanupGroupId
import io.github.traushb.actuali.services.database.resetGoalDefs
import io.github.traushb.actuali.services.database.rulesTableExists
import io.github.traushb.actuali.services.database.scheduleOwnedRuleIds
import io.github.traushb.actuali.services.database.tombstoneOrphanCleanupGroups
import io.github.traushb.actuali.services.rules.RuleFieldType
import io.github.traushb.actuali.services.rules.RuleSchema
import io.github.traushb.actuali.services.rules.RuleSummary
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.UUID

private val logger = Logger("BudgetStore")

// Budget

/**
 * Fetch one "yyyy-MM" month into [BudgetStore.currentBudgetMonth]. The Budget
 * tab owns the selected month; [BudgetStore.requestedBudgetMonth] mirrors the
 * latest request so an older in-flight fetch can't publish over a newer one.
 */
suspend fun BudgetStore.fetchBudgetMonth(month: String) {
    budgetMonthRequestGeneration += 1
    requestedBudgetMonth = month
    try {
        val fetched = database?.fetchBudgetMonth(month)
        // A newer month was requested while this one was fetching (rapid month flips): this result is stale.
        if (requestedBudgetMonth != month) return
        currentBudgetMonth = fetched
    } catch (e: CancellationException) {
        // The hosting screen's coroutine was cancelled (rapid month flips) — not an error.
    } catch (e: Exception) {
        if (requestedBudgetMonth != month) return
        error = e.localizedMessage
    }
}

// Budget Amounts

/**
 * Prior category-month rows used for Quick Assign suggestions. Reading
 * history never changes the displayed month or introduces new storage.
 */
suspend fun BudgetStore.budgetHistory(category: CategoryBudget, monthCount: Int = 3): List<CategoryBudget> {
    val database = database ?: return emptyList()
    val result = mutableListOf<CategoryBudget>()
    var month = category.month
    repeat(monthCount) {
        val previous = BudgetStore.shiftBudgetMonth(month, -1) ?: return result
        month = previous
        val budget = runCatching { database.fetchBudgetMonth(previous) }.getOrNull() ?: return@repeat
        budget.allCategoryBudgets.firstOrNull { it.categoryId == category.categoryId }?.let { result += it }
    }
    return result
}

/** Shift a "yyyy-MM" month key; also backs the Budget tab's month picker so the format logic lives in one place. */
fun BudgetStore.Companion.shiftBudgetMonth(month: String, offset: Int): String? {
    // Like the Swift Calendar this replaces, an out-of-range month ("2025-13") rolls over rather than failing.
    val parts = month.split("-").filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val year = parts[0].toIntOrNull() ?: return null
    val monthNumber = parts[1].toIntOrNull() ?: return null
    val shifted = runCatching { LocalDate.of(year, 1, 1).plusMonths(monthNumber - 1L + offset) }.getOrNull() ?: return null
    return "%04d-%02d".format(shifted.year, shifted.monthValue)
}

/**
 * Parse the budget edit field ("25.50") into cents. Negative amounts
 * (intentional overdraw, as Actual's web client allows) are only valid where
 * the caller opts in — a transfer must stay non-negative or it would silently
 * reverse direction.
 */
fun BudgetStore.Companion.budgetAmountCents(string: String, allowNegative: Boolean = false): Long {
    val cents = string.toDoubleOrNull()?.let(Transaction::cents) ?: throw BudgetStoreError.InvalidAmount
    if (!allowNegative && cents < 0) throw BudgetStoreError.InvalidAmount
    return cents
}

/** Set the budgeted amount for a category, then refetch the month so Available and carryover recompute. */
suspend fun BudgetStore.setBudgetAmount(month: String, categoryId: String, amountCents: Long) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setBudgetAmount(month, categoryId, amountCents)
    fetchBudgetMonth(month)
}

/** Copy the visible budgeted amounts from the previous month. Tracking budgets also budget income categories; envelope budgets do not. */
suspend fun BudgetStore.copyPreviousMonthBudget(month: String) {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured
    val previousMonth = BudgetStore.shiftBudgetMonth(month, -1) ?: return
    val previous = database.fetchBudgetMonth(previousMonth)
    val budgets = previous.categoryBudgets.map { GoalTemplateEngine.BudgetWrite(it.categoryId, it.budgeted) } +
        if (previous.isTrackingBudget) previous.incomeCategories.map { GoalTemplateEngine.BudgetWrite(it.categoryId, it.budgeted) } else emptyList()
    syncClient.applyGoalTemplateWrites(month, budgets, emptyList())
    fetchBudgetMonth(requestedBudgetMonth ?: month)
}

/** Match upstream `budget/set-zero`: clear every live category, hidden ones included, but leave income alone unless this is a tracking budget. */
suspend fun BudgetStore.setBudgetsToZero(month: String) {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured
    val budget = database.fetchBudgetMonth(month)
    val categoryIds = budget.allCategoryBudgets.map { it.categoryId } +
        if (budget.isTrackingBudget) budget.allIncomeCategories.map { it.categoryId } else emptyList()
    syncClient.applyGoalTemplateWrites(month, categoryIds.map { GoalTemplateEngine.BudgetWrite(it, 0) }, emptyList())
    fetchBudgetMonth(requestedBudgetMonth ?: month)
}

/**
 * Turn "rollover overspending" on or off for a category (GH #372), then
 * refetch the month. Mirrors the web's balance menu: the flag is written from
 * this month through the last month the web would have created, so both
 * clients agree on which rows carry it. [now] pins the range's end for tests.
 */
suspend fun BudgetStore.setBudgetCarryover(month: String, categoryId: String, enabled: Boolean, now: LocalDate = LocalDate.now()) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setBudgetCarryover(BudgetStore.carryoverMonths(month, now), listOf(categoryId), enabled)
    fetchBudgetMonth(month)
}

/**
 * The months upstream `setCategoryCarryover` flags: [month] through the
 * latest month in the web's sheet, which `createAllBudgets` extends to twelve
 * months past today (`getBudgetRange`). A month already beyond that is
 * flagged alone.
 */
fun BudgetStore.Companion.carryoverMonths(month: String, now: LocalDate = LocalDate.now()): List<String> {
    val latest = BudgetMonthMath.addMonths(BudgetMonthMath.currentMonth(now), 12)
    val count = maxOf(BudgetMonthMath.differenceInCalendarMonths(latest, month), 0)
    return (0..count).map { BudgetMonthMath.addMonths(month, it) }
}

/** Hold part or all of this envelope month's To Budget for next month. */
suspend fun BudgetStore.holdBudgetForNextMonth(month: String, amountCents: Long) {
    val budget = currentBudgetMonth?.takeIf { it.month == month } ?: throw BudgetStoreError.InvalidAmount
    val toBudget = budget.toBudget ?: throw BudgetStoreError.InvalidAmount
    if (amountCents <= 0) throw BudgetStoreError.InvalidAmount
    if (!BudgetStore.isValidHoldAmount(amountCents, toBudget)) throw BudgetStoreError.TransferAmountExceedsSource
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setBudgetBuffer(month, budget.buffered + amountCents)
    fetchBudgetMonth(month)
}

fun BudgetStore.Companion.isValidHoldAmount(amountCents: Long, toBudget: Long): Boolean = amountCents > 0 && toBudget > 0 && amountCents <= toBudget

suspend fun BudgetStore.resetBudgetBuffer(month: String) {
    if (currentBudgetMonth?.month != month) throw BudgetStoreError.InvalidAmount
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setBudgetBuffer(month, 0)
    fetchBudgetMonth(month)
}

suspend fun BudgetStore.disableAutomaticBudgetBuffer(month: String) {
    if (currentBudgetMonth?.month != month) throw BudgetStoreError.InvalidAmount
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.resetIncomeCarryover(month)
    fetchBudgetMonth(month)
}

/**
 * Move budgeted funds between categories (GH #128), null meaning the month's
 * "To Budget" pool on that side, then refetch the month so both categories'
 * Available figures recompute.
 */
suspend fun BudgetStore.transferBudget(month: String, fromCategoryId: String?, toCategoryId: String?, amountCents: Long) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    if (amountCents <= 0) throw BudgetStoreError.TransferAmountNotPositive
    // Also rejects To Budget on both sides (null == null) — a no-op request.
    if (fromCategoryId == toCategoryId) throw BudgetStoreError.TransferCategoriesMatch
    syncClient.transferBudget(month, fromCategoryId, toCategoryId, amountCents)
    fetchBudgetMonth(month)
}

// Goal Templates (budget goals, GH #371)

enum class GoalTemplateAction { Check, Apply, Overwrite }

sealed interface GoalTemplateOutcome {
    data class Applied(val count: Int) : GoalTemplateOutcome
    data object UpToDate : GoalTemplateOutcome
    data object CheckPassed : GoalTemplateOutcome
    data class Errors(val messages: List<String>) : GoalTemplateOutcome
    data class Failed(val message: String) : GoalTemplateOutcome
}

/** Flip the synced feature flag (the web's Settings → Experimental features toggle), so all clients agree on whether goals are on. */
suspend fun BudgetStore.setGoalTemplatesEnabled(enabled: Boolean) {
    val syncClient = syncClient ?: return
    try {
        syncClient.setPreference("flags.goalTemplatesEnabled", if (enabled) "true" else "false")
        goalTemplatesEnabled = enabled
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to update goal templates setting: %@", e.localizedMessage)
    }
}

private suspend fun BudgetDatabase.goalSchedules(): List<GoalScheduleInfo> = fetchSchedules()
    .filter { !it.name.isNullOrEmpty() }
    .map { GoalScheduleInfo(it.id, it.name, it.completed, it.amount, it.dateCondition) }

private fun GoalTemplateCategoryRow.asGoalCategory() = GoalTemplateCategory(id, name, isIncome)

/**
 * Check/apply/overwrite budget templates for a month — the port of upstream's
 * `budget/check-templates`, `budget/apply-goal-template` and
 * `budget/overwrite-goal-template` handlers. [categoryId] scopes the run to
 * one category (`budget/apply-single-category-template`), which always
 * overwrites, hidden or not — same as the web. [categoryIds] scopes a
 * month-style run (hidden categories skipped, apply vs overwrite honored) to
 * one group's categories.
 */
suspend fun BudgetStore.runGoalTemplates(
    month: String,
    action: GoalTemplateAction,
    categoryId: String? = null,
    categoryIds: Set<String>? = null,
): GoalTemplateOutcome {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) return GoalTemplateOutcome.Failed(BudgetStoreError.SyncNotConfigured.message)
    return try {
        val rows = database.fetchGoalTemplateCategories()
        val schedules = database.goalSchedules()

        // Notes → templates. UI-managed categories (web template editor) keep their stored goal_def untouched.
        val parsedNotes = HashMap<String, List<GoalTemplate>>()
        for (row in rows) {
            if (row.sourceIsUI) continue
            val note = row.note?.takeIf { GoalTemplateNotes.noteHasTemplates(it) } ?: continue
            GoalTemplateNotes.parseTemplates(note).takeIf { it.isNotEmpty() }?.let { parsedNotes[row.id] = it }
        }

        if (action == GoalTemplateAction.Check) return checkOutcome(rows, parsedNotes, schedules)

        val scope: (String) -> Boolean = when {
            categoryId != null -> { id -> id == categoryId }
            categoryIds != null -> { id -> id in categoryIds }
            else -> { _ -> true }
        }

        // Store the parsed notes into goal_def (upstream storeTemplates),
        // skipping unchanged categories to avoid CRDT churn — the end state is identical.
        val storeUpdates = rows.filter { scope(it.id) }.mapNotNull { row ->
            val templates = parsedNotes[row.id] ?: return@mapNotNull null
            val stored = row.goalDef?.let { GoalTemplate.decodeArray(it) }
            if (stored != templates) Triple(row.id, GoalTemplate.encodeArray(templates), "notes") else null
        }
        syncClient.storeGoalDefs(storeUpdates)

        // Orphaned defs: notes-managed categories whose notes lost their
        // templates (upstream resetCategoryGoalDefsWithNoTemplates).
        database.resetGoalDefs(rows.filter { scope(it.id) && !it.sourceIsUI && it.goalDef != null && it.id !in parsedNotes }.map { it.id })

        // Effective templates per category after the store above.
        var categoryTemplates: Map<String, List<GoalTemplate>> = HashMap(parsedNotes).apply {
            for (row in rows) {
                if (!row.sourceIsUI) continue
                row.goalDef?.let { GoalTemplate.decodeArray(it) }?.takeIf { it.isNotEmpty() }?.let { put(row.id, it) }
            }
        }.filterKeys(scope)

        // A loan whose target is snoozed contributes nothing this month:
        // YNAB's "skip a payment" without tearing the target down. A run for
        // one category is an explicit request, so it goes through.
        val snoozed = if (categoryId == null) snoozedLoanCategoryIds(month) else emptySet()
        if (snoozed.isNotEmpty()) categoryTemplates = categoryTemplates.filterKeys { it !in snoozed }

        val sheet = database.fetchGoalTemplateSheet(month)
        val processCategories = if (categoryId != null) {
            rows.filter { it.id == categoryId }.map { it.asGoalCategory() }
        } else {
            rows.filter { scope(it.id) && !it.hidden && !it.groupHidden && (sheet.isTracking || !it.isIncome) }.map { it.asGoalCategory() }
        }

        val result = GoalTemplateEngine.run(
            month = month,
            force = action == GoalTemplateAction.Overwrite || categoryId != null,
            categoryTemplates = categoryTemplates,
            categories = processCategories,
            allCategories = rows.map { it.asGoalCategory() },
            schedules = schedules,
            sheet = sheet,
        )
        when (result) {
            is GoalTemplateEngine.RunResult.Errors -> GoalTemplateOutcome.Errors(result.messages)
            is GoalTemplateEngine.RunResult.UpToDate -> {
                syncClient.applyGoalTemplateWrites(month, emptyList(), result.goalResets)
                if (result.goalResets.isNotEmpty()) fetchBudgetMonth(month)
                GoalTemplateOutcome.UpToDate
            }
            is GoalTemplateEngine.RunResult.Applied -> {
                syncClient.applyGoalTemplateWrites(month, result.budgets, result.goals)
                fetchBudgetMonth(month)
                GoalTemplateOutcome.Applied(result.count)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Goal template run failed: ${e.localizedMessage}")
        GoalTemplateOutcome.Failed(e.localizedMessage ?: e.toString())
    }
}

sealed interface CleanupOutcome {
    data class Completed(val notification: CleanupEngine.Notification) : CleanupOutcome
    data class Failed(val message: String) : CleanupOutcome
}

/**
 * Run Actual's end-of-month cleanup for one budget month. Notes-managed
 * definitions are refreshed first, then every budget change is written
 * through the same optimistic CRDT batch as goal templates.
 */
suspend fun BudgetStore.runCleanup(month: String): CleanupOutcome {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) return CleanupOutcome.Failed(BudgetStoreError.SyncNotConfigured.message)
    return try {
        val rows = database.fetchGoalTemplateCategories()
        val existingGroups = database.fetchCleanupGroups()
        val groupIdsByName = HashMap<String, String>()
        val groupNamesById = HashMap<String, String>()
        for ((id, name) in existingGroups) {
            groupIdsByName.putIfAbsent(name.lowercase(), id)
            groupNamesById.putIfAbsent(id, name)
        }
        val parsedByCategory = HashMap<String, List<CleanupNotes.ParsedRow>>()
        val neededGroupNames = HashMap<String, String>()
        for (row in rows) {
            if (row.sourceIsUI) continue
            val parsed = row.note?.let { CleanupNotes.parseRows(it) }.orEmpty()
            parsedByCategory[row.id] = parsed
            for (name in parsed.mapNotNull { it.groupName }) neededGroupNames.putIfAbsent(name.lowercase(), name)
        }

        for ((key, name) in neededGroupNames.toSortedMap()) {
            if (key in groupIdsByName) continue
            val id = resolveCleanupGroup(name)
            syncClient.upsertCleanupGroup(id, name)
            groupIdsByName[key] = id
            groupNamesById[id] = name
        }

        val cleanupByCategory = HashMap<String, List<CleanupTemplate>>()
        val updates = mutableListOf<Pair<String, String?>>()
        for (row in rows) {
            val cleanup = if (row.sourceIsUI) {
                row.cleanupDef?.let { CleanupTemplate.decodeArray(it) }.orEmpty()
            } else {
                val derived = CleanupNotes.toTemplates(parsedByCategory[row.id].orEmpty()) { groupIdsByName[it.lowercase()] }
                val stored = row.cleanupDef?.let { CleanupTemplate.decodeArray(it) }.orEmpty()
                if (stored != derived || (derived.isEmpty() && row.cleanupDef != null)) {
                    updates += row.id to derived.takeIf { it.isNotEmpty() }?.let { CleanupTemplate.encodeArray(it) }
                }
                derived
            }
            cleanupByCategory[row.id] = cleanup
        }
        syncClient.storeCleanupDefs(updates)
        database.tombstoneOrphanCleanupGroups()

        val result = CleanupEngine.run(
            month = month,
            categories = rows.map { CleanupEngine.Category(it.id, it.name, it.isIncome, cleanupByCategory[it.id].orEmpty()) },
            groupNames = groupNamesById,
            sheet = database.fetchGoalTemplateSheet(month),
        )
        syncClient.applyGoalTemplateWrites(month, result.budgets, result.goals, writeFalseLongGoalsAsZero = true)
        fetchBudgetMonth(month)
        CleanupOutcome.Completed(result.notification)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Cleanup run failed: ${e.localizedMessage}")
        CleanupOutcome.Failed(e.localizedMessage ?: e.toString())
    }
}

// Automation editor (goalTemplatesUIEnabled beta)

suspend fun BudgetStore.setGoalTemplatesUIEnabled(enabled: Boolean) {
    val syncClient = syncClient ?: return
    try {
        syncClient.setPreference("flags.goalTemplatesUIEnabled", if (enabled) "true" else "false")
        goalTemplatesUIEnabled = enabled
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to update automations setting: %@", e.localizedMessage)
    }
}

/** Everything the automations editor needs for one category — the port of BudgetAutomationsModal's load phase. */
data class AutomationEditorData(
    val categoryName: String = "",
    val entries: List<AutomationEntry> = emptyList(),
    val cleanup: CleanupConfig = CleanupConfig(),
    /** Notes-managed category: saving from the editor migrates it to UI management, with a warning shown first (web parity). */
    val needsMigration: Boolean = false,
    /** The note's original template/cleanup lines, for the warning box. */
    val originalNoteLines: String = "",
    /** Templates contain an error row the editor can't represent — the web refuses to open the editor in this state. */
    val hasUnsupportedTemplates: Boolean = false,
    val existingNote: String = "",
    val schedules: List<GoalScheduleInfo> = emptyList(),
    /** Percentage sources: income categories as (id, name). */
    val incomeSources: List<Pair<String, String>> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val cleanupGroups: List<Pair<String, String>> = emptyList(),
    /**
     * Dry-run inputs, fetched once at load: unsaved edits stay local to the
     * editor, so the sheet snapshot can't drift while it's open.
     */
    val category: GoalTemplateCategory = GoalTemplateCategory("", "", false),
    val allCategories: List<GoalTemplateCategory> = emptyList(),
    val sheet: GoalTemplateSheet = GoalTemplateSheet(),
)

suspend fun BudgetStore.loadAutomationEditor(categoryId: String, month: String): AutomationEditorData {
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    val rows = database.fetchGoalTemplateCategories()
    val row = rows.firstOrNull { it.id == categoryId } ?: throw BudgetStoreError.SyncNotConfigured

    val schedules = database.goalSchedules()
    val incomeSources = rows.filter { it.isIncome }.map { it.id to it.name }
    val cleanupGroups = database.fetchCleanupGroups().toMutableList()
    val needsMigration = !row.sourceIsUI

    var templates: List<GoalTemplate>
    val cleanup: List<CleanupTemplate>
    var originalNoteLines = ""
    if (needsMigration) {
        templates = row.note?.let { GoalTemplateNotes.parseTemplates(it) }.orEmpty()
        val parsedRows = row.note?.let { CleanupNotes.parseRows(it) }.orEmpty()
        // Resolve note-based pool names locally. New or tombstoned pools are
        // written only if the user saves the editor.
        val nameToId = HashMap<String, String>()
        for ((id, name) in cleanupGroups) nameToId.putIfAbsent(name.lowercase(), id)
        for (name in parsedRows.mapNotNull { it.groupName }.toSet()) {
            if (name.lowercase() in nameToId) continue
            val id = resolveCleanupGroup(name)
            nameToId[name.lowercase()] = id
            cleanupGroups += id to name
        }
        cleanup = CleanupNotes.toTemplates(parsedRows) { nameToId[it.lowercase()] }
        originalNoteLines = row.note.orEmpty().split("\n").filter {
            val trimmed = it.trim(' ', '\t')
            trimmed.startsWith("#template") || trimmed.startsWith("#goal") || trimmed.lowercase().startsWith("#cleanup")
        }.joinToString("\n")
    } else {
        templates = row.goalDef?.let { GoalTemplate.decodeArray(it) }.orEmpty()
        cleanup = row.cleanupDef?.let { CleanupTemplate.decodeArray(it) }.orEmpty()
    }

    val hasUnsupportedTemplates = templates.any { it.type == GoalTemplate.Kind.Error }

    // Text templates address income categories by name; the editor works with
    // ids (the web resolves the same way). Duplicate names are possible across
    // groups; the first match wins.
    val incomeNameToId = HashMap<String, String>()
    for ((id, name) in incomeSources) incomeNameToId.putIfAbsent(name.lowercase(), id)
    templates = templates.map { template ->
        val id = template.category?.takeIf { template.type == GoalTemplate.Kind.Percentage }?.let { incomeNameToId[it.lowercase()] }
        if (id != null) template.copy(category = id) else template
    }

    return AutomationEditorData(
        categoryName = row.name,
        entries = if (hasUnsupportedTemplates) emptyList() else BudgetAutomations.migrateToEntries(templates, schedules),
        cleanup = CleanupConfig.from(cleanup),
        needsMigration = needsMigration,
        originalNoteLines = originalNoteLines,
        hasUnsupportedTemplates = hasUnsupportedTemplates,
        existingNote = row.note.orEmpty(),
        schedules = schedules,
        incomeSources = incomeSources,
        categoryNames = rows.associate { it.id to it.name },
        cleanupGroups = cleanupGroups,
        category = row.asGoalCategory(),
        allCategories = rows.map { it.asGoalCategory() },
        sheet = database.fetchGoalTemplateSheet(month),
    )
}

/**
 * Projected budgeted amount and per-entry contributions — the editor's live
 * "Estimated monthly total" (upstream dry-run-category-template). Pure
 * computation over the load-time snapshot, so it can run on every edit.
 */
fun dryRunAutomations(month: String, data: AutomationEditorData, templates: List<GoalTemplate>): Pair<Long, List<Long>> =
    GoalTemplateEngine.dryRun(month, data.category, templates, data.allCategories, data.schedules, data.sheet)

/** Save the editor's automations as UI-managed (source 'ui'), which is also what completes a notes → UI migration. */
suspend fun BudgetStore.saveAutomations(categoryId: String, templates: List<GoalTemplate>, cleanup: List<CleanupTemplate>, cleanupGroups: List<Pair<String, String>>) {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured
    persistCleanupGroups(cleanup, cleanupGroups)
    syncClient.storeCategoryAutomations(
        categoryId,
        goalDef = templates.takeIf { it.isNotEmpty() }?.let { GoalTemplate.encodeArray(it) },
        cleanupDef = cleanup.takeIf { it.isNotEmpty() }?.let { CleanupTemplate.encodeArray(it) },
        source = "ui",
    )
    database.tombstoneOrphanCleanupGroups()
}

/** Resolve a cleanup pool for local editor state without writing it. */
suspend fun BudgetStore.resolveCleanupGroup(name: String): String {
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    return database.findCleanupGroupId(name.trim(' ', '\t')) ?: UUID.randomUUID().toString()
}

private suspend fun BudgetStore.persistCleanupGroups(cleanup: List<CleanupTemplate>, groups: List<Pair<String, String>>) {
    val database = database
    val syncClient = syncClient
    if (database == null || syncClient == null) throw BudgetStoreError.SyncNotConfigured
    val referenced = cleanup.mapNotNull { it.groupId }.toSet()
    val live = database.fetchCleanupGroups().mapTo(HashSet()) { it.first }
    for ((id, name) in groups) if (id in referenced && id !in live) syncClient.upsertCleanupGroup(id, name)
}

/** The un-migrate note preview: the existing note merged with the rendered `#template`/`#goal`/`#cleanup` lines the automations produce. */
fun renderUnmigrateNote(data: AutomationEditorData, templates: List<GoalTemplate>, cleanup: List<CleanupTemplate>): String {
    val rendered = listOf(
        AutomationSentences.renderNoteTemplates(templates) { data.categoryNames[it] },
        CleanupNotes.toNotes(cleanup) { id -> data.cleanupGroups.firstOrNull { it.first == id }?.second },
    ).filter { it.isNotEmpty() }.joinToString("\n")
    return AutomationSentences.mergeIntoNote(data.existingNote, rendered)
}

/**
 * Hand a UI-managed category back to notes: save the edited note, clear the
 * UI defs, and re-derive goal_def/cleanup_def from the note — the web's
 * "Save notes & un-migrate".
 */
suspend fun BudgetStore.unmigrateAutomations(categoryId: String, note: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setNote(categoryId, note)

    val templates = if (GoalTemplateNotes.noteHasTemplates(note)) GoalTemplateNotes.parseTemplates(note) else emptyList()
    val cleanupRows = CleanupNotes.parseRows(note)
    var cleanup = emptyList<CleanupTemplate>()
    if (cleanupRows.isNotEmpty()) {
        val nameToId = HashMap<String, String>()
        val cleanupGroups = mutableListOf<Pair<String, String>>()
        database?.fetchCleanupGroups()?.forEach { group ->
            nameToId[group.second.lowercase()] = group.first
            cleanupGroups += group
        }
        for (name in cleanupRows.mapNotNull { it.groupName }.toSet()) {
            if (name.lowercase() in nameToId) continue
            val id = resolveCleanupGroup(name)
            nameToId[name.lowercase()] = id
            cleanupGroups += id to name
        }
        cleanup = CleanupNotes.toTemplates(cleanupRows) { nameToId[it.lowercase()] }
        persistCleanupGroups(cleanup, cleanupGroups)
    }

    syncClient.storeCategoryAutomations(
        categoryId,
        goalDef = templates.takeIf { it.isNotEmpty() }?.let { GoalTemplate.encodeArray(it) },
        cleanupDef = cleanup.takeIf { it.isNotEmpty() }?.let { CleanupTemplate.encodeArray(it) },
        source = "notes",
    )
    database?.tombstoneOrphanCleanupGroups()
}

/** Port of upstream `checkTemplateNotes`: surface unparseable lines and schedule templates naming schedules that don't exist. */
private fun checkOutcome(rows: List<GoalTemplateCategoryRow>, parsedNotes: Map<String, List<GoalTemplate>>, schedules: List<GoalScheduleInfo>): GoalTemplateOutcome {
    val scheduleNames = schedules.mapNotNull { it.name }.toSet()
    val errors = mutableListOf<String>()
    for (row in rows) {
        for (template in parsedNotes[row.id] ?: continue) {
            val name = template.name
            if (template.type == GoalTemplate.Kind.Error) {
                val message = template.error
                errors += if (message != null && "adjustment" in message) {
                    "${row.name}: ${template.line.orEmpty()}\nError: $message"
                } else {
                    "${row.name}: ${template.line.orEmpty()}"
                }
            } else if (template.type == GoalTemplate.Kind.Schedule && name != null && name !in scheduleNames) {
                errors += "${row.name}: Schedule \"$name\" does not exist"
            }
        }
    }
    return if (errors.isEmpty()) GoalTemplateOutcome.CheckPassed else GoalTemplateOutcome.Errors(errors)
}

// Notes

/**
 * Load the note for a category (GH #131) or account (GH #198), keyed by that
 * row's id. Unsupported when no budget is open, the file has no `notes`
 * table, or the read fails — the note affordance hides itself in all three
 * cases rather than surfacing a banner over a secondary field.
 */
suspend fun BudgetStore.fetchNote(id: String): EntityNote {
    val database = database ?: return EntityNote.unsupported
    return try {
        database.fetchNote(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to read note: ${e.localizedMessage}")
        EntityNote.unsupported
    }
}

/**
 * Save a note, syncing back to Actual (GH #131, #198). An empty string clears
 * it. Throws so the caller can keep the editor open rather than silently
 * dropping what the user typed.
 */
suspend fun BudgetStore.saveNote(id: String, note: String) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.setNote(id, note)
}

// Rules

/** Live rules in engine order (GH #222), loaded on demand by the Rules screen rather than at budget load. */
suspend fun BudgetStore.loadRules() {
    val database = database ?: run {
        rules = emptyList()
        scheduleOwnedRuleIds = emptySet()
        rulesSupported = false
        return
    }
    try {
        rulesSupported = database.rulesTableExists()
        rules = if (rulesSupported) database.fetchRulesRanked() else emptyList()
        scheduleOwnedRuleIds = runCatching { database.scheduleOwnedRuleIds() }.getOrDefault(emptySet())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("loadRules failed: ${e.localizedMessage}")
        rules = emptyList()
        scheduleOwnedRuleIds = emptySet()
    }
}

/**
 * Create or update a rule. Validation mirrors upstream `rule-validate`: a
 * rule needs at least one condition and one action, and every condition must
 * use an operator its field supports.
 */
suspend fun BudgetStore.saveRule(rule: Rule) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    BudgetStore.validate(rule)
    syncClient.saveRule(rule)
    loadRules()
}

/** Delete a rule. Refuses when a schedule owns it, like upstream's `deleteRule`, rather than orphaning the schedule. */
suspend fun BudgetStore.deleteRule(rule: Rule) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    if (rule.id in scheduleOwnedRuleIds) throw BudgetStoreError.RuleOwnedBySchedule
    syncClient.deleteRule(rule)
    loadRules()
}

fun BudgetStore.Companion.validate(rule: Rule) {
    if (rule.conditions.isEmpty()) throw BudgetStoreError.RuleNeedsCondition
    if (rule.actions.isEmpty()) throw BudgetStoreError.RuleNeedsAction
    if (!rule.isSerializable) throw BudgetStoreError.RuleNotSerializable

    for (condition in rule.conditions) {
        if (!RuleSchema.isValidOp(condition.field, condition.op)) throw BudgetStoreError.RuleInvalidCondition(condition.field, condition.op)
        // Upstream's Condition constructor rejects empty values for
        // non-nullable types, and empty arrays for oneOf/notOneOf.
        when (condition.op) {
            "oneOf", "notOneOf" -> if (condition.value.listValue.isNullOrEmpty()) throw BudgetStoreError.RuleEmptyValue(condition.field)
            "onBudget", "offBudget" -> Unit
            // Upstream's parse asserts a `{num1, num2}` payload; anything else
            // makes `makeRule` return null and the whole rule vanish from the web client.
            "isbetween" -> if (condition.value.betweenValue == null) throw BudgetStoreError.RuleEmptyValue(condition.field)
            else -> {
                val type = RuleSchema.fieldType(condition.field)
                if ((type == RuleFieldType.Num || type == RuleFieldType.Date || type == RuleFieldType.Bool) && condition.value.isNull) {
                    throw BudgetStoreError.RuleEmptyValue(condition.field)
                }
                // A date condition needs a full YYYY-MM-DD for the comparison
                // ops; `is` also accepts a month or a year, matching upstream's parseDateString.
                if (type == RuleFieldType.Date) {
                    val digits = condition.value.stringValue.orEmpty().replace("-", "")
                    val allowed = if (condition.op == "is") setOf(4, 6, 8) else setOf(8)
                    if (digits.length !in allowed || digits.toIntOrNull() == null) throw BudgetStoreError.RuleEmptyValue(condition.field)
                }
                if (condition.op in setOf("contains", "doesNotContain", "matches", "hasTags", "hasAnyTag")) {
                    val text = condition.value.stringValue
                    if (text.isNullOrEmpty()) throw BudgetStoreError.RuleEmptyValue(condition.field)
                    // Upstream doesn't check this — a bad pattern just fails
                    // silently at apply time; here the user can still fix it.
                    // The length bound rules out pasted blobs: java.util.regex
                    // has no timeout, so a pathological pattern would wedge the
                    // sync dispatcher it runs on (the web app has the same exposure).
                    if (condition.op == "matches") {
                        if (text.length > 500) throw BudgetStoreError.RuleInvalidPattern(text)
                        runCatching { Regex(text.lowercase()) }.getOrNull() ?: throw BudgetStoreError.RuleInvalidPattern(text)
                    }
                }
            }
        }
    }

    for (action in rule.actions) {
        if (action.op != "set") continue
        val field = action.field?.takeIf { RuleSchema.fieldType(it) != null } ?: throw BudgetStoreError.RuleInvalidAction
        // Upstream: `account` may never be set to nothing.
        if (field == "account" && action.value.stringValue.isNullOrEmpty()) throw BudgetStoreError.RuleEmptyValue(field)
    }
}

/** Names for everything a rule summary might reference. */
val BudgetStore.ruleSummary: RuleSummary
    get() {
        fun <T> names(items: List<T>, id: (T) -> String, name: (T) -> String) = LinkedHashMap<String, String>().apply {
            for (item in items) putIfAbsent(id(item), name(item))
        }
        return RuleSummary(
            RuleSummary.Names(
                payees = names(payees, { it.id }, { it.name }),
                categories = names(categoryGroups.flatMap { it.categories }, { it.id }, { it.name }),
                categoryGroups = names(categoryGroups, { it.id }, { it.name }),
                accounts = names(accounts, { it.id }, { it.name }),
            ),
        ) { cents, locale -> formatCurrency(cents, locale) }
    }

// Loans: pairing

/**
 * Pair the loan with a budget category, or unpair it with null.
 * Read-modify-write on the stored config, so pairing from the account screen
 * can't overwrite terms edited elsewhere in the meantime.
 */
suspend fun BudgetStore.pairLoan(accountId: String, categoryId: String?) {
    val config = loanConfigs[accountId] ?: return
    setLoan(accountId, config.copy(categoryId = categoryId))
}

/**
 * The category paired with a loan, if it still exists and is visible. A
 * category deleted or hidden after pairing leaves the loan unpaired for
 * display rather than naming something the user can't see.
 */
fun BudgetStore.pairedLoanCategory(accountId: String): Category? {
    val categoryId = activeLoanConfig(accountId)?.categoryId ?: return null
    return categoryGroups.flatMap { it.categories }.firstOrNull { it.id == categoryId && !it.hidden }
}

// Loans: recording a payment

/**
 * Post a loan payment as real transactions, the way YNAB splits one: the
 * lender's interest charge and any escrow/fee charge as outflows on the loan
 * account, then the payment itself as a transfer from the funding account
 * carrying the paired category on its on-budget leg. The loan account nets to
 * the principal while the category sees the whole payment.
 *
 * [interest] and [escrow] are passed in rather than recomputed so the caller
 * can correct them to what the lender actually charged.
 */
suspend fun BudgetStore.recordLoanPayment(
    accountId: String,
    fromAccountId: String,
    payment: Long,
    interest: Long,
    escrow: Long,
    date: Int,
    notes: String?,
    cleared: Boolean = true,
) {
    // Read once up front: the charges below suspend, and a sync landing
    // mid-flight shouldn't move the payment to a different category than the
    // one the sheet showed.
    val categoryId = loanConfigs[accountId]?.categoryId
    // The editor only offers off-budget accounts, but an account can be moved
    // on budget later; there the charges would land uncategorized and the
    // transfer couldn't carry the category.
    if (accountId !in offBudgetAccountIds) throw BudgetStoreError.LoanAccountOnBudget
    // Everything the transfer could refuse is checked before the charges post,
    // so a refusal can't leave charges behind for a retry to double.
    transferPayees(fromAccountId, accountId, payment)

    // Charges first, payment last: the transfer's refresh then settles the published state.
    if (interest != 0L) postLoanCharge(accountId, -interest, date, LoanConfig.INTEREST_PAYEE_NAME, notes, cleared)
    if (escrow != 0L) postLoanCharge(accountId, -escrow, date, LoanConfig.ESCROW_PAYEE_NAME, notes, cleared)
    createTransfer(fromAccountId, accountId, payment, date, notes, cleared, categoryId)
}

/** One lender charge on the loan account. Off-budget, so it carries no category — `preserveCategory` keeps a rule from attaching one. */
private suspend fun BudgetStore.postLoanCharge(accountId: String, amount: Long, date: Int, payeeName: String, notes: String?, cleared: Boolean) {
    val payee = findOrCreatePayee(payeeName)
    createTransaction(
        Transaction(
            id = UUID.randomUUID().toString(), accountId = accountId, date = date, amount = amount, payeeId = payee.id, payeeName = payee.name,
            categoryId = null, categoryName = null, notes = notes, cleared = cleared, reconciled = false, transferId = null, isParent = false,
            parentId = null, tombstone = false, sortOrder = null, importedPayee = null,
        ),
        preserveCategory = true,
    )
}

// Loans: payment target

/**
 * What the paired category's automations would put aside this month — the
 * automations editor's own "Estimated monthly total", through the same dry
 * run. Deliberately a read: converting a notes-managed category to UI-managed
 * needs the editor's explicit Save behind a warning, and a second writer here
 * would bypass that consent. null means unpaired or no target set.
 */
suspend fun BudgetStore.loanTargetMonthly(accountId: String, month: String): Long? {
    val categoryId = activeLoanConfig(accountId)?.categoryId ?: return null
    val data = try {
        loadAutomationEditor(categoryId, month)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return null
    }
    val templates = data.entries.map { it.template }
    if (templates.isEmpty()) return null
    return dryRunAutomations(month, data, templates).first
}

/** Skip this loan's target for [month] (`YYYY-MM`), or clear the skip with null — YNAB's snooze, for the month you miss a payment. */
suspend fun BudgetStore.snoozeLoanTarget(accountId: String, month: String?) {
    val config = loanConfigs[accountId] ?: return
    setLoan(accountId, config.copy(targetSnoozedMonth = month))
}

/** Categories whose loan target is snoozed for [month], so a template run can pass over them. */
fun BudgetStore.snoozedLoanCategoryIds(month: String): Set<String> =
    activeLoanConfigs.values.mapNotNullTo(HashSet()) { config -> config.categoryId?.takeIf { config.targetIsSnoozed(month) } }
