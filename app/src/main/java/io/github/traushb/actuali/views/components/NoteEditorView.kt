package io.github.traushb.actuali.views.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.saveNote
import io.github.traushb.actuali.views.LocalBudgetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Editor for one row's note — a category (GH #131) or an account (GH #198).
 * Actual's `notes` table is keyed by the annotated row's id, so one editor
 * serves every entity. A failed save keeps the editor open with the text
 * intact so nothing the user typed is lost.
 */
@Composable
fun NoteEditorView(noteId: String, title: String, note: String, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(note) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // The sheet exists only to type in, so it opens straight into the keyboard.
    LaunchedEffect(Unit) { focus.requestFocus() }

    FormSheet(
        title = title,
        onDismiss = { if (!isSaving) onDismiss() },
        confirmEnabled = !isSaving,
        onConfirm = {
            scope.launch {
                isSaving = true
                saveError = null
                try {
                    store.saveNote(noteId, EntityNote.normalizedForSave(text))
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    saveError = e.localizedMessage
                } finally {
                    isSaving = false
                }
            }
        },
    ) {
        FormSection(footer = saveError ?: L("Syncs back to Actual, so it shows on every device on this budget. Clearing the text removes the note.")) {
            TextField(
                text,
                { text = it },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(focus).testTag("noteEditor"),
            )
            TagSuggestionBar(text, store.tags) { text = it }
            NoteLinkRows(text)
        }
    }
}
