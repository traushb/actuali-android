package io.github.traushb.actuali.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.models.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * Compares only stable transaction state returned by the normal fetch path.
 * Display-only values, insert-only values that aren't read back, and
 * `sortOrder` normalization must not make a live row differ from history.
 */
fun Transaction.matchesLiveTransaction(transaction: Transaction): Boolean =
    id == transaction.id &&
        accountId == transaction.accountId &&
        date == transaction.date &&
        amount == transaction.amount &&
        payeeId == transaction.payeeId &&
        categoryId == transaction.categoryId &&
        notes == transaction.notes &&
        cleared == transaction.cleared &&
        reconciled == transaction.reconciled &&
        transferId == transaction.transferId &&
        isParent == transaction.isParent &&
        parentId == transaction.parentId &&
        tombstone == transaction.tombstone &&
        importedPayee == transaction.importedPayee &&
        schedule == transaction.schedule

@Serializable
enum class HistoryActionKind {
    @SerialName("created") Created,

    @SerialName("edited") Edited,

    @SerialName("deleted") Deleted,
}

@Serializable
enum class HistoryActionStatus {
    @SerialName("applied") Applied,

    @SerialName("undone") Undone,
}

@Serializable
data class HistoryAction(
    val id: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val budgetID: String,
    val kind: HistoryActionKind,
    val before: List<Transaction>,
    val after: List<Transaction>,
    val status: HistoryActionStatus,
) {
    val primarySnapshot: Transaction? get() = after.firstOrNull { it.parentId == null } ?: after.firstOrNull() ?: before.firstOrNull()

    val title: String
        get() {
            if (after.size == 2 && after.all { it.transferId != null }) {
                return when (kind) {
                    HistoryActionKind.Created -> L("Created transfer")
                    HistoryActionKind.Edited -> L("Edited transfer")
                    HistoryActionKind.Deleted -> L("Deleted transfer")
                }
            }
            if (after.any { it.isParent } || before.any { it.isParent }) {
                return when (kind) {
                    HistoryActionKind.Created -> L("Added split transaction")
                    HistoryActionKind.Edited -> L("Edited split transaction")
                    HistoryActionKind.Deleted -> L("Deleted split transaction")
                }
            }
            val name = primarySnapshot?.payeeName?.takeIf { it.isNotEmpty() } ?: L("Transaction")
            return when (kind) {
                HistoryActionKind.Created -> L("Added %@", name)
                HistoryActionKind.Edited -> L("Edited %@", name)
                HistoryActionKind.Deleted -> L("Deleted %@", name)
            }
        }

    val detail: String get() = primarySnapshot?.let { Transaction.formattedDate(it.date) }.orEmpty()
}

/** The last ten transaction actions per budget, with an undo for the newest still-applied one. Main-thread only. */
class HistoryStore(private val defaults: Defaults) {
    class PendingUndo(val budgetID: String, val expected: List<Transaction>, val removedIDs: Set<String>)

    var actions: List<HistoryAction> by mutableStateOf(emptyList())
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var errorTitle: String by mutableStateOf(L("Couldn't Undo"))
        private set

    private var loadedBudgetID: String? = null

    fun load(budgetID: String) {
        loadedBudgetID = budgetID
        val json = defaults.string(key(budgetID))
        if (json == null) {
            clearLoadedActions()
            loadedBudgetID = budgetID
            return
        }
        val decoded = runCatching { AppJson.decodeFromString(ListSerializer(HistoryAction.serializer()), json) }.getOrNull()
        if (decoded == null) {
            actions = emptyList()
            errorTitle = L("Couldn't Load History")
            errorMessage = L("History couldn't be loaded. New history will continue from here.")
            return
        }
        clearError()
        actions = decoded.filter { it.budgetID == budgetID }.sortedByDescending { it.createdAt }
    }

    fun clearLoadedActions() {
        actions = emptyList()
        loadedBudgetID = null
        clearError()
    }

