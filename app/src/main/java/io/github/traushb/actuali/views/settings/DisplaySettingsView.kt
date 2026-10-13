package io.github.traushb.actuali.views.settings

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.models.AppearanceMode
import io.github.traushb.actuali.models.StartTab
import io.github.traushb.actuali.services.ActualNumberFormat
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.database.fetchDashboardPages
import io.github.traushb.actuali.services.reports.DashboardPage
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class CurrencyOption(val symbol: String, val code: String)

/**
 * Every currency in Actual's loot-core currencies list, plus a few
 * user-requested extras Actual lacks (AMD, BDT, NOK, NZD, VND, ZAR). Sorted by
 * code. Display-only — all budget math is currency-agnostic integer cents,
 * and rendering uses the platform formatter for the ISO code.
 */
val currencyOptions = listOf(
    CurrencyOption("د.إ", "AED"),
    CurrencyOption("֏", "AMD"),
    CurrencyOption("Arg$", "ARS"),
    CurrencyOption("A$", "AUD"),
    CurrencyOption("৳", "BDT"),
    CurrencyOption("R$", "BRL"),
    CurrencyOption("Br", "BYN"),
    CurrencyOption("C$", "CAD"),
    CurrencyOption("Fr", "CHF"),
    CurrencyOption("CLP$", "CLP"),
    CurrencyOption("CN¥", "CNY"),
    CurrencyOption("Col$", "COP"),
    CurrencyOption("₡", "CRC"),
    CurrencyOption("Kč", "CZK"),
    CurrencyOption("kr", "DKK"),
    CurrencyOption("RD$", "DOP"),
    CurrencyOption("ج.م", "EGP"),
    CurrencyOption("€", "EUR"),
    CurrencyOption("£", "GBP"),
    CurrencyOption("Q", "GTQ"),
    CurrencyOption("HK$", "HKD"),
    CurrencyOption("Ft", "HUF"),
    CurrencyOption("Rp", "IDR"),
    CurrencyOption("₪", "ILS"),
    CurrencyOption("₹", "INR"),
    CurrencyOption("﷼", "IRR"),
    CurrencyOption("J$", "JMD"),
    CurrencyOption("¥", "JPY"),
    CurrencyOption("₩", "KRW"),
    CurrencyOption("Rs.", "LKR"),
    CurrencyOption("L", "MDL"),
    CurrencyOption("ден", "MKD"),
    CurrencyOption("MX$", "MXN"),
    CurrencyOption("RM", "MYR"),
    CurrencyOption("kr", "NOK"),
    CurrencyOption("NZ$", "NZD"),
    CurrencyOption("S/", "PEN"),
    CurrencyOption("₱", "PHP"),
    CurrencyOption("Rs.", "PKR"),
    CurrencyOption("zł", "PLN"),
    CurrencyOption("ر.ق", "QAR"),
    CurrencyOption("lei", "RON"),
    CurrencyOption("дин", "RSD"),
    CurrencyOption("₽", "RUB"),
    CurrencyOption("ر.س", "SAR"),
    CurrencyOption("kr", "SEK"),
    CurrencyOption("S$", "SGD"),
    CurrencyOption("฿", "THB"),
    CurrencyOption("₺", "TRY"),
    CurrencyOption("NT$", "TWD"),
    CurrencyOption("₴", "UAH"),
    CurrencyOption("$", "USD"),
    CurrencyOption("\$U", "UYU"),
    CurrencyOption("UZS", "UZS"),
    CurrencyOption("₫", "VND"),
    CurrencyOption("R", "ZAR"),
)

@Composable
fun DisplaySettingsView() {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    var dashboardPages by remember { mutableStateOf<List<DashboardPage>>(emptyList()) }

    // Keyed on the open budget: a switch cancels this read, so a stale result
    // can't clear the incoming budget's default page.
    LaunchedEffect(store.currentBudgetId, store.databaseForLogger) {
        val database = store.databaseForLogger
        if (database == null) {
            dashboardPages = emptyList()
            return@LaunchedEffect
        }
        // A failed read isn't "no pages": bail rather than clear the default.
        val pages = try {
            database.fetchDashboardPages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@LaunchedEffect
        }
        dashboardPages = pages
        // A default naming a deleted page has nothing to match in the picker,
        // and Reports already falls back to the first page.
        store.defaultDashboardPageId?.let { id -> if (pages.none { it.id == id }) store.defaultDashboardPageId = null }
    }

    Scaffold(topBar = { AppTopBar(L("Display")) }) { padding ->
        FormColumn(padding) {
            FormSection(
                L("Amount Formatting"),
                if (store.currencyCode.isNotEmpty()) L("Symbol Only shows amounts with just the currency symbol — $ instead of NZ$.") else null,
            ) {
                // Empty code = no currency, matching Actual's defaultCurrencyCode
                // convention. Amounts render as plain numbers.
                FormPickerRow(
                    L("Currency"),
                    listOf("") + currencyOptions.map { it.code },
                    store.currencyCode,
                    { code -> currencyOptions.firstOrNull { it.code == code }?.let { "${it.symbol} ${it.code}" } ?: L("None") },
                    // Persisted into the budget's own preferences, not just
                    // locally, so it survives a relaunch (GH #59).
                    { scope.launch { store.setCurrencyCode(it) } },
                )
                FormPickerRow(L("Number Formatting"), ActualNumberFormat.entries, store.numberFormat, { it.example }, { scope.launch { store.setNumberFormat(it) } })
                // Meaningless with no currency selected, so hidden there.
                if (store.currencyCode.isNotEmpty()) {
                    FormToggleRow(L("Symbol Only"), store.useNarrowCurrencySymbol, { store.useNarrowCurrencySymbol = it })
                }
            }
            FormSection(L("Appearance")) {
                FormPickerRow(L("Appearance"), AppearanceMode.entries, store.appearanceMode, { it.label() }, { store.appearanceMode = it })
            }
            FormSection(L("App Navigation"), L("Start Page takes effect the next time the app opens.")) {
                FormPickerRow(L("Start Page"), StartTab.entries, store.startTab, { it.label() }, { store.startTab = it })
                // Nothing to choose between with one page.
                if (dashboardPages.size > 1) {
                    FormPickerRow(
                        L("Default Dashboard"),
                        listOf(null) + dashboardPages.map { it.id },
                        store.defaultDashboardPageId,
                        { id -> if (id == null) L("First Dashboard") else dashboardPages.firstOrNull { it.id == id }?.name?.ifEmpty { L("Untitled") } ?: L("Untitled") },
                        { store.defaultDashboardPageId = it },
                    )
                }
            }
        }
    }
}
