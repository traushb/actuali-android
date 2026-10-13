package io.github.traushb.actuali.views.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.UncategorizedTapAction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.notifications.CreditCardNotificationSettings
import io.github.traushb.actuali.services.notifications.TransactionNotificationSettings
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow

/**
 * Defaults, transaction behavior, payee locations and card routing,
 * notifications, and Category Funding. iOS's Wallet automation and import
 * have no Android counterpart; shared bank messages cover the same ground.
 */
@Composable
fun TransactionAutomationSettingsView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val context = LocalContext.current
    var transactionAlerts by remember { mutableStateOf(TransactionNotificationSettings().isEnabled) }
    var cardReminders by remember { mutableStateOf(CreditCardNotificationSettings().isEnabled) }
    var permissionDenied by remember { mutableStateOf(false) }
    val hasBudget = store.currentBudgetId != null

    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        store.scheduleCreditCardDueNotifications()
    }

    // Android 13 asks at runtime; earlier versions only have the app-level switch, checked as is.
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed()) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionDenied = !notificationsAllowed()
        }
    }

    // Permission can change in the system settings while the app is away; re-check on every return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (transactionAlerts || cardReminders) permissionDenied = !notificationsAllowed()
    }

    Scaffold(topBar = { AppTopBar(L("Transactions & Automation")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Defaults"), L("New transactions and shared bank messages use the Default Account when no account is chosen. Conventional Amount Entry types 324 as 324.00 instead of filling cents first.")) {
                // Reachable for demo and offline budgets too: shared messages can't post without it (GH #122).
                if (hasBudget) {
                    val accounts = store.accounts.filter { !it.closed }
                    FormPickerRow(
                        L("Default Account"),
                        listOf(null) + accounts.map { it.id },
                        store.defaultAccountId,
                        { id -> accounts.firstOrNull { it.id == id }?.name ?: L("None") },
                        { store.defaultAccountId = it },
                    )
                } else {
                    FormRow(L("Load a budget to choose a default account."), enabled = false)
                }
                FormToggleRow(L("Conventional Amount Entry"), store.conventionalAmountEntry, { store.conventionalAmountEntry = it })
            }

            FormSection(L("Transaction Behavior")) {
                FormPickerRow(L("View Transactions As"), TransactionDisplayMode.entries, store.transactionDisplayMode, { it.label() }, { store.transactionDisplayMode = it })
                FormPickerRow(L("Uncategorized Action"), UncategorizedTapAction.entries, store.uncategorizedTapAction, { it.label() }, { store.uncategorizedTapAction = it })
            }

            FormSection(
                L("Payees & Accounts"),
                if (!store.payeeLocationWritesEnabled && hasBudget && store.isConnected) L("Payee locations require Actual Server 26.4.0 or later.") else null,
            ) {
                // Meaningless against servers before payee locations (< 26.4.0), and clearing needs the same server (GH #147).
                if (store.payeeLocationWritesEnabled) {
                    FormToggleRow(L("Record Payee Locations"), store.recordPayeeLocations, { store.recordPayeeLocations = it })
                    FormRow(L("Payee Locations"), navigates = true, onClick = { nav.push(Route.PayeeLocations) })
                }
                if (hasBudget) {
                    FormRow(
                        L("Card & Account Mappings"),
                        value = store.cardAccountMappings.size.takeIf { it > 0 }?.toString(),
                        navigates = true,
                        onClick = { nav.push(Route.CardAccountMappings) },
                    )
                    // Same predicate the screen lists, so the count can't promise cards it won't show.
                    FormRow(
                        L("Credit Cards & Billing Cycles"),
                        value = store.activeCreditCardStatementDays.size.takeIf { it > 0 }?.toString(),
                        navigates = true,
                        onClick = { nav.push(Route.CreditCardsSettings) },
                    )
                }
                if (!store.payeeLocationWritesEnabled && !hasBudget) FormRow(L("Load a budget to manage transaction accounts."), enabled = false)
            }

            FormSection(
                L("Notifications"),
                L(
                    if (permissionDenied) {
                        "Notifications are turned off for Actuali in the Settings app, so alerts can't be delivered."
                    } else {
                        "New transaction alerts arrive when Actuali refreshes in the background. Credit card due reminders are sent 7, 5, 3, and 1 days before payment is due for cards with an unpaid balance."
                    },
                ),
            ) {
                // The setting gates posting only; the background refresh behind it runs regardless.
                FormToggleRow(L("New Transaction Alerts"), transactionAlerts, { enabled ->
                    transactionAlerts = enabled
                    TransactionNotificationSettings().isEnabled = enabled
                    if (enabled) requestNotifications() else permissionDenied = false
                })
                FormToggleRow(L("Credit Card Due Reminders"), cardReminders, { enabled ->
                    cardReminders = enabled
                    CreditCardNotificationSettings().isEnabled = enabled
                    if (enabled) requestNotifications()
                    store.scheduleCreditCardDueNotifications()
                })
                if (permissionDenied) {
                    FormButtonRow(L("Open Settings to Allow Notifications"), {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
                }
            }

            FormSection(L("Automations"), L("Category Funding runs only for manual expenses entered from the selected account and funds only the required shortfall.")) {
                if (hasBudget) {
                    FormRow(
                        L("Category Funding Settings"),
                        leading = { Icon(Icons.Filled.ArrowCircleUp, contentDescription = null) },
                        navigates = true,
                        onClick = { nav.push(Route.CategoryFunding) },
                    )
                } else {
                    FormRow(L("Load a budget to configure Category Funding."), enabled = false)
                }
            }
        }
    }
}
