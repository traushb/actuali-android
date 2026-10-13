package io.github.traushb.actuali.services.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.traushb.actuali.MainActivity
import io.github.traushb.actuali.R
import java.util.concurrent.TimeUnit

/**
 * A notification ready to post: what the user reads plus the routing payload
 * a tap hands back to [NotificationRouter].
 */
data class NotificationContent(
    val title: String,
    val body: String,
    /** Routes the tap; mirrors the iOS notification category. */
    val category: String,
    val transactionIds: List<String> = emptyList(),
    val accountId: String? = null,
)

/** Seam over the platform notification service, so the notifiers' gating is testable. */
interface NotificationPosting {
    /** Whether the user lets this app post (runtime permission and app-level switch). */
    fun isAllowed(): Boolean

    fun post(tag: String, content: NotificationContent)

    /** Posts [content] at [atMillis] (wall clock), replacing anything scheduled under [tag]. */
    fun schedule(tag: String, atMillis: Long, content: NotificationContent)

    fun cancel(tags: List<String>)
}

/**
 * Posts through NotificationManager; future notifications go through
 * WorkManager, which survives reboots the way iOS's calendar triggers do.
 */
class AndroidNotificationPoster(private val context: Context) : NotificationPosting {
    override fun isAllowed(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override fun post(tag: String, content: NotificationContent) {
        if (!isAllowed()) return
        try {
            NotificationManagerCompat.from(context).notify(tag, 0, build(context, content))
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    override fun schedule(tag: String, atMillis: Long, content: NotificationContent) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ScheduledNotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(ScheduledNotificationWorker.inputData(tag, content))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(tag), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(tags: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        val work = WorkManager.getInstance(context)
        for (tag in tags) {
            manager.cancel(tag, 0)
            work.cancelUniqueWork(workName(tag))
        }
    }

    companion object {
        const val CHANNEL_ID = "actuali.general"
        const val EXTRA_CATEGORY = "notification.category"
        const val EXTRA_TRANSACTION_IDS = "notification.transactionIds"
        const val EXTRA_ACCOUNT_ID = "notification.accountId"

        private fun workName(tag: String) = "notification:$tag"

        fun createChannel(context: Context, name: String) {
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun build(context: Context, content: NotificationContent): android.app.Notification {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CATEGORY, content.category)
                putExtra(EXTRA_TRANSACTION_IDS, content.transactionIds.toTypedArray())
                content.accountId?.let { putExtra(EXTRA_ACCOUNT_ID, it) }
            }
            // A distinct request code per payload, so a newer notification's
            // extras don't overwrite an older one's still on screen.
            val pending = PendingIntent.getActivity(
                context, (content.category + content.transactionIds + content.accountId).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(content.title)
                .setContentText(content.body.lineSequence().firstOrNull())
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        }
    }
}

/** Posts a notification [AndroidNotificationPoster.schedule] queued for later. */
class ScheduledNotificationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val data = inputData
        val content = NotificationContent(
            title = data.getString(KEY_TITLE).orEmpty(),
            body = data.getString(KEY_BODY).orEmpty(),
            category = data.getString(KEY_CATEGORY).orEmpty(),
            transactionIds = data.getStringArray(KEY_TRANSACTION_IDS)?.toList().orEmpty(),
            accountId = data.getString(KEY_ACCOUNT_ID),
        )
        AndroidNotificationPoster(applicationContext).post(data.getString(KEY_TAG).orEmpty(), content)
        return Result.success()
    }

    companion object {
        private const val KEY_TAG = "tag"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_CATEGORY = "category"
        private const val KEY_TRANSACTION_IDS = "transactionIds"
        private const val KEY_ACCOUNT_ID = "accountId"

        fun inputData(tag: String, content: NotificationContent): Data = Data.Builder()
            .putString(KEY_TAG, tag)
            .putString(KEY_TITLE, content.title)
            .putString(KEY_BODY, content.body)
            .putString(KEY_CATEGORY, content.category)
            .putStringArray(KEY_TRANSACTION_IDS, content.transactionIds.toTypedArray())
            .putString(KEY_ACCOUNT_ID, content.accountId)
            .build()
    }
}
