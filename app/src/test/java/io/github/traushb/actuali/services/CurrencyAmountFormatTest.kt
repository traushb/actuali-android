package io.github.traushb.actuali.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CurrencyAmountFormatTest {
    private fun format(cents: Long, code: String = "USD", locale: Locale = Locale.US, wholeUnits: Boolean = false, numberFormat: ActualNumberFormat = ActualNumberFormat.CommaDot) =
        CurrencyAmountFormat.string(cents, code, narrowSymbol = false, wholeUnits = wholeUnits, numberFormat = numberFormat, locale = locale)

    /** The symbol and sign layout is worked out once per currency, sign and locale; each amount must still get its own digits and sign. */
    @Test
    fun layoutFollowsSignForEveryAmount() {
        assertEquals("$1,234.56", format(123_456))
        assertEquals("-$1,234.56", format(-123_456))
        assertEquals("$0.00", format(0))
        assertEquals("$9.99", format(999))
        assertEquals("-$0.05", format(-5))
    }

    @Test
    fun localeDecidesWhereTheSymbolGoes() {
        val german = format(123_456, "EUR", Locale.GERMANY, numberFormat = ActualNumberFormat.DotComma)
        assertEquals("1.234,56\u00A0€", german.replace(' ', '\u00A0'))
        assertEquals("¥1,234", format(1_234_00, "JPY"))
        assertEquals("$1,235", format(123_456, wholeUnits = true))
    }

    @Test
    fun noCurrencyCodeIsAPlainNumber() {
        assertEquals("1,234.56", format(123_456, code = ""))
        assertEquals("-12.00", format(-1200, code = ""))
    }

    @Test
    fun groupingMatchesEachNumberFormat() {
        val expected = mapOf(
            ActualNumberFormat.CommaDot to "1,234,567.89",
            ActualNumberFormat.DotComma to "1.234.567,89",
            ActualNumberFormat.SpaceComma to "1\u202F234\u202F567,89",
            ActualNumberFormat.ApostropheDot to "1\u2019234\u2019567.89",
            ActualNumberFormat.CommaDotIn to "12,34,567.89",
        )
        for ((numberFormat, text) in expected) assertEquals(numberFormat.name, text, numberFormat.format(1_234_567.89, wholeUnits = false, currencyCode = null))
        assertEquals("1,23,456", ActualNumberFormat.CommaDotIn.format(123_456.0, wholeUnits = true, currencyCode = null))
        assertEquals("999", ActualNumberFormat.CommaDot.format(999.0, wholeUnits = true, currencyCode = null))
        assertEquals("1,000", ActualNumberFormat.CommaDot.format(1000.0, wholeUnits = true, currencyCode = null))
    }
}
