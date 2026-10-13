package io.github.traushb.actuali.views

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.HistoryAction
import io.github.traushb.actuali.services.HistoryActionKind
import io.github.traushb.actuali.services.HistoryActionStatus
import io.github.traushb.actuali.services.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTextTest {
    private fun transaction(
        id: String,
        amount: Long = -1000,
        payeeId: String? = "payee",
        payeeName: String? = "Groceries",
        categoryId: String? = "category",
        categoryName: String? = "Food",
        notes: String? = null,
        date: Int = 20_260_906,
        isParent: Boolean = false,
        parentId: String? = null,
    ) = Transaction(id, "account", date, amount, payeeId, payeeName, categoryId, categoryName, notes, false, false, null, isParent, parentId, false, null, null)

    private fun detail(before: List<Transaction>, after: List<Transaction>, primary: Transaction) =
        HistoryText.editedDetail(HistoryAction("action", 0, "budget", HistoryActionKind.Edited, before, after, HistoryActionStatus.Applied), primary) { it.toString() }

    @Test
    fun splitChildAmountEditReportsTheChildAmount() {
        val parent = transaction("parent", categoryId = null, categoryName = null, isParent = true)
        val result = detail(listOf(parent, transaction("child", amount = -600, parentId = "parent")), listOf(parent, transaction("child", amount = -700, parentId = "parent")), parent)
        assertEquals(L("Amount: %@ → %@", "-600", "-700"), result)
    }

    @Test
    fun payeeEditUsesThePayeeIdChange() {
        val after = transaction("txn", payeeId = "payee-new", payeeName = "New Payee")
        assertEquals(L("Payee: %@ → %@", "Old Payee", "New Payee"), detail(listOf(transaction("txn", payeeId = "payee-old", payeeName = "Old Payee")), listOf(after), after))
    }

    @Test
    fun categoryEditUsesTheCategoryIdChange() {
        val after = transaction("txn", categoryId = "category-new", categoryName = "New Category")
        assertEquals(L("Category: %@ → %@", "Old Category", "New Category"), detail(listOf(transaction("txn", categoryId = "category-old", categoryName = "Old Category")), listOf(after), after))
    }

    @Test
    fun dateEditShowsBothDates() {
        val after = transaction("txn", date = 20_260_909)
        assertEquals(
            L("Date: %@ → %@", Transaction.formattedDate(20_260_906), Transaction.formattedDate(20_260_909)),
            detail(listOf(transaction("txn", date = 20_260_906)), listOf(after), after),
        )
    }

    @Test
    fun splitChildNoteEditReportsTheChildNote() {
        val parent = transaction("parent", categoryId = null, categoryName = null, isParent = true)
        val result = detail(listOf(parent, transaction("child", notes = "old note", parentId = "parent")), listOf(parent, transaction("child", notes = "new note", parentId = "parent")), parent)
        assertEquals(L("Note changed"), result)
    }

    @Test
    fun aChangeToAnotherTransactionIsNotAttributedToThePrimary() {
        val primary = transaction("primary")
        assertNull(detail(listOf(primary, transaction("other", amount = -600)), listOf(primary, transaction("other", amount = -700)), primary))
    }
}
