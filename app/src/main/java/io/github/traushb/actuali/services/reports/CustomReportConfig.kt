package io.github.traushb.actuali.services.reports

/**
 * One row of the `custom_reports` table, as consumed by CustomReportEngine.
 * String-typed option fields deliberately mirror upstream's stored values
 * ("Payment", "Monthly", "BarGraph", …) so unsupported ones can be named in
 * fallback cards instead of failing to decode.
 */
data class CustomReportConfig(
    val id: String,
    val name: String,
    /** "total" | "time" */
    val mode: String,
    /** "Category" | "Group" | "Interval" | "Payee" | ... */
    val groupBy: String,
    /** "Payment" | "Deposit" | "Net" | ... */
    val balanceType: String,
    /** "Daily" | "Weekly" | "Monthly" | "Yearly" */
    val interval: String,
    /** "BarGraph" | "StackedBarGraph" | "TableGraph" | ... */
    val graphType: String,
    /** "All time", "Year to date", ... (dynamic ranges) */
    val dateRange: String?,
    val dateStatic: Boolean,
    /** "yyyy-MM-dd" (used when dateStatic) */
    val startDate: String?,
    val endDate: String?,
    val includeCurrent: Boolean,
    val showEmpty: Boolean,
    val showOffBudget: Boolean,
    val showHidden: Boolean,
    val showUncategorized: Boolean,
    /** "desc" | "asc" | "name" | "budget" */
    val sortBy: String,
    /** LineGraph only (upstream show_trend_lines) */
    val showTrendLines: Boolean,
    /** Drop empty leading/trailing intervals (upstream trim_intervals) */
    val trimIntervals: Boolean,
    val conditions: List<WidgetRuleCondition>?,
    val conditionsOp: String?,
)
