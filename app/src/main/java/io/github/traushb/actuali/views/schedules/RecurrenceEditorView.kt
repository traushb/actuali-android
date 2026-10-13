package io.github.traushb.actuali.views.schedules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.LocaleFormatting
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.RecurConfig
import io.github.traushb.actuali.services.schedules.ScheduleDescription
import io.github.traushb.actuali.services.schedules.ScheduleRecurrence
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.ChoiceDialog
import io.github.traushb.actuali.views.components.FormButtonRow
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormDateRow
import io.github.traushb.actuali.views.components.FormPickerRow
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.FormSegmentedRow
import io.github.traushb.actuali.views.components.FormStepperRow
import io.github.traushb.actuali.views.components.FormToggleRow
import kotlin.math.max

/** Editable form of a [RecurConfig], holding partial, in-progress state. */
data class RecurrenceDraft(
    val frequency: RecurConfig.Frequency = RecurConfig.Frequency.Monthly,
    val interval: Int = 1,
    val start: DayDate,
    val patterns: List<RecurConfig.Pattern> = emptyList(),
    val skipWeekend: Boolean = false,
    val weekendSolveMode: String = "before",
    val endMode: String = "never",
    val endOccurrences: Int = 1,
    val endDate: DayDate,
) {
    val config: RecurConfig
        get() = RecurConfig(
            frequency = frequency,
            interval = max(1, interval),
            start = start,
            patterns = patterns,
            skipWeekend = skipWeekend,
            weekendSolveMode = weekendSolveMode,
            endMode = endMode,
            endOccurrences = if (endMode == "after_n_occurrences") max(1, endOccurrences) else null,
            endDate = if (endMode == "on_date") endDate else null,
        )

    val supportsPatterns: Boolean get() = frequency == RecurConfig.Frequency.Monthly

    fun addingPattern(): RecurrenceDraft = copy(patterns = patterns + RecurConfig.Pattern("day", start.day))

    /**
     * Clamps the ordinal when a pattern names a weekday, matching upstream's
     * `boundedRecurrence`: switching "the 12th" to "the 12th Monday" has to
     * become "the 5th Monday", not an occurrence that never fires.
     */
    fun withPatternType(index: Int, type: String): RecurrenceDraft {
        val pattern = patterns.getOrNull(index) ?: return this
        return withPattern(index, RecurConfig.Pattern(type, clamp(type, pattern.value)))
    }

    fun withPatternValue(index: Int, value: Int): RecurrenceDraft {
        val pattern = patterns.getOrNull(index) ?: return this
        return withPattern(index, RecurConfig.Pattern(pattern.type, clamp(pattern.type, value)))
    }

    private fun withPattern(index: Int, pattern: RecurConfig.Pattern) = copy(patterns = patterns.toMutableList().also { it[index] = pattern })

    private fun clamp(type: String, value: Int) = if (type != "day" && value > MAX_WEEKDAY_ORDINAL) MAX_WEEKDAY_ORDINAL else value

    companion object {
        /** Upstream's MAX_DAY_OF_WEEK_INTERVAL — there is no 6th Friday of a month. */
        const val MAX_WEEKDAY_ORDINAL = 5

        /** The sentinel a pattern uses for "last". */
        const val LAST_VALUE = -1

        fun from(config: RecurConfig?, today: DayDate = DayDate.today()): RecurrenceDraft {
            val base = config ?: return RecurrenceDraft(start = today, endDate = today)
            return RecurrenceDraft(
                frequency = base.frequency,
                interval = max(1, base.interval),
                start = base.start,
                patterns = base.patterns,
                skipWeekend = base.skipWeekend,
                weekendSolveMode = base.weekendSolveMode,
                endMode = base.endMode,
                endOccurrences = base.endOccurrences ?: 1,
                endDate = base.endDate ?: today,
            )
        }
    }
}

private val WEEKDAY_CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

