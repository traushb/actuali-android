package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import java.util.UUID
import kotlin.math.abs

/** Detects recurring transactions and proposes schedules for them. Port of loot-core `find-schedules.ts`. */
object ScheduleDiscovery {
    /** A transaction eligible to take part in a pattern. */
    data class Candidate(val id: String, val date: DayDate, val amount: Long, val payeeId: String, val accountId: String)

    /** A pattern that matched, before payee-level deduplication. */
    data class Match(
        val rank: Double,
        val amount: Long,
        val accountId: String,
        val payeeId: String,
        val config: RecurConfig,
        /** Every occurrence landed exactly on its date. */
        val exactDate: Boolean,
        /** Every matched transaction carried exactly the same amount. */
        val exactAmount: Boolean,
    )

    /** A proposal shown to the user. */
    data class Proposal(
        val accountId: String,
        val payeeId: String,
        val amount: Long,
        val config: RecurConfig,
        val exactDate: Boolean,
        val exactAmount: Boolean,
        val id: String = UUID.randomUUID().toString(),
    ) {
        /** The form the create path takes; operators reflect how exactly the history matched. */
        val formFields: ScheduleFormFields
            get() = ScheduleFormFields(
                name = null,
                payeeId = payeeId,
                accountId = accountId,
                amount = ScheduledAmount.Fixed(amount),
                amountOp = if (exactAmount) ScheduleAmountOp.IsExactly else ScheduleAmountOp.IsApprox,
                date = ScheduleDateCondition.Recurring(config),
                postsTransaction = false,
                customUpcomingLength = null,
            )
    }

    /** How far an amount may drift and still count as the same payment — upstream's 7.5%. */
    fun approxThreshold(amount: Long): Long = roundHalfAwayFromZero(abs(amount).toDouble() * 0.075)

    /** Closeness of two dates: an exact hit is 1, one day off is 0.5, and so on. */
    fun rank(a: DayDate, b: DayDate): Double = 1.0 / (abs(a.days(b)).toDouble() + 1.0)

    /** Scan every open account and return one proposal per payee. */
    fun discover(
        accounts: List<Account>,
        loadCandidates: (String, Int) -> List<Candidate>,
        latestDate: (String) -> DayDate?,
    ): List<Proposal> {
        val matches = mutableListOf<Match>()
        for (account in accounts) {
            if (account.closed) continue
            val latest = latestDate(account.id) ?: continue
            // Every sweep starts at most ~8 months back; one load covers them
            // all, with a month of slack for the ±2-day windows.
            val earliest = latest.addingMonths(-9)
            val candidates = loadCandidates(account.id, earliest.yyyymmdd)
            if (candidates.isEmpty()) continue
            val index = CandidateIndex(candidates)
            for (sweep in sweeps(latest)) matches += run(sweep, index, account.id)
        }
        // One proposal per payee: the highest-ranked pattern wins.
        return matches.groupBy { it.payeeId }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.rank } }
            .map { Proposal(it.accountId, it.payeeId, it.amount, it.config, it.exactDate, it.exactAmount) }
            .sortedBy { it.payeeId }
    }

    /** A range of candidate start dates plus the config each one produces (null skips a start date). */
    class Sweep(val start: DayDate, val dayCount: Int, val makeConfig: (DayDate) -> RecurConfig?)

    fun sweeps(latest: DayDate): List<Sweep> {
        val weekdayCode = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")[latest.weekday - 1]
        val monthly = RecurConfig.Frequency.Monthly
        return listOf(
            Sweep(latest.addingDays(-28), 14) { RecurConfig.make(RecurConfig.Frequency.Weekly, start = it) },
            // Six weeks covers three occurrences; upstream scans one more.
            Sweep(latest.addingDays(-49), 14) { RecurConfig.make(RecurConfig.Frequency.Weekly, interval = 2, start = it) },
            // 28 is the highest day every month is guaranteed to have; past
            // that a monthly schedule would skip short months. The last-day
            // sweeps below cover month ends instead.
            Sweep(latest.addingMonths(-4), 62) { start -> if (start.day <= 28) RecurConfig.make(monthly, start = start) else null },
            Sweep(latest.addingMonths(-3), 1) { RecurConfig.make(monthly, start = it, patterns = listOf(RecurConfig.Pattern("day", -1))) },
            Sweep(latest.addingMonths(-4), 1) { RecurConfig.make(monthly, start = it, patterns = listOf(RecurConfig.Pattern("day", -1))) },
            Sweep(latest.addingDays(-56), 14) {
                RecurConfig.make(monthly, start = it, patterns = listOf(RecurConfig.Pattern(weekdayCode, 1), RecurConfig.Pattern(weekdayCode, 3)))
            },
            Sweep(latest.addingMonths(-8), 14) {
                RecurConfig.make(monthly, start = it, patterns = listOf(RecurConfig.Pattern(weekdayCode, 2), RecurConfig.Pattern(weekdayCode, 4)))
            },
        )
    }

    private fun run(sweep: Sweep, index: CandidateIndex, accountId: String): List<Match> {
        val matches = mutableListOf<Match>()
        for (offset in 0 until sweep.dayCount) {
            val start = sweep.start.addingDays(offset)
            val config = sweep.makeConfig(start) ?: continue
            val occurrences = ScheduleRecurrence.upcomingDates(config, 3, start)
            if (occurrences.size != 3) continue
            matches += match(occurrences.map { it to index.near(it, 2) }, config, accountId)
        }
        return matches
    }

    /**
     * Port of upstream `matchSchedules`. Anchored on the LAST occurrence
     * (upstream reverses the array first), so a pattern is judged by the most
     * recent payment and traced backwards.
     */
    fun match(occurrences: List<Pair<DayDate, List<Candidate>>>, config: RecurConfig, accountId: String): List<Match> {
        val reversed = occurrences.reversed()
        val anchor = reversed.firstOrNull() ?: return emptyList()
        val rest = reversed.drop(1)
        val matches = mutableListOf<Match>()
        for (transaction in anchor.second) {
            val threshold = approxThreshold(transaction.amount)
            // Every other occurrence must contain the same payee at a
            // comparable amount, or this isn't a pattern.
            val found = mutableListOf<Pair<Candidate, Double>>()
            var complete = true
            for ((date, candidates) in rest) {
                val hit = candidates.firstOrNull {
                    it.payeeId == transaction.payeeId &&
                        it.amount >= transaction.amount - threshold &&
                        it.amount <= transaction.amount + threshold
                }
                if (hit == null) {
                    complete = false
                    break
                }
                found += hit to rank(date, hit.date)
            }
            if (!complete) continue
            val total = found.fold(rank(anchor.first, transaction.date)) { acc, entry -> acc + entry.second }
            matches += Match(
                rank = total,
                amount = transaction.amount,
                accountId = accountId,
                payeeId = transaction.payeeId,
                config = config,
                // Every occurrence scoring 1 sums to the occurrence count.
                exactDate = total == occurrences.size.toDouble(),
                exactAmount = found.all { it.first.amount == transaction.amount },
            )
        }
        return matches
    }

    /** Date-bucketed candidates, so the ±2-day window is a lookup rather than a query. */
    class CandidateIndex(candidates: List<Candidate>) {
        private val byDate: Map<Int, List<Candidate>> = candidates.groupBy { it.date.yyyymmdd }

        fun near(date: DayDate, days: Int): List<Candidate> = (-days..days).flatMap { byDate[date.addingDays(it).yyyymmdd].orEmpty() }
    }
}
