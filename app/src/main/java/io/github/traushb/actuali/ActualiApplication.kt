package io.github.traushb.actuali

import android.app.Application
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import io.github.traushb.actuali.services.BackgroundRefresh
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.HistoryObserver
import io.github.traushb.actuali.services.Keychain
import io.github.traushb.actuali.services.KeystoreSecretStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.PendingImportStore
import io.github.traushb.actuali.services.SharedPreferencesDefaults
import io.github.traushb.actuali.services.Strings
import io.github.traushb.actuali.services.backupOnBackground
import io.github.traushb.actuali.services.blankBudgetTemplate
import io.github.traushb.actuali.services.database.BackupDestinationManager
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.location.AndroidPositionSource
import io.github.traushb.actuali.services.location.LocationProvider
import io.github.traushb.actuali.services.notifications.AndroidNotificationPoster
import io.github.traushb.actuali.services.notifications.NewTransactionNotifier
import io.github.traushb.actuali.services.syncOnForeground
import io.github.traushb.actuali.shared.WidgetSnapshotStore
import io.github.traushb.actuali.widgets.CategoryBalanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ActualiApplication : Application(), Configuration.Provider {
    /** WorkManager's on-demand initialization reads this; the default initializer is removed in the manifest. */
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    /** Outlives any one screen: widget redraws the store asks for. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Platform-backed stand-ins for UserDefaults, the keychain, the String
        // Catalogs and Application Support, installed before anything reads them.
        Strings.opener = { tag -> runCatching { assets.open("strings/$tag.json") }.getOrNull() }
        // The string tables are a few hundred KB of JSON; parsing them here, off
        // the main thread, overlaps the activity's start instead of delaying its
        // first frame. Lookups made before it finishes wait for it.
        appScope.launch { Strings.preload() }
        Defaults.standard = SharedPreferencesDefaults(getSharedPreferences("defaults", MODE_PRIVATE))
        Keychain.store = KeystoreSecretStore(this)
        BudgetFileManager.shared = BudgetFileManager(filesDir)
        BackupDestinationManager.shared = BackupDestinationManager(contentResolver)
        PendingImportStore.shared = PendingImportStore(File(filesDir, "pending_imports.json"))
        LocationProvider.shared = LocationProvider(AndroidPositionSource(this))
        blankBudgetTemplate = { assets.open("blank-budget.sqlite") }
        appScope.launch { AndroidNotificationPoster.createChannel(this@ActualiApplication, L("Notifications")) }
        NewTransactionNotifier.poster = AndroidNotificationPoster(this)
        WidgetSnapshotStore.standard = WidgetSnapshotStore(File(filesDir, "widget-snapshot.json")) {
            appScope.launch { CategoryBalanceWidget().updateAll(this@ActualiApplication) }
        }
        // WorkManager initializes on first use (see the manifest), here off the main thread.
        appScope.launch { BackgroundRefresh.schedule(this@ActualiApplication) }
        // History records from the first publication on, like the iOS app's init.
        HistoryObserver(BudgetStore.shared)
        // The iOS scene phase: coming to the foreground syncs, leaving it takes a backup.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    BudgetStore.shared.scope.launch { BudgetStore.shared.syncOnForeground() }
                }

                override fun onStop(owner: LifecycleOwner) = BudgetStore.shared.backupOnBackground()
            },
        )
    }
}
