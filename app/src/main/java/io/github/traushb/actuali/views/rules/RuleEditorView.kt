package io.github.traushb.actuali.views.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.RuleValue
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AmountParser
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.rules.RuleFieldType
import io.github.traushb.actuali.services.rules.RuleSchema
import io.github.traushb.actuali.services.saveRule
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.FormAmountRow
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.FormSheet
import io.github.traushb.actuali.views.components.FormTextFieldRow
import io.github.traushb.actuali.views.components.FormToggleRow
import io.github.traushb.actuali.views.components.FullScreenSheet
import io.github.traushb.actuali.views.components.MessageAlert
import io.github.traushb.actuali.views.components.listTile
import io.github.traushb.actuali.views.negative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** The rule editor's pure rules: value shapes, labels, and the multi-picker's toggle. */
object RuleEditorLogic {
    val valuelessOps = setOf("onBudget", "offBudget")
    private val multiOps = setOf("oneOf", "notOneOf")

    /** The empty value for a freshly chosen field/op pair. */
    fun defaultValue(field: String, op: String): RuleValue = when {
        op in multiOps -> RuleValue.ListValue(emptyList())
        op == "isbetween" -> RuleValue.Obj(mapOf("num1" to RuleValue.Num(0.0), "num2" to RuleValue.Num(0.0)))
        else -> when (RuleSchema.fieldType(field)) {
            RuleFieldType.Num -> RuleValue.Num(0.0)
            RuleFieldType.Bool -> RuleValue.Bool(true)
            RuleFieldType.Date, RuleFieldType.Str -> RuleValue.Str("")
            RuleFieldType.Id, null -> RuleValue.Null
        }
    }

    /** Changing the field can invalidate the operator and the value shape, so both reset as the web editor does. */
    fun withField(condition: Rule.Condition, field: String): Rule.Condition {
        val op = if (RuleSchema.isValidOp(field, condition.op)) condition.op else RuleSchema.validOps(field).firstOrNull() ?: "is"
        // Inflow/outflow only means anything on an amount.
        val options = if (RuleSchema.fieldType(field) == RuleFieldType.Num) condition.options else null
        return condition.copy(field = field, op = op, value = defaultValue(field, op), options = options)
    }

    fun withOp(condition: Rule.Condition, op: String): Rule.Condition {
        val reshape = (condition.op in multiOps) != (op in multiOps) || (condition.op == "isbetween") != (op == "isbetween") || condition.op in valuelessOps
        val value = when {
            op in valuelessOps -> RuleValue.Null
            reshape -> defaultValue(condition.field, op)
            else -> condition.value
        }
        return condition.copy(op = op, value = value)
    }

    fun withActionOp(action: Rule.Action, op: String): Rule.Action = when (op) {
        "set" -> action.copy(op = op, field = action.field ?: "category", value = RuleValue.Null)
        "delete-transaction" -> action.copy(op = op, field = null, value = RuleValue.Null)
        else -> action.copy(op = op, field = "notes", value = RuleValue.Str(""))
    }

    fun fieldLabel(field: String, locale: Locale = Locale.getDefault()): String = RuleSchema.sentenceCased(RuleSchema.label(field, locale = locale), locale)

    fun operatorLabel(op: String, field: String? = null, locale: Locale = Locale.getDefault()): String {
        val label = RuleSchema.opLabel(op, field?.let(RuleSchema::fieldType), locale)
        return if (field == null) RuleSchema.sentenceCased(label, locale) else label
    }

    /**
     * Toggle [id], keeping visible ids in the choice list's order so the rule
     * reads the same each round trip — and keeping ids the picker can't show
     * (hidden categories, closed accounts, other clients' rules), which an
     * unrelated toggle mustn't drop.
     */
    fun toggling(id: String, value: RuleValue, visibleIds: List<String>): RuleValue {
        val current = value.listValue.orEmpty().mapNotNull { it.stringValue }
        val ids = current.toMutableSet().apply { if (!add(id)) remove(id) }
        val visible = visibleIds.filter(ids::contains)
        val hidden = current.filter { it in ids && it !in visibleIds }
        return RuleValue.ListValue((visible + hidden).map { RuleValue.Str(it) })
    }

