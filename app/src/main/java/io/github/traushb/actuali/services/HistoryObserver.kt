package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Turns the store's transaction publications into History actions by diffing
 * every live row against the previous publication. The store calls the
 * `*Changed`/`*Published` hooks synchronously from its property setters (the
 * iOS observer subscribes to the `@Published` projections), so each
 * publication is classified with the bank-sync flag as it was at that moment.
 * Main-thread only.
 */
class HistoryObserver(private val store: BudgetStore) {
    private var previousBudgetID: String? = store.currentBudgetId
    private var hasBaseline = false
    private var previous: MutableMap<String, Transaction> = HashMap()
    private var previousSplitChildren: MutableMap<String, Map<String, Transaction>> = HashMap()
    private var previousMessageID = 0L
    private var consumeTask: Job? = null
    private var baselineGeneration = 0

    init {
        store.historyObserver = this
        enqueueConsume(store.currentBudgetId, isRemote = false)
    }

    internal fun budgetIdChanged(budgetID: String?) {
        if (budgetID != previousBudgetID) {
            previousBudgetID = budgetID
            resetBaseline()
            return
        }
        enqueueConsume(budgetID, store.isBankSyncing)
    }

    internal fun loadingChanged(isLoading: Boolean) {
        if (shouldResetBaselineForReload(isLoading)) resetBaseline()
    }

    /** `transactions` is only the trigger: it holds just the newest page, so [consume] diffs every live row from the database instead. */
    internal fun transactionsPublished() = enqueueConsume(store.currentBudgetId, store.isBankSyncing)

    /** Test-only: wait until every queued publication has been diffed. */
    suspend fun drainForTesting() {
        consumeTask?.join()
    }

    private fun resetBaseline() {
        hasBaseline = false
        previous = HashMap()
        previousSplitChildren = HashMap()
        baselineGeneration += 1
    }

    private fun enqueueConsume(budgetID: String?, isRemote: Boolean) {
        val generation = baselineGeneration
        val previousTask = consumeTask
        consumeTask = store.scope.launch {
            previousTask?.join()
            consume(budgetID, isRemote, generation)
        }
    }

