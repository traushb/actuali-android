package io.github.traushb.actuali.views.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Schedule
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.ReportBudgetData
import io.github.traushb.actuali.services.database.fetchBudgetDataForReports
import io.github.traushb.actuali.services.database.fetchCustomReportConfigs
import io.github.traushb.actuali.services.database.fetchDashboardPages
import io.github.traushb.actuali.services.database.fetchFirstDayOfWeekIdx
import io.github.traushb.actuali.services.database.fetchForecastSchedules
import io.github.traushb.actuali.services.database.fetchTrackingBudgetMonths
import io.github.traushb.actuali.services.database.fetchTransactionsForReports
import io.github.traushb.actuali.services.database.fetchWidgets
import io.github.traushb.actuali.services.reports.CustomReportConfig
import io.github.traushb.actuali.services.reports.DashboardPage
import io.github.traushb.actuali.services.reports.DashboardWidget
import io.github.traushb.actuali.services.reports.SpendingMeta
import io.github.traushb.actuali.services.reports.engines.BalanceForecastBudgetMonth
import io.github.traushb.actuali.services.reports.engines.BalanceForecastMeta
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.InitialSyncBanner
import io.github.traushb.actuali.views.components.RefreshBox
import io.github.traushb.actuali.views.components.rememberOpenBudgetName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

object ReportsTabLogic {
    /**
     * Which page to show: a still-live explicit selection wins, then the
     * dashboard configured in Settings (GH #223), otherwise the first live
     * page (the web's ReportsDashboardRouter redirects to dashboardPages[0]),
     * otherwise null so the pre-pages pageless fallback applies.
     */
    fun resolvePageId(selected: String?, configuredDefault: String? = null, pages: List<DashboardPage>): String? = when {
        selected != null && pages.any { it.id == selected } -> selected
        configuredDefault != null && pages.any { it.id == configuredDefault } -> configuredDefault
        else -> pages.firstOrNull()?.id
    }

    /** A rename keeps the computed cards; a different page or edited widget definitions start them over. */
    fun dashboardIdentityChanged(loadedWidgets: List<DashboardWidget>, fetchedWidgets: List<DashboardWidget>, loadedPageId: String?, fetchedPageId: String?): Boolean =
        loadedWidgets != fetchedWidgets || loadedPageId != fetchedPageId
}

object DashboardLogic {
    /**
     * Match WebUI spending-spreadsheet.ts default exclusions: drop
     * off-budget accounts and income categories before computing.
     */
    fun spendingScope(transactions: List<Transaction>, offBudget: Set<String>, income: Set<String>): List<Transaction> =
        transactions.filter { it.accountId !in offBudget && it.categoryId?.let { id -> id in income } != true }

    /**
     * Upstream's card falls back to the schedules source when the file
     * isn't a tracking budget (BalanceForecastCard.tsx budgetType check).
     */
    fun forecastMeta(meta: BalanceForecastMeta?, isTracking: Boolean): BalanceForecastMeta? =
        if (meta?.source == BalanceForecastMeta.Source.TrackingBudget && !isTracking) meta.copy(source = BalanceForecastMeta.Source.Schedules) else meta

    fun comparisonLabel(meta: SpendingMeta?, locale: Locale = Locale.getDefault()): String =
        // A null mode is single-month upstream (SpendingCard.tsx).
        when (meta?.mode ?: SpendingMeta.Mode.SingleMonth) {
            SpendingMeta.Mode.Budget -> ReportStrings.text("vs budget", locale)
            SpendingMeta.Mode.SingleMonth -> ReportStrings.format("vs %@", meta?.compareTo ?: "prior", locale = locale)
            SpendingMeta.Mode.Average -> ReportStrings.text("vs avg", locale)
        }
}

