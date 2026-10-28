package com.vitosstudios.suite.opc

import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XDocument
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser

data class Relationship(
    val id: String,
    /** Canonical (transitional) relationship type. */
    val type: String,
    val target: String,
    val isExternal: Boolean,
)

/**
 * The relationships of one source part, read from and written to its `_rels/<name>.rels` part.
 * The rels part is only re-serialised when relationships are added or removed.
 */
class Relationships internal constructor(
    private val pkg: OpcPackage,
    /** Source part name, or null for the package relationships. */
    val sourcePartName: String?,
    private val relsPartName: String,
) {
    private var cacheVersion = -1L
    private var cacheDoc: XDocument? = null
    private var cache: List<Relationship> = emptyList()

    private fun doc(): XDocument? = pkg.part(relsPartName)?.xmlOrNull()

    fun all(): List<Relationship> {
        val d = doc() ?: return emptyList()
        if (d === cacheDoc && d.modCount == cacheVersion) return cache
        cache = d.root.elements().filter { it.localName == "Relationship" }.mapNotNull { e ->
            val id = e.attr("Id") ?: return@mapNotNull null
            val type = e.attr("Type") ?: return@mapNotNull null
            Relationship(id, RelTypes.canonical(type), e.attr("Target") ?: "", e.attr("TargetMode") == "External")
        }.toList()
        cacheDoc = d
        cacheVersion = d.modCount
        return cache
    }

    fun byId(id: String): Relationship? = all().firstOrNull { it.id == id }

    fun ofType(type: String): List<Relationship> = all().filter { it.type == type }

    fun firstOfType(type: String): Relationship? = all().firstOrNull { it.type == type }

    /** Absolute part name of an internal target. */
    fun resolve(rel: Relationship): String = PartNames.resolve(sourcePartName, rel.target)

    fun targetPart(rel: Relationship): Part? = if (rel.isExternal) null else pkg.part(resolve(rel))

    fun targetPart(id: String): Part? = byId(id)?.let(::targetPart)

    /** Adds a relationship to an internal part (undoable) and returns it. */
    fun addTo(type: String, targetPartName: String): Relationship =
        add(type, PartNames.relativize(sourcePartName, targetPartName), external = false)

    /** Adds a relationship (undoable). */
    fun add(type: String, target: String, external: Boolean = false): Relationship = pkg.undo.transaction("Add relationship") {
        val d = doc() ?: createRelsPart()
        val id = nextId()
        val e = d.root.createChild(Namespaces.PKG_RELATIONSHIPS, "Relationship")
        e.setAttr("Id", id)
        e.setAttr("Type", serializedType(type))
        e.setAttr("Target", target)
        if (external) e.setAttr("TargetMode", "External")
        d.root.appendChild(e)
        Relationship(id, type, target, external)
    }

    /** Changes the target of [id] (undoable), for example to edit a hyperlink address. */
    fun retarget(id: String, target: String) {
        val e = element(id) ?: throw OpcException("No relationship $id")
        e.setAttr("Target", target)
    }

    fun remove(id: String) {
        val e = element(id) ?: return
        e.detach()
    }

    private fun element(id: String): XElement? =
        doc()?.root?.elements()?.firstOrNull { it.localName == "Relationship" && it.attr("Id") == id }

    private fun nextId(): String {
        val used = all().map { it.id }.toHashSet()
        var i = all().size + 1
        while ("rId$i" in used) i++
        return "rId$i"
    }

    /** Strict packages get strict relationship types. */
    private fun serializedType(type: String): String {
        val usesStrict = pkg.part(relsPartName)?.xmlOrNull()?.root?.elements()
            ?.any { it.attr("Type")?.startsWith("http://purl.oclc.org/") == true } == true
        return if (usesStrict && type.startsWith("http://schemas.openxmlformats.org/officeDocument/2006/relationships/"))
            "http://purl.oclc.org/ooxml/officeDocument/relationships/" + type.substringAfterLast('/')
        else type
    }

    private fun createRelsPart(): XDocument {
        val d = XmlParser.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<Relationships xmlns="${Namespaces.PKG_RELATIONSHIPS}"></Relationships>""",
        )
        pkg.addXmlPart(relsPartName, ContentTypeNames.RELATIONSHIPS, d)
        pkg.contentTypes.ensureDefault("rels", ContentTypeNames.RELATIONSHIPS)
        return pkg.part(relsPartName)!!.xml()
    }
}
