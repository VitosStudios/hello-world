package com.vitosstudios.suite.opc

import java.net.URLDecoder

/** Helpers for OPC part names ("/word/document.xml"). Part names compare case-insensitively. */
object PartNames {
    const val CONTENT_TYPES = "/[Content_Types].xml"
    const val PACKAGE_RELS = "/_rels/.rels"

    fun fromZipEntry(entry: String): String = "/" + entry.trimStart('/')
    fun toZipEntry(partName: String): String = partName.removePrefix("/")
    fun key(partName: String): String = partName.lowercase()

    fun directory(partName: String): String = partName.substring(0, partName.lastIndexOf('/') + 1)
    fun fileName(partName: String): String = partName.substring(partName.lastIndexOf('/') + 1)
    fun extension(partName: String): String =
        fileName(partName).substringAfterLast('.', "").lowercase()

    fun isRelationshipPart(partName: String): Boolean =
        partName.endsWith(".rels", ignoreCase = true) && partName.contains("/_rels/")

    /** "/word/document.xml" -> "/word/_rels/document.xml.rels"; null source -> package rels. */
    fun relsPartFor(sourcePartName: String?): String =
        if (sourcePartName == null) PACKAGE_RELS
        else directory(sourcePartName) + "_rels/" + fileName(sourcePartName) + ".rels"

    /** Inverse of [relsPartFor]; returns "" for the package relationships. */
    fun sourceOfRelsPart(relsPartName: String): String? {
        if (relsPartName.equals(PACKAGE_RELS, ignoreCase = true)) return ""
        val idx = relsPartName.lastIndexOf("/_rels/")
        if (idx < 0 || !relsPartName.endsWith(".rels", ignoreCase = true)) return null
        return relsPartName.substring(0, idx + 1) + relsPartName.substring(idx + 7, relsPartName.length - 5)
    }

    /** Resolves a relationship target against the directory of [sourcePartName] (or "/"). */
    fun resolve(sourcePartName: String?, target: String): String {
        val clean = target.substringBefore('#')
        val base = if (clean.startsWith("/")) "" else if (sourcePartName == null) "/" else directory(sourcePartName)
        val segments = ArrayList<String>()
        for (seg in (base + clean).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments += seg
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun percentDecode(name: String): String =
        if ('%' !in name) name else try { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { name }

    /** Relative reference from [sourcePartName]'s directory to [targetPartName]. */
    fun relativize(sourcePartName: String?, targetPartName: String): String {
        val fromDir = if (sourcePartName == null) "/" else directory(sourcePartName)
        val from = fromDir.trim('/').split('/').filter { it.isNotEmpty() }
        val to = targetPartName.trim('/').split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common].equals(to[common], ignoreCase = true)) common++
        val ups = List(from.size - common) { ".." }
        return (ups + to.drop(common)).joinToString("/")
    }
}
