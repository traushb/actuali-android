package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.CreditCardConfig
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.StatementDueRequest
import io.github.traushb.actuali.services.database.fetchCreditCardStatementDues
import io.github.traushb.actuali.services.database.fetchDiscoveryTransactions
import io.github.traushb.actuali.services.database.fetchPaidScheduleIds
import io.github.traushb.actuali.services.database.fetchSchedulePaymentDates
import io.github.traushb.actuali.services.database.fetchSchedules
import io.github.traushb.actuali.services.database.fetchTransactions
import io.github.traushb.actuali.services.database.latestTransactionDate
import io.github.traushb.actuali.services.database.setTransactionSchedule
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.ScheduleDiscovery
import io.github.traushb.actuali.services.schedules.ScheduleFormFields
import io.github.traushb.actuali.services.schedules.ScheduleRegisterProjection
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import io.github.traushb.actuali.services.schedules.ScheduleStatusCalculator
import io.github.traushb.actuali.services.schedules.UpcomingScheduleEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = Logger("BudgetStore")

internal typealias ScheduleSnapshot = Triple<List<ScheduleSummary>, Map<String, ScheduleStatus>, Map<String, Set<DayDate>>>

// Scheduled Transactions

/**
 * Refresh the schedules cache and recompute every status. Statuses depend on
 * today's date as well as on transactions, so they're derived on every refresh
 * rather than cached against a schedule row.
 */
suspend fun BudgetStore.loadSchedules() {
    scheduleLoadError = null
    val database = database ?: run {
        publishSchedules(Triple(emptyList(), emptyMap(), emptyMap()))
        schedulesLoaded = true
        return
    }
    try {
        val fetched = fetchSchedules(database, upcomingScheduledTransactionLength)
        if (this.database !== database) return
        publishSchedules(fetched)
        schedulesLoaded = true
    } catch (e: CancellationException) {
        return
    } catch (e: Exception) {
        if (this.database !== database) return
        publishSchedules(Triple(emptyList(), emptyMap(), emptyMap()))
        scheduleLoadError = e.localizedMessage
        schedulesLoaded = true
    }
}

fun BudgetStore.upcomingRegisterEntries(accountId: String? = null): List<UpcomingScheduleEntry> =
    ScheduleRegisterProjection.upcomingEntries(
        schedules = schedules,
        statuses = scheduleStatuses,
        accountId = accountId,
        activeAccountIds = accounts.filter { !it.closed }.mapTo(HashSet()) { it.id },
        startingBalance = accountId?.let { id -> accounts.firstOrNull { it.id == id }?.balance },
    )

internal fun BudgetStore.publishSchedules(fetched: ScheduleSnapshot) {
    schedules = fetched.first
    scheduleStatuses = fetched.second
    schedulePaymentDates = fetched.third
}

internal suspend fun BudgetStore.fetchSchedules(database: BudgetDatabase, upcomingLength: String?): ScheduleSnapshot {
    val loaded = database.fetchSchedules()
    val today = DayDate.today()
    val paid = database.fetchPaidScheduleIds(loaded, today)
    val paymentDates = database.fetchSchedulePaymentDates(loaded)
    val statuses = loaded.associate { schedule ->
        schedule.id to ScheduleStatusCalculator.status(
            schedule.nextDate, schedule.completed, schedule.id in paid, schedule.customUpcomingLength ?: upcomingLength, today,
        )
    }
    return Triple(loaded.sortedWith(scheduleOrder), statuses, paymentDates)
}

/** Loads the latest statement dues for all active credit cards. */
suspend fun BudgetStore.loadCreditCardStatementDues(today: DayDate = DayDate.today()) {
    val database = database ?: run {
        creditCardStatementDues = emptyMap()
        return
    }
    val fetched = fetchCreditCardStatementDues(database, accounts, creditCardConfigs, today)
    if (this.database === database) creditCardStatementDues = fetched
}

internal suspend fun fetchCreditCardStatementDues(
    database: BudgetDatabase,
    accounts: List<Account>,
    configs: Map<String, CreditCardConfig>,
    today: DayDate = DayDate.today(),
): Map<String, List<CreditCardCycle.StatementDue>> {
    val requests = mutableListOf<StatementDueRequest>()
    for (account in accounts) {
        if (account.closed) continue
        val config = configs[account.id] ?: continue
        val cycle = CreditCardCycle(config.statementDay, config.paymentDue)
        // Keep recent closed statements for the Bills history. The 60-day
        // maximum means these three cover every statement still pending.
        val recentStatements = cycle.recentStatementCycles(today).reversed()
        for (statement in recentStatements) requests += StatementDueRequest(account.id, statement.end, statement.dueDate, account.balance)
        if (recentStatements.none { today <= it.dueDate }) {
            val statementDate = cycle.cycleRange(today).second
            requests += StatementDueRequest(account.id, statementDate, cycle.dueDate(statementDate), account.balance)
        }
    }
    if (requests.isEmpty()) return emptyMap()
    return try {
        database.fetchCreditCardStatementDues(requests)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to load credit card statement dues: $e")
        emptyMap()
    }
}

