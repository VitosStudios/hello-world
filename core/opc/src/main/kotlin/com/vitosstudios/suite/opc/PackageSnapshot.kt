package com.vitosstudios.suite.opc

import com.vitosstudios.suite.common.AtomicFile
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Immutable content of a package at one moment; see [OpcPackage.snapshot]. Safe to write on any thread. */
class PackageSnapshot internal constructor(
    private val entries: List<Entry>,
    private val source: PackageSource,
    private val zipComment: String?,
    /** Undo state the snapshot corresponds to; pass to `UndoManager.markSaved(marker)` after saving. */
    val undoMarker: Long,
) {
    internal class Entry(
        val zipName: String,
        val method: Int,
        val time: Long,
        val comment: String?,
        /** Content captured at snapshot time, or null to copy the source entry. */
        val bytes: ByteArray?,
        val changed: Boolean,
        val name: String,
    )

    fun writeTo(output: OutputStream) {
        val zos = ZipOutputStream(output)
        zipComment?.let { zos.setComment(it) }
        for (e in entries) {
            val ze = ZipEntry(e.zipName)
            ze.time = e.time
            e.comment?.let { ze.comment = it }
            if (e.method == ZipEntry.STORED) {
                val data = e.bytes ?: source.read(e.zipName)
                ze.method = ZipEntry.STORED
                ze.size = data.size.toLong()
                ze.compressedSize = data.size.toLong()
                ze.crc = CRC32().apply { update(data) }.value
                zos.putNextEntry(ze)
                zos.write(data)
            } else {
                ze.method = ZipEntry.DEFLATED
                zos.putNextEntry(ze)
                if (e.bytes != null) zos.write(e.bytes) else if (!e.zipName.endsWith("/")) source.copyTo(e.zipName, zos)
            }
            zos.closeEntry()
        }
        zos.finish()
        zos.flush()
    }

    /** Crash-safe, verified save (same guarantees as [OpcPackage.save]). */
    fun save(target: File, verify: (File) -> Unit = {}) {
        val changed = entries.filter { it.changed }.map { it.name }.toSet()
        AtomicFile.write(target, verify = { tmp ->
            PackageVerifier.verify(tmp, changed)
            verify(tmp)
        }) { out -> writeTo(out) }
    }
}
