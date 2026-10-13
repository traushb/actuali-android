package io.github.traushb.actuali.services.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val environment = DiagnosticReportEnvironment(
        version = "1.0", build = "1", bundle = "io.github.traushb.actuali", buildConfiguration = "Debug", hardwareModel = "Google Pixel",
        operatingSystem = "Android 16 (API 36)", processorCount = 8, physicalMemoryBytes = 8_000_000_000, availableStorageBytes = null,
        batterySaver = false, thermalStatus = "none", userUnlocked = true, uptimeSeconds = 42,
    )

    private fun settings(on: Boolean) = DiagnosticSettingsSnapshot(
        appearanceMode = "dark", startTab = "reports", defaultDashboardConfigured = on, currencyCode = "CAD", numberFormat = "dot-comma",
        useNarrowCurrencySymbol = on, budgetDisplayStyle = "compact", showCompactBudgetOverview = on, showBudgetedAmounts = !on,
        showCompactSpentColumn = on, showGroupTotals = on, showBudgetCheckInStrip = !on, hideZeroBudgetCategories = on,
        showHiddenCategories = on, showCategoryStatusDots = !on, customCategoryStatusColorCount = 3, showBudgetProgressBars = on,
        showInverseBudgetProgressBars = on, hiddenBudgetProgressCategoryCount = 2, spentCategoryExclusionCount = 2,
        showOverspentBadge = !on, hideIncomeGroup = on, goalTemplatesEnabled = on, goalTemplatesUIEnabled = on,
        transactionDisplayMode = "groupedByDate", transactionStatusFilter = "cleared", showTransactionStatusFilters = !on,
        uncategorizedTapAction = "transactionEditor", conventionalAmountEntry = on, defaultAccountConfigured = on,
        recordPayeeLocations = on, payeeLocationWritesSupported = on, hideBalances = on, shakeToHideBalances = on,
        hideDecimalPlaces = on, hideClosedAccounts = on, transactionNotificationsEnabled = on, creditCardDueNotificationsEnabled = on,
        cardAccountMappingCount = 2, creditCardConfigurationCount = 3, loanConfigurationCount = 4, depositConfigurationCount = 5,
        categoryFundingConfigured = on, categoryFundingEnabled = on, categoryFundingSource = "category",
    )

    @Test
    fun keepsOnlyTheMostRecentEntries() {
        val log = DiagnosticLog(capacity = 2)
        log.recordSyncStarted()
        log.recordSyncResponseMessageCount(1)
        log.recordSyncFinished(DiagnosticLog.SyncResult.Success)
        val entries = log.snapshot().entries
        assertEquals(2, entries.size)
        assertEquals("SYNC response-messages=1", entries[0].message)
        assertTrue(entries[1].message.startsWith("SYNC finished result=success"))
    }

    @Test
    fun reportContainsEnvironmentAndPrivacySections() {
        val report = DiagnosticReportBuilder.make(DiagnosticLogSnapshot.empty, settings(true), environment, generatedAt = 1_700_000_000_000)
        for (expected in listOf(
            "Actuali Diagnostic Report", "Generated: 2023-11-14T22:13:20.000Z", "[Application]", "[Device]", "[Connection]",
            "Custom headers configured: no", "[Sync Status]", "[Diagnostic Event History]",
            "Scope: app session; events may include earlier budget or connection configurations.", "credentials",
        )) assertTrue(expected, report.text.contains(expected))
        for (absent in listOf("Report ID:", "Background refresh:", "Locale:", "Preferred language:", "Time zone offset seconds:")) assertFalse(absent, report.text.contains(absent))
        assertEquals("Actuali-Diagnostics-20231114-221320Z.txt", report.filename)
    }

    @Test
    fun reportRendersEverySettingsField() {
        val text = DiagnosticReportBuilder.make(DiagnosticLogSnapshot.empty, settings(true), environment, generatedAt = 1_700_000_000_000).text
        for (expected in listOf(
            "Appearance: dark", "Start page: reports", "Default dashboard configured: yes", "Currency: CAD", "Number format: dot-comma",
            "Narrow currency symbol: yes", "Budget view style: compact", "Show compact budget overview: yes", "Show spent: yes",
            "Budgeted amounts: no", "Group totals: yes", "Budget status filters: no", "Hide spent categories: yes",
            "Show hidden categories: yes", "Category status dots: no", "Custom category status colors: 3", "Budget progress bars: yes",
            "Inverse progress bars: yes", "Categories with hidden progress bars: 2", "Categories excluded from spent: 2",
            "Overspent badge: no", "Hide income group: yes", "Budget goal templates: yes", "Automations editor: yes",
            "Transaction display: groupedByDate", "Transaction status filter: cleared", "Transaction status filters: no",
            "Uncategorized action: transactionEditor", "Conventional amount entry: yes", "Default account configured: yes",
            "Record payee locations: yes", "Payee location writes supported: yes", "Hide balances: yes", "Shake to toggle balances: yes",
            "Hide decimal places: yes", "Hide closed accounts: yes", "New transaction alerts: yes", "Credit card due reminders: yes",
            "Card & account mappings: 2", "Credit card configurations: 3", "Loan configurations: 4", "Deposit configurations: 5",
            "Category funding configured: yes", "Category funding enabled: yes", "Category funding source: category",
        )) assertTrue(expected, text.contains(expected))
    }

    /** Settings that do nothing in the current configuration say so, so a report isn't misread. */
    @Test
    fun inactiveSettingsAreMarked() {
        val text = DiagnosticReportBuilder.make(
            DiagnosticLogSnapshot.empty,
            settings(true).copy(budgetDisplayStyle = "clean", showBudgetProgressBars = false, goalTemplatesEnabled = false),
            environment,
        ).text
        assertTrue(text.contains("Show spent: yes (inactive in Clean view)"))
        assertTrue(text.contains("Inverse progress bars: yes (inactive; progress bars disabled)"))
        assertTrue(text.contains("Automations editor: yes (inactive; goal templates disabled)"))
    }
}
