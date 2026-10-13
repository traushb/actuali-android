package io.github.traushb.actuali.views.components

import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.views.components.AmountEntry.Operator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Drives the amount field's calculator the way the field feeds it keystrokes, pastes and keyboard-row taps. */
class AmountEntryTest {
    private fun entry(
        initial: String = "",
        allowsNegative: Boolean = false,
        conventional: Boolean = false,
        format: ActualNumberFormat = ActualNumberFormat.CommaDot,
    ) = AmountEntry(format, conventional, allowsNegative).also { it.sync(initial) }

    private fun AmountEntry.type(keys: String) = keys.forEach { edit(it.toString()) }

    private fun AmountEntry.backspace() = edit("")

    @Test
    fun calculatorModeShiftsDigitsIntoCents() {
        val amount = entry()
        amount.type("120")
        assertEquals("1.20", amount.fieldText)
        assertEquals("1.20", amount.boundText)
    }

    @Test
    fun theSelectedNumberFormatDrivesGroupingAndSeparators() {
        val expected = mapOf(
            ActualNumberFormat.DotComma to "1.234,56",
            ActualNumberFormat.SpaceComma to "1 234,56",
            ActualNumberFormat.ApostropheDot to "1’234.56",
        )
        for ((format, display) in expected) {
            val amount = entry(format = format)
            amount.type("123456")
            assertEquals(display, amount.fieldText)
            assertEquals("1234.56", amount.boundText)
        }
        val indian = entry(format = ActualNumberFormat.CommaDotIn)
        indian.type("123456789")
        assertEquals("12,34,567.89", indian.fieldText)
        assertEquals("1234567.89", indian.boundText)
    }

    /** A paste is parsed as a whole value: the digit shifter would read a grouping separator as the decimal point. */
    @Test
    fun pastesKeepTheWholeAmountInAnyFormat() {
        val cases = listOf(
            Triple(ActualNumberFormat.SpaceComma, "1 234,56", "1 234,56"),
            Triple(ActualNumberFormat.ApostropheDot, "1’234.56", "1’234.56"),
            Triple(ActualNumberFormat.CommaDot, "450,046.23", "450,046.23"),
            // A paste in the other convention still lands whole, shown in the selected one.
            Triple(ActualNumberFormat.CommaDot, "1.234,56", "1,234.56"),
            Triple(ActualNumberFormat.DotComma, "1,234.56", "1.234,56"),
        )
        for ((format, pasted, display) in cases) {
            val amount = entry(format = format)
            amount.edit(pasted)
            assertEquals(display, amount.fieldText)
        }
    }

    @Test
    fun anOversizedPasteIsRejected() {
        val amount = entry()
        assertFalse(amount.edit("1,000,000,000,000,000,000,000"))
        assertEquals("", amount.boundText)
        assertEquals("", amount.fieldText)
    }

    @Test
    fun conventionalPasteKeepsSignAndCents() {
        val amount = entry(allowsNegative = true, conventional = true)
        amount.edit("-450,046.23")
        assertEquals("-450046.23", amount.boundText)
    }

    // Conventional entry

    @Test
    fun conventionalEntryTypesWholeDigitsAndExplicitDecimals() {
        val whole = entry(conventional = true)
        whole.type("12")
        assertEquals("12", whole.fieldText)
        assertEquals("12", whole.boundText)
        val decimal = entry(conventional = true)
        decimal.type("12.056")
        assertEquals("12.05", decimal.fieldText)
        assertEquals("12.05", decimal.boundText)
    }

    @Test
    fun conventionalBackspaceWorksThroughDecimalAndDigits() {
        val amount = entry(conventional = true)
        amount.type("12.05")
        for (expected in listOf("12", "12", "12", "1", "")) {
            amount.backspace()
            assertEquals(expected, amount.boundText)
        }
        amount.backspace()
        assertEquals("", amount.fieldText)
    }

