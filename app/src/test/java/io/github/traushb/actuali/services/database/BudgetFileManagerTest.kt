package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.support.cleanup
import io.github.traushb.actuali.support.tempDir
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Budget creation file plumbing (GH #387) and the archive writer/reader:
 * ports of BudgetFileManagerCreateTests and BudgetArchiveWriterTests.
 */
class BudgetFileManagerTest {
    private fun withManager(block: (BudgetFileManager, File) -> Unit) {
        val root = tempDir()
        try {
            block(BudgetFileManager(File(root, "root")), root)
        } finally {
            cleanup(root)
        }
    }

    private fun writeFixtureBudget(dir: File, id: String): Pair<File, File> {
        val db = File(dir, "db.sqlite")
        DatabaseQueue(db.path).use { queue ->
            queue.write { it.execute("CREATE TABLE t (id TEXT PRIMARY KEY)"); it.execute("INSERT INTO t VALUES ('a')") }
        }
        val metadata = File(dir, "metadata.json").apply { writeBytes(BudgetMetadata(id = id, budgetName = "Fixture").encoded()) }
        return db to metadata
    }

    private fun zip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /**
     * Upstream idFromBudgetName: every space or non-alphanumeric flattened to
     * "-", then "-" plus 7 characters of a UUID (hex, since a UUID's first dash
     * sits at index 8).
     */
    @Test
    fun budgetIdMatchesUpstreamShape() {
        assertTrue(Regex("^My-Finances-[0-9a-f]{7}$").matches(BudgetFileManager.budgetId("My Finances")))
        assertTrue(Regex("^B-dget--2026---[0-9a-f]{7}$").matches(BudgetFileManager.budgetId("Büdget (2026)!")))
    }

    @Test
    fun createBudgetWritesTemplateCopyAndDefaultMetadata() = withManager { manager, root ->
        val template = File(root, "template.sqlite").apply { writeText("not-really-sqlite") }

        val metadata = manager.createBudget("Fresh Start") { template.inputStream() }

        assertEquals("Fresh Start", metadata.budgetName)
        assertTrue(metadata.id.startsWith("Fresh-Start-"))
        assertEquals("not-really-sqlite", manager.databasePath(metadata.id).readText())
        // metadata.json is upstream's getDefaultPrefs shape: id and budgetName
        // only — no sync-group keys until the upload registers the file.
        val json = AppJson.parseToJsonElement(manager.metadataPath(metadata.id).readText()).jsonObject
        assertEquals(setOf("id", "budgetName"), json.keys)
        assertTrue(manager.listLocalBudgets().any { it.id == metadata.id })
    }

    @Test
    fun createBudgetRemovesPartialDirectoryAfterFileError() = withManager { manager, root ->
        assertThrows(Exception::class.java) { manager.createBudget("Broken") { File(root, "missing.sqlite").inputStream() } }
        assertTrue(manager.listLocalBudgets().isEmpty())
        assertTrue(manager.budgetsDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun uploadArchiveStampsResetClockAndPreservesUnknownKeys() = withManager { manager, root ->
        manager.budgetDirectory("b1").mkdirs()
        manager.databasePath("b1").writeText("db-bytes")
        manager.metadataPath("b1").writeText("""{"id":"b1","budgetName":"B","futureKey":"kept"}""")

        val zipFile = File(root, "upload.zip").apply { writeBytes(manager.makeUploadArchive("b1")) }
        val extracted = manager.extractBudgetArchive(zipFile)
        try {
            assertEquals("db-bytes", extracted.database.readText())
            val archived = AppJson.parseToJsonElement(extracted.metadataData.decodeToString()).jsonObject
            // The archived copy tells downloaders to mint a fresh clock node
            // (upstream exportBuffer), and keys this app doesn't model survive.
            assertTrue(archived.getValue("resetClock").jsonPrimitive.boolean)
            assertEquals("kept", archived.getValue("futureKey").jsonPrimitive.content)
        } finally {
            extracted.database.delete()
        }
        // The live metadata is untouched.
        assertNull(AppJson.parseToJsonElement(manager.metadataPath("b1").readText()).jsonObject["resetClock"])
    }

    /** The interop guarantee: an Actuali-made archive round-trips through the same import path a server download uses. */
    @Test
    fun archiveRoundTripsThroughImportBudget() = withManager { manager, root ->
        val (db, metadata) = writeFixtureBudget(root, "roundtrip")
        val archive = File(root, "backup.zip")
        manager.makeBudgetArchive(db, metadata, archive)

        val imported = runBlocking { manager.importBudget(archive.readBytes(), "file-1", "group-1") }
        assertEquals("roundtrip", imported.id)
        assertEquals("file-1", imported.cloudFileId)
        assertEquals("group-1", imported.groupId)
        val count = DatabaseQueue(manager.databasePath("roundtrip").path).use { q -> q.read { it.long("SELECT COUNT(*) FROM t") } }
        assertEquals(1L, count)
    }

    @Test
    fun extractionRejectsTraversalEntryNames() = withManager { manager, root ->
        val archive = File(root, "evil.zip").also { zip(it, "../evil.txt" to "boom".toByteArray()) }
        assertThrows(BudgetFileError::class.java) { manager.extractBudgetArchive(archive) }
    }

    @Test
    fun extractionRejectsCaseInsensitiveDuplicates() = withManager { manager, root ->
        val archive = File(root, "dupes.zip").also { zip(it, "db.sqlite" to "x".toByteArray(), "DB.SQLITE" to "x".toByteArray()) }
        assertThrows(BudgetFileError::class.java) { manager.extractBudgetArchive(archive) }
    }

    @Test
    fun extractionRequiresBothEntries() = withManager { manager, root ->
        val (db, _) = writeFixtureBudget(root, "solo")
        val archive = File(root, "dbonly.zip").also { zip(it, "db.sqlite" to db.readBytes()) }
        assertThrows(BudgetFileError::class.java) { manager.extractBudgetArchive(archive) }
    }

    @Test
    fun extractionRejectsNonZipData() = withManager { manager, root ->
        val archive = File(root, "garbage.zip").apply { writeText("not a zip") }
        assertThrows(BudgetFileError::class.java) { manager.extractBudgetArchive(archive) }
    }

    /**
     * A backup interrupted mid-write must never replace or corrupt an existing
     * archive: makeBudgetArchive builds at a temp path and only renames into
     * place once both entries are written.
     */
    @Test
    fun interruptedArchiveDoesNotReplaceExistingBackup() = withManager { manager, root ->
        val (db, metadata) = writeFixtureBudget(root, "atomic")
        val archive = File(root, "backup.zip")
        manager.makeBudgetArchive(db, metadata, archive)
        val goodBytes = archive.readBytes()

        assertThrows(Exception::class.java) { manager.makeBudgetArchive(File(root, "gone.sqlite"), metadata, archive) }

        assertArrayEquals(goodBytes, archive.readBytes())
        val extracted = manager.extractBudgetArchive(archive)
        extracted.database.delete()
        assertEquals("atomic", extracted.metadata.id)
        assertFalse(File(root, "backup.zip.tmp").exists())
    }
}
