package io.github.traushb.actuali.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS's TransactionTextParserTests run against its fallback parser, which is the whole Android parser. */
class TransactionTextParserTest {
    private fun parse(text: String) = TransactionTextParser.parse(text)

    @Test
    fun parsesIndianUPIMessage() {
        val result = parse("A/c XX9876 debited by Rs.500.00 on 20-08-26 to SWIGGY via UPI")
        assertEquals(500.00, result.amount)
        assertNull(result.sourceCurrencyCode)
        assertEquals("9876", result.cardHint)
        assertFalse(result.isIncome)
        assertEquals("SWIGGY", result.payee)
    }

    @Test
    fun parsesUSCreditCardMessage() {
        val result = parse("Card ending 4321: $18.50 at Starbucks")
        assertEquals(18.50, result.amount)
        assertNull(result.sourceCurrencyCode)
        assertEquals("4321", result.cardHint)
        assertFalse(result.isIncome)
        assertEquals("Starbucks", result.payee)
    }

    @Test
    fun parsesWalletDebitMessage() {
        val result = parse(
            "Rs. 250.00 spent from Sample  Meal wallet, card no.xx1234 on 01-01-2026 12:00:00 at Sample Diner . Avl bal Rs.5000.00. Not you call 18000000000",
        )
        assertEquals(250.00, result.amount)
        assertEquals("1234", result.cardHint)
        assertFalse(result.isIncome)
        assertEquals("Sample Diner", result.payee)
    }

    @Test
    fun parsesCardAlertMessageWithLimitAndBalance() {
        val result = parse(
            "ALERT: INR 150.00 is spent on your SampleCard ending 4321 at Quick-mart Payments on 01-01-2026. Available credit limit is Rs 100,000.00, Current outstanding is Rs 150.00. Not you?  Call 18000000 (toll-free)",
        )
        assertEquals(150.00, result.amount)
        assertEquals("INR", result.sourceCurrencyCode)
        assertEquals("4321", result.cardHint)
        assertFalse(result.isIncome)
        assertEquals("Quick-mart Payments", result.payee)
    }

    @Test
    fun doesNotTreatCardSuffixAsAmountBeforeCurrencyCode() = assertEquals(25.50, parse("Card ending 4321: USD 25.50 at Store").amount)

    @Test
    fun doesNotTreatDateAsAmountBeforeCurrencyCode() = assertEquals(25.50, parse("Purchase on 01-01-2026: USD 25.50 at Store").amount)

    @Test
    fun doesNotUseFundingWalletAsPayee() {
        assertNull(parse("Rs.500.00 paid from Sample Meal wallet on 01-01-2026").payee)
        assertNull(parse("INR 500.00 paid from Amazon Pay Balance on 01-01-2026").payee)
        assertEquals("Coffee Shop", parse("Rs.500.00 paid from Sample Meal wallet at Coffee Shop on 01-01-2026").payee)
    }

    @Test
    fun doesNotIncludeBareTrailingDateInMerchant() = assertEquals("Amazon", parse("Paid $18.50 to Amazon 12-01-2026").payee)

    @Test
    fun parsesNumericHyphenatedMerchant() = assertEquals("7-Eleven", parse("Paid $4.50 at 7-Eleven").payee)

    @Test
    fun parsesRefundAsIncomeAndDoesNotCaptureCardAsMerchant() {
        val result = parse("Refund of $25.00 from Amazon credited to card 5555")
        assertEquals(25.0, result.amount)
        assertTrue(result.isIncome)
        assertEquals("5555", result.cardHint)
        assertNotEquals("card 5555", result.payee)
    }

    @Test
    fun emptyTextReturnsNils() {
        val result = parse("")
        assertNull(result.amount)
        assertNull(result.payee)
        assertNull(result.cardHint)
    }

    @Test
    fun toPendingImportPreservesFields() {
        val text = "Paid $10 at Coffee Shop using card ending 1234"
        val pending = parse(text).toPendingImport()
        assertEquals(text, pending.rawText)
        assertEquals("1234", pending.cardHint)
        assertEquals(10.0, pending.amount)
        assertNull(pending.sourceCurrencyCode)
    }

    @Test
    fun preservesConfidentSourceCurrency() {
        val parsed = parse("Paid EUR 100.00 at Bakery")
        assertEquals("EUR", parsed.sourceCurrencyCode)
        assertEquals("EUR", parsed.toPendingImport().sourceCurrencyCode)
    }

    @Test
    fun preservesExplicitIndianCurrencyCode() = assertEquals("INR", parse("Paid INR 100 at Store").sourceCurrencyCode)

    @Test
    fun preservesAdditionalExplicitIsoCurrencyCodes() {
        for (code in listOf("CAD", "AUD", "JPY", "CHF")) assertEquals(code, parse("Paid $code 100 at Store").sourceCurrencyCode)
    }

