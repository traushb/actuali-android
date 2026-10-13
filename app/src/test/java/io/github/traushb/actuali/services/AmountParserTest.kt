package io.github.traushb.actuali.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {
    private fun parse(text: String, format: ActualNumberFormat? = null) = AmountParser.parse(text, format)

    @Test
    fun parsesPlainDecimal() {
        assertEquals(4.0, parse("4.00"))
        assertEquals(4.5, parse("4.5"))
        assertEquals(12.0, parse("12"))
    }

    @Test
    fun parsesCommaDecimal() {
        assertEquals(4.0, parse("4,00"))
        assertEquals(4.5, parse("4,5"))
    }

    @Test
    fun stripsCurrencySymbolsAndCodes() {
        assertEquals(4.0, parse("$4.00"))
        assertEquals(4.0, parse("4,00 €"))
        assertEquals(12.5, parse("CHF 12.50"))
        assertEquals(4.0, parse("4.00 USD"))
        assertEquals(1234.56, parse("£1,234.56"))
    }

    @Test
    fun parsesGroupedThousands() {
        assertEquals(1234.56, parse("1,234.56"))
        assertEquals(1234.56, parse("1.234,56"))
        assertEquals(1_234_567.89, parse("1,234,567.89"))
    }

    @Test
    fun treatsSingleSeparatorWithThreeDigitTailAsGrouping() {
        assertEquals(1234.0, parse("1,234"))
        assertEquals(1_234_567.0, parse("1.234.567"))
    }

    @Test
    fun selectedCommaDotTreatsCommaAsGrouping() {
        assertEquals(1234.0, parse("1,234", ActualNumberFormat.CommaDot))
        assertEquals(1234.56, parse("1,234.56", ActualNumberFormat.CommaDot))
        assertEquals(1_000_000.33, parse("10,00,000.33", ActualNumberFormat.CommaDot))
        assertNull(parse("12,34", ActualNumberFormat.CommaDot))
    }

    @Test
    fun selectedDotCommaTreatsCommaAsDecimal() {
        assertEquals(1.234, parse("1,234", ActualNumberFormat.DotComma))
        assertEquals(1234.56, parse("1.234,56", ActualNumberFormat.DotComma))
        assertEquals(1.23, parse("1,23", ActualNumberFormat.DotComma))
        assertNull(parse("12.50", ActualNumberFormat.DotComma))
    }

    @Test
    fun selectedSpaceCommaTreatsNarrowSpaceAsGrouping() {
        assertEquals(1234.56, parse("1 234,56", ActualNumberFormat.SpaceComma))
        assertEquals(1234.56, parse("1 234,56", ActualNumberFormat.SpaceComma))
    }

    @Test
    fun selectedApostropheDotTreatsApostropheAsGrouping() {
        assertEquals(1234.56, parse("1’234.56", ActualNumberFormat.ApostropheDot))
        assertEquals(1234.56, parse("1'234.56", ActualNumberFormat.ApostropheDot))
    }

    @Test
    fun selectedIndianFormatUsesIndianGrouping() {
        assertEquals(1_234_567.89, parse("12,34,567.89", ActualNumberFormat.CommaDotIn))
        assertEquals(1234.56, parse("1,234.56", ActualNumberFormat.CommaDotIn))
    }

    @Test
    fun selectedFormatRejectsConflictingSeparatorsInsteadOfGuessing() {
        assertNull(parse("1,234.56", ActualNumberFormat.DotComma))
        assertNull(parse("1.234,56", ActualNumberFormat.CommaDot))
        assertNull(parse("1'234,56", ActualNumberFormat.DotComma))
        assertNull(parse("1.234.56", ActualNumberFormat.ApostropheDot))
    }

    @Test
    fun selectedFormatPreservesZeroAndNegativeValues() {
        assertEquals(0.0, parse("0,00", ActualNumberFormat.DotComma))
        assertEquals(-1234.56, parse("-1.234,56", ActualNumberFormat.DotComma))
        assertEquals(-1234.56, parse("-1’234.56", ActualNumberFormat.ApostropheDot))
    }

    @Test
    fun treatsZeroIntegerPartAsDecimal() {
        assertEquals(0.234, parse("0,234"))
        assertEquals(0.5, parse("0.50"))
    }

    @Test
    fun parsesNegativeAmounts() {
        assertEquals(-4.0, parse("-4.00"))
        assertEquals(-4.0, parse("-$4.00"))
    }

    @Test
    fun parsesZero() {
        assertEquals(0.0, parse("0"))
        assertEquals(0.0, parse("0.00"))
    }

    @Test
    fun trimsWhitespace() = assertEquals(4.0, parse("  4.00  "))

    @Test
    fun parsesSingleAmountEmbeddedInText() {
        assertEquals(4.5, parse("Starbucks $4.50"))
        assertEquals(4.0, parse("Betrag: 4,00."))
    }

    @Test
    fun hyphenInSurroundingTextIsNotANegativeSign() = assertEquals(4.0, parse("Coca-Cola $4.00"))

    @Test
    fun rejectsTextWithMultipleNumbers() {
        assertNull(parse("7-Eleven"))
        assertNull(parse("7-Eleven $4.50"))
        assertNull(parse("Jan 5 2026 $4.00"))
    }

    @Test
    fun rejectsEmptyAndNonNumericInput() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("abc"))
        assertNull(parse("$"))
    }
}
