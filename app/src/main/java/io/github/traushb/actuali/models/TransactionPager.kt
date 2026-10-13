package io.github.traushb.actuali.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.services.database.BudgetDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Paging state for the transaction lists (GH #65): loads newest-first pages
 * through an injected fetch (SQL LIMIT/OFFSET underneath), remembers the
 * active search so follow-up pages stay filtered, and tracks whether another
 * page may exist so screens know when to show a load-more sentinel.
 * Main-thread only.
 */
class TransactionPager(
    private val pageSize: Int = BudgetDatabase.TRANSACTION_PAGE_SIZE,
    private val fetchPage: suspend (offset: Int, limit: Int, search: String?) -> List<Transaction>,
) {
    var transactions: List<Transaction> by mutableStateOf(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set

    private var search: String? = null
    private var isLoadingMore = false

    /**
     * Bumped on every reset; loads capture it before fetching and drop their
     * result if a newer first page started meanwhile (fast typing in the
     * search field, refresh racing a scroll-triggered page load).
     */
    private var generation = 0

    /** Replace the list with the first page for [search] (null = no filter). */
    suspend fun loadFirstPage(search: String? = null) {
        generation += 1
        val started = generation
        val page = fetchPage(0, pageSize, search)
        if (started != generation || !currentCoroutineContext().isActive) return
        // Committed with its page: a dropped load must leave the old rows and
        // the old query together, or the next page would mix result sets.
        this.search = search
        transactions = page
        hasMore = page.size >= pageSize
    }

    /** Append the next page of the current result set, if one may exist. */
    suspend fun loadNextPage() {
        if (!hasMore || isLoadingMore) return
        isLoadingMore = true
        try {
            val started = generation
            val page = fetchPage(transactions.size, pageSize, search)
            if (started != generation || !currentCoroutineContext().isActive) return
            transactions = transactions + page
            hasMore = page.size >= pageSize
        } finally {
            isLoadingMore = false
        }
    }
}
