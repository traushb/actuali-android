package io.github.traushb.actuali.views

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.views.accounts.AccountTransactionsScreen
import io.github.traushb.actuali.views.accounts.AddAccountView
import io.github.traushb.actuali.views.accounts.BankSyncSetupView
import io.github.traushb.actuali.views.accounts.CreateLocalAccountView
import io.github.traushb.actuali.views.accounts.PendingImportsView
import io.github.traushb.actuali.views.bills.BillsCalendarView
import io.github.traushb.actuali.views.rules.RulesListView
import io.github.traushb.actuali.views.schedules.DiscoverSchedulesView
import io.github.traushb.actuali.views.schedules.ScheduleEditView
import io.github.traushb.actuali.views.schedules.SchedulesListView
import io.github.traushb.actuali.views.settings.BackupListView
import io.github.traushb.actuali.views.settings.BudgetViewSettingsView
import io.github.traushb.actuali.views.settings.CategoryFundingAutomationView
import io.github.traushb.actuali.views.settings.ConnectionDataSettingsView
import io.github.traushb.actuali.views.settings.CreditCardsSettingsView
import io.github.traushb.actuali.views.settings.CustomHeadersEditor
import io.github.traushb.actuali.views.settings.DisplaySettingsView
import io.github.traushb.actuali.views.settings.CardAccountMappingsView
import io.github.traushb.actuali.views.settings.PayeeLocationDetailView
import io.github.traushb.actuali.views.settings.PayeeLocationsView
import io.github.traushb.actuali.views.settings.DiagnosticReportView
import io.github.traushb.actuali.views.settings.PrivacySettingsView
import io.github.traushb.actuali.views.settings.SupportView
import io.github.traushb.actuali.views.settings.TagsListView
import io.github.traushb.actuali.views.settings.TransactionAutomationSettingsView
import io.github.traushb.actuali.views.transactions.CategoryTransactionsView
import io.github.traushb.actuali.views.transactions.TagTransactionsView
import io.github.traushb.actuali.views.transactions.UncategorizedTransactionsView
import io.github.traushb.actuali.views.settings.DepositsSettingsView
import io.github.traushb.actuali.views.settings.LoansSettingsView

/**
 * A screen in a tab's navigation stack — SwiftUI's `navigationDestination`
 * values. Screens iOS pushes are routes; its sheets are full-screen sheets
 * over the current screen, which keeps its state underneath.
 */
sealed interface Route {
    // Tab roots.
    data object Budget : Route
    data object Accounts : Route
    data object AddTransaction : Route
    data object Reports : Route
    data object More : Route

    /** One account's transactions, or every account's when [accountId] is null. */
    data class AccountTransactions(val accountId: String?) : Route
    data object AddAccount : Route
    data object CreateLocalAccount : Route
    data object BankSyncSetup : Route
    data object PendingImports : Route
    data object CreditCardsSettings : Route
    data object LoansSettings : Route
    data object DepositsSettings : Route
    data object BillsCalendar : Route
    data object Schedules : Route

    /** A schedule to edit, or a new one when [schedule] is null. */
    data class ScheduleEditor(val schedule: ScheduleSummary?) : Route
    data object DiscoverSchedules : Route
    data object ConnectionData : Route
    data object CustomHeaders : Route
    data object Backups : Route
    data object BudgetViewSettings : Route
    data object DisplaySettings : Route
    data object PrivacySettings : Route
    data object CategoryFunding : Route
    data object Uncategorized : Route
    data object TransactionAutomationSettings : Route
    data object PayeeLocations : Route
    data class PayeeLocationDetail(val payeeId: String, val payeeName: String) : Route
    data object CardAccountMappings : Route
    data object Tags : Route
    data object Rules : Route
    data object History : Route
    data object Support : Route
    data object DiagnosticReport : Route
    data class TagTransactions(val tagId: String) : Route

    /** A category's transactions: one "yyyy-MM" [month], or all time when null (GH #56). */
    data class CategoryTransactions(val categoryId: String, val categoryName: String, val month: String?) : Route
}