    /**
     * Drop a budget's persisted actions. They live in preferences keyed by
     * budget id, so they survive the budget's files being recreated from
     * scratch (demo reseed); clear them or the fresh budget opens with history
     * recorded against the previous copy.
     */
    fun clearPersistedActions(budgetID: String) {
        defaults.remove(key(budgetID))
        if (loadedBudgetID != budgetID) return
        actions = emptyList()
        clearError()
    }

    fun recordSnapshots(budgetID: String, kind: HistoryActionKind, before: List<Transaction>, after: List<Transaction>, now: Long = System.currentTimeMillis()) {
        if (recordingSuppressed || (before.isEmpty() && after.isEmpty())) return
        // ponytail: bulk writes (locking an account's cleared rows) diff as one
        // action over every row they touch. Undo restores one row per sync write
        // and actions persist in preferences, so past the old page size the
        // action isn't recorded. Batch restores would lift the cap.
        if (maxOf(before.size, after.size) > MAX_SNAPSHOTS_PER_ACTION) return

        if (loadedBudgetID != budgetID) load(budgetID)

        val existing = actions.firstOrNull()?.takeIf { it.status == HistoryActionStatus.Applied && it.budgetID == budgetID }
        // ponytail: split edits publish parent/child changes separately; only
        // merge adjacent publications for the same parent within 0.5s. The
        // ceiling is intentional — an operation-scoped History transaction
        // can remove the timing heuristic without changing stored transactions.
        val existingParentID = existing?.let { splitParentID(it.before, it.after) }
        if (kind == HistoryActionKind.Edited && existing != null && existingParentID != null &&
            existingParentID == splitParentID(before, after) && now - existing.createdAt <= 500
        ) {
            actions = listOf(existing.copy(kind = kind, before = mergeBefore(existing.before, before), after = mergeAfter(existing.after, after))) + actions.drop(1)
            save(budgetID)
            return
        }

        // The second leg of a transfer lands as its own publication; fold it into the first.
        val transaction = after.singleOrNull()
        val existingTransaction = existing?.takeIf { it.kind == kind }?.after?.singleOrNull()
        if (transaction != null && existingTransaction != null && existingTransaction.id == transaction.transferId && existingTransaction.transferId == transaction.id) {
            actions = listOf(existing.copy(before = existing.before + before, after = existing.after + after)) + actions.drop(1)
            save(budgetID)
            return
        }

        clearError()
        actions = (listOf(HistoryAction(UUID.randomUUID().toString(), now, budgetID, kind, before, after, HistoryActionStatus.Applied)) + actions).take(10)
        save(budgetID)
    }

    fun canUndo(action: HistoryAction): Boolean =
        action.budgetID == loadedBudgetID && action.status == HistoryActionStatus.Applied &&
            actions.firstOrNull { it.status == HistoryActionStatus.Applied }?.id == action.id

    fun clearError() {
        errorMessage = null
        errorTitle = L("Couldn't Undo")
    }

