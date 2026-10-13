package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.BudgetType
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.database.Backup
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.BudgetMetadata
import io.github.traushb.actuali.services.database.fetchBudgetMonth
import io.github.traushb.actuali.services.database.fetchCardAccountMappings
import io.github.traushb.actuali.services.database.fetchCategoryGroups
import io.github.traushb.actuali.services.database.fetchCreditCardConfigs
import io.github.traushb.actuali.services.database.fetchCurrencyCode
import io.github.traushb.actuali.services.database.fetchDepositConfigs
import io.github.traushb.actuali.services.database.fetchEnvelopeBudgetSummary
import io.github.traushb.actuali.services.database.fetchLoanConfigs
import io.github.traushb.actuali.services.database.fetchPayees
import io.github.traushb.actuali.services.database.fetchPreference
import io.github.traushb.actuali.services.database.fetchTags
import io.github.traushb.actuali.services.database.fetchTagSummaries
import io.github.traushb.actuali.services.database.fetchTransaction
import io.github.traushb.actuali.services.database.fetchTransactions
import io.github.traushb.actuali.services.database.fetchUncategorizedCount
import io.github.traushb.actuali.services.database.fetchUpcomingScheduledTransactionLength
import io.github.traushb.actuali.services.network.ActualServerError
import io.github.traushb.actuali.services.network.ServerVersion
import io.github.traushb.actuali.services.notifications.NewTransactionDetector
import io.github.traushb.actuali.services.notifications.NewTransactionNotifier
import io.github.traushb.actuali.services.schedules.SchedulePoster
import io.github.traushb.actuali.services.sync.EncryptionKeyError
import io.github.traushb.actuali.services.sync.EncryptionKeyManager
import io.github.traushb.actuali.services.sync.HybridLogicalClock
import io.github.traushb.actuali.services.sync.SyncClient
import io.github.traushb.actuali.services.sync.SyncEncryption
import io.github.traushb.actuali.services.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

private val logger = Logger("BudgetStore")

/** Opens the bundled blank budget template; installed at launch (an asset on Android, a file in tests). */
@Volatile
var blankBudgetTemplate: (() -> InputStream)? = null

// Budget Management

