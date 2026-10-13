package io.github.traushb.actuali.views.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.traushb.actuali.models.NoteLinkText
import io.github.traushb.actuali.models.roundHalfAwayFromZero
import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.CurrencyAmountFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.DashboardWidget
import io.github.traushb.actuali.services.reports.MarkdownMeta
import io.github.traushb.actuali.services.reports.engines.AgeOfMoneyData
import io.github.traushb.actuali.services.reports.engines.AgeOfMoneyEngine
import io.github.traushb.actuali.services.reports.engines.BalanceForecastData
import io.github.traushb.actuali.services.reports.engines.BalanceForecastEngine
import io.github.traushb.actuali.services.reports.engines.BudgetAnalysisData
import io.github.traushb.actuali.services.reports.engines.BudgetAnalysisEngine
import io.github.traushb.actuali.services.reports.engines.CalendarData
import io.github.traushb.actuali.services.reports.engines.CalendarDayCell
import io.github.traushb.actuali.services.reports.engines.CalendarEngine
import io.github.traushb.actuali.services.reports.engines.CalendarMonthData
import io.github.traushb.actuali.services.reports.engines.CashFlowData
import io.github.traushb.actuali.services.reports.engines.CashFlowEngine
import io.github.traushb.actuali.services.reports.engines.CrossoverData
import io.github.traushb.actuali.services.reports.engines.CrossoverEngine
import io.github.traushb.actuali.services.reports.engines.CustomReportEngine
import io.github.traushb.actuali.services.reports.engines.FormulaEngine
import io.github.traushb.actuali.services.reports.engines.MonteCarloData
import io.github.traushb.actuali.services.reports.engines.MonteCarloEngine
import io.github.traushb.actuali.services.reports.engines.NetWorthData
import io.github.traushb.actuali.services.reports.engines.NetWorthEngine
import io.github.traushb.actuali.services.reports.engines.ReportMonthYearFormatting
import io.github.traushb.actuali.services.reports.engines.SankeyBudgetInput
import io.github.traushb.actuali.services.reports.engines.SankeyEngine
import io.github.traushb.actuali.services.reports.engines.SpendingData
import io.github.traushb.actuali.services.reports.engines.SpendingEngine
import io.github.traushb.actuali.services.reports.engines.SummaryData
import io.github.traushb.actuali.services.reports.engines.SummaryEngine
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AccentRole
import io.github.traushb.actuali.views.components.BarChart
import io.github.traushb.actuali.views.components.BarSeries
import io.github.traushb.actuali.views.components.ChartBand
import io.github.traushb.actuali.views.components.ChartColors
import io.github.traushb.actuali.views.components.ChartSeries
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.LineChart
import io.github.traushb.actuali.views.components.ShapedIcon
import io.github.traushb.actuali.views.components.StatusTone
import io.github.traushb.actuali.views.components.color
import io.github.traushb.actuali.views.components.container
import io.github.traushb.actuali.views.components.containerShape
import io.github.traushb.actuali.views.components.onContainer
import io.github.traushb.actuali.views.components.sparseLabels
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.positive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// Formatting

object SummaryWidgetFormatting {
    fun percentage(value: Double, locale: Locale): String =
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 2 }.format(value / 100)
}

object MonteCarloWidgetFormatting {
    fun percentage(value: Double, locale: Locale): String =
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(value / 100)
}

object CalendarWidgetFormatting {
    /** Single-letter weekday headers starting at [firstDayOfWeekIdx] (0 = Sunday). */
    fun weekdaySymbols(locale: Locale, firstDayOfWeekIdx: Int): List<String> =
        (0 until 7).map { DayOfWeek.SUNDAY.plus((it + firstDayOfWeekIdx).toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale) }

    fun monthTitle(date: LocalDate, locale: Locale): String = ReportMonthYearFormatting.string(date, locale)
}

