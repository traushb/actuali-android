package io.github.traushb.actuali.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.models.PendingImport
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val logger = Logger("PendingImportStore")

/**
 * Persists pending transaction imports as a JSON file in app storage.
 * Observable (Compose state) so the toolbar badge and review sheet react to
 * changes. Main-thread only, like the iOS @MainActor store.
 */
class PendingImportStore(private val file: File) {
    sealed class StoreError : Exception() {
        data class SaveFailed(val detail: String) : StoreError() {
            override val message: String get() = message(Locale.getDefault())

            fun message(locale: Locale): String = ReportStrings.format("Failed to save pending imports: %@", detail, locale = locale)
        }
    }

    var imports: List<PendingImport> by mutableStateOf(emptyList())
        private set

    val count: Int get() = imports.size

    fun visibleImports(): List<PendingImport> = imports

    init {
        load()
    }

    fun add(item: PendingImport) {
        val updated = listOf(item) + imports
        save(updated)
        imports = updated
        logger.info("Queued pending import ${item.id}")
    }

    fun remove(id: String) {
        val updated = imports.filter { it.id != id }
        save(updated)
        imports = updated
    }

    fun removeAll() {
        save(emptyList())
        imports = emptyList()
    }

    // Persistence

    private fun load() {
        if (!file.exists()) return
        try {
            // Heals hints a model scrambled before resolveCardHint existed. It's
            // re-derived on every load, so it doesn't need writing back.
            imports = AppJson.decodeFromString(serializer, file.readText()).map {
                if (it.rawText.isEmpty()) it else it.copy(cardHint = TransactionTextParser.resolveCardHint(it.cardHint, it.rawText))
            }
        } catch (e: Exception) {
            logger.error("Failed to load pending imports: ${e.message}")
            try {
                val backup = preserveCorruptFile()
                imports = emptyList()
                runCatching { save(emptyList()) }
                logger.error("Recovered pending imports as an empty queue; corrupt data preserved at ${backup.path}")
            } catch (e: Exception) {
                logger.error("Could not preserve corrupt pending imports; original file was left untouched: ${e.message}")
            }
        }
    }

    private fun preserveCorruptFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())
        val backup = File(file.parentFile, "${file.nameWithoutExtension}.corrupt-$stamp-${UUID.randomUUID().toString().uppercase()}.json")
        Files.copy(file.toPath(), backup.toPath())
        return backup
    }

    private fun save(imports: List<PendingImport>) {
        try {
            // Write-then-rename, so a crash can't leave a half-written queue.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(AppJson.encodeToString(serializer, imports))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to save pending imports: ${e.message}")
            throw StoreError.SaveFailed(e.localizedMessage ?: e.toString())
        }
    }

    companion object {
        /** Installed at launch, backed by `pending_imports.json` in the app's files directory. */
        lateinit var shared: PendingImportStore

        private val serializer = ListSerializer(PendingImport.serializer())
    }
}
