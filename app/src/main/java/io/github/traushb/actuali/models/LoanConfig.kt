package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.budget.AutomationSentences
import io.github.traushb.actuali.services.loans.LoanAmortization
import io.github.traushb.actuali.services.schedules.DayDate
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Synced configuration for a loan account, persisted in Actual's `preferences`
 * table under `actuali:loan:<accountId>` as a JSON string. The fields are the
 * ones YNAB collects when a loan account is created; everything added later
 * is optional so a client that predates it still reads the rest.
 */
@Serializable
data class LoanConfig(
    /**
     * What was owed when the loan was added, in cents and always positive.
     *
     * ponytail: stored at setup rather than derived from the account's
     * starting-balance transaction, so it goes stale if that transaction is
     * later edited. Upgrade path: read the account's oldest transaction and
     * fall back to this value.
     */
    val originalBalance: Long,
    /** Nominal annual rate as a percentage — 5.25 means 5.25% APR. */
    val annualRatePercent: Double,
    /** The payment the lender requires each month, in cents. */
    val minimumPayment: Long,
    /** Escrow or fees bundled into the monthly payment, in cents. */
    val escrowOrFees: Long? = null,
    /** The budget category this loan's payments are assigned to, if any. */
    val categoryId: String? = null,
    /** The month (`YYYY-MM`) the payment target is skipped for, if any. */
    val targetSnoozedMonth: String? = null,
) {
    /** Principal repaid so far, clamped to 0...originalBalance. */
    fun paidOff(accountBalance: Long): Long = min(originalBalance, max(0, originalBalance - owed(accountBalance)))

    /** Share of the original balance repaid, 0...1. */
    fun fractionPaidOff(accountBalance: Long): Double =
        if (originalBalance > 0) paidOff(accountBalance).toDouble() / originalBalance.toDouble() else 0.0

    /** The projection for this loan, or null when the payment can't clear the balance. */
    fun schedule(accountBalance: Long, month: DayDate = DayDate.today()): LoanAmortization.Schedule? =
        LoanAmortization.schedule(
            balance = owed(accountBalance),
            annualRatePercent = annualRatePercent,
            payment = minimumPayment,
            escrowOrFees = escrowOrFees ?: 0,
            startingMonth = month,
        )

    /**
     * One-line payoff summary, shared by the Loans list and the account header.
     * A payment too small to cover the monthly interest says so rather than
     * showing nothing.
     */
    fun payoffSummary(accountBalance: Long, month: DayDate = DayDate.today(), locale: Locale = Locale.getDefault()): String {
        if (owed(accountBalance) <= 0) return L("Paid off")
        val payoff = schedule(accountBalance, month)?.payoffDate ?: return L("Payment doesn't cover interest")
        val label = AutomationSentences.monthLabel(String.format(Locale.ROOT, "%04d-%02d", payoff.year, payoff.month), locale)
        return L("Paid off %@", label)
    }

    /** How one real payment divides between the lender's charges and the balance. */
    data class PaymentSplit(val interest: Long, val escrow: Long, val principal: Long)

    /**
     * The split for a payment against [accountBalance]: interest and escrow are
     * charged in full whatever was paid; underpaying moves the shortfall into
     * a negative principal.
     */
    fun paymentSplit(accountBalance: Long, payment: Long): PaymentSplit {
        val interest = LoanAmortization.monthlyInterest(owed(accountBalance), annualRatePercent)
        val escrow = escrowOrFees ?: 0
        return PaymentSplit(interest, escrow, payment - interest - escrow)
    }

    /** What clearing the loan outright this month would take. */
    fun payoffAmount(accountBalance: Long): Long {
        val owed = owed(accountBalance)
        if (owed <= 0) return 0
        return owed + LoanAmortization.monthlyInterest(owed, annualRatePercent) + (escrowOrFees ?: 0)
    }

    /** The amount to offer when recording a payment: the minimum, except on the last one. */
    fun suggestedPayment(accountBalance: Long): Long = min(minimumPayment, payoffAmount(accountBalance))

    /** Whether the payment target is skipped for [month] (`YYYY-MM`). */
    fun targetIsSnoozed(month: String): Boolean = targetSnoozedMonth == month

    companion object {
        /**
         * The payees Record Payment books a loan's interest and escrow charges
         * to. Fixed rather than localized, like "Starting Balance":
         * `findOrCreatePayee` matches by name, so a translated name would fork
         * the payee per device language.
         */
        const val INTEREST_PAYEE_NAME = "Interest"
        const val ESCROW_PAYEE_NAME = "Escrow"

        /**
         * What is still owed, as a positive number. Actual holds a loan balance
         * negative while money is owed, so the sign is flipped here once.
         */
        fun owed(accountBalance: Long): Long = max(0, -accountBalance)
    }
}