object ReportCurrencyAxisFormatting {
    /**
     * A y-axis tick as currency. Whole units where the tick is whole — nice
     * ticks nearly always are, and "$1,000" reads faster on an axis than
     * iOS's "$1,000.00" — with the cents kept for the fractional rest.
     */
    fun label(units: Double, numberFormat: ActualNumberFormat, currencyCode: String, narrowSymbol: Boolean, locale: Locale = Locale.getDefault()): String {
        val cents = roundHalfAwayFromZero(units * 100)
        return CurrencyAmountFormat.string(cents, currencyCode, narrowSymbol, wholeUnits = cents % 100 == 0L, numberFormat = numberFormat, locale = locale)
    }
}

/** A y-axis tick in the open budget's currency. */
internal fun BudgetStore.axisLabel(units: Double): String = ReportCurrencyAxisFormatting.label(units, numberFormat, currencyCode, useNarrowCurrencySymbol)

/**
 * Every point's date as an x label, at most a dozen (the chart thins them
 * further to fit), in a format that suits the spacing: years apart, months
 * apart (with the year once the range crosses one), or days.
 */
private fun dateAxisLabels(dates: List<LocalDate>, locale: Locale = Locale.getDefault()): List<Pair<Double, String>> {
    if (dates.isEmpty()) return emptyList()
    val span = dates.last().toEpochDay() - dates.first().toEpochDay()
    val spacing = span / maxOf(1, dates.size - 1)
    val skeleton = when {
        spacing >= 300 -> "y"
        spacing >= 28 -> if (span > 330) "MMMyy" else "MMM"
        else -> "MMMd"
    }
    return sparseLabels(dates, 12).map { it.toEpochDay().toDouble() to LocaleFormatting.format(it, skeleton, locale) }
}

/** Month slots for a bar chart: the month, plus its year once the range spans more than one. */
private fun monthSlotLabels(months: List<LocalDate>, locale: Locale = Locale.getDefault()): List<String> {
    val skeleton = if (months.size > 12 || months.map { it.year }.distinct().size > 1) "MMMyy" else "MMM"
    return months.map { LocaleFormatting.format(it, skeleton, locale) }
}

// Chrome

/** The card every widget sits on. */
@Composable
fun ReportCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A card's title behind its kind's [icon] — on an Expressive shape and accent
 * fixed by the icon, so each kind of card keeps its look — with its headline
 * figure on the trailing edge.
 */
@Composable
internal fun CardHeader(title: String, icon: ImageVector, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShapedIcon(icon, AccentRole.of(icon.name), containerShape(icon.name.hashCode()), size = 36.dp)
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** The headline figure's caption beneath it, both trailing-aligned. */
@Composable
private fun HeadlineFigure(value: String, caption: String?, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmallEmphasized, color = color, maxLines = 1)
        caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun Placeholder(text: String, minHeight: Dp) {
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A big single figure that shrinks rather than wraps when the amount is long. */
@Composable
private fun BigFigure(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.headlineLargeEmphasized,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = MaterialTheme.typography.headlineLargeEmphasized.fontSize),
        modifier = modifier,
    )
}

/**
 * A widget's card once its data is computed — off the main thread, once
 * per dashboard load — and a loading card of [loadingHeight] until then.
 * A new load keeps the previous result on screen while it computes.
 */
@Suppress("UNCHECKED_CAST") // A widget id's results are always its widget type's.
@Composable
private fun <T : Any> WidgetCard(widgetId: String, inputs: ReportInputs?, loadingHeight: Dp, compute: (ReportInputs) -> T, content: @Composable (T) -> Unit) {
    val currentCompute by rememberUpdatedState(compute)
    // Coming back to the tab finds its last results ready, so nothing reloads or recomputes.
    val value by produceState(inputs?.let { ReportsCache.valueFor(it, widgetId) as T? }, inputs) {
        val loaded = inputs ?: return@produceState
        (ReportsCache.valueFor(loaded, widgetId) as T?)?.let {
            value = it
            return@produceState
        }
        val computed = withContext(Dispatchers.Default) { currentCompute(loaded) }
        ReportsCache.storeValue(loaded, widgetId, computed)
        value = computed
    }
    val current = value
    if (current == null) {
        ReportCard { DelayedLoadingIndicator(Modifier.fillMaxWidth().height(loadingHeight)) }
    } else {
        content(current)
    }
}

