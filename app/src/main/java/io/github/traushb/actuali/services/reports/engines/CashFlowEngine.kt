package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.reports.CashFlowMeta
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.monthEnd
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import java.time.LocalDate

data class CashFlowPoint(
    val periodStart: LocalDate,
    val incomeCents: Long,
    /** Positive value. */
    val expenseCents: Long,
)

data class CashFlowData(val points: List<CashFlowPoint>)

object CashFlowEngine {
    fun compute(
        meta: CashFlowMeta?,
        transactions: List<Transaction>,
        offBudgetAccountIds: Set<String> = emptySet(),
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
    ): CashFlowData {
        val (start, end) = TimeFrame.resolve(meta?.timeFrame, today)

        // Match the WebUI's cash flow queries (cash-flow-spreadsheet.tsx):
        // only on-budget accounts, and no transfer legs — a transfer's equal
        // and opposite legs would otherwise inflate both income and expense.
        val filtered = transactions.filter {
            !it.tombstone && it.transferAcct == null && it.accountId !in offBudgetAccountIds &&
                ConditionsFilter.matches(it, meta?.conditions, meta?.conditionsOp, context)
        }

        val points = mutableListOf<CashFlowPoint>()
        var month = start.monthStart()
        while (month <= end) {
            val startYMD = month.ymd
            val endYMD = month.monthEnd().ymd
            var income = 0L
            var expense = 0L
            for (tx in filtered) {
                if (tx.date < startYMD || tx.date > endYMD) continue
                if (tx.amount >= 0) income += tx.amount else expense += -tx.amount
            }
            points += CashFlowPoint(month, income, expense)
            month = month.plusMonths(1)
        }
        return CashFlowData(points)
    }
}
