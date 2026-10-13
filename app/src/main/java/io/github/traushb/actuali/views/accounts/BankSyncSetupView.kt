package io.github.traushb.actuali.views.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.banksync.BankSyncRemoteAccount
import io.github.traushb.actuali.services.canSyncBanks
import io.github.traushb.actuali.services.connectSimpleFIN
import io.github.traushb.actuali.services.createAccount
import io.github.traushb.actuali.services.disconnectSimpleFIN
import io.github.traushb.actuali.services.fetchBankAccounts
import io.github.traushb.actuali.services.linkBankAccount
import io.github.traushb.actuali.services.network.SimpleFINAccount
import io.github.traushb.actuali.services.refreshBankSyncSource
import io.github.traushb.actuali.services.resolvedBankSyncImportStartDay
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.setBankSyncImportStartDay
import io.github.traushb.actuali.services.syncBankAccounts
import io.github.traushb.actuali.services.unlinkBankAccount
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val SIMPLEFIN_BRIDGE_URL = "https://bridge.simplefin.org/"

/**
 * Set up a SimpleFIN bank feed and wire its accounts to budget accounts. With
 * a connection available (the server's own, or one claimed on this device) it
 * lists the bridge's accounts; with neither, it's a paste field for a setup
 * token. Which connection is in play is said out loud: only the server's is
 * shared with the web app.
 */
