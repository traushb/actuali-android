package io.github.traushb.actuali.views.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.BudgetDisplayStyle
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.setGoalTemplatesEnabled
import io.github.traushb.actuali.services.setGoalTemplatesUIEnabled
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.budget.statusColor
import io.github.traushb.actuali.views.budget.statusText
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow
import kotlinx.coroutines.launch

/**
 * ponytail: Android has no system color picker, so status colors come from a
 * fixed palette. The upgrade is a hue/brightness picker if anyone needs an
 * exact shade.
 */
private val statusPalette = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00897B, 0xFF00ACC1,
    0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41, 0xFF757575,
)

@Composable
fun BudgetViewSettingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var colorsExpanded by remember { mutableStateOf(false) }
    var showingColorInfo by remember { mutableStateOf(false) }
    var pickingColorFor by remember { mutableStateOf<CategoryProgressState?>(null) }
    val clean = store.budgetDisplayStyle == BudgetDisplayStyle.Clean

    Scaffold(topBar = { AppTopBar(L("Budget View")) }) { padding ->
        FormColumn(padding) {
            // The Budget options menu keeps these as contextual shortcuts;
            // Settings exposes the same preferences outside the Budget tab.
            FormSection(L("Presentation"), if (clean) L("Group Totals and Show Spent are available in Compact view.") else null) {
                FormPickerRow(
                    L("View Style"),
                    BudgetDisplayStyle.entries,
                    store.budgetDisplayStyle,
                    { L(if (it == BudgetDisplayStyle.Clean) "Clean" else "Compact") },
                    { store.budgetDisplayStyle = it },
                )
                FormToggleRow(L("Group Totals"), store.showGroupTotals, { store.showGroupTotals = it }, enabled = !clean)
                FormToggleRow(L("Show Spent"), store.showCompactSpentColumn, { store.showCompactSpentColumn = it }, Modifier.testTag("budgetViewSettings.showSpent"), enabled = !clean)
                FormToggleRow(L("Budgeted Amounts"), store.showBudgetedAmounts, { store.showBudgetedAmounts = it })
                FormToggleRow(L("Status Filters"), store.showBudgetCheckInStrip, { store.showBudgetCheckInStrip = it })
                FormToggleRow(L("Hide Spent Categories"), store.hideZeroBudgetCategories, { store.hideZeroBudgetCategories = it })
                FormToggleRow(L("Category Status Dots"), store.showCategoryStatusDots, { store.showCategoryStatusDots = it })
                FormToggleRow(L("Budget Progress Bars"), store.showBudgetProgressBars, { store.showBudgetProgressBars = it })
                FormToggleRow(L("Inverse Progress Bars"), store.showInverseBudgetProgressBars, { store.showInverseBudgetProgressBars = it }, enabled = store.showBudgetProgressBars)
                FormToggleRow(L("Overspent Badge"), store.showOverspentBadge, { store.showOverspentBadge = it })
                FormToggleRow(L("Show Balance Impact"), store.showTransactionImpactCue, { store.showTransactionImpactCue = it }, Modifier.testTag("budgetViewSettings.showBalanceImpact"))
                FormToggleRow(L("Hide Income Group"), store.hideIncomeGroup, { store.hideIncomeGroup = it }, Modifier.testTag("budgetViewSettings.hideIncomeGroup"))
                FormRow(
                    L("Color picker"),
                    leading = {
                        IconButton(onClick = { showingColorInfo = true }, modifier = Modifier.testTag("categoryStatusColorPickerInfo")) {
                            Icon(Icons.Filled.Info, contentDescription = L("Color picker information"))
                        }
                    },
                    trailing = { Icon(if (colorsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null) },
                    onClick = { colorsExpanded = !colorsExpanded },
                )
                if (colorsExpanded) {
                    for (state in CategoryProgressState.entries) {
                        FormRow(
                            state.statusText(),
                            Modifier.testTag("categoryStatusColorPicker.${state.name.lowercase()}"),
                            leading = { Box(Modifier.size(20.dp).background(store.statusColor(state), CircleShape)) },
                            trailing = {
                                IconButton(
                                    onClick = { store.resetCategoryStatusDotColor(state) },
                                    enabled = store.hasCustomCategoryStatusDotColor(state),
                                    modifier = Modifier.testTag("categoryStatusColorReset.${state.name.lowercase()}"),
                                ) { Icon(Icons.Filled.Restore, contentDescription = L("Reset to Default")) }
                            },
                            onClick = { pickingColorFor = state },
                        )
                    }
                }
            }

            // Mirrors the web's Settings → Experimental features: the flag is
            // a synced preference, so flipping it here flips it for every
            // client on this budget.
            FormSection(
                L("Experimental"),
                L("Set budgeting goals per category with #template and #goal lines in category notes, or with the visual automations editor, then apply them from the Budget tab's options menu. Synced with the web app's Goal Templates experimental features."),
            ) {
                FormToggleRow(
                    L("Budget Goal Templates"),
                    store.goalTemplatesEnabled,
                    { scope.launch { store.setGoalTemplatesEnabled(it) } },
                    enabled = store.currentBudgetId != null,
                )
                FormToggleRow(
                    L("Automations Editor"),
                    store.goalTemplatesUIEnabled,
                    { scope.launch { store.setGoalTemplatesUIEnabled(it) } },
                    enabled = store.currentBudgetId != null && store.goalTemplatesEnabled,
                )
            }
        }
    }

    if (showingColorInfo) {
        AlertDialog(
            onDismissRequest = { showingColorInfo = false },
            title = { Text(L("Color picker")) },
            text = { Text(L("Picked color will be used for both category status dots and progress bars.")) },
            confirmButton = { TextButton({ showingColorInfo = false }) { Text(L("OK")) } },
        )
    }
    pickingColorFor?.let { state ->
        ColorPaletteDialog(state.statusText(), store.statusColor(state), onDismiss = { pickingColorFor = null }) {
            store.setCategoryStatusDotColor(it.toArgb().toLong(), state)
            pickingColorFor = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPaletteDialog(title: String, current: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (argb in statusPalette) {
                    val color = Color(argb)
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(color, CircleShape)
                            .then(if (color == current) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { onPick(color) }
                            .semantics { contentDescription = "#%06X".format(argb and 0xFFFFFF) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(L("Cancel")) } },
    )
}
