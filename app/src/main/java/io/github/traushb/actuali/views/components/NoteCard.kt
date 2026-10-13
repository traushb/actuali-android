package io.github.traushb.actuali.views.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.NoteLinkText
import io.github.traushb.actuali.services.L

/**
 * An account's or category's note (GH #131, #198): visible without digging,
 * tap to edit. Links in it open (GH #190).
 */
@Composable
fun NoteCard(note: EntityNote, tag: String, modifier: Modifier = Modifier, onEdit: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("common.note"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (note.isEmpty) {
                // Tinted: an empty note is an invitation to act, where an existing one is content to read.
                Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(L("common.addNote"), color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).testTag(tag), verticalAlignment = Alignment.Top) {
                    Text(NoteLinkText.annotated(note.text), modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Edit, contentDescription = L("Edit"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
