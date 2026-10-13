package io.github.traushb.actuali.views.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.backupFile
import io.github.traushb.actuali.services.database.Backup
import io.github.traushb.actuali.services.database.BackupDestinationManager
import io.github.traushb.actuali.services.refreshBackups
import io.github.traushb.actuali.services.restoreBackup
import io.github.traushb.actuali.services.revertToLatest
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val backupDateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatBackupDate(millis: Long): String = backupDateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * The open budget's backups: restore one, export it, revert a restore, and
 * the folder every new backup is mirrored to (a storage access tree on
 * Android, a security-scoped bookmark on iOS).
 */
@Composable
fun BackupListView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val destinations = BackupDestinationManager.shared
    var pendingRestore by remember { mutableStateOf<Backup?>(null) }
    var destinationErrorMessage by remember { mutableStateOf<String?>(null) }
    // Destination state lives in preferences; bump to re-read it after a change.
    var destinationVersion by remember { mutableIntStateOf(0) }
    val destinationName = remember(destinationVersion) { destinations.destinationName }
    val lastMirroredDate = remember(destinationVersion) { destinations.lastMirroredDate }
    val lastMirrorError = remember(destinationVersion) { destinations.lastMirrorError }
    var exporting by remember { mutableStateOf<File?>(null) }
    val hasLatest = store.backups.any { it.isLatest }
    val archives = store.backups.filterIsInstance<Backup.Archive>()

    LaunchedEffect(Unit) { store.refreshBackups() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                destinations.saveDestination(tree)
            } catch (e: Exception) {
                destinationErrorMessage = e.localizedMessage
                return@launch
            }
            destinationVersion++
            val budgetId = store.currentBudgetId ?: return@launch
            val files = archives.mapNotNull { store.backupFile(it.id) }
            withContext(Dispatchers.IO) { destinations.mirrorExistingBackups(budgetId, files) }
            destinationVersion++
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        val source = exporting
        exporting = null
        if (target == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.error = e.localizedMessage
            }
        }
    }

    Scaffold(topBar = { AppTopBar(L("Backups")) }) { padding ->
        FormColumn(padding) {
            if (hasLatest) {
                FormSection(L("You're Working From a Backup"), L("Reverting restores the budget exactly as it was before the backup was loaded.")) {
                    FormButtonRow(L("Revert to Original Version"), { scope.launch { store.revertToLatest() } })
                }
            }

            FormSection(
                L("Destination"),
                if (destinationName != null) {
                    L("Backups are saved locally on this device and automatically mirrored to your chosen folder under Actuali/<budgetId>/.")
                } else {
                    L("Backups are stored safely in Actuali's private app storage. You can choose a custom folder to automatically mirror every backup there.")
                },
            ) {
                FormRow(
                    L("Backup Location"),
                    supporting = destinationName ?: L("Default (App Storage)"),
                    leading = destinationName?.let { { Icon(Icons.Filled.Folder, contentDescription = null) } },
                    trailing = {
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.testTag("backups.chooseFolder")) {
                            Text(L(if (destinationName == null) "Choose Folder" else "Change"))
                        }
                    },
                )
                if (destinationName != null) {
                    when {
                        lastMirrorError != null -> MirrorStatus(Icons.Filled.Warning, L("Mirror failed: %@", lastMirrorError), MaterialTheme.colorScheme.negative)
                        lastMirroredDate != null -> MirrorStatus(Icons.Filled.CheckCircle, L("Mirrored %@", formatBackupDate(lastMirroredDate)), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FormButtonRow(L("Reset to Default"), {
                        destinations.clearDestination()
                        destinationVersion++
                    }, destructive = true)
                }
            }

            FormSection(
                header = if (archives.isNotEmpty()) L("Available Backups") else null,
                footer = if (!hasLatest && archives.isNotEmpty()) {
                    L("Backups are stored in Actuali's private app storage on this device. Tap Export to save a copy to another folder or a cloud drive. Tapping a backup restores it (your current data is saved first so you can revert).")
                } else {
                    null
                },
            ) {
                if (archives.isEmpty()) FormRow(L("No backups yet"), enabled = false)
                archives.forEach { backup ->
                    val file = store.backupFile(backup.id)
                    FormRow(
                        formatBackupDate(backup.date),
                        Modifier.testTag("backups.row.${backup.id}"),
                        supporting = L("Tap to restore"),
                        trailing = file?.let {
                            {
                                OutlinedButton(onClick = {
                                    exporting = it
                                    exporter.launch(it.name)
                                }) {
                                    Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(L("Export"), modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        },
                        onClick = { pendingRestore = backup },
                    )
                }
            }
        }
    }

    pendingRestore?.let { backup ->
        ConfirmationDialog(
            title = L("Restore this backup?"),
            confirmLabel = L("Restore"),
            message = L(
                if (store.isConnected) {
                    "Restoring disconnects this budget from sync. To sync again, re-download the budget from your server — that replaces the restored data. Your current data is saved first so you can revert."
                } else {
                    "Your current data is saved first so you can revert."
                },
            ),
            destructive = true,
            onDismiss = { pendingRestore = null },
            onConfirm = { scope.launch { store.restoreBackup(backup.id) } },
        )
    }
    MessageAlert(L("Couldn't Set Backup Folder"), destinationErrorMessage) { destinationErrorMessage = null }
}

@Composable
private fun MirrorStatus(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
