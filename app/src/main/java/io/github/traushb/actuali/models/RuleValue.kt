package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.jsonNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A JSON value carried by a rule condition or action. Rules live as JSON blobs
 * in the `rules` table, so every value is a JSON primitive — modelling that
 * explicitly keeps the model comparable (UI state) and makes the round trip
 * back out to JSON exact.
 */
sealed interface RuleValue {
    data class Str(val value: String) : RuleValue
    data class Num(val value: Double) : RuleValue
    data class Bool(val value: Boolean) : RuleValue
    data class ListValue(val items: List<RuleValue>) : RuleValue
    data class Obj(val fields: Map<String, RuleValue>) : RuleValue
    data object Null : RuleValue

    /**
     * The JSON representation for writing back. Whole numbers serialize as
     * integers so amounts round-trip as the cents upstream wrote (`1050`, not
     * `1050.0`); JSON has no NaN or infinity, so those become null rather
     * than poisoning the whole rule.
     */
    val json: JsonElement
        get() = when (this) {
            is Str -> JsonPrimitive(value)
            is Num -> jsonNumber(value)
            is Bool -> JsonPrimitive(value)
            is ListValue -> JsonArray(items.map { it.json })
            is Obj -> JsonObject(fields.mapValues { it.value.json })
            Null -> JsonNull
        }

    val stringValue: String? get() = (this as? Str)?.value
    val numberValue: Double? get() = (this as? Num)?.value
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val listValue: List<RuleValue>? get() = (this as? ListValue)?.items

    /** The `{num1, num2}` payload an `isbetween` condition carries. */
    val betweenValue: Pair<Double, Double>?
        get() {
            val fields = (this as? Obj)?.fields ?: return null
            val num1 = fields["num1"]?.numberValue ?: return null
            val num2 = fields["num2"]?.numberValue ?: return null
            return num1 to num2
        }

    val isNull: Boolean get() = this == Null

    companion object {
        /** Wrap a parsed JSON value. */
        fun from(json: JsonElement?): RuleValue = when (json) {
            null, JsonNull -> Null
            is JsonPrimitive -> when {
                json.isString -> Str(json.content)
                json.booleanOrNull != null -> Bool(json.booleanOrNull!!)
                else -> json.doubleOrNull?.let { Num(it) } ?: Null
            }
            is JsonArray -> ListValue(json.map { from(it) })
            is JsonObject -> Obj(json.mapValues { from(it.value) })
        }
    }
}