    @Test
    fun extractsAmountAdjacentToAnyExplicitIsoCurrencyCode() {
        val leading = parse("Card ending 4321 was charged (CAD): 25.50 at Store")
        val trailing = parse("Card ending 4321 was charged 19.75 CHF at Store")
        assertEquals(25.50, leading.amount)
        assertEquals("CAD", leading.sourceCurrencyCode)
        assertEquals(19.75, trailing.amount)
        assertEquals("CHF", trailing.sourceCurrencyCode)
    }

    @Test
    fun requiresUppercaseExplicitIsoCurrencyCode() = assertNull(parse("Paid cad 100 at Store").sourceCurrencyCode)

    @Test
    fun doesNotTreatTitleCaseProseAsLeadingCurrencyCode() {
        val parsed = parse("Try 100 at Store")
        assertNull(parsed.sourceCurrencyCode)
        assertEquals(100.0, parsed.amount)
        assertEquals("Store", parsed.payee)
        assertEquals("Try 100 at Store", parsed.rawText)
    }

    @Test
    fun acceptsUppercaseTurkishLiraInLeadingPosition() = assertEquals("TRY", parse("TRY 100 at Store").sourceCurrencyCode)

    @Test
    fun acceptsTurkishLiraInTrailingPosition() = assertEquals("TRY", parse("100 TRY at Store").sourceCurrencyCode)

    @Test
    fun rejectsLowercaseCurrencyCodeInTrailingPosition() = assertNull(parse("100 try at Store").sourceCurrencyCode)

    @Test
    fun doesNotTreatTitleCaseProseAsTrailingCurrencyCode() = assertNull(parse("100 Try at Store").sourceCurrencyCode)

    @Test
    fun acceptsPunctuationAroundExplicitCurrencyCode() {
        assertEquals("CAD", parse("Paid (CAD): 100 at Store").sourceCurrencyCode)
        assertEquals("CAD", parse("Paid 100, CAD. at Store").sourceCurrencyCode)
    }

    @Test
    fun rejectsUnknownCurrencyCode() = assertNull(parse("Paid XYZ 100 at Store").sourceCurrencyCode)

    @Test
    fun doesNotTreatThreeLetterProseAsCurrency() = assertNull(parse("Paid the 100 at Store").sourceCurrencyCode)

    @Test
    fun toPendingImportPreservesOriginBudgetId() =
        assertEquals("budget-a", parse("Paid $10 at Coffee").toPendingImport(originBudgetId = "budget-a").originBudgetId)

    @Test
    fun resolvesCardHintOverridingHallucinatedDigits() =
        assertEquals("6419", TransactionTextParser.resolveCardHint("1964", "Rs 1,234.00 spent on Sample Bank Card XX6419 on 01-01-2026 at Coffee Shop."))

    @Test
    fun resolvesCardHintAcceptingGroundedCandidateWhenRegexMisses() =
        assertEquals("9988", TransactionTextParser.resolveCardHint("9988", "Transaction approved on device 9988 for purchase"))

    @Test
    fun rejectsHallucinatedCardHintNotInText() = assertNull(TransactionTextParser.resolveCardHint("1234", "Paid $15 at Store"))

    @Test
    fun parsesAccountKeywordCardHint() = assertEquals("1234", parse("Account ending 1234 charged USD 20.00 at Store").cardHint)

    @Test
    fun doesNotTreatReferenceNumberAsCardHint() =
        assertEquals("6419", parse("Rs 500 spent on card. Ref no 987654321. A/C XX6419 on 01-01-2026").cardHint)

    @Test
    fun doesNotTreatBalanceOrAmountAsCardHint() {
        assertEquals("6419", parse("Account balance 1234.56. Card XX6419 debited USD 20 at Coffee Shop").cardHint)
        assertEquals("6419", parse("Card txn of Rs.2500.00 at Amazon on 12-01-26 using Card XX6419").cardHint)
    }

    @Test
    fun parsesCardHintAfterFillerWords() {
        assertEquals("1234", parse("Card ending with 1234 used").cardHint)
        assertEquals("4321", parse("Account number 4321 debited").cardHint)
    }

    @Test
    fun keepsGroundedModelCardHintOverRegex() =
        assertEquals("6419", TransactionTextParser.resolveCardHint("6419", "Card XX1111 was replaced. Rs 500 charged on card XX6419"))

    @Test
    fun keepsGroundedNonNumericCardHint() = assertEquals("HSBC", TransactionTextParser.resolveCardHint("HSBC", "HSBC: Rs 500 spent at Store"))

    @Test
    fun lowercaseProseIsNotCurrency() = assertNull(parse("Debited 500 all accounts on 12 Jan").sourceCurrencyCode)
}
