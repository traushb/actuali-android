package io.github.traushb.actuali.views.accounts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundedHalfAwayFromZero
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.budget.AutomationSentences
import io.github.traushb.actuali.services.loans.LoanAmortization
import io.github.traushb.actuali.services.pairLoan
import io.github.traushb.actuali.services.pairedLoanCategory
import io.github.traushb.actuali.services.recordLoanPayment
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ChartSeries
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.LineChart
import io.github.traushb.actuali.views.components.sparseLabels
import io.github.traushb.actuali.views.settings.LoanEditor
import io.github.traushb.actuali.views.transactions.CategoryPickerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

object LoanPaymentLogic {
    /**
     * Where the money comes from: an open, on-budget account that isn't the
     * loan. Paying from another off-budget account would leave the budget
     * untouched, defeating the point of pairing a category.
     */
    fun fundingAccounts(accounts: List<Account>, loanAccountId: String): List<Account> =
        accounts.filter { !it.closed && !it.offBudget && it.id != loanAccountId }.sortedWith(compareBy({ it.sortOrder }, { it.name }))
}

/**
 * Record a loan payment: what left the funding account, and how much of it the
 * lender kept as interest and escrow before the rest came off the balance. The
 * estimate is a starting point — interest and escrow stay editable and the
 * principal follows from them.
 */
