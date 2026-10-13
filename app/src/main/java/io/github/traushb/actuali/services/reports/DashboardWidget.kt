package io.github.traushb.actuali.services.reports

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.parseJsonOrNull
import io.github.traushb.actuali.services.reports.engines.BalanceForecastMeta
import io.github.traushb.actuali.services.reports.engines.BudgetAnalysisMeta
import io.github.traushb.actuali.services.reports.engines.CalendarMeta
import io.github.traushb.actuali.services.reports.engines.CrossoverMeta
import io.github.traushb.actuali.services.reports.engines.MonteCarloMeta
import io.github.traushb.actuali.services.reports.engines.SankeyMeta
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// TimeFrame (shared by most widgets)

@Serializable
data class WidgetTimeFrame(
    val start: String? = null,
    val end: String? = null,
    val mode: Mode? = null,
) {
    @Serializable
    enum class Mode {
        @SerialName("sliding-window")
        SlidingWindow,

        @SerialName("static")
        Static,

        @SerialName("full")
        Full,

        @SerialName("lastMonth")
        LastMonth,

        @SerialName("lastYear")
        LastYear,

        @SerialName("yearToDate")
        YearToDate,

        @SerialName("priorYearToDate")
        PriorYearToDate,
    }
}

/** Rule condition (the subset widgets use). Values stay raw JSON, as conditions carry strings, numbers, arrays or objects. */
@Serializable
data class WidgetRuleCondition(
    val op: String,
    val field: String,
    val value: JsonElement? = null,
    val options: JsonElement? = null,
    /** Present on saved-filter references; upstream drops these conditions before filtering, so engines must skip them. */
    val customName: String? = null,
)

// Per-widget meta

@Serializable
data class SummaryMeta(
    val name: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    @Serializable(with = SummaryContentSerializer::class)
    val content: SummaryContent? = null,
)

/**
 * The summary widget's display mode. `type` is one of `sum`, `avgPerMonth`,
 * `avgPerYear`, `avgPerTransact`, or `percentage`; the divisor fields only
 * apply to `percentage`.
 */
@Serializable
data class SummaryContent(
    val type: String? = null,
    val divisorConditions: List<WidgetRuleCondition>? = null,
    val divisorConditionsOp: String? = null,
    val divisorAllTimeDateRange: Boolean? = null,
)

/**
 * Upstream double-encodes content as a JSON string (`"{\"type\":\"sum\"}"`);
 * an inline object is tolerated as well, and unreadable content is no content
 * rather than an unreadable widget.
 */
object SummaryContentSerializer : KSerializer<SummaryContent?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): SummaryContent? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val json = if (element is JsonPrimitive && element.isString) parseJsonOrNull(element.content) else element
        return json?.let { runCatching { AppJson.decodeFromJsonElement(SummaryContent.serializer(), it) }.getOrNull() }
    }

    override fun serialize(encoder: Encoder, value: SummaryContent?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(AppJson.encodeToString(SummaryContent.serializer(), value))
    }
}

@Serializable
data class NetWorthMeta(
    val name: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val interval: Interval? = null,
    val mode: Mode? = null,
) {
    @Serializable
    enum class Interval {
        @SerialName("Daily")
        Daily,

        @SerialName("Weekly")
        Weekly,

        @SerialName("Monthly")
        Monthly,

        @SerialName("Yearly")
        Yearly,
    }

    @Serializable
    enum class Mode {
        @SerialName("trend")
        Trend,

        @SerialName("stacked")
        Stacked,
    }
}

@Serializable
data class CashFlowMeta(
    val name: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val showBalance: Boolean? = null,
)

@Serializable
data class SpendingMeta(
    val name: String? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val compare: String? = null,
    val compareTo: String? = null,
    val isLive: Boolean? = null,
    val mode: Mode? = null,
    val averageRange: SpendingAverageRange? = null,
) {
    @Serializable
    enum class Mode {
        @SerialName("single-month")
        SingleMonth,

        @SerialName("budget")
        Budget,

        @SerialName("average")
        Average,
    }
}

/**
 * Window the spending widget's average mode averages over. Mirrors upstream
 * SpendingAverageRange: `last-n-months` (3/6/12), `year-to-date`, `all-time`.
 */
@Serializable
data class SpendingAverageRange(val mode: String? = null, val months: Int? = null)

@Serializable
data class MarkdownMeta(
    val content: String,
    @SerialName("text_align") val textAlign: TextAlign? = null,
) {
    @Serializable
    enum class TextAlign {
        @SerialName("left")
        Left,

        @SerialName("right")
        Right,

        @SerialName("center")
        Center,
    }
}

@Serializable
data class AgeOfMoneyMeta(
    val name: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    /** "daily" | "weekly" | "monthly"; null = monthly. */
    val granularity: String? = null,
)

@Serializable
data class FormulaQueryMeta(
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
)

