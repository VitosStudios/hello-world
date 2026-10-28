package com.vitosstudios.suite.opc

import com.vitosstudios.suite.xml.XmlCodec
import java.io.File
import java.util.zip.ZipFile

/**
 * Structural check run on every file before it replaces the original: the archive opens, every
 * entry decompresses with a valid CRC, [Content_Types].xml and the package relationships parse,
 * the main document part exists and parses, and every part in [changedParts] that is XML parses.
 * Parts that were copied unchanged are not re-validated: they are byte-identical to the source,
 * even if the source contained something this app cannot parse.
 */
object PackageVerifier {
    fun verify(file: File, changedParts: Set<String> = emptySet()) {
        ZipFile(file).use { zip ->
            val names = HashSet<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!names.add(e.name.lowercase())) throw OpcException("Duplicate entry ${e.name}")
                // Reading to the end verifies the CRC.
                zip.getInputStream(e).use { s -> val buf = ByteArray(1 shl 16); while (s.read(buf) >= 0) {} }
            }
        }
        OpcPackage.open(file).use { pkg ->
            pkg.requirePart(PartNames.CONTENT_TYPES).xml()
            pkg.part(PartNames.PACKAGE_RELS)?.xml() ?: throw OpcException("Package relationships are missing")
            val main = pkg.mainDocument() ?: throw OpcException("Main document part is missing")
            main.xml()
            for (name in changedParts) {
                val p = pkg.part(name) ?: throw OpcException("Saved part $name is missing")
                if (isXml(p)) XmlCodec.parse(p.bytes())
            }
        }
    }

    fun isXml(p: Part): Boolean {
        if (PartNames.isRelationshipPart(p.name) || p.name.equals(PartNames.CONTENT_TYPES, true)) return true
        val ct = p.contentType ?: return p.extension == "xml"
        return ct.endsWith("+xml") || ct == ContentTypeNames.XML || ct == "text/xml"
    }
}
