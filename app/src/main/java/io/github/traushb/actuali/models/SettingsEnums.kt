package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.ReportStrings
import java.util.Locale

enum class AppearanceMode(val rawValue: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    val id: String get() = rawValue

    fun label(locale: Locale = Locale.getDefault()): String = when (this) {
        System -> ReportStrings.text("System", locale)
        Light -> ReportStrings.text("Light", locale)
        Dark -> ReportStrings.text("Dark", locale)
    }

    companion object {
        fun resolved(raw: String?): AppearanceMode = entries.firstOrNull { it.rawValue == raw } ?: System
    }
}

/**
 * How the Budget tab lays out its summary and category rows. `Clean` is the
 * card look — category name with a large Available amount and Budgeted/Spent
 * captions. `Compact` is a plain monthly table.
 */
enum class BudgetDisplayStyle(val rawValue: String) {
    Clean("clean"),
    Compact("compact"),
    ;

    companion object {
        /** Detailed was removed in GH #442; keep its saved value opening Compact. */
        fun resolved(raw: String?): BudgetDisplayStyle {
            if (raw == "detailed") return Compact
            return entries.firstOrNull { it.rawValue == raw } ?: Clean
        }
    }
}

/** Tab the app opens on at launch. Persisted to preferences, defaults to Accounts. */
enum class StartTab(val rawValue: String) {
    Accounts("accounts"),
    Budget("budget"),
    AddTransaction("addTransaction"),
    Reports("reports"),
    ;

    val id: String get() = rawValue

    /** Tag of the matching tab in MainTabView. */
    val tabTag: Int
        get() = when (this) {
            Accounts -> 0
            Budget -> 1
            AddTransaction -> 2
            Reports -> 3
        }

    fun label(locale: Locale = Locale.getDefault()): String = when (this) {
        Accounts -> ReportStrings.text("Accounts", locale)
        Budget -> ReportStrings.text("Budget", locale)
        AddTransaction -> ReportStrings.text("Add Transaction", locale)
        Reports -> ReportStrings.text("Reports", locale)
    }

    companion object {
        const val DEFAULTS_KEY = "startTab"

        fun resolved(raw: String?): StartTab = entries.firstOrNull { it.rawValue == raw } ?: Accounts
    }
}

enum class TransactionDisplayMode(val rawValue: String) {
    Flat("flat"),
    GroupedByDate("groupedByDate"),
    ;

    val id: String get() = rawValue

    fun label(locale: Locale = Locale.getDefault()): String = when (this) {
        Flat -> ReportStrings.text("Flat List", locale)
        GroupedByDate -> ReportStrings.text("Grouped by Date", locale)
    }

    companion object {
        const val DEFAULTS_KEY = "transactionDisplayMode"

        fun resolved(raw: String?): TransactionDisplayMode = entries.firstOrNull { it.rawValue == raw } ?: Flat
    }
}

/**
 * What tapping a row in the Uncategorized list opens (GH #260). Freshly
 * imported transactions often need the payee fixed as well as the category,
 * and the editor already carries a category field, so it can be the faster
 * triage stop. Defaults to the category picker to keep the quick-tap flow.
 */
enum class UncategorizedTapAction(val rawValue: String) {
    CategoryPicker("categoryPicker"),
    TransactionEditor("transactionEditor"),
    ;

    val id: String get() = rawValue

    fun label(locale: Locale = Locale.getDefault()): String = when (this) {
        CategoryPicker -> ReportStrings.text("Category Picker", locale)
        TransactionEditor -> ReportStrings.text("Transaction Editor", locale)
    }

    /**
     * Whether tapping [transaction] in the Uncategorized list should open the
     * full editor. Split children never do, whatever the setting says.
     */
    fun opensEditor(transaction: Transaction): Boolean = this == TransactionEditor && transaction.parentId == null

    companion object {
        const val DEFAULTS_KEY = "uncategorizedTapAction"

        fun resolved(raw: String?): UncategorizedTapAction = entries.firstOrNull { it.rawValue == raw } ?: CategoryPicker
    }
}
