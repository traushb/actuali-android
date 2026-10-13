package io.github.traushb.actuali.views.settings

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.traushb.actuali.models.BudgetType
import io.github.traushb.actuali.services.BackgroundRefreshStatus
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.createBudget
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.database.BudgetMetadata
import io.github.traushb.actuali.services.downloadBudget
import io.github.traushb.actuali.services.fetchRemoteBudgets
import io.github.traushb.actuali.services.loadDemoData
import io.github.traushb.actuali.services.makeBackupNow
import io.github.traushb.actuali.services.refreshBackups
import io.github.traushb.actuali.services.resetSyncState
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.sync.EncryptionKeyManager
import io.github.traushb.actuali.services.sync.SyncState
import io.github.traushb.actuali.services.unlockAndOpen
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.warning
import kotlinx.coroutines.launch

@Composable
fun ConnectionDataSettingsView() {
    val store = LocalBudgetStore.current
    Scaffold(topBar = { AppTopBar(L("Connection & Data")) }) { padding ->
        FormColumn(padding) {
            ServerConnectionSection()
            // Budget selection needs a live server session. Sync and backups
            // remain reachable for any loaded budget, including demo and
            // offline budgets without a session.
            if (store.isConnected) {
                BudgetSelectionSection()
                if (store.canChangeBudgetType) BudgetTypeSection()
            }
            if (store.currentBudgetId != null) {
                SyncSection()
                BackupSection()
            }
        }
    }
}

@Composable
private fun BudgetTypeSection() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    FormSection(L("Budget")) {
        FormPickerRow(
            L("Budget type"),
            listOf(BudgetType.Envelope, BudgetType.Tracking),
            store.budgetType,
            { L(if (it == BudgetType.Envelope) "Envelope budget" else "Tracking budget") },
            { scope.launch { store.setBudgetType(it) } },
            Modifier.testTag("budgetType.picker"),
            enabled = !store.isLoading,
        )
    }
}

