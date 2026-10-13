package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

/**
 * Hand-rolled port of loot-core's `goal-template.pegjs` grammar. Same
 * ordered-choice semantics as peggy: alternatives are tried top to bottom,
 * each must consume the entire line, and the first full match wins.
 *
 * Whitespace mirrors the grammar's `_` rule ([ \t]*, i.e. optional), so the
 * same slightly-permissive inputs upstream accepts ("#template 3days…",
 * "repeatevery") parse identically here.
 */
object GoalTemplateParser {
    class ParseError(message: String) : Exception(message)

    /**
     * Parse one `#template…` / `#goal…` line (already trimmed to start at the
     * `#`). Throws on lines that match no grammar alternative.
     */
    fun parse(line: String, locale: Locale = Locale.getDefault()): GoalTemplate {
        val scanner = Scanner(line)

        // `#goal`i amount
        if (scanner.matchLiteral("#goal", caseInsensitive = true)) {
            val amount = scanner.amount()
            if (amount == null || !scanner.isAtEnd) throw ParseError(ReportStrings.text("Invalid #goal syntax", locale))
            return GoalTemplate(GoalTemplate.Kind.Goal, GoalTemplate.Directive.Goal, null, amount = amount)
        }

        // `#template` is case-sensitive upstream.
        if (!scanner.matchLiteral("#template", caseInsensitive = false)) {
            throw ParseError(ReportStrings.text("Line is not a template", locale))
        }
        // priority = '-' number; absent coerces to 0 (upstream's `+null`).
        var priority = 0
        if (scanner.matchLiteral("-", caseInsensitive = false)) {
            priority = scanner.number() ?: throw ParseError(ReportStrings.text("Invalid priority", locale))
        }

        val alternatives: List<(Scanner) -> GoalTemplate?> = listOf(
            ::parsePercentage, ::parsePeriodic, ::parseByOrSpend, ::parseSimpleWithAmount,
            ::parseSimpleLimitOnly, ::parseSchedule, ::parseRemainder, ::parseAverage, ::parseCopy,
        )
        for (alternative in alternatives) {
            val attempt = scanner.copy()
            val template = alternative(attempt)
            if (template != null && attempt.isAtEnd) {
                template.priority = if (template.type == GoalTemplate.Kind.Remainder) null else priority
                return template
            }
        }
        throw ParseError(ReportStrings.text("Invalid template syntax", locale))
    }

    // Alternatives (bodies after the `#template[-N]` prefix)

    /** percent '%' of ['previous'] name */
    private fun parsePercentage(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        val percent = s.percent() ?: return null
        s.skipWhitespace()
        if (!s.matchLiteral("of", caseInsensitive = true)) return null
        s.skipWhitespace()
        var previous = false
        // Grammar has only optional whitespace after 'previous', so "of
        // previousSalary" parses as previous + "Salary" upstream too.
        if (s.matchLiteral("previous", caseInsensitive = true)) {
            previous = true
            s.skipWhitespace()
        }
        val name = s.restOfLine().takeIf { it.isNotEmpty() } ?: return null
        return GoalTemplate(GoalTemplate.Kind.Percentage, GoalTemplate.Directive.Template, percent = percent, previous = previous, category = name)
    }

    /** amount 'repeat every' periodCount 'starting' date [limit] */
    private fun parsePeriodic(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        val amount = s.amount() ?: return null
        s.skipWhitespace()
        if (!s.matchLiteral("repeat", caseInsensitive = true)) return null
        s.skipWhitespace()
        if (!s.matchLiteral("every", caseInsensitive = true)) return null
        s.skipWhitespace()
        val period = s.periodCount() ?: return null
        s.skipWhitespace()
        if (!s.matchLiteral("starting", caseInsensitive = true)) return null
        s.skipWhitespace()
        val starting = s.date() ?: return null
        val limit = s.limit()
        return GoalTemplate(GoalTemplate.Kind.Periodic, GoalTemplate.Directive.Template, amount = amount, period = period, starting = starting, limit = limit)
    }

