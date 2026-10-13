package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.services.InMemoryDefaults
import io.github.traushb.actuali.support.cleanup
import io.github.traushb.actuali.support.tempDir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Ports of BackupRetentionTests, BackupServiceMakeTests and BackupRestoreRevertTests. */
class BackupServiceTest {
    private val root = tempDir()
    private val manager = BudgetFileManager(root)
    private val service = BackupService(manager, BackupDestinationManager(resolver = null, defaults = InMemoryDefaults()))

    @After
    fun tearDown() = cleanup(root)

    // Retention

    /** Local-calendar date, matching how prune buckets by day. */
    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val today get() = day(2017, 1, 1)

    /** Upstream vector 1: keeps 3 backups on the current day. */
    @Test
    fun keepsThreeOnCurrentDay() {
        val backups = (1..4).map { "backup$it" to day(2017, 1, 1) }
        assertEquals(listOf("backup4"), BackupService.backupsToRemove(backups, today))
    }

    /** Upstream vector 2: nothing to delete — ≤3 today, 1 per prior day. */
    @Test
    fun keepsOnePerPriorDay() {
        val backups = listOf("backup1" to day(2017, 1, 1), "backup2" to day(2017, 1, 1), "backup3" to day(2016, 12, 30), "backup4" to day(2016, 12, 29))
        assertTrue(BackupService.backupsToRemove(backups, today).isEmpty())
    }

    /** Upstream vector 3: extra copies on a prior day are deleted. */
    @Test
    fun deletesExtrasOnPriorDays() {
        val backups = listOf(
            "backup1" to day(2017, 1, 1), "backup2" to day(2017, 1, 1),
            "backup3" to day(2016, 12, 29), "backup4" to day(2016, 12, 29), "backup5" to day(2016, 12, 29),
        )
        assertEquals(setOf("backup4", "backup5"), BackupService.backupsToRemove(backups, today).toSet())
    }

    /** Upstream vector 4: cap at 10 total (12 in → backup11/12 out). */
    @Test
    fun capsAtTenTotal() {
        val backups = mutableListOf("backup1" to day(2017, 1, 1), "backup2" to day(2017, 1, 1))
        (29 downTo 20).forEachIndexed { index, dayOfMonth -> backups += "backup${index + 3}" to day(2016, 12, dayOfMonth) }
        assertEquals(setOf("backup11", "backup12"), BackupService.backupsToRemove(backups, today).toSet())
    }

    /** Ours: a backup from 23:59 yesterday is "yesterday", not "today". */
    @Test
    fun dayBucketsUseLocalMidnightBoundary() {
        val yesterdayLate = today - 60_000
        val backups = listOf("today1" to today, "late1" to yesterdayLate, "late2" to yesterdayLate)
        assertEquals(listOf("late2"), BackupService.backupsToRemove(backups, today))
    }

