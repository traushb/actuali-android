package io.github.traushb.actuali.views.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalTopBarScrollBehavior
import io.github.traushb.actuali.views.SystemBarsAppearance
import io.github.traushb.actuali.views.TopBarScrolling

/** Set inside a [FullScreenSheet]: the top bar closes the sheet instead of popping the tab's stack. */
val LocalSheetDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/** An iOS sheet hosting a whole screen (its own scaffold and top bar) over the current one, which keeps its state underneath. */
@Composable
fun FullScreenSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SystemBarsAppearance()
        KeyboardAccessoryHost {
            TopBarScrolling(large = false) { CompositionLocalProvider(LocalSheetDismiss provides onDismiss, content = content) }
        }
    }
}

/**
 * An iOS form sheet — a NavigationStack with Cancel and a confirming action —
 * as a full-screen dialog: close on the left, [confirmLabel] on the right.
 * Without [onConfirm] it's a plain closable screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = L("Save"),
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SystemBarsAppearance()
        KeyboardAccessoryHost {
            TopBarScrolling(large = false) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(title, maxLines = 1) },
                            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = L("Cancel")) } },
                            actions = { onConfirm?.let { TextButton(onClick = it, enabled = confirmEnabled) { Text(confirmLabel) } } },
                            colors = pageTopBarColors(),
                            scrollBehavior = LocalTopBarScrollBehavior.current,
                        )
                    },
                ) { padding -> FormColumn(padding, content = content) }
            }
        }
    }
}
