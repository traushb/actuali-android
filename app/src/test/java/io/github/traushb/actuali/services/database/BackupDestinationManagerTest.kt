package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.services.InMemoryDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The defaults-backed half of BackupDestinationManagerTests. The halves that
 * pick and write a real folder go through the Storage Access Framework, which
 * only exists on a device.
 */
class BackupDestinationManagerTest {
    private val defaults = InMemoryDefaults()
    private val manager = BackupDestinationManager(resolver = null, defaults = defaults)

    @Test
    fun defaultStateHasNoCustomDestination() {
        assertNull(manager.destinationName)
        assertFalse(manager.isCustomDestinationConfigured)
        assertNull(manager.lastMirroredDate)
        assertNull(manager.lastMirrorError)
    }

    @Test
    fun clearDestinationRemovesKeys() {
        defaults.set(BackupDestinationManager.TREE_KEY, "content://tree/fake")
        defaults.set(BackupDestinationManager.NAME_KEY, "my-fav-backup")
        defaults.set(BackupDestinationManager.LAST_MIRRORED_DATE_KEY, System.currentTimeMillis())
        defaults.set(BackupDestinationManager.LAST_MIRROR_ERROR_KEY, "Mock Error")

        assertEquals("my-fav-backup", manager.destinationName)
        assertTrue(manager.isCustomDestinationConfigured)
        assertNotNull(manager.lastMirroredDate)
        assertEquals("Mock Error", manager.lastMirrorError)

        manager.clearDestination()

        assertNull(manager.destinationName)
        assertFalse(manager.isCustomDestinationConfigured)
        assertNull(manager.lastMirroredDate)
        assertNull(manager.lastMirrorError)
    }

    @Test
    fun mirrorArchiveSafelySkipsWhenUnconfigured() {
        val file = File.createTempFile("archive", ".zip").apply { writeText("test") }
        try {
            // Must not crash or fail when no custom destination is set
            manager.mirrorArchive(file, "b1", "test.zip")
            manager.removeMirroredArchive("b1", "test.zip")
            assertNull(manager.lastMirrorError)
        } finally {
            file.delete()
        }
    }
}