/** One dashboard widget: what it computes from the shared [inputs], and the card that shows it. */
@Composable
fun WidgetView(widget: DashboardWidget, inputs: ReportInputs?) {
    val store = LocalBudgetStore.current
    // Captured here, on the main thread: the computation must not read the store.
    val accounts = store.accounts
    val categoryGroups = store.categoryGroups
    val categories = categoryGroups.flatMap { it.categories }
    val payees = store.payees
    val offBudget = accounts.filter { it.offBudget }.map { it.id }.toSet()
    // Budget-level context conditions need (on/off-budget ops, account-name
    // matching) that isn't derivable from the transaction rows themselves.
    val context = ConditionsFilter.Context(
        offBudgetAccountIds = offBudget,
        accountNames = accounts.associate { it.id to it.name },
        categoryGroupIds = categories.associate { it.id to it.groupId },
        categoryGroupNames = categoryGroups.associate { it.id to it.name },
    )
    val locale = Locale.getDefault()
    val name = widget.displayName
    val modifier = Modifier.testTag("reports.widget.${widget.id}")
    when (widget) {
        is DashboardWidget.Summary -> WidgetCard(widget.id, inputs, 80.dp, { SummaryEngine.compute(widget.meta, it.transactions, it.today, context) }) { SummaryWidget(name, it, modifier) }
        is DashboardWidget.NetWorth -> WidgetCard(widget.id, inputs, 180.dp, { NetWorthEngine.compute(widget.meta, it.transactions, it.today, context) }) { NetWorthWidget(name, it, modifier) }
        is DashboardWidget.CashFlow -> WidgetCard(widget.id, inputs, 200.dp, { CashFlowEngine.compute(widget.meta, it.transactions, offBudget, it.today, context) }) { CashFlowWidget(name, it, modifier) }
        is DashboardWidget.Spending -> {
            val income = categories.filter { it.isIncome }.map { it.id }.toSet()
            WidgetCard(widget.id, inputs, 120.dp, {
                SpendingEngine.compute(widget.meta, DashboardLogic.spendingScope(it.transactions, offBudget, income), it.budgets.entries, categories, categoryGroups, it.today, context)
            }) { SpendingWidget(name, it, DashboardLogic.comparisonLabel(widget.meta, locale), modifier) }
        }
        is DashboardWidget.Markdown -> MarkdownWidget(widget.meta, modifier)
        is DashboardWidget.AgeOfMoney -> WidgetCard(widget.id, inputs, 160.dp, { AgeOfMoneyEngine.compute(widget.meta, it.transactions, it.today, context, locale) }) { AgeOfMoneyWidget(name, it, modifier) }
        is DashboardWidget.Formula -> WidgetCard(widget.id, inputs, 100.dp, { FormulaEngine.compute(widget.meta, it.transactions, it.today, context) }) { FormulaWidget(name, it, modifier) }
        is DashboardWidget.CustomReport -> WidgetCard(widget.id, inputs, 200.dp, {
            CustomReportEngine.compute(
                config = widget.meta?.id?.let { id -> it.customReportConfigs[id] },
                transactions = it.transactions,
                reportContext = CustomReportEngine.ReportContext(categories, categoryGroups, offBudget, it.firstDayOfWeekIdx, payees, accounts, it.budgets.entries),
                filterContext = context,
                today = it.today,
                locale = locale,
            )
        }) { CustomReportWidget(it, modifier) }
        is DashboardWidget.Calendar -> WidgetCard(widget.id, inputs, 200.dp, { CalendarEngine.compute(widget.meta, it.transactions, it.today, it.firstDayOfWeekIdx, context) }) { CalendarWidget(name, it, modifier) }
        is DashboardWidget.Crossover -> WidgetCard(widget.id, inputs, 200.dp, { CrossoverEngine.compute(widget.meta, it.transactions, categories, accounts.map { account -> account.id }, it.today) }) { CrossoverWidget(name, it, modifier) }
        is DashboardWidget.BudgetAnalysis -> WidgetCard(widget.id, inputs, 200.dp, {
            BudgetAnalysisEngine.compute(widget.meta, it.transactions, it.budgets.entries, categories, categoryGroups, it.today, context)
        }) { BudgetAnalysisWidget(name, it, modifier) }
        is DashboardWidget.Sankey -> WidgetCard(widget.id, inputs, 240.dp, {
            SankeyEngine.compute(
                meta = widget.meta,
                transactions = it.transactions,
                categoryGroups = categoryGroups,
                // ponytail: budgeted-mode envelope aggregates (To Budget /
                // carryover flows) are omitted — wire per-month toBudget
                // math if those flows turn out to matter on the card.
                budget = SankeyBudgetInput(entries = it.budgets.entries.map { entry -> SankeyBudgetInput.Entry(entry.month, entry.categoryId, entry.amountCents) }),
                today = it.today,
                context = context,
                locale = locale,
            )
        }) { SankeyWidget(name, it, modifier) }
        is DashboardWidget.BalanceForecast -> WidgetCard(widget.id, inputs, 200.dp, {
            BalanceForecastEngine.compute(
                meta = DashboardLogic.forecastMeta(widget.meta, it.budgets.isTracking),
                transactions = it.transactions,
                schedules = it.forecastSchedules,
                trackingBudgetMonths = it.trackingBudgetMonths,
                offBudgetAccountIds = offBudget,
                transferAccountsByPayeeId = payees.mapNotNull { payee -> payee.transferAccountId?.let { payee.id to it } }.toMap(),
                today = it.today,
                context = context,
            )
        }) { BalanceForecastWidget(name, it, modifier) }
        is DashboardWidget.MonteCarlo -> WidgetCard(widget.id, inputs, 220.dp, { MonteCarloEngine.compute(widget.meta, accounts.associate { account -> account.id to account.balance }) }) { MonteCarloWidget(name, it, modifier) }
        // Never a card; the notice above the cards stands in for these.
        is DashboardWidget.Unsupported -> Unit
    }
}

