package io.github.traushb.actuali.services

import java.util.Locale

/**
 * Strips common payment-processor prefixes and store-number suffixes from raw
 * card-feed merchant strings, producing a normalized payee name.
 */
object MerchantNormalizer {
    /** Leading prefixes to strip, case-insensitive. */
    private val leadingPrefixes = listOf("SQ *", "TST*", "TST *", "SP ", "PAYPAL *")

    /** Trailing store-number pattern: a space, `#`, then one or more digits, to end of string. */
    private val trailingStoreNumber = Regex("\\s+#\\d+\\s*$")

    fun normalize(raw: String, locale: Locale = Locale.getDefault()): String {
        var s = raw.trim()
        // Strip leading prefix (first match wins; case-insensitive).
        leadingPrefixes.firstOrNull { s.startsWith(it, ignoreCase = true) }?.let { s = s.drop(it.length).trim() }
        s = s.replace(trailingStoreNumber, "").trim()
        return capitalizedWords(s, locale)
    }

    /**
     * `localizedCapitalized`: each word's first letter upper-cased, the rest
     * lower-cased. A letter starts a word unless it follows a letter, digit or
     * apostrophe, so "MCDONALD'S" reads "Mcdonald's" and "7-ELEVEN" "7-Eleven".
     */
    private fun capitalizedWords(s: String, locale: Locale): String {
        val out = StringBuilder(s.length)
        var previous: Char? = null
        for (ch in s) {
            val continuesWord = previous != null && (previous.isLetterOrDigit() || previous == '\'' || previous == '’')
            out.append(if (continuesWord) ch.toString().lowercase(locale) else ch.toString().uppercase(locale))
            previous = ch
        }
        return out.toString()
    }
}