    /** amount 'by' month ['spend from' month] ['repeat every' repeat] */
    private fun parseByOrSpend(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        val amount = s.amount() ?: return null
        s.skipWhitespace()
        if (!s.matchLiteral("by", caseInsensitive = true)) return null
        s.skipWhitespace()
        val month = s.month() ?: return null

        var from: String? = null
        var attempt = s.copy()
        attempt.skipWhitespace()
        if (attempt.matchLiteral("spend", caseInsensitive = true)) {
            attempt.skipWhitespace()
            if (attempt.matchLiteral("from", caseInsensitive = true)) {
                attempt.skipWhitespace()
                attempt.month()?.let { fromMonth ->
                    from = fromMonth
                    s.restore(attempt)
                }
            }
        }

        var annual: Boolean? = null
        var repeatCount: Int? = null
        attempt = s.copy()
        attempt.skipWhitespace()
        if (attempt.matchLiteral("repeat", caseInsensitive = true)) {
            attempt.skipWhitespace()
            if (attempt.matchLiteral("every", caseInsensitive = true)) {
                attempt.skipWhitespace()
                attempt.repeatInterval()?.let { (isAnnual, count) ->
                    annual = isAnnual
                    repeatCount = count
                    s.restore(attempt)
                }
            }
        }

        return GoalTemplate(
            if (from != null) GoalTemplate.Kind.Spend else GoalTemplate.Kind.By,
            GoalTemplate.Directive.Template,
            amount = amount,
            month = month,
            from = from,
            annual = annual,
            repeatCount = repeatCount,
        )
    }

    /** amount [limit] */
    private fun parseSimpleWithAmount(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        val monthly = s.amount() ?: return null
        return GoalTemplate(GoalTemplate.Kind.Simple, GoalTemplate.Directive.Template, monthly = monthly, limit = s.limit())
    }

    /** limit only (monthly = null) */
    private fun parseSimpleLimitOnly(s: Scanner): GoalTemplate? {
        val limit = s.limit() ?: return null
        return GoalTemplate(GoalTemplate.Kind.Simple, GoalTemplate.Directive.Template, limit = limit)
    }

    /** 'schedule' ['full'] name [modifiers] */
    private fun parseSchedule(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        if (!s.matchLiteral("schedule", caseInsensitive = true)) return null
        s.skipWhitespace()
        var full = false
        // Upstream's `full = 'full'i _` — no word boundary, so a schedule named
        // "Fuller House" loses its prefix there too. Kept identical.
        if (s.matchLiteral("full", caseInsensitive = true)) {
            full = true
            s.skipWhitespace()
        }
        val (name, modifiers) = s.scheduleNameAndModifiers() ?: return null
        if (name.isEmpty()) return null
        return GoalTemplate(
            GoalTemplate.Kind.Schedule,
            GoalTemplate.Directive.Template,
            name = name,
            full = if (full) true else null,
            adjustment = modifiers?.first,
            adjustmentType = modifiers?.second,
        )
    }

    /** 'remainder' [weight] [limit] */
    private fun parseRemainder(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        if (!s.matchLiteral("remainder", caseInsensitive = true)) return null
        s.skipWhitespace()
        val weight = s.positive() ?: 1
        return GoalTemplate(GoalTemplate.Kind.Remainder, GoalTemplate.Directive.Template, weight = weight.toDouble(), limit = s.limit())
    }

    /** 'average' positive ['months'] [modifiers] */
    private fun parseAverage(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        if (!s.matchLiteral("average", caseInsensitive = true)) return null
        s.skipWhitespace()
        val numMonths = s.positive() ?: return null
        s.skipWhitespace()
        s.matchLiteral("months", caseInsensitive = true)
        val modifiers = s.modifiers()
        return GoalTemplate(
            GoalTemplate.Kind.Average,
            GoalTemplate.Directive.Template,
            numMonths = numMonths,
            adjustment = modifiers?.first,
            adjustmentType = modifiers?.second,
        )
    }

