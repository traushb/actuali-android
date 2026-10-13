package io.github.traushb.actuali.views.accounts

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.createAccount
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.components.FormToggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Accounts tab's + button, the PWA's "Add account" popup: a local
 * (manual) account, or one fed by a bank through SimpleFIN.
 */
@Composable
fun AddAccountView() {
    val nav = LocalNavStack.current
    Scaffold(topBar = { AppTopBar(L("accounts.add.title")) }) { padding ->
        FormColumn(padding) {
            FormSection {
                FormRow(L("accounts.create.local"), navigates = true, onClick = { nav.push(Route.CreateLocalAccount) })
                FormRow(L("accounts.create.linkBank"), navigates = true, onClick = { nav.push(Route.BankSyncSetup) })
            }
        }
    }
}

/**
 * The account creation form: name, on/off budget and starting balance. A
 * nonzero balance becomes a "Starting Balance" transaction — Actual has no
 * stored balance field, so the transaction is the balance.
 */
@Composable
fun CreateLocalAccountView() {
    val store = LocalBudgetStore.current
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var onBudget by remember { mutableStateOf(true) }
    var balanceText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun create() {
        // A second tap can land before the disabled state does: one tap, one account.
        if (isSaving) return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            errorMessage = L("accounts.create.nameRequired")
            return
        }
        val cents = if (balanceText.isBlank()) 0L else AmountParser.parse(balanceText)?.let(Transaction::cents) ?: run {
            errorMessage = L("accounts.create.invalidBalance")
            return
        }
        isSaving = true
        errorMessage = null
        scope.launch {
            try {
                store.createAccount(trimmedName, offBudget = !onBudget, startingBalanceCents = cents)
                // Back to the Accounts list in one step, past the Add Account menu.
                nav.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
                isSaving = false
            }
        }
    }

    Scaffold(topBar = { AppTopBar(L("accounts.create.title"), actions = { TextButton(onClick = ::create, enabled = !isSaving) { Text(L("common.create")) } }) }) { padding ->
        FormColumn(padding) {
            FormSection(footer = errorMessage) {
                FormTextFieldRow(name, { name = it }, L("accounts.create.name"), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false), enabled = !isSaving)
            }
            FormSection(footer = L("accounts.create.offBudgetHelp")) {
                FormToggleRow(L("accounts.create.onBudget"), onBudget, { onBudget = it }, enabled = !isSaving)
            }
            FormSection(footer = L("accounts.create.balanceHelp")) {
                FormAmountRow(L("accounts.create.balance"), balanceText, { balanceText = it }, allowsNegative = true)
            }
        }
    }
}