    private suspend fun consume(budgetID: String?, isRemote: Boolean, generation: Int) {
        if (generation != baselineGeneration) return
        if (budgetID == null) {
            hasBaseline = false
            previous = HashMap()
            previousSplitChildren = HashMap()
            return
        }
        if (budgetID != store.currentBudgetId) return

        // ponytail: reads every live row on each publication. Fine for personal
        // budgets; recording at the write call sites removes the read if huge
        // budgets make it slow.
        val snapshot = try {
            store.fetchAllLiveTransactions(if (hasBaseline) previousMessageID else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (generation != baselineGeneration || budgetID != store.currentBudgetId) return
        previousMessageID = snapshot.messageID

        val current = snapshot.transactions.associateByTo(HashMap()) { it.id }
        val currentSplitChildren = HashMap<String, MutableMap<String, Transaction>>()
        for (parent in current.values) if (parent.isParent) currentSplitChildren[parent.id] = HashMap()
        for (child in snapshot.splitChildren) {
            val parentID = child.parentId ?: continue
            currentSplitChildren[parentID]?.put(child.id, child)
        }

        if (!hasBaseline) {
            adopt(current, currentSplitChildren, budgetID)
            hasBaseline = true
            return
        }

        HistoryStore.pendingUndo?.let { pendingUndo ->
            previous = current
            previousSplitChildren = HashMap(currentSplitChildren)
            if (pendingUndo.budgetID == budgetID && matchesPendingUndo(pendingUndo, current, currentSplitChildren)) {
                HistoryStore.finishUndoRecording()
            }
            return
        }

        if (isRemote || HistoryStore.recordingSuppressed) {
            previous = current
            previousSplitChildren = HashMap(currentSplitChildren)
            return
        }

        // `isRemote` is captured at publication, but this read happens later
        // and may include a sync that landed in between. Rows another device
        // wrote since the baseline are adopted, so only local edits diff.
        val splitParentOf = HashMap<String, String?>()
        for ((parentID, children) in previousSplitChildren) for (childID in children.keys) splitParentOf[childID] = parentID
        for (child in snapshot.splitChildren) splitParentOf[child.id] = child.parentId
        for (id in snapshot.remoteRowIDs) {
            val rootID = splitParentOf[id] ?: id
            current[rootID]?.let { previous[rootID] = it } ?: previous.remove(rootID)
            currentSplitChildren[rootID]?.let { previousSplitChildren[rootID] = it } ?: previousSplitChildren.remove(rootID)
        }

        val added = current.values.filter { it.id !in previous }
        val removed = previous.values.filter { it.id !in current }
        val changed = current.values.filter { new -> previous[new.id]?.let { !it.matchesLiveTransaction(new) } ?: false }

        val splitParentIDs = (previous.values.filter { it.isParent }.map { it.id } + current.values.filter { it.isParent }.map { it.id }).toSortedSet()
        val handledRootIDs = HashSet<String>()
        val history = HistoryStore.shared
        for (parentID in splitParentIDs) {
            val oldRoot = previous[parentID]
            val newRoot = current[parentID]
            val oldChildren = previousSplitChildren[parentID].orEmpty()
            val newChildren = currentSplitChildren[parentID].orEmpty()

            val rootChanged = if (oldRoot != null && newRoot != null) !oldRoot.matchesLiveTransaction(newRoot) else oldRoot != null || newRoot != null
            if (!rootChanged && samePersistedState(oldChildren, newChildren)) continue
            handledRootIDs += parentID

            when {
                oldRoot == null && newRoot != null ->
                    history.recordSnapshots(budgetID, HistoryActionKind.Created, emptyList(), listOf(newRoot) + newChildren.values.sortedWith(entryOrder))
                oldRoot != null && newRoot == null -> {
                    val before = listOf(oldRoot) + oldChildren.values.sortedWith(entryOrder)
                    history.recordSnapshots(budgetID, HistoryActionKind.Deleted, before, before.map { it.copy(tombstone = true) })
                }
                oldRoot != null && newRoot != null -> {
                    val before = mutableListOf(oldRoot)
                    val after = mutableListOf(newRoot)
                    for (childID in (oldChildren.keys + newChildren.keys).sorted()) {
                        val oldChild = oldChildren[childID]
                        val newChild = newChildren[childID]
                        before += oldChild ?: newChild!!.copy(tombstone = true)
                        after += newChild ?: oldChild!!.copy(tombstone = true)
                    }
                    history.recordSnapshots(budgetID, HistoryActionKind.Edited, before, after)
                }
            }
        }

        val remainingAdded = added.filter { it.id !in handledRootIDs }
        val remainingRemoved = removed.filter { it.id !in handledRootIDs }
        val remainingChanged = changed.filter { it.id !in handledRootIDs }

        when {
            remainingAdded.isNotEmpty() && remainingRemoved.isEmpty() && remainingChanged.isEmpty() ->
                history.recordSnapshots(budgetID, HistoryActionKind.Created, emptyList(), remainingAdded)
            remainingAdded.isEmpty() && remainingRemoved.isNotEmpty() && remainingChanged.isEmpty() ->
                history.recordSnapshots(budgetID, HistoryActionKind.Deleted, remainingRemoved, remainingRemoved.map { it.copy(tombstone = true) })
            remainingAdded.isEmpty() && remainingRemoved.isEmpty() && remainingChanged.isNotEmpty() ->
                history.recordSnapshots(budgetID, HistoryActionKind.Edited, remainingChanged.mapNotNull { previous[it.id] }, remainingChanged)
            remainingAdded.isNotEmpty() || remainingRemoved.isNotEmpty() || remainingChanged.isNotEmpty() -> {
                val before = mutableListOf<Transaction>()
                val after = mutableListOf<Transaction>()
                val ids = (remainingAdded.map { it.id } + remainingRemoved.map { it.id } + remainingChanged.map { it.id }).toSortedSet()
                for (id in ids) {
                    val old = previous[id]
                    val new = current[id]
                    before += old ?: new!!.copy(tombstone = true)
                    after += new ?: old!!.copy(tombstone = true)
                }
                history.recordSnapshots(budgetID, HistoryActionKind.Edited, before, after)
            }
        }

        adopt(current, currentSplitChildren, budgetID)
    }

    private fun adopt(current: MutableMap<String, Transaction>, splitChildren: Map<String, Map<String, Transaction>>, budgetID: String) {
        previous = current
        previousSplitChildren = HashMap(splitChildren)
        previousBudgetID = budgetID
    }

    companion object {
        fun shouldResetBaselineForReload(isLoading: Boolean): Boolean = isLoading

        private val entryOrder = compareBy<Transaction>({ it.sortOrder ?: 0.0 }, { it.id })

        private fun matchesPendingUndo(pending: HistoryStore.PendingUndo, current: Map<String, Transaction>, splitChildren: Map<String, Map<String, Transaction>>): Boolean {
            val live = HashMap(current)
            for (children in splitChildren.values) live.putAll(children)
            for (expected in pending.expected) {
                val actual = live[expected.id]
                val intact = if (expected.tombstone) actual == null else actual != null && expected.matchesLiveTransaction(actual)
                if (!intact) return false
            }
            return pending.removedIDs.all { it !in live }
        }

        private fun samePersistedState(lhs: Map<String, Transaction>, rhs: Map<String, Transaction>): Boolean =
            lhs.size == rhs.size && lhs.all { (id, old) -> rhs[id]?.let { old.matchesLiveTransaction(it) } ?: false }
    }
}