suspend fun BudgetStore.fetchRemoteBudgets() {
    isLoading = true
    error = null
    try {
        remoteBudgets = serverClient.listFiles().map { BudgetStore.RemoteBudget(it.fileId, it.name, it.groupId, it.encryptKeyId != null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.localizedMessage
    }
    isLoading = false
}

suspend fun BudgetStore.downloadBudget(remoteBudget: BudgetStore.RemoteBudget) {
    isLoading = true
    downloadingBudgetId = remoteBudget.id
    error = null

    // Close the existing database before importing over its file.
    syncStateJob?.cancel()
    syncStateJob = null
    retire(database, syncClient)
    syncClient = null
    database = null

    // Whether the budget actually got opened, so the initial sync below runs
    // only when there is something to catch up.
    var opened = false
    try {
        var loadedKey: io.github.traushb.actuali.services.sync.LoadedKey? = null
        if (remoteBudget.isEncrypted) {
            loadedKey = EncryptionKeyManager.load(remoteBudget.id) ?: run {
                error = L("This budget is encrypted. Enter its encryption password to open it.")
                isLoading = false
                downloadingBudgetId = null
                return
            }
        }

        // Download the (possibly encrypted) ZIP blob.
        var zipData = serverClient.downloadFile(remoteBudget.id)

        // Decrypt the whole blob for encrypted budgets.
        if (loadedKey != null) {
            val meta = serverClient.getFileInfo(remoteBudget.id).encryptMeta ?: throw ActualServerError.InvalidResponse
            if (meta.keyId != loadedKey.keyId) {
                runCatching { EncryptionKeyManager.remove(remoteBudget.id) }
                error = L("This budget's encryption key has changed. Re-enter the password.")
                isLoading = false
                downloadingBudgetId = null
                return
            }
            val iv = meta.iv ?: throw ActualServerError.InvalidResponse
            val authTag = meta.authTag ?: throw ActualServerError.InvalidResponse
            zipData = SyncEncryption.decrypt(zipData, iv, authTag, loadedKey.key)
        }

        val metadata = fileManager.importBudget(zipData, remoteBudget.id, remoteBudget.groupId)
        currentBudgetId = metadata.id
        isInitialSyncing = true
        opened = true
        // The initial sync can pull weeks of history, and this file's
        // messages_crdt rowids come from whichever client uploaded it, so a
        // watermark left by a previous copy would announce all that history
        // as new transactions.
        NewTransactionDetector.forgetWatermark(metadata.id, defaults)
        loadLocalBudget(metadata.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.localizedMessage
    }

    isLoading = false
    downloadingBudgetId = null

    // Outside the download spinner: the budget is on screen from here, it
    // just isn't caught up yet.
    if (opened) runInitialSync()
}

/**
 * The first sync after a budget is opened. A downloaded budget is a server
 * snapshot that can trail the server's message log by hours or days, so
 * catch it up immediately (GH #126).
 */
private suspend fun BudgetStore.runInitialSync() {
    try {
        if (syncClient != null) sync()
    } finally {
        isInitialSyncing = false
    }
}

/**
 * Validate an encryption password for a budget, persist the derived key, then
 * download it. Returns null on success, or an error message so the sheet can stay open.
 */
suspend fun BudgetStore.unlockAndOpen(remoteBudget: BudgetStore.RemoteBudget, password: String): String? {
    try {
        val keyInfo = serverClient.getKeyInfo(remoteBudget.id)
        val loaded = EncryptionKeyManager.deriveAndValidate(password, keyInfo)
        EncryptionKeyManager.store(loaded, remoteBudget.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EncryptionKeyError) {
        return e.message
    } catch (e: Exception) {
        return e.localizedMessage
    }
    downloadBudget(remoteBudget)
    return error // any download error surfaced by downloadBudget
}

/** Mirror of upstream's validateBudgetName (util/budget-name.ts:23), checked against the names already on the server and local files. */
fun budgetNameError(name: String, existingNames: List<String>): String? = when {
    name.isEmpty() -> L("Budget name cannot be blank")
    name.length > 100 -> L("Budget name is too long (max length 100)")
    name in existingNames -> L("“%@” already exists", name)
    else -> null
}

/**
 * Create a new empty budget file from the bundled blank template, register it
 * on the server, and open it. Mirrors upstream's createBudget followed by
 * cloudStorage.upload: the fresh cloudFileId plus the groupId the server
 * assigns is what makes desktop and web treat the file as one of their own.
 *
 * ponytail: creation requires the server to be reachable. Upstream tolerates a
 * failed upload because possiblyUpload retries later, but Actuali has no
 * re-upload path yet, so a local-only file would be stranded unsyncable —
 * instead a failed registration fails the whole create and removes the local
 * files. The upgrade path is a general upload-on-sync retry.
 */
suspend fun BudgetStore.createBudget(rawName: String) {
    val name = rawName.trim()
    val existingNames = remoteBudgets.map { it.name } + fileManager.listLocalBudgets().mapNotNull { it.budgetName }
    budgetNameError(name, existingNames)?.let {
        error = it
        return
    }
    val template = blankBudgetTemplate ?: run {
        error = L("The blank budget template is missing from the app bundle.")
        return
    }

    isLoading = true
    error = null

    // Whether /sync/upload-user-file completed: past that point the server
    // durably has the file, so a later local failure must not read as "the
    // create failed" — the budget exists and can simply be downloaded.
    var registeredOnServer = false
    var uploadOutcomeUnknown = false

    try {
        val metadata = fileManager.createBudget(name, template)
        val cloudFileId = UUID.randomUUID().toString().lowercase()
        var uploadStarted = false
        fun saveRegistration(groupId: String) {
            val registered = BudgetMetadata(
                id = metadata.id, budgetName = name, cloudFileId = cloudFileId, groupId = groupId,
                lastUploaded = LocalDate.now().toString(),
            )
            fileManager.metadataPath(metadata.id).writeBytes(registered.encoded())
        }
        try {
            val zipData = fileManager.makeUploadArchive(metadata.id)
            uploadStarted = true
            val groupId = serverClient.uploadFile(zipData, cloudFileId, name)
            registeredOnServer = true
            saveRegistration(groupId)
        } catch (uploadError: Exception) {
            if (uploadError is CancellationException) throw uploadError
            val files = if (uploadStarted) runCatching { serverClient.listFiles() }.getOrNull() else emptyList()
            val remote = files?.firstOrNull { it.fileId == cloudFileId }
            if (remote != null) {
                registeredOnServer = true
                saveRegistration(remote.groupId ?: throw uploadError)
            } else {
                // The local copy is still blank. Remove it even when the server
                // result is unknown: a committed copy can be downloaded later,
                // while an unregistered local copy is invisible and permanently
                // blocks this budget name.
                uploadOutcomeUnknown = files == null
                runCatching { fileManager.deleteBudget(metadata.id) }
                throw uploadError
            }
        }

        // Close the previous budget before switching, same as downloadBudget.
        syncStateJob?.cancel()
        syncStateJob = null
        retire(database, syncClient)
        syncClient = null
        database = null

        currentBudgetId = metadata.id
        loadLocalBudget(metadata.id)
        val loadError = error
        fetchRemoteBudgets()
        if (loadError != null) error = loadError
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = when {
            registeredOnServer -> {
                // The file exists server-side; surface it in the picker so one
                // tap downloads it instead of leaving an invisible orphan.
                fetchRemoteBudgets()
                L(
                    "“%@” was created on your server, but couldn't be finished on this device: %@ Select it in Budget Selection to download it.",
                    name, e.localizedMessage,
                )
            }
            uploadOutcomeUnknown -> L("The connection stopped before Actuali received the upload result. Reopen Connection & Data before you try again.")
            else -> e.localizedMessage
        }
    }
    isLoading = false
}

suspend fun BudgetStore.loadLocalBudget(budgetId: String) {
    if (budgetId != DemoDataSeeder.BUDGET_ID) {
        try {
            DemoDataSeeder.removeSamplePendingImport()
        } catch (e: Exception) {
            logger.error("Demo pending import cleanup failed: ${e.message}")
        }
    }
    isLoading = true
    isBudgetLoaded = false
    error = null
    schedulesLoaded = false
    scheduleLoadError = null
    val monthRequestGenerationBeforeLoad = budgetMonthRequestGeneration
    var published = false

    var db: BudgetDatabase? = null
    try {
        val previousDatabase = database
        val previousClient = syncClient
        val openedDb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BudgetDatabase(fileManager.databasePath(budgetId).path) }
        db = openedDb
        database = openedDb
        if (previousDatabase != null || previousClient != null) {
            syncStateJob?.cancel()
            syncStateJob = null
            syncClient = null
            retire(previousDatabase, previousClient)
        }

        // Fetch all data into locals first, then publish in one batch so the
        // UI never sees a torn snapshot if another load interleaves at a
        // suspension point. A null currency means the budget has no currency
        // preference; an empty value is Actual's explicit "None" setting.
        val fetchedCurrencyCode = openedDb.fetchCurrencyCode()
        val fetchedNumberFormat = openedDb.fetchPreference("numberFormat")
        val fetchedBudgetType = BudgetType.fromPreference(openedDb.fetchPreference("budgetType"))
        val fetchedUpcomingLength = openedDb.fetchUpcomingScheduledTransactionLength()
        val fetchedCreditCards = openedDb.fetchCreditCardConfigs()
        val fetchedLoans = openedDb.fetchLoanConfigs()
        val fetchedDeposits = openedDb.fetchDepositConfigs()
        val fetchedCardMappings = openedDb.fetchCardAccountMappings()
        val fetchedAccounts = openedDb.fetchAccounts()
        val fetchedTransactions = openedDb.fetchTransactions()
        val fetchedUncategorizedCount = openedDb.fetchUncategorizedCount()
        val fetchedGroups = openedDb.fetchCategoryGroups()
        val fetchedPayees = openedDb.fetchPayees()
        val fetchedTags = runCatching { openedDb.fetchTags(includeHidden = true) }.getOrDefault(emptyList())
        val currentMonth = currentMonthString()
        val displayedMonth = if (budgetMonthRequestGeneration == monthRequestGenerationBeforeLoad) {
            lastViewedBudgetMonth ?: currentMonth
        } else {
            requestedBudgetMonth ?: lastViewedBudgetMonth ?: currentMonth
        }
        val fetchedBudgetMonth = openedDb.fetchBudgetMonth(displayedMonth)
        val fetchedWidgetBudgetMonth = if (displayedMonth == currentMonth) fetchedBudgetMonth else openedDb.fetchBudgetMonth(currentMonth)
        val fetchedGoalTemplatesFlag = openedDb.fetchPreference("flags.goalTemplatesEnabled") == "true"
        val fetchedGoalTemplatesUIFlag = openedDb.fetchPreference("flags.goalTemplatesUIEnabled") == "true"

        // If a concurrent load replaced the database while we were fetching,
        // drop our stale snapshot. The winning load owns the spinner.
        if (database !== openedDb) return

        // The database stays authoritative whenever it has an answer, so a
        // currency changed on another client always wins here. The cache only
        // covers the gap: a fresh snapshot can predate the CRDT messages that
        // carry the setting (GH #297).
        isPublishingReload = true
        if (fetchedCurrencyCode != null) {
            currencyCode = fetchedCurrencyCode
            cacheCurrencyCode(fetchedCurrencyCode, budgetId)
        } else {
            cachedCurrencyCode(budgetId)?.let { currencyCode = it }
        }
        numberFormat = ActualNumberFormat.entries.firstOrNull { it.rawValue == fetchedNumberFormat } ?: ActualNumberFormat.CommaDot
        budgetType = fetchedBudgetType
        upcomingScheduledTransactionLength = fetchedUpcomingLength
        creditCardConfigs = fetchedCreditCards
        loanConfigs = fetchedLoans
        depositConfigs = fetchedDeposits
        cardAccountMappings = fetchedCardMappings
        accounts = fetchedAccounts
        // Let observers establish their baseline before the transaction
        // publication is visible as a user change.
        isBudgetLoaded = true
        transactions = fetchedTransactions
        uncategorizedCount = fetchedUncategorizedCount
        categoryGroups = fetchedGroups
        payees = fetchedPayees
        // A month selected while the reads were in flight is newer than this
        // snapshot; leave that request and its fetch result intact.
        if (budgetMonthRequestGeneration == monthRequestGenerationBeforeLoad) {
            requestedBudgetMonth = displayedMonth
            currentBudgetMonth = fetchedBudgetMonth
        }
        widgetBudgetMonth = fetchedWidgetBudgetMonth
        goalTemplatesEnabled = fetchedGoalTemplatesFlag
        goalTemplatesUIEnabled = fetchedGoalTemplatesUIFlag
        tags = fetchedTags
        dataVersion += 1
        isPublishingReload = false
        publishWidgetSnapshot()
        published = true
        scope.launch { refreshTagSummaries() }

        // Linked feeds drive the sync buttons in the accounts UI.
        loadBankSyncAccounts()

        // budgetId is the internal id (metadata.json's), but sync needs the
        // server's file id and group, from the local metadata.
        val metadata = runCatching { fileManager.metadataPath(budgetId).readBytes() }.getOrNull()?.let(BudgetMetadata::decode)
        val fileId = metadata?.cloudFileId ?: budgetId
        val groupId = metadata?.groupId ?: ""
        syncDetachedByRestore = metadata != null && metadata.cloudFileId != null && groupId.isEmpty()
        if (metadata == null) logger.notice("Could not load metadata for budget")

        if (syncDetachedByRestore) {
            // A restored backup: the server still has the old sync group, so
            // any sync with our nulled groupId earns a 400 file-has-reset and
            // an endless retry loop. Leave sync unconfigured until a
            // re-download writes a fresh groupId.
            syncStateJob?.cancel()
            syncStateJob = null
            syncClient = null
            syncStatus.state = SyncState.Idle
            logger.notice("Budget detached by restore - sync not configured")
        } else {
            val nodeId = defaults.string("nodeId") ?: HybridLogicalClock.generateNodeId().also { defaults.set("nodeId", it) }
            val client = SyncClient(serverClient, nodeId)
            syncClient = client
            val loadedKey = EncryptionKeyManager.load(fileId)
            client.configure(openedDb, fileId, groupId, loadedKey?.key, loadedKey?.keyId)
            logger.info("Sync configuration successful (encrypted: ${loadedKey != null})")
            subscribeToSyncState()
        }

        refreshPayeeLocationSupport()
        scheduleCreditCardDueNotifications()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // If a concurrent load replaced our database mid-fetch, this failure
        // belongs to a stale load — don't clobber the winner's error or spinner.
        if (db != null && database !== db) return
        if (!published) {
            syncStateJob?.cancel()
            syncStateJob = null
            syncClient = null
            requestedBudgetMonth = null
            currentBudgetMonth = null
            budgetType = BudgetType.Envelope
            widgetBudgetMonth = null
            accounts = emptyList()
            transactions = emptyList()
            uncategorizedCount = 0
            categoryGroups = emptyList()
            payees = emptyList()
            tags = emptyList()
            tagSummaries = emptyList()
            dataVersion += 1
            clearWidgetSnapshot()
        }
        error = L("Failed to load budget: %@", e.localizedMessage)
    }
    isLoading = false
}

/**
 * Seed [BudgetStore.payeeLocationWritesEnabled] from the last cached answer for
 * the configured server, then probe `GET /info` in the background. A failed
 * probe keeps the cached answer. Never blocks or fails budget load.
 */
internal fun BudgetStore.refreshPayeeLocationSupport() {
    val capturedURL = serverURL
    val key = "payeeLocationWritesEnabled_$capturedURL"
    payeeLocationWritesEnabled = defaults.bool(key)
    scope.launch {
        val version = serverClient.fetchServerVersion() ?: return@launch // capabilities unknown — keep the cached answer
        // The user may have switched servers while the probe was in flight.
        if (serverURL != capturedURL) return@launch
        val supported = ServerVersion.supportsPayeeLocations(version)
        payeeLocationWritesEnabled = supported
        defaults.set(key, supported)
    }
}

suspend fun BudgetStore.refreshData() {
    val budgetId = currentBudgetId ?: return
    loadLocalBudget(budgetId)
}

/**
 * Populate a local "demo" budget with curated data, for exploring the app
 * without a server. Logs out any active session so sync can't fire against a
 * real server — but keeps local budget files: trying the demo must never
 * destroy a user's synced data.
 */
suspend fun BudgetStore.loadDemoData(tracking: Boolean = false, seedUncategorized: Boolean = false, seedUnsupportedBankSync: Boolean = false) {
    logout(clearLocalData = false)
    try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DemoDataSeeder.seed(tracking, seedUncategorized, seedUnsupportedBankSync, fileManager = fileManager)
        }
        currentBudgetId = DemoDataSeeder.BUDGET_ID
        // Reseeding rebuilds the budget directory, but history persists in
        // preferences keyed by budget id. Clear it so a reseeded demo opens pristine.
        HistoryStore.shared.clearPersistedActions(DemoDataSeeder.BUDGET_ID)
        forgetExcludedFromSpent(DemoDataSeeder.BUDGET_ID)
        loadLocalBudget(DemoDataSeeder.BUDGET_ID)
        try {
            DemoDataSeeder.seedPendingImports()
        } catch (e: Exception) {
            logger.error("Demo pending import seed failed: ${e.message}")
        }
        // The seeder recreates the budget directory mid-launch, so a load
        // already running from init may have captured an I/O error. A
        // successful demo seed supersedes it.
        error = null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = L("Failed to seed demo data: %@", e.localizedMessage)
    }
}

/** Refresh just the data without recreating the sync client; used after local changes to update the UI. */
suspend fun BudgetStore.refreshDataOnly() {
    val database = database ?: return
    val budgetId = currentBudgetId
    val currencyCodeBefore = currencyCode
    val numberFormatBefore = numberFormat
    val budgetTypeBefore = budgetType
    val creditCardsBefore = creditCardConfigs
    val loansBefore = loanConfigs
    val depositsBefore = depositConfigs
    val cardMappingsBefore = cardAccountMappings
    try {
        // Fetch into locals, then publish in one batch so overlapping
        // refreshes can't leave the UI with a mixed snapshot.
        val fetchedAccounts = database.fetchAccounts()
        val fetchedTransactions = database.fetchTransactions()
        val fetchedUncategorizedCount = database.fetchUncategorizedCount()
        val fetchedGroups = database.fetchCategoryGroups()
        val fetchedPayees = database.fetchPayees()
        val fetchedTags = runCatching { database.fetchTags(includeHidden = true) }.getOrDefault(emptyList())
        val fetchedTagSummaries = runCatching { database.fetchTagSummaries() }.getOrDefault(emptyList())
        val currentMonth = currentMonthString()
        // `currentBudgetMonth` follows the month the Budget tab is browsing;
        // a foreground sync must not silently replace it with the current
        // calendar month (GH #328).
        val displayedMonth = requestedBudgetMonth ?: currentMonth
        val fetchedBudgetMonth = database.fetchBudgetMonth(displayedMonth)
        val fetchedWidgetBudgetMonth = if (displayedMonth == currentMonth) fetchedBudgetMonth else database.fetchBudgetMonth(currentMonth)
        // Re-read here as well as on load: a sync can bring in a changed
        // upcoming window, and the status badges are computed from it.
        val fetchedUpcomingLength = database.fetchUpcomingScheduledTransactionLength()
        val fetchedCreditCards = database.fetchCreditCardConfigs()
        val fetchedLoans = database.fetchLoanConfigs()
        val fetchedDeposits = database.fetchDepositConfigs()
        val fetchedCardMappings = database.fetchCardAccountMappings()
        // Re-read too: a sync can bring in a currency set on another client (GH #297).
        val fetchedCurrencyCode = database.fetchCurrencyCode()
        val fetchedNumberFormat = database.fetchPreference("numberFormat")
        val fetchedBudgetType = BudgetType.fromPreference(database.fetchPreference("budgetType"))
        // The web's Experimental toggles arrive as synced preferences.
        val fetchedGoalTemplatesFlag = database.fetchPreference("flags.goalTemplatesEnabled") == "true"
        val fetchedGoalTemplatesUIFlag = database.fetchPreference("flags.goalTemplatesUIEnabled") == "true"

        var fetchedScheduleError: String? = null
        val fetchedSchedules = try {
            fetchSchedules(database, fetchedUpcomingLength)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fetchedScheduleError = e.localizedMessage
            null
        }
        val duesConfigs = if (creditCardConfigs == creditCardsBefore) fetchedCreditCards else creditCardConfigs
        val fetchedDues = fetchCreditCardStatementDues(database, fetchedAccounts, duesConfigs)
        val fetchedBankAccounts = fetchBankSyncAccounts()

        // If the budget was switched while we were fetching, this snapshot
        // belongs to the old database — drop it.
        if (this.database !== database || currentBudgetId != budgetId) return

        isPublishingReload = true
        // A card saved while these reads were in flight is newer than this
        // snapshot; its write comes back on the next refresh.
        if (creditCardConfigs == creditCardsBefore) creditCardConfigs = fetchedCreditCards
        if (loanConfigs == loansBefore) loanConfigs = fetchedLoans
        if (depositConfigs == depositsBefore) depositConfigs = fetchedDeposits
        if (cardAccountMappings == cardMappingsBefore) cardAccountMappings = fetchedCardMappings

        accounts = fetchedAccounts
        transactions = fetchedTransactions
        uncategorizedCount = fetchedUncategorizedCount
        categoryGroups = fetchedGroups
        payees = fetchedPayees
        if (tags != fetchedTags) tags = fetchedTags
        tagSummaries = fetchedTagSummaries
        // A month selected while these reads were in flight owns the Budget
        // tab now; its fetch publishes separately.
        if (requestedBudgetMonth == displayedMonth) currentBudgetMonth = fetchedBudgetMonth
        widgetBudgetMonth = fetchedWidgetBudgetMonth
        upcomingScheduledTransactionLength = fetchedUpcomingLength
        goalTemplatesEnabled = fetchedGoalTemplatesFlag
        goalTemplatesUIEnabled = fetchedGoalTemplatesUIFlag
        // Keep a currency chosen while the reads were in flight.
        if (fetchedCurrencyCode != null && currencyCode == currencyCodeBefore) {
            currencyCode = fetchedCurrencyCode
            budgetId?.let { cacheCurrencyCode(fetchedCurrencyCode, it) }
        }
        if (fetchedNumberFormat != null && numberFormat == numberFormatBefore) {
            numberFormat = ActualNumberFormat.entries.firstOrNull { it.rawValue == fetchedNumberFormat } ?: ActualNumberFormat.CommaDot
        }
        publishSchedules(fetchedSchedules ?: Triple(emptyList(), emptyMap(), emptyMap()))
        schedulesLoaded = true
        scheduleLoadError = fetchedScheduleError
        if (creditCardConfigs == duesConfigs) creditCardStatementDues = fetchedDues
        fetchedBankAccounts?.let { bankSyncAccounts = it }
        if (budgetType == budgetTypeBefore) budgetType = fetchedBudgetType
        dataVersion += 1
        isPublishingReload = false
        publishWidgetSnapshot()
        scheduleCreditCardDueNotifications()
    } catch (e: CancellationException) {
        // The caller's coroutine was cancelled (a superseded refresh). Nothing
        // failed — never alarm the user.
    } catch (e: Exception) {
        // A failure for an old database mustn't surface over the new budget.
        if (this.database !== database) return
        error = L("Failed to refresh data: %@", e.localizedMessage)
    }
}

// Backup Actions

suspend fun BudgetStore.refreshBackups() {
    val budgetId = currentBudgetId ?: run {
        backups = emptyList()
        return
    }
    backups = backupService.availableBackups(budgetId)
}

suspend fun BudgetStore.makeBackupNow() {
    val budgetId = currentBudgetId ?: return
    try {
        backupService.makeBackup(budgetId, database)
        refreshBackups()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.localizedMessage
    }
}

/**
 * Automatic backup on app-background. Skipped while viewing a backup:
 * backgrounding happens seconds after a restore, and makeBackup's first step
 * would destroy the revert baseline.
 */
fun BudgetStore.backupOnBackground() {
    val budgetId = currentBudgetId ?: return
    val database = database ?: return
    if (fileManager.latestDatabasePath(budgetId).exists()) return
    val service = backupService
    scope.launch {
        runCatching { service.makeBackup(budgetId, database) }
        refreshBackups()
    }
}

suspend fun BudgetStore.restoreBackup(backupId: String) {
    val budgetId = currentBudgetId ?: return
    isLoading = true
    error = null

    // Drop the sync client and database references before loadBackup swaps
    // files; the baseline snapshot goes through the old queue's write
    // serialization. We deliberately don't flushPendingSync() here: that
    // awaits the network push and would hang this local restore when the
    // server is unreachable.
    val openDatabase = database
    val openClient = syncClient
    syncStateJob?.cancel()
    syncStateJob = null
    syncClient = null
    database = null
    openClient?.cancelPendingSync()

    try {
        backupService.loadBackup(budgetId, backupId, openDatabase)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error = e.localizedMessage
    }
    openDatabase?.close()

    // Reopen even after a failure: the live files are still (or again) a
    // valid budget, and the UI needs a database either way.
    loadLocalBudget(budgetId)
    refreshBackups()
    isLoading = false
}

/**
 * On-disk location of a stored backup archive, so the user can export it via
 * the share sheet and import it into Actual on the web or desktop — it is
 * already in Actual's import format (db.sqlite + metadata.json, CRDT state stripped).
 */
fun BudgetStore.backupFile(backupId: String): java.io.File? = currentBudgetId?.let { fileManager.backupPath(it, backupId) }

suspend fun BudgetStore.revertToLatest() = restoreBackup(Backup.Latest.id)

// Payees

/** Find an existing payee by name (case-insensitive) or create a new one. */
suspend fun BudgetStore.findOrCreatePayee(name: String): Payee {
    payees.firstOrNull { it.name.lowercase() == name.lowercase() }?.let { return it }
    val newPayee = Payee(UUID.randomUUID().toString(), name, null)
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    syncClient.createPayee(newPayee)
    // Add to the local list immediately (optimistic).
    payees = payees + newPayee
    return newPayee
}

// Tags

/** Creates a managed tag: validates the name, checks uniqueness, and writes it through sync. */
suspend fun BudgetStore.createTag(name: String, color: String? = null, description: String? = null): Tag {
    val normalized = Tag.normalizeTagName(name)
    if (!Tag.isValidTagName(normalized)) throw BudgetStoreError.InvalidTagName
    if (tags.any { it.tag.lowercase() == normalized.lowercase() }) throw BudgetStoreError.TagAlreadyExists
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    return try {
        syncClient.createTag(normalized, color, description).also { refreshDataOnly() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.TagCreationFailed(e.localizedMessage ?: e.toString())
    }
}

/** Updates metadata (color, description, hidden status) for an existing tag. */
suspend fun BudgetStore.updateTag(tag: Tag) = tagWrite { it.updateTag(tag) }

/** Soft-deletes a tag from the managed list without altering existing transaction notes. */
suspend fun BudgetStore.deleteTag(id: String) = tagWrite { it.deleteTag(id) }

/** Renames a tag and rewrites all occurrences of `#oldName` to `#newName` in transaction notes. */
suspend fun BudgetStore.renameTag(id: String, oldName: String, newName: String) {
    val normalizedNew = Tag.normalizeTagName(newName)
    if (!Tag.isValidTagName(normalizedNew)) throw BudgetStoreError.InvalidTagName
    if (tags.any { it.id != id && it.tag.lowercase() == normalizedNew.lowercase() }) throw BudgetStoreError.TagAlreadyExists
    tagWrite { it.renameTag(id, oldName, normalizedNew) }
}

private suspend fun BudgetStore.tagWrite(write: suspend (SyncClient) -> Unit) {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    try {
        write(syncClient)
        refreshDataOnly()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BudgetStoreError.TagUpdateFailed(e.localizedMessage ?: e.toString())
    }
}

/** Discovers `#tag` patterns used in transaction notes that aren't yet in the managed tags table. */
suspend fun BudgetStore.discoverTags(): List<Tag> {
    val syncClient = syncClient ?: throw BudgetStoreError.SyncNotConfigured
    val created = syncClient.importDiscoveredTags()
    if (created.isNotEmpty()) refreshDataOnly()
    return created
}

/** Refresh tag summaries without a full budget reload. */
suspend fun BudgetStore.refreshTagSummaries() {
    val database = database ?: return
    val fetched = runCatching { database.fetchTagSummaries() }.getOrDefault(emptyList())
    if (this.database === database) tagSummaries = fetched
}

/** Transactions carrying the given tag in their notes. */
suspend fun BudgetStore.fetchTransactions(taggedWith: String): List<Transaction> =
    runCatching { database?.fetchTransactions(taggedWith = taggedWith) }.getOrNull().orEmpty()

// Sync

/** Force an immediate sync (pull-to-refresh). */
suspend fun BudgetStore.sync() {
    // Pull-to-refresh runs this in a UI coroutine the framework can cancel;
    // run the pipeline in the store's scope so a UI-driven cancellation can't
    // abort a sync mid-flight.
    scope.launch {
        if (syncClient == null) logger.notice("syncClient is null, cannot sync!")
        syncClient?.syncNow()
        syncStatus.lastSyncTime = System.currentTimeMillis()
        refreshDataOnly()
        notifyAboutSyncedTransactions()
    }.join()
}

/** Discard the sync marker and re-sync; recovers a client stuck in a divergent state. */
suspend fun BudgetStore.resetSyncState() {
    logger.notice("resetSyncState() called from BudgetStore")
    syncClient?.resetSyncState()
    syncStatus.lastSyncTime = System.currentTimeMillis()
    refreshDataOnly()
}

/**
 * Wait for the background push a local write kicked off. Only callers that can
 * be suspended right after writing need this — interactive flows must never
 * block on the network (issue #125).
 */
suspend fun BudgetStore.flushPendingSync() {
    syncClient?.flushPendingSync()
}

/** Whether local writes are still waiting to reach the server; meaningful straight after [flushPendingSync]. */
suspend fun BudgetStore.hasPendingLocalWrites(): Boolean = syncClient?.hasPendingLocalWrites() ?: false

suspend fun BudgetStore.hasPendingLocalWrites(dataset: String, row: String): Boolean = syncClient?.hasPendingLocalWrites(dataset, row) ?: false

/** Sync when the app comes to the foreground — rate-limited, and only if a budget is loaded. */
suspend fun BudgetStore.syncOnForeground() {
    // After a failover, probe whether the primary recovered while we were
    // backgrounded. Fire-and-forget: the sync proceeds on whichever address
    // is currently active.
    scope.launch { serverClient.retryPrimaryIfRecovered() }
    val client = syncClient ?: run {
        logger.debug("syncOnForeground() skipped - no budget loaded")
        return
    }
    val success = client.automaticSync()
    syncStatus.lastSyncTime = System.currentTimeMillis()
    // Post due schedules between the sync and the data refresh so any posted
    // transactions appear in the same refresh. Only after a successful sync:
    // posting against stale data risks double-posting an occurrence another
    // client already covered.
    if (success) postDueSchedulesIfNeeded()
    refreshDataOnly()
    notifyAboutSyncedTransactions()
}

/**
 * Headless sync for background refresh. Ensures the saved budget is loaded
 * before syncing. Returns false when no budget is configured.
 */
suspend fun BudgetStore.syncInBackground(): Boolean {
    ensureBudgetReady()
    val client = syncClient ?: return false
    client.automaticSync()
    syncStatus.lastSyncTime = System.currentTimeMillis()
    refreshDataOnly()
    notifyAboutSyncedTransactions()
    return true
}

/** Single transaction by id (cache first, then database) for notification tap-through. */
suspend fun BudgetStore.transaction(id: String): Transaction? =
    transactions.firstOrNull { it.id == id } ?: runCatching { database?.fetchTransaction(id) }.getOrNull()

/**
 * Detect transactions that arrived via the sync just completed and post one
 * summary notification. Shared by the foreground and background paths so a
 * foreground sync posts the same notification a background refresh would.
 */
suspend fun BudgetStore.notifyAboutSyncedTransactions() {
    val fresh = detectNewTransactionsForNotification()
    NewTransactionNotifier.notify(
        fresh, currencyCode, useNarrowCurrencySymbol, numberFormat,
        accounts.associate { it.id to it.name }, offBudgetAccountIds,
    )
}

/**
 * Transactions that arrived via sync since the last check (advances the
 * notification watermark). Errors are logged, not thrown — a failed detection
 * must never take down the background refresh.
 */
suspend fun BudgetStore.detectNewTransactionsForNotification(): List<Transaction> {
    val database = database ?: return emptyList()
    val syncClient = syncClient ?: return emptyList()
    val budgetId = currentBudgetId ?: return emptyList()
    return try {
        NewTransactionDetector(defaults).detectNewTransactions(database, budgetId, syncClient.nodeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("New-transaction detection failed: ${e.message}")
        emptyList()
    }
}

// Scheduled Transaction Posting

/** The toast copy for a completed posting pass. */
fun schedulePostNoticeText(count: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    ReportStrings.localized("Posted %lld scheduled transactions", count.toLong(), locale = locale)

/**
 * Mirror sync state into the status object, and post due schedules whenever a
 * sync completes successfully (syncing → idle). loot-core runs its schedule
 * service on every sync completion, so posting must not depend on WHICH sync
 * succeeded (GH #97).
 */
internal fun BudgetStore.subscribeToSyncState() {
    syncStateJob?.cancel()
    val client = syncClient ?: return
    syncStateJob = scope.launch {
        client.state.collect { state ->
            if (syncStatus.state == state) return@collect
            val wasSyncing = syncStatus.state == SyncState.Syncing
            syncStatus.state = state
            if (wasSyncing && state == SyncState.Idle) scope.launch { postDueSchedulesAfterSync() }
        }
    }
}

/**
 * Posting for syncs that finish outside syncOnForeground() — that path
 * refreshes after posting itself; here nothing else would republish the
 * register, so refresh when anything posted.
 */
private suspend fun BudgetStore.postDueSchedulesAfterSync() {
    if (postDueSchedulesIfNeeded() > 0) refreshDataOnly()
}

internal suspend fun BudgetStore.postDueSchedulesIfNeeded(): Int {
    val client = syncClient ?: return 0
    val database = database ?: return 0
    val budgetId = currentBudgetId ?: return 0
    // Lazy-create with no suspension between the check and the cache write, so
    // two main-thread-interleaved calls still share one instance.
    val poster = schedulePoster ?: SchedulePoster(database, client, defaults).also { schedulePoster = it }
    val count = poster.runIfNeeded(budgetId)
    if (count <= 0) return 0
    schedulePostNotice = schedulePostNoticeText(count)
    scheduleNoticeDismissTask?.cancel()
    scheduleNoticeDismissTask = scope.launch {
        delay(4000)
        schedulePostNotice = null
    }
    return count
}

// Envelope summary

data class EnvelopeBudgetSummary(
    val availableFunds: Long,
    val lastMonthOverspent: Long,
    val budgeted: Long,
    val forNextMonth: Long,
    val toBudget: Long,
    val manualBuffered: Long,
    val autoBuffered: Long,
) {
    companion object {
        fun make(availableFunds: Long, lastMonthOverspent: Long, budgeted: Long, toBudget: Long, manualBuffered: Long): EnvelopeBudgetSummary {
            val forNextMonth = availableFunds + lastMonthOverspent - budgeted - toBudget
            val autoBuffered = if (manualBuffered == 0L) maxOf(forNextMonth, 0) else 0
            return EnvelopeBudgetSummary(availableFunds, lastMonthOverspent, budgeted, forNextMonth, toBudget, manualBuffered, autoBuffered)
        }

        fun isValidBudgetMonth(month: String): Boolean {
            val parts = month.split("-")
            if (parts.size != 2 || parts[0].length != 4 || parts[1].length != 2) return false
            val year = parts[0].toIntOrNull() ?: return false
            val monthNumber = parts[1].toIntOrNull() ?: return false
            return year > 0 && monthNumber in 1..12
        }
    }
}

/** Reads the summary values produced by the canonical budget walk. */
suspend fun BudgetStore.fetchEnvelopeBudgetSummary(month: String): EnvelopeBudgetSummary? {
    if (!EnvelopeBudgetSummary.isValidBudgetMonth(month)) return null
    val data = runCatching { databaseForLogger?.fetchEnvelopeBudgetSummary(month) }.getOrNull() ?: return null
    return EnvelopeBudgetSummary.make(data.availableFunds, data.lastMonthOverspent, data.budgeted, data.toBudget, data.buffered)
}

/** Off-budget account ids, for the paths that treat their transactions as outside the budget. */
val BudgetStore.offBudgetAccountIds: Set<String> get() = accounts.filter { it.offBudget }.mapTo(HashSet()) { it.id }