    suspend fun undo(action: HistoryAction, budgetStore: BudgetStore) {
        if (!canUndo(action) || action.budgetID != budgetStore.currentBudgetId) return
        clearError()
        val changedMessage = L("This action changed after it was recorded, so it cannot be safely undone.")

        // Check against every live row: a row missing from the newest page in
        // `budgetStore.transactions` is not deleted.
        val snapshot = try {
            budgetStore.fetchAllLiveTransactions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.localizedMessage
            return
        }
        val live = LinkedHashMap<String, Transaction>().apply {
            for (transaction in snapshot.transactions + snapshot.splitChildren) putIfAbsent(transaction.id, transaction)
        }

        val afterByID = action.after.associateBy { it.id }
        for (recordedAfter in action.after) {
            val actual = live[recordedAfter.id]
            val intact = if (recordedAfter.tombstone) actual == null else actual != null && recordedAfter.matchesLiveTransaction(actual)
            if (!intact) {
                errorMessage = changedMessage
                return
            }
        }
        for (expected in action.before) {
            val recordedAfter = afterByID[expected.id]
            val actual = live[expected.id]
            val intact = when {
                recordedAfter == null || recordedAfter.tombstone -> actual == null
                else -> actual != null && recordedAfter.matchesLiveTransaction(actual)
            }
            if (!intact) {
                errorMessage = changedMessage
                return
            }
        }

        pendingUndo = when (action.kind) {
            HistoryActionKind.Created -> PendingUndo(action.budgetID, emptyList(), action.after.mapTo(HashSet()) { it.id })
            HistoryActionKind.Edited, HistoryActionKind.Deleted -> PendingUndo(action.budgetID, action.before, emptySet())
        }
        recordingSuppressed = true

        when (action.kind) {
            HistoryActionKind.Created -> {
                budgetStore.error = null
                budgetStore.deleteTransactions(action.after.filter { it.parentId == null })
                budgetStore.error?.let {
                    errorMessage = it
                    finishUndoRecording()
                    return
                }
            }
            HistoryActionKind.Edited, HistoryActionKind.Deleted -> {
                val restorePairs = action.before.map { previous -> previous to (afterByID[previous.id] ?: previous.copy(tombstone = true)) }
                // ponytail: restore one row per sync write so each update carries
                // only its own changed fields. Grouping rows would union their
                // field sets and can overwrite concurrent CRDT edits on untouched rows.
                val restoredPairs = mutableListOf<Pair<Transaction, Transaction>>()
                try {
                    for ((before, after) in restorePairs) {
                        budgetStore.restoreTransaction(before, after)
                        restoredPairs += before to after
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Compensate on failure so a multi-row Undo doesn't remain partially restored.
                    errorMessage = try {
                        for ((before, after) in restoredPairs.asReversed()) budgetStore.restoreTransaction(after, before)
                        e.localizedMessage
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        L("Undo failed and the previous state could not be restored. Please reopen the budget and verify these transactions.")
                    }
                    finishUndoRecording()
                    return
                }
            }
        }

        if (actions.none { it.id == action.id }) {
            finishUndoRecording()
            return
        }
        actions = actions.map { if (it.id == action.id) it.copy(status = HistoryActionStatus.Undone) else it }
        save(action.budgetID)
        yield()
        finishUndoRecording()
    }

    private fun key(budgetID: String) = "history.actions.$budgetID"

    private fun save(budgetID: String) {
        if (loadedBudgetID != budgetID) return
        defaults.set(key(budgetID), AppJson.encodeToString(ListSerializer(HistoryAction.serializer()), actions))
    }

    companion object {
        val shared: HistoryStore by lazy { HistoryStore(Defaults.standard) }

        var recordingSuppressed = false
        var pendingUndo: PendingUndo? = null
        const val MAX_SNAPSHOTS_PER_ACTION = 500

        fun finishUndoRecording() {
            recordingSuppressed = false
            pendingUndo = null
        }

        private fun splitParentID(before: List<Transaction>, after: List<Transaction>): String? =
            after.firstOrNull { it.isParent }?.id
                ?: before.firstOrNull { it.isParent }?.id
                ?: after.firstNotNullOfOrNull { it.parentId }
                ?: before.firstNotNullOfOrNull { it.parentId }

        private fun mergeBefore(existing: List<Transaction>, newer: List<Transaction>): List<Transaction> {
            val existingIDs = existing.mapTo(HashSet()) { it.id }
            return existing + newer.filter { it.id !in existingIDs }
        }

        private fun mergeAfter(existing: List<Transaction>, newer: List<Transaction>): List<Transaction> {
            val result = existing.toMutableList()
            for (transaction in newer) {
                val index = result.indexOfFirst { it.id == transaction.id }
                if (index >= 0) result[index] = transaction else result += transaction
            }
            return result
        }
    }
}
