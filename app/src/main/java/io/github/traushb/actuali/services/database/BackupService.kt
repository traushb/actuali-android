package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

private val logger = Logger("Backup")

/**
 * The list item the Backups screen renders: a timestamped archive, or the
 * one-shot revert that exists only while the user is viewing a restored backup.
 */
sealed interface Backup {
    val id: String

    data class Archive(override val id: String, val date: Long) : Backup

    data object Latest : Backup {
        override val id = "db.latest.sqlite"
    }

    val isLatest: Boolean get() = this is Latest
}

sealed class BackupError : Exception() {
    class SnapshotFailed(val error: Throwable) : BackupError() {
        override val message: String get() = L("Couldn't snapshot the budget database: %@", error.localizedMessage)
    }

    class ArchiveCreationFailed(val error: Throwable) : BackupError() {
        override val message: String get() = L("Couldn't create the backup archive: %@", error.localizedMessage)
    }

    class BackupNotFound(val id: String) : BackupError() {
        override val message: String get() = L("Backup %@ no longer exists", id)
    }

    class RestoreFailed(val error: Throwable) : BackupError() {
        override val message: String get() = L("Couldn't restore the backup: %@", error.localizedMessage)
    }
}

/** Runs [block], wrapping any failure but cancellation with [wrap]. */
private inline fun <T> wrapping(wrap: (Exception) -> Exception, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw wrap(e)
}

