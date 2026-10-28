package com.vitosstudios.suite.common

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Crash-safe file replacement.
 *
 * The new content is written to a temporary file next to the target, flushed to disk, checked by
 * [verify] and only then moved over the target with an atomic rename. If anything fails (an
 * exception in the writer, a failed verification, the process dying mid-write) the target keeps
 * its previous content and the temporary file is removed (or ignored on the next run).
 */
object AtomicFile {
    private const val TEMP_SUFFIX = ".partial"

    fun write(target: File, verify: (File) -> Unit = {}, writer: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File(dir, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, 1 shl 16)
                writer(out)
                out.flush()
                fos.fd.sync()
            }
            verify(tmp)
            moveAtomically(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Deletes temporary files left behind by a process that died mid-write. */
    fun cleanupStale(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith(".") && it.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // Same directory, so rename(2) is atomic on POSIX file systems.
            if (!from.renameTo(to)) throw IOException("Cannot replace $to", e)
        }
    }
}