@Composable
fun LoanPaymentView(account: Account, config: LoanConfig, balance: Long, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val funding = LoanPaymentLogic.fundingAccounts(store.accounts, account.id)
    val suggested = config.suggestedPayment(balance)
    val split = remember { config.paymentSplit(balance, suggested) }
    var fundingAccountId by remember { mutableStateOf(funding.firstOrNull()?.id.orEmpty()) }
    var paymentText by remember { mutableStateOf(LoanEditor.amountText(suggested)) }
    var interestText by remember { mutableStateOf(LoanEditor.amountText(split.interest)) }
    var escrowText by remember { mutableStateOf(LoanEditor.amountText(split.escrow)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var recording by remember { mutableStateOf(false) }

    val payment = LoanEditor.cents(paymentText) ?: 0
    val interest = LoanEditor.cents(interestText) ?: 0
    val escrow = if (config.escrowOrFees == null) 0 else LoanEditor.cents(escrowText) ?: 0
    // Can go negative, and says so rather than clamping: a payment that doesn't cover the charges grows the loan.
    val principal = payment - interest - escrow
    val canRecord = payment > 0 && fundingAccountId.isNotEmpty() && !recording && !store.syncDetachedByRestore
    val paired = store.pairedLoanCategory(account.id)

    Scaffold(
        topBar = {
            AppTopBar(L("Record Payment"), onBack = onDismiss, actions = {
                TextButton(enabled = canRecord, modifier = Modifier.testTag("loanPayment.record"), onClick = {
                    recording = true
                    scope.launch {
                        try {
                            store.recordLoanPayment(account.id, fundingAccountId, payment, interest, escrow, Transaction.yyyymmdd(date), notes = null)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            store.error = e.localizedMessage
                            recording = false
                        }
                    }
                }) { Text(L("Record")) }
            })
        },
    ) { padding ->
        FormColumn(padding) {
            FormSection(footer = if (funding.isEmpty()) L("A loan payment has to come from an open, on-budget account. Add one first.") else null) {
                FormPickerRow(L("From"), funding.map { it.id }, fundingAccountId, { id -> funding.firstOrNull { it.id == id }?.name.orEmpty() }, { fundingAccountId = it }, Modifier.testTag("loanPayment.fromAccount"))
                FormDateRow(L("Date"), date, { date = it }, Modifier.testTag("loanPayment.date"))
                FormAmountRow(L("Payment"), paymentText, { paymentText = it }, fieldModifier = Modifier.testTag("loanPayment.amount"))
            }
            FormSection(
                L("Breakdown"),
                footer = if (principal > 0) {
                    L("Interest and escrow are estimated from the balance and rate. Correct them to match what your lender charged — the principal follows.")
                } else {
                    L("This payment doesn't cover the interest and fees, so the balance will grow this month.")
                },
            ) {
                FormAmountRow(L("Interest"), interestText, { interestText = it }, fieldModifier = Modifier.testTag("loanPayment.interest"))
                if (config.escrowOrFees != null) {
                    FormAmountRow(L("Escrow or Fees"), escrowText, { escrowText = it }, fieldModifier = Modifier.testTag("loanPayment.escrow"))
                }
                FormRow(L("Principal"), value = store.displayBalance(principal), modifier = Modifier.testTag("loanPayment.principal"))
            }
            FormSection(
                footer = if (paired == null) {
                    L("Pair this loan with a category to budget for its payments. Without one the payment still records, but nothing is assigned against it.")
                } else {
                    L("The whole payment is assigned to this category, while the loan balance drops by the principal alone.")
                },
            ) { FormRow(L("Category"), value = paired?.name ?: L("Not paired"), modifier = Modifier.testTag("loanPayment.category")) }
        }
    }
}

/**
 * Pick the category a loan's payments are assigned to; its "None" unpairs,
 * the affordance YNAB offers. Writes straight to the stored config.
 */
@Composable
fun LoanCategoryPairingView(accountId: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    CategoryPickerView(store.activeLoanConfig(accountId)?.categoryId, onPick = { categoryId ->
        scope.launch { store.pairLoan(accountId, categoryId) }
        onDismiss()
    }, onClose = onDismiss)
}

/** The planner's text, apart from the screen so it can be tested without one. */
object LoanPlannerText {
    /** "Nov 2028" for a term of [months] starting at [startingMonth]: the month the final payment lands in. */
    fun payoffLabel(startingMonth: DayDate, months: Int, locale: Locale = Locale.getDefault()): String {
        val payoff = startingMonth.addingMonths(maxOf(0, months - 1))
        return AutomationSentences.monthLabel("%04d-%02d".format(payoff.year, payoff.month), locale)
    }

    /** "One time extra in Dec" — the simulator applies it to the month it opens in. */
    fun extraLabel(month: DayDate, locale: Locale = Locale.getDefault()): String =
        ReportStrings.format("One time extra in %@", AutomationSentences.monthLabel("%04d-%02d".format(month.year, month.month), locale), locale = locale)

    /** "5 yrs, 11 mos" — years first, so the headline number is the one that moves when a payment changes. */
    fun durationText(months: Int, locale: Locale = Locale.getDefault()): String {
        val years = months / 12
        val remainder = months % 12
        val yearPart = if (years > 0) ReportStrings.localized("%lld yrs", years.toLong(), locale = locale) else ""
        val monthPart = if (remainder > 0 || years == 0) ReportStrings.localized("%lld mos", remainder.toLong(), locale = locale) else ""
        return listOf(yearPart, monthPart).filter { it.isNotEmpty() }.joinToString(", ")
    }

    fun savingsText(interest: String, months: Int, locale: Locale = Locale.getDefault()): String =
        ReportStrings.format("Saves %1\$@ in interest and pays the loan off %2\$@ sooner.", interest, durationText(months, locale), locale = locale)
}

/**
 * YNAB's Loan Payoff Simulator: try a payment or a payoff date and see what it
 * costs. Read-only. The monthly payment is the single source of truth —
 * picking a payoff date solves back to the payment that reaches it.
 */
@Composable
fun LoanPayoffPlannerView(account: Account, config: LoanConfig, onDismiss: () -> Unit, startingMonth: DayDate = DayDate.today()) {
    val store = LocalBudgetStore.current
    var paymentText by remember { mutableStateOf(LoanEditor.amountText(config.minimumPayment)) }
    // A one-off payment on top of this month's.
    var extraText by remember { mutableStateOf("") }
    val balance = LoanConfig.owed(store.accounts.firstOrNull { it.id == account.id }?.balance ?: account.balance)
    // Falls back to the lender's minimum so the chart never blanks out mid-edit.
    val chosenPayment = LoanEditor.cents(paymentText)?.takeIf { it > 0 } ?: config.minimumPayment
    val extra = LoanEditor.cents(extraText)?.takeIf { it > 0 } ?: 0
    fun schedule(payment: Long, extra: Long = 0) = LoanAmortization.schedule(
        balance,
        config.annualRatePercent,
        payment,
        config.escrowOrFees ?: 0,
        if (extra > 0) mapOf(startingMonth to extra) else emptyMap(),
        startingMonth,
    )
    val minimum = schedule(config.minimumPayment)
    val chosen = schedule(chosenPayment, extra)
    val isAtMinimum = chosenPayment == config.minimumPayment && extra == 0L
    // Anything from next month to what the minimum achieves — further when the typed payment is below it.
    val optionCount = maxOf(minimum?.paymentCount ?: 0, chosen?.paymentCount ?: 0)

    Scaffold(topBar = { AppTopBar(L("Payoff Simulator"), onBack = onDismiss, actions = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("loanPlanner.done")) { Text(L("common.done")) } }) }) { padding ->
        FormColumn(padding) {
            FormSection(footer = L("Raising the payment or adding a one-off shortens the loan and cuts the interest. Nothing here is saved — it only shows what would happen.")) {
                FormRow(L("Required Minimum Payment"), value = store.displayBalance(config.minimumPayment))
                FormAmountRow(L("Monthly Payment"), paymentText, { paymentText = it }, fieldModifier = Modifier.testTag("loanPlanner.payment"))
                if (optionCount > 0) {
                    FormPickerRow(
                        L("Payoff Date"),
                        (1..optionCount).toList(),
                        chosen?.paymentCount ?: optionCount,
                        { LoanPlannerText.payoffLabel(startingMonth, it) },
                        { months ->
                            LoanAmortization.requiredPayment(balance, config.annualRatePercent, config.escrowOrFees ?: 0, months, startingMonth)?.let {
                                paymentText = LoanEditor.amountText(it)
                                extraText = ""
                            }
                        },
                        Modifier.testTag("loanPlanner.payoffDate"),
                    )
                }
                FormAmountRow(LoanPlannerText.extraLabel(startingMonth), extraText, { extraText = it }, fieldModifier = Modifier.testTag("loanPlanner.extra"))
            }
            if (chosen != null && minimum != null) {
                FormSection(L("Balance Over Time")) {
                    val chosenPoints = chosen.balanceOverTime(balance)
                    val series = buildList {
                        // The minimum track is the comparison: dashed and recessive, told apart without relying on hue.
                        if (!isAtMinimum) {
                            add(ChartSeries(L("Minimum payment"), minimum.balanceOverTime(balance).map { it.month.localDate.toEpochDay().toDouble() to it.balance / 100.0 }, MaterialTheme.colorScheme.onSurfaceVariant, dashed = true))
                        }
                        add(ChartSeries(L("Your payment"), chosenPoints.map { it.month.localDate.toEpochDay().toDouble() to it.balance / 100.0 }, MaterialTheme.colorScheme.primary))
                    }
                    val months = (minimum.balanceOverTime(balance) + chosenPoints).map { it.month }.distinct().sorted()
                    LineChart(
                        series,
                        xLabels = sparseLabels(months).map { it.localDate.toEpochDay().toDouble() to "${it.year}" },
                        yLabel = { units ->
                            CurrencyAmountFormat.string(roundedHalfAwayFromZero(units * 100).toLong(), store.currencyCode, store.useNarrowCurrencySymbol, numberFormat = store.numberFormat)
                        },
                        showsYAxis = !store.hideBalances,
                        showsLegend = !isAtMinimum,
                    )
                }
                FormSection(L("Remaining to Pay")) {
                    FormRow(L("Principal"), value = store.displayBalance(balance))
                    FormRow(L("Interest Remaining"), value = store.displayBalance(chosen.totalInterest))
                    FormRow(L("Total"), value = store.displayBalance(balance + chosen.totalInterest))
                    FormRow(L("Time Remaining"), value = LoanPlannerText.durationText(chosen.paymentCount))
                    // A plan slower than the minimum saves nothing, and "Saves $0.00 … 0 mos sooner" would say so badly.
                    val savings = LoanAmortization.savings(minimum, chosen)
                    if (!isAtMinimum && (savings.interest > 0 || savings.months > 0)) {
                        FormRow(LoanPlannerText.savingsText(store.displayBalance(savings.interest), savings.months))
                    }
                }
            } else {
                FormSection { FormRow(L("Payment doesn't cover interest")) }
            }
        }
    }
}
