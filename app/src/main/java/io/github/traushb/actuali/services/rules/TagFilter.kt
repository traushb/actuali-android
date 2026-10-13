package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.models.Tag

/**
 * Tag extraction and matching shared by the rule engine and report filters.
 * Port of upstream loot-core `shared/tags.ts` (`extractTagsForFilter`) plus
 * the tag pattern from `server/rules/condition.ts` / the AQL filter path.
 * The rule engine matches case-insensitively (it lowercases both sides);
 * AQL filters stay case-sensitive — callers pick via `caseSensitive`.
 */
object TagFilter {
    /**
     * Every whitespace-separated token (leading `#`s stripped) becomes a
     * `#token` tag, deduped preserving order:
     * "one #one ##one ##two three" → ["#one", "#two", "#three"]
     */
    fun extractTags(value: String): List<String> {
        val seen = LinkedHashSet<String>()
        value.split(Regex("[\\s#]+"))
            .filter { it.isNotEmpty() }
            .forEach { seen += "#$it" }
        return seen.toList()
    }

    private val hashtagRegex = Regex("(?<!#)#([^\\s#]+)")

    /**
     * Extracts genuine `#hashtags` from a transaction note string.
     * Unlike [extractTags] which is for filter input where words can omit `#`,
     * this parses free-form notes where only tokens with a `#` prefix are tags.
     * Excludes `##hidden` prefixes per upstream Actual Budget convention.
     */
    fun extractHashtags(notes: String): List<String> {
        if (!notes.contains("#")) return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (match in hashtagRegex.findAll(notes)) {
            val normalized = Tag.normalizeTagName(match.value)
            if (!Tag.isValidTagName(normalized)) continue
            val tagWithHash = "#$normalized"
            if (seen.add(tagWithHash.lowercase())) result += tagWithHash
        }
        return result
    }

    /** One piece of a note: plain text, or a genuine `#hashtag`. */
    sealed interface NoteSegment {
        data class Text(val text: String) : NoteSegment
        data class TagSegment(val tag: String) : NoteSegment
    }

    /**
     * A note line split into plain text and `#hashtags`, in reading order, so a
     * row can draw the tags in place (as chips) within the note's own line.
     * `##hidden` and invalid tags stay in the text, mirroring
     * [extractHashtags]; whitespace is collapsed and empty text is dropped.
     */
    fun noteSegments(notes: String): List<NoteSegment> {
        val segments = mutableListOf<NoteSegment>()
        val text = StringBuilder()
        fun flushText() {
            val collapsed = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (collapsed.isNotEmpty()) segments += NoteSegment.Text(collapsed)
            text.clear()
        }
        var cursor = 0
        for (match in hashtagRegex.findAll(notes)) {
            val normalized = Tag.normalizeTagName(match.value)
            if (!Tag.isValidTagName(normalized)) continue
            text.append(notes, cursor, match.range.first)
            flushText()
            segments += NoteSegment.TagSegment("#$normalized")
            cursor = match.range.last + 1
        }
        text.append(notes.substring(cursor))
        flushText()
        return segments
    }

    /**
     * Matches upstream's tag pattern `(?<!#)tag([\s#]|$)`: the tag must not
     * be preceded by an extra `#` (so `##hidden` tags never match) and must
     * end at whitespace, another tag, or the end of the notes.
     */
    fun notesContainTag(notes: String, tag: String, caseSensitive: Boolean): Boolean {
        val needle = if (caseSensitive) tag else tag.lowercase()
        val haystack = if (caseSensitive) notes else notes.lowercase()
        val regex = runCatching { Regex("(?<!#)${Regex.escape(needle)}([\\s#]|$)") }.getOrNull() ?: return false
        return regex.containsMatchIn(haystack)
    }
}