    /**
     * The pickable rows for an id-typed field. Transfer payees and hidden
     * categories are filtered out as the transaction form filters them.
     */
    fun choices(field: String, store: BudgetStore): List<Pair<String, String>> = when (field) {
        "payee" -> store.payees.filter { !it.tombstone && it.transferAccountId == null }.map { it.id to it.name }
        "category" -> store.categoryGroups.filter { !it.hidden }.flatMap { it.categories }.filter { !it.hidden }.map { it.id to it.name }
        "category_group" -> store.categoryGroups.filter { !it.hidden }.map { it.id to it.name }
        "account" -> store.accounts.filter { !it.closed }.map { it.id to it.name }
        else -> emptyList()
    }
}

private val actionOps = listOf("set", "prepend-notes", "append-notes", "delete-transaction")

/** Create or edit a rule, in the web editor's shape: a stage, all/any, conditions and actions. */
@Composable
fun RuleEditorView(rule: Rule, onDismiss: () -> Unit) {
    val store = LocalBudgetStore.current
    val scope = rememberCoroutineScope()
    val isNew = rule.conditions.isEmpty() && rule.actions.isEmpty()
    var stage by remember { mutableStateOf(rule.stage) }
    var conditionsOp by remember { mutableStateOf(rule.conditionsOp) }
    val conditions = remember { mutableStateListOf(*rule.conditions.toTypedArray()) }
    val actions = remember { mutableStateListOf(*rule.actions.toTypedArray()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    FormSheet(
        L(if (isNew) "New Rule" else "Edit Rule"),
        onDismiss = { if (!saving) onDismiss() },
        confirmEnabled = !saving && conditions.isNotEmpty() && actions.isNotEmpty(),
        onConfirm = {
            saving = true
            scope.launch {
                try {
                    store.saveRule(rule.copy(stage = stage, conditionsOp = conditionsOp, conditions = conditions.toList(), actions = actions.toList()))
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.localizedMessage
                }
                saving = false
            }
        },
    ) {
        FormSection(footer = L("Pre rules run before everything else, Post rules run last. Most rules belong in Default.")) {
            FormSegmentedRow(Rule.Stage.entries, stage, { it.label() }, { stage = it })
        }
        FormSection(L("If")) {
            FormPickerRow(
                L("Match"),
                Rule.ConditionsOp.entries,
                conditionsOp,
                { L(if (it == Rule.ConditionsOp.And) "all conditions" else "any condition") },
                { conditionsOp = it },
            )
            conditions.forEachIndexed { index, condition ->
                ConditionEditor(condition, { conditions[index] = it }) { conditions.removeAt(index) }
            }
            FormRow(L("Add Condition"), leading = { Icon(Icons.Filled.Add, contentDescription = null) }, onClick = {
                conditions.add(Rule.Condition("is", "imported_payee", RuleValue.Str("")))
            })
        }
        FormSection(L("Then")) {
            actions.forEachIndexed { index, action ->
                ActionEditor(action, { actions[index] = it }) { actions.removeAt(index) }
            }
            FormRow(L("Add Action"), leading = { Icon(Icons.Filled.Add, contentDescription = null) }, onClick = {
                actions.add(Rule.Action("set", "category", RuleValue.Null))
            })
        }
    }
    MessageAlert(L("Couldn't Save Rule"), failure) { failure = null }
}

/** One condition: field, operator, and a value editor shaped by the field's type. */
@Composable
private fun ConditionEditor(condition: Rule.Condition, onChange: (Rule.Condition) -> Unit, onDelete: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FormPickerRow(L("Field"), RuleSchema.conditionFields, condition.field, { RuleEditorLogic.fieldLabel(it) }, { onChange(RuleEditorLogic.withField(condition, it)) })
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = L("Delete"), tint = MaterialTheme.colorScheme.negative) }
        }
        FormPickerRow(L("Operator"), RuleSchema.validOps(condition.field), condition.op, { RuleEditorLogic.operatorLabel(it, condition.field) }, { onChange(RuleEditorLogic.withOp(condition, it)) })
        // onBudget/offBudget read the account, not a value; upstream hides the value input for them.
        if (condition.op !in RuleEditorLogic.valuelessOps) {
            RuleValueEditor(condition.value, condition.field, condition.op, condition.options, showsDirection = true, { onChange(condition.copy(value = it)) }) { onChange(condition.copy(options = it)) }
        }
    }
}

