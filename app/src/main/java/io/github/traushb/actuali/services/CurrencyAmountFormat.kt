package io.github.traushb.actuali.services

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Number formats supported by Actual's synced `numberFormat` preference. */
enum class ActualNumberFormat(val rawValue: String) {
    CommaDot("comma-dot"),
    DotComma("dot-comma"),
    SpaceComma("space-comma"),
    ApostropheDot("apostrophe-dot"),
    CommaDotIn("comma-dot-in"),
    ;

    val id: String get() = rawValue

    val example: String
        get() = when (this) {
            CommaDot -> "1,000.33"
            DotComma -> "1.000,33"
            SpaceComma -> "1 000,33"
            ApostropheDot -> "1’000.33"
            CommaDotIn -> "10,00,000.33"
        }

    val decimalSeparator: String
        get() = when (this) {
            CommaDot, ApostropheDot, CommaDotIn -> "."
            DotComma, SpaceComma -> ","
        }

    private val groupingSeparator: String
        get() = when (this) {
            CommaDot, CommaDotIn -> ","
            DotComma -> "."
            SpaceComma -> " "
            ApostropheDot -> "’"
        }

    /**
     * Digits grouped the way the iOS app's locale-pinned NumberFormatter
     * groups them (en_US, de_DE, fr_FR, de_CH, en_IN). Done by hand because
     * the JVM's DecimalFormat can't do en_IN's lakh grouping.
     */
    private fun group(integerDigits: String): String {
        if (integerDigits.length <= 3) return integerDigits
        // The last three digits, then groups of [size] leftward from them.
        val head = integerDigits.length - 3
        val size = if (this == CommaDotIn) 2 else 3
        val separator = groupingSeparator
        val out = StringBuilder(integerDigits.length * 2)
        var start = head % size
        if (start == 0) start = size
        out.append(integerDigits, 0, start)
        while (start < head) {
            out.append(separator).append(integerDigits, start, start + size)
            start += size
        }
        return out.append(separator).append(integerDigits, head, integerDigits.length).toString()
    }

    /**
     * [number] formatted in this style. With a [currencyCode] the fraction
     * digits follow that currency (JPY has none) and the result is digits
     * only — the caller splices them into a currency string.
     */
    fun format(number: Double, wholeUnits: Boolean, currencyCode: String?): String {
        if (currencyCode == null && number == 0.0) {
            return if (wholeUnits) "0" else "0${decimalSeparator}00"
        }
        val fractionDigits = when {
            wholeUnits -> 0
            currencyCode != null -> currencyFractionDigits(currencyCode)
            else -> 2
        }
        val rounded = BigDecimal.valueOf(number).setScale(fractionDigits, RoundingMode.HALF_EVEN)
        val plain = rounded.abs().toPlainString()
        val integerPart = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")
        val body = group(integerPart) + if (fraction.isEmpty()) "" else decimalSeparator + fraction
        return if (rounded.signum() < 0) "-$body" else body
    }

    companion object {
        fun resolved(raw: String?): ActualNumberFormat = entries.firstOrNull { it.rawValue == raw } ?: CommaDot

        fun currencyFractionDigits(code: String): Int =
            runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2
    }
}

object CurrencyAmountFormat {
    /**
     * @param cents Signed amount in cents (e.g., 1050 = $10.50).
     * @param currencyCode ISO code; empty means no currency — amounts render
     *   as plain numbers, matching Actual's defaultCurrencyCode convention.
     * @param narrowSymbol Use the narrow symbol ("$" instead of "NZ$"/"US$"),
     *   the Settings "Symbol Only" option (GH #83).
     * @param wholeUnits Round to whole units, for compact chart annotations
     *   where cents add noise.
     * @param numberFormat Actual's synced number format preference.
     */
    fun string(
        cents: Long,
        currencyCode: String,
        narrowSymbol: Boolean,
        wholeUnits: Boolean = false,
        numberFormat: ActualNumberFormat = ActualNumberFormat.CommaDot,
        locale: Locale = Locale.getDefault(),
    ): String {
        val amount = cents / 100.0
        if (currencyCode.isEmpty()) {
            return numberFormat.format(amount, wholeUnits, null)
        }
        val numericString = numberFormat.format(abs(amount), wholeUnits, currencyCode).trim { !it.isDigit() }
        val (prefix, suffix) = layout(currencyCode, narrowSymbol, wholeUnits, amount < 0, locale)
        return prefix + numericString + suffix
    }

