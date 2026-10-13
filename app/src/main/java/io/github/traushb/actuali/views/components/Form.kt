package io.github.traushb.actuali.views.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.database.BudgetMetadata
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalLargeTopBar
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.LocalTopBarScrollBehavior
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The building blocks of SwiftUI's `Form`, in Material 3 Expressive dress: a
 * title bar, grouped sections of tiles with a header and footer, and the row
 * kinds the iOS forms use (text, toggle, picker, date, stepper, button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}, onBack: (() -> Unit)? = null, subtitle: String? = null) {
    val stack = LocalNavStack.current
    val dismiss = LocalSheetDismiss.current
    val back = onBack ?: stack.takeIf { dismiss == null && it.entries.size > 1 }?.let { { it.pop() } }
    val navigationIcon: @Composable () -> Unit = {
        when {
            back != null -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L("Back")) }
            dismiss != null -> IconButton(onClick = dismiss) { Icon(Icons.Filled.Close, contentDescription = L("Close")) }
        }
    }
    val behavior = LocalTopBarScrollBehavior.current
    if (LocalLargeTopBar.current) {
        LargeFlexibleTopAppBar(
            title = { Text(title, maxLines = 2) },
            subtitle = subtitle?.let { { Text(it, maxLines = 1) } },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = pageTopBarColors(),
            scrollBehavior = behavior,
        )
    } else {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = navigationIcon, actions = actions, colors = pageTopBarColors(), scrollBehavior = behavior)
    }
}

/** The open budget file's name for a title's subtitle, read off the main thread from its metadata; null until it lands. */
@Composable
fun rememberOpenBudgetName(): String? {
    val id = LocalBudgetStore.current.currentBudgetId
    return produceState<String?>(null, id) {
        value = id?.let { withContext(Dispatchers.IO) { runCatching { BudgetFileManager.shared.metadataPath(it).readBytes() }.getOrNull()?.let(BudgetMetadata::decode)?.budgetName } }
    }.value
}

/** A top bar in the page's own tone, a step up once content scrolls under it — the default `surface` would band the tinted page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun pageTopBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

/**
 * A scrolling column of [FormSection]s inside the scaffold's [padding]. It
 * shrinks to the space above the keyboard and its accessory bar, so a focused
 * field scrolls into view instead of sitting under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormColumn(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val accessoryShowing = LocalKeyboardAccessory.current.content != null && WindowInsets.isImeVisible
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .padding(bottom = if (accessoryShowing) KeyboardAccessoryHeight else 0.dp)
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** A titled group of rows, each its own tile ([SegmentedTiles]), with an explanatory footer and optional header buttons. */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null || headerActions != null) {
            Row(Modifier.padding(start = 16.dp, bottom = if (headerActions == null) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(header.orEmpty(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                headerActions?.invoke(this)
            }
        }
        SegmentedTiles(content = content)
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

// Expressive's segmented list: a group's outer corners large, the joins extra-small, the spec's gap between.
private val TileOuterCorner = 16.dp
private val TileInnerCorner = 4.dp
private val TileGap = ListItemDefaults.SegmentedGap

/** The tile shape at [index] of [count]: the group's outer corners large, the joins between tiles small. */
fun segmentedTileShape(index: Int, count: Int): Shape {
    val top = if (index == 0) TileOuterCorner else TileInnerCorner
    val bottom = if (index == count - 1) TileOuterCorner else TileInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A lazy list's group header, lined up with the tiles beneath it and with [FormSection]'s. */
@Composable
fun ListSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp))
}

/** A full-width lazy list row as one tile of a segmented group, inset from the screen edges like a [FormSection]. */
@Composable
fun Modifier.listTile(index: Int, count: Int): Modifier = padding(horizontal = 16.dp).segmentedTile(index, count)

/** A lazy list item as one tile of a segmented group: its shape, its fill, and the gap below it. */
@Composable
fun Modifier.segmentedTile(index: Int, count: Int): Modifier {
    val shape = segmentedTileShape(index, count)
    return padding(bottom = if (index < count - 1) TileGap else 0.dp).clip(shape).background(MaterialTheme.colorScheme.surface, shape)
}

/**
 * Android 16's segmented list: each child the content emits is its own tile —
 * the group's outer corners large, the joins small, a hairline gap between —
 * so a section reads as one group without divider lines. Each tile is
 * clipped to its shape, so a pressed row's ripple keeps the corners.
 */
@Composable
private fun SegmentedTiles(content: @Composable () -> Unit) {
    // The segmented list item's container role, on the page's surfaceContainer.
    val color = MaterialTheme.colorScheme.surface
    // Where the tiles landed at the last placement, for drawing their fills behind them.
    var tiles by remember { mutableStateOf(emptyList<Pair<IntRect, Shape>>()) }
    Layout(
        content = content,
        modifier = Modifier.fillMaxWidth().drawBehind {
            for ((rect, shape) in tiles) {
                val outline = shape.createOutline(Size(rect.width.toFloat(), rect.height.toFloat()), layoutDirection, this)
                translate(rect.left.toFloat(), rect.top.toFloat()) { drawOutline(outline, color) }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }.filter { it.height > 0 }
        val gap = TileGap.roundToPx()
        val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            val placed = placeables.mapIndexed { index, placeable ->
                val shape = segmentedTileShape(index, placeables.size)
                placeable.placeWithLayer(0, y) {
                    this.shape = shape
                    clip = true
                }
                (IntRect(0, y, width, y + placeable.height) to shape).also { y += placeable.height + gap }
            }
            if (placed != tiles) tiles = placed
        }
    }
}

/** A leading icon whose accent and Expressive shape cycle with its [index] down a form, so neighbors never match. */
@Composable
fun CyclingFormIcon(icon: ImageVector, index: Int) = FormIcon(icon, role = AccentRole.entries[index % AccentRole.entries.size], shape = containerShape(index))

/** A row's leading icon in a tonal container, as Android 16's settings rows have: a circle, or one of Expressive's [shape]s. */
@Composable
fun FormIcon(icon: ImageVector, modifier: Modifier = Modifier, role: AccentRole = AccentRole.Secondary, shape: Shape = CircleShape) {
    ShapedIcon(icon, role, shape, modifier)
}

private val transparentListItem @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** A label with an optional trailing value; tappable when [onClick] is set, with a chevron when it navigates. */
@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    navigates: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp)) }
                trailing?.invoke()
                if (navigates) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = transparentListItem,
        modifier = if (onClick != null) modifier.clickable(enabled = enabled, onClick = onClick) else modifier,
    )
}

