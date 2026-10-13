package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.ScheduleSummary

data class UpcomingScheduleEntry(
    val schedule: ScheduleSummary,
    val date: DayDate,
    val runningBalance: Long?,
) {
    val id: String get() = schedule.id
}

object ScheduleRegisterProjection {
    fun upcomingEntries(
        schedules: List<ScheduleSummary>,
        statuses: Map<String, ScheduleStatus>,
        accountId: String? = null,
        activeAccountIds: Set<String>,
        startingBalance: Long? = null,
        today: DayDate = DayDate.today(),
    ): List<UpcomingScheduleEntry> {
        val upcoming = schedules.mapNotNull { schedule ->
            val date = schedule.nextDate ?: return@mapNotNull null
            val scheduleAccountId = schedule.accountId ?: return@mapNotNull null
            val status = statuses[schedule.id]
            val include = date >= today &&
                !schedule.completed &&
                scheduleAccountId in activeAccountIds &&
                (status == ScheduleStatus.Upcoming || status == ScheduleStatus.Due) &&
                (accountId == null || scheduleAccountId == accountId)
            if (include) schedule to date else null
        }.sortedWith(compareBy<Pair<ScheduleSummary, DayDate>> { it.second }.thenBy { it.first.sortOrder ?: 0.0 })

        // Accumulate forward, then display newest first like the transaction register.
        var balance = startingBalance
        return upcoming.map { (schedule, date) ->
            balance = balance?.plus(schedule.postAmount)
            UpcomingScheduleEntry(schedule, date, balance)
        }.reversed()
    }
}
