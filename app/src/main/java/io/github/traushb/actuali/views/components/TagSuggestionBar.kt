package io.github.traushb.actuali.views.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.NoteLinkText
import io.github.traushb.actuali.models.Tag

/** Hashtag autocomplete: detecting the `#tag` being typed at the end of a note, and completing it. */
object TagSuggestionHelper {
    /** The active `#token` at the end of [text]: its query ("vac" for "#vac", "" for "#") and where it starts. */
    fun activeTagToken(text: String): Pair<String, Int>? {
        val hashIndex = text.lastIndexOf('#')
        if (hashIndex < 0) return null
        // An escaped "##" isn't a tag.
        if (hashIndex > 0 && text[hashIndex - 1] == '#') return null
        // Everything from # to the end must be a single word.
        val token = text.substring(hashIndex)
        if (token.any { it.isWhitespace() }) return null
        return token.drop(1) to hashIndex
    }

    /** Replaces the active `#token` in [text] with `#completedTag `. */
    fun applyTagCompletion(completedTag: String, text: String): String {
        val (_, start) = activeTagToken(text) ?: return "$text #$completedTag "
        return text.substring(0, start) + "#$completedTag "
    }

    /** Live tags matching [query] by prefix; every live tag for an empty query (just typed `#`). */
    fun matchingTags(tags: List<Tag>, query: String): List<Tag> {
        val active = tags.filter { !it.tombstone && !it.hidden }
        if (query.isEmpty()) return active
        return active.filter { it.tag.lowercase().startsWith(query.lowercase()) }
    }
}

/** Horizontal tag chips offered while a `#tag` is being typed in a note. */
@Composable
fun TagSuggestionBar(text: String, availableTags: List<Tag>, onTextChange: (String) -> Unit) {
    val token = TagSuggestionHelper.activeTagToken(text) ?: return
    val suggestions = TagSuggestionHelper.matchingTags(availableTags, token.first)
    if (suggestions.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tag in suggestions) {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .clickable { onTextChange(TagSuggestionHelper.applyTagCompletion(tag.tag, text)) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("tagSuggestion-${tag.tag}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(8.dp).background(tag.composeColor, CircleShape))
                Text(tag.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Tappable rows for the links in a note being edited (GH #190): a text field
 * can't render tappable links, so markdown links and bare URLs in the draft
 * surface as rows beneath it, updating as the user types.
 */
@Composable
fun NoteLinkRows(text: String) {
    val uriHandler = LocalUriHandler.current
    for (link in NoteLinkText.links(text)) {
        ListItem(
            headlineContent = { Text(link.label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { runCatching { uriHandler.openUri(link.url) } }.testTag("noteLinkRow"),
        )
    }
}
