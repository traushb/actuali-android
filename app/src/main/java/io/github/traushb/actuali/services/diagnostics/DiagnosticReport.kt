package io.github.traushb.actuali.services.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import androidx.core.content.pm.PackageInfoCompat
import io.github.traushb.actuali.BuildConfig
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.CategoryFundingAutomation
import io.github.traushb.actuali.services.CategoryFundingSource
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.notifications.CreditCardNotificationSettings
import io.github.traushb.actuali.services.notifications.TransactionNotificationSettings
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The app settings a support report states — preferences and counts, never names, ids or amounts. */
data class DiagnosticSettingsSnapshot(
    val appearanceMode: String,
    val startTab: String,
    val defaultDashboardConfigured: Boolean,
    val currencyCode: String,
    val numberFormat: String,
    val useNarrowCurrencySymbol: Boolean,
    val budgetDisplayStyle: String,
    val showCompactBudgetOverview: Boolean,
    val showBudgetedAmounts: Boolean,
    val showCompactSpentColumn: Boolean,
    val showGroupTotals: Boolean,
    val showBudgetCheckInStrip: Boolean,
    val hideZeroBudgetCategories: Boolean,
    val showHiddenCategories: Boolean,
    val showCategoryStatusDots: Boolean,
    val customCategoryStatusColorCount: Int,
    val showBudgetProgressBars: Boolean,
    val showInverseBudgetProgressBars: Boolean,
    val hiddenBudgetProgressCategoryCount: Int,
    val spentCategoryExclusionCount: Int,
    val showOverspentBadge: Boolean,
    val hideIncomeGroup: Boolean,
    val goalTemplatesEnabled: Boolean,
    val goalTemplatesUIEnabled: Boolean,
    val transactionDisplayMode: String,
    val transactionStatusFilter: String,
    val showTransactionStatusFilters: Boolean,
    val uncategorizedTapAction: String,
    val conventionalAmountEntry: Boolean,
    val defaultAccountConfigured: Boolean,
    val recordPayeeLocations: Boolean,
    val payeeLocationWritesSupported: Boolean,
    val hideBalances: Boolean,
    val shakeToHideBalances: Boolean,
    val hideDecimalPlaces: Boolean,
    val hideClosedAccounts: Boolean,
    val transactionNotificationsEnabled: Boolean,
    val creditCardDueNotificationsEnabled: Boolean,
    val cardAccountMappingCount: Int,
    val creditCardConfigurationCount: Int,
    val loanConfigurationCount: Int,
    val depositConfigurationCount: Int,
    val categoryFundingConfigured: Boolean,
    val categoryFundingEnabled: Boolean,
    val categoryFundingSource: String,
) {
    companion object {
        fun capture(store: BudgetStore, defaults: Defaults = Defaults.standard): DiagnosticSettingsSnapshot {
            val funding = CategoryFundingAutomation.loadConfiguration(store.currentBudgetId, defaults)
            val visibleCategoryIds = store.categoryGroups.flatMap { it.categories }.mapTo(HashSet()) { it.id }
            return DiagnosticSettingsSnapshot(
                appearanceMode = store.appearanceMode.rawValue,
                startTab = store.startTab.rawValue,
                defaultDashboardConfigured = store.defaultDashboardPageId != null,
                currencyCode = store.currencyCode.ifEmpty { "none" },
                numberFormat = store.numberFormat.rawValue,
                useNarrowCurrencySymbol = store.useNarrowCurrencySymbol,
                budgetDisplayStyle = store.budgetDisplayStyle.rawValue,
                showCompactBudgetOverview = store.showCompactBudgetOverview,
                showBudgetedAmounts = store.showBudgetedAmounts,
                showCompactSpentColumn = store.showCompactSpentColumn,
                showGroupTotals = store.showGroupTotals,
                showBudgetCheckInStrip = store.showBudgetCheckInStrip,
                hideZeroBudgetCategories = store.hideZeroBudgetCategories,
                showHiddenCategories = store.showHiddenCategories,
                showCategoryStatusDots = store.showCategoryStatusDots,
                customCategoryStatusColorCount = CategoryProgressState.entries.count(store::hasCustomCategoryStatusDotColor),
                showBudgetProgressBars = store.showBudgetProgressBars,
                showInverseBudgetProgressBars = store.showInverseBudgetProgressBars,
                hiddenBudgetProgressCategoryCount = store.hiddenBudgetProgressCategoryIDs.count { it in visibleCategoryIds },
                spentCategoryExclusionCount = store.excludedFromSpentCategoryIds.size,
                showOverspentBadge = store.showOverspentBadge,
                hideIncomeGroup = store.hideIncomeGroup,
                goalTemplatesEnabled = store.goalTemplatesEnabled,
                goalTemplatesUIEnabled = store.goalTemplatesUIEnabled,
                transactionDisplayMode = store.transactionDisplayMode.rawValue,
                transactionStatusFilter = store.transactionStatusFilter.rawValue,
                showTransactionStatusFilters = store.showTransactionStatusFilters,
                uncategorizedTapAction = store.uncategorizedTapAction.rawValue,
                conventionalAmountEntry = store.conventionalAmountEntry,
                defaultAccountConfigured = store.defaultAccountId != null,
                recordPayeeLocations = store.recordPayeeLocations,
                payeeLocationWritesSupported = store.payeeLocationWritesEnabled,
                hideBalances = store.hideBalances,
                shakeToHideBalances = store.shakeToHideBalances,
                hideDecimalPlaces = store.hideDecimalPlaces,
                hideClosedAccounts = store.hideClosedAccounts,
                transactionNotificationsEnabled = TransactionNotificationSettings(defaults).isEnabled,
                creditCardDueNotificationsEnabled = CreditCardNotificationSettings(defaults).isEnabled,
                cardAccountMappingCount = store.cardAccountMappings.size,
                creditCardConfigurationCount = store.creditCardConfigs.size,
                loanConfigurationCount = store.loanConfigs.size,
                depositConfigurationCount = store.depositConfigs.size,
                categoryFundingConfigured = funding != null,
                categoryFundingEnabled = funding?.isEnabled ?: false,
                categoryFundingSource = when (funding?.fundingSource) {
                    null -> "none"
                    CategoryFundingSource.ToBudget -> "to-budget"
                    is CategoryFundingSource.Category -> "category"
                },
            )
        }
    }
}

