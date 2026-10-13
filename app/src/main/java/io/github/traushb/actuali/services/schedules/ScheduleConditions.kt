package io.github.traushb.actuali.services.schedules

import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.ScheduleAmountOp
import io.github.traushb.actuali.models.ScheduleDateCondition
import io.github.traushb.actuali.models.ScheduledAmount
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.encodeSorted
import io.github.traushb.actuali.services.longOrNullValue
import io.github.traushb.actuali.services.numberOrNull
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The four fields a schedule owns, as edited in the form. Mirrors the web's `ScheduleFormFields`. */
data class ScheduleFormFields(
    val name: String? = null,
    val payeeId: String? = null,
    val accountId: String? = null,
    val amount: ScheduledAmount? = null,
    val amountOp: ScheduleAmountOp = ScheduleAmountOp.IsApprox,
    val date: ScheduleDateCondition? = null,
    val postsTransaction: Boolean = false,
    val customUpcomingLength: String? = null,
) {
    /**
     * Trimmed, with blank treated as "no name" — loot-core
     * `normalizeScheduleName`, which stores null rather than an empty string
     * so the uniqueness check doesn't collide every unnamed schedule.
     */
    val normalizedName: String? get() = name?.trim()?.takeIf { it.isNotEmpty() }
}

sealed class ScheduleWriteError : Exception() {
    object DateRequired : ScheduleWriteError() {
        override val message: String get() = L("A date is required.")
    }

    object AmountRequired : ScheduleWriteError() {
        override val message: String get() = L("A valid amount is required.")
    }

    data class DuplicateName(val name: String) : ScheduleWriteError() {
        override val message: String get() = L("Another schedule is already called “%@”.", name)
    }

    object UnsupportedRecurrence : ScheduleWriteError() {
        override val message: String get() = L("This repeat pattern isn't supported.")
    }

    object NotRecurring : ScheduleWriteError() {
        override val message: String get() = L("Only a repeating schedule can be skipped.")
    }

    object NoAccount : ScheduleWriteError() {
        override val message: String get() = L("This schedule has no account, so it can't post a transaction.")
    }
}

/**
 * Building and merging the rule conditions that carry a schedule's fields.
 *
 * Upstream does this in two stages and both are reproduced here: the client
 * builds the four schedule conditions from the form, and the server merges
 * them into the rule's full condition array so custom conditions survive.
 */
object ScheduleConditions {
    /**
     * Positions of the four schedule-owned conditions inside a rule's full
     * conditions array.
     *
     * Upstream's `extractScheduleConds` returns the condition objects and
     * later compares them by reference. This port carries indices instead —
     * which is also exactly what the JSON-path cache needs to write `$[n]`.
     */
    data class Indices(val payee: Int?, val account: Int?, val amount: Int?, val date: Int?) {
        /**
         * Upstream's `Object.values()` order. Load-bearing: `merge` pairs the
         * old and new sets positionally, so reordering this silently writes a
         * payee into the account slot.
         */
        val ordered: List<Int?> get() = listOf(payee, account, amount, date)
    }

    /**
     * Port of `extractScheduleConds`. Field fallbacks match upstream: a rule
     * may name the payee `description` and the account `acct` (the internal
     * schema names), and the public name wins when both are present.
     */
    fun extract(conditions: List<JsonObject>): Indices {
        fun find(ops: Set<String>, fields: List<String>): Int? {
            for (field in fields) {
                val index = conditions.indexOfFirst {
                    it["op"].stringOrNull in ops && it["field"].stringOrNull == field
                }
                if (index >= 0) return index
            }
            return null
        }
        return Indices(
            payee = find(setOf("is"), listOf("payee", "description")),
            account = find(setOf("is"), listOf("account", "acct")),
            amount = find(setOf("is", "isapprox", "isbetween"), listOf("amount")),
            date = find(setOf("is", "isapprox"), listOf("date")),
        )
    }

