package io.github.traushb.actuali.services.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sync state and last-sync time, kept off BudgetStore so status changes only
 * recompose the views displaying them (Settings' Sync section).
 */
class SyncStatus {
    var state: SyncState by mutableStateOf(SyncState.Idle)

    /** Epoch milliseconds. */
    var lastSyncTime: Long? by mutableStateOf(null)
}