@Serializable
data class FormulaMeta(
    val name: String? = null,
    val formula: String? = null,
    val queries: Map<String, FormulaQueryMeta>? = null,
)

@Serializable
data class CustomReportMeta(
    /** References a row in the `custom_reports` table. */
    val id: String? = null,
    val name: String? = null,
)

/**
 * A live row in `dashboard_pages`. The web app treats each page as a separate
 * dashboard (upstream migration 1765518577215); a null name renders as an
 * empty string upstream.
 */
data class DashboardPage(val id: String, val name: String)

sealed interface DashboardWidget {
    val id: String

    data class Summary(override val id: String, val meta: SummaryMeta?) : DashboardWidget
    data class NetWorth(override val id: String, val meta: NetWorthMeta?) : DashboardWidget
    data class CashFlow(override val id: String, val meta: CashFlowMeta?) : DashboardWidget
    data class Spending(override val id: String, val meta: SpendingMeta?) : DashboardWidget
    data class Markdown(override val id: String, val meta: MarkdownMeta) : DashboardWidget
    data class AgeOfMoney(override val id: String, val meta: AgeOfMoneyMeta?) : DashboardWidget
    data class Formula(override val id: String, val meta: FormulaMeta?) : DashboardWidget
    data class CustomReport(override val id: String, val meta: CustomReportMeta?) : DashboardWidget
    data class Calendar(override val id: String, val meta: CalendarMeta?) : DashboardWidget
    data class Crossover(override val id: String, val meta: CrossoverMeta?) : DashboardWidget
    data class BudgetAnalysis(override val id: String, val meta: BudgetAnalysisMeta?) : DashboardWidget
    data class Sankey(override val id: String, val meta: SankeyMeta?) : DashboardWidget
    data class BalanceForecast(override val id: String, val meta: BalanceForecastMeta?) : DashboardWidget
    data class MonteCarlo(override val id: String, val meta: MonteCarloMeta?) : DashboardWidget
    data class Unsupported(override val id: String, val type: String) : DashboardWidget

    val typeLabel: String
        get() = when (this) {
            is Summary -> L("Summary")
            is NetWorth -> L("Net Worth")
            is CashFlow -> L("Cash Flow")
            is Spending -> L("Spending")
            is Markdown -> L("Notes")
            is AgeOfMoney -> L("Age of Money")
            is Formula -> L("Formula")
            is CustomReport -> L("Custom Report")
            is Calendar -> L("Calendar")
            is Crossover -> L("Crossover")
            is BudgetAnalysis -> L("Budget Analysis")
            is Sankey -> L("Sankey")
            is BalanceForecast -> L("Balance Forecast")
            is MonteCarlo -> L("Monte Carlo")
            is Unsupported -> type
        }

    val displayName: String
        get() = when (this) {
            is Summary -> meta?.name
            is NetWorth -> meta?.name
            is CashFlow -> meta?.name
            is Spending -> meta?.name
            is AgeOfMoney -> meta?.name
            is Formula -> meta?.name
            is CustomReport -> meta?.name
            is Calendar -> meta?.name
            is Crossover -> meta?.name
            is BudgetAnalysis -> meta?.name
            is Sankey -> meta?.name
            is BalanceForecast -> meta?.name
            is MonteCarlo -> meta?.name
            is Markdown, is Unsupported -> null
        } ?: typeLabel

    companion object {
        fun parse(id: String, type: String, metaJSON: String?): DashboardWidget {
            fun <T> decode(serializer: KSerializer<T>): T? =
                metaJSON?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }

            return when (type) {
                "summary-card" -> Summary(id, decode(SummaryMeta.serializer()))
                "net-worth-card" -> NetWorth(id, decode(NetWorthMeta.serializer()))
                "cash-flow-card" -> CashFlow(id, decode(CashFlowMeta.serializer()))
                "spending-card" -> Spending(id, decode(SpendingMeta.serializer()))
                "markdown-card" -> decode(MarkdownMeta.serializer())?.let { Markdown(id, it) } ?: Unsupported(id, type)
                "age-of-money-card" -> AgeOfMoney(id, decode(AgeOfMoneyMeta.serializer()))
                "formula-card" -> Formula(id, decode(FormulaMeta.serializer()))
                "custom-report" -> CustomReport(id, decode(CustomReportMeta.serializer()))
                "calendar-card" -> Calendar(id, decode(CalendarMeta.serializer()))
                "crossover-card" -> Crossover(id, decode(CrossoverMeta.serializer()))
                "budget-analysis-card" -> BudgetAnalysis(id, decode(BudgetAnalysisMeta.serializer()))
                "sankey-card" -> Sankey(id, decode(SankeyMeta.serializer()))
                "balance-forecast-card" -> BalanceForecast(id, decode(BalanceForecastMeta.serializer()))
                "monte-carlo-card" -> MonteCarlo(id, decode(MonteCarloMeta.serializer()))
                else -> Unsupported(id, type)
            }
        }
    }
}
