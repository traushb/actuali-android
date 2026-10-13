package io.github.traushb.actuali.views.budget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.views.info
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.warning
import java.util.Locale

/** The state's own color, before any custom color from Budget View settings. */
val CategoryProgressState.tint: Color
    @Composable get() = when (this) {
        CategoryProgressState.Overspent -> MaterialTheme.colorScheme.negative
        CategoryProgressState.Spent -> MaterialTheme.colorScheme.warning
        CategoryProgressState.Spending -> MaterialTheme.colorScheme.info
        CategoryProgressState.Funded -> MaterialTheme.colorScheme.positive
        CategoryProgressState.Unassigned -> MaterialTheme.colorScheme.onSurfaceVariant
    }

fun CategoryProgressState.statusText(locale: Locale = Locale.getDefault()): String = ReportStrings.text(
    when (this) {
        CategoryProgressState.Overspent -> "budget.status.overspent"
        CategoryProgressState.Spent -> "budget.status.fullySpent"
        CategoryProgressState.Spending -> "budget.status.partiallySpent"
        CategoryProgressState.Funded -> "budget.status.funded"
        CategoryProgressState.Unassigned -> "budget.status.noMoneyAssigned"
    },
    locale,
)

/** The dot and progress-bar color for [state]: the user's pick, else the state's tint. */
@Composable
fun BudgetStore.statusColor(state: CategoryProgressState): Color = categoryStatusDotColor(state)?.let { Color(it.toInt()) } ?: state.tint
