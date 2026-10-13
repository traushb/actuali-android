package io.github.traushb.actuali.services.budget

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.encodeSorted
import io.github.traushb.actuali.services.numberOrNull
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One row of `categories.cleanup_def` — the end-of-month cleanup roles a
 * category takes on. Mirrors loot-core's `CleanupTemplate` union
 * (types/models/cleanup-templates.ts): a source gives its leftover balance up,
 * a sink receives (weighted), and an overspend sink only takes enough to cover
 * overspending. `groupId` scopes a row to a cleanup pool; null means global.
 */
data class CleanupTemplate(
    val role: Role,
    val groupId: String? = null,
    /** Sink share; upstream stores it only on sink rows. */
    val weight: Double = 1.0,
) {
    enum class Role(val rawValue: String) {
        Source("source"), Sink("sink"), Overspend("overspend");

        companion object {
            fun fromRaw(raw: String?): Role? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    companion object {
        fun source(groupId: String? = null) = CleanupTemplate(Role.Source, groupId)

        fun sink(groupId: String? = null, weight: Double = 1.0) = CleanupTemplate(Role.Sink, groupId, weight)

        fun overspend(groupId: String) = CleanupTemplate(Role.Overspend, groupId)

        fun decodeArray(json: String): List<CleanupTemplate>? {
            val array = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
            if (array.any { it !is JsonObject }) return null
            return array.mapNotNull { element ->
                val obj = element as JsonObject
                val role = Role.fromRaw(obj["role"].stringOrNull) ?: return@mapNotNull null
                val groupId = obj["groupId"].stringOrNull
                if (role == Role.Overspend && groupId == null) return@mapNotNull null
                CleanupTemplate(role, groupId, obj["weight"].numberOrNull ?: 1.0)
            }
        }

        fun encodeArray(rows: List<CleanupTemplate>): String = JsonArray(
            rows.map { row ->
                val obj = linkedMapOf<String, JsonElement>(
                    "role" to JsonPrimitive(row.role.rawValue),
                    "groupId" to (row.groupId?.let(::JsonPrimitive) ?: JsonNull),
                )
                if (row.role == Role.Sink) {
                    obj["weight"] = if (row.weight == Math.rint(row.weight)) JsonPrimitive(row.weight.toLong()) else JsonPrimitive(row.weight)
                }
                JsonObject(obj)
            },
        ).encodeSorted()
    }
}

/**
 * Port of loot-core's `cleanup-template.pegjs` + cleanup-template-notes.ts:
 * `#cleanup` note lines, parsed with group NAMES (resolved to ids by the
 * caller) and rendered back for the un-migrate flow.
 */
object CleanupNotes {
    data class ParsedRow(val kind: Kind, val groupName: String?) {
        sealed interface Kind {
            data object Source : Kind
            data class Sink(val weight: Double) : Kind
            data object Overspend : Kind
        }
    }

    /** Parse the `#cleanup …` lines of one note. Unparseable lines are silently skipped. */
    fun parseRows(note: String): List<ParsedRow> = note.split("\n").mapNotNull { line ->
        val trimmed = line.trim()
        if (!trimmed.lowercase().startsWith("#cleanup")) null else parseLine(trimmed)
    }

    /**
     * Grammar (after "#cleanup "): `source` | `sink [N]` | `<group> source` |
     * `<group> sink [N]` | `<group>` (overspend).
     */
    private fun parseLine(line: String): ParsedRow? {
        // The prefix match is case-insensitive (upstream lowercases the line
        // to test the prefix, then feeds the original into the grammar).
        if (line.length <= "#cleanup".length) return null
        var body = line.substring("#cleanup".length)
        if (body.first() != ' ') return null
        body = body.trim { it == ' ' || it == '\t' }
        if (body.isEmpty()) return null

        fun weight(text: String): Double? {
            val trimmed = text.trim { it == ' ' || it == '\t' }
            if (trimmed.isEmpty()) return 1.0
            if (!trimmed.all { it in '0'..'9' }) return null
            val value = trimmed.toDouble()
            return if (value == 0.0) 1.0 else value
        }

        if (body == "source") return ParsedRow(ParsedRow.Kind.Source, null)
        if (body.startsWith("sink")) {
            weight(body.substring(4))?.let { return ParsedRow(ParsedRow.Kind.Sink(it), null) }
        }
        val sourceIndex = body.indexOf(" source")
        if (sourceIndex >= 0 && sourceIndex + " source".length == body.length) {
            val group = body.substring(0, sourceIndex)
            if (group.isEmpty()) return null
            return ParsedRow(ParsedRow.Kind.Source, group)
        }
        val sinkIndex = body.indexOf(" sink")
        if (sinkIndex >= 0) {
            val group = body.substring(0, sinkIndex)
            val rest = body.substring(sinkIndex + " sink".length)
            val w = weight(rest)
            if (group.isNotEmpty() && w != null) return ParsedRow(ParsedRow.Kind.Sink(w), group)
        }
        // Bare group name = "cover this group's overspending".
        return ParsedRow(ParsedRow.Kind.Overspend, body)
    }

    /**
     * Resolve parsed note rows into cleanup_def rows using a name-insensitive
     * group map. Rows naming unknown groups are dropped.
     */
    fun toTemplates(rows: List<ParsedRow>, groupIdForName: (String) -> String?): List<CleanupTemplate> = rows.mapNotNull { row ->
        val groupId = row.groupName?.let(groupIdForName)
        when (val kind = row.kind) {
            ParsedRow.Kind.Source -> if (row.groupName != null && groupId == null) null else CleanupTemplate.source(groupId)
            is ParsedRow.Kind.Sink -> if (row.groupName != null && groupId == null) null else CleanupTemplate.sink(groupId, kind.weight)
            ParsedRow.Kind.Overspend -> groupId?.let { CleanupTemplate.overspend(it) }
        }
    }

    /** Port of the web's `cleanupToNotes`: render cleanup rows back to `#cleanup` lines. */
    fun toNotes(cleanup: List<CleanupTemplate>, groupName: (String) -> String?): String = cleanup.mapNotNull { row ->
        when (row.role) {
            CleanupTemplate.Role.Overspend -> {
                val name = row.groupId?.let(groupName) ?: return@mapNotNull null
                "#cleanup $name"
            }
            CleanupTemplate.Role.Source, CleanupTemplate.Role.Sink -> {
                var scope = ""
                if (row.groupId != null) {
                    val name = groupName(row.groupId) ?: return@mapNotNull null
                    scope = "$name "
                }
                if (row.role == CleanupTemplate.Role.Source) {
                    "#cleanup ${scope}source"
                } else {
                    val weight = if (row.weight != 1.0) " ${formatWeight(row.weight)}" else ""
                    "#cleanup ${scope}sink$weight"
                }
            }
        }
    }.joinToString("\n")

    private fun formatWeight(weight: Double): String =
        if (weight == Math.rint(weight)) weight.toLong().toString() else weight.toString()
}

/** The automation editor's view of a category's cleanup rows — port of the web's `cleanupModel.ts`. */
data class CleanupConfig(
    val global: Global = Global(),
    val groups: List<Group> = emptyList(),
) {
    data class Global(val send: Boolean = false, val take: Boolean = false, val weight: Double = 1.0)

    data class Group(
        val groupId: String,
        val send: Boolean = false,
        val take: Boolean = false,
        val weight: Double = 1.0,
        val overspendOnly: Boolean = false,
    ) {
        val id: String get() = groupId
    }

    val isConfigured: Boolean get() = global.send || global.take || groups.any { it.send || it.take }

    fun toCleanupTemplates(): List<CleanupTemplate> {
        if (!isConfigured) return emptyList()
        val rows = mutableListOf<CleanupTemplate>()
        if (global.send) rows += CleanupTemplate.source()
        if (global.take) rows += CleanupTemplate.sink(weight = global.weight)
        for (group in groups) {
            if (group.send) rows += CleanupTemplate.source(group.groupId)
            if (group.take) {
                rows += if (group.overspendOnly) CleanupTemplate.overspend(group.groupId) else CleanupTemplate.sink(group.groupId, group.weight)
            }
        }
        return rows
    }

    companion object {
        fun from(cleanup: List<CleanupTemplate>): CleanupConfig {
            var global = Global()
            val groups = mutableListOf<Group>()
            fun groupIndex(groupId: String): Int {
                val index = groups.indexOfFirst { it.groupId == groupId }
                if (index >= 0) return index
                groups += Group(groupId)
                return groups.size - 1
            }
            for (row in cleanup) {
                when (row.role) {
                    CleanupTemplate.Role.Source -> if (row.groupId != null) {
                        val i = groupIndex(row.groupId)
                        groups[i] = groups[i].copy(send = true)
                    } else {
                        global = global.copy(send = true)
                    }
                    CleanupTemplate.Role.Sink -> if (row.groupId != null) {
                        val i = groupIndex(row.groupId)
                        // A real sink wins over a stray prior overspend row.
                        groups[i] = groups[i].copy(take = true, weight = row.weight, overspendOnly = false)
                    } else {
                        global = global.copy(take = true, weight = row.weight)
                    }
                    CleanupTemplate.Role.Overspend -> {
                        val groupId = row.groupId ?: continue
                        val i = groupIndex(groupId)
                        // Don't downgrade a real sink row already recorded.
                        if (!groups[i].take) groups[i] = groups[i].copy(take = true, overspendOnly = true)
                    }
                }
            }
            return CleanupConfig(global, groups)
        }
    }
}