/** The single notice above the cards when the page has widgets of types not shown yet. */
@Composable
fun UnsupportedTypesNotice() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp)
            .testTag("reports.unsupportedNotice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(L("Limited reports are currently available, more will be available soon."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

// Widgets

@Composable
private fun SummaryWidget(name: String, data: SummaryData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    // The magnitude, with the color carrying the direction — the web app's
    // summary card shows "$95,597.58" in red for spending, not a minus sign.
    val text = when (data.kind) {
        SummaryData.Kind.Currency -> store.displayBalance(abs(data.totalCents))
        SummaryData.Kind.Percentage -> SummaryWidgetFormatting.percentage(abs(data.value), Locale.getDefault())
    }
    val tone = when {
        data.value > 0 -> StatusTone.Positive
        data.value < 0 -> StatusTone.Negative
        else -> StatusTone.Neutral
    }
    ReportCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // The direction as a glyph too, on the figure's tone and an Expressive shape, so the card reads before the number does.
            Box(Modifier.size(48.dp).background(tone.container, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(
                    when (tone) {
                        StatusTone.Positive -> Icons.AutoMirrored.Filled.TrendingUp
                        StatusTone.Negative -> Icons.AutoMirrored.Filled.TrendingDown
                        else -> Icons.AutoMirrored.Filled.TrendingFlat
                    },
                    contentDescription = null,
                    tint = tone.onContainer,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BigFigure(text, if (tone == StatusTone.Neutral) MaterialTheme.colorScheme.onSurface else tone.color)
            }
        }
    }
}

@Composable
private fun NetWorthWidget(name: String, data: NetWorthData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    ReportCard(modifier) {
        CardHeader(name, Icons.AutoMirrored.Filled.ShowChart) {
            data.points.lastOrNull()?.let { HeadlineFigure(store.displayBalanceWholeUnits(it.balanceCents), null) }
        }
        if (data.points.size >= 2) {
            LineChart(
                listOf(ChartSeries(ReportStrings.text("Balance"), data.points.map { it.date.toEpochDay().toDouble() to it.balanceCents / 100.0 }, MaterialTheme.colorScheme.positive, fillsArea = true, smooth = true)),
                xLabels = dateAxisLabels(data.points.map { it.date }),
                yLabel = store::axisLabel,
                // The trend stays visible; only the axis would disclose amounts.
                showsYAxis = !store.hideBalances,
                showsLegend = false,
            )
        } else {
            Placeholder(ReportStrings.text("Not enough data"), 80.dp)
        }
    }
}

@Composable
private fun CashFlowWidget(name: String, data: CashFlowData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.SwapVert)
        if (data.points.all { it.incomeCents == 0L && it.expenseCents == 0L }) {
            Placeholder(ReportStrings.text("No data"), 180.dp)
        } else {
            BarChart(
                slots = monthSlotLabels(data.points.map { it.periodStart }),
                series = listOf(
                    BarSeries(ReportStrings.text("Income"), data.points.map { it.incomeCents / 100.0 }, MaterialTheme.colorScheme.positive),
                    BarSeries(ReportStrings.text("Expense"), data.points.map { it.expenseCents / 100.0 }, MaterialTheme.colorScheme.negative),
                ),
                yLabel = store::axisLabel,
                showsYAxis = !store.hideBalances,
                height = 200.dp,
            )
        }
    }
}

