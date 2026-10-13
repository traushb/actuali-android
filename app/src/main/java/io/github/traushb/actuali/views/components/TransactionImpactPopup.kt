package io.github.traushb.actuali.views.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.MonthTitles
import io.github.traushb.actuali.services.TransactionImpact
import io.github.traushb.actuali.services.TransactionImpactCue
import io.github.traushb.actuali.services.dismissTransactionImpactCues
import io.github.traushb.actuali.services.spokenImpactText
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import java.time.LocalDate

/**
 * Floating cards that show how the last transaction change moved each budget
 * category's available balance: before, the change, after. Driven by
 * `BudgetStore.transactionImpactCues`; tapping dismisses them early. Scrolls
 * once there are more cards than fit.
 */
@Composable
fun TransactionImpactPopup(modifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    val cues = store.transactionImpactCues
    // The cards stay on screen while they animate out, after the store has cleared them.
    var lastCues by remember { mutableStateOf(cues) }
    SideEffect { if (cues.isNotEmpty()) lastCues = cues }
    AnimatedVisibility(
        cues.isNotEmpty(),
        modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (cue in cues.ifEmpty { lastCues }) TransactionImpactCard(cue)
        }
    }
}

@Composable
private fun TransactionImpactCard(cue: TransactionImpactCue) {
    val store = LocalBudgetStore.current
    val accent = if (cue.isExpense) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.positive
    // Named only when it isn't the current month, so a change to an older
    // transaction doesn't look like it hit this month's balance.
    val monthTitle = MonthTitles.title(cue.month).takeIf { cue.month != TransactionImpact.month(Transaction.yyyymmdd(LocalDate.now())) }
    val spoken = store.spokenImpactText(cue)
    val dismiss = L("Double-tap to dismiss")
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { store.dismissTransactionImpactCues() }
            .clearAndSetSemantics {
                contentDescription = "$spoken. $dismiss"
                role = Role.Button
                onClick { store.dismissTransactionImpactCues(); true }
            }
            .testTag("transactionImpactCue.${cue.id}"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (cue.isExpense) Icons.Filled.SouthEast else Icons.Filled.NorthEast, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(cue.categoryName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    monthTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(store.displayBalance(cue.balanceBeforeCents), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        (if (cue.deltaCents > 0) "+" else "") + store.displayBalance(cue.deltaCents),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(
                        store.displayBalance(cue.balanceAfterCents),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (cue.balanceAfterCents < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
