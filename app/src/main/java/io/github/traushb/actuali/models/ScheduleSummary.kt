package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.schedules.DayDate
import java.util.Locale

/**
 * How a schedule's amount is matched, mirroring the rule condition's `op`.
 * Drives the list's amount prefix and the editor's operator picker.
 */
enum class ScheduleAmountOp(val rawValue: String) {
    IsExactly("is"),
    IsApprox("isapprox"),
    IsBetween("isbetween"),
    ;

    fun label(locale: Locale = Locale.getDefault()): String = when (this) {
        IsExactly -> ReportStrings.text("is exactly", locale)
        IsApprox -> ReportStrings.text("is approximately", locale)
        IsBetween -> ReportStrings.text("is between", locale)
    }

    companion object {
        fun fromRaw(raw: String?): ScheduleAmountOp? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A schedule as shown on the schedules screen.
 *
 * Wider than `Schedule` (the auto-posting projection): this includes completed
 * and manual schedules, and keeps the raw rule JSON so an edit can merge into
 * the existing rule instead of overwriting conditions it doesn't understand.
 *
 * Almost every field is optional because a schedule can be structurally broken
 * — a missing rule or next-date row after a bad sync — and a broken schedule
 * still has to be listed, or it could never be repaired or deleted from the
 * phone.
 */
data class ScheduleSummary(
    val id: String,
    val name: String? = null,
    val ruleId: String? = null,
    /** Effective next occurrence, per loot-core's `v_schedules` CASE. */
    val nextDate: DayDate? = null,
    /** `schedules_next_date.id` — the row a next-date write targets. */
    val nextDateRowId: String? = null,
    val baseNextDateTs: Long? = null,
    val accountId: String? = null,
    val payeeId: String? = null,
    val amount: ScheduledAmount? = null,
    val amountOp: ScheduleAmountOp = ScheduleAmountOp.IsApprox,
    /**
     * The date condition's raw op (`is` / `isapprox`), needed by the
     * occurrence-match lookback rule.
     */
    val dateOp: String? = null,
    val dateCondition: ScheduleDateCondition? = null,
    val postsTransaction: Boolean = false,
    val completed: Boolean = false,
    val customUpcomingLength: String? = null,
    val sortOrder: Double? = null,
    /**
     * The rule carries conditions or actions beyond the four a schedule owns
     * — the web's "edit as rule" case. Surfaced so the UI can warn that an
     * edit here won't expose everything the rule does. Preserved on update;
     * never dropped.
     */
    val isCustom: Boolean = false,
    /** Raw rule JSON, kept verbatim for the merge-on-update path. */
    val conditionsJSON: String? = null,
    val actionsJSON: String? = null,
    /**
     * From the linked rule's `set category` action, when present. The rules
     * engine can't match a schedule's recurring-date condition, so the
     * category is surfaced here and applied directly — the same reason
     * `Schedule` carries it.
     */
    val categoryId: String? = null,
) {
    val isRecurring: Boolean get() = dateCondition is ScheduleDateCondition.Recurring

    /** What a posted transaction would carry. Null amount posts 0, matching loot-core `getScheduledAmount(null)`. */
    val postAmount: Long get() = amount?.postAmount ?: 0
}