    private data class LayoutKey(val currencyCode: String, val narrowSymbol: Boolean, val wholeUnits: Boolean, val negative: Boolean, val locale: Locale)

    private val layouts = ConcurrentHashMap<LayoutKey, Pair<String, String>>()

    /**
     * What the locale puts around the digits — symbol, spacing, sign — which
     * depends on these alone, never on the amount. Worked out once per
     * combination from a sample amount: building a currency NumberFormat for
     * every amount on screen showed up in each row's frame time.
     */
    private fun layout(currencyCode: String, narrowSymbol: Boolean, wholeUnits: Boolean, negative: Boolean, locale: Locale): Pair<String, String> =
        layouts.getOrPut(LayoutKey(currencyCode, narrowSymbol, wholeUnits, negative, locale)) {
            val sample = currencyString(if (negative) -1.0 else 1.0, currencyCode, narrowSymbol, wholeUnits, locale)
            val first = sample.indexOfFirst { it.isDigit() }
            val last = sample.indexOfLast { it.isDigit() }
            if (first < 0) sample to "" else sample.substring(0, first) to sample.substring(last + 1)
        }

    fun string(
        cents: Int,
        currencyCode: String,
        narrowSymbol: Boolean,
        wholeUnits: Boolean = false,
        numberFormat: ActualNumberFormat = ActualNumberFormat.CommaDot,
        locale: Locale = Locale.getDefault(),
    ): String = string(cents.toLong(), currencyCode, narrowSymbol, wholeUnits, numberFormat, locale)

    /** The locale's currency layout (symbol, spacing, sign); its digits get replaced. */
    private fun currencyString(
        amount: Double,
        currencyCode: String,
        narrowSymbol: Boolean,
        wholeUnits: Boolean,
        locale: Locale,
    ): String {
        val format = NumberFormat.getCurrencyInstance(locale) as? DecimalFormat
            ?: return "$currencyCode $amount"
        val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        val symbols = format.decimalFormatSymbols
        if (currency != null) {
            format.currency = currency
            if (narrowSymbol) {
                symbols.currencySymbol = narrow(currency.getSymbol(locale), currencyCode)
                format.decimalFormatSymbols = symbols
            }
        } else {
            // Not an ISO code: show it as written, the way Apple's formatter does.
            symbols.currencySymbol = currencyCode
            format.decimalFormatSymbols = symbols
            format.minimumFractionDigits = 2
            format.maximumFractionDigits = 2
        }
        if (wholeUnits) {
            format.minimumFractionDigits = 0
            format.maximumFractionDigits = 0
        }
        format.roundingMode = RoundingMode.HALF_EVEN
        return format.format(amount)
    }

    /**
     * CLDR's narrow symbol for the common "NZ$"/"CA$"/"CN¥" shapes: the
     * glyph without its country prefix.
     *
     * ponytail: a prefix-stripping heuristic, not CLDR's narrow-symbol table
     * (the JVM exposes none). BRL keeps "R$" as CLDR does; rarer currencies
     * whose narrow form differs keep their regular symbol. Upgrade path:
     * android.icu.number.NumberFormatter with UnitWidth.NARROW on API 30+.
     */
    private fun narrow(symbol: String, code: String): String {
        if (code == "BRL") return symbol
        val stripped = symbol.trimStart { it in 'A'..'Z' }
        return if (stripped.isNotEmpty() && stripped.length < symbol.length && stripped.none { it.isLetter() }) stripped else symbol
    }
}
