package io.github.traushb.actuali.services.database

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.contains
import io.github.traushb.actuali.services.remove
import io.github.traushb.actuali.services.string
import java.io.File

private val logger = Logger("BackupDestination")

sealed class BackupDestinationError : Exception() {
    object AccessDenied : BackupDestinationError() {
        override val message: String get() = L("Permission was denied to access the selected folder.")
    }
}

/**
 * Manages an optional user-selected destination folder (a Storage Access
 * Framework tree: a local folder, or a cloud provider's) — the Android
 * counterpart of the iOS security-scoped bookmark. Backups are mirrored to
 * this folder while the master copies stay in app storage.
 *
 * [resolver] is null only in JVM tests, where no folder is ever configured.
 */
class BackupDestinationManager(private val resolver: ContentResolver?, private val defaults: Defaults = Defaults.standard) {
    val destinationName: String? get() = defaults.string(NAME_KEY)

    val isCustomDestinationConfigured: Boolean get() = defaults.contains(TREE_KEY)

    val lastMirroredDate: Long? get() = (defaults.get(LAST_MIRRORED_DATE_KEY) as? Number)?.toLong()

    val lastMirrorError: String? get() = defaults.string(LAST_MIRROR_ERROR_KEY)

    /** Keeps access to the picked folder across restarts and records it as the destination. */
    @Synchronized
    fun saveDestination(tree: Uri) {
        val resolver = resolver ?: throw BackupDestinationError.AccessDenied
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            throw BackupDestinationError.AccessDenied
        }
        val name = displayName(resolver, tree, DocumentsContract.getTreeDocumentId(tree)) ?: tree.lastPathSegment.orEmpty()
        defaults.set(TREE_KEY, tree.toString())
        defaults.set(NAME_KEY, name)
        defaults.remove(LAST_MIRROR_ERROR_KEY)
        logger.info("Custom backup destination configured: $name")
    }

    /** Clears the custom destination and returns to default local storage. */
    @Synchronized
    fun clearDestination() {
        configuredTree()?.let { tree ->
            runCatching {
                resolver?.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        for (key in listOf(TREE_KEY, NAME_KEY, LAST_MIRRORED_DATE_KEY, LAST_MIRROR_ERROR_KEY)) defaults.remove(key)
        logger.info("Custom backup destination reset to default")
    }

    /**
     * Mirrors a local backup archive to the destination folder under
     * Actuali/<budgetId>/.
     * ponytail: mirroring is fire-and-forget; if the destination is temporarily
     * unavailable (e.g. an offline cloud provider), local storage retains the
     * master copy safely.
     */
    @Synchronized
    fun mirrorArchive(source: File, budgetId: String, filename: String) {
        val tree = configuredTree() ?: return
        val resolver = resolver ?: return
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            recordFailure(L("Could not access security-scoped destination"))
            logger.warning("Lost access to the custom backup destination")
            return
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        if (displayName(resolver, tree, rootId) == null) {
            recordFailure(L("Destination folder no longer exists"))
            logger.warning("Custom destination folder no longer exists")
            return
        }
        try {
            val budgetFolder = directory(resolver, tree, directory(resolver, tree, rootId, "Actuali"), budgetId)
            // SAF providers rename on a clash ("x (1).zip"); replace instead, as the iOS copy does.
            child(resolver, tree, budgetFolder, filename)?.let { DocumentsContract.deleteDocument(resolver, documentUri(tree, it)) }
            val target = DocumentsContract.createDocument(resolver, documentUri(tree, budgetFolder), "application/zip", filename)
                ?: error("Couldn't create $filename")
            resolver.openOutputStream(target)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Couldn't open $filename")
            logger.info("Mirrored backup archive $filename to custom destination")
            recordSuccess()
        } catch (e: Exception) {
            logger.error("Failed to mirror backup archive: ${e.message}")
            recordFailure(e.localizedMessage ?: e.toString())
        }
    }

    /** Prunes a mirrored archive when retention removes it from local backups. */
    @Synchronized
    fun removeMirroredArchive(budgetId: String, filename: String) {
        val tree = configuredTree() ?: return
        val resolver = resolver ?: return
        runCatching {
            val actuali = child(resolver, tree, DocumentsContract.getTreeDocumentId(tree), "Actuali") ?: return
            val budgetFolder = child(resolver, tree, actuali, budgetId) ?: return
            val archive = child(resolver, tree, budgetFolder, filename) ?: return
            DocumentsContract.deleteDocument(resolver, documentUri(tree, archive))
        }
    }

    /** Copies existing archives to the destination (e.g. immediately after selecting a folder). */
    fun mirrorExistingBackups(budgetId: String, files: List<File>) {
        for (file in files) mirrorArchive(file, budgetId, file.name)
    }

    private fun configuredTree(): Uri? = defaults.string(TREE_KEY)?.let(Uri::parse)

    private fun recordSuccess() {
        defaults.set(LAST_MIRRORED_DATE_KEY, System.currentTimeMillis())
        defaults.remove(LAST_MIRROR_ERROR_KEY)
    }

    private fun recordFailure(reason: String) = defaults.set(LAST_MIRROR_ERROR_KEY, reason)

    private fun documentUri(tree: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun displayName(resolver: ContentResolver, tree: Uri, documentId: String): String? = runCatching {
        resolver.query(documentUri(tree, documentId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** The id of [parentId]'s child named [name], if any. */
    private fun child(resolver: ContentResolver, tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return cursor.getString(0)
        }
        return null
    }

    private fun directory(resolver: ContentResolver, tree: Uri, parentId: String, name: String): String =
        child(resolver, tree, parentId, name)
            ?: DocumentsContract.createDocument(resolver, documentUri(tree, parentId), Document.MIME_TYPE_DIR, name)
                ?.let(DocumentsContract::getDocumentId)
            ?: error("Couldn't create folder $name")

    companion object {
        /** Installed at launch with the app's content resolver. */
        lateinit var shared: BackupDestinationManager

        // Same keys as iOS; the "bookmark" holds the tree URI here.
        const val TREE_KEY = "actuali.backup.customDestinationBookmark"
        const val NAME_KEY = "actuali.backup.customDestinationName"
        const val LAST_MIRRORED_DATE_KEY = "actuali.backup.lastMirroredDate"
        const val LAST_MIRROR_ERROR_KEY = "actuali.backup.lastMirrorError"
    }
}
