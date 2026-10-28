package com.vitosstudios.suite.opc

import com.vitosstudios.suite.common.AtomicFile
import com.vitosstudios.suite.common.LambdaChange
import com.vitosstudios.suite.common.UndoManager
import com.vitosstudios.suite.xml.XDocument
import com.vitosstudios.suite.xml.XmlCodec
import com.vitosstudios.suite.xml.XmlParseException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when an XML part cannot be parsed losslessly; such parts are preserved but not edited. */
class PartNotEditableException(val partName: String, cause: Throwable?) :
    Exception("Part $partName cannot be edited safely: ${cause?.message}", cause)

/** Where the bytes of unmodified parts come from. */
internal interface PackageSource : Closeable {
    fun read(entryName: String): ByteArray
    fun copyTo(entryName: String, out: OutputStream)
}

private class ZipFileSource(val file: File) : PackageSource {
    private val zip = ZipFile(file)
    override fun read(entryName: String): ByteArray =
        zip.getInputStream(zip.getEntry(entryName) ?: throw OpcException("Missing entry $entryName")).use { it.readBytes() }
    override fun copyTo(entryName: String, out: OutputStream) {
        zip.getInputStream(zip.getEntry(entryName) ?: throw OpcException("Missing entry $entryName")).use { it.copyTo(out) }
    }
    override fun close() = zip.close()
}

private class MemorySource(val entries: Map<String, ByteArray>) : PackageSource {
    override fun read(entryName: String): ByteArray = entries[entryName] ?: throw OpcException("Missing entry $entryName")
    override fun copyTo(entryName: String, out: OutputStream) = out.write(read(entryName))
    override fun close() {}
}

/** ZIP-level facts about an entry, kept so the archive is rewritten as close to the original as possible. */
data class ZipInfo(
    val entryName: String,
    val method: Int,
    val time: Long,
    val comment: String?,
    val size: Long,
)

/**
 * One item of the package: a part, a relationships part, [Content_Types].xml or a directory entry.
 *
 * Content stays in the source archive until it is needed. Unmodified parts are written back
 * byte-identical (streamed from the source); XML parts are parsed only when a format layer asks
 * for [xml], and serialised only if they were changed.
 */
class Part internal constructor(
    val pkg: OpcPackage,
    val name: String,
    internal val zipInfo: ZipInfo?,
    private var replacement: ByteArray?,
) {
    private var doc: XDocument? = null
    private var parseError: Throwable? = null

    val isDirectory: Boolean get() = name.endsWith("/")
    val contentType: String? get() = pkg.contentTypes.contentTypeOf(name)
    val extension: String get() = PartNames.extension(name)

    /** True if the content differs from what was read (XML edits or replaced bytes). */
    val isModified: Boolean get() = replacement != null || (doc?.modCount ?: 0L) > 0L

    /** The part was created in this session (it has no source entry). */
    val isNew: Boolean get() = zipInfo == null

    /** Whether [xml] has been requested and succeeded. */
    val isParsed: Boolean get() = doc != null

    /** Current content. */
    fun bytes(): ByteArray {
        doc?.let { if (it.modCount > 0) return XmlCodec.serialize(it) }
        replacement?.let { return it }
        return pkg.source.read(zipInfo!!.entryName)
    }

    /** Size of the current content without materialising unmodified parts. */
    fun size(): Long = if (isModified || zipInfo == null) bytes().size.toLong() else zipInfo.size

    internal fun writeContent(out: OutputStream) {
        if (isModified || zipInfo == null) out.write(bytes()) else pkg.source.copyTo(zipInfo.entryName, out)
    }

    /**
     * The parsed XML tree, attached to the package's undo manager. Throws
     * [PartNotEditableException] if the part is not XML that survives a lossless round trip.
     */
    fun xml(): XDocument {
        doc?.let { return it }
        parseError?.let { throw PartNotEditableException(name, it) }
        val parsed = try {
            XmlCodec.parseVerified(bytes())
        } catch (e: XmlParseException) {
            parseError = e
            throw PartNotEditableException(name, e)
        }
        parsed.changeSink = pkg.undo::record
        parsed.onModified = { pkg.onPartModified(this) }
        doc = parsed
        return parsed
    }

    fun xmlOrNull(): XDocument? = try { xml() } catch (e: PartNotEditableException) { null }

    /** Replaces the whole content (undoable). Drops a parsed tree. */
    fun setBytes(newBytes: ByteArray) {
        val oldReplacement = replacement
        val oldDoc = doc
        replacement = newBytes
        doc = null
        pkg.undo.record(LambdaChange({ replacement = oldReplacement; doc = oldDoc }, { replacement = newBytes; doc = null }))
        pkg.onPartModified(this)
    }

    /** Installs a freshly built tree as this part's content (used for new parts). */
    internal fun installXml(newDoc: XDocument) {
        newDoc.changeSink = pkg.undo::record
        newDoc.onModified = { pkg.onPartModified(this) }
        doc = newDoc
        replacement = XmlCodec.serialize(newDoc)
    }

    override fun toString(): String = "Part($name)"
}