    /** 'copy from' positive 'months ago' [limit] */
    private fun parseCopy(s: Scanner): GoalTemplate? {
        s.skipWhitespace()
        if (!s.matchLiteral("copy from", caseInsensitive = true)) return null
        s.skipWhitespace()
        val lookBack = s.positive() ?: return null
        s.skipWhitespace()
        if (!s.matchLiteral("months ago", caseInsensitive = true)) return null
        return GoalTemplate(GoalTemplate.Kind.Copy, GoalTemplate.Directive.Template, lookBack = lookBack, limit = s.limit())
    }

    /** A cursor over one line; `copy()`/`restore()` stand in for the Swift value-type copies. */
    class Scanner(private val characters: String, private var position: Int = 0) {
        fun copy() = Scanner(characters, position)

        fun restore(other: Scanner) {
            position = other.position
        }

        val isAtEnd: Boolean get() = position >= characters.length

        private fun peek(offset: Int = 0): Char? = characters.getOrNull(position + offset)

        /** `_` in the grammar: zero or more spaces/tabs. */
        fun skipWhitespace() {
            while (peek().let { it == ' ' || it == '\t' }) position += 1
        }

        fun matchLiteral(literal: String, caseInsensitive: Boolean): Boolean {
            if (position + literal.length > characters.length) return false
            for ((offset, expected) in literal.withIndex()) {
                val actual = characters[position + offset]
                val matches = if (caseInsensitive) actual.lowercaseChar() == expected.lowercaseChar() else actual == expected
                if (!matches) return false
            }
            position += literal.length
            return true
        }

        private fun digits(min: Int = 1, max: Int = Int.MAX_VALUE): String? {
            val result = StringBuilder()
            while (result.length < max) {
                val c = peek() ?: break
                if (c !in '0'..'9') break
                result.append(c)
                position += 1
            }
            if (result.length < min) {
                position -= result.length
                return null
            }
            return result.toString()
        }

        fun number(): Int? = digits()?.toIntOrNull()

        /** `[1-9][0-9]*` */
        fun positive(): Int? {
            val c = peek() ?: return null
            if (c !in '1'..'9') return null
            return number()
        }

        /** currencySymbol? _? '-'? digits ('.' digit{0,2})? */
        fun amount(): Double? {
            val start = position
            val first = peek()
            if (first != null && Character.getType(first) == Character.CURRENCY_SYMBOL.toInt()) position += 1
            skipWhitespace()
            val text = StringBuilder()
            if (peek() == '-') {
                text.append('-')
                position += 1
            }
            val whole = digits()
            if (whole == null) {
                position = start
                return null
            }
            text.append(whole)
            if (peek() == '.') {
                position += 1
                text.append('.').append(digits(min = 0, max = 2) ?: "")
            }
            // Trailing-dot inputs ("5.") coerce like JS `+'5.'`.
            return text.toString().removeSuffix(".").toDoubleOrNull()
        }

        /** digits ('.' digits)? _? '%' */
        fun percent(): Double? {
            val start = position
            val text = StringBuilder(digits() ?: return null)
            if (peek() == '.') {
                position += 1
                digits()?.let { text.append('.').append(it) }
            }
            skipWhitespace()
            if (peek() != '%') {
                position = start
                return null
            }
            position += 1
            return text.toString().toDoubleOrNull()
        }

        /** Exactly `dddd-dd`. */
        fun month(): String? {
            val start = position
            val year = digits(min = 4, max = 4)
            if (year == null || peek() != '-') {
                position = start
                return null
            }
            position += 1
            val monthDigits = digits(min = 2, max = 2)
            if (monthDigits == null) {
                position = start
                return null
            }
            return "$year-$monthDigits"
        }

        /** Exactly `dddd-dd-dd`. */
        fun date(): String? {
            val start = position
            val monthPart = month()
            if (monthPart == null || peek() != '-') {
                position = start
                return null
            }
            position += 1
            val day = digits(min = 2, max = 2)
            if (day == null) {
                position = start
                return null
            }
            return "$monthPart-$day"
        }

        fun restOfLine(): String {
            val result = StringBuilder()
            while (true) {
                val c = peek() ?: break
                if (c == '\r' || c == '\n' || c == '\t') break
                result.append(c)
                position += 1
            }
            return result.toString()
        }

