package io.github.traushb.actuali.views.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.PayeeLocation
import io.github.traushb.actuali.models.PayeeLocationSummary
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ConfirmationDialog
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.EmptyState
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The GPS coordinates recorded against payees (GH #147). Recording happens
 * silently from the Add Transaction form, so a transaction saved in the
 * wrong place leaves a bad coordinate; this lists every payee with locations
 * so they can be cleared from anywhere. View and clear only, as upstream.
 */
@Composable
fun PayeeLocationsView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    var summaries by remember { mutableStateOf<List<PayeeLocationSummary>?>(null) }
    // Composed afresh on the way back from a payee, so its deletes show without a callback.
    LaunchedEffect(Unit) { summaries = store.fetchPayeesWithLocations() }

    Scaffold(topBar = { AppTopBar(L("Payee Locations")) }) { padding ->
        val current = summaries
        when {
            current == null -> DelayedLoadingIndicator(Modifier.padding(padding).fillMaxSize(), contained = true)
            current.isEmpty() -> EmptyState(L("No Recorded Locations"), Icons.Filled.LocationOff, L("No payee has a location recorded against it."), Modifier.padding(padding))
            else -> FormColumn(padding) {
                FormSection(footer = L("Locations are recorded when you save a transaction with Save Location on, and are used to suggest nearby payees. Turn recording off under Preferences.")) {
                    for (summary in current) {
                        FormRow(summary.payee.name, value = summary.locationCount.toString(), navigates = true, onClick = { nav.push(Route.PayeeLocationDetail(summary.payee.id, summary.payee.name)) })
                    }
                }
            }
        }
    }
}

/** One payee's recorded locations, newest first: swipe one away, or clear them all. */
@Composable
fun PayeeLocationDetailView(payeeId: String, payeeName: String) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var locations by remember { mutableStateOf<List<PayeeLocation>>(emptyList()) }
    var confirmingClearAll by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(payeeId) { locations = store.fetchPayeeLocations(payeeId) }

    // Tombstone, then re-read. On failure the rows stay put; the store already logged why.
    fun delete(doomed: List<PayeeLocation>) {
        if (doomed.isEmpty()) return
        scope.launch {
            if (!store.deletePayeeLocations(doomed)) {
                failure = L("The location couldn't be removed. Check your connection and try again.")
                return@launch
            }
            locations = store.fetchPayeeLocations(payeeId)
        }
    }

    Scaffold(topBar = { AppTopBar(payeeName) }) { padding ->
        FormColumn(padding) {
            if (locations.isEmpty()) {
                FormSection { FormRow(L("No locations recorded."), enabled = false) }
            } else {
                FormSection(footer = L("Swipe a location to remove it. Removing every location stops %@ being suggested when you're nearby.", payeeName)) {
                    for (location in locations) {
                        // Keyed per location, so a removal doesn't hand this row's swipe state to its neighbour.
                        key(location.id) { SwipeToDeleteRow(location) { delete(listOf(location)) } }
                    }
                }
                FormSection { FormButtonRow(L("Clear All Locations"), { confirmingClearAll = true }, destructive = true) }
            }
        }
    }
    if (confirmingClearAll) {
        ConfirmationDialog(
            title = L("Clear all %lld recorded locations for %@?", locations.size.toLong(), payeeName),
            confirmLabel = L("Clear All Locations"),
            destructive = true,
            onDismiss = { confirmingClearAll = false },
            onConfirm = { delete(locations) },
        )
    }
    MessageAlert(L("Couldn't Clear Location"), failure) { failure = null }
}

@Composable
private fun SwipeToDeleteRow(location: PayeeLocation, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(
        state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.negative).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, contentDescription = L("Delete"), tint = Color.White)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { PayeeLocationRow(location) }
    }
}

/** A recorded coordinate and when it was captured; raw latitude and longitude are hard to place, so the row opens a map there. */
@Composable
private fun PayeeLocationRow(location: PayeeLocation) {
    val context = LocalContext.current
    val number = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 5
        maximumFractionDigits = 5
    }
    val recorded = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(location.createdAt).atZone(ZoneId.systemDefault()))
    FormRow(
        "${number.format(location.latitude)}, ${number.format(location.longitude)}",
        supporting = L("Recorded %@", recorded),
        trailing = {
            IconButton(onClick = {
                val uri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(${Uri.encode(L("Recorded Location"))})")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) { Icon(Icons.Filled.Map, contentDescription = L("Show on Map")) }
        },
    )
}
