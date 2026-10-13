package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

sealed class BudgetFileError : Exception() {
    object InvalidZipFile : BudgetFileError() {
        override val message: String get() = L("The downloaded file is not a valid ZIP archive")
    }

    object MissingDatabase : BudgetFileError() {
        override val message: String get() = L("The budget file is missing the database")
    }

    object MissingMetadata : BudgetFileError() {
        override val message: String get() = L("The budget file is missing metadata")
    }

    class ExtractionFailed(val error: Throwable) : BudgetFileError() {
        override val message: String get() = L("Failed to extract budget file: %@", error.localizedMessage)
    }

    object MetadataParsingFailed : BudgetFileError() {
        override val message: String get() = L("Failed to parse budget metadata")
    }

    class UnsafeArchive(val detail: String) : BudgetFileError() {
        override val message: String get() = L("The archive failed a safety check: %@", detail)
    }
}

@Serializable
data class BudgetMetadata(
    val id: String,
    val budgetName: String? = null,
    val cloudFileId: String? = null,
    val groupId: String? = null,
    val resetClock: Boolean? = null,
    val lastUploaded: String? = null,
    val encryptKeyId: String? = null,
) {
    /**
     * The metadata to persist after restoring this (archived) metadata over a
     * live budget. Sync-group fields are nulled: upstream nulls them only
     * transiently before its re-upload registers a fresh server group, but
     * Actuali has no upload path, so a restored fork must stay detached until
     * the user re-downloads. Identity fields come from the live metadata when
     * present — the directory's live file is the source of truth for which
     * cloud file this is.
     */
    fun restoredOver(live: BudgetMetadata?): BudgetMetadata = BudgetMetadata(
        id = id,
        budgetName = budgetName,
        cloudFileId = live?.cloudFileId ?: cloudFileId,
        groupId = null,
        resetClock = resetClock,
        lastUploaded = null,
        encryptKeyId = live?.encryptKeyId ?: encryptKeyId,
    )

    fun encoded(): ByteArray = AppJson.encodeToString(serializer(), this).toByteArray()

    companion object {
        fun decode(data: ByteArray): BudgetMetadata? = runCatching { AppJson.decodeFromString(serializer(), data.decodeToString()) }.getOrNull()
    }
}

/**
 * Budget files on disk: one directory per budget under `Budgets/`, holding
 * `db.sqlite`, `metadata.json` and the local `backups/`. Stateless beyond its
 * root, so one instance is safe to share across threads.
 */
class BudgetFileManager(private val root: File) {
    // Directories

    val budgetsDirectory: File
        get() = File(root, "Budgets").apply { mkdirs() }

    fun budgetDirectory(budgetId: String) = File(budgetsDirectory, budgetId)

    fun databasePath(budgetId: String) = File(budgetDirectory(budgetId), "db.sqlite")

    fun metadataPath(budgetId: String) = File(budgetDirectory(budgetId), "metadata.json")

    // Backup Paths

    /** Directory holding this budget's local backup archives, created on first use. */
    fun backupsDirectory(budgetId: String) = File(budgetDirectory(budgetId), "backups").apply { mkdirs() }

    fun backupPath(budgetId: String, name: String) = File(backupsDirectory(budgetId), name)

    fun latestDatabasePath(budgetId: String) = File(budgetDirectory(budgetId), "db.latest.sqlite")

    fun latestMetadataPath(budgetId: String) = File(budgetDirectory(budgetId), "metadata.latest.json")

    // Backup Archives

