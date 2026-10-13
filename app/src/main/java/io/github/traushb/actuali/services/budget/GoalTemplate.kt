package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.boolOrNull
import io.github.traushb.actuali.services.encodeSorted
import io.github.traushb.actuali.services.numberOrNull
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * One goal-template line for a category — the parsed form of a `#template` /
 * `#goal` note line, and the unit stored in `categories.goal_def` (a JSON
 * array of these). Mirrors loot-core's `Template` union
 * (types/models/templates.ts); one class with optional fields so
 * unknown/partial JSON written by other clients (including the web's
 * template-editor UI, which also emits `refill` and `limit` types)
 * round-trips without loss.
 */
data class GoalTemplate(
    var type: Kind,
    var directive: Directive,
    /** 0 for a bare `#template`, N for `#template-N`; null where upstream stores null. */
    var priority: Int? = null,
    // Amounts are whole currency units (e.g. 50.5 dollars), matching the JSON
    // upstream stores — the engine converts to cents when it runs.
    var amount: Double? = null,
    /** simple; null = limit-only line. */
    var monthly: Double? = null,
    var limit: Limit? = null,
    /** percentage. */
    var percent: Double? = null,
    /** percentage: "of previous". */
    var previous: Boolean? = null,
    /** percentage income source (name, id, or special). */
    var category: String? = null,
    /** periodic. */
    var period: Period? = null,
    /** periodic: "YYYY-MM-DD". */
    var starting: String? = null,
    /** by/spend target: "YYYY-MM". */
    var month: String? = null,
    /** by/spend repeat every year(s) vs month(s). */
    var annual: Boolean? = null,
    /** by/spend repeat interval ("repeat" in JSON). */
    var repeatCount: Int? = null,
    /** spend start: "YYYY-MM". */
    var from: String? = null,
    /** schedule name. */
    var name: String? = null,
    /** schedule id (UI-managed templates). */
    var scheduleId: String? = null,
    /** schedule: budget in full the month it's due. */
    var full: Boolean? = null,
    /** schedule/average increase/decrease. */
    var adjustment: Double? = null,
    var adjustmentType: AdjustmentType? = null,
    /** average. */
    var numMonths: Int? = null,
    /** copy. */
    var lookBack: Int? = null,
    /** remainder. */
    var weight: Double? = null,
    /** error: the raw note line. */
    var line: String? = null,
    /** error: parser message. */
    var error: String? = null,
    /** Note lines directly above the template line. */
    var description: String? = null,
) {
    enum class Directive(val rawValue: String) {
        Template("template"), Goal("goal"), Error("error");

        companion object {
            fun fromRaw(raw: String?): Directive? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    enum class Kind(val rawValue: String) {
        Percentage("percentage"), Periodic("periodic"), By("by"), Spend("spend"), Simple("simple"),
        Schedule("schedule"), Average("average"), Copy("copy"), Remainder("remainder"), Refill("refill"),
        Goal("goal"), Limit("limit"), Error("error");

        companion object {
            fun fromRaw(raw: String?): Kind? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    enum class LimitPeriod(val rawValue: String) {
        Daily("daily"), Weekly("weekly"), Monthly("monthly");

        companion object {
            fun fromRaw(raw: String?): LimitPeriod? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    data class Limit(
        var amount: Double,
        var hold: Boolean,
        var period: LimitPeriod,
        /** "YYYY-MM-DD", weekly only. */
        var start: String? = null,
    )

    enum class PeriodUnit(val rawValue: String) {
        Day("day"), Week("week"), Month("month"), Year("year");

        companion object {
            fun fromRaw(raw: String?): PeriodUnit? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    data class Period(var period: PeriodUnit, var amount: Int)

    enum class AdjustmentType(val rawValue: String) {
        Percent("percent"), Fixed("fixed");

        companion object {
            fun fromRaw(raw: String?): AdjustmentType? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * The JSON object upstream's `JSON.parse(goal_def)` readers expect.
     * Integral doubles serialize as plain integers, matching what peggy's
     * `+amount` produces in JS.
     */
    val json: JsonObject
        get() {
            val obj = linkedMapOf<String, JsonElement>(
                "type" to JsonPrimitive(type.rawValue),
                "directive" to JsonPrimitive(directive.rawValue),
            )
            // Upstream's parser emits explicit nulls for priority on
            // remainder/goal lines; readers null-check rather than key-check.
            obj["priority"] = priority?.let(::JsonPrimitive) ?: JsonNull
            amount?.let { obj["amount"] = number(it) }
            monthly?.let { obj["monthly"] = number(it) }
            if (type == Kind.Simple && monthly == null) obj["monthly"] = JsonNull
            val limit = limit
            if (limit != null && type == Kind.Limit) {
                obj["amount"] = number(limit.amount)
                obj["hold"] = JsonPrimitive(limit.hold)
                obj["period"] = JsonPrimitive(limit.period.rawValue)
                limit.start?.let { obj["start"] = JsonPrimitive(it) }
            } else if (limit != null) {
                obj["limit"] = JsonObject(
                    linkedMapOf(
                        "amount" to number(limit.amount),
                        "hold" to JsonPrimitive(limit.hold),
                        "period" to JsonPrimitive(limit.period.rawValue),
                        "start" to (limit.start?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                )
            } else if (type == Kind.Simple || type == Kind.Periodic || type == Kind.Remainder) {
                obj["limit"] = JsonNull
            }
            percent?.let { obj["percent"] = number(it) }
            previous?.let { obj["previous"] = JsonPrimitive(it) }
            category?.let { obj["category"] = JsonPrimitive(it) }
            period?.let {
                obj["period"] = JsonObject(linkedMapOf("period" to JsonPrimitive(it.period.rawValue), "amount" to JsonPrimitive(it.amount)))
            }
            starting?.let { obj["starting"] = JsonPrimitive(it) }
            month?.let { obj["month"] = JsonPrimitive(it) }
            annual?.let { obj["annual"] = JsonPrimitive(it) }
            repeatCount?.let { obj["repeat"] = JsonPrimitive(it) }
            if (type == Kind.Spend || type == Kind.By) obj["from"] = from?.let(::JsonPrimitive) ?: JsonNull
            name?.let { obj["name"] = JsonPrimitive(it) }
            scheduleId?.let { obj["scheduleId"] = JsonPrimitive(it) }
            full?.let { obj["full"] = JsonPrimitive(it) }
            adjustment?.let { obj["adjustment"] = number(it) }
            adjustmentType?.let { obj["adjustmentType"] = JsonPrimitive(it.rawValue) }
            numMonths?.let { obj["numMonths"] = JsonPrimitive(it) }
            lookBack?.let { obj["lookBack"] = JsonPrimitive(it) }
            weight?.let { obj["weight"] = number(it) }
            line?.let { obj["line"] = JsonPrimitive(it) }
            error?.let { obj["error"] = JsonPrimitive(it) }
            description?.let { obj["description"] = JsonPrimitive(it) }
            return JsonObject(obj)
        }

    companion object {
        private fun number(value: Double): JsonPrimitive =
            if (value == Math.rint(value) && abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

        /**
         * Decode a `goal_def` column value. Unknown `type`/`directive` strings
         * become error templates rather than being dropped, so a template
         * written by a newer client blocks the category's apply instead of
         * silently half-applying.
         */
        fun decodeArray(json: String): List<GoalTemplate>? {
            val array = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
            if (array.any { it !is JsonObject }) return null
            return array.map { fromJson(it as JsonObject) }
        }

        fun encodeArray(templates: List<GoalTemplate>): String = JsonArray(templates.map { it.json }).encodeSorted()

        fun fromJson(json: JsonObject): GoalTemplate {
            fun double(key: String) = json[key].numberOrNull ?: json[key].boolOrNull?.let { if (it) 1.0 else 0.0 }
            fun int(key: String) = double(key)?.toInt()
            fun bool(key: String): Boolean? = json[key].boolOrNull ?: json[key].numberOrNull?.let { it != 0.0 }
            fun string(key: String) = json[key].stringOrNull

            val kind = Kind.fromRaw(string("type"))
            val directive = Directive.fromRaw(string("directive"))
            if (kind == null || directive == null) {
                return GoalTemplate(
                    type = Kind.Error,
                    directive = Directive.Error,
                    line = string("line") ?: string("type") ?: "unknown template",
                    error = string("error") ?: "Unrecognized template type",
                    description = string("description"),
                )
            }
            val template = GoalTemplate(kind, directive, int("priority"))
            template.amount = double("amount")
            template.monthly = double("monthly")
            val limitObject = json["limit"] as? JsonObject
            val limitAmount = limitObject?.get("amount").numberOrNull
            if (limitObject != null && limitAmount != null) {
                template.limit = Limit(
                    amount = limitAmount,
                    hold = limitObject["hold"].boolOrNull ?: false,
                    period = LimitPeriod.fromRaw(limitObject["period"].stringOrNull) ?: LimitPeriod.Monthly,
                    start = limitObject["start"].stringOrNull,
                )
            } else if (kind == Kind.Limit) {
                // The UI's LimitTemplate stores its fields at the top level.
                template.limit = Limit(
                    amount = template.amount ?: 0.0,
                    hold = bool("hold") ?: false,
                    period = LimitPeriod.fromRaw(string("period")) ?: LimitPeriod.Monthly,
                    start = string("start"),
                )
            }
            template.percent = double("percent")
            template.previous = bool("previous")
            template.category = string("category")
            val periodObject = json["period"] as? JsonObject
            val unit = PeriodUnit.fromRaw(periodObject?.get("period").stringOrNull)
            val periodAmount = periodObject?.get("amount").numberOrNull?.toInt()
            if (unit != null && periodAmount != null) template.period = Period(unit, periodAmount)
            template.starting = string("starting")
            template.month = string("month")
            template.annual = bool("annual")
            template.repeatCount = int("repeat")
            template.from = string("from")
            template.name = string("name")
            template.scheduleId = string("scheduleId")
            template.full = bool("full")
            template.adjustment = double("adjustment")
            template.adjustmentType = AdjustmentType.fromRaw(string("adjustmentType"))
            template.numMonths = int("numMonths")
            template.lookBack = int("lookBack")
            template.weight = double("weight")
            template.line = string("line")
            template.error = string("error")
            template.description = string("description")
            return template
        }
    }
}
