package io.github.traushb.actuali.services

import io.github.traushb.actuali.shared.WidgetSnapshot

/**
 * Write the category-balance snapshot and ask the home screen to redraw.
 * Called after every data refresh and when a setting that changes amount
 * formatting flips. No-op until a budget month is loaded, or when no snapshot
 * store is installed.
 */
fun BudgetStore.publishWidgetSnapshot() {
    if (isPublishingReload) return
    val store = widgetSnapshotStore ?: return
    val month = widgetBudgetMonth ?: return
    val previous = lastWidgetSnapshot?.takeIf { it.first == store.file }?.second
    val snapshot = WidgetSnapshot.make(month.categoryBudgets, month.month, hideBalances, System.currentTimeMillis()) { displayBalance(it) }
    // Keep the footer's refresh time current without reloading unchanged balances.
    val contentChanged = snapshot.month != previous?.month || snapshot.balancesHidden != previous.balancesHidden || snapshot.categories != previous.categories
    try {
        store.write(snapshot)
    } catch (e: java.io.IOException) {
        // Leave the last successful snapshot intact and retry next refresh.
        return
    }
    lastWidgetSnapshot = store.file to snapshot
    if (contentChanged) store.reload()
}

/** Remove the snapshot so a disconnected device's widget shows the empty state instead of the departed budget's balances. */
fun BudgetStore.clearWidgetSnapshot() {
    lastWidgetSnapshot = null
    val store = widgetSnapshotStore ?: return
    store.clear()
    store.reload()
}