    /**
     * Creates a backup archive with EXACTLY the two root entries the import
     * path looks for, so Actuali-made archives round-trip through
     * [importBudget] and open in Actual desktop (upstream zips the same two
     * names, backups.ts:145-148).
     */
    fun makeBudgetArchive(db: File, metadata: File, destination: File) {
        if (!db.exists()) throw BudgetFileError.MissingDatabase
        if (!metadata.exists()) throw BudgetFileError.MissingMetadata

        // Build at a temp path and rename into place: a crash or a process kill
        // mid-write can then only leave a *.zip.tmp the sweep removes — never a
        // truncated .zip at the real path for the backup list to surface.
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.delete()
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            for ((name, file) in listOf("db.sqlite" to db, "metadata.json" to metadata)) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // Same-second overwrite, per upstream.
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * util/zip.ts:20-35 — reject NUL, backslash, drive-letter prefix, absolute
     * paths, and `..` as a path *segment* (segment comparison, not a substring
     * match: "a..b/x" is legal, "a/../x" is not).
     */
    private fun assertSafeEntryName(name: String) {
        if ('\u0000' in name || '\\' in name || DRIVE_LETTER.containsMatchIn(name) || name.startsWith("/") ||
            name.split("/").contains("..")
        ) {
            throw BudgetFileError.UnsafeArchive("unsafe entry name: $name")
        }
    }

    class ExtractedBudget(
        /** Temporary location of the extracted database. The caller owns it and must delete it (or move it into place). */
        val database: File,
        val metadataData: ByteArray,
        val metadata: BudgetMetadata,
    )

    /**
     * Opens a budget archive, validates it (entry names, size caps,
     * duplicates), and extracts db.sqlite to a temp file. Shared by server
     * import and backup restore.
     *
     * Entries are only ever streamed into fixed temp paths, never materialized
     * at their own names, so a symlink entry can't point extraction anywhere.
     */
    fun extractBudgetArchive(archive: File): ExtractedBudget {
        if (archive.length() > MAX_ARCHIVE_BYTES) throw BudgetFileError.UnsafeArchive("archive exceeds $MAX_ARCHIVE_BYTES bytes")

        val zip = try {
            ZipFile(archive)
        } catch (e: IOException) {
            throw BudgetFileError.InvalidZipFile
        }
        zip.use {
            val entries = zip.entries().toList()
            val seen = mutableSetOf<String>()
            var totalUncompressed = 0L
            for (entry in entries) {
                assertSafeEntryName(entry.name)
                val entrySize = maxOf(entry.size, 0)
                if (entrySize > MAX_ARCHIVE_BYTES) {
                    throw BudgetFileError.UnsafeArchive("entry ${entry.name} exceeds $MAX_ARCHIVE_BYTES bytes")
                }
                totalUncompressed += entrySize
                if (totalUncompressed > MAX_ARCHIVE_BYTES) {
                    throw BudgetFileError.UnsafeArchive("total uncompressed size exceeds $MAX_ARCHIVE_BYTES bytes")
                }
                if (!seen.add(entry.name.lowercase())) throw BudgetFileError.UnsafeArchive("duplicate entry: ${entry.name}")
            }

            val dbEntry = entries.firstOrNull { it.name.endsWith("db.sqlite") } ?: throw BudgetFileError.MissingDatabase
            val metaEntry = entries.firstOrNull { it.name.endsWith("metadata.json") } ?: throw BudgetFileError.MissingMetadata

            val metadataData = java.io.ByteArrayOutputStream().also { out ->
                zip.getInputStream(metaEntry).use { copyCapped(it, out, metaEntry.name) }
            }.toByteArray()
            val metadata = BudgetMetadata.decode(metadataData) ?: throw BudgetFileError.MetadataParsingFailed

            val tempDb = File(tempDirectory, UUID.randomUUID().toString() + ".sqlite")
            try {
                zip.getInputStream(dbEntry).use { input -> tempDb.outputStream().use { copyCapped(input, it, dbEntry.name) } }
            } catch (e: Exception) {
                tempDb.delete()
                throw e
            }
            return ExtractedBudget(tempDb, metadataData, metadata)
        }
    }

    /**
     * Copies at most [MAX_ARCHIVE_BYTES]: the sizes checked above come from the
     * archive's own directory, which a decompression bomb can understate.
     */
    private fun copyCapped(input: InputStream, output: OutputStream, entryName: String) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_ARCHIVE_BYTES) throw BudgetFileError.UnsafeArchive("entry $entryName exceeds $MAX_ARCHIVE_BYTES bytes")
            output.write(buffer, 0, read)
        }
    }

    // Budget Management

    fun listLocalBudgets(): List<BudgetMetadata> =
        budgetsDirectory.listFiles().orEmpty().mapNotNull { dir ->
            File(dir, "metadata.json").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }?.let(BudgetMetadata::decode)
        }

    fun budgetExists(budgetId: String): Boolean = databasePath(budgetId).exists()

    // Create

    /**
     * Create a new budget directory from the bundled blank template: db.sqlite
     * is a byte-copy of the template (upstream copies its default-db.sqlite the
     * same way, budgetfiles/app.ts:437) and metadata.json starts as just
     * {id, budgetName}, upstream's getDefaultPrefs shape.
     */
    fun createBudget(name: String, openTemplate: () -> InputStream): BudgetMetadata {
        val base = budgetId(name)
        var id = base
        var index = 0
        // Upstream's collision loop (idFromBudgetName): the 7 random chars
        // make a clash vanishingly unlikely, but cheap to mirror exactly.
        while (budgetDirectory(id).exists()) {
            index += 1
            id = base + index
        }

        val budgetDir = budgetDirectory(id)
        try {
            budgetDir.mkdirs()
            openTemplate().use { template -> databasePath(id).outputStream().use { template.copyTo(it) } }
            val metadata = BudgetMetadata(id = id, budgetName = name)
            metadataPath(id).writeBytes(metadata.encoded())
            return metadata
        } catch (e: Exception) {
            budgetDir.deleteRecursively()
            throw e
        }
    }

    /**
     * Zip a budget's live files for upload, with resetClock stamped true in the
     * archived metadata copy only, so whoever downloads the file mints a fresh
     * clock node id (upstream exportBuffer, cloud-storage.ts:181). The live
     * metadata on disk is untouched, and keys this app doesn't model survive.
     *
     * ponytail: no cache stripping — this only serves newly created budgets,
     * whose database is the pristine template (empty kvcache, no Actuali-only
     * migration ids because BudgetDatabase hasn't opened it yet). A general
     * re-upload path would have to clear kvcache/kvcache_key and
     * actualiOnlyMigrationIds from a copy first, the way BackupService does.
     */
    fun makeUploadArchive(budgetId: String): ByteArray {
        val json = AppJson.parseToJsonElement(metadataPath(budgetId).readText()) as? JsonObject ?: throw BudgetFileError.MetadataParsingFailed
        val stamped = JsonObject(json + ("resetClock" to JsonPrimitive(true)))

        val tempDir = File(tempDirectory, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val metadata = File(tempDir, "metadata.json").apply { writeText(stamped.toString()) }
            val archive = File(tempDir, "upload.zip")
            makeBudgetArchive(databasePath(budgetId), metadata, archive)
            return archive.readBytes()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Import

    suspend fun importBudget(zipData: ByteArray, fileId: String, groupId: String?): BudgetMetadata = withContext(Dispatchers.IO) {
        val tempZip = File(tempDirectory, UUID.randomUUID().toString() + ".zip")
        try {
            tempZip.writeBytes(zipData)
            val extracted = extractBudgetArchive(tempZip)
            try {
                val updatedMetadata = extracted.metadata.copy(cloudFileId = fileId, groupId = groupId)
                val budgetId = extracted.metadata.id

                // Replace only the live files — deleting the whole directory
                // would silently destroy backups/ on every re-download. A fresh
                // download does, however, supersede any revert baseline.
                budgetDirectory(budgetId).mkdirs()
                latestDatabasePath(budgetId).delete()
                latestMetadataPath(budgetId).delete()

                val dbPath = databasePath(budgetId)
                for (suffix in listOf("-wal", "-shm")) File(dbPath.path + suffix).delete()
                Files.move(extracted.database.toPath(), dbPath.toPath(), StandardCopyOption.REPLACE_EXISTING)

                metadataPath(budgetId).writeBytes(updatedMetadata.encoded())
                updatedMetadata
            } finally {
                extracted.database.delete()
            }
        } finally {
            tempZip.delete()
        }
    }

    // Delete

    fun deleteBudget(budgetId: String) {
        if (!budgetDirectory(budgetId).deleteRecursively()) throw IOException("Couldn't delete budget $budgetId")
    }

    companion object {
        /** Installed at launch, rooted in the app's files directory. */
        lateinit var shared: BudgetFileManager

        /**
         * Ported from upstream's safeUnzip caps (util/zip.ts:9): the zip
         * readers there and here do no validation themselves, so guard against
         * zip-slip and decompression bombs before extracting anything.
         */
        private const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024

        private val DRIVE_LETTER = Regex("^[a-zA-Z]:")

        /** The app's cache directory on Android, the JVM's temp directory in tests. */
        private val tempDirectory: File get() = File(System.getProperty("java.io.tmpdir") ?: ".")

        /**
         * Archive names match upstream's yyyy-MM-dd_HH-mm-ss.zip. The device
         * timezone is deliberate: "today" means the user's today.
         */
        fun backupArchiveName(date: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Instant.ofEpochMilli(date).atZone(zone)) + ".zip"

        fun backupTempName(now: Long): String = "db.$now.sqlite.tmp"

        /**
         * Mirror of upstream's idFromBudgetName (util/budget-name.ts:41): every
         * space or non-alphanumeric becomes "-" (per UTF-16 unit, as the JS
         * regex does), plus the first 7 characters of a fresh UUID.
         */
        fun budgetId(name: String): String =
            name.map { if (it.code < 128 && it.isLetterOrDigit()) it else '-' }.joinToString("") +
                "-" + UUID.randomUUID().toString().lowercase().take(7)
    }
}
