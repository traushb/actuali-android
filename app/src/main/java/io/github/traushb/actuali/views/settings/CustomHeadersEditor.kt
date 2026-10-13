package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.traushb.actuali.services.CustomHeader
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormTextFieldRow

/**
 * User-defined HTTP headers sent on every server request. Edits a draft and
 * commits it when the screen goes away, so the store (and its keystore write)
 * is touched once per visit rather than per keystroke.
 */
@Composable
fun CustomHeadersEditor() {
    val store = LocalBudgetStore.current
    val draft = remember { mutableStateListOf(*store.customHeaders.toTypedArray()) }
    DisposableEffect(Unit) {
        onDispose {
            val cleaned = draft.filter { it.name.isNotBlank() }
            if (cleaned != store.customHeaders) store.customHeaders = cleaned
        }
    }
    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    Scaffold(topBar = { AppTopBar(L("Custom HTTP Headers")) }) { padding ->
        FormColumn(padding) {
            FormSection(footer = L("Sent with every request to your server. For Cloudflare Access, add a service token as two headers: CF-Access-Client-Id and CF-Access-Client-Secret.")) {
                draft.forEachIndexed { index, header ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FormTextFieldRow(header.name, { draft[index] = header.copy(name = it) }, L("Header name"), Modifier.testTag("customHeaders.name.$index"), keyboardOptions = plain)
                            FormTextFieldRow(header.value, { draft[index] = header.copy(value = it) }, L("Value"), Modifier.testTag("customHeaders.value.$index"), keyboardOptions = plain)
                        }
                        IconButton(onClick = { draft.removeAt(index) }) { Icon(Icons.Filled.Delete, contentDescription = L("Delete")) }
                    }
                }
                FormRow(
                    L("Add header"),
                    Modifier.testTag("customHeaders.add"),
                    leading = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { draft.add(CustomHeader()) },
                )
            }
        }
    }
}
