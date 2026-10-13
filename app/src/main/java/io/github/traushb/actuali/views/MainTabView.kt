package io.github.traushb.actuali.views

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.CategoryFundingAutomation
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.notifications.NotificationDestination
import io.github.traushb.actuali.services.notifications.NotificationRouter
import io.github.traushb.actuali.views.accounts.AccountsListView
import io.github.traushb.actuali.views.budget.BudgetView
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.KeyboardAccessoryHost
import io.github.traushb.actuali.views.components.ToastView
import io.github.traushb.actuali.views.components.TransactionImpactPopup
import io.github.traushb.actuali.views.reports.ReportsTabView
import io.github.traushb.actuali.views.settings.SettingsView
import io.github.traushb.actuali.views.transactions.AddTransactionView
import io.github.traushb.actuali.views.transactions.EditTransactionView
import io.github.traushb.actuali.views.transactions.UncategorizedTransactionsView
import java.util.Locale
import kotlin.math.sqrt

/**
 * The app root: the tab bar, the store-wide error alert, the screens a
 * tapped notification opens, and the shake-to-hide-balances gesture.
 */
@Composable
fun ContentView() {
    val store = LocalBudgetStore.current
    val haptics = LocalHapticFeedback.current
    ShakeDetector(store.shakeToHideBalances, store::handleDeviceShake)
    // Feedback for each shake that flips the setting — not for the trigger's first value.
    var lastTrigger by remember { mutableStateOf(store.shakeFeedbackTrigger) }
    LaunchedEffect(store.shakeFeedbackTrigger) {
        if (store.shakeFeedbackTrigger == lastTrigger) return@LaunchedEffect
        lastTrigger = store.shakeFeedbackTrigger
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    KeyboardAccessoryHost {
        MainTabView()
        ToastView(store.schedulePostNotice, Modifier.align(Alignment.TopCenter))
        // Floats above the tab bar.
        TransactionImpactPopup(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    store.error?.let { message ->
        AlertDialog(
            onDismissRequest = { store.error = null },
            title = { Text(L("Something Went Wrong")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { store.error = null }) { Text(L("OK")) } },
        )
    }
    when (val destination = NotificationRouter.destination) {
        is NotificationDestination.Editor -> FullScreenSheet({ NotificationRouter.destination = null }) {
            EditTransactionView(destination.transaction) { NotificationRouter.destination = null }
        }
        NotificationDestination.Uncategorized -> FullScreenSheet({ NotificationRouter.destination = null }) {
            UncategorizedTransactionsView(onClose = { NotificationRouter.destination = null })
        }
        null -> Unit
    }
}

/**
 * The tab bar's tabs, in the order shown, with the tag each is addressed by
 * (StartTab, notifications) — and, per the navigation bar spec, an outlined
 * icon at rest and a filled one when selected.
 */
private enum class Tab(val tag: Int, val route: Route, val labelKey: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Budget(1, Route.Budget, "Budget", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    Accounts(0, Route.Accounts, "Accounts", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    Add(2, Route.AddTransaction, "Add", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
    Reports(3, Route.Reports, "Reports", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    More(4, Route.More, "More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

object MainTabText {
    /** The numeric badge isn't surfaced to accessibility on its own, so it's mirrored as a spoken value. */
    fun overspentBadgeValue(count: Int, locale: Locale = Locale.getDefault()): String =
        if (count > 0) ReportStrings.localized("%lld overspent categories", count.toLong(), locale = locale) else ""
}

@Composable
fun MainTabView() {
    val store = LocalBudgetStore.current
    var selectedTag by rememberSaveable { mutableIntStateOf(store.startTab.tabTag) }
    val stacks = remember { Tab.entries.associate { it.tag to NavStack(it.route) } }
    val holder = rememberSaveableStateHolder()

    // A tab-hosted save routes to the account's transactions, on the Accounts tab.
    LaunchedEffect(NotificationRouter.pendingAccountNavigation) { if (NotificationRouter.pendingAccountNavigation != null) selectedTag = Tab.Accounts.tag }
    // Cancel in the tab-hosted add flow returns to the Start Page.
    LaunchedEffect(NotificationRouter.pendingTabNavigation) {
        NotificationRouter.pendingTabNavigation?.let {
            selectedTag = it
            NotificationRouter.pendingTabNavigation = null
        }
    }

    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                for (tab in Tab.entries) {
                    val badge = if (tab == Tab.Budget) store.overspentBadgeCount else 0
                    val selected = selectedTag == tab.tag
                    ShortNavigationBarItem(
                        selected = selected,
                        // Reselecting a tab returns it to its root, as on iOS.
                        onClick = { if (selected) stacks.getValue(tab.tag).reset() else selectedTag = tab.tag },
                        icon = {
                            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) }
                        },
                        label = { Text(L(tab.labelKey)) },
                        modifier = Modifier.testTag("tab.${tab.name.lowercase()}").semantics { if (badge > 0) stateDescription = MainTabText.overspentBadgeValue(badge) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            holder.SaveableStateProvider(selectedTag) {
                TabNavigation(stacks.getValue(selectedTag)) { route -> TabRoot(route, isSelected = true) }
            }
            // Over More's whole stack, so work started from any of its screens stays covered.
            if (selectedTag == Tab.More.tag && store.isLoading) DelayedLoadingIndicator(Modifier.align(Alignment.Center), contained = true)
        }
    }
}

@Composable
private fun TabRoot(route: Route, isSelected: Boolean) {
    when (route) {
        Route.Accounts -> AccountsListView()
        Route.AddTransaction -> AddTransactionTabView(isSelected)
        Route.More -> SettingsView()
        Route.Budget -> BudgetView()
        Route.Reports -> ReportsTabView()
        else -> EmptyState(L(route.toString()), Icons.Filled.Construction)
    }
}

/**
 * The Add tab: the add form on the default account, else the first open one.
 * A default account that's gone is cleared, with an alert saying so.
 */
@Composable
fun AddTransactionTabView(isSelected: Boolean) {
    val store = LocalBudgetStore.current
    var showingDefaultAccountAlert by remember { mutableStateOf(false) }
    val configuredId = store.defaultAccountId
    val validDefault = configuredId?.let { id -> store.accounts.firstOrNull { it.id == id && !it.closed } }
    val account = validDefault ?: store.accounts.firstOrNull { !it.closed }
    LaunchedEffect(configuredId, validDefault) {
        if (configuredId != null && validDefault == null && account != null) {
            store.defaultAccountId = null
            showingDefaultAccountAlert = true
        }
    }
    if (account == null) {
        EmptyState(L("No Accounts"), Icons.Filled.AccountBalance, L("Add an account to create transactions"))
    } else {
        AddTransactionView(account.id, autofocusAmount = isSelected, onSaved = { CategoryFundingAutomation.processIfNeeded(it, store) })
    }
    if (showingDefaultAccountAlert) {
        AlertDialog(
            onDismissRequest = { showingDefaultAccountAlert = false },
            title = { Text(L("Default Account Unavailable")) },
            text = { Text(L("Your default account is no longer available. Please configure a new default in More → Transactions & Automation.")) },
            confirmButton = { TextButton(onClick = { showingDefaultAccountAlert = false }) { Text(L("OK")) } },
        )
    }
}

/**
 * Shake-to-hide-balances: iOS delivers a shake as a motion event; Android has
 * only the raw accelerometer, so a jolt well past gravity counts as one.
 * Listens only while the setting is on.
 */
@Composable
private fun ShakeDetector(enabled: Boolean, onShake: () -> Unit) {
    if (!enabled) return
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var lastShake = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                // One shake per second, however long the shaking lasts.
                if (gForce > SHAKE_THRESHOLD_G && now - lastShake > 1000) {
                    lastShake = now
                    currentOnShake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { manager.unregisterListener(listener) }
    }
}

private const val SHAKE_THRESHOLD_G = 2.7f