/**
 * Explicit `sort_order` first (the web's manual ordering), then soonest next
 * date, then name — so a budget that has never been reordered still reads sensibly.
 */
private val scheduleOrder = Comparator<ScheduleSummary> { a, b ->
    val x = a.sortOrder
    val y = b.sortOrder
    when {
        x != null && y != null && x != y -> return@Comparator x.compareTo(y)
        x == null && y != null -> return@Comparator 1
        x != null && y == null -> return@Comparator -1
    }
    val dx = a.nextDate
    val dy = b.nextDate
    when {
        dx != null && dy != null && dx != dy -> return@Comparator dx.compareTo(dy)
        dx == null && dy != null -> return@Comparator 1
        dx != null && dy == null -> return@Comparator -1
    }
    String.CASE_INSENSITIVE_ORDER.compare(a.name.orEmpty(), b.name.orEmpty())
}

suspend fun BudgetStore.createSchedule(fields: ScheduleFormFields): String = createSchedules(listOf(fields))[0]

/**
 * Create one or more schedules, refreshing once at the end. "Find schedules"
 * creates a whole selection at a time, and a refresh per schedule would
 * re-read every account, transaction and payee for nothing.
 */
suspend fun BudgetStore.createSchedules(fields: List<ScheduleFormFields>): List<String> {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val ids = mutableListOf<String>()
    try {
        for (field in fields) ids += syncClient.createSchedule(field)
    } catch (e: Exception) {
        // Whatever got through is already saved; show it before surfacing the failure.
        refreshDataOnly()
        throw e
    }
    refreshDataOnly()
    return ids
}

suspend fun BudgetStore.updateSchedule(schedule: ScheduleSummary, fields: ScheduleFormFields, resetNextDate: Boolean = false) =
    scheduleWrite { it.updateSchedule(schedule, fields, resetNextDate) }

suspend fun BudgetStore.deleteSchedule(schedule: ScheduleSummary) = scheduleWrite { it.deleteSchedule(schedule) }

suspend fun BudgetStore.skipScheduleNextDate(schedule: ScheduleSummary) = scheduleWrite { it.skipScheduleNextDate(schedule) }

suspend fun BudgetStore.postScheduleTransaction(schedule: ScheduleSummary, today: Boolean) = scheduleWrite { it.postScheduleTransaction(schedule, today) }

suspend fun BudgetStore.setScheduleCompleted(schedule: ScheduleSummary, completed: Boolean) = scheduleWrite { it.setScheduleCompleted(schedule, completed) }

private suspend fun BudgetStore.scheduleWrite(write: suspend (io.github.traushb.actuali.services.sync.SyncClient) -> Unit) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    write(syncClient)
    refreshDataOnly()
}

suspend fun BudgetStore.fetchScheduleTransactions(scheduleId: String): List<Transaction> {
    val database = database ?: return emptyList()
    return runCatching { withContext(Dispatchers.IO) { database.fetchTransactions(scheduleId = scheduleId) } }.getOrDefault(emptyList())
}

/**
 * Link transactions to a schedule, or unlink them by passing null.
 * `transactions.schedule` is already a syncable field, so this needs no new
 * write path.
 */
suspend fun BudgetStore.linkTransactions(transactions: List<Transaction>, scheduleId: String?) {
    val database = database ?: throw BudgetStoreError.SyncNotConfigured
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    if (transactions.isEmpty()) return
    withContext(Dispatchers.IO) { database.setTransactionSchedule(transactions.map { it.id }, scheduleId) }
    syncClient.updateTransactions(transactions.map { it.copy(schedule = scheduleId) }, setOf("schedule"))
    refreshDataOnly()
}

/** Scan transaction history for repeating payments, off the main thread (it is CPU-bound). */
suspend fun BudgetStore.discoverSchedules(): List<ScheduleDiscovery.Proposal> {
    val database = database ?: return emptyList()
    val accounts = accounts
    return withContext(Dispatchers.Default) {
        runCatching {
            ScheduleDiscovery.discover(
                accounts,
                loadCandidates = { accountId, notBefore -> database.fetchDiscoveryTransactions(accountId, notBefore) },
                latestDate = { database.latestTransactionDate(it) },
            )
        }.getOrDefault(emptyList())
    }
}
