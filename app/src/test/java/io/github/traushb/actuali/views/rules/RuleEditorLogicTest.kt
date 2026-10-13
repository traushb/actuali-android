package io.github.traushb.actuali.views.rules

import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.RuleValue
import io.github.traushb.actuali.services.ReportStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The multi-picker's toggle logic and the editor's reshaping rules. The
 * picker lists only live, visible entities, but a rule can reference hidden
 * categories, closed accounts, or ids authored on another client — toggling
 * one item must never drop them.
 */
class RuleEditorLogicTest {
    private val visible = listOf("cat-a", "cat-b", "cat-c")

    private fun ids(vararg values: String) = RuleValue.ListValue(values.map { RuleValue.Str(it) })

    @Test
    fun togglingAddsAndRemovesAVisibleId() {
        val added = RuleEditorLogic.toggling("cat-b", ids(), visible)
        assertEquals(ids("cat-b"), added)
        assertEquals(ids(), RuleEditorLogic.toggling("cat-b", added, visible))
    }

    @Test
    fun togglingKeepsVisibleIdsInChoiceOrder() {
        var value: RuleValue = ids()
        for (id in listOf("cat-c", "cat-a")) value = RuleEditorLogic.toggling(id, value, visible)
        assertEquals(ids("cat-a", "cat-c"), value)
    }

    @Test
    fun togglingPreservesIdsThePickerCannotDisplay() {
        val toggled = RuleEditorLogic.toggling("cat-b", ids("hidden-cat", "cat-a"), visible)
        assertEquals(ids("cat-a", "cat-b", "hidden-cat"), toggled)
        // A hidden id can still be removed explicitly.
        assertEquals(ids("cat-a", "cat-b"), RuleEditorLogic.toggling("hidden-cat", toggled, visible))
    }

    /** French and Portuguese count zero as singular. */
    @Test
    fun selectedCountUsesEachLanguagesPluralForms() {
        val expected = mapOf(
            Locale.US to listOf("0 selected", "1 selected", "2 selected"),
            Locale.FRANCE to listOf("0 sélectionné", "1 sélectionné", "2 sélectionnés"),
            Locale.forLanguageTag("pt-BR") to listOf("0 selecionado", "1 selecionado", "2 selecionados"),
        )
        for ((locale, values) in expected) {
            values.forEachIndexed { count, value -> assertEquals(value, ReportStrings.localized("%lld selected", count.toLong(), locale = locale)) }
        }
    }

    @Test
    fun editorLabelsUseRequestedLocale() {
        assertEquals("Groupe de catégories", RuleEditorLogic.fieldLabel("category_group", Locale.FRANCE))
        assertEquals("est après", RuleEditorLogic.operatorLabel("gt", "date", Locale.FRANCE))
        assertEquals("Définir", RuleEditorLogic.operatorLabel("set", locale = Locale.FRANCE))
    }

    /** Changing an operator between single and multi value reshapes the value, as the web editor does. */
    @Test
    fun switchingToAMultiValueOpResetsTheValue() {
        val condition = Rule.Condition(op = "is", field = "category", value = RuleValue.Str("cat-a"))
        assertEquals(ids(), RuleEditorLogic.withOp(condition, "oneOf").value)
        assertEquals(RuleValue.Null, RuleEditorLogic.withOp(condition, "onBudget").value)
        assertEquals(RuleValue.Str("cat-a"), RuleEditorLogic.withOp(condition, "isNot").value)
    }

    @Test
    fun betweenStartsFromAZeroRange() =
        assertEquals(RuleValue.Obj(mapOf("num1" to RuleValue.Num(0.0), "num2" to RuleValue.Num(0.0))), RuleEditorLogic.defaultValue("amount", "isbetween"))
}