@Composable
private fun SpendingWidget(name: String, data: SpendingData, comparisonLabel: String, modifier: Modifier) {
    val store = LocalBudgetStore.current
    val delta = data.currentSpentCents - data.comparisonCents
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Payments)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ReportStrings.text("This month"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(store.displayBalance(data.currentSpentCents), style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(comparisonLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(store.displayBalance(data.comparisonCents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (data.comparisonCents != 0L) {
            // Spending more than the comparison is the bad direction.
            val color = when {
                delta > 0 -> MaterialTheme.colorScheme.negative
                delta < 0 -> MaterialTheme.colorScheme.positive
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (delta != 0L) Icon(if (delta > 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(
                    listOf(store.displayBalance(abs(delta)), ReportStrings.text(if (delta > 0) "more spent" else "less spent").takeIf { delta != 0L }).filterNotNull().joinToString(" "),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        }
    }
}

/**
 * A markdown card's text: inline emphasis, code, strikethrough and links,
 * with the text's own line breaks kept (iOS's `inlineOnlyPreservingWhitespace`),
 * plus heading and bullet lines, which the web app renders and iOS shows as typed.
 *
 * ponytail: headings and bullets are the only blocks — numbered lists,
 * quotes and tables show as typed. A CommonMark parser is the upgrade path.
 */
object InlineMarkdown {
    private val heading = Regex("""(#{1,6})\s+(.*?)(?:\s+#+)?\s*""")
    private val bullet = Regex("""(\s*)[-*+]\s+(.*)""")
    private val token = Regex(
        """\[([^\]\n]+)]\((\S+?)\)|\*\*(.+?)\*\*|(?<!\w)__(.+?)__(?!\w)|~~(.+?)~~|`([^`\n]+)`|\*(?!\s)(.+?)(?<!\s)\*|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""",
    )

    fun annotated(text: String): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val headingMatch = heading.matchEntire(line)
            val bulletMatch = bullet.matchEntire(line)
            when {
                headingMatch != null -> {
                    val (level, title) = headingMatch.destructured
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (level.length <= 2) 1.3.em else 1.1.em)) { appendInline(title) }
                }
                bulletMatch != null -> {
                    append(bulletMatch.groupValues[1] + "•  ")
                    appendInline(bulletMatch.groupValues[2])
                }
                else -> appendInline(line)
            }
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        var cursor = 0
        for (match in token.findAll(text)) {
            // Plain runs still get bare URLs linked.
            append(NoteLinkText.annotated(text.substring(cursor, match.range.first)))
            val groups = match.groupValues
            when {
                groups[1].isNotEmpty() -> withLink(LinkAnnotation.Url(groups[2])) { appendInline(groups[1]) }
                groups[3].isNotEmpty() || groups[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(groups[3] + groups[4]) }
                groups[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(groups[5]) }
                groups[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(groups[6]) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(groups[7] + groups[8]) }
            }
            cursor = match.range.last + 1
        }
        append(NoteLinkText.annotated(text.substring(cursor)))
    }
}

@Composable
private fun MarkdownWidget(meta: MarkdownMeta, modifier: Modifier) {
    ReportCard(modifier) {
        Text(
            InlineMarkdown.annotated(meta.content),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = when (meta.textAlign) {
                MarkdownMeta.TextAlign.Center -> TextAlign.Center
                MarkdownMeta.TextAlign.Right -> TextAlign.End
                else -> TextAlign.Start
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgeOfMoneyWidget(name: String, data: AgeOfMoneyData, modifier: Modifier) {
    val days = NumberFormat.getIntegerInstance(Locale.getDefault())
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Schedule) {
            data.currentAge?.let { age ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (data.trend) {
                        AgeOfMoneyData.Trend.Up -> Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.positive, modifier = Modifier.size(18.dp))
                        AgeOfMoneyData.Trend.Down -> Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, tint = MaterialTheme.colorScheme.negative, modifier = Modifier.size(18.dp))
                        AgeOfMoneyData.Trend.Stable -> Unit
                    }
                    Text(ReportStrings.localized("%lld days", age.toLong()), style = MaterialTheme.typography.titleSmallEmphasized)
                }
            }
        }
        if (data.points.size >= 2) {
            LineChart(
                listOf(ChartSeries(ReportStrings.text("Days"), data.points.mapIndexed { index, point -> index.toDouble() to point.age.toDouble() }, ChartColors.Teal, fillsArea = true, smooth = true)),
                xLabels = data.points.mapIndexed { index, point -> index.toDouble() to point.monthLabel },
                yLabel = { days.format(it) },
                showsLegend = false,
                height = 140.dp,
            )
        } else if (data.currentAge == null) {
            Placeholder(ReportStrings.text("Not enough data"), 60.dp)
        }
        if (data.insufficientData) {
            Text(ReportStrings.text("Some expenses predate the income history; ages are approximate."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormulaWidget(name: String, result: FormulaEngine.Result, modifier: Modifier) {
    val store = LocalBudgetStore.current
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Functions)
        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
            when (result) {
                is FormulaEngine.Result.Value -> BigFigure(
                    store.displayBalance(roundHalfAwayFromZero(result.value * 100)),
                    if (result.value < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.positive,
                )
                is FormulaEngine.Result.Text -> BigFigure(result.value, MaterialTheme.colorScheme.onSurface)
                is FormulaEngine.Result.Unsupported -> Placeholder(result.reason, 60.dp)
            }
        }
    }
}

/** Holds its line even with nothing to total, so side-by-side month grids stay aligned. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTotals(incomeCents: Long, expenseCents: Long) {
    val store = LocalBudgetStore.current
    FlowRow(Modifier.heightIn(min = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (incomeCents != 0L) CalendarTotal(Icons.Filled.ArrowDropUp, store.displayBalance(incomeCents), MaterialTheme.colorScheme.positive)
        if (expenseCents != 0L) CalendarTotal(Icons.Filled.ArrowDropDown, store.displayBalance(expenseCents), MaterialTheme.colorScheme.negative)
    }
}

@Composable
private fun CalendarTotal(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun CalendarWidget(name: String, data: CalendarData, modifier: Modifier) {
    val locale = Locale.getDefault()
    val weekdays = CalendarWidgetFormatting.weekdaySymbols(locale, data.firstDayOfWeekIdx)
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.CalendarMonth) { CalendarTotals(data.totalIncomeCents, data.totalExpenseCents) }
        if (data.months.isEmpty()) {
            Placeholder(ReportStrings.text("No data"), 120.dp)
        } else {
            // Upstream scrolls every month sideways; on a phone the latest two keep the cells readable.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (month in data.months.takeLast(2)) CalendarMonthGrid(month, weekdays, locale, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(month: CalendarMonthData, weekdays: List<String>, locale: Locale, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(CalendarWidgetFormatting.monthTitle(month.monthStart, locale), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CalendarTotals(month.totalIncomeCents, month.totalExpenseCents)
        Row(Modifier.padding(top = 4.dp)) {
            for (symbol in weekdays) {
                Text(symbol, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        for (week in month.days.chunked(7)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (cell in week) CalendarDay(cell, Modifier.weight(1f))
            }
        }
    }
}

/** Upstream's DayButton: bottom-anchored bars, income left, expense right, each the day's share of the month's total. */
@Composable
private fun CalendarDay(cell: CalendarDayCell, modifier: Modifier) {
    val day = cell.dayOfMonth
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(if (day == null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (day == null) return@Box
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).fillMaxHeight((cell.incomeSize / 100).toFloat()).background(MaterialTheme.colorScheme.positive.copy(alpha = 0.7f)))
            Box(Modifier.weight(1f).fillMaxHeight((cell.expenseSize / 100).toFloat()).background(MaterialTheme.colorScheme.negative.copy(alpha = 0.7f)))
        }
        Text(day.toString(), fontSize = 8.sp, lineHeight = 8.sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun CrossoverWidget(name: String, data: CrossoverData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    val positive = MaterialTheme.colorScheme.positive
    val negative = MaterialTheme.colorScheme.negative
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Insights) {
            HeadlineFigure(data.yearsToRetire?.let { ReportStrings.yearsToRetire(it) } ?: ReportStrings.text("N/A"), ReportStrings.text("Years to Retire"))
        }
        if (data.points.size >= 2) {
            fun x(month: LocalDate) = month.toEpochDay().toDouble()
            LineChart(
                listOf(
                    ChartSeries(ReportStrings.text("Investment income"), data.points.map { x(it.month) to it.investmentIncomeCents / 100.0 }, positive, smooth = true),
                    ChartSeries(ReportStrings.text("Expenses"), data.points.map { x(it.month) to it.expensesCents / 100.0 }, negative, smooth = true),
                    // Upstream draws adjusted (target) expenses dashed, over the projected months only.
                    ChartSeries("", data.points.mapNotNull { point -> point.adjustedExpensesCents?.let { x(point.month) to it / 100.0 } }, negative, dashed = true, smooth = true),
                ),
                xLabels = dateAxisLabels(data.points.map { it.month }),
                yLabel = store::axisLabel,
                showsYAxis = !store.hideBalances,
                showsLegend = true,
                xRules = listOfNotNull(data.crossoverMonth?.let { x(it) to ChartColors.Blue }),
            )
        } else {
            Placeholder(ReportStrings.text("Not enough data"), 80.dp)
        }
    }
}

@Composable
private fun BudgetAnalysisWidget(name: String, data: BudgetAnalysisData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.DataUsage) {
            // Upstream's headline: the latest interval's balance, green when non-negative.
            data.intervalData.lastOrNull()?.let {
                HeadlineFigure(store.displayBalanceWholeUnits(it.balanceCents), null, if (it.balanceCents >= 0) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.negative)
            }
        }
        if (data.intervalData.isEmpty()) {
            Placeholder(ReportStrings.text("No data"), 180.dp)
            return@ReportCard
        }
        // Budgeted / spent / overspending, as upstream's graph series; spent stays negative, as upstream plots it.
        val values = if (data.balanceOnly) {
            emptyList()
        } else {
            listOf(
                Triple(ReportStrings.text("Budgeted"), data.intervalData.map { it.budgetedCents / 100.0 }, MaterialTheme.colorScheme.positive),
                Triple(ReportStrings.text("Spent"), data.intervalData.map { it.spentCents / 100.0 }, MaterialTheme.colorScheme.negative),
                Triple(ReportStrings.text("Overspending"), data.intervalData.map { it.overspendingAdjustmentCents / 100.0 }, ChartColors.Orange),
            )
        }
        val balance = if (data.showBalance || data.balanceOnly) {
            listOf(ChartSeries(ReportStrings.text("Balance"), data.intervalData.mapIndexed { index, point -> index.toDouble() to point.balanceCents / 100.0 }, ChartColors.Gray))
        } else {
            emptyList()
        }
        val slots = monthSlotLabels(data.intervalData.map { LocalDate.of(it.month / 100, it.month % 100, 1) })
        if (data.graphType == BudgetAnalysisData.GraphType.Bar) {
            BarChart(
                slots,
                values.map { (label, amounts, color) -> BarSeries(label, amounts, color) },
                yLabel = store::axisLabel,
                lines = balance,
                showsYAxis = !store.hideBalances,
                height = 200.dp,
            )
        } else {
            LineChart(
                values.map { (label, amounts, color) -> ChartSeries(label, amounts.mapIndexed { index, amount -> index.toDouble() to amount }, color) } + balance,
                xLabels = slots.mapIndexed { index, label -> index.toDouble() to label },
                yLabel = store::axisLabel,
                showsYAxis = !store.hideBalances,
                height = 200.dp,
            )
        }
    }
}

@Composable
private fun BalanceForecastWidget(name: String, data: BalanceForecastData, modifier: Modifier) {
    val store = LocalBudgetStore.current
    val history = data.points.filter { !it.isForecast }
    // Prefixed with the last historical point so the dashed line joins the solid one.
    val forecast = data.points.filter { it.isForecast }.let { if (it.isNotEmpty() && history.isNotEmpty()) listOf(history.last()) + it else it }
    val color = MaterialTheme.colorScheme.primary
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Timeline) {
            data.points.lastOrNull()?.let { ending ->
                val lowest = data.points.minByOrNull { it.balanceCents }
                HeadlineFigure(
                    ReportStrings.format("Ending: %@", store.displayBalanceWholeUnits(ending.balanceCents)),
                    lowest?.takeIf { it.date != ending.date }?.let { ReportStrings.format("Low: %@", store.displayBalanceWholeUnits(it.balanceCents)) },
                    if (ending.balanceCents < 0) MaterialTheme.colorScheme.negative else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (data.points.size >= 2) {
            LineChart(
                listOf(
                    ChartSeries(ReportStrings.text("History"), history.map { it.date.toEpochDay().toDouble() to it.balanceCents / 100.0 }, color, smooth = true),
                    ChartSeries(ReportStrings.text("Forecast"), forecast.map { it.date.toEpochDay().toDouble() to it.balanceCents / 100.0 }, color.copy(alpha = 0.6f), dashed = true, smooth = true),
                ),
                xLabels = dateAxisLabels(data.points.map { it.date }),
                yLabel = store::axisLabel,
                showsYAxis = !store.hideBalances,
                showsLegend = false,
            )
        } else {
            Placeholder(ReportStrings.text("Not enough data"), 80.dp)
        }
    }
}

/**
 * Upstream MonteCarloCard.tsx: the headline success rate to the target age,
 * over MonteCarloGraph's compact fan (p10–p90, p25–p75, the median) with
 * the axes hidden.
 */
@Composable
private fun MonteCarloWidget(name: String, data: MonteCarloData, modifier: Modifier) {
    val locale = Locale.getDefault()
    // Upstream: Math.round(successRate * 1000) / 10
    val successPercent = Math.round(data.successRate * 1000) / 10.0
    val endAge = data.currentAge + data.horizonYears
    val color = MaterialTheme.colorScheme.primary
    fun age(year: Int) = data.currentAge + year
    ReportCard(modifier) {
        CardHeader(name, Icons.Filled.Casino) {
            HeadlineFigure(
                MonteCarloWidgetFormatting.percentage(successPercent, locale),
                ReportStrings.format("Success rate to age %@", NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(endAge)),
            )
        }
        LineChart(
            listOf(ChartSeries(ReportStrings.text("Median"), data.percentileBands.map { age(it.year) to it.p50 / 100.0 }, color, smooth = true)),
            xLabels = emptyList(),
            yLabel = { "" },
            showsYAxis = false,
            showsLegend = false,
            height = 140.dp,
            bands = listOf(
                ChartBand(data.percentileBands.map { Triple(age(it.year), it.p10 / 100.0, it.p90 / 100.0) }, color.copy(alpha = 0.15f)),
                ChartBand(data.percentileBands.map { Triple(age(it.year), it.p25 / 100.0, it.p75 / 100.0) }, color.copy(alpha = 0.3f)),
            ),
        )
    }
}

/** Shown in place of a chart with nothing in range. */
@Composable
internal fun EmptyChartText(minHeight: Dp = 60.dp) = Placeholder(ReportStrings.text("No data in range"), minHeight)
