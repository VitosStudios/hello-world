package com.vitosstudios.suite.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.vitosstudios.suite.common.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Moves files between the user's storage (Storage Access Framework) and private working copies.
 *
 * Editors only ever touch the working copy, which is saved crash-safely (temp file, verification,
 * atomic rename). Changes reach the user's file by [writeBack], which streams the verified working
 * copy to the document provider and reads it back to confirm. The file as opened is kept as a
 * backup until a write-back has succeeded, so a failed write never loses the original.
 */
class FileRepository(private val context: Context, private val dao: FileDao) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val workRoot: File get() = File(context.filesDir, "work").apply { mkdirs() }
    private val localDocs: File get() = File(context.filesDir, "documents").apply { mkdirs() }

    fun observeFiles(): Flow<List<FileEntry>> = dao.observeAll()

    suspend fun get(id: String): FileEntry? = dao.get(id)

    /**
     * Registers a file chosen in the system picker (or shared to the app) and prepares its
     * working copy. If the file has unsaved changes from an earlier session they are kept.
     */
    suspend fun open(uri: Uri): FileEntry = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        }
        val (name, size, mime) = describe(uri)
        val kind = FileKind.from(name, mime) ?: throw IOException("This file type is not supported: $name")
        val existing = dao.byUri(uri.toString())
        val id = existing?.id ?: UUID.randomUUID().toString()
        val dir = File(workRoot, id).apply { mkdirs() }
        val working = File(dir, "working.${kind.primaryExtension}")
        val backup = File(dir, "original.${kind.primaryExtension}")
        if (existing == null || !existing.pendingWriteBack || !working.isFile) {
            copyIn(uri, working)
            working.copyTo(backup, overwrite = true)
        }
        val entry = FileEntry(
            id = id, uri = uri.toString(), displayName = name, kind = kind, sizeBytes = size ?: working.length(),
            lastOpenedAt = System.currentTimeMillis(), workingPath = working.absolutePath, backupPath = backup.absolutePath,
            pendingWriteBack = existing?.pendingWriteBack == true && working.isFile, lastError = existing?.lastError,
            pinned = existing?.pinned ?: false,
        )
        dao.upsert(entry)
        entry
    }

    /** Creates a new file stored on this device; [write] produces its content. */
    suspend fun createLocal(baseName: String, kind: FileKind, write: (File) -> Unit): FileEntry = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        var name = "$baseName.${kind.primaryExtension}"
        var n = 2
        while (File(localDocs, name).exists()) name = "$baseName $n.${kind.primaryExtension}".also { n++ }
        val file = File(localDocs, name)
        write(file)
        val entry = FileEntry(id, null, name, kind, file.length(), System.currentTimeMillis(), file.absolutePath, null)
        dao.upsert(entry)
        entry
    }

    suspend fun touch(id: String) {
        dao.get(id)?.let { dao.upsert(it.copy(lastOpenedAt = System.currentTimeMillis())) }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.get(id)?.let { dao.upsert(it.copy(pinned = pinned)) }
    }

    /** Removes the entry from the list; the user's own file is not deleted. */
    suspend fun forget(id: String) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        if (e.uri != null) File(workRoot, id).deleteRecursively()
        dao.delete(id)
    }

    /** Marks that the working copy changed and must be written back. */
    suspend fun markPending(id: String) {
        dao.get(id)?.let { if (!it.pendingWriteBack) dao.upsert(it.copy(pendingWriteBack = true)) }
    }

    /**
     * Writes the working copy back to the user's file and verifies it by reading it again.
     * Returns null on success or an error message (the working copy stays the source of truth).
     */
    suspend fun writeBack(id: String): String? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext "Unknown file"
        val working = File(e.workingPath)
        if (e.uri == null) {
            dao.upsert(e.copy(pendingWriteBack = false, sizeBytes = working.length(), lastError = null))
            return@withContext null
        }
        val uri = Uri.parse(e.uri)
        val error = try {
            val expected = sha256(working)
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("The storage provider refused to write")).use { out ->
                working.inputStream().use { it.copyTo(out) }
            }
            val actual = resolver.openInputStream(uri)?.use { sha256(it) } ?: throw IOException("Cannot read the file back")
            if (!actual.contentEquals(expected)) throw IOException("The saved file does not match; it was not confirmed")
            null
        } catch (ex: Exception) {
            ex.message ?: ex.javaClass.simpleName
        }
        dao.upsert(e.copy(pendingWriteBack = error != null, lastError = error, sizeBytes = if (error == null) working.length() else e.sizeBytes))
        error
    }

    /** Restores the working copy and the user's file from the backup taken when the file was opened. */
    suspend fun restoreOriginal(id: String): String? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext "Unknown file"
        val backup = e.backupPath?.let(::File)?.takeIf { it.isFile } ?: return@withContext "No backup available"
        AtomicFile.write(File(e.workingPath)) { out -> backup.inputStream().use { it.copyTo(out) } }
        dao.upsert(e.copy(pendingWriteBack = true))
        writeBack(id)
    }

    /** Copies the working copy to a new location chosen by the user ("Save a copy"). */
    suspend fun exportCopy(id: String, target: Uri): String? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext "Unknown file"
        try {
            (resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write")).use { out ->
                File(e.workingPath).inputStream().use { it.copyTo(out) }
            }
            null
        } catch (ex: Exception) {
            ex.message ?: "Export failed"
        }
    }

    /** Writes an exported file (PDF, CSV, image, video) chosen with "Export as". */
    suspend fun writeTo(target: Uri, source: File): String? = withContext(Dispatchers.IO) {
        try {
            (resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write")).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            null
        } catch (ex: Exception) {
            ex.message ?: "Export failed"
        }
    }

    /** Re-binds a device-only file to a location chosen by the user, then writes it there. */
    suspend fun bindToUri(id: String, target: Uri): String? {
        val e = dao.get(id) ?: return "Unknown file"
        try {
            resolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {}
        val (name, _, _) = withContext(Dispatchers.IO) { describe(target) }
        dao.upsert(e.copy(uri = target.toString(), displayName = name, pendingWriteBack = true))
        return writeBack(id)
    }

    /** Scratch directory for exports shared with other apps. */
    fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Files that still have to be written back (for example after the app was killed). */
    suspend fun pendingWriteBacks(): List<FileEntry> = dao.pending()

    private fun describe(uri: Uri): Triple<String, Long?, String?> {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return Triple(f.name, f.length(), null)
        }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return Triple(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size, resolver.getType(uri))
    }

    private fun copyIn(uri: Uri, target: File) {
        AtomicFile.write(target) { out ->
            (resolver.openInputStream(uri) ?: throw IOException("Cannot read the file")).use { it.copyTo(out) }
        }
    }

    private fun sha256(f: File): ByteArray = f.inputStream().use { sha256(it) }

    private fun sha256(input: java.io.InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest()
    }
}
