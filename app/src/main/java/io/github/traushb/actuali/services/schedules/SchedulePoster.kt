package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.Schedule
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.BudgetStoreError
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.fetchPostableSchedules
import io.github.traushb.actuali.services.database.hasTransaction
import io.github.traushb.actuali.services.database.transferAccountId
import io.github.traushb.actuali.services.database.transferPayeeId
import io.github.traushb.actuali.services.integer
import io.github.traushb.actuali.services.rules.RulesEngine
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

private val logger = Logger("SchedulePoster")

/**
 * The two server-visible writes the poster performs, abstracted so tests can
 * record them. `SyncClient` is the production implementation.
 */
interface SchedulePostingActions {
    suspend fun createTransaction(transaction: Transaction)

    suspend fun createTransfer(source: Transaction, target: Transaction)

    suspend fun advanceScheduleNextDate(nextDateRowId: String, newNextDate: Int, baseNextDateTs: Long?)
}

/** Both legs of the transfer a schedule posts when its payee is a transfer payee; null otherwise. */
fun scheduledTransfer(transaction: Transaction, database: BudgetDatabase): Pair<Transaction, Transaction>? {
    val transferAccountId = database.transferAccountId(transaction.payeeId) ?: return null
    if (transferAccountId == transaction.accountId) throw BudgetStoreError.TransferAccountsMatch
    val sourcePayeeId = database.transferPayeeId(transaction.accountId) ?: throw BudgetStoreError.TransferPayeeMissing
    val target = Transaction(
        id = UUID.randomUUID().toString().lowercase(),
        accountId = transferAccountId,
        date = transaction.date,
        amount = -transaction.amount,
        payeeId = sourcePayeeId,
        payeeName = null,
        categoryId = null,
        categoryName = null,
        notes = transaction.notes,
        cleared = transaction.cleared,
        reconciled = false,
        transferId = transaction.id,
        isParent = false,
        parentId = null,
        tombstone = false,
        sortOrder = null,
        importedPayee = null,
    )
    return transaction.copy(transferId = target.id) to target
}

/**
 * Posts due automatic schedules and advances their next dates. Mirrors the
 * posting half of loot-core `advanceSchedulesService`.
 *
 * Every write here lands on the user's real Actual server, so failure
 * behavior is conservative throughout: a fetch error is a logged no-op, a
 * schedule-level error skips only that schedule, and the once-per-day gate is
 * set only after a pass with zero schedule-level errors.
 *
 * Overlapping `runIfNeeded` calls (foreground flapping) must not interleave:
 * the gate only moves at the END of a clean pass, so two concurrent passes
 * would both clear it and could double-post. The try-lock below makes a
 * second caller return immediately instead.
 */
class SchedulePoster(
    val database: BudgetDatabase,
    val actions: SchedulePostingActions,
    val defaults: Defaults = Defaults.standard,
) {
    private val running = Mutex()

    private fun gateKey(budgetId: String) = "lastScheduleRun-$budgetId"

    /**
     * Post every due occurrence of every postable schedule, advancing next
     * dates as it goes. At most one CLEAN pass per calendar day per budget.
     * A call that overlaps an in-flight pass returns 0 without running.
     * @return number of transactions posted.
     */
    suspend fun runIfNeeded(budgetId: String, today: DayDate = DayDate.today()): Int {
        check(budgetId.isNotEmpty()) { "empty budgetId would collapse the once-per-day gate across budgets" }
        if (!running.tryLock()) return 0
        try {
            if (defaults.integer(gateKey(budgetId)) == today.yyyymmdd) return 0
            val schedules = try {
                database.fetchPostableSchedules()
            } catch (e: Exception) {
                // No gate write: the next trigger (same day included) retries.
                logger.error("fetchPostableSchedules failed - skipping pass: $e")
                return 0
            }
            var posted = 0
            var clean = true
            for (schedule in schedules) {
                try {
                    posted += process(schedule, today)
                } catch (e: Exception) {
                    // NEVER abort the pass — other schedules must still post.
                    clean = false
                    logger.error("Schedule ${schedule.id} failed to post/advance: $e")
                }
            }
            if (clean) defaults.set(gateKey(budgetId), today.yyyymmdd)
            if (posted > 0) logger.info("Posted $posted scheduled transaction(s)")
            return posted
        } finally {
            running.unlock()
        }
    }

    /** Post all due occurrences of one schedule, advancing after each. */
    private suspend fun process(schedule: Schedule, today: DayDate): Int {
        var current = schedule.nextDate
        var posted = 0
        var iterations = 0
        while (current <= today && iterations < ITERATION_CAP) {
            iterations += 1
            // Dedup guard (loot-core parity): an alive transaction already
            // linked to this schedule on/after the occurrence means the user
            // (or another client) covered it — advance without posting.
            if (!database.hasTransaction(schedule.id, current.yyyymmdd)) {
                var txn = Transaction(
                    id = UUID.randomUUID().toString().lowercase(),
                    accountId = schedule.accountId,
                    date = current.yyyymmdd,
                    amount = schedule.amount?.postAmount ?: 0,
                    payeeId = schedule.payeeId,
                    payeeName = null,
                    categoryId = schedule.categoryId,
                    categoryName = null,
                    notes = null,
                    cleared = false,
                    reconciled = false,
                    transferId = null,
                    isParent = false,
                    parentId = null,
                    tombstone = false,
                    sortOrder = null,
                    importedPayee = null,
                    schedule = schedule.id,
                )
                // Ordinary schedules still go through the existing rules
                // path. Applying their actions here too would run one-off
                // schedule actions twice in createTransaction.
                if (database.transferAccountId(txn.payeeId) != null) {
                    val result = RulesEngine.apply(schedule.actions, txn, schedule.id)
                    if (!result.isDeleted) {
                        txn = result.transaction.copy(schedule = schedule.id)
                        val transfer = scheduledTransfer(txn, database)
                        if (transfer != null) actions.createTransfer(transfer.first, transfer.second) else actions.createTransaction(txn)
                    }
                } else {
                    actions.createTransaction(txn)
                }
                // Keep the existing schedule semantics: a delete action
                // consumes this occurrence and still advances the schedule.
                posted += 1
            }

            // One-off and unsupported-recurrence schedules post at most once
            // and never advance; completion is left to the web app.
            val condition = schedule.dateCondition as? ScheduleDateCondition.Recurring ?: break
            val next = ScheduleRecurrence.nextOccurrence(condition.config, current.addingDays(1))
            // Ended, or weekend-solve pinned the date: treat as not-advanced and stop.
            if (next == null || next <= current) break
            actions.advanceScheduleNextDate(schedule.nextDateRowId, next.yyyymmdd, schedule.baseNextDateTs)
            current = next
        }
        return posted
    }

    private companion object {
        /**
         * Hard stop for a single schedule's catch-up loop: 200 occurrences is
         * far beyond anything sane to backfill and guards against recurrence
         * bugs spinning forever.
         */
        const val ITERATION_CAP = 200
    }
}
