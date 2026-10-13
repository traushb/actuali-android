package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Exactly which rows and columns a schedule write touches, computed as pure
 * data.
 *
 * The row shapes are the part of this feature that has to match loot-core
 * byte for byte — a wrong column leaves a schedule the web renders blank.
 * Keeping the plan free of the sync client, the network and the clock makes
 * that shape directly assertable in tests.
 */
data class ScheduleWritePlan(
    val scheduleId: String,
    val writes: List<RowWrite>,
    /**
     * Final rule conditions, for the local JSON-path cache. Null when the plan
     * doesn't touch the rule (a delete, or a schedule-columns-only update).
     */
    val conditions: List<JsonObject>?,
) {
    data class RowWrite(val dataset: String, val row: String, val fields: List<Pair<String, Any?>>)
}

object ScheduleWriteBuilder {
    private fun linkScheduleActions(scheduleId: String): List<JsonObject> =
        listOf(JsonObject(linkedMapOf("op" to JsonPrimitive("link-schedule"), "value" to JsonPrimitive(scheduleId))))

    /**
     * Port of loot-core `createSchedule`: one rule, one next-date row, one
     * schedule row. Ids and [now] are injected so a test can assert the whole plan.
     */
    fun createPlan(
        fields: ScheduleFormFields,
        scheduleId: String,
        ruleId: String,
        nextDateRowId: String,
        now: Long,
        today: DayDate = DayDate.today(),
    ): ScheduleWritePlan {
        val conditions = ScheduleConditions.build(fields, emptyList())
        val date = fields.date ?: throw ScheduleWriteError.DateRequired
        val nextDate = ScheduleConditions.nextDate(date, today) ?: throw ScheduleWriteError.UnsupportedRecurrence

        return ScheduleWritePlan(
            scheduleId = scheduleId,
            writes = listOf(
                ScheduleWritePlan.RowWrite(
                    "rules", ruleId,
                    listOf(
                        "stage" to null,
                        "conditions_op" to "and",
                        "conditions" to ScheduleConditions.serialize(conditions),
                        "actions" to ScheduleConditions.serialize(linkScheduleActions(scheduleId)),
                        "tombstone" to 0,
                    ),
                ),
                // Both halves of the next-date pair start equal, which is what
                // makes the local value the effective one until something
                // resets the base.
                ScheduleWritePlan.RowWrite(
                    "schedules_next_date", nextDateRowId,
                    listOf(
                        "schedule_id" to scheduleId,
                        "local_next_date" to nextDate.yyyymmdd,
                        "local_next_date_ts" to now,
                        "base_next_date" to nextDate.yyyymmdd,
                        "base_next_date_ts" to now,
                    ),
                ),
                ScheduleWritePlan.RowWrite(
                    "schedules", scheduleId,
                    listOf(
                        "rule" to ruleId,
                        "name" to fields.normalizedName,
                        "posts_transaction" to if (fields.postsTransaction) 1 else 0,
                        "custom_upcoming_length" to fields.customUpcomingLength,
                        "completed" to 0,
                        "tombstone" to 0,
                    ),
                ),
            ),
            conditions = conditions,
        )
    }