    /** Retention runs as part of makeBackup (end-to-end over real files). */
    @Test
    fun makeBackupPrunes() = runBlocking {
        manager.budgetDirectory("b").mkdirs()
        DatabaseQueue(manager.databasePath("b").path).use { q -> q.write { it.execute("CREATE TABLE t (id TEXT)") } }
        manager.metadataPath("b").writeBytes(BudgetMetadata(id = "b").encoded())

        // 4 same-day backups (distinct seconds so names differ) → 3 survive.
        val base = LocalDateTime.now().withHour(12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for (offset in 0 until 4) service.makeBackup("b", null, base + offset * 1000L)
        val kept = service.availableBackups("b")
        assertEquals(3, kept.size)
        assertTrue(kept.none { it.isLatest })
    }

    // Make

    /** A budget directory with a real db carrying CRDT tables + one data table, and a metadata.json — the minimum makeBackup touches. */
    private fun seedCrdtBudget(id: String) {
        manager.budgetDirectory(id).mkdirs()
        DatabaseQueue(manager.databasePath(id).path).use { queue ->
            queue.write { db ->
                db.executeScript(
                    """
                    CREATE TABLE t (id TEXT PRIMARY KEY, note TEXT);
                    INSERT INTO t VALUES ('row1', 'original');
                    CREATE TABLE messages_crdt (id INTEGER PRIMARY KEY, timestamp TEXT NOT NULL UNIQUE, dataset TEXT, row TEXT, column TEXT, value BLOB);
                    CREATE TABLE messages_clock (id INTEGER PRIMARY KEY, clock TEXT);
                    INSERT INTO messages_crdt (timestamp, dataset, row, column, value)
                        VALUES ('2026-08-12T00:00:00.000Z-0000-0123456789abcdef', 't', 'row1', 'note', 'x');
                    INSERT INTO messages_clock VALUES (1, '{}');
                    CREATE TABLE __migrations__ (id INTEGER PRIMARY KEY NOT NULL);
                    INSERT INTO __migrations__ (id) VALUES (1548957970627);
                    INSERT INTO __migrations__ (id) VALUES (1765518577216);
                    """,
                )
            }
        }
        manager.metadataPath(id).writeBytes(
            BudgetMetadata(id = id, budgetName = "Seed", cloudFileId = "cf-1", groupId = "g-1", lastUploaded = "2026-08-01").encoded(),
        )
    }

    private fun zips(id: String) = manager.backupsDirectory(id).listFiles().orEmpty().filter { it.extension == "zip" }

    @Test
    fun backupStripsCRDTStateAndKeepsData() = runBlocking {
        seedCrdtBudget("b1")
        service.makeBackup("b1", null)

        val zips = zips("b1")
        assertEquals(1, zips.size)
        // No stray temp files survive.
        assertTrue(manager.backupsDirectory("b1").listFiles().orEmpty().none { it.name.endsWith(".sqlite.tmp") })

        // The archived db is standalone: CRDT tables empty, data intact.
        val extracted = manager.extractBudgetArchive(zips[0])
        try {
            DatabaseQueue(extracted.database.path).use { q ->
                q.read { db ->
                    assertEquals(0L, db.long("SELECT COUNT(*) FROM messages_crdt"))
                    assertEquals(0L, db.long("SELECT COUNT(*) FROM messages_clock"))
                    assertEquals("original", db.string("SELECT note FROM t WHERE id = 'row1'"))
                    assertEquals(listOf(1_548_957_970_627L), db.longs("SELECT id FROM __migrations__"))
                }
            }
        } finally {
            extracted.database.delete()
        }

        // The live db is untouched.
        DatabaseQueue(manager.databasePath("b1").path).use { q ->
            q.read { db ->
                assertEquals(1L, db.long("SELECT COUNT(*) FROM messages_crdt"))
                assertEquals(2L, db.long("SELECT COUNT(*) FROM __migrations__"))
            }
        }
        // Archived metadata is the verbatim live copy (groupId intact —
        // detachment happens on restore, not on backup).
        assertEquals("g-1", extracted.metadata.groupId)
    }

    @Test
    fun backupClearsRevertBaseline() = runBlocking {
        seedCrdtBudget("b2")
        // Simulate "viewing a backup".
        manager.latestDatabasePath("b2").writeText("old-db")
        manager.latestMetadataPath("b2").writeText("old-meta")

        service.makeBackup("b2", null)

        assertFalse(manager.latestDatabasePath("b2").exists())
        assertFalse(manager.latestMetadataPath("b2").exists())
    }

    @Test
    fun backupWorksThroughOpenBudgetDatabase() = runBlocking {
        seedCrdtBudget("b3")
        // The live-database path: snapshot via the open BudgetDatabase.
        BudgetDatabase(manager.databasePath("b3").path).use { database -> service.makeBackup("b3", database) }
        assertEquals(1, zips("b3").size)
    }

    @Test
    fun backupSweepsLeftoverArchiveTemp() = runBlocking {
        seedCrdtBudget("b4")
        // A .zip.tmp a killed backup left mid-write. It must never be surfaced
        // as a restorable backup, and the next backup sweeps it.
        val leftover = File(manager.backupsDirectory("b4"), "2026-01-01_00-00-00.zip.tmp").apply { writeText("truncated") }
        assertTrue(service.availableBackups("b4").none { it.id.endsWith(".tmp") })

        service.makeBackup("b4", null)
        assertFalse(leftover.exists())

        val remaining = manager.backupsDirectory("b4").listFiles().orEmpty()
        assertEquals(1, remaining.count { it.extension == "zip" })
        assertTrue(remaining.none { it.name.endsWith(".tmp") })
    }

    // Restore & revert

    private fun seedNoteBudget(id: String, note: String) {
        manager.budgetDirectory(id).mkdirs()
        DatabaseQueue(manager.databasePath(id).path).use { queue ->
            queue.write { db ->
                db.execute("CREATE TABLE t (id TEXT PRIMARY KEY, note TEXT)")
                db.execute("INSERT INTO t VALUES ('row1', ?)", note)
                db.execute("CREATE TABLE messages_crdt (id INTEGER PRIMARY KEY, timestamp TEXT)")
                db.execute("CREATE TABLE messages_clock (id INTEGER PRIMARY KEY, clock TEXT)")
            }
        }
        manager.metadataPath(id).writeBytes(
            BudgetMetadata(id = id, budgetName = "Seed", cloudFileId = "cf-1", groupId = "g-1", lastUploaded = "2026-08-01", encryptKeyId = "k-1").encoded(),
        )
    }

    private fun note(id: String): String? =
        DatabaseQueue(manager.databasePath(id).path).use { q -> q.read { it.string("SELECT note FROM t WHERE id = 'row1'") } }

    private fun setNote(id: String, note: String) =
        DatabaseQueue(manager.databasePath(id).path).use { q -> q.write { it.execute("UPDATE t SET note = ?", note) } }

    private fun metadata(id: String): BudgetMetadata = BudgetMetadata.decode(manager.metadataPath(id).readBytes())!!

    @Test
    fun restoreDetachesAndRevertRestoresOriginal() = runBlocking {
        seedNoteBudget("b", "backup-state")
        service.makeBackup("b", null)
        val archiveId = service.availableBackups("b").first { !it.isLatest }.id

        // The budget moves on after the backup was taken.
        setNote("b", "current-state")
        val preRestoreMetaBytes = manager.metadataPath("b").readBytes()

        service.loadBackup("b", archiveId, null)

        assertEquals("backup-state", note("b"))
        val restoredMeta = metadata("b")
        assertNull(restoredMeta.groupId) // detached
        assertNull(restoredMeta.lastUploaded)
        assertEquals("cf-1", restoredMeta.cloudFileId) // identity kept
        assertEquals("k-1", restoredMeta.encryptKeyId)
        assertTrue(manager.latestDatabasePath("b").exists())

        // The list shows the revert head first.
        assertEquals(true, service.availableBackups("b").firstOrNull()?.isLatest)

        // Revert: original content back (the baseline is a VACUUM snapshot, so
        // db equality is semantic, not byte-level), baseline consumed, sync
        // resumable via the byte-identical metadata.
        service.loadBackup("b", Backup.Latest.id, null)
        assertEquals("current-state", note("b"))
        assertArrayEquals(preRestoreMetaBytes, manager.metadataPath("b").readBytes())
        assertEquals("g-1", metadata("b").groupId)
        assertFalse(manager.latestDatabasePath("b").exists())
        assertFalse(manager.latestMetadataPath("b").exists())
    }

    @Test
    fun secondRestoreKeepsOriginalBaseline() = runBlocking {
        seedNoteBudget("b", "v1")
        val now = System.currentTimeMillis()
        service.makeBackup("b", null, now)
        setNote("b", "v2")
        service.makeBackup("b", null, now + 1000)
        setNote("b", "v3")

        val archives = service.availableBackups("b").filter { !it.isLatest }
        assertEquals(2, archives.size)

        // Restore twice in a row: the baseline must stay the ORIGINAL (v3)
        // state — the snapshot happens once per viewing session.
        service.loadBackup("b", archives[1].id, null) // oldest → v1
        service.loadBackup("b", archives[0].id, null) // newer → v2
        assertEquals("v2", note("b"))

        service.loadBackup("b", Backup.Latest.id, null)
        assertEquals("v3", note("b"))
    }

    @Test
    fun restoreOfMissingBackupThrows() {
        seedNoteBudget("b", "v1")
        assertThrows(BackupError::class.java) { runBlocking { service.loadBackup("b", "2026-01-01_00-00-00.zip", null) } }
        // The failed restore must not have started a viewing session.
        assertFalse(manager.latestDatabasePath("b").exists())
    }

    @Test
    fun revertWithoutBaselineThrows() {
        seedNoteBudget("b", "v1")
        assertThrows(BackupError::class.java) { runBlocking { service.loadBackup("b", Backup.Latest.id, null) } }
        // Live files untouched.
        assertEquals("v1", note("b"))
        assertEquals("g-1", metadata("b").groupId)
    }

    /**
     * A crash between the baseline's two writes leaves only the metadata half
     * (the db is written last as the marker). The next restore sweeps the
     * orphan and builds a fresh, consistent pair.
     */
    @Test
    fun restoreSweepsOrphanedBaselineMetadata() = runBlocking {
        seedNoteBudget("b", "live")
        service.makeBackup("b", null)
        val archiveId = service.availableBackups("b").first { !it.isLatest }.id
        manager.latestMetadataPath("b").writeText("orphan")

        service.loadBackup("b", archiveId, null)
        service.loadBackup("b", Backup.Latest.id, null)
        assertEquals("live", note("b"))
        assertEquals("g-1", metadata("b").groupId)
    }

    @Test
    fun reimportPreservesBackupsAndClearsBaseline() = runBlocking {
        seedNoteBudget("b", "v1")
        service.makeBackup("b", null)
        manager.latestDatabasePath("b").writeText("baseline")

        // Build a server-download zip for the SAME budget id and re-import.
        val staging = File(root, "staging").apply { mkdirs() }
        val stagedDb = File(staging, "db.sqlite")
        DatabaseQueue(stagedDb.path).use { q -> q.write { it.execute("CREATE TABLE t (id TEXT)") } }
        val stagedMeta = File(staging, "metadata.json").apply { writeBytes(BudgetMetadata(id = "b", budgetName = "Server Copy").encoded()) }
        val downloadZip = File(staging, "download.zip")
        manager.makeBudgetArchive(stagedDb, stagedMeta, downloadZip)

        manager.importBudget(downloadZip.readBytes(), "cf-1", "g-new")

        // Backups survived the re-download; the revert baseline did not.
        val backups = service.availableBackups("b")
        assertTrue(backups.any { !it.isLatest })
        assertTrue(backups.none { it.isLatest })
        assertFalse(manager.latestDatabasePath("b").exists())
    }
}
