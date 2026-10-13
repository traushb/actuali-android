package io.github.traushb.actuali.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient decoding for server responses and synced JSON blobs written by other clients. */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** A copy with every object's keys sorted, recursively — `JSONSerialization`'s `.sortedKeys`. */
fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value.sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}

fun JsonElement.encodeSorted(): String = AppJson.encodeToString(JsonElement.serializer(), sortedKeys())

fun parseJsonOrNull(text: String?): JsonElement? =
    text?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() }

val JsonElement?.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement?.numberOrNull: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

val JsonElement?.longOrNullValue: Long?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d == Math.floor(d) && !d.isInfinite() }?.toLong() }

val JsonElement?.boolOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

val JsonElement?.objectOrNull: JsonObject? get() = this as? JsonObject

val JsonElement?.arrayOrNull: JsonArray? get() = this as? JsonArray

val JsonElement?.isJsonNull: Boolean get() = this == null || this is JsonNull

/** Builds a JSON number that prints whole values without a fraction (`1050`, not `1050.0`). */
fun jsonNumber(value: Double): JsonElement = when {
    !value.isFinite() -> JsonNull
    value == Math.rint(value) && kotlin.math.abs(value) < 9_007_199_254_740_992.0 -> JsonPrimitive(value.toLong())
    else -> JsonPrimitive(value)
}
