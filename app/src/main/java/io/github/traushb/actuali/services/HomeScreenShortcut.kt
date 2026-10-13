package io.github.traushb.actuali.services

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.traushb.actuali.MainActivity
import io.github.traushb.actuali.R
import io.github.traushb.actuali.models.StartTab

/** The launcher shortcut on a long-press of the app icon — iOS's Home Screen quick action. */
object HomeScreenShortcut {
    const val ADD_TRANSACTION = "io.github.traushb.actuali.addTransaction"

    /** Published at each launch, as iOS sets its shortcut items, so the label follows the app's language. */
    fun publish(context: Context) {
        val addTransaction = ShortcutInfoCompat.Builder(context, ADD_TRANSACTION)
            .setShortLabel(StartTab.AddTransaction.label())
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add))
            .setIntent(Intent(context, MainActivity::class.java).setAction(ADD_TRANSACTION))
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(context, listOf(addTransaction))
    }

    /** The tab a launch [action] opens, or null when it isn't a shortcut. */
    fun tab(action: String?): Int? = if (action == ADD_TRANSACTION) StartTab.AddTransaction.tabTag else null
}