@Composable
private fun ServerConnectionSection() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editedServerURL by remember { mutableStateOf("") }
    var editedFallbackServerURL by remember { mutableStateOf("") }
    var demoMenu by remember { mutableStateOf(false) }

    // Re-hide when the app leaves the foreground, so a revealed password
    // doesn't stay up on return. ponytail: best effort for the recents
    // thumbnail too, which can be taken before this recomposes; the upgrade
    // is setRecentsScreenshotEnabled(false) while revealed.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { passwordVisible = false }
    LaunchedEffect(store.isConnected) { if (!store.isConnected) editing = false }

    @Composable
    fun UrlFields(serverURL: String, onServerURL: (String) -> Unit, fallback: String, onFallback: (String) -> Unit) {
        val url = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)
        FormTextFieldRow(serverURL, onServerURL, L("Server URL"), Modifier.testTag("connection.serverURL"), keyboardOptions = url)
        FormTextFieldRow(fallback, onFallback, L("Fallback Server URL"), Modifier.testTag("connection.fallbackServerURL"), keyboardOptions = url)
    }

    FormSection(
        header = L("Connection"),
        footer = if (store.isConnected) {
            null
        } else {
            L("Example: https://actual.example.com\n\nBehind an auth proxy like Cloudflare Access? Add a service token under “Custom HTTP headers.”\n\nNo server? Tap “Try the demo budget” to explore the app with sample data. The demo runs entirely on this device — it never connects to a server or touches a real budget.")
        },
        headerActions = if (store.isConnected) {
            {
                if (editing) {
                    TextButton(onClick = { editing = false }, enabled = !saving) { Text(L("Cancel")) }
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val saved = store.updateServerConnection(editedServerURL, editedFallbackServerURL)
                                saving = false
                                if (saved) editing = false
                            }
                        },
                        enabled = editedServerURL.isNotBlank() && !store.isLoading && !saving,
                    ) { Text(L("Save")) }
                } else {
                    TextButton(onClick = {
                        editedServerURL = store.serverURL
                        editedFallbackServerURL = store.fallbackServerURL
                        editing = true
                    }) { Text(L("Edit")) }
                }
            }
        } else {
            null
        },
    ) {
        when {
            store.isConnected && editing -> UrlFields(editedServerURL, { editedServerURL = it }, editedFallbackServerURL) { editedFallbackServerURL = it }
            store.isConnected -> {
                FormRow(L("Server URL"), value = store.serverURL)
                FormRow(L("Fallback Server URL"), value = store.fallbackServerURL.ifEmpty { L("None") })
            }
            else -> UrlFields(store.serverURL, { store.serverURL = it }, store.fallbackServerURL) { store.fallbackServerURL = it }
        }

        if (store.isConnected) {
            FormRow(L("Status"), value = L("Connected"), trailing = {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.positive, CircleShape))
            })
            FormButtonRow(L("Disconnect"), { confirmingDisconnect = true }, destructive = true)
        } else {
            // Show the password field before probing, when password login is
            // active, or when the first OpenID sign-in needs the server
            // password (no owner yet).
            if (store.availableLoginMethods.isEmpty() || store.passwordLoginActive || store.requiresServerPassword) {
                FormTextFieldRow(
                    password,
                    { password = it },
                    L(if (store.requiresServerPassword && !store.passwordLoginActive) "Server password (first sign-in)" else "Password"),
                    Modifier.testTag("connection.password"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = L(if (passwordVisible) "Hide password" else "Show password"),
                            )
                        }
                    },
                )
            }
            FormButtonRow(
                L("Connect"),
                {
                    scope.launch {
                        store.connect()
                        if (store.error != null) return@launch
                        // Discover available auth methods, then log in with
                        // password directly when that's the active method.
                        store.checkLoginMethods()
                        // A probe that couldn't reach the server has already
                        // explained why; don't repeat it as a login attempt.
                        if (store.error != null) return@launch
                        if (store.passwordLoginActive && password.isNotEmpty()) store.login(password)
                    }
                },
                Modifier.testTag("connection.connect"),
                enabled = store.serverURL.isNotEmpty() && !store.isLoading,
            )
            if (store.supportsOpenIDLogin) {
                FormButtonRow(
                    L("Sign in with OpenID"),
                    { scope.launch { store.loginWithOpenID(password.ifEmpty { null }) } },
                    enabled = !store.isLoading && !(store.requiresServerPassword && password.isEmpty()),
                )
            }
            Box {
                FormButtonRow(L("Try the demo budget"), { demoMenu = true }, Modifier.testTag("connection.demo"), enabled = !store.isLoading)
                DropdownMenu(demoMenu, onDismissRequest = { demoMenu = false }) {
                    DropdownMenuItem(text = { Text(L("Envelope budget")) }, onClick = {
                        demoMenu = false
                        scope.launch { store.loadDemoData() }
                    })
                    DropdownMenuItem(text = { Text(L("Tracking budget")) }, onClick = {
                        demoMenu = false
                        scope.launch { store.loadDemoData(tracking = true) }
                    })
                }
            }
            val headerCount = store.customHeaders.count { it.name.isNotBlank() }
            val stack = LocalNavStack.current
            FormRow(L("Custom HTTP headers"), value = if (headerCount > 0) headerCount.toString() else null, navigates = true, onClick = { stack.push(Route.CustomHeaders) })
        }
    }

    if (confirmingDisconnect) {
        ConfirmationDialog(
            title = L("Disconnect and remove data?"),
            confirmLabel = L("Disconnect & Remove Data"),
            message = L("Signs out and deletes this device's copy of your budgets. Any changes that haven't synced to the server yet will be lost. Your data on the server is not affected."),
            destructive = true,
            onDismiss = { confirmingDisconnect = false },
            onConfirm = {
                scope.launch {
                    store.logout()
                    password = ""
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BudgetSelectionSection() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var budgetToUnlock by remember { mutableStateOf<BudgetStore.RemoteBudget?>(null) }
    var showingSelectPrompt by remember { mutableStateOf(false) }
    var budgetToRemoveLocally by remember { mutableStateOf<BudgetStore.RemoteBudget?>(null) }
    var budgetToDeleteFromServer by remember { mutableStateOf<BudgetStore.RemoteBudget?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newBudgetName by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    // One directory read per change of what's open or downloadable; every row check below shares it.
    val localBudgets = remember(store.currentBudgetId, store.remoteBudgets, store.downloadingBudgetId) { BudgetFileManager.shared.listLocalBudgets() }
    val selected = selectedBudget(store, localBudgets)
    val busy = store.downloadingBudgetId != null

    fun open(budget: BudgetStore.RemoteBudget) {
        if (budget.isEncrypted && EncryptionKeyManager.load(budget.id) == null) budgetToUnlock = budget else scope.launch { store.downloadBudget(budget) }
    }

    LaunchedEffect(Unit) {
        store.fetchRemoteBudgets()
        // One-time nudge after connecting: a fresh connection shouldn't leave empty tabs.
        if (store.currentBudgetId == null && store.remoteBudgets.isNotEmpty()) showingSelectPrompt = true
    }

    FormSection(
        header = L("Budget Selection"),
        footer = when {
            store.currentBudgetId == null && store.remoteBudgets.isEmpty() && !store.isLoading ->
                L("No budgets were found on your server. Tap Create New Budget to make one, or Refresh Budgets if you've created one elsewhere.")
            store.currentBudgetId == null -> L("Select a budget to load it onto this device.")
            store.remoteBudgets.isNotEmpty() -> L("Tap the selected budget to switch budgets. Touch and hold it for options to remove it from this device or delete it from the server.")
            else -> null
        },
    ) {
        Box {
            val others = store.remoteBudgets.filter { it.id != selected?.id }
            ListItem(
                headlineContent = { Text(selected?.name ?: L("Select a Budget"), maxLines = 1) },
                leadingContent = if (selected?.isEncrypted == true) ({ Icon(Icons.Filled.Lock, contentDescription = null) }) else null,
                trailingContent = {
                    if (busy) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Filled.UnfoldMore, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .combinedClickable(
                        enabled = !busy && (selected != null || store.remoteBudgets.isNotEmpty()),
                        onLongClick = if (selected != null) ({ optionsMenu = true }) else null,
                        onClick = { picker = true },
                    )
                    .testTag(if (selected != null) "budget-selection-selected" else "budget-selection-select"),
            )
            DropdownMenu(picker, onDismissRequest = { picker = false }, modifier = Modifier.testTag("budget-selection-picker")) {
                for (budget in others) {
                    DropdownMenuItem(
                        text = { Text(budget.name, maxLines = 1) },
                        leadingIcon = if (budget.isEncrypted) ({ Icon(Icons.Filled.Lock, contentDescription = null) }) else null,
                        onClick = {
                            picker = false
                            open(budget)
                        },
                    )
                }
                if (others.isEmpty()) DropdownMenuItem(text = { Text(L("No other budgets")) }, onClick = {}, enabled = false)
            }
            DropdownMenu(optionsMenu, onDismissRequest = { optionsMenu = false }) {
                if (selected != null && localBudgets.any { it.cloudFileId == selected.id }) {
                    DropdownMenuItem(text = { Text(L("Remove from This Device")) }, onClick = {
                        optionsMenu = false
                        budgetToRemoveLocally = selected
                    })
                }
                DropdownMenuItem(text = { Text(L("Delete from Server…"), color = MaterialTheme.colorScheme.negative) }, onClick = {
                    optionsMenu = false
                    budgetToDeleteFromServer = selected
                })
            }
        }
        if (store.remoteBudgets.isEmpty() && !store.isLoading) {
            FormButtonRow(L("Refresh Budgets"), { scope.launch { store.fetchRemoteBudgets() } })
        }
        FormButtonRow(L("Create New Budget"), {
            newBudgetName = ""
            creating = true
        }, enabled = !store.isLoading && !busy)
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(L("New Budget")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L("Creates an empty budget file on your server and opens it on this device."))
                    OutlinedTextField(newBudgetName, { newBudgetName = it }, placeholder = { Text(L("Name")) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton({
                    creating = false
                    scope.launch { store.createBudget(newBudgetName) }
                }) { Text(L("Create")) }
            },
            dismissButton = { TextButton({ creating = false }) { Text(L("Cancel")) } },
        )
    }
    if (showingSelectPrompt) {
        AlertDialog(
            onDismissRequest = { showingSelectPrompt = false },
            title = { Text(L("Select a Budget")) },
            text = {
                Column {
                    Text(L("You're connected! Choose which budget to load onto this device."))
                    for (budget in store.remoteBudgets) {
                        TextButton({
                            showingSelectPrompt = false
                            open(budget)
                        }) { Text(budget.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ showingSelectPrompt = false }) { Text(L("Not Now")) } },
        )
    }
    budgetToRemoveLocally?.let { budget ->
        ConfirmationDialog(
            title = L("Remove from this device?"),
            confirmLabel = L("Remove from This Device"),
            message = L("Removes “%@” from this device, including its local backups. Any changes that haven't synced to the server yet will be lost. The budget stays on your server and can be downloaded again.", budget.name),
            destructive = true,
            onDismiss = { budgetToRemoveLocally = null },
            onConfirm = { scope.launch { store.removeLocalBudget(budget.id) } },
        )
    }
    budgetToUnlock?.let { budget -> EncryptionPasswordSheet(budget) { budgetToUnlock = null } }
    budgetToDeleteFromServer?.let { budget -> DeleteServerBudgetSheet(budget) { budgetToDeleteFromServer = null } }
}

/**
 * The open budget as a server budget: currentBudgetId is the internal id, so
 * it maps through the local metadata. When the server list doesn't include it
 * (still loading, or deleted from another client) it still gets a row, so the
 * selection stays visible and the local copy stays removable.
 */
private fun selectedBudget(store: BudgetStore, locals: List<BudgetMetadata>): BudgetStore.RemoteBudget? {
    val metadata = locals.firstOrNull { it.id == store.currentBudgetId } ?: return null
    val fileId = metadata.cloudFileId ?: return null
    return store.remoteBudgets.firstOrNull { it.id == fileId }
        ?: BudgetStore.RemoteBudget(fileId, metadata.budgetName ?: L("Unknown"), metadata.groupId, metadata.encryptKeyId != null)
}

@Composable
fun EncryptionPasswordSheet(budget: BudgetStore.RemoteBudget, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    FormSheet(
        L("Unlock Budget"),
        onDismiss = { if (!working) onDismiss() },
        confirmLabel = L("Unlock"),
        confirmEnabled = password.isNotEmpty() && !working,
        onConfirm = {
            working = true
            errorText = null
            scope.launch {
                val failure = store.unlockAndOpen(budget, password)
                working = false
                if (failure != null) errorText = failure else onDismiss()
            }
        },
    ) {
        FormSection(
            L("End-to-End Encrypted"),
            L("“%@” is end-to-end encrypted. Enter its encryption password to open it on this device. This is separate from your server password.", budget.name),
        ) {
            FormTextFieldRow(
                password,
                { password = it },
                L("Encryption password"),
                Modifier.testTag("encryptionPassword.field"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.negative, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Retype-to-confirm for the irreversible half of budget deletion: Delete stays
 * disabled until the typed name matches.
 */
@Composable
private fun DeleteServerBudgetSheet(budget: BudgetStore.RemoteBudget, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var typedName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    FormSheet(
        L("Delete Budget"),
        onDismiss = { if (!working) onDismiss() },
        confirmLabel = L("Delete"),
        confirmEnabled = typedName.trim() == budget.name && !working,
        onConfirm = {
            working = true
            errorText = null
            scope.launch {
                val failure = store.deleteServerBudget(budget)
                working = false
                if (failure != null) errorText = failure else onDismiss()
            }
        },
    ) {
        FormSection(
            L("Delete from Server"),
            L("This permanently deletes “%@” from your Actual server for every device that uses it, along with this device's copy and its local backups. This cannot be undone.\n\nType the budget's name to confirm.", budget.name),
        ) {
            FormTextFieldRow(
                typedName,
                { typedName = it },
                L("Budget name"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                enabled = !working,
            )
        }
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.negative, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SyncSection() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val status = store.syncStatus
    var lastBackgroundRefresh by remember { mutableStateOf(BackgroundRefreshStatus().lastRun) }
    var confirmingReset by remember { mutableStateOf(false) }
    // The worker records its run while the app is in the background, so re-read on every return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lastBackgroundRefresh = BackgroundRefreshStatus().lastRun }

    fun relative(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    FormSection(L("Sync")) {
        FormRow(L("Status"), trailing = {
            when (val state = status.state) {
                SyncState.Idle -> Text(L("Idle"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SyncState.Syncing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L("Syncing..."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncState.Offline -> Text(L("Offline"), color = MaterialTheme.colorScheme.warning)
                is SyncState.Error -> Text(L("Error"), color = MaterialTheme.colorScheme.negative)
            }
        })
        (status.state as? SyncState.Error)?.let { Notice(it.message) }
        if (store.syncDetachedByRestore) {
            Notice(L("Sync is disconnected because a backup was restored. Re-download the budget from your server to resume syncing — that replaces the restored data with the server copy."))
        }
        status.lastSyncTime?.let {
            FormRow(L("Last Sync"), value = relative(it))
        }
        // "Never", or a stale time, means the system isn't running the
        // periodic work — battery restrictions on the app, usually.
        FormRow(L("Last Background Refresh"), value = lastBackgroundRefresh?.let(::relative) ?: L("Never"))
        FormButtonRow(L("Sync Now"), { scope.launch { store.sync() } }, enabled = status.state != SyncState.Syncing)
        if (status.state is SyncState.Error) {
            FormButtonRow(L("Reset Sync State"), { confirmingReset = true }, destructive = true)
        }
    }
    if (confirmingReset) {
        ConfirmationDialog(
            title = L("Reset sync state?"),
            confirmLabel = L("Reset & Resync"),
            message = L("Discards the local sync marker and re-checks your budget against the server, pulling down anything missing. Local edits are re-sent rather than discarded, but a large budget can take a moment to reconcile."),
            destructive = true,
            onDismiss = { confirmingReset = false },
            onConfirm = { scope.launch { store.resetSyncState() } },
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun BackupSection() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val stack = LocalNavStack.current
    var confirmingBackupOverBaseline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.refreshBackups() }

    FormSection(
        L("Backups"),
        L("Backups are stored in Actuali's private app storage on this device. One is taken automatically when you leave the app. Tap Backups to restore or export them anytime."),
    ) {
        FormRow(
            L("Backups"),
            Modifier.testTag("settings.backups"),
            value = store.backups.size.takeIf { it > 0 }?.toString(),
            navigates = true,
            onClick = { stack.push(Route.Backups) },
        )
        FormButtonRow(L("Back Up Now"), {
            if (store.isViewingBackup) confirmingBackupOverBaseline = true else scope.launch { store.makeBackupNow() }
        })
    }
    if (confirmingBackupOverBaseline) {
        ConfirmationDialog(
            title = L("Back up the restored data?"),
            confirmLabel = L("Back Up"),
            message = L("This makes the restored data your current budget and removes the option to revert to the version from before you loaded a backup."),
            destructive = true,
            onDismiss = { confirmingBackupOverBaseline = false },
            onConfirm = { scope.launch { store.makeBackupNow() } },
        )
    }
}
