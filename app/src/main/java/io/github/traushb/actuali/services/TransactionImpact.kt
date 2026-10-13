package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.database.fetchBudgetMonth
import io.github.traushb.actuali.services.database.fetchChildTransactions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** How a transaction change moved one budget category's available balance, shown by the impact popup. */
data class TransactionImpactCue(
    val categoryId: String,
    val categoryName: String,
    /** The budget month the balance belongs to ("2026-10"). */
    val month: String,
    val balanceBeforeCents: Long,
    val balanceAfterCents: Long,
) {
    val id: String get() = "$month-$categoryId"

    val deltaCents: Long get() = balanceAfterCents - balanceBeforeCents

    val isExpense: Boolean get() = deltaCents < 0
}

/** One category in one budget month, the unit a balance is compared by. */
data class TransactionImpactTarget(val month: String, val categoryId: String)

/** Month titles as the budget screens show them ("October 2026", "Oct 2026"). */
object MonthTitles {
    private fun date(month: String): LocalDate? = runCatching { LocalDate.parse("$month-01") }.getOrNull()

    fun title(month: String, locale: Locale = Locale.getDefault()): String =
        date(month)?.let { LocaleFormatting.format(it, "yMMMM", locale) } ?: month

    /** [title] abbreviated to a fixed-ish width for the toolbar stepper. */
    fun shortTitle(month: String, locale: Locale = Locale.getDefault()): String =
        date(month)?.let { LocaleFormatting.format(it, "yMMM", locale) } ?: month
}

/** Pure rules behind the balance impact popup. */
object TransactionImpact {
    /** "2026-10" for a YYYYMMDD day integer, the budget month it falls in. */
    fun month(date: Int): String = "%04d-%02d".format(date / 10000, (date % 10000) / 100)

    /**
     * One cue per category whose balance moved. Categories missing from either
     * snapshot (income, hidden away, deleted) and unchanged balances show
     * nothing. Ordered by month, then name, so the cards are stable.
     */
    fun cues(before: Map<TransactionImpactTarget, Pair<String, Long>>, after: Map<TransactionImpactTarget, Pair<String, Long>>): List<TransactionImpactCue> =
        before.keys.mapNotNull { target ->
            val was = before[target] ?: return@mapNotNull null
            val now = after[target] ?: return@mapNotNull null
            if (was.second == now.second) return@mapNotNull null
            TransactionImpactCue(target.categoryId, now.first, target.month, was.second, now.second)
        }.sortedWith(compareBy<TransactionImpactCue> { it.month }.thenBy { it.categoryName })

    /** How long the popup stays up before it dismisses itself. */
    const val AUTO_DISMISS_MILLIS = 4200L
}

/** The budget categories a set of transactions sits in, by month: a split's lines count, transfers and off-budget accounts don't. */
suspend fun BudgetStore.impactTargets(transactions: List<Transaction>): Set<TransactionImpactTarget> {
    val targets = HashSet<TransactionImpactTarget>()
    val offBudget = offBudgetAccountIds
    for (tx in transactions) {
        if (tx.transferId != null || tx.transferAcct != null || tx.accountId in offBudget) continue
        val month = TransactionImpact.month(tx.date)
        val categoryIds = if (tx.isParent) {
            databaseForLogger?.let { db -> runCatching { db.fetchChildTransactions(tx.id) }.getOrDefault(emptyList()).mapNotNull { it.categoryId } }.orEmpty()
        } else {
            listOfNotNull(tx.categoryId)
        }
        categoryIds.mapTo(targets) { TransactionImpactTarget(month, it) }
    }
    return targets
}

/** The same, for a transaction form that is about to be saved. */
fun BudgetStore.impactTargets(form: TransactionForm): Set<TransactionImpactTarget> {
    if (form.type == io.github.traushb.actuali.models.TransactionType.Transfer || form.accountId in offBudgetAccountIds) return emptySet()
    val month = TransactionImpact.month(Transaction.yyyymmdd(form.date))
    val ids = if (form.splits.isEmpty()) listOf(form.categoryId) else form.splits.map { it.categoryId }
    return ids.filterNotNull().mapTo(HashSet()) { TransactionImpactTarget(month, it) }
}

private suspend fun BudgetStore.availableBalances(
    targets: Set<TransactionImpactTarget>,
    refreshedMonth: BudgetMonth? = null,
): Map<TransactionImpactTarget, Pair<String, Long>> {
    val database = databaseForLogger ?: return emptyMap()
    val result = HashMap<TransactionImpactTarget, Pair<String, Long>>()
    for (month in targets.map { it.month }.toSet()) {
        val budget = if (refreshedMonth?.month == month) refreshedMonth else runCatching { database.fetchBudgetMonth(month) }.getOrNull()
        budget ?: continue
        for (category in budget.categoryBudgets) {
            val target = TransactionImpactTarget(month, category.categoryId)
            if (target in targets) result[target] = category.categoryName to category.available
        }
    }
    return result
}

/**
 * Run a change to transactions and show the popup for the categories it
 * moved. [targets] are read before the change; callers that also change where
 * a row lands (an edit) pass both the old and the new categories.
 */
suspend fun <T> BudgetStore.withImpactCue(targets: Set<TransactionImpactTarget>, work: suspend () -> T): T {
    // Hidden balances stay hidden: no popup rather than one full of dots.
    val database = databaseForLogger
    if (!showTransactionImpactCue || hideBalances || targets.isEmpty() || database == null) return work()
    val budgetId = currentBudgetId
    val displayedBefore = currentBudgetMonth
    val before = availableBalances(targets)
    val result = work()
    if (databaseForLogger !== database || currentBudgetId != budgetId || !showTransactionImpactCue || hideBalances) return result
    // A successful write refreshes the displayed month already; reuse that
    // snapshot when it changed instead of walking its history again.
    val refreshedMonth = currentBudgetMonth.takeIf { it != displayedBefore }
    val after = availableBalances(targets, refreshedMonth)
    if (databaseForLogger !== database || currentBudgetId != budgetId) return result
    showImpactCues(TransactionImpact.cues(before, after))
    return result
}

/** Convenience for changes described by the transactions they touch. */
suspend fun <T> BudgetStore.withImpactCue(transactions: List<Transaction>, work: suspend () -> T): T {
    if (!showTransactionImpactCue || hideBalances) return work()
    return withImpactCue(impactTargets(transactions), work)
}

private fun BudgetStore.showImpactCues(cues: List<TransactionImpactCue>) {
    if (!showTransactionImpactCue || hideBalances) return
    impactDismissTask?.cancel()
    transactionImpactCues = cues
    if (cues.isEmpty()) return
    impactDismissTask = scope.launch {
        delay(TransactionImpact.AUTO_DISMISS_MILLIS)
        transactionImpactCues = emptyList()
    }
}

fun BudgetStore.dismissTransactionImpactCues() {
    impactDismissTask?.cancel()
    transactionImpactCues = emptyList()
}

/** "Groceries: 100.00 to 90.00, down 10.00" for TalkBack. */
fun BudgetStore.spokenImpactText(cue: TransactionImpactCue): String {
    val before = displayBalance(cue.balanceBeforeCents)
    val after = displayBalance(cue.balanceAfterCents)
    val change = displayBalance(abs(cue.deltaCents))
    val name = if (cue.month == TransactionImpact.month(Transaction.yyyymmdd(LocalDate.now()))) cue.categoryName
    else L("%@ (%@)", cue.categoryName, MonthTitles.title(cue.month))
    return if (cue.isExpense) L("%@: %@ to %@, down %@", name, before, after, change) else L("%@: %@ to %@, up %@", name, before, after, change)
}