@Composable
fun BankSyncSetupView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var setupToken by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var isLoadingAccounts by remember { mutableStateOf(false) }
    var remoteAccounts by remember { mutableStateOf<List<SimpleFINAccount>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var linkTarget by remember { mutableStateOf<BankSyncRemoteAccount?>(null) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    // Null until the stored day loads, so the initial value is never written back.
    var importStartDay by remember { mutableStateOf<Int?>(null) }
    var isBackfilling by remember { mutableStateOf(false) }

    suspend fun loadRemoteAccounts() {
        isLoadingAccounts = true
        try {
            remoteAccounts = store.fetchBankAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.localizedMessage
        } finally {
            isLoadingAccounts = false
        }
    }

    LaunchedEffect(Unit) {
        importStartDay = store.resolvedBankSyncImportStartDay()
        // Which half of the screen applies depends on whether the server does SimpleFIN itself.
        store.refreshBankSyncSource()
        if (store.canSyncBanks && remoteAccounts.isEmpty()) loadRemoteAccounts()
    }

    /** Store the day, then reach it; a run that fails costs nothing — the next sync reaches just as far. */
    fun applyImportStart(day: Int) {
        store.setBankSyncImportStartDay(day)
        importStartDay = day
        isBackfilling = true
        scope.launch {
            try {
                val result = store.syncBankAccounts()
                if (result.problems.isNotEmpty()) errorMessage = result.problems.joinToString("\n\n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
            } finally {
                isBackfilling = false
            }
        }
    }

    Scaffold(topBar = { AppTopBar(L("Bank Sync")) }) { padding ->
        FormColumn(padding) {
            if (store.canSyncBanks) {
                FormSection(
                    footer = if (store.serverProvidesBankSync) {
                        L("Your Actual server holds the SimpleFIN credentials, so this app and the web app share one connection — accounts you link in either place work in both.")
                    } else {
                        L("Your server has no SimpleFIN connection of its own, so this device uses the token you gave it. Accounts you link here still appear in the web app, but it needs its own SimpleFIN setup to sync them.")
                    },
                ) {
                    FormRow(
                        if (store.serverProvidesBankSync) L("Using your server's SimpleFIN connection") else L("Using this device's SimpleFIN connection"),
                        leading = { Icon(if (store.serverProvidesBankSync) Icons.Filled.Dns else Icons.Filled.PhoneAndroid, contentDescription = null) },
                    )
                }
                FormSection(L("Bank Accounts"), footer = L("Tap an account to link it to a budget account. Linked accounts import new transactions every time you sync.")) {
                    when {
                        isLoadingAccounts -> FormRow(L("Loading accounts…"), leading = { LoadingIndicator(Modifier.size(20.dp)) })
                        remoteAccounts.isEmpty() -> FormRow(L("No accounts yet. Add a bank at SimpleFIN, then pull to refresh."))
                        else -> remoteAccounts.forEach { remote ->
                            val account = remote.remoteAccount
                            val linked = store.bankSyncAccounts.firstOrNull { it.externalAccountId == account.id && it.syncSource == account.source.rawValue }
                            FormRow(
                                account.name,
                                supporting = account.institutionName,
                                value = account.balanceCents?.let(store::displayBalance),
                                trailing = linked?.let { { Icon(Icons.Filled.Link, contentDescription = it.name, tint = MaterialTheme.colorScheme.positive) } },
                                navigates = true,
                                onClick = { linkTarget = account },
                            )
                        }
                    }
                }
                FormSection {
                    FormButtonRow(L("Refresh account list"), { scope.launch { loadRemoteAccounts() } }, enabled = !isLoadingAccounts)
                    if (store.isSimpleFINConfigured) {
                        FormButtonRow(L("Disconnect this device"), { confirmingDisconnect = true }, destructive = true)
                    }
                }
            } else {
                FormSection(
                    L("SimpleFIN"),
                    footer = L(
                        "SimpleFIN gives apps read-only access to your bank. Create a token at bridge.simplefin.org and paste it here — it can only be claimed once, so use a fresh one if another app already has it.\n\nIf you set SimpleFIN up on your Actual server instead, this app uses that connection automatically and you won't need a token here at all.",
                    ),
                ) {
                    OutlinedTextField(
                        setupToken,
                        { setupToken = it },
                        placeholder = { Text(L("Setup token")) },
                        enabled = !isConnecting,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(16.dp),
                    )
                    FormButtonRow(if (isConnecting) L("Connecting…") else L("Connect"), {
                        val token = setupToken
                        isConnecting = true
                        scope.launch {
                            try {
                                store.connectSimpleFIN(token)
                                setupToken = ""
                                loadRemoteAccounts()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorMessage = e.localizedMessage
                            } finally {
                                isConnecting = false
                            }
                        }
                    }, enabled = !isConnecting && setupToken.isNotBlank())
                }
                FormSection { FormButtonRow(L("Open SimpleFIN Bridge"), { runCatching { uriHandler.openUri(SIMPLEFIN_BRIDGE_URL) } }) }
            }

            // Nothing to import from means nothing to date.
            if (store.canSyncBanks) {
                FormSection(
                    L("Import Start Date"),
                    footer = L("Linked accounts import transactions from this day on. It starts out as the day this budget began; pick an earlier day to pull in older history. Transactions already imported always stay."),
                ) {
                    val day = importStartDay
                    FormDateRow(
                        L("Import transactions since"),
                        day?.let { DayDate.fromYYYYMMDD(it)?.localDate } ?: LocalDate.now(),
                        { picked -> Transaction.yyyymmdd(picked).takeIf { it != day }?.let(::applyImportStart) },
                        enabled = day != null && !isBackfilling,
                        latest = LocalDate.now(),
                    )
                    if (isBackfilling) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LoadingIndicator(Modifier.size(20.dp))
                            Text(L("Importing older transactions…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    linkTarget?.let { remote -> FullScreenSheet({ linkTarget = null }) { BankAccountLinkView(remote) { linkTarget = null } } }
    if (confirmingDisconnect) {
        ConfirmationDialog(
            title = L("Disconnect SimpleFIN?"),
            message = L("Your accounts stay linked and keep the transactions they've already imported. To sync from this device again you'll need a new setup token."),
            confirmLabel = L("Disconnect"),
            destructive = true,
            onDismiss = { confirmingDisconnect = false },
            onConfirm = {
                try {
                    store.disconnectSimpleFIN()
                    remoteAccounts = emptyList()
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage
                }
            },
        )
    }
    MessageAlert(L("Bank Sync"), errorMessage) { errorMessage = null }
}

/** Pick what a provider-side account feeds: an account the budget already has, or a new one made for it. */
@Composable
fun BankAccountLinkView(remote: BankSyncRemoteAccount, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var newAccountOffBudget by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val currentLink = store.bankSyncAccounts.firstOrNull { it.externalAccountId == remote.id && it.syncSource == remote.source.rawValue }
    // One feed per account: an account already linked elsewhere isn't offered.
    val linkedIds = store.bankSyncAccounts.mapTo(HashSet()) { it.id }
    val linkable = store.accounts.filter { !it.closed && it.id !in linkedIds }

    fun perform(work: suspend () -> Unit) {
        isWorking = true
        scope.launch {
            try {
                work()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
            } finally {
                isWorking = false
            }
        }
    }

    Scaffold(topBar = { AppTopBar(remote.name, actions = { TextButton(onClick = onDismiss) { Text(L("Done")) } }) }) { padding ->
        FormColumn(padding) {
            currentLink?.let { linked ->
                FormSection(footer = L("Transactions already imported stay in the account.")) {
                    FormButtonRow(L("Unlink from %@", linked.name), { perform { store.unlinkBankAccount(linked.id) } }, destructive = true, enabled = !isWorking)
                }
            }
            FormSection(L("New account"), footer = L("Creates a budget account for %@ at %@ and links it. Its opening balance is worked out on the first sync.", remote.name, remote.institutionName)) {
                FormToggleRow(L("Off budget"), newAccountOffBudget, { newAccountOffBudget = it }, enabled = !isWorking)
                FormButtonRow(L("Create “%@”", remote.name), {
                    perform {
                        // No opening balance: the first sync works it out from the bridge's balance and the history it downloads.
                        val account = store.createAccount(remote.name, newAccountOffBudget, startingBalanceCents = 0)
                        store.linkBankAccount(account.id, remote)
                    }
                }, enabled = !isWorking)
            }
            FormSection(L("Link to an existing account")) {
                if (linkable.isEmpty()) FormRow(L("Every open account is already linked."))
                linkable.forEach { account ->
                    FormRow(account.name, value = store.displayBalance(account.balance), enabled = !isWorking, onClick = { perform { store.linkBankAccount(account.id, remote) } })
                }
            }
        }
    }
    MessageAlert(L("Bank Sync"), errorMessage) { errorMessage = null }
}
