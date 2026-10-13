package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable
import java.util.UUID

/**
 * A managed transaction tag from Actual Budget's `tags` table.
 * Tags in Actual are embedded in transaction notes as `#name`.
 * The `tags` table holds metadata for each tag: color, description, and hidden status.
 */
data class Tag(
    override val id: String = UUID.randomUUID().toString(),
    val tag: String,
    val color: String? = null,
    val description: String? = null,
    val hidden: Boolean = false,
    val tombstone: Boolean = false,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    val displayName: String get() = "#$tag"

    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            // Upstream filters `id` out of CRDT messages (db/index.ts), like
            // every other model here — the row id rides in the message header.
            "tag" to tag,
            "color" to color,
            "description" to description,
            "hidden" to if (hidden) 1 else 0,
            "tombstone" to if (tombstone) 1 else 0,
        )

    companion object {
        const val DATASET_NAME = "tags"

        /** Curated palette matching Actual Budget's tag color picker swatches. */
        val presetColors = listOf(
            "#ef4444", // Red
            "#f97316", // Orange
            "#f59e0b", // Amber
            "#10b981", // Emerald
            "#06b6d4", // Cyan
            "#3b82f6", // Blue
            "#6366f1", // Indigo
            "#8b5cf6", // Purple
            "#ec4899", // Pink
            "#64748b", // Slate
        )

        /** Strips leading/trailing `#` and whitespace from a tag name. */
        fun normalizeTagName(raw: String): String = raw.trim().trim('#')

        /** Must not be empty, and cannot contain whitespace or `#`. */
        fun isValidTagName(raw: String): Boolean {
            val normalized = normalizeTagName(raw)
            return normalized.isNotEmpty() && normalized.none { it.isWhitespace() || it == '#' }
        }

        /** "#rrggbb" or "#rrggbbaa" as ARGB, or null when unparseable. */
        fun parseHexColor(hex: String): Long? {
            val sanitized = hex.trim().replace("#", "")
            val rgb = sanitized.toLongOrNull(16) ?: return null
            return when (sanitized.length) {
                6 -> 0xFF000000L or rgb
                8 -> ((rgb and 0xFF) shl 24) or (rgb ushr 8)
                else -> null
            }
        }
    }
}

/** Aggregated usage statistics for a tag across transactions. */
data class TagSummary(
    val tag: Tag,
    val transactionCount: Int,
    /** Absolute spend in cents (positive value representing expense amount). */
    val totalSpent: Long,
    /** Net total amount in cents (inflows - outflows). */
    val netAmount: Long,
) {
    val id: String get() = tag.id
}
