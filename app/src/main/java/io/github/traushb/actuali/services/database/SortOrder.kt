package io.github.traushb.actuali.services.database

import kotlin.math.floor

/**
 * Row placement for the ordered lists Actual keeps in `sort_order` columns.
 * Ported from upstream `packages/loot-core/src/server/db/sort.ts` so a
 * category created here lands where the web UI would put it.
 */
object SortOrder {
    /**
     * The gap upstream leaves between neighbouring rows, so an insert between
     * two of them usually only has to pick a midpoint.
     */
    const val INCREMENT: Double = 16384.0

    /** One row of an ordered list: its id and where it sits. */
    data class Position(val id: String, val sortOrder: Double)

    /** A position for a new row, plus the existing rows that have to shift for it to fit. */
    data class Placement(val sortOrder: Double, val moved: List<Position>)

    /**
     * Where a new row goes when it should sit immediately before [targetId]
     * in [items] (already ordered by sort_order, then id).
     *
     * Normally that's the midpoint between the target and whatever precedes
     * it. When those two have closed to within 2 there's no usable midpoint
     * left, so the target and the rows after it are pushed up by a full
     * increment first. A null or unknown [targetId] appends instead.
     *
     * Mirrors upstream `shoveSortOrders`, including its quirk of measuring
     * the midpoint against the *pre-shove* values: the shoved rows all move
     * well above it, so the new row still lands ahead of them.
     */
    fun shove(items: List<Position>, targetId: String?): Placement {
        val to = targetId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
            ?: return Placement((items.lastOrNull()?.sortOrder ?: 0.0) + INCREMENT, emptyList())

        val moved = mutableListOf<Position>()
        val preceding = if (to > 0) items[to - 1].sortOrder else 0.0
        if (items[to].sortOrder - preceding <= 2) {
            var next = to
            var order = floor(items[to].sortOrder) + INCREMENT
            while (next < items.size) {
                // A row already past the new order has a big enough gap of
                // its own, and so does everything below it.
                if (order <= items[next].sortOrder) break
                moved += Position(items[next].id, order)
                next += 1
                order += INCREMENT
            }
        }

        val above = items[to].sortOrder
        val sortOrder = if (to > 0) (items[to - 1].sortOrder + above) / 2 else above / 2
        return Placement(sortOrder, moved)
    }
}