/** One tab's navigation stack — SwiftUI's `NavigationStack(path:)`. */
class NavStack(root: Route) {
    val entries: SnapshotStateList<Route> = mutableStateListOf(root)

    fun push(route: Route) {
        entries.add(route)
    }

    fun pop() {
        if (entries.size > 1) entries.removeAt(entries.lastIndex)
    }

    /** Replace everything above the root with [routes] (a notification tap landing on a screen; back to the root with none). */
    fun reset(vararg routes: Route) {
        entries.subList(1, entries.size).clear()
        entries.addAll(routes)
    }
}

val LocalNavStack = staticCompositionLocalOf<NavStack> { error("No NavStack provided") }

/** How the current screen's [io.github.traushb.actuali.views.components.AppTopBar] reacts to its content scrolling. */
val LocalTopBarScrollBehavior = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

/** Whether the current screen's title is large — iOS's large titles, on a tab's root screen. */
val LocalLargeTopBar = staticCompositionLocalOf { false }

/**
 * A screen's top-bar scrolling: a large title collapses as the content
 * scrolls, a compact bar takes on its scrolled color. Every screen and sheet
 * provides one, so each screen's Scaffold stays plain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarScrolling(large: Boolean, content: @Composable () -> Unit) {
    val behavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.nestedScroll(behavior.nestedScrollConnection)) {
        CompositionLocalProvider(LocalTopBarScrollBehavior provides behavior, LocalLargeTopBar provides large, content = content)
    }
}

@Composable
fun TabNavigation(stack: NavStack, root: @Composable (Route) -> Unit) {
    CompositionLocalProvider(LocalNavStack provides stack) {
        NavDisplay(
            backStack = stack.entries,
            onBack = { stack.pop() },
            entryProvider = { route ->
                // The Add tab is a form, not a list: it keeps the compact bar iOS gives it.
                NavEntry(route) { TopBarScrolling(large = route == stack.entries.first() && route != Route.AddTransaction) { RouteContent(route, root) } }
            },
        )
    }
}

@Composable
private fun RouteContent(route: Route, root: @Composable (Route) -> Unit) {
    when (route) {
        Route.Budget, Route.Accounts, Route.AddTransaction, Route.Reports, Route.More -> root(route)
        is Route.AccountTransactions -> AccountTransactionsScreen(route.accountId)
        Route.AddAccount -> AddAccountView()
        Route.CreateLocalAccount -> CreateLocalAccountView()
        Route.BankSyncSetup -> BankSyncSetupView()
        Route.PendingImports -> PendingImportsView()
        Route.CreditCardsSettings -> CreditCardsSettingsView()
        Route.LoansSettings -> LoansSettingsView()
        Route.DepositsSettings -> DepositsSettingsView()
        Route.BillsCalendar -> BillsCalendarView()
        Route.Schedules -> SchedulesListView()
        is Route.ScheduleEditor -> ScheduleEditView(route.schedule)
        Route.DiscoverSchedules -> DiscoverSchedulesView()
        Route.ConnectionData -> ConnectionDataSettingsView()
        Route.CustomHeaders -> CustomHeadersEditor()
        Route.Backups -> BackupListView()
        Route.BudgetViewSettings -> BudgetViewSettingsView()
        Route.DisplaySettings -> DisplaySettingsView()
        Route.PrivacySettings -> PrivacySettingsView()
        Route.CategoryFunding -> CategoryFundingAutomationView()
        Route.Uncategorized -> UncategorizedTransactionsView()
        Route.TransactionAutomationSettings -> TransactionAutomationSettingsView()
        Route.PayeeLocations -> PayeeLocationsView()
        is Route.PayeeLocationDetail -> PayeeLocationDetailView(route.payeeId, route.payeeName)
        Route.CardAccountMappings -> CardAccountMappingsView()
        Route.Tags -> TagsListView()
        Route.Rules -> RulesListView()
        Route.History -> HistoryView()
        Route.Support -> SupportView()
        Route.DiagnosticReport -> DiagnosticReportView()
        is Route.TagTransactions -> TagTransactionsView(route.tagId)
        is Route.CategoryTransactions -> CategoryTransactionsView(route.categoryId, route.categoryName, route.month)
    }
}
