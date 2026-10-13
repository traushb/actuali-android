package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.encodeSorted
import io.github.traushb.actuali.services.rules.RuleFieldType
import io.github.traushb.actuali.services.rules.RuleSchema
import io.github.traushb.actuali.services.sync.CRDTSyncable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.UUID

private val logger = Logger("Rule")

/**
 * Mirrors loot-core's rule model (`types/models/rule.ts`). Conditions and
 * actions are stored as JSON blobs in the `rules` table using the *internal*
 * schema names (`description` for payee, `acct` for account); `RuleSchema`
 * translates them on the way in and out.
 */
data class Rule(
    override val id: String,
    val stage: Stage,
    val conditionsOp: ConditionsOp,
    val conditions: List<Condition>,
    val actions: List<Action>,
    val tombstone: Boolean = false,
) : CRDTSyncable {
    enum class Stage(val rawValue: Int) {
        Pre(0),
        Default(1),
        Post(2),
        ;

        /** The value written to the `stage` column — NULL for the default stage, same as upstream. */
        val storedValue: String?
            get() = when (this) {
                Pre -> "pre"
                Post -> "post"
                Default -> null
            }

        fun label(locale: Locale = Locale.getDefault()): String = when (this) {
            Pre -> ReportStrings.text("Pre", locale)
            Default -> ReportStrings.text("Default", locale)
            Post -> ReportStrings.text("Post", locale)
        }

        companion object {
            fun fromRaw(raw: String?): Stage = when (raw) {
                "pre" -> Pre
                "post" -> Post
                else -> Default
            }
        }
    }

    enum class ConditionsOp(val rawValue: String) {
        And("and"),
        Or("or"),
        ;

        val label: String get() = if (this == And) L("all") else L("any")

        companion object {
            fun fromRaw(raw: String?): ConditionsOp = if (raw?.lowercase() == "or") Or else And
        }
    }

    data class Condition(
        val op: String,
        /** Public field name, e.g. "imported_payee". */
        val field: String,
        val value: RuleValue,
        val options: Map<String, RuleValue>? = null,
    ) {
        /** Upstream `Condition.serialize()` + `toInternalField`. */
        val json: JsonObject
            get() {
                val obj = linkedMapOf<String, JsonElement>(
                    "op" to JsonPrimitive(op),
                    "field" to JsonPrimitive(RuleSchema.internalField(this.field)),
                    "value" to value.json,
                )
                RuleSchema.fieldType(this.field)?.let { obj["type"] = JsonPrimitive(it.rawValue) }
                if (!options.isNullOrEmpty()) obj["options"] = JsonObject(options.mapValues { it.value.json })
                return JsonObject(obj)
            }
    }

    data class Action(
        val op: String,
        /** Null for ops without a field (link-schedule, …). */
        val field: String?,
        val value: RuleValue,
        val options: Map<String, RuleValue>? = null,
    ) {
        /**
         * Upstream `Action.serialize()`: `set` carries the field and its type,
         * `set-split-amount`/`link-schedule`/notes ops carry a null field with a
         * fixed type, and `delete-transaction` carries neither (JS leaves both
         * `undefined`, which `JSON.stringify` drops).
         */
        val json: JsonObject
            get() {
                val obj = linkedMapOf<String, JsonElement>("op" to JsonPrimitive(op), "value" to value.json)
                when (op) {
                    "set" -> this.field?.let { field ->
                        obj["field"] = JsonPrimitive(RuleSchema.internalField(field))
                        RuleSchema.fieldType(field)?.let { obj["type"] = JsonPrimitive(it.rawValue) }
                    }
                    "set-split-amount" -> {
                        obj["field"] = JsonNull
                        obj["type"] = JsonPrimitive(RuleFieldType.Num.rawValue)
                    }
                    "link-schedule" -> {
                        obj["field"] = JsonNull
                        obj["type"] = JsonPrimitive(RuleFieldType.Id.rawValue)
                    }
                    "prepend-notes", "append-notes" -> {
                        // Upstream really does tag these `id`; kept for byte parity.
                        obj["field"] = JsonPrimitive("notes")
                        obj["type"] = JsonPrimitive(RuleFieldType.Id.rawValue)
                    }
                }
                if (!options.isNullOrEmpty()) obj["options"] = JsonObject(options.mapValues { it.value.json })
                return JsonObject(obj)
            }
    }

    // Sorted keys so a rule that round-trips through the editor unchanged
    // produces the same bytes, keeping CRDT writes idempotent-looking.
    val conditionsJSON: String get() = JsonArray(conditions.map { it.json }).encodeSorted()

    val actionsJSON: String get() = JsonArray(actions.map { it.json }).encodeSorted()

    /**
     * Whether both blobs will survive a round trip to JSON. Every RuleValue
     * maps to legal JSON (non-finite numbers become null), so this always
     * holds; kept so save paths read the same as on iOS.
     */
    val isSerializable: Boolean get() = true

    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "stage" to stage.storedValue,
            "conditions_op" to conditionsOp.rawValue,
            "conditions" to conditionsJSON,
            "actions" to actionsJSON,
            "tombstone" to if (tombstone) 1 else 0,
        )

    class ParseError(message: String) : Exception(message)

    companion object {
        const val DATASET_NAME = "rules"

        /** A rule with nothing filled in, for the "new rule" editor. */
        fun empty(id: String = UUID.randomUUID().toString()): Rule =
            Rule(id, Stage.Default, ConditionsOp.And, emptyList(), emptyList())

        /** Parse a single row of the `rules` table. */
        fun parse(
            id: String,
            stage: String?,
            conditionsOp: String?,
            conditionsJSON: String?,
            actionsJSON: String?,
        ): Rule = Rule(
            id = id,
            stage = Stage.fromRaw(stage),
            conditionsOp = ConditionsOp.fromRaw(conditionsOp),
            conditions = parseConditions(conditionsJSON),
            actions = parseActions(actionsJSON),
        )

        private fun parseConditions(json: String?): List<Condition> = parseArray(json).map { item ->
            // Upstream drops the *whole rule* when any condition fails to parse
            // (`makeRule` returns null). Dropping just the bad condition would
            // leave an `and` rule matching on fewer conditions than its author
            // wrote — firing more broadly, not less.
            val op = item.string("op") ?: throw ParseError("invalid condition")
            val field = item.string("field") ?: throw ParseError("invalid condition")
            Condition(
                op = op,
                field = RuleSchema.publicField(field),
                value = RuleValue.from(item["value"]),
                options = options(item["options"]),
            )
        }

        private fun parseActions(json: String?): List<Action> = parseArray(json).map { item ->
            val op = item.string("op") ?: throw ParseError("invalid action")
            Action(
                op = op,
                field = item.string("field")?.let(RuleSchema::publicField),
                value = RuleValue.from(item["value"]),
                options = options(item["options"]),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun options(element: JsonElement?): Map<String, RuleValue>? {
            val obj = element as? JsonObject ?: return null
            if (obj.isEmpty()) return null
            return obj.mapValues { RuleValue.from(it.value) }
        }

        private fun parseArray(json: String?): List<JsonObject> {
            if (json == null) return emptyList()
            val element = try {
                io.github.traushb.actuali.services.AppJson.parseToJsonElement(json)
            } catch (e: Exception) {
                logger.debug("Rule JSON failed to parse: ${e.message}")
                throw ParseError("invalid JSON")
            }
            return when (element) {
                JsonNull -> emptyList()
                is JsonArray -> element.map { it as? JsonObject ?: throw ParseError("not an object") }
                else -> throw ParseError("not an array")
            }
        }
    }
}