    /**
     * Port of loot-core `updateSchedule`.
     *
     * [resetRequested] forces the next date to be recomputed; otherwise it is
     * recomputed only when the account or the date condition actually
     * changed. Account counts because a schedule on a closed account is never
     * advanced, so moving it to an open one has to catch it up.
     */
    fun updatePlan(
        schedule: ScheduleSummary,
        fields: ScheduleFormFields,
        now: Long,
        today: DayDate = DayDate.today(),
        resetRequested: Boolean = false,
        newNextDateRowId: () -> String = { UUID.randomUUID().toString().lowercase() },
        newRuleId: () -> String = { UUID.randomUUID().toString().lowercase() },
    ): ScheduleWritePlan {
        val existingConditions = ScheduleConditions.parse(schedule.conditionsJSON)
        val existingActions = ScheduleConditions.parse(schedule.actionsJSON)

        val scheduleConditions = ScheduleConditions.build(fields, existingConditions)
        val merged = ScheduleConditions.merge(existingConditions, scheduleConditions)

        val writes = mutableListOf<ScheduleWritePlan.RowWrite>()

        // A schedule whose rule went missing gets a fresh one rather than
        // dropping every field change on the floor (upstream fixRuleForSchedule).
        // This is the one case where writing `rule` is correct — upstream's
        // "you cannot change the rule" guard is about swapping a live rule.
        val ruleId = schedule.ruleId ?: newRuleId()
        val ruleFields = mutableListOf<Pair<String, Any?>>("conditions" to ScheduleConditions.serialize(merged))
        if (schedule.ruleId == null) {
            ruleFields += "stage" to null
            ruleFields += "conditions_op" to "and"
            ruleFields += "actions" to ScheduleConditions.serialize(linkScheduleActions(schedule.id))
            ruleFields += "tombstone" to 0
        } else {
            ScheduleConditions.syncedActions(merged, existingActions)?.let {
                ruleFields += "actions" to ScheduleConditions.serialize(it)
            }
        }
        writes += ScheduleWritePlan.RowWrite("rules", ruleId, ruleFields)

        // Next date: reset when forced, or when account/date changed.
        val oldIndices = ScheduleConditions.extract(existingConditions)
        val newIndices = ScheduleConditions.extract(merged)
        val accountChanged = !ScheduleConditions.conditionsEqual(
            ScheduleConditions.condition(oldIndices.account, existingConditions),
            ScheduleConditions.condition(newIndices.account, merged),
        )
        val oldDateCondition = ScheduleConditions.condition(oldIndices.date, existingConditions)
        val newDateCondition = ScheduleConditions.condition(newIndices.date, merged)
        // Compare the date SEMANTICALLY, not as raw JSON. A config written by
        // the web omits keys `RecurConfig.json` always emits (interval,
        // skipWeekend, weekendSolveMode, endMode), so a byte comparison reports
        // a change on every save — resetting the next date and undoing a skip.
        val dateChanged = oldDateCondition?.get("op").stringOrNull != newDateCondition?.get("op").stringOrNull ||
            parsedDate(oldDateCondition) != parsedDate(newDateCondition)

        val date = fields.date
        val nextDate = date?.let { ScheduleConditions.nextDate(it, today) }
        if ((resetRequested || accountChanged || dateChanged) && nextDate != null) {
            // A schedule whose next-date row went missing gets a fresh one
            // rather than silently skipping the write; `schedule_id` is only
            // needed on that new row.
            val rowId = schedule.nextDateRowId ?: newNextDateRowId()
            val nextFields = mutableListOf<Pair<String, Any?>>()
            if (schedule.nextDateRowId == null) {
                nextFields += "schedule_id" to schedule.id
                nextFields += "local_next_date" to nextDate.yyyymmdd
                nextFields += "local_next_date_ts" to now
            }
            nextFields += "base_next_date" to nextDate.yyyymmdd
            nextFields += "base_next_date_ts" to now
            writes += ScheduleWritePlan.RowWrite("schedules_next_date", rowId, nextFields)
        }

        val scheduleFields = mutableListOf<Pair<String, Any?>>(
            "name" to fields.normalizedName,
            "posts_transaction" to if (fields.postsTransaction) 1 else 0,
            "custom_upcoming_length" to fields.customUpcomingLength,
        )
        if (schedule.ruleId == null) scheduleFields += "rule" to ruleId
        writes += ScheduleWritePlan.RowWrite("schedules", schedule.id, scheduleFields)

        return ScheduleWritePlan(schedule.id, writes, merged)
    }

    /**
     * Port of loot-core `deleteSchedule`, as tombstones.
     *
     * loot-core hard-deletes both rows locally and lets the CRDT layer carry
     * the tombstone; here the tombstone IS the write. The rule goes too —
     * an orphaned rule would keep matching imported transactions forever.
     */
    fun deletePlan(schedule: ScheduleSummary): ScheduleWritePlan {
        val writes = mutableListOf(ScheduleWritePlan.RowWrite("schedules", schedule.id, listOf("tombstone" to 1)))
        schedule.ruleId?.let { writes += ScheduleWritePlan.RowWrite("rules", it, listOf("tombstone" to 1)) }
        return ScheduleWritePlan(schedule.id, writes, null)
    }

    /**
     * Set a schedule's next date. Port of loot-core `setNextDate`.
     *
     * `reset: true` moves the canonical base (a user-visible change of when
     * the schedule is next due). `reset: false` moves only the local
     * override, pinning `local_next_date_ts` to the CURRENT
     * `base_next_date_ts` so the override stays effective under the
     * v_schedules rule until another client resets the base — the branch the
     * auto-poster already uses.
     */
    fun nextDatePlan(schedule: ScheduleSummary, newNextDate: DayDate, reset: Boolean, now: Long): ScheduleWritePlan? {
        val rowId = schedule.nextDateRowId ?: return null
        val fields: List<Pair<String, Any?>> = if (reset) {
            listOf("base_next_date" to newNextDate.yyyymmdd, "base_next_date_ts" to now)
        } else {
            listOf("local_next_date" to newNextDate.yyyymmdd, "local_next_date_ts" to schedule.baseNextDateTs)
        }
        return ScheduleWritePlan(schedule.id, listOf(ScheduleWritePlan.RowWrite("schedules_next_date", rowId, fields)), null)
    }

    /** Update only the schedule row's own columns (complete / restart). */
    fun scheduleColumnsPlan(scheduleId: String, fields: List<Pair<String, Any?>>): ScheduleWritePlan =
        ScheduleWritePlan(scheduleId, listOf(ScheduleWritePlan.RowWrite("schedules", scheduleId, fields)), null)

    /**
     * Parse a raw date condition into its semantic form so two encodings of
     * the same recurrence compare equal. `RecurConfig.parse` normalises the
     * optional fields, which is exactly the normalisation this needs.
     */
    private fun parsedDate(condition: JsonObject?): ScheduleDateCondition? {
        val value = condition?.get("value") ?: return null
        value.stringOrNull?.let { iso -> return DayDate.fromIso(iso)?.let { ScheduleDateCondition.Fixed(it) } }
        val json = value as? JsonObject ?: return null
        return RecurConfig.parse(json)?.let { ScheduleDateCondition.Recurring(it) }
    }
}