class BackupService(
    private val fileManager: BudgetFileManager = BudgetFileManager.shared,
    private val destinationManager: BackupDestinationManager = BackupDestinationManager.shared,
) {
    /** One operation at a time, like the Swift actor; all of it is blocking file I/O. */
    private val actor = Dispatchers.IO.limitedParallelism(1)

    /**
     * The snapshot is taken with VACUUM INTO instead of a raw file copy, so it
     * is consistent under any journal mode and never touches the live db. Pass
     * the open database when the budget is loaded; null opens a temporary
     * connection on the file (backing up a non-open budget).
     */
    suspend fun makeBackup(budgetId: String, database: BudgetDatabase?, now: Long = System.currentTimeMillis()) = withContext(actor) {
        // 1. Ensure the backups folder exists and sweep any .tmp a crashed
        //    backup left behind.
        val backupsDir = fileManager.backupsDirectory(budgetId)
        backupsDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }

        // 2. Snapshot the live db to a temp file next to the archives.
        val tempDb = File(backupsDir, BudgetFileManager.backupTempName(now))
        try {
            wrapping(BackupError::SnapshotFailed) { snapshotLiveDatabase(budgetId, database, tempDb) }

            // 3. Strip CRDT state from the snapshot as backups are standalone
            //    and carry no sync history. Table guards defend against
            //    unusual server files.
            wrapping(BackupError::SnapshotFailed) {
                DatabaseQueue(tempDb.path).use { queue ->
                    queue.write { db ->
                        if (db.tableExists("messages_crdt")) db.execute("DELETE FROM messages_crdt")
                        if (db.tableExists("messages_clock")) db.execute("DELETE FROM messages_clock")
                        // Strip Actuali's own migration bookkeeping: Actual's
                        // import rejects any db whose __migrations__ holds ids it
                        // doesn't ship. Without these rows the snapshot looks
                        // exactly like a file an upstream client migrated;
                        // restoring it in Actuali simply re-records the ids.
                        if (db.tableExists("__migrations__")) {
                            val ids = BudgetDatabase.actualiOnlyMigrationIds.joinToString(", ")
                            db.execute("DELETE FROM __migrations__ WHERE id IN ($ids)")
                        }
                    }
                }
            }

            // 4. Zip the cleaned snapshot with a VERBATIM byte-copy of
            //    metadata.json, so the archive never drops metadata keys this
            //    app doesn't model.
            val archiveName = BudgetFileManager.backupArchiveName(now)
            val archive = fileManager.backupPath(budgetId, archiveName)
            try {
                fileManager.makeBudgetArchive(tempDb, fileManager.metadataPath(budgetId), archive)
            } catch (e: Exception) {
                archive.delete()
                throw BackupError.ArchiveCreationFailed(e)
            }

            // 5. Making a backup ends "viewing a backup": whatever is current
            //    becomes the new baseline. Only now that the archive has landed
            //    — a failed backup must not cost the user the revert option.
            fileManager.latestDatabasePath(budgetId).delete()
            fileManager.latestMetadataPath(budgetId).delete()

            // 6. Prune existing backups
            prune(budgetId, now)
            logger.info("Backup created: ${archive.name}")

            // 7. Mirror to the custom destination folder if configured
            destinationManager.mirrorArchive(archive, budgetId, archiveName)
        } finally {
            tempDb.delete()
        }
    }

    /**
     * Consistent snapshot of the live db via VACUUM INTO — serialized on the
     * open database's queue when one is passed, so it can never race a
     * concurrent write. Null opens a temporary connection on the file
     * (non-open budget); not BudgetDatabase, whose init runs migrations, and a
     * snapshot must not mutate what it captures.
     */
    private suspend fun snapshotLiveDatabase(budgetId: String, database: BudgetDatabase?, destination: File) {
        if (database != null) {
            database.snapshotDatabase(destination.path)
        } else {
            DatabaseQueue(fileManager.databasePath(budgetId).path).use { queue ->
                queue.writeWithoutTransaction { db -> db.execute("VACUUM INTO ?", destination.path) }
            }
        }
    }

    // List

    /** Newest-first archives, with the revert prepended while the user is viewing a backup. */
    suspend fun availableBackups(budgetId: String): List<Backup> = withContext(actor) {
        val result = mutableListOf<Backup>()
        if (fileManager.latestDatabasePath(budgetId).exists()) result += Backup.Latest
        archiveList(budgetId).mapTo(result) { Backup.Archive(it.first, it.second) }
        result
    }

    private fun archiveList(budgetId: String): List<Pair<String, Long>> =
        fileManager.backupsDirectory(budgetId).listFiles().orEmpty()
            .filter { it.extension == "zip" }
            .map { it.name to it.lastModified() }
            .sortedByDescending { it.second }

    private fun prune(budgetId: String, today: Long) {
        for (id in backupsToRemove(archiveList(budgetId), today)) {
            fileManager.backupPath(budgetId, id).delete()
            destinationManager.removeMirroredArchive(budgetId, id)
        }
    }

    // Restore

    /**
     * Restore [backupId] over the live files, first snapshotting the current
     * state so the user can revert. The caller (BudgetStore) MUST have dropped
     * its database/sync references first and must reopen/reload afterwards;
     * pass the database it held so the baseline snapshot serializes with any
     * write still in flight on that queue.
     */
    suspend fun loadBackup(budgetId: String, backupId: String, database: BudgetDatabase?) = withContext(actor) {
        val liveDb = fileManager.databasePath(budgetId)
        val liveMetadata = fileManager.metadataPath(budgetId)
        val latestDb = fileManager.latestDatabasePath(budgetId)
        val latestMetadata = fileManager.latestMetadataPath(budgetId)

        try {
            if (backupId == Backup.Latest.id) {
                // Case A — revert: put the baseline back and consume it. The
                // baseline metadata still carries the original groupId, so sync
                // resumes automatically once the store reopens. Both halves
                // must exist — a half-pair from a crash is not a baseline (the
                // creation order below makes the db the marker).
                if (!latestDb.exists() || !latestMetadata.exists()) throw BackupError.BackupNotFound(backupId)
                replaceFile(liveDb, latestDb)
                replaceFile(liveMetadata, latestMetadata)
                Files.delete(latestDb.toPath())
                Files.delete(latestMetadata.toPath())
            } else {
                // Case B — a real archive.
                val archive = fileManager.backupPath(budgetId, backupId)
                if (!archive.exists()) throw BackupError.BackupNotFound(backupId)

                // First restore of this viewing session: preserve current state
                // as the revert baseline. Metadata first, db last — the db is
                // the existence marker everything keys on, so a crash between
                // the two writes leaves only a metadata orphan, swept here.
                if (!latestDb.exists()) {
                    latestMetadata.delete()
                    Files.copy(liveMetadata.toPath(), latestMetadata.toPath())
                    snapshotLiveDatabase(budgetId, database, latestDb)
                }

                val extracted = fileManager.extractBudgetArchive(archive)
                try {
                    val liveMeta = runCatching { liveMetadata.readBytes() }.getOrNull()?.let(BudgetMetadata::decode)
                    val restored = extracted.metadata.restoredOver(liveMeta)

                    removeSQLiteSidecars(liveDb)
                    Files.delete(liveDb.toPath())
                    Files.move(extracted.database.toPath(), liveDb.toPath())
                    liveMetadata.writeBytes(restored.encoded())
                } finally {
                    extracted.database.delete()
                }
            }
        } catch (e: BackupError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupError.RestoreFailed(e)
        }
    }

    private fun replaceFile(destination: File, source: File) {
        removeSQLiteSidecars(destination)
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * -journal for DELETE mode (the app's default), -wal/-shm in case a
     * snapshot or older file carried them. A stale sidecar left next to the
     * swapped-in database would be reinterpreted against the wrong file.
     */
    private fun removeSQLiteSidecars(db: File) {
        for (suffix in listOf("-journal", "-wal", "-shm")) File(db.path + suffix).delete()
    }

    companion object {
        /**
         * Within each local calendar day keep the first 3 (today) or first 1
         * (other days) of the given newest-first list, then keep only the first
         * 10 survivors. Returns the ids to delete.
         */
        fun backupsToRemove(backups: List<Pair<String, Long>>, today: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
            fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val todayStart = day(today)
            val removed = mutableListOf<String>()
            for ((day, dayBackups) in backups.groupBy { day(it.second) }) {
                val keep = if (day == todayStart) 3 else 1
                dayBackups.drop(keep).mapTo(removed) { it.first }
            }
            val remaining = backups.filter { it.first !in removed }
            remaining.drop(10).mapTo(removed) { it.first }
            return removed
        }
    }
}