        /**
         * Peg's bare 'day'/'week'/'year' literals happily match the prefix of
         * "days"/"weeks"/"years" and then fail the sequence as a whole; the
         * N-suffixed alternatives require a leading number, so a bare plural
         * never parses upstream either.
         */
        private fun nextIsLetter(letter: Char): Boolean = peek()?.lowercaseChar() == letter.lowercaseChar()

        /** periodCount: day | N days | week | N weeks | N months | year | N years */
        fun periodCount(): GoalTemplate.Period? {
            if (matchLiteral("day", true) && !nextIsLetter('s')) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Day, 1)
            if (matchLiteral("week", true) && !nextIsLetter('s')) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Week, 1)
            if (matchLiteral("year", true) && !nextIsLetter('s')) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Year, 1)
            val start = position
            val n = number() ?: return null
            skipWhitespace()
            if (matchLiteral("days", true)) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Day, n)
            if (matchLiteral("weeks", true)) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Week, n)
            if (matchLiteral("months", true)) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Month, n)
            if (matchLiteral("years", true)) return GoalTemplate.Period(GoalTemplate.PeriodUnit.Year, n)
            position = start
            return null
        }

        /** repeat: month | N months | year | N years */
        fun repeatInterval(): Pair<Boolean, Int?>? {
            if (matchLiteral("month", true) && !nextIsLetter('s')) return false to null
            if (matchLiteral("year", true) && !nextIsLetter('s')) return true to null
            val start = position
            val n = positive() ?: return null
            skipWhitespace()
            if (matchLiteral("months", true)) return false to n
            if (matchLiteral("years", true)) return true to n
            position = start
            return null
        }

        /** limit: 'up to' amount ['per week starting' date | 'per day'] ['hold'] */
        fun limit(): GoalTemplate.Limit? {
            val start = position
            skipWhitespace()
            if (!matchLiteral("up", true)) {
                position = start
                return null
            }
            skipWhitespace()
            if (!matchLiteral("to", true)) {
                position = start
                return null
            }
            skipWhitespace()
            val amount = amount()
            if (amount == null) {
                position = start
                return null
            }

            var attempt = copy()
            attempt.skipWhitespace()
            if (attempt.matchLiteral("per week starting", true)) {
                attempt.skipWhitespace()
                val startDate = attempt.date()
                if (startDate != null) {
                    attempt.skipWhitespace()
                    val hold = attempt.matchLiteral("hold", true)
                    restore(attempt)
                    return GoalTemplate.Limit(amount, hold, GoalTemplate.LimitPeriod.Weekly, startDate)
                }
            }

            attempt = copy()
            attempt.skipWhitespace()
            if (attempt.matchLiteral("per day", true)) {
                attempt.skipWhitespace()
                val hold = attempt.matchLiteral("hold", true)
                restore(attempt)
                return GoalTemplate.Limit(amount, hold, GoalTemplate.LimitPeriod.Daily, null)
            }

            skipWhitespace()
            val hold = matchLiteral("hold", true)
            return GoalTemplate.Limit(amount, hold, GoalTemplate.LimitPeriod.Monthly, null)
        }

        /** modifiers: '[' ('increase'|'decrease') value['%'] ']' */
        fun modifiers(): Pair<Double, GoalTemplate.AdjustmentType>? {
            val start = position
            skipWhitespace()
            if (!matchLiteral("[", false)) {
                position = start
                return null
            }
            val sign = when {
                matchLiteral("increase", true) -> 1.0
                matchLiteral("decrease", true) -> -1.0
                else -> {
                    position = start
                    return null
                }
            }
            skipWhitespace()
            val whole = digits()
            if (whole == null) {
                position = start
                return null
            }
            val text = StringBuilder(whole)
            if (peek() == '.') {
                position += 1
                digits()?.let { text.append('.').append(it) }
            }
            // The grammar allows whitespace before '%' but requires ']'
            // immediately after the value/percent — "[increase 5 ]" fails.
            val afterValue = copy()
            afterValue.skipWhitespace()
            val isPercent = afterValue.matchLiteral("%", false)
            if (isPercent) restore(afterValue)
            val value = text.toString().toDoubleOrNull()
            if (!matchLiteral("]", false) || value == null) {
                position = start
                return null
            }
            return sign * value to if (isPercent) GoalTemplate.AdjustmentType.Percent else GoalTemplate.AdjustmentType.Fixed
        }

        /**
         * rawScheduleName + optional trailing modifiers: everything up to a
         * `[increase…]`/`[decrease…]` block (or end of line), trimmed.
         */
        fun scheduleNameAndModifiers(): Pair<String, Pair<Double, GoalTemplate.AdjustmentType>?>? {
            val first = peek() ?: return null
            if (first == ' ' || first == '\t' || first == '\r' || first == '\n') return null
            val name = StringBuilder()
            while (!isAtEnd) {
                val attempt = copy()
                val modifiers = attempt.modifiers()
                if (modifiers != null) {
                    restore(attempt)
                    return name.toString().trim { it == ' ' || it == '\t' } to modifiers
                }
                val next = peek()
                if (next == null || next == '\r' || next == '\n') break
                name.append(next)
                position += 1
            }
            return name.toString().trim { it == ' ' || it == '\t' } to null
        }
    }
}

