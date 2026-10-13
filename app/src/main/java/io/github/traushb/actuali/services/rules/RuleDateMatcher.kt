package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.services.CanonicalDateParser
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Date-condition semantics shared by the rules engine and the report filters.
 * Condition values are strings (`"2026-05-03"`, `"2026-05"`, `"2026"`);
 * transactions store dates as YYYYMMDD ints.
 *
 * Returns null when the condition can't be evaluated (unparseable value, or
 * an op/precision combination upstream rejects at parse time). Callers decide
 * what that means: the engine treats it as "no match", the report filters as
 * "condition dropped", which is what each does upstream.
 */
object RuleDateMatcher {
    fun matches(transactionDate: Int, op: String, value: String): Boolean? {
        val targetDate = CanonicalDateParser.parse(value) ?: return null
        val precision = value.length
        val target = targetDate.year * 10000 + targetDate.monthValue * 100 + targetDate.dayOfMonth

        return when {
            op == "is" && precision == 10 -> transactionDate == target
            op == "is" && precision == 7 -> transactionDate / 100 == target / 100
            op == "is" && precision == 4 -> transactionDate / 10000 == target / 10000
            op == "isapprox" && precision == 10 -> {
                // Upstream widens an exact date by ±2 days. Calendar days, not
                // local hours, so DST can't push a two-day gap over the edge.
                val txDate = date(transactionDate) ?: return null
                abs(ChronoUnit.DAYS.between(targetDate, txDate)) <= 2
            }
            op == "gt" && precision == 10 -> transactionDate > target
            op == "gte" && precision == 10 -> transactionDate >= target
            op == "lt" && precision == 10 -> transactionDate < target
            op == "lte" && precision == 10 -> transactionDate <= target
            // Comparison ops require an exact date upstream; month/year values
            // fail `Condition`'s parse assertions and the rule never loads.
            else -> null
        }
    }

    private fun date(yyyymmdd: Int): LocalDate? =
        runCatching { LocalDate.of(yyyymmdd / 10000, (yyyymmdd % 10000) / 100, yyyymmdd % 100) }.getOrNull()
}