    @Test
    fun conventionalEntryEditsPrefilledAmounts() {
        val fractional = entry("12.5", conventional = true)
        fractional.type("0")
        assertEquals("12.50", fractional.fieldText)
        // Callers hand the field "%.2f" strings; a whole amount must survive the round trip.
        val whole = entry("324", conventional = true)
        whole.type("5")
        assertEquals("3,245", whole.fieldText)
        assertEquals("3245", whole.boundText)
    }

    @Test
    fun conventionalEntrySupportsNegativeAmounts() {
        val amount = entry(allowsNegative = true, conventional = true)
        amount.toggleSign()
        amount.type("12.05")
        assertEquals("-12.05", amount.fieldText)
        assertEquals("-12.05", amount.boundText)
    }

    @Test
    fun conventionalArithmeticEvaluatesLeftToRight() {
        fun evaluate(vararg steps: Any): String {
            val amount = entry(conventional = true)
            for (step in steps) if (step is Operator) amount.pushOperator(step) else amount.type(step as String)
            amount.finalizeExpression()
            return amount.boundText
        }
        assertEquals("18", evaluate("12", Operator.Add, "6"))
        assertEquals("6", evaluate("12", Operator.Subtract, "6"))
        assertEquals("72", evaluate("12", Operator.Multiply, "6"))
        assertEquals("2", evaluate("12", Operator.Divide, "6"))
        assertEquals("20", evaluate("2", Operator.Add, "3", Operator.Multiply, "4"))
        // A fractional result keeps its cents: whole display is about not inventing a fraction.
        assertEquals("2.50", evaluate("5", Operator.Divide, "2"))
    }

    @Test
    fun conventionalBindingStaysValidMidExpression() {
        val amount = entry(conventional = true)
        amount.type("12")
        amount.pushOperator(Operator.Add)
        amount.type("6")
        assertEquals("12 + 6", amount.fieldText)
        assertEquals("18", amount.boundText)
    }

    // Sign

    @Test
    fun toggleSignNegatesAndRestores() {
        val amount = entry(allowsNegative = true)
        amount.type("5")
        amount.toggleSign()
        assertEquals("-0.05", amount.fieldText)
        assertEquals("-0.05", amount.boundText)
        amount.toggleSign()
        assertEquals("0.05", amount.boundText)
    }

    @Test
    fun aSignToggledBeforeTypingCarriesIntoTheAmount() {
        val amount = entry(allowsNegative = true)
        amount.toggleSign()
        assertEquals("-", amount.fieldText)
        amount.type("250")
        assertEquals("-2.50", amount.boundText)
    }

    /** Reconcile can show a negative balance, so a prefilled sign survives editing. */
    @Test
    fun prefilledNegativeAmountKeepsItsSign() {
        val amount = entry("-123.4", allowsNegative = true)
        amount.type("5")
        assertEquals("-123.45", amount.boundText)
    }

    /** Tapping the field selects everything; the next digit replaces it, sign included. */
    @Test
    fun aFullReplaceResetsTheSign() {
        val amount = entry("-123.45", allowsNegative = true)
        amount.edit("9", replacesAll = true)
        assertEquals("0.09", amount.boundText)
    }

    @Test
    fun backspaceClearsABareSign() {
        val amount = entry(allowsNegative = true)
        amount.toggleSign()
        amount.backspace()
        assertEquals("", amount.fieldText)
        amount.type("7")
        assertEquals("0.07", amount.boundText)
    }

    @Test
    fun hardwareKeyboardMinusTogglesTheSign() {
        val amount = entry(allowsNegative = true)
        amount.type("120-")
        assertEquals("-1.20", amount.boundText)
    }

    @Test
    fun minusIsIgnoredWhereNegativesAreNotAllowed() {
        val amount = entry("-1.20")
        amount.type("-")
        assertFalse(amount.boundText.startsWith("-"))
        amount.toggleSign()
        amount.type("5")
        assertFalse(amount.fieldText.startsWith("-"))
    }

    // Keyboard-row arithmetic

