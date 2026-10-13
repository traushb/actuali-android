package io.github.traushb.actuali.views.components

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundedHalfAwayFromZero
import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.services.AmountParser
import java.util.Locale
import kotlin.math.abs

/**
 * The amount field's calculator. Digits shift into cents ("120" reads 1.20)
 * unless [conventionalAmountEntry] is on, and the keyboard row adds
 * left-to-right `+ − × ÷` arithmetic. [fieldText] is what the field shows
 * (mid-expression "12.50 + 6.00"); [boundText] is the plain decimal the form
 * reads ("18.50"), valid at any moment.
 */
class AmountEntry(
    var numberFormat: ActualNumberFormat,
    private val conventionalAmountEntry: Boolean = false,
    private val allowsNegative: Boolean = false,
) {
    /** Left-to-right evaluation only. */
    enum class Operator(val symbol: Char) {
        Add('+'),
        Subtract('−'),
        Multiply('×'),
        Divide('÷'),
        ;

        fun apply(lhs: Double, rhs: Double): Double = when (this) {
            Add -> lhs + rhs
            Subtract -> lhs - rhs
            Multiply -> lhs * rhs
            // Dividing by zero has no sensible amount to show, so the operator
            // is dropped and the running total stands.
            Divide -> if (rhs == 0.0) lhs else lhs / rhs
        }
    }

    /** The last value handed to the form, so an outside change can be told from the field's own echo. */
    var lastPublishedText: String? = null
        private set

    private var integerDigits = ""
    private var hasDecimalPoint = false
    private var fractionDigits = ""
    private var isNegative = false

    /** Everything to the left of the pending operator, already evaluated. */
    private var accumulatedValue: Double? = null
    private var pendingOperator: Operator? = null

    /** True once the current operand has content of its own, so a bare sign or a dangling operator isn't typed input. */
    private val hasTypedOperand: Boolean get() = integerDigits.isNotEmpty() || hasDecimalPoint

    /** Load a value set from outside the field. */
    fun sync(value: String) {
        accumulatedValue = null
        pendingOperator = null
        lastPublishedText = value
        isNegative = allowsNegative && value.startsWith("-")
        val dot = value.indexOfFirst { it == '.' || it == ',' }
        if (dot >= 0) {
            integerDigits = value.substring(0, dot).filter(Char::isDigit)
            hasDecimalPoint = true
            fractionDigits = value.substring(dot + 1).filter(Char::isDigit).take(2)
        } else {
            integerDigits = value.filter(Char::isDigit)
            hasDecimalPoint = false
            fractionDigits = ""
        }
    }

    /**
     * Apply an edit: [replacesAll] when the whole text was selected, [string]
     * the typed text (empty for a backspace). A multi-character string is a
     * paste, parsed as a whole value — the digit shifter would read a grouping
     * separator as the decimal point ("1.234,56" became 1.23). Returns false
     * when the paste isn't an amount, leaving everything as it was.
     */
    fun edit(string: String, replacesAll: Boolean = false): Boolean {
        if (string.length > 1) {
            val pasted = AmountParser.parse(string, numberFormat) ?: AmountParser.parse(string)
            if (pasted == null || Transaction.cents(pasted) == null) return false
            if (replacesAll) reset()
            setOperand(pasted)
            return publish()
        }
        if (replacesAll) reset()
        if (string.isEmpty()) handleBackspace() else string.forEach(::handleCharacter)
        return publish()
    }

    /** Done, tapping away or dismissing the keyboard always leaves a plain amount, never a half-finished expression. */
    fun finalizeExpression() {
        val pending = pendingOperator
        val accumulated = accumulatedValue
        if (pending != null && accumulated != null) {
            // A dangling operator ("12.50 ×" then Done) leaves the running total
            // alone rather than multiplying it by an implied zero.
            val result = if (hasTypedOperand) pending.apply(accumulated, currentOperandValue()) else accumulated
            accumulatedValue = null
            pendingOperator = null
            setOperand(result)
        }
        publish()
    }

    fun toggleSign() {
        if (!allowsNegative) return
        isNegative = !isNegative
        publish()
    }

    /**
     * Folds the operand just typed into the running total and arms [op].
     * Tapping a second operator without typing in between swaps which one is armed.
     */
    fun pushOperator(op: Operator) {
        if (accumulatedValue == null && !hasTypedOperand) return
        if (hasTypedOperand) {
            val operand = currentOperandValue()
            val accumulated = accumulatedValue
            val pending = pendingOperator
            accumulatedValue = if (accumulated != null && pending != null) pending.apply(accumulated, operand) else operand
            resetOperand()
        }
        pendingOperator = op
        publish()
    }

    /** What the field shows: the running total and armed operator, if any, then the operand being typed. */
    val fieldText: String
        get() {
            val operandText = operandDisplay()
            val pending = pendingOperator ?: return operandText
            val accumulated = accumulatedValue ?: return operandText
            val accumulatedText = displayValue(accumulated)
            return if (operandText.isEmpty()) "$accumulatedText ${pending.symbol} " else "$accumulatedText ${pending.symbol} $operandText"
        }

    /** What the form reads: always a plain decimal, so it can be parsed at any moment — including mid-expression. */
    val boundText: String
        get() {
            if (!hasTypedOperand && pendingOperator == null) return ""
            val value = normalized(resolvedValue())
            val whole = conventionalAmountEntry && value == roundedHalfAwayFromZero(value)
            return String.format(Locale.ROOT, if (whole) "%.0f" else "%.2f", value)
        }

    private fun publish(): Boolean {
        lastPublishedText = boundText
        return true
    }

    private fun reset() {
        resetOperand()
        accumulatedValue = null
        pendingOperator = null
    }

    private fun resolvedValue(): Double {
        val pending = pendingOperator
        val accumulated = accumulatedValue
        if (pending == null || accumulated == null) return currentOperandValue()
        return if (hasTypedOperand) pending.apply(accumulated, currentOperandValue()) else accumulated
    }

    private fun currentOperandValue(): Double {
        val unsigned = when {
            hasDecimalPoint -> "${integerDigits.ifEmpty { "0" }}.$fractionDigits".toDoubleOrNull() ?: 0.0
            conventionalAmountEntry -> integerDigits.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
            else -> (integerDigits.toLongOrNull() ?: 0) / 100.0
        }
        return if (isNegative) -unsigned else unsigned
    }

    /**
     * Rounds to cents and drops the sign where the field can't show one —
     * those callers take their sign from the expense/income toggle, so the
     * field carries a magnitude and 50 − 80 reads as 30.00.
     */
    private fun normalized(value: Double): Double {
        val signed = if (allowsNegative) value else abs(value)
        return roundedHalfAwayFromZero(signed * 100) / 100
    }

    private fun resetOperand() {
        integerDigits = ""
        hasDecimalPoint = false
        fractionDigits = ""
        isNegative = false
    }

    /** Loads a computed result back into the digit state so it keeps behaving like typed input. */
    private fun setOperand(value: Double) {
        val rounded = normalized(value)
        val cents = roundedHalfAwayFromZero(abs(rounded) * 100).toLong()
        isNegative = allowsNegative && rounded < 0
        integerDigits = (cents / 100).toString()
        // Conventional entry never adds a fraction the user didn't type.
        hasDecimalPoint = !(conventionalAmountEntry && cents % 100 == 0L)
        fractionDigits = if (hasDecimalPoint) "%02d".format(cents % 100) else ""
    }

    private fun handleCharacter(character: Char) {
        // Hardware-keyboard minus; on screen it's the ± key.
        if (character == '-' && allowsNegative) {
            isNegative = !isNegative
            return
        }
        if (character == '.' || character == ',') {
            hasDecimalPoint = true
            return
        }
        if (!character.isDigit()) return
        if (hasDecimalPoint) {
            if (fractionDigits.length < 2) fractionDigits += character
        } else if (integerDigits.length < 10) {
            integerDigits += character
        }
    }

    private fun handleBackspace() {
        when {
            hasDecimalPoint -> if (fractionDigits.isNotEmpty()) fractionDigits = fractionDigits.dropLast(1) else hasDecimalPoint = false
            integerDigits.isNotEmpty() -> integerDigits = integerDigits.dropLast(1)
            isNegative -> isNegative = false
            pendingOperator != null -> {
                // Backspacing through an empty operand undoes the operator and
                // hands the running total back as editable digits.
                pendingOperator = null
                accumulatedValue?.let(::setOperand)
                accumulatedValue = null
            }
        }
    }

    /** Just the operand being typed, with no running total in front of it. */
    private fun operandDisplay(): String {
        val sign = if (isNegative) "-" else ""
        // A bare "-" so a sign toggled before any digits stays visible.
        if (!hasDecimalPoint && integerDigits.isEmpty()) return sign
        if (hasDecimalPoint) {
            val whole = integerDigits.ifEmpty { "0" }
            if (fractionDigits.isEmpty()) {
                return numberFormat.format("$sign$whole".toDoubleOrNull() ?: 0.0, wholeUnits = true, currencyCode = null) + numberFormat.decimalSeparator
            }
            return numberFormat.format("$sign$whole.$fractionDigits".toDoubleOrNull() ?: 0.0, wholeUnits = false, currencyCode = null)
        }
        if (conventionalAmountEntry) return numberFormat.format("$sign$integerDigits".toDoubleOrNull() ?: 0.0, wholeUnits = true, currencyCode = null)
        val dollars = (integerDigits.toLongOrNull() ?: 0) / 100.0
        return numberFormat.format(if (isNegative) -dollars else dollars, wholeUnits = false, currencyCode = null)
    }

    /** An evaluated value as the field shows it: two decimals, except in conventional entry where a whole result stays whole. */
    private fun displayValue(value: Double): String =
        numberFormat.format(value, wholeUnits = conventionalAmountEntry && value == roundedHalfAwayFromZero(value), currencyCode = null)
}