/** The recurrence sub-screen of the schedule editor; [onBack] returns to the form. */
@Composable
fun RecurrenceEditorView(draft: RecurrenceDraft, onChange: (RecurrenceDraft) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { AppTopBar(L("Repeat"), onBack = onBack) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Repeats")) {
                FormPickerRow(
                    L("Frequency"),
                    RecurConfig.Frequency.entries,
                    draft.frequency,
                    optionLabel = {
                        when (it) {
                            RecurConfig.Frequency.Daily -> L("Daily")
                            RecurConfig.Frequency.Weekly -> L("Weekly")
                            RecurConfig.Frequency.Monthly -> L("Monthly")
                            RecurConfig.Frequency.Yearly -> L("Yearly")
                        }
                    },
                    // Patterns are monthly-only; leaving them on another frequency would silently change the recurrence.
                    onSelect = { onChange(draft.copy(frequency = it, patterns = if (it == RecurConfig.Frequency.Monthly) draft.patterns else emptyList())) },
                )
                FormStepperRow(L("Every"), draft.interval, 1..365, { onChange(draft.copy(interval = it)) }, valueText = intervalLabel(draft))
                FormDateRow(L("Starting"), draft.start.localDate, { onChange(draft.copy(start = DayDate.of(it))) })
            }

            if (draft.supportsPatterns) PatternSection(draft, onChange)

            FormSection(L("Ends")) {
                FormSegmentedRow(
                    listOf("never", "after_n_occurrences", "on_date"),
                    draft.endMode,
                    optionLabel = {
                        when (it) {
                            "never" -> L("Never")
                            "after_n_occurrences" -> L("After")
                            else -> L("On Date")
                        }
                    },
                    onSelect = { onChange(draft.copy(endMode = it)) },
                )
                if (draft.endMode == "after_n_occurrences") {
                    FormStepperRow(L("Occurrences"), draft.endOccurrences, 1..999, { onChange(draft.copy(endOccurrences = it)) })
                }
                if (draft.endMode == "on_date") {
                    FormDateRow(L("End Date"), draft.endDate.localDate, { onChange(draft.copy(endDate = DayDate.of(it))) })
                }
            }

            FormSection(footer = L("An occurrence that lands on a weekend moves to the nearest weekday.")) {
                FormToggleRow(L("Skip Weekends"), draft.skipWeekend, { onChange(draft.copy(skipWeekend = it)) })
                if (draft.skipWeekend) {
                    FormPickerRow(
                        L("Move To"),
                        listOf("before", "after"),
                        draft.weekendSolveMode,
                        optionLabel = { if (it == "before") L("Friday Before") else L("Monday After") },
                        onSelect = { onChange(draft.copy(weekendSolveMode = it)) },
                    )
                }
            }

            FormSection(L("Next Dates")) { UpcomingDatesList(draft.config) }
        }
    }
}

@Composable
private fun PatternSection(draft: RecurrenceDraft, onChange: (RecurrenceDraft) -> Unit) {
    val footer = if (draft.patterns.isEmpty()) {
        L("Repeats on day %lld of the month. Add specific days to repeat more than once a month.", draft.start.day.toLong())
    } else {
        ScheduleDescription.recurring(draft.config)
    }
    FormSection(L("On These Days"), footer) {
        draft.patterns.forEachIndexed { index, pattern ->
            var choosingValue by remember { mutableStateOf(false) }
            var choosingType by remember { mutableStateOf(false) }
            // Day-of-month patterns run 1–31; an nth weekday only goes to 5.
            val values = listOf(RecurrenceDraft.LAST_VALUE) + (1..if (pattern.type == "day") 31 else RecurrenceDraft.MAX_WEEKDAY_ORDINAL).toList()
            val types = listOf("day") + WEEKDAY_CODES
            fun valueLabel(value: Int) = if (value == RecurrenceDraft.LAST_VALUE) L("Last") else LocaleFormatting.ordinal(value)
            fun typeLabel(type: String) = if (type == "day") L("Day") else ScheduleDescription.weekdayName(type)
            Row(Modifier.fillMaxWidth()) {
                FormRow(valueLabel(pattern.value), Modifier.weight(1f), onClick = { choosingValue = true })
                FormRow(typeLabel(pattern.type), Modifier.weight(1f), onClick = { choosingType = true })
                IconButton(onClick = { onChange(draft.copy(patterns = draft.patterns.filterIndexed { i, _ -> i != index })) }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = L("Delete"))
                }
            }
            if (choosingValue) {
                ChoiceDialog(L("On These Days"), values, pattern.value, ::valueLabel, onDismiss = { choosingValue = false }) {
                    choosingValue = false
                    onChange(draft.withPatternValue(index, it))
                }
            }
            if (choosingType) {
                ChoiceDialog(L("On These Days"), types, pattern.type, ::typeLabel, onDismiss = { choosingType = false }) {
                    choosingType = false
                    onChange(draft.withPatternType(index, it))
                }
            }
        }
        FormButtonRow(L("Add Day"), onClick = { onChange(draft.addingPattern()) })
    }
}

private fun intervalLabel(draft: RecurrenceDraft): String {
    val count = draft.interval.toLong()
    return when (draft.frequency) {
        RecurConfig.Frequency.Daily -> ReportStrings.localized("%lld days", count)
        RecurConfig.Frequency.Weekly -> ReportStrings.localized("%lld weeks", count)
        RecurConfig.Frequency.Monthly -> ReportStrings.localized("%lld months", count)
        RecurConfig.Frequency.Yearly -> ReportStrings.localized("%lld years", count)
    }
}

/** The next few occurrences, so a recurrence can be sanity-checked before it's saved — the web picker's preview. */
@Composable
fun UpcomingDatesList(config: RecurConfig, count: Int = 4) {
    val dates = ScheduleRecurrence.upcomingDates(config, count)
    if (dates.isEmpty()) {
        FormRow(L("This pattern has no upcoming dates."))
        return
    }
    dates.forEach { date ->
        FormRow(ScheduleDescription.mediumDate(date), value = LocaleFormatting.weekdayName(date.weekday))
    }
}