/** The device and build facts a support report states. */
data class DiagnosticReportEnvironment(
    val version: String,
    val build: String,
    val bundle: String,
    val buildConfiguration: String,
    val hardwareModel: String,
    val operatingSystem: String,
    val processorCount: Int,
    val physicalMemoryBytes: Long,
    val availableStorageBytes: Long?,
    val batterySaver: Boolean,
    val thermalStatus: String,
    val userUnlocked: Boolean,
    val uptimeSeconds: Long,
) {
    companion object {
        fun capture(context: Context): DiagnosticReportEnvironment {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return DiagnosticReportEnvironment(
                version = info?.versionName ?: "Unknown",
                build = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "Unknown",
                bundle = context.packageName,
                buildConfiguration = if (BuildConfig.DEBUG) "Debug" else "Release",
                hardwareModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                operatingSystem = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                processorCount = Runtime.getRuntime().availableProcessors(),
                physicalMemoryBytes = memory.totalMem,
                availableStorageBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
                batterySaver = power.isPowerSaveMode,
                thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalStatus(power.currentThermalStatus) else "unknown",
                userUnlocked = (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked,
                uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
            )
        }

        private fun thermalStatus(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}

data class DiagnosticReport(val text: String, val filename: String)

/**
 * The support report. Field names stay in English so support tooling and
 * copied reports read the same everywhere; only the privacy line is
 * localized, for the person sending it.
 */
object DiagnosticReportBuilder {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val filenameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun make(snapshot: DiagnosticLogSnapshot, settings: DiagnosticSettingsSnapshot, environment: DiagnosticReportEnvironment, generatedAt: Long = System.currentTimeMillis()): DiagnosticReport {
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        val clean = settings.budgetDisplayStyle == "clean"
        val inactiveInClean = if (clean) " (inactive in Clean view)" else ""
        val lines = mutableListOf(
            "Actuali Diagnostic Report",
            "Generated: ${timestamp(generatedAt)}",
            L("Privacy: This report excludes credentials, custom header values, account and budget identifiers, server addresses, budget contents, transaction details, and financial amounts."),
            "",
            "[Application]",
            "Version: ${environment.version} (${environment.build})",
            "Bundle: ${environment.bundle}",
            "Build configuration: ${environment.buildConfiguration}",
            "Process uptime seconds: ${environment.uptimeSeconds}",
            "",
            "[Device]",
            "Hardware model: ${environment.hardwareModel}",
            "Operating system: ${environment.operatingSystem}",
            "Processor count: ${environment.processorCount}",
            "Physical memory bytes: ${environment.physicalMemoryBytes}",
            "Battery saver: ${yesNo(environment.batterySaver)}",
            "Thermal status: ${environment.thermalStatus}",
            "User unlocked: ${yesNo(environment.userUnlocked)}",
            "Available storage bytes: ${environment.availableStorageBytes ?: "Unknown"}",
            "",
            "[Settings]",
            "Appearance: ${settings.appearanceMode}",
            "Start page: ${settings.startTab}",
            "Default dashboard configured: ${yesNo(settings.defaultDashboardConfigured)}",
            "Currency: ${settings.currencyCode}",
            "Number format: ${settings.numberFormat}",
            "Narrow currency symbol: ${yesNo(settings.useNarrowCurrencySymbol)}",
            "",
            "Budget view style: ${settings.budgetDisplayStyle}",
            "Show compact budget overview: ${yesNo(settings.showCompactBudgetOverview)}$inactiveInClean",
            "Show spent: ${yesNo(settings.showCompactSpentColumn)}$inactiveInClean",
            "Budgeted amounts: ${yesNo(settings.showBudgetedAmounts)}",
            "Group totals: ${yesNo(settings.showGroupTotals)}$inactiveInClean",
            "Budget status filters: ${yesNo(settings.showBudgetCheckInStrip)}",
            "Hide spent categories: ${yesNo(settings.hideZeroBudgetCategories)}",
            "Show hidden categories: ${yesNo(settings.showHiddenCategories)}",
            "Category status dots: ${yesNo(settings.showCategoryStatusDots)}",
            "Custom category status colors: ${settings.customCategoryStatusColorCount}",
            "Budget progress bars: ${yesNo(settings.showBudgetProgressBars)}",
            "Inverse progress bars: ${yesNo(settings.showInverseBudgetProgressBars)}${if (!settings.showBudgetProgressBars) " (inactive; progress bars disabled)" else ""}",
            "Categories with hidden progress bars: ${settings.hiddenBudgetProgressCategoryCount}",
            "Categories excluded from spent: ${settings.spentCategoryExclusionCount}",
            "Overspent badge: ${yesNo(settings.showOverspentBadge)}",
            "Hide income group: ${yesNo(settings.hideIncomeGroup)}",
            "Budget goal templates: ${yesNo(settings.goalTemplatesEnabled)}",
            "Automations editor: ${yesNo(settings.goalTemplatesUIEnabled)}${if (!settings.goalTemplatesEnabled) " (inactive; goal templates disabled)" else ""}",
            "",
            "Transaction display: ${settings.transactionDisplayMode}",
            "Transaction status filter: ${settings.transactionStatusFilter}",
            "Transaction status filters: ${yesNo(settings.showTransactionStatusFilters)}",
            "Uncategorized action: ${settings.uncategorizedTapAction}",
            "Conventional amount entry: ${yesNo(settings.conventionalAmountEntry)}",
            "Default account configured: ${yesNo(settings.defaultAccountConfigured)}",
            "Record payee locations: ${yesNo(settings.recordPayeeLocations)}",
            "Payee location writes supported: ${yesNo(settings.payeeLocationWritesSupported)}",
            "",
            "Hide balances: ${yesNo(settings.hideBalances)}",
            "Shake to toggle balances: ${yesNo(settings.shakeToHideBalances)}",
            "Hide decimal places: ${yesNo(settings.hideDecimalPlaces)}",
            "Hide closed accounts: ${yesNo(settings.hideClosedAccounts)}",
            "",
            "New transaction alerts: ${yesNo(settings.transactionNotificationsEnabled)}",
            "Credit card due reminders: ${yesNo(settings.creditCardDueNotificationsEnabled)}",
            "",
            "Card & account mappings: ${settings.cardAccountMappingCount}",
            "Credit card configurations: ${settings.creditCardConfigurationCount}",
            "Loan configurations: ${settings.loanConfigurationCount}",
            "Deposit configurations: ${settings.depositConfigurationCount}",
            "Category funding configured: ${yesNo(settings.categoryFundingConfigured)}",
            "Category funding enabled: ${yesNo(settings.categoryFundingEnabled)}",
            "Category funding source: ${settings.categoryFundingSource}",
            "",
            "[Connection]",
            "Server configured: ${yesNo(snapshot.serverConfigured)}",
            "Server transport: ${snapshot.serverTransport}",
            "Fallback configured: ${yesNo(snapshot.fallbackConfigured)}",
            "Current server route: ${snapshot.serverRoute}",
            "Custom headers configured: ${yesNo(snapshot.customHeadersConfigured)}",
            "Connection status: ${snapshot.connectionStatus.rawValue}",
            "Credential present: ${yesNo(snapshot.credentialPresent)}",
            "Last HTTP status: ${snapshot.lastNetworkStatusCode ?: "none"}",
            "Last network error: ${snapshot.lastNetworkError?.diagnosticDescription ?: "none"}",
            "",
            "[Sync Status]",
            "Last sync attempt: ${timestamp(snapshot.lastSyncAttemptAt)}",
            "Last sync success: ${timestamp(snapshot.lastSyncSuccessAt)}",
            "Last sync result: ${snapshot.lastSyncResult?.rawValue ?: "none"}",
            "Last sync duration: ${snapshot.lastSyncDurationMilliseconds?.let { "$it ms" } ?: "none"}",
            "Last sync request messages: ${snapshot.lastSyncRequestMessageCount ?: "none"}",
            "Last sync response messages: ${snapshot.lastSyncResponseMessageCount ?: "none"}",
            "",
            "[Diagnostic Event History]",
            "Scope: app session; events may include earlier budget or connection configurations.",
            "Retained events: ${snapshot.entries.size}",
        )
        if (snapshot.entries.isEmpty()) {
            lines += L("No diagnostic events recorded.")
        } else {
            snapshot.entries.forEachIndexed { index, entry -> lines += "${index + 1}. ${timestamp(entry.date)} | ${entry.message}" }
        }
        lines += listOf("", "End of report", "")
        return DiagnosticReport(lines.joinToString("\n"), "Actuali-Diagnostics-${filenameFormatter.format(Instant.ofEpochMilli(generatedAt))}.txt")
    }

    private fun timestamp(millis: Long?): String = millis?.let { timestampFormatter.format(Instant.ofEpochMilli(it)) } ?: "never"
}
