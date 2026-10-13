package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.PendingImport
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/** Result of parsing a bank SMS / message into transaction fields. */
data class ParsedMessage(
    val amount: Double?,
    val sourceCurrencyCode: String?,
    val payee: String?,
    val cardHint: String?,
    /** Epoch millis. */
    val date: Long?,
    val isIncome: Boolean,
    val rawText: String,
) {
    fun toPendingImport(originBudgetId: String? = null): PendingImport = PendingImport(
        originBudgetId = originBudgetId,
        amount = amount,
        sourceCurrencyCode = sourceCurrencyCode,
        payee = payee,
        cardHint = cardHint,
        date = date ?: System.currentTimeMillis(),
        isIncome = isIncome,
        rawText = rawText,
    )
}

/**
 * Deterministic parser for bank notification text: regexes plus
 * [AmountParser]. iOS first tries Apple's on-device language model and falls
 * back to this same path; Android has no platform equivalent, so this is the
 * whole parser.
 */
object TransactionTextParser {
    /**
     * Unicode `\s`/`\d`/`\b`, as the ICU engine behind NSRegularExpression has.
     * Android's regex engine is ICU too: Unicode-aware already, and it rejects
     * `(?U)` as a syntax error. Only the JVM the unit tests run on needs the flag.
     */
    private val unicodeFlag = if (System.getProperty("java.vm.name") == "Dalvik") "" else "(?U)"

    // Patterns compile on first use (`by lazy`): loading pending imports at
    // launch only needs the card hint, and compiling every pattern up front
    // sat on the startup path.
    private fun regex(pattern: String, ignoreCase: Boolean = false) =
        if (ignoreCase) Regex("$unicodeFlag$pattern", RegexOption.IGNORE_CASE) else Regex("$unicodeFlag$pattern")

    private val isoCurrencies: Set<String> by lazy { Currency.getAvailableCurrencies().mapTo(HashSet()) { it.currencyCode } }

    fun parse(text: String): ParsedMessage {
        val lower = text.lowercase()
        val isIncome = "credited" in lower || "received" in lower || "refund" in lower
        return ParsedMessage(
            amount = extractAmount(text),
            sourceCurrencyCode = extractCurrencyCode(text),
            payee = extractMerchant(text),
            cardHint = extractCardHint(text),
            date = extractDate(text),
            isIncome = isIncome,
            rawText = text,
        )
    }

    // Extraction helpers

