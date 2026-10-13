package io.github.traushb.actuali.services.loans

import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.schedules.DayDate
import kotlin.math.max
import kotlin.math.min

/**
 * Monthly amortization math for loan accounts.
 *
 * Mirrors YNAB's loan model: interest compounds monthly at the annual rate
 * divided by 12 applied to the current balance, and each payment covers that
 * interest — plus escrow or fees where the lender bundles them — before
 * anything reaches principal. Every amount is cents. What comes back is a
 * projection, not the lender's own schedule.
 */
object LoanAmortization {
    /** One projected month. */
    data class Entry(
        val month: DayDate,
        /** The regular payment plus any one-off extra, or only what's left on the final month. */
        val payment: Long,
        val interest: Long,
        val escrow: Long,
        /** The part of `payment` that reduced the balance; negative when the loan is going backwards. */
        val principal: Long,
        /** Still owed after this month's payment. */
        val balance: Long,
    )

    data class Schedule(val entries: List<Entry>) {
        /** The month the final payment lands, or null for a balance already clear. */
        val payoffDate: DayDate? get() = entries.lastOrNull()?.month

        val paymentCount: Int get() = entries.size

        val totalInterest: Long get() = entries.sumOf { it.interest }

        /**
         * Balance over time for a burndown chart. The opening balance is
         * prepended so the first segment shows the first month's progress.
         */
        fun balanceOverTime(openingBalance: Long): List<BalancePoint> {
            val first = entries.firstOrNull() ?: return emptyList()
            return listOf(BalancePoint(first.month.addingMonths(-1), openingBalance)) + entries.map { BalancePoint(it.month, it.balance) }
        }
    }

    /** What paying more than the minimum buys. */
    data class Savings(val interest: Long, val months: Int)

    /** One point on the payoff curve. */
    data class BalancePoint(val month: DayDate, val balance: Long) {
        val id: Int get() = month.yyyymmdd
    }

    /**
     * Longest projection the engine will run. Fifty years outlives any consumer
     * loan, and bounding the walk is what lets a payment too small to cover
     * the interest terminate as null instead of looping forever.
     */
    const val MAX_MONTHS = 600

    /** The interest charged for one month on [balance]: rate ÷ 12 × balance, rounded to the cent. */
    fun monthlyInterest(balance: Long, annualRatePercent: Double): Long {
        if (balance <= 0 || annualRatePercent <= 0) return 0
        // 1200 = 100 (the rate is a percentage) x 12 (months).
        return roundHalfAwayFromZero(balance.toDouble() * annualRatePercent / 1200)
    }

    /**
     * Projects the loan to payoff, or null when [payment] can't clear it inside
     * [MAX_MONTHS] — what a payment that doesn't cover the monthly interest
     * looks like from here. [extraPayments] are one-off amounts keyed by month.
     */
    fun schedule(
        balance: Long,
        annualRatePercent: Double,
        payment: Long,
        escrowOrFees: Long = 0,
        extraPayments: Map<DayDate, Long> = emptyMap(),
        startingMonth: DayDate,
    ): Schedule? {
        if (balance <= 0) return Schedule(emptyList())
        var remaining = balance
        val entries = mutableListOf<Entry>()
        var month = startingMonth
        repeat(MAX_MONTHS) {
            val interest = monthlyInterest(remaining, annualRatePercent)
            // Interest and escrow come off the top and only the rest touches
            // principal. Capping at what's left makes the final month the
            // part-payment that clears the loan rather than an overpayment.
            val due = payment + (extraPayments[month] ?: 0)
            val paid = min(due, remaining + interest + escrowOrFees)
            val principal = paid - interest - escrowOrFees
            remaining -= principal
            entries += Entry(month, paid, interest, escrowOrFees, principal, remaining)
            if (remaining <= 0) return Schedule(entries)
            month = month.addingMonths(1)
        }
        return null
    }

    /**
     * The smallest monthly payment that clears [balance] within [months], or
     * null when the term is out of range. Searched against [schedule] rather
     * than solved with the annuity formula: the schedule rounds interest to
     * the cent every month, so a closed-form answer can miss its own term.
     */
    fun requiredPayment(balance: Long, annualRatePercent: Double, escrowOrFees: Long = 0, months: Int, startingMonth: DayDate): Long? {
        if (balance <= 0) return 0
        if (months <= 0 || months > MAX_MONTHS) return null
        var low = 1L
        var high = balance + monthlyInterest(balance, annualRatePercent) + escrowOrFees
        while (low < high) {
            val mid = low + (high - low) / 2
            val clears = schedule(balance, annualRatePercent, mid, escrowOrFees, startingMonth = startingMonth)
                ?.let { it.paymentCount <= months } ?: false
            if (clears) high = mid else low = mid + 1
        }
        return low
    }

    /** Interest and time saved by following [target] instead of [minimum]. */
    fun savings(minimum: Schedule, target: Schedule): Savings =
        Savings(max(0, minimum.totalInterest - target.totalInterest), max(0, minimum.paymentCount - target.paymentCount))
}
