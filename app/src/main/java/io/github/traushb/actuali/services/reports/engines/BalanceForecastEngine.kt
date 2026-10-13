package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Schedule
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.WidgetRuleCondition
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Mirrors upstream `BalanceForecastWidget` meta (loot-core dashboard.ts). */
@Serializable
data class BalanceForecastMeta(
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val accounts: List<String>? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    val granularity: Granularity? = null,
    val source: Source? = null,
) {
    @Serializable
    enum class Granularity {
        @SerialName("Daily")
        Daily,

        @SerialName("Monthly")
        Monthly,
    }

    @Serializable
    enum class Source {
        @SerialName("schedules")
        Schedules,

        @SerialName("tracking-budget")
        TrackingBudget,
    }
}

/**
 * One month of tracking-budget totals, derived from `reflect_budgets`. Mirrors
 * the upstream sheet cells (forecast-tracking-budget.ts): income = sum of
 * budgeted amounts across income categories, expenses = sum across expense
 * categories (positive). Months missing from the list count as zero.
 */
data class BalanceForecastBudgetMonth(val month: Int, val budgetedIncomeCents: Long, val budgetedExpensesCents: Long)

data class BalanceForecastPoint(
    /** The sampled day: each day for Daily, the last day of the month for Monthly. */
    val date: LocalDate,
    val balanceCents: Long,
    /** True when the sampled day is after `today` — the projected segment. */
    val isForecast: Boolean,
)

data class BalanceForecastData(val points: List<BalanceForecastPoint>)

/**
 * Port of upstream's balance forecast at dashboard-card fidelity: loot-core
 * `forecast/` (projection, schedules, tracking-budget) combined with
 * desktop-client `balanceForecastChartData.ts`.
 *
 * Inputs: all live transactions (the engine applies account selection and
 * widget conditions itself); alive, non-completed schedules regardless of
 * `posts_transaction`; tracking-budget months (only for the tracking-budget
 * source — callers fall back to schedules when the file isn't a tracking
 * budget, as BalanceForecastCard.tsx does); off-budget account ids for the
 * tracking-budget starting balance; payee id → transfer account so transfer
 * schedules project both legs.
 *
 * Deliberate cuts from upstream (card fidelity): no rule-running on simulated
 * occurrences; posted-occurrence dedup always matches the exact occurrence
 * date (upstream widens to a 2-day lookback for `isapprox` manual schedules);
 * accountless schedules are excluded.
 */
object BalanceForecastEngine {
    fun compute(
        meta: BalanceForecastMeta?,
        transactions: List<Transaction>,
        schedules: List<Schedule> = emptyList(),
        trackingBudgetMonths: List<BalanceForecastBudgetMonth> = emptyList(),
        offBudgetAccountIds: Set<String> = emptySet(),
        transferAccountsByPayeeId: Map<String, String> = emptyMap(),
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): BalanceForecastData {
        val todayDay = DayDate.of(today)
        val (chartStart, chartEnd) = resolveRange(meta?.timeFrame, today)
        if (chartStart > chartEnd) return BalanceForecastData(emptyList())

        // The forecast query always covers whole months; the chart trims back
        // to the requested bounds (BalanceForecastCard.tsx).
        val forecastStart = DayDate(chartStart.year, chartStart.month, 1)
        val forecastEnd = DayDate(chartEnd.year, chartEnd.month, DayDate.lastDay(chartEnd.year, chartEnd.month))

        val combinedByDay = if (meta?.source == BalanceForecastMeta.Source.TrackingBudget) {
            trackingBudgetSeries(transactions, trackingBudgetMonths, offBudgetAccountIds, forecastStart, forecastEnd)
        } else {
            if (meta?.accounts?.isEmpty() == true) return BalanceForecastData(emptyList())
            schedulesSeries(meta, transactions, schedules, transferAccountsByPayeeId, forecastStart, forecastEnd, todayDay, context)
        }
        return BalanceForecastData(chartPoints(combinedByDay, meta?.granularity ?: BalanceForecastMeta.Granularity.Monthly, chartStart, chartEnd, todayDay))
    }

    // Schedules source

