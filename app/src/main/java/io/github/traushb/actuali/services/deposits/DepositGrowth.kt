package io.github.traushb.actuali.services.deposits

import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.loans.LoanAmortization
import io.github.traushb.actuali.services.schedules.DayDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What a fixed or recurring deposit is worth over its term — the deposit-side
 * counterpart to `LoanAmortization`: pure arithmetic over integer cents.
 * Interest credits on **whole compounding periods**, which is what deposits
 * actually do, so the value steps rather than growing smoothly.
 */
object DepositGrowth {
    /** Fifty years, the loan engine's bound too; stops a corrupt term from looping forever. */
    const val MAX_TERM_MONTHS = LoanAmortization.MAX_MONTHS

    /** Compounding periods completed after [months] have elapsed. */
    fun completedPeriods(months: Int, compounding: DepositConfig.Compounding): Int =
        if (months <= 0) 0 else months / compounding.periodMonths

    private fun compounded(amount: Long, annualRatePercent: Double, compounding: DepositConfig.Compounding, periods: Int): Double {
        if (periods <= 0 || annualRatePercent <= 0) return amount.toDouble()
        val periodRate = annualRatePercent / 100 / compounding.rawValue
        return amount * (1 + periodRate).pow(periods.toDouble())
    }

    /**
     * What [config] is worth once [afterMonths] have elapsed, in cents. Clamped
     * to the term: a matured deposit stops earning at maturity.
     */
    fun value(config: DepositConfig, afterMonths: Int): Long {
        val term = min(max(0, config.termMonths), MAX_TERM_MONTHS)
        val months = min(max(0, afterMonths), term)
        if (config.amount <= 0) return 0
        return when (config.kind) {
            DepositConfig.Kind.Fixed -> roundHalfAwayFromZero(
                compounded(config.amount, config.annualRatePercent, config.compounding, completedPeriods(months, config.compounding)),
            )
            DepositConfig.Kind.Recurring -> {
                // Each instalment compounds only for the time since it was paid.
                var total = 0.0
                for (instalment in 0 until instalmentsMade(config, months)) {
                    total += compounded(config.amount, config.annualRatePercent, config.compounding, completedPeriods(months - instalment, config.compounding))
                }
                roundHalfAwayFromZero(total)
            }
        }
    }

    /** How many instalments a recurring deposit has taken by [afterMonths]; month 0 already has one. */
    fun instalmentsMade(config: DepositConfig, afterMonths: Int): Int {
        if (config.kind != DepositConfig.Kind.Recurring) return 1
        val term = min(max(0, config.termMonths), MAX_TERM_MONTHS)
        if (term <= 0) return 0
        return min(max(0, afterMonths) + 1, term)
    }

    /** What has gone in by [afterMonths] — the baseline interest is measured against. */
    fun deposited(config: DepositConfig, afterMonths: Int): Long {
        if (config.amount <= 0) return 0
        return when (config.kind) {
            DepositConfig.Kind.Fixed -> config.amount
            DepositConfig.Kind.Recurring -> config.amount * instalmentsMade(config, afterMonths)
        }
    }

    /** One point on the growth curve. */
    data class Point(val month: DayDate, val value: Long, val deposited: Long) {
        val id: Int get() = month.yyyymmdd
    }

    /** Month-by-month value from opening to maturity, both ends included. */
    fun curve(config: DepositConfig): List<Point> {
        val term = min(max(0, config.termMonths), MAX_TERM_MONTHS)
        if (term <= 0 || config.amount <= 0) return emptyList()
        return (0..term).map { month -> Point(config.openedOn.addingMonths(month), value(config, month), deposited(config, month)) }
    }
}