    private fun normalizeCurrencyCode(value: String?): String? {
        val code = value?.trim()?.uppercase() ?: return null
        return code.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } && it in isoCurrencies }
    }

    private val leadingCurrency by lazy { regex("""(?<!\p{L})[\(\[]?([A-Za-z]{3})(?!\p{L})[\)\]]?\s*[.:=,;\-]?\s*(?=\d)""") }
    private val trailingCurrency by lazy { regex("""\d[\d,]*(?:\.\d{1,2})?\s*[.:=,;\-]?\s*(?<!\p{L})([A-Za-z]{3})(?!\p{L})""") }

    /** Returns only currencies identified without relying on an ambiguous symbol. */
    private fun extractCurrencyCode(text: String): String? =
        currencyCode(text, leadingCurrency) ?: currencyCode(text, trailingCurrency) ?: when {
            "€" in text -> "EUR"
            "£" in text -> "GBP"
            "₹" in text -> "INR"
            else -> null
        }

    private fun currencyCode(text: String, pattern: Regex): String? = pattern.findAll(text).firstNotNullOfOrNull { match ->
        val candidate = match.groupValues[1]
        normalizeCurrencyCode(candidate)?.takeIf { isExplicitCurrencyCode(candidate) }
    }

    /** Lowercase ISO codes overlap ordinary prose ("all", "try", "pen"). */
    private fun isExplicitCurrencyCode(value: String): Boolean = value.all { it in 'A'..'Z' }

    // (pattern, amount group, ISO code group when the marker is a code)
    private val amountPatterns by lazy {
        listOf(
            Triple(regex("""(?:[\$€£₹]|\brs\.?)\s*(\d[\d,]*(?:\.\d{1,2})?)""", ignoreCase = true), 1, null),
            Triple(regex("""(\d[\d,]*(?:\.\d{1,2})?)\s*(?:[\$€£₹]|\brs\b)""", ignoreCase = true), 1, null),
            Triple(regex("""(?<!\p{L})[\(\[]?([A-Za-z]{3})(?!\p{L})[\)\]]?\s*[.:=,;\-]?\s*(\d[\d,]*(?:\.\d{1,2})?)""", ignoreCase = true), 2, 1),
            Triple(regex("""(\d[\d,]*(?:\.\d{1,2})?)\s*[.:=,;\-]?\s*(?<!\p{L})([A-Za-z]{3})(?!\p{L})""", ignoreCase = true), 1, 2),
        )
    }
    private val referencePrefix by lazy { regex("""\b(?:card|account|a/c|acct|ending|xx|x{2,})\b[^\d]*$""", ignoreCase = true) }
    private val datePrefix by lazy { regex("""\d{1,4}[-/.]\d{1,4}[-/.]$""") }

    /**
     * Extract currency amount. Requires an explicit currency marker (leading or
     * trailing) to avoid falsely capturing masked card or account numbers. When
     * multiple amounts exist (e.g. transaction amount followed by credit limit
     * or balance), selects the earliest one.
     */
    private fun extractAmount(text: String): Double? {
        var earliest: Pair<Int, Double>? = null
        for ((pattern, amountGroup, codeGroup) in amountPatterns) {
            for (match in pattern.findAll(text)) {
                val amountRange = match.groups[amountGroup] ?: continue
                val amount = AmountParser.parse(amountRange.value) ?: continue
                if (codeGroup != null) {
                    val code = match.groups[codeGroup]?.value
                    if (code != null && (normalizeCurrencyCode(code) == null || !isExplicitCurrencyCode(code))) continue
                }
                // A trailing ISO match can mistake a card suffix or date for the amount.
                if (codeGroup == 2) {
                    val prefix = text.substring(0, amountRange.range.first)
                    if (referencePrefix.containsMatchIn(prefix) || datePrefix.containsMatchIn(prefix)) continue
                }
                if (earliest == null || amountRange.range.first < earliest.first) earliest = amountRange.range.first to amount
            }
        }
        // Fall back to whole-text parse (only accepts single-number strings)
        return earliest?.second ?: AmountParser.parse(text)?.takeIf { it > 0 }
    }

    // ponytail: simple pattern covering "card ending 1234", "XX9876",
    // "A/C ...4321", "account number 4321", "a/c no 1234". Only filler words
    // and masking may sit between keyword and digits, so "Account balance
    // 1234.56" isn't a hint; the tail rejects longer runs and amounts.
    private val cardHint by lazy { regex("""(?:card|account|a/c|acct|ending|x{2,})(?:\s*(?:no\.?|number|with|in|#))*[^\dA-Za-z]{0,6}(\d{4})(?![.,]?\d)""", ignoreCase = true) }

    /** Extract the last 4 digits of a card / account number. */
    private fun extractCardHint(text: String): String? = cardHint.find(text)?.groupValues?.get(1)

    /**
     * Keeps a candidate card hint only when the text contains it; otherwise
     * falls back to the regex. Language models can transpose digits
     * (XX6419 -> "1964"), but the regex is rougher than a grounded answer.
     */
    fun resolveCardHint(candidate: String?, text: String): String? {
        val trimmed = candidate?.trim()
        if (!trimmed.isNullOrEmpty() && text.contains(trimmed, ignoreCase = true)) return trimmed
        return extractCardHint(text)
    }

    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?"""
    private val isoDate by lazy { regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""") }
    private val numericDate by lazy { regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})\b""") }
    private val dayMonthName by lazy { regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s-]*$MONTH[\s,-]*(\d{4}|\d{2})?\b""", ignoreCase = true) }
    private val monthNameDay by lazy { regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s*(\d{4}))?\b""", ignoreCase = true) }

    /**
     * The first date in the text, at local midnight.
     *
     * ponytail: covers the numeric and month-name forms bank messages use;
     * iOS's NSDataDetector also reads relative dates ("yesterday") and times.
     * Ambiguous numeric dates follow the device locale's day/month order.
     */
    private fun extractDate(text: String, locale: Locale = Locale.getDefault(), today: LocalDate = LocalDate.now()): Long? {
        val dayFirst = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()
            ?.let { it.indexOf('d') < it.indexOf('M') } ?: true
        fun year(raw: String?): Int = when {
            raw.isNullOrEmpty() -> today.year
            raw.length == 2 -> 2000 + raw.toInt()
            else -> raw.toInt()
        }
        fun month(name: String) = monthNames.indexOf(name.take(3).lowercase()) + 1

        val candidates = listOfNotNull(
            isoDate.find(text)?.let { m -> m.range.first to runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() },
            numericDate.find(text)?.let { m ->
                val (a, b) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                val (day, monthValue) = if (dayFirst) a to b else b to a
                m.range.first to runCatching { LocalDate.of(year(m.groupValues[3]), monthValue, day) }.getOrNull()
            },
            dayMonthName.find(text)?.let { m -> m.range.first to runCatching { LocalDate.of(year(m.groupValues[3]), month(m.groupValues[2]), m.groupValues[1].toInt()) }.getOrNull() },
            monthNameDay.find(text)?.let { m -> m.range.first to runCatching { LocalDate.of(year(m.groupValues[3]), month(m.groupValues[1]), m.groupValues[2].toInt()) }.getOrNull() },
        )
        val date = candidates.filter { it.second != null }.minByOrNull { it.first }?.second ?: return null
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private val merchant by lazy { regex("""\b(?:at|to|paid|merchant|vpa)\s+(?!from\b)([A-Za-z0-9\s&'.-]+?)(?:\s+(?:on|using|via|for|with|card|ref|\d{1,4}[-/.]\d{1,2}[-/.]\d{2,4}|\.|\,)|$)""", ignoreCase = true) }
    private const val TRIM = ".,;:-"

    /**
     * Extract a merchant / payee name with keyword-based patterns common to
     * bank SMS. Rejects "from" to avoid capturing funding sources ("paid from
     * Wallet"). Trailing dates act as delimiters, so hyphens can remain valid
     * in merchant names.
     *
     * ponytail: iOS also falls back to NLTagger's organization names; Android
     * has no on-device named-entity tagger, so a message without a keyword
     * yields no payee for the user to fill in.
     */
    private fun extractMerchant(text: String): String? {
        val candidate = merchant.find(text)?.groupValues?.get(1)?.trim { it.isWhitespace() || it in TRIM } ?: return null
        return candidate.takeIf(::isValidMerchantCandidate)
    }

    private fun isValidMerchantCandidate(candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        val lower = candidate.lowercase()
        // Reject candidates that are just numbers or card/account references
        val isCardReference = lower.startsWith("card") || lower.startsWith("a/c") || lower.startsWith("acct")
        val isOnlyDigitsOrPunct = candidate.all { it.isDigit() || it.isWhitespace() || isPunctuation(it) }
        return !isCardReference && !isOnlyDigitsOrPunct
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