    /**
     * Combined running balance per day over the whole forecast range: posted
     * history plus projected schedule occurrences from max(start, today)
     * onward. Port of `projectForecastData` (forecast-projection.ts),
     * collapsed to the combined series the card renders.
     */
    private fun schedulesSeries(
        meta: BalanceForecastMeta?,
        transactions: List<Transaction>,
        schedules: List<Schedule>,
        transferAccountsByPayeeId: Map<String, String>,
        forecastStart: DayDate,
        forecastEnd: DayDate,
        today: DayDate,
        context: ConditionsFilter.Context,
    ): Map<Int, Long> {
        val selected = meta?.accounts?.toSet()
        fun inSelection(accountId: String) = selected?.contains(accountId) ?: true

        val filtered = transactions.filter {
            !it.tombstone && inSelection(it.accountId) && ConditionsFilter.matches(it, meta?.conditions, meta?.conditionsOp, context)
        }

        var startingBalance = 0L
        val deltaByDay = HashMap<Int, Long>()
        for (tx in filtered) {
            if (tx.date < forecastStart.yyyymmdd) startingBalance += tx.amount
            else if (tx.date <= forecastEnd.yyyymmdd) deltaByDay.merge(tx.date, tx.amount, Long::plus)
        }

        // Posted-occurrence dedup index (shared/schedules
        // indexPostedScheduleTransactions; upstream also builds it from the
        // account+condition-filtered set).
        val postedDatesBySchedule = HashMap<String, MutableList<Int>>()
        for (tx in filtered) tx.schedule?.let { postedDatesBySchedule.getOrPut(it) { mutableListOf() } += tx.date }

        // Past-due occurrences before today never project (firstForecastDate in
        // forecast-projection.ts).
        val firstForecast = if (forecastEnd < today) forecastStart else maxOf(forecastStart, today)

        fun addOccurrenceLeg(accountId: String, amount: Long, payeeId: String?, categoryId: String?, date: DayDate) {
            if (!inSelection(accountId)) return
            val simulated = Transaction(
                id = "forecast", accountId = accountId, date = date.yyyymmdd, amount = amount,
                payeeId = payeeId, payeeName = null, categoryId = categoryId, categoryName = null,
                notes = null, cleared = false, reconciled = false, transferId = null, isParent = false,
                parentId = null, tombstone = false, sortOrder = null, importedPayee = null,
            )
            if (!ConditionsFilter.matches(simulated, meta?.conditions, meta?.conditionsOp, context)) return
            deltaByDay.merge(date.yyyymmdd, amount, Long::plus)
        }

        // Schedules are NOT pre-filtered by account: when only the transfer
        // side is selected, its leg still projects (forecast-schedules.ts).
        for (schedule in schedules) {
            val amount = schedule.amount?.postAmount ?: 0
            val posted = postedDatesBySchedule[schedule.id].orEmpty()
            for (date in occurrenceDates(schedule, forecastEnd)) {
                if (date < firstForecast || date > forecastEnd || date.yyyymmdd in posted) continue
                addOccurrenceLeg(schedule.accountId, amount, schedule.payeeId, schedule.categoryId, date)
                val transferAccountId = schedule.payeeId?.let { transferAccountsByPayeeId[it] }
                if (transferAccountId != null && transferAccountId != schedule.accountId) {
                    addOccurrenceLeg(transferAccountId, -amount, null, null, date)
                }
            }
        }

        val series = HashMap<Int, Long>()
        var running = startingBalance
        var day = forecastStart
        while (day <= forecastEnd) {
            running += deltaByDay[day.yyyymmdd] ?: 0
            series[day.yyyymmdd] = running
            day = day.addingDays(1)
        }
        return series
    }

    /** Port of `getFutureOccurrenceDates` (forecast-schedules.ts): the stored next date, then recurrence expansion through [endDate]. */
    private fun occurrenceDates(schedule: Schedule, endDate: DayDate): List<DayDate> = when (val condition = schedule.dateCondition) {
        is ScheduleDateCondition.Fixed -> if (condition.day <= endDate) listOf(condition.day) else emptyList()
        // Recurrence shape the port can't expand; the stored next_date is
        // still a real upcoming occurrence, so project that one (mirrors the
        // poster's post-once-without-advancing behavior).
        ScheduleDateCondition.Unsupported -> if (schedule.nextDate <= endDate) listOf(schedule.nextDate) else emptyList()
        is ScheduleDateCondition.Recurring -> {
            val dates = mutableListOf(schedule.nextDate)
            val seen = mutableSetOf(schedule.nextDate)
            var day = schedule.nextDate
            var iterations = 0
            while (day <= endDate && iterations < 10000) {
                iterations += 1
                val next = ScheduleRecurrence.nextOccurrence(condition.config, day)
                if (next == null || next > endDate) break
                // getNextDate can return an already-seen date (ended schedule
                // fallback, weekend solve); step a day forward like upstream.
                if (next in seen) {
                    day = day.addingDays(1)
                    continue
                }
                dates += next
                seen += next
                day = next.addingDays(1)
            }
            dates
        }
    }

