package io.github.traushb.actuali.models

import kotlin.math.abs

/**
 * Matches transactions against the free-text query from the transactions
 * search field. Text queries match payee, category, and notes; queries that
 * parse as a currency amount also match the transaction amount, ignoring
 * sign so "12.50" finds both payments and refunds.
 *
 * Digits the query leaves unspecified are treated as wildcards, so results
 * narrow progressively while typing toward an exact amount: "19" matches
 * 19.00-19.99, "19." the same, "19.0" matches 19.00-19.09, and "19.05" is
 * exact.
 */
class TransactionSearchMatcher(query: String) {
    private val query: String = query.trim { it == ' ' || it == '\t' }

    /** Absolute amounts (in cents) the query matches, when it parses as one. */
    private val amountRange: LongRange? = parseAmountRange(this.query)

    /** The trimmed query text, for callers that push matching into SQL. */
    val text: String get() = query

    /** Absolute cent range the query matches as an amount, for callers that push matching into SQL. */
    val amountCentsRange: LongRange? get() = amountRange

    fun matches(transaction: Transaction): Boolean {
        if (query.isEmpty()) return true
        if (transaction.payeeName?.contains(query, ignoreCase = true) == true ||
            transaction.categoryName?.contains(query, ignoreCase = true) == true ||
            transaction.notes?.contains(query, ignoreCase = true) == true
        ) {
            return true
        }
        return amountRange?.contains(abs(transaction.amount)) == true
    }

    private companion object {
        /**
         * Parse the query as a currency amount, e.g. "12", "12.50", "$12.50",
         * "-12,5". A separator followed by more than 2 digits (the grouping in
         * "1,234") is ambiguous, so the query is not treated as an amount.
         */
        fun parseAmountRange(input: String): LongRange? {
            var text = input.removePrefix("-")
            text = text.filterNot { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }
            text = text.trim { it == ' ' || it == '\t' }

            val separatorIndex = text.indexOfFirst { it == '.' || it == ',' }
            val integerPart: String
            val fractionPart: String
            if (separatorIndex >= 0) {
                integerPart = text.substring(0, separatorIndex)
                fractionPart = text.substring(separatorIndex + 1)
                if (fractionPart.length > 2) return null
            } else {
                integerPart = text
                fractionPart = ""
            }

            val digits = integerPart + fractionPart
            if (digits.isEmpty() || digits.length > 12 || !digits.all { it in '0'..'9' }) return null
            val base = digits.toLong()

            // One cents "slot" per untyped fraction digit: "19" -> 1900...1999,
            // "19.0" -> 1900...1909, "19.05" -> exactly 1905.
            val slot = when (fractionPart.length) {
                0 -> 100L
                1 -> 10L
                else -> 1L
            }
            val lower = base * slot
            return lower..(lower + slot - 1)
        }
    }
}