    /**
     * Port of `updateScheduleConditions`: the four conditions a schedule owns,
     * built from the form.
     *
     * An existing condition keeps its op and any extra keys and only takes a
     * new value — so a schedule the web set to an exact date stays exact
     * after an edit here. Amount is the exception and is rewritten whole,
     * because the form owns its operator.
     */
    fun build(fields: ScheduleFormFields, existing: List<JsonObject>): List<JsonObject> {
        val date = fields.date ?: throw ScheduleWriteError.DateRequired
        val amount = fields.amount ?: throw ScheduleWriteError.AmountRequired
        val indices = extract(existing)

        fun update(index: Int?, op: String, field: String, value: JsonElement?): JsonObject? {
            if (index != null) {
                return JsonObject(existing[index] + ("value" to (value ?: JsonNull)))
            }
            // A payee condition is written even when empty — upstream creates
            // it unconditionally so the slot exists for a later edit.
            if (value == null && field != "payee") return null
            return JsonObject(linkedMapOf("op" to JsonPrimitive(op), "field" to JsonPrimitive(field), "value" to (value ?: JsonNull)))
        }

        return listOfNotNull(
            update(indices.payee, "is", "payee", fields.payeeId?.let(::JsonPrimitive)),
            update(indices.account, "is", "account", fields.accountId?.let(::JsonPrimitive)),
            update(indices.date, "isapprox", "date", dateValue(date)),
            // Never merged: the operator is part of what the form edits.
            JsonObject(
                linkedMapOf(
                    "op" to JsonPrimitive(fields.amountOp.rawValue),
                    "field" to JsonPrimitive("amount"),
                    "value" to amountValue(amount),
                ),
            ),
        )
    }

    /**
     * Port of `updateConditions`: fold the four schedule conditions into the
     * rule's full condition array.
     *
     * Conditions the schedule does not own are left exactly where they are —
     * this is what keeps a schedule that was customised on the web ("edit as
     * rule") intact after an edit from the phone.
     */
    fun merge(existing: List<JsonObject>, scheduleConditions: List<JsonObject>): List<JsonObject> {
        val oldSlots = extract(existing).ordered
        val newSlots = extract(scheduleConditions).ordered
        val result = existing.toMutableList()
        val appended = mutableListOf<JsonObject>()
        for ((slot, newIndex) in newSlots.withIndex()) {
            if (newIndex == null) continue
            val replacement = scheduleConditions[newIndex]
            val oldIndex = oldSlots[slot]
            if (oldIndex != null) result[oldIndex] = replacement else appended += replacement
        }
        return result + appended
    }

    /**
     * Port of `updateActions`: keep a plain `set amount` action aligned with
     * the amount condition.
     *
     * Posting a scheduled transaction runs the rule, so an action left at the
     * old amount would quietly overwrite the edited one. Templated and
     * formula actions compute their own value and are never touched, and
     * `set-split-amount` is excluded by the op check.
     *
     * Returns null when nothing changed, so the caller can skip the write.
     */
    fun syncedActions(conditions: List<JsonObject>, actions: List<JsonObject>): List<JsonObject>? {
        val amountIndex = extract(conditions).amount ?: return null
        val amount = scheduledAmount(conditions[amountIndex]["value"])
        var changed = false
        val updated = actions.map { action ->
            if (action["op"].stringOrNull != "set" || action["field"].stringOrNull != "amount") return@map action
            val options = action["options"] as? JsonObject
            if (options?.get("template") != null || options?.get("formula") != null) return@map action
            if (action["value"].numberOrNull?.toLong() == amount) return@map action
            changed = true
            JsonObject(action + ("value" to JsonPrimitive(amount)))
        }
        return if (changed) updated else null
    }

    /** JSON paths for the local `schedules_json_paths` cache — upstream's `onRuleUpdate`. */
    data class JsonPaths(val payee: String?, val account: String?, val amount: String?, val date: String?)