    // Tracking-budget source

    /**
     * Port of `projectTrackingBudgetForecast` (forecast-tracking-budget.ts):
     * current on-budget balance, then one month-end point per month adding
     * budgeted income minus budgeted expenses. Ignores account selection and
     * conditions, like upstream.
     */
    private fun trackingBudgetSeries(
        transactions: List<Transaction>,
        months: List<BalanceForecastBudgetMonth>,
        offBudgetAccountIds: Set<String>,
        forecastStart: DayDate,
        forecastEnd: DayDate,
    ): Map<Int, Long> {
        var running = transactions.filter { !it.tombstone && it.accountId !in offBudgetAccountIds }.sumOf { it.amount }
        val byMonth = months.associateBy { it.month }
        val series = HashMap<Int, Long>()
        for ((year, month) in monthsInclusive(forecastStart, forecastEnd)) {
            val entry = byMonth[year * 100 + month]
            running += (entry?.budgetedIncomeCents ?: 0) - (entry?.budgetedExpensesCents ?: 0)
            series[DayDate(year, month, DayDate.lastDay(year, month)).yyyymmdd] = running
        }
        return series
    }

    // Chart data

    /** Port of `buildBalanceForecastChartData` (balanceForecastChartData.ts). */
    private fun chartPoints(
        combinedByDay: Map<Int, Long>,
        granularity: BalanceForecastMeta.Granularity,
        chartStart: DayDate,
        chartEnd: DayDate,
        today: DayDate,
    ): List<BalanceForecastPoint> {
        if (combinedByDay.isEmpty()) return emptyList()
        return when (granularity) {
            BalanceForecastMeta.Granularity.Daily -> {
                // Carry the balance forward from the latest point before the
                // visible range, so a day-shaped start doesn't begin at zero.
                var running = combinedByDay.filterKeys { it < chartStart.yyyymmdd }.maxByOrNull { it.key }?.value ?: 0
                val points = mutableListOf<BalanceForecastPoint>()
                var day = chartStart
                while (day <= chartEnd) {
                    combinedByDay[day.yyyymmdd]?.let { running = it }
                    points += BalanceForecastPoint(day.localDate, running, day > today)
                    day = day.addingDays(1)
                }
                points
            }
            BalanceForecastMeta.Granularity.Monthly -> {
                // Latest balance within each month; months with no data carry
                // the running balance (which starts at zero — upstream monthly
                // has no prior-range carry).
                val latestKeyByMonth = HashMap<Int, Int>()
                for (key in combinedByDay.keys) {
                    val month = key / 100
                    if (key > (latestKeyByMonth[month] ?: 0)) latestKeyByMonth[month] = key
                }
                var running = 0L
                monthsInclusive(chartStart, chartEnd).map { (year, month) ->
                    latestKeyByMonth[year * 100 + month]?.let { combinedByDay[it] }?.let { running = it }
                    val lastDay = DayDate(year, month, DayDate.lastDay(year, month))
                    BalanceForecastPoint(lastDay.localDate, running, lastDay > today)
                }
            }
        }
    }

    // Dates

    private fun resolveRange(timeFrame: WidgetTimeFrame?, today: LocalDate): Pair<DayDate, DayDate> {
        if (timeFrame != null) {
            val (start, end) = TimeFrame.resolve(timeFrame, today)
            return DayDate.of(start) to DayDate.of(end)
        }
        // Card default: current month through 11 months ahead
        // (BalanceForecastCard.tsx defaultTimeFrame).
        val endMonth = today.withDayOfMonth(1).plusMonths(11)
        return DayDate(today.year, today.monthValue, 1) to DayDate(endMonth.year, endMonth.monthValue, endMonth.lengthOfMonth())
    }

    private fun monthsInclusive(start: DayDate, end: DayDate): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var year = start.year
        var month = start.month
        while (year < end.year || (year == end.year && month <= end.month)) {
            result += year to month
            month += 1
            if (month > 12) {
                month = 1
                year += 1
            }
        }
        return result
    }
}
