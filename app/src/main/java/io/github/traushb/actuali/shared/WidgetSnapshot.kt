package io.github.traushb.actuali.shared

import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.services.AppJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One category's remaining balance as the widget shows it. The amount is
 * pre-formatted by the app so the widget never needs the budget's currency
 * settings.
 */
@Serializable
data class WidgetCategoryBalance(
    val id: String,
    val name: String,
    /** In cents; carried alongside the formatted string so the widget can color overspent categories without parsing the display text. */
    val available: Long,
    val formattedAvailable: String,
) {
    val isOverspent: Boolean get() = available < 0
}

/**
 * Everything the widget needs to render, written by the app after each data
 * refresh. App and widget ship as one APK, so both always agree on this
 * format; a failed decode (null) already covers any future format change.
 */
@Serializable
data class WidgetSnapshot(
    /** The budget month these balances describe ("2026-08"). The widget refuses to show a past month as current — see [isForMonth]. */
    val month: String,
    /** Epoch milliseconds. */
    val generatedAt: Long,
    /**
     * Mirrors the app's hide-balances privacy setting. The formatted amounts
     * arrive already masked; the widget additionally mutes overspent coloring
     * so the sign doesn't leak through the mask.
     */
    val balancesHidden: Boolean,
    val categories: List<WidgetCategoryBalance>,
) {
    /**
     * Whether these balances describe the month containing [date]. False once
     * the calendar rolls over without the app running — the widget shows its
     * empty state then, rather than passing off last month's balances as current.
     */
    fun isForMonth(date: LocalDate): Boolean = month == date.format(YEAR_MONTH)

    /**
     * The chosen categories in their chosen order, capped at what the widget
     * size can display. An empty selection falls back to the budget's first
     * categories so a freshly added widget isn't blank.
     */
    fun categories(ids: List<String>, limit: Int): List<WidgetCategoryBalance> {
        if (ids.isEmpty()) return categories.take(limit)
        val byId = LinkedHashMap<String, WidgetCategoryBalance>().apply { for (category in categories) putIfAbsent(category.id, category) }
        return ids.mapNotNull { byId[it] }.take(limit)
    }

    companion object {
        /** Same format as BudgetStore's month keys — the two must agree or the widget would flag every snapshot as stale. */
        private val YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

        /**
         * Bridges the app's budget model to the widget snapshot, with
         * formatting injected so the mapping stays a pure function of its
         * inputs. Sorted the way the Budget tab sorts — fetchBudgetMonth gives
         * no order guarantee, and the unconfigured fallback shows the first few.
         */
        fun make(budgets: List<CategoryBudget>, month: String, balancesHidden: Boolean, generatedAt: Long, format: (Long) -> String) = WidgetSnapshot(
            month = month,
            generatedAt = generatedAt,
            balancesHidden = balancesHidden,
            categories = budgets
                .sortedWith(compareBy({ it.groupSortOrder }, { it.categorySortOrder }))
                .map { WidgetCategoryBalance(it.categoryId, it.categoryName, it.available, format(it.available)) },
        )
    }
}

/**
 * Reads and writes the category-balance snapshot. The app writes after every
 * data refresh; the widget only reads. [reloadWidgets] asks the home screen to
 * redraw.
 */
class WidgetSnapshotStore(val file: File, private val reloadWidgets: () -> Unit) {
    fun write(snapshot: WidgetSnapshot) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(AppJson.encodeToString(WidgetSnapshot.serializer(), snapshot))
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("Couldn't replace ${file.name}")
        }
    }

    /** Best-effort removal, used when the user disconnects so the widget doesn't keep showing the departed budget's balances. */
    fun clear() {
        file.delete()
    }

    /** null when the snapshot is missing or unreadable. */
    fun read(): WidgetSnapshot? = runCatching { AppJson.decodeFromString(WidgetSnapshot.serializer(), file.readText()) }.getOrNull()

    fun reload() = reloadWidgets()

    companion object {
        /** Installed by ActualiApplication; null in unit tests, like the iOS store without its app group. */
        @Volatile
        var standard: WidgetSnapshotStore? = null
    }
}
