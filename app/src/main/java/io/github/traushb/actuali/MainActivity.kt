package io.github.traushb.actuali

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.HomeScreenShortcut
import io.github.traushb.actuali.services.loadDemoData
import io.github.traushb.actuali.services.network.OpenIDAuthenticator
import io.github.traushb.actuali.services.notifications.AndroidNotificationPoster
import io.github.traushb.actuali.services.notifications.NotificationRouter
import io.github.traushb.actuali.views.ActualiTheme
import io.github.traushb.actuali.views.ContentView
import io.github.traushb.actuali.views.LocalBudgetStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val store get() = BudgetStore.shared

    /** OpenID sign-in opens in a Custom Tab; the provider redirects back through the actuali:// intent filter. */
    private val openIDLauncher: (String) -> Boolean = { url ->
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, url.toUri()) }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        OpenIDAuthenticator.launcher = openIDLauncher
        HomeScreenShortcut.publish(this)
        if (savedInstanceState == null) handle(intent)
        // Debug builds take `--ez loadDemoData true` from `adb shell am start`, the iOS -loadDemoData launch argument.
        if (BuildConfig.DEBUG && savedInstanceState == null && intent.getBooleanExtra("loadDemoData", false)) {
            lifecycleScope.launch {
                store.loadDemoData(
                    tracking = intent.getBooleanExtra("loadTrackingDemoData", false),
                    seedUncategorized = intent.getBooleanExtra("seedUncategorized", false),
                    seedUnsupportedBankSync = intent.getBooleanExtra("seedUnsupportedBankSync", false),
                )
            }
        }
        setContent {
            ActualiTheme(store.appearanceMode) {
                CompositionLocalProvider(LocalBudgetStore provides store) { ContentView() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onPause() {
        super.onPause()
        OpenIDAuthenticator.onPaused()
    }

    override fun onResume() {
        super.onResume()
        OpenIDAuthenticator.onResumed()
    }

    override fun onDestroy() {
        if (OpenIDAuthenticator.launcher === openIDLauncher) OpenIDAuthenticator.launcher = null
        super.onDestroy()
    }

    /** A sign-in callback, the launcher shortcut, or a tapped notification. */
    private fun handle(intent: Intent) {
        intent.dataString?.let { if (OpenIDAuthenticator.handleCallback(it)) return }
        HomeScreenShortcut.tab(intent.action)?.let {
            NotificationRouter.pendingTabNavigation = it
            return
        }
        if (!intent.hasExtra(AndroidNotificationPoster.EXTRA_CATEGORY)) return
        val category = intent.getStringExtra(AndroidNotificationPoster.EXTRA_CATEGORY)
        val transactionIds = intent.getStringArrayExtra(AndroidNotificationPoster.EXTRA_TRANSACTION_IDS)?.toList().orEmpty()
        val accountId = intent.getStringExtra(AndroidNotificationPoster.EXTRA_ACCOUNT_ID)
        lifecycleScope.launch { NotificationRouter.handleTap(category, transactionIds, accountId, store) }
    }
}
