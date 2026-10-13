package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.RecurConfig
import kotlin.math.floor

/** Amount condition on a schedule's rule. */
sealed interface ScheduledAmount {
    /** Cents. */
    data class Fixed(val cents: Long) : ScheduledAmount

    /** num1, num2 from an isbetween condition (cents). */
    data class Range(val num1: Long, val num2: Long) : ScheduledAmount

    /**
     * Amount a posted transaction should carry, mirroring loot-core
     * `getScheduledAmount`: `Math.round((num1 + num2) / 2)`. JS `Math.round`
     * rounds half toward +∞, which matters for negative cents:
     * (-3 + -4) / 2 = -3.5 → -3.
     */
    val postAmount: Long
        get() = when (this) {
            is Fixed -> cents
            is Range -> floor((num1 + num2).toDouble() / 2 + 0.5).toLong()
        }
}

/**
 * The rule's date condition: a one-off day, a recurrence, or a shape the
 * port can't advance. Upstream posting never consults this — getStatus works
 * off the stored next_date, and only the ADVANCE needs the recurrence — so
 * `Unsupported` posts the stored due occurrence once and never advances,
 * exactly like `Fixed`.
 */
sealed interface ScheduleDateCondition {
    data class Fixed(val day: DayDate) : ScheduleDateCondition
    data class Recurring(val config: RecurConfig) : ScheduleDateCondition
    data object Unsupported : ScheduleDateCondition
}

/**
 * A schedule eligible for auto-posting, read from the synced Actual tables
 * (`schedules` + `schedules_next_date` + the linked rule's conditions).
 */
data class Schedule(
    val id: String,
    val name: String?,
    /**
     * Effective next occurrence, per loot-core's v_schedules view:
     * `local_next_date` when `local_next_date_ts == base_next_date_ts`,
     * otherwise `base_next_date`.
     */
    val nextDate: DayDate,
    /** `schedules_next_date.id` — target row for the advance write after posting. */
    val nextDateRowId: String,
    /**
     * `schedules_next_date.base_next_date_ts` (ms epoch); the advance write
     * sets `local_next_date_ts` to this value (loot-core setNextDate). NULL
     * in the row stays null here — the web posts such schedules and its
     * advance writes a NULL local_next_date_ts, so ours must too.
     */
    val baseNextDateTs: Long?,
    val accountId: String,
    val payeeId: String?,
    /**
     * From the linked rule's `set category` action, when present. Surfaced
     * here because the RulesEngine can't apply the rule at post time — its
     * recurring-date condition is unsupported.
     */
    val categoryId: String?,
    /**
     * Null when the rule has no amount condition. loot-core's
     * `getScheduledAmount(null)` posts 0 in that case — we keep null here and
     * let the poster decide.
     */
    val amount: ScheduledAmount?,
    val dateCondition: ScheduleDateCondition,
    /**
     * Actions on the linked rule. The poster applies these after selecting
     * the due occurrence and before constructing transfer legs.
     */
    val actions: List<Rule.Action>,
)