@Composable
fun FormToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = {
            Switch(
                checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }) else null,
            )
        },
        colors = transparentListItem,
        modifier = modifier.selectable(checked, enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
    )
}

/** An inline text field filling the row, as SwiftUI's `TextField` inside a `Form`. */
@Composable
fun FormTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A choice from a list: the row shows the current option, a tap opens the options as radio buttons. */
@Composable
fun <T> FormPickerRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    var showing by remember { mutableStateOf(false) }
    FormRow(label, modifier, value = optionLabel(selected), leading = leading, enabled = enabled, onClick = { showing = true })
    if (showing) {
        ChoiceDialog(label, options, selected, optionLabel, onDismiss = { showing = false }) {
            showing = false
            onSelect(it)
        }
    }
}

@Composable
fun <T> ChoiceDialog(title: String, options: List<T>, selected: T?, optionLabel: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (option in options) {
                    Row(
                        Modifier.fillMaxWidth().selectable(option == selected, role = Role.RadioButton) { onSelect(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(option == selected, onClick = null)
                        Text(optionLabel(option), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
    )
}

/** SwiftUI's `.pickerStyle(.segmented)`: a few mutually exclusive options as a connected button group. */
@Composable
fun <T> FormSegmentedRow(options: List<T>, selected: T, optionLabel: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(optionLabel(option), maxLines = 1) }
        }
    }
}

/** A date row; a tap opens the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDateRow(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    latest: LocalDate? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    var showing by remember { mutableStateOf(false) }
    FormRow(label, modifier, value = Transaction.formattedDate(Transaction.yyyymmdd(date)), leading = leading, enabled = enabled, onClick = { showing = true })
    if (showing) {
        // The picker works in UTC midnights; the date itself has no zone.
        fun millis(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = millis(date),
            selectableDates = latest?.let { last ->
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= millis(last)
                }
            } ?: DatePickerDefaults.AllDates,
        )
        DatePickerDialog(
            onDismissRequest = { showing = false },
            confirmButton = {
                TextButton({
                    showing = false
                    state.selectedDateMillis?.let { onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(L("OK")) }
            },
            dismissButton = { TextButton({ showing = false }) { Text(L("Cancel")) } },
        ) { DatePicker(state) }
    }
}

/** SwiftUI's `Stepper`: − and + around a value, clamped to [range]. */
@Composable
fun FormStepperRow(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier, valueText: String = value.toString()) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onValueChange(value - 1) }, enabled = value > range.first) { Icon(Icons.Filled.Remove, contentDescription = L("Decrement")) }
                Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onValueChange(value + 1) }, enabled = value < range.last) { Icon(Icons.Filled.Add, contentDescription = L("Increment")) }
            }
        },
        colors = transparentListItem,
        modifier = modifier,
    )
}

/** A label with an [AmountInputField] on the right — the iOS forms' `HStack { Text; Spacer; AmountInputField }`. */
@Composable
fun FormAmountRow(label: String, text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, allowsNegative: Boolean = false, fieldModifier: Modifier = Modifier) {
    val store = LocalBudgetStore.current
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            AmountInputField(
                text,
                onChange,
                fieldModifier.widthIn(min = 80.dp, max = 160.dp),
                conventionalAmountEntry = store.conventionalAmountEntry,
                allowsNegative = allowsNegative,
            )
        },
        colors = transparentListItem,
        modifier = modifier,
    )
}

/** A whole-row action, as a SwiftUI `Button` inside a `Form`; red when [destructive]. */
@Composable
fun FormButtonRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.negative
        else -> MaterialTheme.colorScheme.primary
    }
    ListItem(
        headlineContent = { Text(label, color = color) },
        colors = transparentListItem,
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/** SwiftUI's `confirmationDialog`: a question, one confirming action (red when [destructive]) and Cancel. */
@Composable
fun ConfirmationDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton({
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.negative else Color.Unspecified) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
    )
}

/** An alert with a single OK; shown while [message] is non-null. */
@Composable
fun MessageAlert(title: String, message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("OK")) } },
    )
}
