package io.github.traushb.actuali.views.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.LocalBudgetStore

/**
 * The app's amount field: a decimal keypad over the [AmountEntry]
 * calculator, with the sign and `+ − × ÷` keys on the keyboard's accessory
 * bar while it has focus (Android keyboards have neither). [text] is always
 * a plain decimal.
 * [onToggleSign] takes the ± key for callers whose sign is separate state.
 */
@Composable
fun AmountInputField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    conventionalAmountEntry: Boolean = false,
    allowsNegative: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign = TextAlign.End,
    autofocus: Boolean = false,
    onToggleSign: (() -> Unit)? = null,
    signToggleEnabled: Boolean = true,
) {
    val store = LocalBudgetStore.current
    val entry = remember(conventionalAmountEntry, allowsNegative) { AmountEntry(store.numberFormat, conventionalAmountEntry, allowsNegative).apply { sync(text) } }
    val field = rememberTextFieldState(entry.fieldText)
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val publish by rememberUpdatedState { if (entry.boundText != text) onTextChange(entry.boundText) }
    val showsSign = allowsNegative || onToggleSign != null
    val currentOnToggleSign by rememberUpdatedState(onToggleSign)
    val currentSignToggleEnabled by rememberUpdatedState(signToggleEnabled)

    // Only a change from outside the field resets it: mid-expression the field
    // reads "12.50 + 6.00" while the form holds "18.50", and that's expected.
    // A side effect runs in the frame that composed [text]; a launched effect
    // can run after the next keystroke, see this keystroke's echo as stale,
    // and resync over it — fast typing lost digits ("1250" read 1.25).
    SideEffect {
        if (text == entry.lastPublishedText) return@SideEffect
        entry.sync(text)
        field.setTextAndPlaceCursorAtEnd(entry.fieldText)
    }
    LaunchedEffect(store.numberFormat) {
        if (entry.numberFormat == store.numberFormat) return@LaunchedEffect
        entry.numberFormat = store.numberFormat
        field.setTextAndPlaceCursorAtEnd(entry.fieldText)
    }
    LaunchedEffect(autofocus) { if (autofocus) focusRequester.requestFocus() }

    fun render(state: TextFieldState) {
        state.setTextAndPlaceCursorAtEnd(entry.fieldText)
        publish()
    }

    // Each keystroke reaches the calculator as it happens: the typed text, a
    // backspace, or a paste over a selection — then the field shows the
    // calculator's own rendering, cursor at the end.
    val calculator = remember(entry) {
        InputTransformation {
            val before = originalText.toString()
            val after = toString()
            val replacesAll = before.isNotEmpty() && originalSelection.min == 0 && originalSelection.max == before.length
            val typed = when {
                replacesAll -> after
                after.startsWith(before) -> after.substring(before.length)
                before.startsWith(after) -> ""
                // An edit away from the end (the cursor was moved): what was inserted there.
                else -> {
                    val prefix = before.commonPrefixWith(after).length
                    val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
                    after.substring(prefix, after.length - suffix)
                }
            }
            entry.edit(typed, replacesAll)
            replace(0, length, entry.fieldText)
            placeCursorAtEnd()
            publish()
        }
    }

    // The sign and operator keys ride on the keyboard, as iOS's accessory toolbar.
    if (focused) {
        val accessory = LocalKeyboardAccessory.current
        val bar: @Composable () -> Unit = remember(entry, showsSign) {
            {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showsSign) {
                        IconButton(
                            onClick = {
                                val toggle = currentOnToggleSign
                                if (toggle != null) {
                                    toggle()
                                } else {
                                    entry.toggleSign()
                                    render(field)
                                }
                            },
                            enabled = currentSignToggleEnabled,
                        ) { Text("±", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = L("Flip sign") }) }
                    }
                    for (op in AmountEntry.Operator.entries) {
                        IconButton(onClick = {
                            entry.pushOperator(op)
                            render(field)
                        }) {
                            when (op) {
                                AmountEntry.Operator.Add -> Icon(Icons.Filled.Add, contentDescription = L("Add"))
                                AmountEntry.Operator.Subtract -> Icon(Icons.Filled.Remove, contentDescription = L("Subtract"))
                                AmountEntry.Operator.Multiply -> Text("×", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = L("Multiply") })
                                AmountEntry.Operator.Divide -> Text("÷", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = L("Divide") })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { focusManager.clearFocus() }) { Text(L("Done"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        DisposableEffect(accessory, bar) {
            accessory.content = bar
            onDispose { if (accessory.content === bar) accessory.content = null }
        }
    }

    BasicTextField(
        state = field,
        inputTransformation = calculator,
        textStyle = textStyle.copy(textAlign = textAlign, color = textStyle.color.takeOrElse { MaterialTheme.colorScheme.onSurface }),
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        onKeyboardAction = { focusManager.clearFocus() },
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused == focused) return@onFocusChanged
                focused = state.isFocused
                if (state.isFocused) {
                    // The first keystroke replaces what's there.
                    field.edit { selectAll() }
                } else {
                    // Leaving the field always leaves a plain amount, never a half-finished expression.
                    entry.finalizeExpression()
                    render(field)
                }
            },
        decorator = { inner ->
            Box(contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterEnd) {
                if (field.text.isEmpty()) {
                    Text(
                        store.numberFormat.format(0.0, wholeUnits = conventionalAmountEntry, currencyCode = null),
                        style = textStyle.copy(textAlign = textAlign),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}
