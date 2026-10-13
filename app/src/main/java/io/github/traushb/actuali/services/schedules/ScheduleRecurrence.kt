package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.services.boolOrNull
import io.github.traushb.actuali.services.longOrNullValue
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max

/** Parsed schedule recurrence config, mirroring loot-core's `_conditions.date` recurring value. */
data class RecurConfig(
    val frequency: Frequency,
    val interval: Int,
    val start: DayDate,
    val patterns: List<Pattern>,
    val skipWeekend: Boolean,
    /** "before" | "after". */
    val weekendSolveMode: String,
    /** Unknown values behave as "never" (matches the JS switch default). */
    val endMode: String,
    val endOccurrences: Int?,
    val endDate: DayDate?,
) {
    enum class Frequency(val rawValue: String) {
        Daily("daily"),
        Weekly("weekly"),
        Monthly("monthly"),
        Yearly("yearly"),
        ;

        companion object {
            fun fromRaw(raw: String?): Frequency? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /** type: "day" | "SU".."SA". */
    data class Pattern(val type: String, val value: Int)

    /**
     * Serialize back into the shape loot-core stores inside a rule's date
     * condition, mirroring the picker's `unparseConfig`: interval and
     * occurrence counts are clamped to sane positives at the boundary, and
     * every field the type models is emitted so a config written here round
     * trips through the web unchanged.
     */
    val json: JsonObject
        get() {
            val json = linkedMapOf<String, JsonElement>(
                "start" to JsonPrimitive(start.iso),
                "frequency" to JsonPrimitive(frequency.rawValue),
                "interval" to JsonPrimitive(max(1, interval)),
                "skipWeekend" to JsonPrimitive(skipWeekend),
                "weekendSolveMode" to JsonPrimitive(weekendSolveMode),
                "endMode" to JsonPrimitive(endMode),
            )
            // Patterns only mean anything for monthly recurrences; upstream omits
            // the key entirely otherwise.
            if (patterns.isNotEmpty()) {
                json["patterns"] = JsonArray(
                    patterns.map { JsonObject(mapOf("type" to JsonPrimitive(it.type), "value" to JsonPrimitive(it.value))) },
                )
            }
            endOccurrences?.let { json["endOccurrences"] = JsonPrimitive(max(1, it)) }
            endDate?.let { json["endDate"] = JsonPrimitive(it.iso) }
            return JsonObject(json)
        }

    companion object {
        private val weekdayCodes = setOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

        /**
         * Build a config directly. [parse] stays the parsing entry point; this
         * is what the editor writes through.
         */
        fun make(
            frequency: Frequency,
            interval: Int = 1,
            start: DayDate,
            patterns: List<Pattern> = emptyList(),
            skipWeekend: Boolean = false,
            weekendSolveMode: String = "after",
            endMode: String = "never",
            endOccurrences: Int? = null,
            endDate: DayDate? = null,
        ): RecurConfig = RecurConfig(
            frequency = frequency,
            interval = max(1, interval),
            start = start,
            // Patterns only mean anything for a monthly recurrence; carrying them
            // on any other frequency would change which sub-rules are generated.
            patterns = if (frequency == Frequency.Monthly) patterns else emptyList(),
            skipWeekend = skipWeekend,
            weekendSolveMode = weekendSolveMode,
            endMode = endMode,
            endOccurrences = endOccurrences,
            endDate = endDate,
        )

        fun parse(json: JsonObject): RecurConfig? {
            val frequency = Frequency.fromRaw(json["frequency"].stringOrNull) ?: return null
            val start = json["start"].stringOrNull?.let(DayDate::fromIso) ?: return null
            // Absent/null interval defaults to 1 (upstream `config.interval ?? 1`),
            // but a present non-integer value (legacy picker state stored strings)
            // makes rSchedule throw upstream — reject rather than silently posting
            // on interval-1 cadence.
            val interval = when (val raw = json["interval"]) {
                null, JsonNull -> 1
                else -> max(1, raw.longOrNullValue?.toInt() ?: return null)
            }
            val patterns = when (val raw = json["patterns"]) {
                is JsonArray -> raw.map { element ->
                    val p = element as? JsonObject ?: return null
                    val type = p["type"].stringOrNull ?: return null
                    val value = p["value"].longOrNullValue?.toInt() ?: return null
                    val valid = value != 0 && if (type == "day") abs(value) <= 31 else type in weekdayCodes && abs(value) <= 5
                    if (!valid) return null // malformed pattern → whole config unsupported
                    Pattern(type, value)
                }
                else -> emptyList()
            }
            val endMode = json["endMode"].stringOrNull ?: "never"
            val endOccurrences = json["endOccurrences"].longOrNullValue?.toInt()
            val endDate = json["endDate"].stringOrNull?.let(DayDate::fromIso)
            // A bounded endMode missing its bound must not silently degrade to "recur forever".
            if (endMode == "on_date" && endDate == null) return null
            if (endMode == "after_n_occurrences" && (endOccurrences ?: 0) <= 0) return null
            return RecurConfig(
                frequency = frequency,
                interval = interval,
                start = start,
                patterns = patterns,
                skipWeekend = json["skipWeekend"].boolOrNull ?: false,
                weekendSolveMode = json["weekendSolveMode"].stringOrNull ?: "after",
                endMode = endMode,
                endOccurrences = endOccurrences,
                endDate = endDate,
            )
        }
    }
}

/** Kotlin port of loot-core's schedule recurrence math (`getNextDate`). */
object ScheduleRecurrence {
    private const val PERIOD_CAP = 20000
    private val weekdayNumber = mapOf("SU" to 1, "MO" to 2, "TU" to 3, "WE" to 4, "TH" to 5, "FR" to 6, "SA" to 7)

    /**
     * Port of loot-core getNextDate: first occurrence on/after [target], falling
     * back to the LAST occurrence if the schedule already ended. Weekend solve
     * applies to the final result only (may move it before [target]).
     */
    fun nextOccurrence(config: RecurConfig, onOrAfter: DayDate): DayDate? {
        var date = rawNext(config, onOrAfter) ?: rawLast(config) ?: return null
        if (config.skipWeekend && date.isWeekend) {
            date = if (config.weekendSolveMode == "before") previousFriday(date) else nextMonday(date)
        }
        return date
    }

    /**
     * Monthly day-patterns and weekday-patterns are SEPARATE unioned rrules,
     * each carrying the full count/endDate independently (matches
     * recurConfigToRSchedule).
     */
    private sealed interface Kind {
        data object Plain : Kind
        data class DaysOfMonth(val days: List<Int>) : Kind
        data class Weekdays(val pairs: List<Pair<Int, Int>>) : Kind
    }

    private class SubRule(val config: RecurConfig, val kind: Kind)

    private fun subRules(c: RecurConfig): List<SubRule> {
        if (c.frequency != RecurConfig.Frequency.Monthly || c.patterns.isEmpty()) return listOf(SubRule(c, Kind.Plain))
        val rules = mutableListOf<SubRule>()
        val days = c.patterns.filter { it.type == "day" }.map { it.value }
        val weekdays = c.patterns.filter { it.type != "day" }.map { weekdayNumber.getValue(it.type) to it.value }
        if (days.isNotEmpty()) rules += SubRule(c, Kind.DaysOfMonth(days))
        if (weekdays.isNotEmpty()) rules += SubRule(c, Kind.Weekdays(weekdays))
        return rules
    }

    private fun rawNext(config: RecurConfig, target: DayDate): DayDate? =
        subRules(config).mapNotNull { firstOccurrence(it) { date -> date >= target } }.minOrNull()

    private fun rawLast(config: RecurConfig): DayDate? = subRules(config).mapNotNull { lastOccurrence(it) }.maxOrNull()

    private fun firstOccurrence(rule: SubRule, predicate: (DayDate) -> Boolean): DayDate? {
        var count = 0
        var result: DayDate? = null
        enumerate(rule) { date ->
            count += 1
            val cap = rule.config.endOccurrences
            if (cap != null && rule.config.endMode == "after_n_occurrences" && count > cap) return@enumerate false
            val end = rule.config.endDate
            if (end != null && rule.config.endMode == "on_date" && date > end) return@enumerate false
            if (predicate(date)) {
                result = date
                return@enumerate false
            }
            true
        }
        return result
    }

    private fun lastOccurrence(rule: SubRule): DayDate? {
        if (rule.config.endMode != "after_n_occurrences" && rule.config.endMode != "on_date") return null
        var count = 0
        var last: DayDate? = null
        enumerate(rule) { date ->
            val end = rule.config.endDate
            if (end != null && rule.config.endMode == "on_date" && date > end) return@enumerate false
            count += 1
            val cap = rule.config.endOccurrences
            if (cap != null && rule.config.endMode == "after_n_occurrences" && count > cap) return@enumerate false
            last = date
            true
        }
        return last
    }

    /** Yields occurrences chronologically; body returns false to stop. */
    private inline fun enumerate(rule: SubRule, body: (DayDate) -> Boolean) {
        val c = rule.config
        when (c.frequency) {
            RecurConfig.Frequency.Daily -> {
                var d = c.start
                repeat(PERIOD_CAP) {
                    if (!body(d)) return
                    d = d.addingDays(c.interval)
                }
            }
            RecurConfig.Frequency.Weekly -> {
                var d = c.start
                repeat(PERIOD_CAP) {
                    if (!body(d)) return
                    d = d.addingDays(7 * c.interval)
                }
            }
            RecurConfig.Frequency.Yearly -> {
                for (k in 0 until PERIOD_CAP) {
                    val y = c.start.year + k * c.interval
                    if (c.start.day > DayDate.lastDay(y, c.start.month)) continue
                    if (!body(DayDate(y, c.start.month, c.start.day))) return
                }
            }
            RecurConfig.Frequency.Monthly -> {
                for (k in 0 until PERIOD_CAP) {
                    val totalMonths = (c.start.month - 1) + k * c.interval
                    val y = c.start.year + totalMonths / 12
                    val m = totalMonths % 12 + 1
                    val lastDay = DayDate.lastDay(y, m)
                    val candidates = mutableListOf<DayDate>()
                    when (val kind = rule.kind) {
                        Kind.Plain -> if (c.start.day <= lastDay) candidates += DayDate(y, m, c.start.day)
                        is Kind.DaysOfMonth -> for (v in kind.days) {
                            val day = if (v > 0) v else lastDay + 1 + v
                            if (day in 1..lastDay) candidates += DayDate(y, m, day)
                        }
                        is Kind.Weekdays -> for ((weekday, nth) in kind.pairs) {
                            nthWeekday(y, m, weekday, nth)?.let { candidates += it }
                        }
                    }
                    for (d in candidates.sorted()) {
                        if (d < c.start) continue
                        if (!body(d)) return
                    }
                }
            }
        }
    }

    private fun nthWeekday(year: Int, month: Int, weekday: Int, nth: Int): DayDate? {
        val lastDay = DayDate.lastDay(year, month)
        return if (nth > 0) {
            val first = DayDate(year, month, 1)
            val offset = (weekday - first.weekday + 7) % 7
            val day = 1 + offset + (nth - 1) * 7
            if (day <= lastDay) DayDate(year, month, day) else null
        } else {
            val last = DayDate(year, month, lastDay)
            val offset = (last.weekday - weekday + 7) % 7
            val day = lastDay - offset + (nth + 1) * 7
            if (day >= 1) DayDate(year, month, day) else null
        }
    }

    /**
     * Where a skip starts searching, port of loot-core `skipNextDate`'s
     * `start` callback plus its `skipRequested` weekend branch.
     *
     * The weekend special case is upstream's and is load-bearing. With
     * `weekendSolveMode: "before"`, an occurrence that lands on a weekend has
     * already been pulled back to the Friday, so searching from "Friday + 1"
     * finds the same occurrence again and the skip does nothing. Jumping to
     * the following Monday first steps clear of it.
     */
    fun skipSearchStart(nextDate: DayDate, config: RecurConfig): DayDate {
        var from = nextDate
        if (config.skipWeekend && config.weekendSolveMode == "before" && (from.weekday == 6 || from.isWeekend)) {
            from = nextMonday(from)
        }
        return from.addingDays(1)
    }

    fun nextMonday(d: DayDate): DayDate {
        var x = d
        while (x.weekday != 2) x = x.addingDays(1)
        return x
    }

    private fun previousFriday(d: DayDate): DayDate {
        var x = d
        while (x.weekday != 6) x = x.addingDays(-1)
        return x
    }

    /**
     * The next [count] occurrences on or after [today], for the editor's
     * preview. Port of loot-core `schedule/get-upcoming-dates`.
     */
    fun upcomingDates(config: RecurConfig, count: Int, today: DayDate = DayDate.today()): List<DayDate> {
        val dates = mutableListOf<DayDate>()
        var cursor = today
        while (dates.size < count) {
            val next = nextOccurrence(config, cursor) ?: break
            // `nextOccurrence` falls back to the LAST occurrence once a bounded
            // schedule has run out, which shows up here as a date that stops
            // advancing — or, on the first pass, as a past date. Weekend solving
            // can legitimately pull a date two days earlier; allow that much.
            if (dates.isEmpty() && next < today.addingDays(-2)) break
            val previous = dates.lastOrNull()
            if (previous != null && next <= previous) break
            dates += next
            cursor = next.addingDays(1)
        }
        return dates
    }
}
