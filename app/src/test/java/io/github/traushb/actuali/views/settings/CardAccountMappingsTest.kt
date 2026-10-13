package io.github.traushb.actuali.views.settings

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AccountType
import io.github.traushb.actuali.models.PendingImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAccountMappingsTest {
    private fun pending(cardHint: String?, payee: String? = null, originBudgetId: String? = "budget-1") =
        PendingImport(originBudgetId = originBudgetId, amount = 25.0, sourceCurrencyCode = "USD", payee = payee, cardHint = cardHint, rawText = "sample notification text")

    private fun account(id: String, name: String, closed: Boolean = false) = Account(id, name, AccountType.Checking, offBudget = false, closed = closed, sortOrder = 0, balance = 0)

    private fun suggestions(imports: List<PendingImport>, accounts: List<Account>, mappings: Map<String, String> = emptyMap()) =
        CardAccountMappings.suggestions(imports, "budget-1", accounts, mappings)

    @Test
    fun noPendingImportsMeansNoSuggestions() =
        assertTrue(suggestions(emptyList(), listOf(account("acct_chase", "Chase")), mapOf("1234" to "acct_chase")).isEmpty())

    @Test
    fun ignoresPendingImportsWithoutACardHint() {
        val imports = listOf(pending(null, "Coffee Shop"), pending("", "Grocery Store"), pending("   ", "Bookstore"))
        assertTrue(suggestions(imports, listOf(account("acct_chase", "Chase"))).isEmpty())
    }

    @Test
    fun ignoresHintsAlreadyMappedInAnyCase() {
        val accounts = listOf(account("acct_chase", "Chase"), account("acct_hsbc", "HSBC"))
        assertTrue(suggestions(listOf(pending("1234", "Amazon"), pending("HSBC", "Gas Station")), accounts, mapOf("1234" to "acct_chase", "hsbc" to "acct_hsbc")).isEmpty())
        assertTrue(suggestions(listOf(pending("hsbc", "Dinner")), listOf(account("acct_hsbc", "HSBC Account")), mapOf("HSBC" to "acct_hsbc")).isEmpty())
    }

    /** A hint naming an open account already routes there, mappings or not. */
    @Test
    fun ignoresHintsThatMatchAnAccountName() =
        assertTrue(suggestions(listOf(pending("HSBC Checking", "Coffee")), listOf(account("acct_checking", "HSBC Checking"))).isEmpty())

    /** A card mapped to a closed account no longer routes, so it's offered for repair. */
    @Test
    fun suggestsACardMappedToAClosedAccount() {
        val accounts = listOf(account("acct_closed", "Old Chase", closed = true), account("acct_active", "Active Checking"))
        assertEquals(listOf("1234"), suggestions(listOf(pending("1234", "Groceries")), accounts, mapOf("1234" to "acct_closed")).map { it.keyword })
    }

    @Test
    fun groupsUnmappedHintsWithCountAndSamplePayee() {
        val result = suggestions(listOf(pending("9876", "Starbucks"), pending("9876", "Target"), pending("5555", "Uber")), listOf(account("acct_main", "Main")))
        assertEquals(listOf(CardAccountMappings.Suggestion("9876", 2, "Starbucks"), CardAccountMappings.Suggestion("5555", 1, "Uber")), result)
    }

    @Test
    fun sortsByCountThenAlphabetically() {
        val imports = listOf(pending("ZZZZ", "A"), pending("AAAA", "B"), pending("MMMM", "C"), pending("MMMM", "D"))
        assertEquals(listOf("MMMM", "AAAA", "ZZZZ"), suggestions(imports, listOf(account("acct_main", "Main"))).map { it.keyword })
    }

    @Test
    fun filtersOtherBudgetsButKeepsLegacyImports() {
        val imports = listOf(pending("1111"), pending("2222", originBudgetId = "budget-2"), pending("3333", originBudgetId = null))
        assertEquals(listOf("1111", "3333"), suggestions(imports, listOf(account("acct_main", "Main"))).map { it.keyword })
    }

    @Test
    fun editingWithoutRenameRemovesNothing() {
        assertTrue(CardAccountMappings.keywordsRemovedBySave(emptyList(), listOf("1234")).isEmpty())
        assertTrue(CardAccountMappings.keywordsRemovedBySave(listOf("1234"), listOf("1234")).isEmpty())
    }

    /** Resolution lowercases hints, but keys are exact, so even a case-only rename drops the old key or the list shows two rows for one mapping. */
    @Test
    fun renamingAKeywordRemovesTheOriginalKey() {
        assertEquals(listOf("1234"), CardAccountMappings.keywordsRemovedBySave(listOf("1234"), listOf("4321")))
        assertEquals(listOf("HSBC"), CardAccountMappings.keywordsRemovedBySave(listOf("HSBC"), listOf("hsbc")))
    }

    @Test
    fun multiKeywordSaveRemovesOnlyDeletedKeywords() {
        assertEquals(listOf("5678"), CardAccountMappings.keywordsRemovedBySave(listOf("1234", "5678", "CSR"), listOf("1234", "CSR", "9999")))
        assertTrue(CardAccountMappings.keywordsRemovedBySave(listOf("1234", "CSR"), listOf("1234", "CSR", "9999")).isEmpty())
    }

    @Test
    fun groupByAccountGroupsAndSortsAccountsAndKeywords() {
        val accounts = listOf(account("acct_chase", "Chase Sapphire"), account("acct_citi", "Citi Double Cash"))
        val mappings = mapOf("CSR" to "acct_chase", "1234" to "acct_chase", "5678" to "acct_chase", "CitiDC" to "acct_citi", "9012" to "acct_citi")
        assertEquals(
            listOf(
                CardAccountMappings.MappedAccount("acct_chase", "Chase Sapphire", listOf("1234", "5678", "CSR")),
                CardAccountMappings.MappedAccount("acct_citi", "Citi Double Cash", listOf("9012", "CitiDC")),
            ),
            CardAccountMappings.groupByAccount(mappings, accounts, unknownName = "Unknown Account"),
        )
    }

    @Test
    fun groupByAccountHandlesUnknownAndDuplicateAccountNames() {
        val grouped = CardAccountMappings.groupByAccount(
            mapOf("1111" to "acct_1", "2222" to "acct_2", "3333" to "acct_missing"),
            listOf(account("acct_1", "Savings"), account("acct_2", "Savings")),
            unknownName = "Unknown Account",
        )
        assertEquals(3, grouped.size)
        assertEquals(CardAccountMappings.MappedAccount("acct_missing", "Unknown Account", listOf("3333")), grouped.single { it.accountId == "acct_missing" })
    }
}
