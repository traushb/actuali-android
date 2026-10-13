package io.github.traushb.actuali.views.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.sync
import io.github.traushb.actuali.services.sync.SyncState
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.CyclingFormIcon
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.rememberOpenBudgetName
import kotlinx.coroutines.launch
import java.text.Collator

private const val PRIVACY_POLICY_URL = "https://github.com/traushb/actuali-android/blob/main/PRIVACY.md"

/** A row of the More tab: a screen it pushes. */
data class SettingsItem(val title: String, val icon: ImageVector, val route: Route)

/**
 * The More tab: connection, preferences, the management screens, and
 * information. iOS's Shortcuts section has no Android counterpart; a bank
 * message reaches the app through the share sheet instead.
 */
object SettingsItems {
    /** Localized, case-insensitive order, so each language reads alphabetically. */
    fun sorted(items: List<SettingsItem>): List<SettingsItem> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return items.sortedWith { lhs, rhs -> collator.compare(lhs.title, rhs.title) }
    }

    fun preferences(): List<SettingsItem> = sorted(
        listOf(
            SettingsItem(L("Budget View"), Icons.Filled.AccountBalanceWallet, Route.BudgetViewSettings),
            SettingsItem(L("Display"), Icons.Filled.Smartphone, Route.DisplaySettings),
            SettingsItem(L("Privacy"), Icons.Filled.PanTool, Route.PrivacySettings),
            SettingsItem(L("Transactions & Automation"), Icons.Filled.SwapHoriz, Route.TransactionAutomationSettings),
        ),
    )

    /** Rules need a loaded budget's rules table; the rest work for any session. */
    fun manage(includeRules: Boolean): List<SettingsItem> = sorted(
        listOfNotNull(
            SettingsItem(L("Bank Sync"), Icons.Filled.AccountBalance, Route.BankSyncSetup),
            SettingsItem(L("Bills & Calendar"), Icons.Filled.CalendarMonth, Route.BillsCalendar),
            SettingsItem(L("Scheduled Transactions"), Icons.Filled.EventRepeat, Route.Schedules),
            SettingsItem(L("Tags"), Icons.Filled.Tag, Route.Tags),
            SettingsItem(L("Rules"), Icons.AutoMirrored.Filled.ListAlt, Route.Rules).takeIf { includeRules },
        ),
    )
}

@Composable
fun SettingsView() {
    val stack = LocalNavStack.current
    val context = LocalContext.current
    val appVersion = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault(L("Unknown"))

    // Icons cycle by position: the rows sort per language, so a look fixed per item could repeat side by side.
    @Composable
    fun Rows(items: List<SettingsItem>) = items.forEachIndexed { index, item ->
        FormRow(item.title, leading = { CyclingFormIcon(item.icon, index) }, navigates = true, onClick = { stack.push(item.route) })
    }

    Scaffold(topBar = { AppTopBar(L("More")) }) { padding ->
        FormColumn(padding) {
            ConnectionCard(onOpen = { stack.push(Route.ConnectionData) }, Modifier.testTag("settings.connectionData"))
            FormSection(L("Preferences")) { Rows(SettingsItems.preferences()) }
            FormSection(L("Manage")) {
                val manage = SettingsItems.manage(includeRules = LocalBudgetStore.current.currentBudgetId != null)
                Rows(manage)
                FormRow(L("History"), leading = { CyclingFormIcon(Icons.Filled.History, manage.size) }, navigates = true, onClick = { stack.push(Route.History) })
            }
            FormSection(L("Information")) {
                FormRow(
                    L("Privacy Policy"),
                    Modifier.testTag("settings.privacyPolicy"),
                    leading = { CyclingFormIcon(Icons.Filled.Security, 0) },
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) },
                )
                FormRow(
                    L("Support & Diagnostic"),
                    leading = { CyclingFormIcon(Icons.AutoMirrored.Filled.HelpOutline, 1) },
                    navigates = true,
                    onClick = { stack.push(Route.Support) },
                )
                FormRow(L("Version"), value = appVersion)
            }
        }
    }
}

/**
 * The More tab's lead card — Connection & Data, with what it holds: the open
 * budget, the last sync, and Sync Now. A tertiary container,
 * so it reads apart from the Accounts hero's primary one.
 */
@Composable
private fun ConnectionCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val status = store.syncStatus
    val budgetName = rememberOpenBudgetName()
    val syncing = status.state == SyncState.Syncing
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // The container's own pair inverted, which holds its contrast in both themes.
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.onTertiaryContainer, MaterialShapes.Sunny.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.tertiaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(budgetName ?: L("Connection & Data"), style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            if (store.currentBudgetId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val lastSync = status.lastSyncTime?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() } ?: L("Never")
                    Text(if (syncing) L("Syncing...") else "${L("Last Sync")} · $lastSync", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { scope.launch { store.sync() } },
                        enabled = !syncing,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onTertiaryContainer, contentColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(L("Sync Now"))
                    }
                }
            }
        }
    }
}
