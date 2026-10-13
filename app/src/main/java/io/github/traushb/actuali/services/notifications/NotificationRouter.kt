package io.github.traushb.actuali.services.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.transaction

/** Screen a tapped new-transaction notification resolves to. */
sealed interface NotificationDestination {
    val id: String

    data class Editor(val transaction: Transaction) : NotificationDestination {
        override val id: String get() = "editor-${transaction.id}"
    }

    data object Uncategorized : NotificationDestination {
        override val id: String get() = "uncategorized"
    }
}

/**
 * Routes tapped notifications to pending UI state, and doubles as the app's
 * cross-tab navigation channel. New-transaction notifications resolve to a
 * destination (a single transaction opens its editor, a batch — or a
 * transaction that no longer exists — opens the uncategorized list); a credit
 * card reminder opens its account. Main-thread only.
 */
object NotificationRouter {
    /**
     * Account whose transaction list should open — from a card reminder, or
     * after the tab-hosted add flow saves. MainTabView reacts by selecting the
     * Accounts tab, and the accounts list consumes the id by pushing it.
     */
    var pendingAccountNavigation: String? by mutableStateOf(null)

    /** Tab tag to select after the tab-hosted add flow cancels (the user's Start Page, GH #281). */
    var pendingTabNavigation: Int? by mutableStateOf(null)

    /** From a tapped new-transaction notification. */
    var destination: NotificationDestination? by mutableStateOf(null)

    sealed interface Route {
        data class EditTransaction(val id: String) : Route
        data object Uncategorized : Route
    }

    /**
     * Pure mapping from a tapped notification's payload to a route; null for
     * notifications this route doesn't own.
     */
    fun route(category: String?, transactionIds: List<String>): Route? {
        if (category != NewTransactionNotifier.CATEGORY_IDENTIFIER) return null
        return transactionIds.singleOrNull()?.let { Route.EditTransaction(it) } ?: Route.Uncategorized
    }

    /**
     * Resolve a route against the store. Waits for the budget to load (a
     * cold-launch tap arrives before loading finishes) and degrades to the
     * uncategorized list when the transaction has since disappeared.
     */
    suspend fun destination(route: Route, store: BudgetStore): NotificationDestination = when (route) {
        Route.Uncategorized -> NotificationDestination.Uncategorized
        is Route.EditTransaction -> {
            store.ensureBudgetReady()
            store.transaction(route.id)?.let { NotificationDestination.Editor(it) } ?: NotificationDestination.Uncategorized
        }
    }

    /** A tapped notification's payload, as MainActivity received it. */
    suspend fun handleTap(category: String?, transactionIds: List<String>, accountId: String?, store: BudgetStore) {
        route(category, transactionIds)?.let {
            destination = destination(it, store)
            return
        }
        if (category == CreditCardDueNotifier.CATEGORY_IDENTIFIER && accountId != null) {
            store.ensureBudgetReady()
            pendingAccountNavigation = accountId
        }
    }
}
