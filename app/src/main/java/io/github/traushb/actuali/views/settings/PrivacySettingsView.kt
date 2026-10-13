package io.github.traushb.actuali.views.settings

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormToggleRow

@Composable
fun PrivacySettingsView() {
    val store = LocalBudgetStore.current
    Scaffold(topBar = { AppTopBar(L("Privacy")) }) { padding ->
        FormColumn(padding) {
            FormSection(
                L("Balance Visibility"),
                L("Hide Balances masks amounts across the app while leaving amount entry and reconciliation visible. Shake to Toggle lets you quickly mask or unmask amounts by shaking your phone. Hide Decimal Places rounds displayed amounts to whole units without changing their values."),
            ) {
                FormToggleRow(L("Hide Balances"), store.hideBalances, { store.hideBalances = it })
                FormToggleRow(L("Shake to Toggle Balances"), store.shakeToHideBalances, { store.shakeToHideBalances = it })
                FormToggleRow(L("Hide Decimal Places"), store.hideDecimalPlaces, { store.hideDecimalPlaces = it })
            }
        }
    }
}