/** One action: `set <field> to <value>`, the note ops, or delete. */
@Composable
private fun ActionEditor(action: Rule.Action, onChange: (Rule.Action) -> Unit, onDelete: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FormPickerRow(L("Action"), actionOps, action.op, { RuleEditorLogic.operatorLabel(it) }, { onChange(RuleEditorLogic.withActionOp(action, it)) })
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = L("Delete"), tint = MaterialTheme.colorScheme.negative) }
        }
        when (action.op) {
            "set" -> {
                val field = action.field ?: "category"
                FormPickerRow(L("Field"), RuleSchema.actionFields, field, { RuleEditorLogic.fieldLabel(it) }, {
                    onChange(action.copy(field = it, value = RuleEditorLogic.defaultValue(it, "is")))
                })
                // `is`: an action's value is always single; between and multi shapes are condition-only, and a set has no direction.
                RuleValueEditor(action.value, field, "is", action.options, showsDirection = false, { onChange(action.copy(value = it)) }) { onChange(action.copy(options = it)) }
            }
            "delete-transaction" -> Unit
            else -> FormTextFieldRow(action.value.stringValue.orEmpty(), { onChange(action.copy(value = RuleValue.Str(it))) }, L("Text"))
        }
    }
}

/** The value half of a condition or action, shaped by the field's type. */
@Composable
private fun RuleValueEditor(
    value: RuleValue,
    field: String,
    op: String,
    options: Map<String, RuleValue>?,
    showsDirection: Boolean,
    onValue: (RuleValue) -> Unit,
    onOptions: (Map<String, RuleValue>?) -> Unit,
) {
    val store = LocalBudgetStore.current
    val isMulti = op == "oneOf" || op == "notOneOf"
    var picking by remember { mutableStateOf(false) }
    when (RuleSchema.fieldType(field)) {
        RuleFieldType.Id -> if (isMulti) {
            FormRow(L("Values"), value = L("%lld selected", value.listValue.orEmpty().size.toLong()), navigates = true, onClick = { picking = true })
        } else {
            val choices = RuleEditorLogic.choices(field, store)
            FormPickerRow(
                L("Value"),
                listOf("") + choices.map { it.first },
                value.stringValue.orEmpty(),
                { id -> if (id.isEmpty()) L("Nothing") else choices.firstOrNull { it.first == id }?.second ?: id },
                { onValue(if (it.isEmpty()) RuleValue.Null else RuleValue.Str(it)) },
            )
        }
        RuleFieldType.Num -> {
            if (op == "isbetween") {
                // A between value is a {num1, num2} object; writing an endpoint rebuilds it, so a malformed value self-heals.
                fun endpoint(key: String) = (value as? RuleValue.Obj)?.fields?.get(key) ?: RuleValue.Null
                fun setEndpoint(key: String, new: RuleValue) {
                    val fields = (value as? RuleValue.Obj)?.fields.orEmpty().toMutableMap()
                    fields[key] = new
                    fields.putIfAbsent("num1", RuleValue.Num(0.0))
                    fields.putIfAbsent("num2", RuleValue.Num(0.0))
                    onValue(RuleValue.Obj(fields))
                }
                RuleAmountField(L("From"), endpoint("num1")) { setEndpoint("num1", it) }
                RuleAmountField(L("To"), endpoint("num2")) { setEndpoint("num2", it) }
            } else {
                RuleAmountField(L("Amount"), value, onValue)
            }
            // Upstream has amount (inflow) and amount (outflow) fields; here they're a direction on the amount row.
            if (showsDirection) {
                val direction = when {
                    options?.get("inflow")?.boolValue == true -> "inflow"
                    options?.get("outflow")?.boolValue == true -> "outflow"
                    else -> "any"
                }
                FormSegmentedRow(listOf("any", "inflow", "outflow"), direction, {
                    L(
                        when (it) {
                            "inflow" -> "Inflow"
                            "outflow" -> "Outflow"
                            else -> "Any"
                        },
                    )
                }, {
                    onOptions(
                        when (it) {
                            "inflow" -> mapOf("inflow" to RuleValue.Bool(true))
                            "outflow" -> mapOf("outflow" to RuleValue.Bool(true))
                            else -> null
                        },
                    )
                })
            }
        }
        RuleFieldType.Date -> {
            val day = value.stringValue?.replace("-", "")?.toIntOrNull()?.takeIf { it > 9_999_999 }
            FormDateRow(L("Date"), day?.let(Transaction::date) ?: LocalDate.now(), { date ->
                onValue(RuleValue.Str("%04d-%02d-%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)))
            })
        }
        RuleFieldType.Bool -> FormToggleRow(L("Value"), value.boolValue ?: false, { onValue(RuleValue.Bool(it)) })
        RuleFieldType.Str, null -> {
            val placeholder = L(
                when {
                    op == "matches" -> "Regular expression"
                    isMulti -> "Comma-separated values"
                    else -> "Value"
                },
            )
            val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            if (isMulti) {
                // oneOf/notOneOf carry a list; comma-separated text keeps the row one field while producing the shape the engine matches.
                FormTextFieldRow(
                    value.listValue.orEmpty().mapNotNull { it.stringValue }.joinToString(", "),
                    { text -> onValue(RuleValue.ListValue(text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { RuleValue.Str(it) })) },
                    placeholder,
                    keyboardOptions = plain,
                )
            } else {
                FormTextFieldRow(value.stringValue.orEmpty(), { onValue(RuleValue.Str(it)) }, placeholder, keyboardOptions = plain)
            }
            // The engine lowercases patterns to match upstream; saying the match is case-insensitive is the part that surprises people.
            if (op == "matches") {
                Text(L("Patterns are matched case-insensitively."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    }
    if (picking) {
        FullScreenSheet({ picking = false }) { RuleIdMultiPicker(field, value, onValue) { picking = false } }
    }
}

/**
 * Cents entry for a rule amount. Holds its own text and commits through the
 * amount parser, so partial input ("10.", "1,50") isn't destroyed
 * mid-keystroke; an unparseable entry leaves the stored value alone.
 */
@Composable
private fun RuleAmountField(label: String, value: RuleValue, onValue: (RuleValue) -> Unit) {
    var text by remember { mutableStateOf(value.numberValue?.let { "%.2f".format(Locale.ROOT, it / 100) }.orEmpty()) }
    FormAmountRow(label, text, { new ->
        text = new
        AmountParser.parse(new)?.let(Transaction::cents)?.let { onValue(RuleValue.Num(it.toDouble())) }
    }, allowsNegative = true)
}

/** Multi-select for oneOf / notOneOf id conditions. */
@Composable
private fun RuleIdMultiPicker(field: String, value: RuleValue, onValue: (RuleValue) -> Unit, onClose: () -> Unit) {
    val store = LocalBudgetStore.current
    val choices = RuleEditorLogic.choices(field, store)
    val selected = value.listValue.orEmpty().mapNotNull { it.stringValue }.toSet()
    Scaffold(topBar = { AppTopBar(RuleEditorLogic.fieldLabel(field), onBack = onClose) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(choices, key = { _, it -> it.first }) { index, (id, name) ->
                FormRow(
                    name,
                    Modifier.listTile(index, choices.size),
                    trailing = { if (id in selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onValue(RuleEditorLogic.toggling(id, value, choices.map { it.first })) },
                )
            }
        }
    }
}