/**
 * The open budget's report dashboards, as arranged in the Actual web app,
 * with a picker between them. Widgets are computed here, from the synced
 * data; they're configured only on the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsTabView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val database = store.databaseForLogger
    var pages by remember { mutableStateOf(emptyList<DashboardPage>()) }
    var selectedPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var widgets by remember { mutableStateOf(emptyList<DashboardWidget>()) }
    // The page `widgets` came from, which isn't `selectedPageId`: that flips
    // the instant the picker is tapped, while the widgets arrive a fetch later.
    var loadedPageId by remember { mutableStateOf<String?>(null) }
    var dashboardGeneration by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var hasLoaded by remember { mutableStateOf(false) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    // Keyed to the open database so the load re-runs when the budget finishes
    // opening (launching straight onto this tab races the budget load) and
    // when the budget is switched; dashboard edits arrive through the flow.
    LaunchedEffect(database, loadGeneration) {
        if (database == null) {
            hasLoaded = true
            return@LaunchedEffect
        }
        database.dashboardChanges().collect {
            try {
                val fetchedPages = database.fetchDashboardPages()
                val pageId = ReportsTabLogic.resolvePageId(selectedPageId, store.defaultDashboardPageId, fetchedPages)
                val fetched = database.fetchWidgets(pageId)
                pages = fetchedPages
                selectedPageId = pageId
                if (ReportsTabLogic.dashboardIdentityChanged(widgets, fetched, loadedPageId, pageId)) dashboardGeneration++
                widgets = fetched
                loadedPageId = pageId
                loadError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.localizedMessage ?: e.toString()
            }
            hasLoaded = true
        }
    }

    // A selection outranks the configured default, so changing the setting
    // has to switch the dashboard itself or it appears to do nothing until
    // the next launch. Clearing it (null) re-resolves to the first page.
    val configuredDefault = store.defaultDashboardPageId
    var appliedDefault by rememberSaveable { mutableStateOf(configuredDefault) }
    LaunchedEffect(configuredDefault) {
        if (configuredDefault != appliedDefault) {
            appliedDefault = configuredDefault
            selectedPageId = configuredDefault
            loadGeneration++
        }
    }

    Scaffold(topBar = { AppTopBar(L("Reports"), subtitle = rememberOpenBudgetName()) }) { padding ->
        RefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    store.sync()
                    loadGeneration++
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                InitialSyncBanner(store.isInitialSyncing)
                when {
                    !hasLoaded -> DelayedLoadingIndicator(Modifier.fillMaxSize())
                    loadError != null -> EmptyState(L("Could not load reports"), Icons.Filled.Warning, loadError)
                    database == null -> EmptyState(L("No budget open"), Icons.Filled.BarChart, L("Open or sync a budget to see reports."))
                    // Recreate the cards when the page or widget definitions
                    // change, so their computed state belongs to that dashboard.
                    else -> key(dashboardGeneration) {
                        DashboardView(widgets) {
                            DashboardPicker(pages, selectedPageId) {
                                selectedPageId = it
                                loadGeneration++
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-width menu naming the dashboard on screen (the web app's sidebar).
 * Shown whatever the page count: with one page it still labels what's on
 * screen, and it's disabled only for pre-pages budgets, which have none.
 */
@Composable
private fun DashboardPicker(pages: List<DashboardPage>, selectedPageId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun displayName(page: DashboardPage) = page.name.ifEmpty { ReportStrings.text("Untitled") }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = pages.isNotEmpty(), onClickLabel = L("Switch dashboard"), role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .testTag("reports.dashboardPicker"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Dashboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                pages.firstOrNull { it.id == selectedPageId }?.let(::displayName) ?: ReportStrings.text("Dashboard"),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pages.isNotEmpty()) Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (page in pages) {
                DropdownMenuItem(
                    text = { Text(displayName(page)) },
                    onClick = {
                        expanded = false
                        onSelect(page.id)
                    },
                    trailingIcon = { if (page.id == selectedPageId) Icon(Icons.Filled.Check, contentDescription = null) },
                    modifier = Modifier.testTag("reports.dashboard.${page.id}"),
                )
            }
        }
    }
}

/**
 * What every widget computes from, fetched once per load and shared by the
 * cards. A plain class on purpose: cards recompute when a new load replaces
 * it, and identity is that signal — comparing the transaction lists would
 * cost a full scan per recomposition.
 */