    fun jsonPaths(conditions: List<JsonObject>): JsonPaths {
        val indices = extract(conditions)
        fun path(index: Int?): String? = index?.let { "$[$it]" }
        return JsonPaths(path(indices.payee), path(indices.account), path(indices.amount), path(indices.date))
    }

    // Value conversion

    /** Port of `getScheduledAmount`, reading a raw condition value. */
    fun scheduledAmount(value: JsonElement?): Long {
        value.numberOrNull?.let { return it.toLong() }
        val range = value as? JsonObject ?: return 0
        val num1 = range["num1"].numberOrNull?.toLong() ?: return 0
        val num2 = range["num2"].numberOrNull?.toLong() ?: return 0
        return ScheduledAmount.Range(num1, num2).postAmount
    }

    fun amountValue(amount: ScheduledAmount): JsonElement = when (amount) {
        is ScheduledAmount.Fixed -> JsonPrimitive(amount.cents)
        is ScheduledAmount.Range -> JsonObject(linkedMapOf("num1" to JsonPrimitive(amount.num1), "num2" to JsonPrimitive(amount.num2)))
    }

    fun dateValue(date: ScheduleDateCondition): JsonElement = when (date) {
        is ScheduleDateCondition.Fixed -> JsonPrimitive(date.day.iso)
        is ScheduleDateCondition.Recurring -> date.config.json
        ScheduleDateCondition.Unsupported -> JsonPrimitive("Unsupported repeat")
    }

    /**
     * The next occurrence a date condition implies, port of loot-core
     * `getNextDate`. A one-off date is returned as-is even when it is in the
     * past — upstream does the same, and the status engine renders that as
     * `missed` rather than silently moving it.
     */
    fun nextDate(date: ScheduleDateCondition, today: DayDate = DayDate.today()): DayDate? = when (date) {
        is ScheduleDateCondition.Fixed -> date.day
        is ScheduleDateCondition.Recurring -> ScheduleRecurrence.nextOccurrence(date.config, today)
        // Can't advance a shape we can't model; the poster already treats
        // these like one-offs and leaves the advance to the web app.
        ScheduleDateCondition.Unsupported -> null
    }

    /**
     * Compare two conditions ignoring the transient `type` key, which
     * upstream strips before comparing. Drives the "did the account or date
     * change?" test that decides whether the next date is recomputed.
     */
    fun conditionsEqual(lhs: JsonObject?, rhs: JsonObject?): Boolean {
        if (lhs == null || rhs == null) return lhs == null && rhs == null
        return jsonEqual(JsonObject(lhs - "type"), JsonObject(rhs - "type"))
    }

    /** Structural equality where `1` and `1.0` match, as NSNumber comparison does. */
    private fun jsonEqual(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { jsonEqual(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
            a.numberOrNull != null && b.numberOrNull != null -> a.numberOrNull == b.numberOrNull
        else -> a == b
    }

    fun condition(index: Int?, conditions: List<JsonObject>): JsonObject? = index?.let { conditions.getOrNull(it) }

    // JSON

    fun parse(json: String?): List<JsonObject> {
        if (json == null) return emptyList()
        val parsed = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        if (parsed.any { it !is JsonObject }) return emptyList()
        return parsed.map { it as JsonObject }
    }

    /**
     * Parse a linked schedule rule's actions without re-evaluating its date
     * condition. Malformed actions are treated as empty, matching the fetch
     * path's existing best-effort behavior.
     */
    fun actions(json: String?): List<Rule.Action> = runCatching {
        Rule.parse("schedule-actions", null, "and", "[]", json).actions
    }.getOrDefault(emptyList())

    /**
     * Sorted keys keep the output deterministic, which matters for tests.
     * Key order inside a JSON object is not significant to any reader; ARRAY
     * order is, and is preserved.
     */
    fun serialize(value: List<JsonObject>): String = JsonArray(value).encodeSorted()

    /** Whole-number reading of a condition value, as `as? Int` reads an NSNumber. */
    fun intValue(value: JsonElement?): Long? = value.longOrNullValue
}