/**
 * An Open Packaging Conventions package (DOCX, XLSX, PPTX): every ZIP entry in its original
 * order, the content types and the relationships.
 */
class OpcPackage private constructor(
    internal var source: PackageSource,
    private var sourceFile: File?,
    infos: List<ZipInfo>,
    val zipComment: String?,
) : Closeable {
    /** Records every change of every part. One manager per open document. */
    val undo = UndoManager()

    private val order = ArrayList<Part>()
    private val byKey = HashMap<String, Part>()
    private val relsCache = HashMap<String, Relationships>()
    private var listeners = ArrayList<(Part) -> Unit>()

    init {
        for (info in infos) {
            val name = PartNames.fromZipEntry(info.entryName)
            val key = PartNames.key(name)
            if (byKey.containsKey(key)) throw OpcException("Duplicate part name $name")
            val part = Part(this, name, info, null)
            order += part
            byKey[key] = part
        }
        if (part(PartNames.CONTENT_TYPES) == null) throw OpcException("Not an OPC package: [Content_Types].xml is missing")
    }

    val contentTypes: ContentTypes by lazy { ContentTypes(part(PartNames.CONTENT_TYPES)!!) }

    val parts: List<Part> get() = order

    /** Parts that are content (not relationships, not [Content_Types].xml, not directories). */
    val contentParts: List<Part>
        get() = order.filter { !it.isDirectory && !PartNames.isRelationshipPart(it.name) && !it.name.equals(PartNames.CONTENT_TYPES, true) }

    fun part(name: String): Part? = byKey[PartNames.key(name)] ?: byKey[PartNames.key(PartNames.percentDecode(name))]

    fun requirePart(name: String): Part = part(name) ?: throw OpcException("Missing part $name")

    /** Relationships whose source is [source] (null for the package itself). */
    fun relationships(source: Part?): Relationships = relationshipsOf(source?.name)

    fun relationshipsOf(sourcePartName: String?): Relationships {
        val relsName = PartNames.relsPartFor(sourcePartName)
        return relsCache.getOrPut(PartNames.key(relsName)) { Relationships(this, sourcePartName, relsName) }
    }

    /** The main document part (word/document.xml, xl/workbook.xml, ppt/presentation.xml). */
    fun mainDocument(): Part? {
        val rel = relationships(null).firstOfType(RelTypes.OFFICE_DOCUMENT) ?: return null
        return relationships(null).targetPart(rel)
    }

    /** Follows the first relationship of [type] from [source]. */
    fun related(source: Part?, type: String): Part? {
        val rels = relationships(source)
        return rels.firstOfType(type)?.let { rels.targetPart(it) }
    }

    fun addListener(listener: (Part) -> Unit) { listeners += listener }

    internal fun onPartModified(part: Part) {
        for (l in listeners) l(part)
    }

    /** Adds a part (undoable). [contentType] null means "covered by a Default". */
    fun addPart(name: String, contentType: String?, bytes: ByteArray): Part {
        require(name.startsWith("/")) { "Part names start with '/'" }
        val key = PartNames.key(name)
        if (byKey.containsKey(key)) throw OpcException("Part $name already exists")
        return undo.transaction("Add part") {
            val part = Part(this, name, null, bytes)
            insertPart(part, order.size)
            if (contentType != null) {
                val ext = PartNames.extension(name)
                if (contentTypes.defaultFor(ext) != contentType) contentTypes.setOverride(name, contentType)
            }
            part
        }
    }

    /** Adds an XML part from a tree (undoable). */
    fun addXmlPart(name: String, contentType: String, doc: XDocument): Part {
        val part = addPart(name, contentType, XmlCodec.serialize(doc))
        part.installXml(doc)
        return part
    }

    /** Removes a part, its relationships part and its content type override (undoable). */
    fun removePart(name: String) {
        val part = part(name) ?: return
        undo.transaction("Remove part") {
            contentTypes.removeOverride(part.name)
            detachPart(part)
            part(PartNames.relsPartFor(part.name))?.let { detachPart(it) }
        }
    }

    /**
     * Removes [name] and, recursively, the internal targets of its relationships that are no
     * longer referenced by any other part.
     */
    fun removePartRecursively(name: String) {
        val root = part(name) ?: return
        undo.transaction("Remove part") {
            val targets = relationships(root).all().filter { !it.isExternal }.mapNotNull { relationships(root).targetPart(it) }
            removePart(root.name)
            for (t in targets) {
                if (part(t.name) != null && !isReferenced(t.name)) removePartRecursively(t.name)
            }
        }
    }

    /** True if any relationship in the package points at [partName]. */
    fun isReferenced(partName: String): Boolean {
        val key = PartNames.key(partName)
        for (p in order.toList()) {
            if (!PartNames.isRelationshipPart(p.name)) continue
            val src = PartNames.sourceOfRelsPart(p.name) ?: continue
            if (src.isNotEmpty() && part(src) == null) continue
            val rels = relationshipsOf(src.ifEmpty { null })
            if (rels.all().any { !it.isExternal && PartNames.key(rels.resolve(it)) == key }) return true
        }
        return false
    }

    /** A part name like "/word/media/image7.png" that does not exist yet. */
    fun uniquePartName(prefix: String, extension: String, start: Int = 1): String {
        var i = start
        while (true) {
            val candidate = "$prefix$i.$extension"
            if (part(candidate) == null) return candidate
            i++
        }
    }

    private fun insertPart(part: Part, index: Int) {
        order.add(index, part)
        byKey[PartNames.key(part.name)] = part
        undo.record(LambdaChange({
            order.remove(part); byKey.remove(PartNames.key(part.name)); relsCache.remove(PartNames.key(part.name))
        }, {
            order.add(index, part); byKey[PartNames.key(part.name)] = part
        }))
        onPartModified(part)
    }

    private fun detachPart(part: Part) {
        val index = order.indexOf(part)
        if (index < 0) return
        order.removeAt(index)
        byKey.remove(PartNames.key(part.name))
        relsCache.remove(PartNames.key(part.name))
        undo.record(LambdaChange({
            order.add(index, part); byKey[PartNames.key(part.name)] = part
        }, {
            order.remove(part); byKey.remove(PartNames.key(part.name)); relsCache.remove(PartNames.key(part.name))
        }))
        onPartModified(part)
    }

    // ----------------------------------------------------------------------------------------
    // Writing

    /** Writes the package. Unmodified parts are copied byte-identical, in their original order. */
    fun writeTo(output: OutputStream) {
        val zos = ZipOutputStream(output)
        zipComment?.let { zos.setComment(it) }
        for (part in order) {
            val info = part.zipInfo
            val entry = ZipEntry(info?.entryName ?: PartNames.toZipEntry(part.name))
            entry.time = info?.time ?: FIXED_TIME
            info?.comment?.let { entry.comment = it }
            val method = info?.method ?: if (part.isDirectory) ZipEntry.STORED else ZipEntry.DEFLATED
            if (method == ZipEntry.STORED) {
                val data = if (part.isDirectory) ByteArray(0) else part.bytes()
                val crc = CRC32().apply { update(data) }
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = crc.value
                zos.putNextEntry(entry)
                zos.write(data)
            } else {
                entry.method = ZipEntry.DEFLATED
                zos.putNextEntry(entry)
                if (!part.isDirectory) part.writeContent(zos)
            }
            zos.closeEntry()
        }
        zos.finish()
        zos.flush()
    }

    fun toBytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    /**
     * Saves to [target] crash-safely: the package is written to a temporary file, verified
     * ([PackageVerifier] plus the format's own [verify]), then atomically moved over [target].
     * After saving over the source file the package keeps reading from the new file.
     */
    fun save(target: File, verify: (File) -> Unit = {}) {
        val changed = order.filter { (it.isModified || it.isNew) && !it.isDirectory }.map { it.name }.toSet()
        AtomicFile.write(target, verify = { tmp ->
            PackageVerifier.verify(tmp, changed)
            verify(tmp)
        }) { out -> writeTo(out) }
        undo.markSaved()
        val src = sourceFile
        if (src != null && src.canonicalFile == target.canonicalFile) rebase(target)
    }

    /** After a save over the source, unmodified parts must be read from the new file. */
    private fun rebase(file: File) {
        // Parts with in-memory content keep it; the others now refer to the rewritten entries,
        // which carry the same names and content.
        val old = source
        source = ZipFileSource(file)
        sourceFile = file
        try { old.close() } catch (_: Exception) {}
    }

    override fun close() {
        source.close()
    }

    companion object {
        /** DOS timestamp used for new entries (1980-01-01), so output does not depend on the clock. */
        private const val FIXED_TIME = 315532800000L + 12 * 3600 * 1000L

        /** Upper bounds that protect against ZIP bombs. */
        var maxEntries = 20_000
        var maxTotalUncompressed = 2L * 1024 * 1024 * 1024

        fun open(file: File): OpcPackage {
            val source = try { ZipFileSource(file) } catch (e: Exception) { throw OpcException("Not a ZIP archive: ${e.message}", e) }
            try {
                val zip = ZipFile(file)
                val infos = zip.use { z ->
                    val list = ArrayList<ZipInfo>()
                    var total = 0L
                    val en = z.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        list += ZipInfo(e.name, e.method, e.time, e.comment, e.size)
                        if (e.size > 0) total += e.size
                        if (list.size > maxEntries || total > maxTotalUncompressed) throw OpcException("Archive is too large to open safely")
                    }
                    list
                }
                val comment = ZipFile(file).use { it.comment }
                return OpcPackage(source, file, infos, comment)
            } catch (e: Exception) {
                source.close()
                throw if (e is OpcException) e else OpcException("Cannot read package: ${e.message}", e)
            }
        }

        fun open(bytes: ByteArray): OpcPackage = open(bytes.inputStream())

        fun open(input: InputStream): OpcPackage {
            val entries = LinkedHashMap<String, ByteArray>()
            val infos = ArrayList<ZipInfo>()
            var total = 0L
            try {
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        val data = zis.readBytes()
                        total += data.size
                        if (infos.size >= maxEntries || total > maxTotalUncompressed) throw OpcException("Archive is too large to open safely")
                        if (entries.containsKey(e.name)) throw OpcException("Duplicate ZIP entry ${e.name}")
                        entries[e.name] = data
                        infos += ZipInfo(e.name, e.method, e.time, e.comment, data.size.toLong())
                    }
                }
            } catch (e: OpcException) {
                throw e
            } catch (e: Exception) {
                throw OpcException("Not a ZIP archive: ${e.message}", e)
            }
            if (infos.isEmpty()) throw OpcException("Not a ZIP archive")
            return OpcPackage(MemorySource(entries), null, infos, null)
        }

        /** Creates a package in memory from entry name -> bytes (used for templates). */
        fun fromEntries(entries: Map<String, ByteArray>): OpcPackage {
            val infos = entries.map { (k, v) -> ZipInfo(k, ZipEntry.DEFLATED, FIXED_TIME, null, v.size.toLong()) }
            return OpcPackage(MemorySource(entries), null, infos, null)
        }
    }
}
