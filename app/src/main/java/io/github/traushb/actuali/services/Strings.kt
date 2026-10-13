package io.github.traushb.actuali.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * `String(localized:)`: the catalog entry for [key] in the current locale,
 * formatted with [args]. Keys are the English source text, exactly as in the
 * iOS String Catalogs these tables are generated from, placeholders included
 * (`"Due in %lldd"`).
 */
fun L(key: String, vararg args: Any?): String = Strings.format(key, args, Locale.getDefault())

/**
 * Lookup over the per-locale tables `generateStringCatalogs` derives from the
 * iOS `.xcstrings` catalogs, so both apps translate from one source.
 */
object Strings {
    /** Opens `strings/<tag>.json`. The app swaps in its assets; JVM tests read the classpath. */
    @Volatile
    var opener: (String) -> InputStream? = { tag -> Strings::class.java.getResourceAsStream("/strings/$tag.json") }

    private val tables = ConcurrentHashMap<String, Map<String, JsonElement>>()

    /** The catalog's languages; anything else falls back to the English source. */
    private val supported = setOf("en", "de", "es", "fr", "it", "nl")

    fun tag(locale: Locale): String = when (val language = locale.language) {
        "pt" -> "pt-BR"
        in supported -> language
        else -> "en"
    }

    // computeIfAbsent rather than getOrPut: a caller that arrives while another
    // thread parses the table (the launch warm-up) waits for that parse instead
    // of running a second one.
    private fun table(tag: String): Map<String, JsonElement> = tables.computeIfAbsent(tag) {
        val text = runCatching { opener(tag)?.use { it.readBytes().decodeToString() } }.getOrNull()
        text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: emptyMap()
    }

    /** Parses the tables [locale] reads ahead of the first lookup — from a background thread at launch. */
    fun preload(locale: Locale = Locale.getDefault()) {
        table(tag(locale))
        table("en")
    }

    private fun entry(key: String, locale: Locale): JsonElement? =
        table(tag(locale))[key] ?: table("en")[key]

    /** The translated format string for [key], resolving plurals against [count]. */
    fun text(key: String, locale: Locale = Locale.getDefault(), count: Number? = null): String =
        when (val entry = entry(key, locale)) {
            is JsonPrimitive -> entry.content
            is JsonObject -> {
                val category = count?.let { PluralRules.category(it, locale) } ?: "other"
                (entry[category] ?: entry["other"] ?: entry.values.firstOrNull())
                    ?.let { (it as? JsonPrimitive)?.content } ?: key
            }
            else -> key
        }

    fun format(key: String, args: Array<out Any?>, locale: Locale = Locale.getDefault()): String {
        val count = args.firstOrNull { it is Int || it is Long }?.let { it as Number }
        val template = text(key, locale, count)
        return if (args.isEmpty()) template.replace("%%", "%") else AppleFormat.format(template, args, locale)
    }
}

/**
 * CLDR cardinal rules for the catalog's languages. Hand-written rather than
 * `android.icu` so JVM tests resolve the same plural as a device.
 */
object PluralRules {
    fun category(count: Number, locale: Locale): String {
        val n = count.toLong()
        return when (Strings.tag(locale)) {
            "fr", "pt-BR" -> when {
                n == 0L || n == 1L -> "one"
                n != 0L && n % 1_000_000L == 0L -> "many"
                else -> "other"
            }
            "es", "it" -> when {
                n == 1L -> "one"
                n != 0L && n % 1_000_000L == 0L -> "many"
                else -> "other"
            }
            else -> if (n == 1L) "one" else "other"
        }
    }
}

/**
 * Apple's `String(format:)` placeholders (`%@`, `%lld`, `%1$@`, `%.2f`, `%g`)
 * over Kotlin values. Hand-rolled because `java.util.Formatter` has no `%@`
 * and its `%g` doesn't trim trailing zeros the way C's does.
 */
object AppleFormat {
    // No space flag and no %a/%p: the catalog never uses them, and prose like
    // "100% and" must not read as a placeholder.
    private val placeholder = Regex("%(?:(\\d+)\\$)?([-+0#]*)(\\d+)?(?:\\.(\\d+))?(hh|h|ll|l|q|z|t|j|L)?([@dDiuUxXoOfFeEgGcCsS%])")

    fun format(template: String, args: Array<out Any?>, locale: Locale): String {
        var next = 0
        return placeholder.replace(template) { match ->
            val conversion = match.groupValues[6]
            if (conversion == "%") return@replace "%"
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            val arg = args.getOrNull(index)
            val flags = match.groupValues[2]
            val width = match.groupValues[3]
            val precision = match.groupValues[4].toIntOrNull()
            when (conversion) {
                "@", "s", "S" -> arg?.toString() ?: "(null)"
                "d", "D", "i", "u", "U" -> String.format(locale, "%${flags}${width}d", (arg as? Number)?.toLong() ?: 0L)
                "x", "X", "o", "O" -> String.format(locale, "%${flags}${width}${conversion.lowercase()}", (arg as? Number)?.toLong() ?: 0L)
                "f", "F" -> String.format(locale, "%${flags}${width}.${precision ?: 6}f", (arg as? Number)?.toDouble() ?: 0.0)
                "e", "E" -> String.format(locale, "%${flags}${width}.${precision ?: 6}$conversion", (arg as? Number)?.toDouble() ?: 0.0)
                "g", "G" -> shortest((arg as? Number)?.toDouble() ?: 0.0, precision ?: 6, locale)
                "c", "C" -> (arg as? Char)?.toString() ?: arg?.toString().orEmpty()
                else -> arg?.toString().orEmpty()
            }
        }
    }

    /** C's `%g`: [significant] digits, trailing zeros dropped. */
    private fun shortest(value: Double, significant: Int, locale: Locale): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(java.math.MathContext(maxOf(significant, 1)))
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent < -4 || exponent >= maxOf(significant, 1)) {
            return String.format(locale, "%.${maxOf(significant - 1, 0)}e", value)
                .replace(Regex("\\.?0+e"), "e")
        }
        val plain = rounded.stripTrailingZeros().toPlainString()
        val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') plain else plain.replace('.', separator)
    }
}

/** Mirrors the iOS `ReportStrings` helpers: catalog lookups with an explicit locale. */
object ReportStrings {
    fun text(key: String, locale: Locale? = null): String = Strings.text(key, locale ?: Locale.getDefault())

    fun format(key: String, vararg args: Any?, locale: Locale? = null): String =
        Strings.format(key, args, locale ?: Locale.getDefault())

    fun localized(key: String, vararg args: Any?, locale: Locale? = null): String =
        Strings.format(key, args, locale ?: Locale.getDefault())

    fun yearsToRetire(years: Double, locale: Locale? = null): String =
        localized("%g years", years, locale = locale)
}
