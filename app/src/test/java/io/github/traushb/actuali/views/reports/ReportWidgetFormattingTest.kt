package io.github.traushb.actuali.views.reports

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.traushb.actuali.services.ActualNumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ReportWidgetFormattingTest {
    @Test
    fun summaryPercentageKeepsUpToTwoDecimals() {
        assertEquals("27.15%", SummaryWidgetFormatting.percentage(27.15, Locale.US))
        assertEquals("27%", SummaryWidgetFormatting.percentage(27.0, Locale.US))
    }

    @Test
    fun monteCarloPercentageUsesInjectedLocale() {
        assertEquals("85.5%", MonteCarloWidgetFormatting.percentage(85.5, Locale.US))
        val french = MonteCarloWidgetFormatting.percentage(85.5, Locale.FRANCE)
        assertTrue(french, french.startsWith("85,5") && french.endsWith("%"))
    }

    @Test
    fun weekdaySymbolsStartOnConfiguredDay() {
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), CalendarWidgetFormatting.weekdaySymbols(Locale.US, 0))
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), CalendarWidgetFormatting.weekdaySymbols(Locale.FRANCE, 1))
    }

    @Test
    fun monthTitleShowsMonthAndYear() = assertEquals("Jan 2024", CalendarWidgetFormatting.monthTitle(LocalDate.of(2024, 1, 1), Locale.US))

    /** Nice ticks are whole, so the axis drops the cents iOS shows; a fractional tick keeps them. */
    @Test
    fun axisLabelDropsCentsOnlyForWholeTicks() {
        assertEquals("$1,000", ReportCurrencyAxisFormatting.label(1000.0, ActualNumberFormat.CommaDot, "USD", false, Locale.US))
        assertEquals("$12.50", ReportCurrencyAxisFormatting.label(12.5, ActualNumberFormat.CommaDot, "USD", false, Locale.US))
        assertEquals("-$500", ReportCurrencyAxisFormatting.label(-500.0, ActualNumberFormat.CommaDot, "USD", false, Locale.US))
    }

    @Test
    fun markdownRendersInlineEmphasisAndLinks() {
        val text = InlineMarkdown.annotated("**Welcome** to _the_ [docs](https://example.com) and `code`")
        assertEquals("Welcome to the docs and code", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Welcome", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("the", text.text.substring(italic.start, italic.end))
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals("docs", text.text.substring(link.start, link.end))
    }

    /** Headings and bullets render (as the web app does); intraword underscores and lone asterisks stay as typed. */
    @Test
    fun markdownRendersHeadingsAndBulletsButLeavesPlainSymbols() {
        val text = InlineMarkdown.annotated("## Dashboard Tips\n- first item\nsnake_case_name costs 2 * 3")
        assertEquals("Dashboard Tips\n•  first item\nsnake_case_name costs 2 * 3", text.text)
        val heading = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Dashboard Tips", text.text.substring(heading.start, heading.end))
        assertTrue(text.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }
}