    @Test
    fun additionShowsTheExpressionThenEvaluates() {
        val amount = entry()
        amount.type("1250")
        amount.pushOperator(Operator.Add)
        assertEquals("12.50", amount.boundText)
        amount.type("600")
        assertEquals("12.50 + 6.00", amount.fieldText)
        // Save never ends editing, so the binding must parse mid-expression.
        assertEquals("18.50", amount.boundText)
        amount.finalizeExpression()
        assertEquals("18.50", amount.fieldText)
    }

    @Test
    fun evaluatesLeftToRightWithoutPrecedence() {
        val amount = entry()
        amount.type("200")
        amount.pushOperator(Operator.Add)
        amount.type("300")
        amount.pushOperator(Operator.Multiply)
        assertEquals("5.00", amount.boundText)
        amount.type("400")
        amount.finalizeExpression()
        assertEquals("20.00", amount.boundText)
    }

    @Test
    fun divisionSplitsAndDivisionByZeroKeepsTheTotal() {
        val split = entry()
        split.type("4500")
        split.pushOperator(Operator.Divide)
        split.type("300")
        split.finalizeExpression()
        assertEquals("15.00", split.boundText)
        val byZero = entry()
        byZero.type("1000")
        byZero.pushOperator(Operator.Divide)
        byZero.type("000")
        byZero.finalizeExpression()
        assertEquals("10.00", byZero.boundText)
    }

    /** "12.50 ×" then Done must not multiply by an implied zero. */
    @Test
    fun aDanglingOperatorCollapsesToTheRunningTotal() {
        val amount = entry()
        amount.type("1250")
        amount.pushOperator(Operator.Multiply)
        amount.finalizeExpression()
        assertEquals("12.50", amount.boundText)
    }

    @Test
    fun aSecondOperatorSwapsTheArmedOne() {
        val amount = entry()
        amount.type("1000")
        amount.pushOperator(Operator.Add)
        amount.pushOperator(Operator.Subtract)
        amount.type("400")
        amount.finalizeExpression()
        assertEquals("6.00", amount.boundText)
    }

    @Test
    fun anOperatorBeforeAnyInputIsIgnored() {
        val amount = entry()
        amount.pushOperator(Operator.Add)
        assertEquals("", amount.fieldText)
        amount.type("500")
        assertEquals("5.00", amount.boundText)
    }

    @Test
    fun backspaceThroughAnEmptyOperandUndoesTheOperator() {
        val amount = entry()
        amount.type("1250")
        amount.pushOperator(Operator.Add)
        assertEquals("12.50 + ", amount.fieldText)
        amount.backspace()
        assertEquals("12.50", amount.fieldText)
        assertEquals("12.50", amount.boundText)
        amount.backspace()
        assertEquals("12.50", amount.boundText)
    }

    @Test
    fun aFullReplaceClearsAPendingExpression() {
        val amount = entry()
        amount.type("1250")
        amount.pushOperator(Operator.Add)
        amount.type("600")
        amount.edit("9", replacesAll = true)
        assertEquals("0.09", amount.fieldText)
        assertEquals("0.09", amount.boundText)
    }

    /** Where the field carries a sign the true result stands; elsewhere the sign comes from the toggle, so it's the magnitude. */
    @Test
    fun negativeResultsKeepTheirSignOnlyWhereAllowed() {
        fun result(allowsNegative: Boolean): String {
            val amount = entry(allowsNegative = allowsNegative)
            amount.type("500")
            amount.pushOperator(Operator.Subtract)
            amount.type("2000")
            amount.finalizeExpression()
            return amount.boundText
        }
        assertEquals("-15.00", result(allowsNegative = true))
        assertEquals("15.00", result(allowsNegative = false))
    }

    @Test
    fun aResultStaysEditableAfterEvaluating() {
        val amount = entry(conventional = true)
        amount.type("10")
        amount.pushOperator(Operator.Add)
        amount.type("8.5")
        amount.finalizeExpression()
        assertEquals("18.50", amount.fieldText)
        amount.backspace()
        assertEquals("18.50", amount.boundText)
    }
}
