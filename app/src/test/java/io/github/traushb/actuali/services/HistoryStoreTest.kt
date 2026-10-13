package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Transaction
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {
    private fun transaction(id: String, amount: Long = -1000, isParent: Boolean = false, parentId: String? = null) = Transaction(
        id = id, accountId = "account", date = 20_260_906, amount = amount, payeeId = "payee", payeeName = "Groceries",
        categoryId = if (isParent) null else "category", categoryName = if (isParent) null else "Food", notes = null,
        cleared = false, reconciled = false, transferId = null, isParent = isParent, parentId = parentId, tombstone = false,
        sortOrder = null, importedPayee = null,
    )

    private fun action(id: String, budgetID: String, kind: HistoryActionKind, before: List<Transaction>, after: List<Transaction>, status: HistoryActionStatus = HistoryActionStatus.Applied, createdAt: Long = 0) =
        HistoryAction(id, createdAt, budgetID, kind, before, after, status)

    @Test
    fun retainsTheNewestTenActions() {
        val store = HistoryStore(InMemoryDefaults())
        for (index in 0 until 11) store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(transaction("$index")), now = index * 1000L)
        assertEquals(10, store.actions.size)
        assertTrue(store.actions.all { it.status == HistoryActionStatus.Applied && it.after.size == 1 })
        assertFalse(store.actions.any { it.after.first().id == "0" })
        assertTrue(store.actions.any { it.after.first().id == "10" })
    }

    @Test
    fun skipsActionsLargerThanTheSnapshotCap() {
        val store = HistoryStore(InMemoryDefaults())
        val cap = HistoryStore.MAX_SNAPSHOTS_PER_ACTION
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), (0..cap).map { transaction("over-$it") })
        assertTrue(store.actions.isEmpty())
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), (0 until cap).map { transaction("at-$it") })
        assertEquals(cap, store.actions.single().after.size)
    }

    @Test
    fun actionsPersistAndReload() {
        val defaults = InMemoryDefaults()
        HistoryStore(defaults).recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(transaction("persisted")))
        val second = HistoryStore(defaults)
        second.load("budget")
        assertEquals("persisted", second.actions.single().after.first().id)
    }

    /** A recreated budget (the demo reseed) must not open with history recorded against the previous copy. */
    @Test
    fun clearPersistedActionsDropsStoredHistoryAndBlocksResurrection() {
        val defaults = InMemoryDefaults()
        HistoryStore(defaults).recordSnapshots("demo", HistoryActionKind.Created, emptyList(), listOf(transaction("stale")))
        val reseeded = HistoryStore(defaults)
        reseeded.load("demo")
        assertEquals(1, reseeded.actions.size)
        reseeded.clearPersistedActions("demo")
        assertTrue(reseeded.actions.isEmpty())

        val relaunched = HistoryStore(defaults)
        relaunched.load("demo")
        assertTrue(relaunched.actions.isEmpty())
        relaunched.recordSnapshots("demo", HistoryActionKind.Created, emptyList(), listOf(transaction("fresh")))
        assertEquals("fresh", relaunched.actions.single().after.first().id)
    }

    @Test
    fun historyIsIsolatedPerBudget() {
        val defaults = InMemoryDefaults()
        val store = HistoryStore(defaults)
        store.recordSnapshots("budget-a", HistoryActionKind.Created, emptyList(), listOf(transaction("a")))
        store.recordSnapshots("budget-b", HistoryActionKind.Created, emptyList(), listOf(transaction("b")))
        store.load("budget-a")
        assertEquals(listOf("budget-a" to "a"), store.actions.map { it.budgetID to it.after.first().id })
        store.load("budget-b")
        assertEquals(listOf("budget-b" to "b"), store.actions.map { it.budgetID to it.after.first().id })
        val reloaded = HistoryStore(defaults)
        reloaded.load("budget-b")
        assertEquals(listOf("budget-b" to "b"), reloaded.actions.map { it.budgetID to it.after.first().id })
    }

    @Test
    fun actionsForAnotherBudgetAreDiscardedOnLoad() {
        val defaults = InMemoryDefaults()
        val stored = listOf(
            action("wrong", "budget-b", HistoryActionKind.Created, emptyList(), listOf(transaction("b")), createdAt = 2000),
            action("right", "budget-a", HistoryActionKind.Created, emptyList(), listOf(transaction("a")), createdAt = 1000),
        )
        defaults.set("history.actions.budget-a", AppJson.encodeToString(ListSerializer(HistoryAction.serializer()), stored))
        val store = HistoryStore(defaults)
        store.load("budget-a")
        assertEquals(listOf("right"), store.actions.map { it.id })
    }

    @Test
    fun recordsFullSplitSnapshots() {
        val store = HistoryStore(InMemoryDefaults())
        val addedChild = transaction("added", amount = -500, parentId = "parent")
        val oldChild = transaction("child", amount = -400, parentId = "parent")
        store.recordSnapshots(
            "budget",
            HistoryActionKind.Edited,
            before = listOf(transaction("parent", isParent = true), oldChild, addedChild.copy(tombstone = true)),
            after = listOf(transaction("parent", amount = -1200, isParent = true), transaction("child", amount = -700, parentId = "parent"), addedChild, oldChild.copy(tombstone = true)),
        )
        val action = store.actions.single()
        assertTrue(action.before.any { it.id == "child" && !it.tombstone })
        assertTrue(action.before.any { it.id == "added" && it.tombstone })
        assertTrue(action.after.any { it.id == "added" && !it.tombstone })
        assertTrue(action.after.any { it.id == "child" && it.tombstone })
    }

    @Test
    fun splitTitleRecognizesACollapseFromTheBeforeState() {
        val action = action("collapse", "budget", HistoryActionKind.Edited, listOf(transaction("parent", isParent = true)), listOf(transaction("parent")))
        assertEquals("Edited split transaction", action.title)
    }

    @Test
    fun undoneActionsCannotBeUndone() {
        val store = HistoryStore(InMemoryDefaults())
        store.load("budget")
        assertFalse(store.canUndo(action("undone", "budget", HistoryActionKind.Edited, emptyList(), listOf(transaction("tx")), HistoryActionStatus.Undone)))
    }

    @Test
    fun liveSnapshotComparisonIgnoresUnstableReadOnlyFields() {
        val recorded = transaction("tx").copy(sortOrder = 123.0, financialId = "wallet-id", startingBalanceFlag = true, payeeName = "Old Display Name", categoryName = "Old Category Name")
        val fetched = transaction("tx").copy(sortOrder = 456.0, financialId = null, startingBalanceFlag = false, payeeName = "New Display Name", categoryName = "New Category Name")
        assertTrue(recorded.matchesLiveTransaction(fetched))
    }

    @Test
    fun liveSnapshotComparisonDetectsUndoRelevantChanges() {
        val base = transaction("tx")
        val changes = listOf(
            base.copy(accountId = "other-account"), base.copy(date = base.date + 1), base.copy(amount = base.amount - 1),
            base.copy(payeeId = "other-payee"), base.copy(categoryId = "other-category"), base.copy(notes = "changed"),
            base.copy(cleared = true), base.copy(reconciled = true), base.copy(transferId = "other-transfer"),
            base.copy(isParent = true), base.copy(parentId = "parent"), base.copy(tombstone = true),
            base.copy(importedPayee = "imported"), base.copy(schedule = "schedule"),
        )
        for (changed in changes) assertFalse(changed.toString(), changed.matchesLiveTransaction(base))
    }

    @Test
    fun coalescesSplitEditPublicationsForTheSameParent() {
        val store = HistoryStore(InMemoryDefaults())
        val oldParent = transaction("parent", isParent = true)
        val newParent = transaction("parent", amount = -1200, isParent = true)
        val oldChild = transaction("child", amount = -400, parentId = "parent")
        val newChild = transaction("child", amount = -700, parentId = "parent")
        store.recordSnapshots("budget", HistoryActionKind.Edited, listOf(oldParent), listOf(newParent), now = 1000)
        store.recordSnapshots("budget", HistoryActionKind.Edited, listOf(newParent, oldChild), listOf(newParent, newChild), now = 1200)
        val action = store.actions.single()
        assertEquals(listOf("parent", "child"), action.before.map { it.id })
        assertEquals(listOf("parent", "child"), action.after.map { it.id })
        assertEquals(listOf(oldParent.amount, oldChild.amount), action.before.map { it.amount })
        assertEquals(listOf(newParent.amount, newChild.amount), action.after.map { it.amount })
    }

    @Test
    fun transferAndSplitDeletionTitlesAreExplicit() {
        val source = transaction("source")
        val target = transaction("target")
        val deletedTransfer = action(
            "transfer", "budget", HistoryActionKind.Deleted, listOf(source, target),
            listOf(source.copy(transferId = target.id, tombstone = true), target.copy(transferId = source.id, tombstone = true)),
        )
        assertEquals("Deleted transfer", deletedTransfer.title)
        val deletedSplit = action("split", "budget", HistoryActionKind.Deleted, listOf(source), listOf(source.copy(isParent = true, tombstone = true)))
        assertEquals("Deleted split transaction", deletedSplit.title)
    }

    @Test
    fun coalescesTransferLegsIntoOneAction() {
        val store = HistoryStore(InMemoryDefaults())
        val source = transaction("source", amount = -5000).copy(transferId = "target")
        val target = transaction("target", amount = 5000).copy(transferId = "source")
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(source))
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(target))
        val action = store.actions.single()
        assertEquals(setOf("source", "target"), action.after.map { it.id }.toSet())
        assertEquals("Created transfer", action.title)
    }

    @Test
    fun doesNotCoalesceUnrelatedTransferLegs() {
        val store = HistoryStore(InMemoryDefaults())
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(transaction("first").copy(transferId = "partner")))
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(transaction("unrelated")))
        store.recordSnapshots("budget", HistoryActionKind.Created, emptyList(), listOf(transaction("partner").copy(transferId = "first")))
        assertEquals(listOf("partner", "unrelated", "first"), store.actions.map { it.after.first().id })
    }
}
