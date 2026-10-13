package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.PendingImport
import io.github.traushb.actuali.support.cleanup
import io.github.traushb.actuali.support.tempDir
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingImportStoreTest {
    private val dir = tempDir()
    private val file = File(dir, "pending_imports.json")

    @After
    fun tearDown() = cleanup(dir)

    private fun write(imports: List<PendingImport>) = file.writeText(AppJson.encodeToString(ListSerializer(PendingImport.serializer()), imports))

    @Test
    fun addAndRemove() {
        val store = PendingImportStore(file)
        val item = PendingImport(amount = 10.0, payee = "Test", rawText = "test")
        store.add(item)
        assertEquals(1, store.count)
        assertEquals("Test", store.imports.first().payee)

        store.remove(item.id)
        assertEquals(0, store.count)
    }

    @Test
    fun removeAll() {
        val store = PendingImportStore(file)
        store.add(PendingImport(amount = 1.0, rawText = "a"))
        store.add(PendingImport(amount = 2.0, rawText = "b"))
        assertEquals(2, store.count)

        store.removeAll()
        assertEquals(0, store.count)
    }

    @Test
    fun failedSaveDoesNotMutateTheQueue() {
        val directory = File(dir, "queue").apply { mkdirs() }
        val store = PendingImportStore(directory)
        assertThrows(PendingImportStore.StoreError::class.java) { store.add(PendingImport(amount = 1.0)) }
        assertTrue(store.imports.isEmpty())
    }

    @Test
    fun persistsAcrossInstances() {
        val store1 = PendingImportStore(file)
        store1.add(PendingImport(originBudgetId = "budget-a", amount = 42.0, payee = "Persisted", rawText = "msg"))
        assertEquals(1, store1.count)

        val store2 = PendingImportStore(file)
        assertEquals(1, store2.count)
        assertEquals("Persisted", store2.imports.first().payee)
        assertEquals("budget-a", store2.imports.first().originBudgetId)
    }

    private fun decodeWithout(item: PendingImport, key: String): PendingImport {
        val json = AppJson.encodeToJsonElement(PendingImport.serializer(), item).jsonObject
        return AppJson.decodeFromJsonElement(PendingImport.serializer(), JsonObject(json - key))
    }

    @Test
    fun legacyRecordWithoutOriginBudgetIdStillDecodes() {
        val item = PendingImport(originBudgetId = "x", amount = 42.0, payee = "Legacy", rawText = "msg")
        val decoded = decodeWithout(item, "originBudgetId")
        assertEquals(item.id, decoded.id)
        assertNull(decoded.originBudgetId)
        assertEquals("Legacy", decoded.payee)
    }

    @Test
    fun legacyRecordWithoutSourceCurrencyStillDecodesAsAmbiguous() =
        assertNull(decodeWithout(PendingImport(amount = 42.0, sourceCurrencyCode = "USD", payee = "Legacy", rawText = "msg"), "sourceCurrencyCode").sourceCurrencyCode)

    @Test
    fun visibleImportsKeepAllBudgetsVisibleForReview() {
        val store = PendingImportStore(file)
        val current = PendingImport(originBudgetId = "budget-a", amount = 1.0)
        val other = PendingImport(originBudgetId = "budget-b", amount = 2.0)
        val legacy = PendingImport(amount = 3.0)
        listOf(current, other, legacy).forEach(store::add)
        assertEquals(setOf(current.id, other.id, legacy.id), store.visibleImports().map { it.id }.toSet())
    }

    @Test
    fun recoversMalformedFileAndPersistsSubsequentImport() {
        file.writeText("{ malformed")
        val recovered = PendingImportStore(file)

        assertTrue(recovered.imports.isEmpty())
        val backups = dir.listFiles().orEmpty().filter { it.name.startsWith("pending_imports.corrupt-") }
        assertEquals(1, backups.size)
        assertEquals("{ malformed", backups[0].readText())

        recovered.add(PendingImport(amount = 7.0, payee = "After Recovery", rawText = "msg"))
        assertEquals("After Recovery", PendingImportStore(file).imports.first().payee)
    }

    @Test
    fun healsScrambledCardHintOnLoad() {
        write(listOf(PendingImport(cardHint = "1964", rawText = "Rs 1,234.00 spent on Sample Bank Card XX6419 on 01-01-2026 at Coffee Shop.")))
        val loaded = PendingImportStore(file)
        assertEquals(1, loaded.imports.size)
        assertEquals("6419", loaded.imports[0].cardHint)
    }

    @Test
    fun keepsGroundedCardHintOnLoad() {
        // The regex alone would pick 1111; a stored hint the text contains must survive.
        write(listOf(PendingImport(cardHint = "6419", rawText = "Card XX1111 was replaced. Rs 500 charged on card XX6419")))
        assertEquals("6419", PendingImportStore(file).imports.first().cardHint)
    }
}
