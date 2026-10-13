package io.github.traushb.actuali.models

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink

/**
 * One tappable link found in a note: the text the user reads and the URL it
 * opens. [label] is the markdown link's title, or the URL text itself for
 * bare URLs. [position] keeps repeated links distinct.
 */
data class NoteLink(val label: String, val url: String, val position: Int)

/**
 * Turns note text into tappable links (GH #190). Notes written in Actual
 * commonly carry markdown links (a rule that links a transaction to its order
 * page), and sometimes bare URLs pasted straight in.
 *
 * ponytail: inline markdown is reduced to `[label](url)` links — other
 * emphasis shows as typed, where iOS renders it. Bare URLs are http(s) and
 * `www.` hosts. A full CommonMark inline parser is the upgrade path.
 */
object NoteLinkText {
    private val markdownLink = Regex("""\[([^\]\n]+)]\((\S+?)\)""")
    private val bareUrl = Regex("""\b(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\""

    private data class Span(val start: Int, val end: Int, val label: String, val url: String)

    /** The note's links in note order, with the markdown syntax removed from what is shown. */
    private fun parse(text: String): Pair<String, List<Span>> {
        val shown = StringBuilder()
        val spans = mutableListOf<Span>()
        var cursor = 0
        fun appendPlain(plain: String) {
            for (match in bareUrl.findAll(plain)) {
                val raw = match.value.trimEnd { it in TRAILING_PUNCTUATION }
                val start = shown.length + match.range.first
                val url = if (raw.startsWith("www.", ignoreCase = true)) "http://$raw" else raw
                spans += Span(start, start + raw.length, raw, url)
            }
            shown.append(plain)
        }
        for (match in markdownLink.findAll(text)) {
            appendPlain(text.substring(cursor, match.range.first))
            val (label, url) = match.destructured
            spans += Span(shown.length, shown.length + label.length, label.trim(), url)
            shown.append(label)
            cursor = match.range.last + 1
        }
        appendPlain(text.substring(cursor))
        return shown.toString() to spans
    }

    /** The note for display: markdown links become link runs, bare URLs are linked in place. */
    fun annotated(text: String): AnnotatedString {
        val (shown, spans) = parse(text)
        return buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                append(shown.substring(cursor, span.start))
                withLink(LinkAnnotation.Url(span.url)) { append(shown.substring(span.start, span.end)) }
                cursor = span.end
            }
            append(shown.substring(cursor))
        }
    }

    /**
     * Every link in the note, in note order — for edit surfaces, where the
     * text sits in a field and can't be tappable itself, so links render as
     * separate rows beneath it.
     */
    fun links(text: String): List<NoteLink> = parse(text).second.mapIndexed { index, span -> NoteLink(span.label, span.url, index) }
}
