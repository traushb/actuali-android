package io.github.traushb.actuali.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

private val logger = Logger("BackgroundRefresh")

/**
 * Periodic background refresh: the system wakes the app, it syncs headlessly
 * so the app opens with fresh data, and notifies about new transactions when
 * the user has opted in (enforced in NewTransactionNotifier, not here). Runs
 * for everyone; the system's battery settings are the off switch.
 */
object BackgroundRefresh {
    private const val WORK_NAME = "com.mfazz.ActualiOS.refresh"

    /** A floor, not a schedule: actual timing is at the system's discretion. */
    private val MINIMUM_INTERVAL: Duration = Duration.ofHours(1)

    /** Idempotent — an already-scheduled chain keeps its timing. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackgroundRefreshWorker>(MINIMUM_INTERVAL)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/**
 * When the background refresh last fired, persisted device-locally so
 * Settings can show whether the system is waking the app at all.
 */
class BackgroundRefreshStatus(private val defaults: Defaults = Defaults.standard) {
    /** Epoch milliseconds. */
    var lastRun: Long?
        get() = (defaults.get(LAST_RUN_KEY) as? Number)?.toLong()
        set(value) = defaults.set(LAST_RUN_KEY, value)

    private companion object {
        const val LAST_RUN_KEY = "backgroundRefreshLastRun"
    }
}

class BackgroundRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Recorded at fire time, not completion: the Settings row answers "is the system waking the app at all?".
        BackgroundRefreshStatus().lastRun = System.currentTimeMillis()
        logger.info("Background refresh fired, starting headless sync")
        // The store is main-thread state; a stopped worker cancels this
        // coroutine, which aborts the sync's network calls.
        val synced = withContext(Dispatchers.Main) { BudgetStore.shared.syncInBackground() }
        logger.info("Background sync finished (budgetConfigured: $synced)")
        // Success either way: nothing to sync isn't worth a retry backoff.
        return Result.success()
    }
}