/**
 * Port of loot-core `template-notes.ts` `getCategoriesWithTemplates`: pull
 * template lines out of one category note, keeping non-directive lines
 * directly above a template line as its description.
 */
object GoalTemplateNotes {
    const val TEMPLATE_PREFIX = "#template"
    const val GOAL_PREFIX = "#goal"
    const val CLEANUP_PREFIX = "#cleanup"

    fun parseTemplates(note: String): List<GoalTemplate> {
        val templates = mutableListOf<GoalTemplate>()
        var descriptionLines = mutableListOf<String>()

        for (line in note.split("\n")) {
            // Upstream takes `line.substring(line.indexOf('#'))` — the whole
            // line when there is no '#' (JS substring(-1) starts at 0).
            val hashIndex = line.indexOf('#')
            val trimmedLine = (if (hashIndex >= 0) line.substring(hashIndex) else line).trim()
            val isTemplateLine = trimmedLine.startsWith(TEMPLATE_PREFIX) || trimmedLine.startsWith(GOAL_PREFIX)

            if (!isTemplateLine) {
                if (line.isBlank() || trimmedLine.startsWith(CLEANUP_PREFIX)) {
                    descriptionLines = mutableListOf()
                } else {
                    descriptionLines += line.trimEnd { it == ' ' || it == '\t' || it == '\r' }
                }
                continue
            }

            val description = if (descriptionLines.isEmpty()) null else descriptionLines.joinToString("\n")
            descriptionLines = mutableListOf()

            val template = try {
                val parsed = GoalTemplateParser.parse(trimmedLine)
                // Adjustment bounds check, mirroring template-notes.ts.
                val adjustment = parsed.adjustment
                if ((parsed.type == GoalTemplate.Kind.Average || parsed.type == GoalTemplate.Kind.Schedule) &&
                    adjustment != null && parsed.adjustmentType == GoalTemplate.AdjustmentType.Percent &&
                    (adjustment <= -100 || adjustment > 1000)
                ) {
                    GoalTemplate(
                        GoalTemplate.Kind.Error,
                        GoalTemplate.Directive.Error,
                        line = line,
                        error = L("Invalid adjustment percentage (%@%). Must be between -100% and 1000%", AutomationSentences.trimTrailingZeros(adjustment)),
                    )
                } else {
                    parsed
                }
            } catch (e: GoalTemplateParser.ParseError) {
                GoalTemplate(GoalTemplate.Kind.Error, GoalTemplate.Directive.Error, line = line, error = e.message ?: L("Invalid template syntax"))
            }
            template.description = description
            templates += template
        }
        return templates
    }

    /** Whether a note contains any template/goal directive at all — the cheap gate before parsing. */
    fun noteHasTemplates(note: String): Boolean {
        val lowered = note.lowercase()
        return lowered.contains(TEMPLATE_PREFIX) || lowered.contains(GOAL_PREFIX)
    }
}