class ReportInputs(
    val transactions: List<Transaction>,
    val customReportConfigs: Map<String, CustomReportConfig>,
    val firstDayOfWeekIdx: Int,
    val budgets: ReportBudgetData,
    val trackingBudgetMonths: List<BalanceForecastBudgetMonth>,
    val forecastSchedules: List<Schedule>,
    val today: LocalDate,
) {
    companion object {
        val empty get() = ReportInputs(emptyList(), emptyMap(), 0, ReportBudgetData(), emptyList(), emptyList(), LocalDate.now())

        /** Budget and schedule inputs are fetched only when a widget on the dashboard reads them. */
        suspend fun load(database: BudgetDatabase, widgets: List<DashboardWidget>): ReportInputs {
            val today = LocalDate.now()
            val configs = database.fetchCustomReportConfigs(widgets.mapNotNull { (it as? DashboardWidget.CustomReport)?.meta?.id })
            val needsBudgets = widgets.any { widget ->
                when (widget) {
                    is DashboardWidget.BudgetAnalysis, is DashboardWidget.Sankey, is DashboardWidget.BalanceForecast -> true
                    is DashboardWidget.Spending -> widget.meta?.mode == SpendingMeta.Mode.Budget
                    // Budgeted custom reports read budget cells instead of transactions.
                    is DashboardWidget.CustomReport -> widget.meta?.id?.let { configs[it] }?.balanceType == "Budgeted"
                    else -> false
                }
            }
            val needsForecast = widgets.any { it is DashboardWidget.BalanceForecast }
            return ReportInputs(
                transactions = database.fetchTransactionsForReports(),
                customReportConfigs = configs,
                firstDayOfWeekIdx = database.fetchFirstDayOfWeekIdx(),
                budgets = if (needsBudgets) database.fetchBudgetDataForReports() else ReportBudgetData(),
                trackingBudgetMonths = if (needsForecast) database.fetchTrackingBudgetMonths() else emptyList(),
                forecastSchedules = if (needsForecast) database.fetchForecastSchedules() else emptyList(),
                today = today,
            )
        }
    }
}

/**
 * The last dashboard load and what each card computed from it, kept across
 * visits: leaving the tab disposes it, and coming back otherwise refetched
 * every transaction and recomputed every card when nothing had changed.
 * Keyed on everything a load depends on, so a data change, another budget,
 * edited widgets, a new day or another language still load afresh.
 * Main-thread only.
 *
 * ponytail: holds the last load's transactions after the tab is left. The
 * ceiling is memory on very large budgets; the upgrade path is dropping it
 * from onTrimMemory.
 */
internal object ReportsCache {
    private var key: List<Any?>? = null
    private var inputs: ReportInputs? = null
    private val values = HashMap<String, Any>()

    fun inputsFor(key: List<Any?>): ReportInputs? = inputs.takeIf { this.key == key }

    fun store(key: List<Any?>, loaded: ReportInputs) {
        this.key = key
        inputs = loaded
        values.clear()
    }

    /** A card's result computed from [from], if it's the cached load. */
    fun valueFor(from: ReportInputs, widgetId: String): Any? = values[widgetId].takeIf { from === inputs }

    fun storeValue(from: ReportInputs, widgetId: String, value: Any) {
        if (from === inputs) values[widgetId] = value
    }
}

/**
 * The page's cards, two-up or more where the width allows. Unsupported
 * widgets never render as cards; one notice above them says only some
 * report types are available.
 */
@Composable
private fun DashboardView(widgets: List<DashboardWidget>, picker: @Composable () -> Unit) {
    val store = LocalBudgetStore.current
    val database = store.databaseForLogger
    val cacheKey = listOf(database, store.dataVersion, widgets, LocalDate.now(), Locale.getDefault())
    var inputs by remember { mutableStateOf(ReportsCache.inputsFor(cacheKey)) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Keyed to dataVersion so cards recompute when transactions change
    // anywhere in the app (edits on other tabs, sync, scheduled posts).
    LaunchedEffect(database, store.dataVersion) {
        ReportsCache.inputsFor(cacheKey)?.let {
            inputs = it
            loadError = null
            return@LaunchedEffect
        }
        try {
            val loaded = if (database == null) ReportInputs.empty else ReportInputs.load(database, widgets)
            ReportsCache.store(cacheKey, loaded)
            inputs = loaded
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.localizedMessage ?: e.toString()
        }
    }

    val visible = widgets.filter { it !is DashboardWidget.Unsupported }
    val error = loadError
    if (widgets.isEmpty() || error != null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { picker() }
            if (error != null) {
                EmptyState(L("Could not load reports"), Icons.Filled.Warning, error)
            } else {
                EmptyState(L("No widgets"), Icons.Filled.BarChart, L("Configure your dashboard in the Actual Budget webapp; it will sync here."))
            }
        }
        return
    }
    // Adaptive rather than a fixed column count: a card never gets squeezed
    // below phone width, so phones stay one column and tablets tile.
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(320.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = Modifier.fillMaxSize().testTag("reports.dashboard"),
    ) {
        item(key = "picker", span = StaggeredGridItemSpan.FullLine) { picker() }
        // Full width above the cards: it describes the whole dashboard.
        if (visible.size < widgets.size) item(key = "unsupported", span = StaggeredGridItemSpan.FullLine) { UnsupportedTypesNotice() }
        items(visible, key = { it.id }) { WidgetView(it, inputs) }
    }
}
