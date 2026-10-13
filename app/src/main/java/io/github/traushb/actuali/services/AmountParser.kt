package io.github.traushb.actuali.services

/**
 * Parses amounts that arrive as text from shared messages and automations.
 *
 * Apple Wallet's transaction amount coerces to 0 when Shortcuts converts it to
 * a Number for some cards (see actuali#41, actualtap#63), but the text
 * representation carries the real value — possibly with a currency symbol,
 * currency code, and locale-specific separators ("4,00 €", "$1,234.56").
 */
object AmountParser {
    private val numberToken = Regex("-?\\d[\\d.,'’  ]*")
    private const val SEPARATORS = "-.,'’  "

    /**
     * Parses using the current format when one is known. Without a format,
     * retain the legacy separator heuristics used by automation inputs whose
     * locale is independent of the app's selected display format.
     */
    fun parse(text: String, numberFormat: ActualNumberFormat? = null): Double? {
        // Exactly one number token, or refuse: automations misconfigured to
        // pass the whole transaction can stringify with extra digits (dates,
        // "7-Eleven"), and a wrong amount is worse than an error.
        val match = numberToken.findAll(text).toList().singleOrNull() ?: return null
        val suffix = text.substring(match.range.last + 1)
        if (suffix.startsWith("-") && suffix.getOrNull(1)?.isLetter() == true) return null

        // A minus counts only if attached to the number or leading the whole
        // string — a hyphenated merchant name ("Coca-Cola") is not a sign.
        val negative = match.value.startsWith("-") || text.trim().startsWith("-")
        var token = match.value.trim { it in SEPARATORS }

        if (numberFormat != null) {
            val value = normalize(token, numberFormat)?.toDoubleOrNull() ?: return null
            return if (negative) -value else value
        }

        token = token.filterNot { it in "  '’" }
        val dot = token.lastIndexOf('.')
        val comma = token.lastIndexOf(',')
        val normalized = when {
            dot >= 0 && comma >= 0 -> {
                // Both present: the rightmost is the decimal separator.
                val (decimal, grouping) = if (dot > comma) '.' to ',' else ',' to '.'
                token.replace(grouping.toString(), "").replace(decimal, '.')
            }
            dot >= 0 -> resolveSingleSeparator(token, '.', dot)
            comma >= 0 -> resolveSingleSeparator(token, ',', comma)
            else -> token
        }
        val value = normalized.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    /**
     * Normalizes a token according to the explicitly selected Actual format.
     * This makes an otherwise ambiguous value such as `1,234` mean 1.234 in
     * `dot-comma`, while `1.234` remains 1,234 in that same format.
     */
    private fun normalize(token: String, numberFormat: ActualNumberFormat): String? {
        val decimalSeparator = numberFormat.decimalSeparator.single()
        val groupingSeparators = when (numberFormat) {
            ActualNumberFormat.CommaDot, ActualNumberFormat.CommaDotIn -> setOf(',')
            ActualNumberFormat.DotComma -> setOf('.')
            ActualNumberFormat.SpaceComma -> setOf(' ', ' ')
            ActualNumberFormat.ApostropheDot -> setOf('\'', '’')
        }

        val integerPart = token.takeWhile { it != decimalSeparator }
        if (integerPart.any { it in groupingSeparators }) {
            val groups = integerPart.split(*groupingSeparators.map(Char::toString).toTypedArray()).filter { it.isNotEmpty() }
            if (groups.size <= 1) return null
            val usesStandardGrouping = groups.drop(1).all { it.length == 3 }
            val usesIndianGrouping = groups.size > 2 &&
                groups.drop(1).dropLast(1).all { it.length == 2 } &&
                groups.last().length == 3 &&
                groups[0].length in 1..3
            val indianAllowed = numberFormat == ActualNumberFormat.CommaDot || numberFormat == ActualNumberFormat.CommaDotIn
            if (groups[0].length !in 1..3 || !(usesStandardGrouping || indianAllowed && usesIndianGrouping)) return null
        }

        var seenDecimal = false
        val normalized = StringBuilder()
        for (character in token) {
            when {
                character == decimalSeparator -> {
                    if (seenDecimal) return null
                    seenDecimal = true
                    normalized.append('.')
                }
                character in groupingSeparators -> if (seenDecimal) return null
                character in ".,'’  " -> return null
                character.isDigit() -> normalized.append(character)
                else -> return null
            }
        }
        return normalized.toString().ifEmpty { null }
    }

    /**
     * With only one separator kind present, decide decimal vs grouping:
     * repeated occurrences ("1.234.567") or a single 3-digit tail with a
     * non-zero integer part ("1,234") mean grouping; anything else ("4,00",
     * "4.5", "0,234") means decimal.
     */
    private fun resolveSingleSeparator(digits: String, separator: Char, lastIndex: Int): String {
        val occurrences = digits.count { it == separator }
        val tail = digits.substring(lastIndex + 1)
        val integerPart = digits.substring(0, digits.indexOf(separator))
        val isGrouping = occurrences > 1 || (tail.length == 3 && integerPart.isNotEmpty() && integerPart != "0")
        return digits.replace(separator.toString(), if (isGrouping) "" else ".")
    }
}
