package io.github.traushb.actuali.widgets

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.traushb.actuali.MainActivity
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.shared.WidgetCategoryBalance
import io.github.traushb.actuali.shared.WidgetSnapshot
import io.github.traushb.actuali.shared.WidgetSnapshotStore
import java.time.LocalDate

/**
 * Remaining available amount for chosen categories, read from the snapshot
 * the app writes after every refresh — the widget never opens the budget.
 * One category at the small size, four at medium.
 */
class CategoryBalanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshotStore.standard?.read()?.takeIf { it.isForMonth(LocalDate.now()) }
        provideContent {
            val chosen = currentState(CHOSEN_CATEGORIES)?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
            val limit = if (LocalSize.current.width < MEDIUM.width) 1 else 4
            GlanceTheme {
                Content(snapshot, snapshot?.categories(chosen, limit).orEmpty(), limit == 1)
            }
        }
    }

    @Composable
    private fun Content(snapshot: WidgetSnapshot?, categories: List<WidgetCategoryBalance>, small: Boolean) {
        val modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
        val secondary = GlanceTheme.colors.onSurfaceVariant
        when {
            categories.isEmpty() -> Column(modifier, verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (snapshot != null) L("No categories to show") else L("Open Actuali to load your budget"),
                    style = TextStyle(color = secondary, fontSize = 12.sp),
                )
            }
            small -> Column(modifier) {
                val category = categories[0]
                Text(category.name, style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 2)
                Spacer(GlanceModifier.defaultWeight())
                Text(category.formattedAvailable, style = TextStyle(color = amountColor(snapshot, category), fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(L("available"), style = TextStyle(color = secondary, fontSize = 11.sp))
            }
            else -> Column(modifier) {
                for (category in categories) {
                    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Text(category.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        Text(category.formattedAvailable, style = TextStyle(color = amountColor(snapshot, category), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
                Spacer(GlanceModifier.defaultWeight().height(0.dp))
                val updated = DateUtils.getRelativeTimeSpanString(snapshot!!.generatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                Text(L("Updated %@", updated.toString()), style = TextStyle(color = secondary, fontSize = 11.sp))
            }
        }
    }

    /**
     * The Budget tab's row semantics: red when overspent, green otherwise —
     * neutral when balances are hidden so the mask doesn't leak the sign.
     */
    @Composable
    private fun amountColor(snapshot: WidgetSnapshot?, category: WidgetCategoryBalance): ColorProvider = when {
        snapshot?.balancesHidden != false -> GlanceTheme.colors.onSurface
        category.isOverspent -> ColorProvider(Color(0xFFD32F2F))
        else -> ColorProvider(Color(0xFF2E7D32))
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)

        /** The chosen category ids, comma-separated, in the order picked. Empty means "not configured yet": the first categories show. */
        val CHOSEN_CATEGORIES = stringPreferencesKey("categories")
    }
}

class CategoryBalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CategoryBalanceWidget()
}
