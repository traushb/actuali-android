package io.github.traushb.actuali.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.shared.WidgetSnapshotStore
import io.github.traushb.actuali.views.ActualiTheme
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.components.pageTopBarColors
import kotlinx.coroutines.launch

/**
 * The widget's "Select Categories" screen. It offers the categories of the
 * snapshot the app last wrote — the widget has no database of its own.
 */
class CategoryBalanceWidgetConfigureActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out leaves the widget unplaced.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val categories = WidgetSnapshotStore.standard?.read()?.categories.orEmpty()

        setContent {
            ActualiTheme {
                val chosen = remember { mutableStateListOf<String>() }
                Scaffold(
                    topBar = { TopAppBar(title = { Text(L("Select Categories")) }, colors = pageTopBarColors()) },
                    bottomBar = {
                        Button(
                            onClick = { save(appWidgetId, chosen.toList()) },
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 56.dp).testTag("widgetConfig.done"),
                        ) { Text(L("Done"), style = MaterialTheme.typography.titleMedium) }
                    },
                ) { padding ->
                    // The same segmented tiles as the app's own lists.
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding())) {
                        itemsIndexed(categories, key = { _, it -> it.id }) { index, category ->
                            val checked = category.id in chosen
                            ListItem(
                                headlineContent = { Text(category.name) },
                                trailingContent = { Checkbox(checked, onCheckedChange = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .listTile(index, categories.size)
                                    .toggleable(checked, role = Role.Checkbox) { if (it) chosen.add(category.id) else chosen.remove(category.id) }
                                    .testTag("widgetConfig.category.${category.id}"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun save(appWidgetId: Int, ids: List<String>) = lifecycleScope.launch {
        val glanceId = GlanceAppWidgetManager(this@CategoryBalanceWidgetConfigureActivity).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(this@CategoryBalanceWidgetConfigureActivity, glanceId) { it[CategoryBalanceWidget.CHOSEN_CATEGORIES] = ids.joinToString(",") }
        CategoryBalanceWidget().update(this@CategoryBalanceWidgetConfigureActivity, glanceId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
