package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XText
import com.vitosstudios.suite.xml.XmlEscaping
import com.vitosstudios.suite.xml.XmlParser

/**
 * The shared string table. Existing entries (including rich text runs and phonetic data) are
 * never rewritten; new strings are appended.
 */
internal class SharedStrings(private val pkg: OpcPackage, private val workbookPart: Part) {
    private var part: Part? = pkg.related(workbookPart, RelTypes.SHARED_STRINGS)
    private var cacheVersion = -1L
    private var strings: ArrayList<String> = ArrayList()
    private var lookup: HashMap<String, Int>? = null

    private fun refresh() {
        val p = part
        if (p == null || pkg.part(p.name) == null) {
            // The table may have been removed by undo.
            part = pkg.related(workbookPart, RelTypes.SHARED_STRINGS)
            if (part == null) { strings = ArrayList(); lookup = null; cacheVersion = -1; return }
        }
        val doc = part!!.xmlOrNull() ?: run { strings = ArrayList(); return }
        if (doc.modCount == cacheVersion) return
        val list = ArrayList<String>()
        for (si in doc.root.children) {
            if (si !is XElement || si.localName != "si") continue
            list += textOf(si)
        }
        strings = list
        lookup = null
        cacheVersion = doc.modCount
    }

    val size: Int get() { refresh(); return strings.size }

    fun get(index: Int): String? { refresh(); return strings.getOrNull(index) }

    /** Index of [text], appending a new plain entry if needed (undoable). */
    fun indexOf(text: String): Int {
        refresh()
        val map = lookup ?: buildLookup()
        map[text]?.let { bumpCount(); return it }
        val p = part ?: create()
        val doc = p.xml()
        val root = doc.root
        val si = root.createChild(Namespaces.S, "si")
        val t = si.createChild(Namespaces.S, "t")
        if (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace() || text.contains('\n'))) {
            t.setAttribute("xml:space", "preserve")
        }
        if (text.isNotEmpty()) t.appendChild(XText(XmlEscaping.escapeText(text)))
        si.appendChild(t)
        root.appendChild(si)
        val idx = strings.size
        strings.add(text)
        root.setAttr("uniqueCount", strings.size.toString())
        bumpCount()
        (lookup ?: buildLookup())[text] = idx
        cacheVersion = doc.modCount
        return idx
    }

    private fun buildLookup(): HashMap<String, Int> {
        val m = HashMap<String, Int>()
        val p = part
        if (p != null) {
            var i = 0
            for (si in p.xml().root.children) {
                if (si !is XElement || si.localName != "si") continue
                val elems = si.children.filterIsInstance<XElement>()
                if (elems.size == 1 && elems[0].localName == "t" && i < strings.size) m.putIfAbsent(strings[i], i)
                i++
            }
        }
        lookup = m
        return m
    }

    private fun bumpCount() {
        val root = part?.xml()?.root ?: return
        val c = root.a("count")?.toIntOrNull() ?: return
        root.setAttr("count", (c + 1).toString())
    }

    private fun create(): Part {
        val doc = XmlParser.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<sst xmlns="${Namespaces.S}" count="0" uniqueCount="0"></sst>""",
        )
        val dir = workbookPart.name.substringBeforeLast('/')
        val name = if (pkg.part("$dir/sharedStrings.xml") == null) "$dir/sharedStrings.xml" else pkg.uniquePartName("$dir/sharedStrings", "xml")
        val p = pkg.addXmlPart(name, ContentTypeNames.SML_SHARED_STRINGS, doc)
        pkg.relationships(workbookPart).addTo(RelTypes.SHARED_STRINGS, name)
        part = p
        strings = ArrayList()
        lookup = HashMap()
        cacheVersion = p.xml().modCount
        return p
    }

    companion object {
        /** Visible text of a `<si>` or `<is>`: plain `<t>` or the `<t>` of every rich run, without phonetic runs. */
        fun textOf(si: XElement): String {
            var single: String? = null
            var sb: StringBuilder? = null
            for (c in si.children) {
                if (c !is XElement) continue
                val piece = when (c.localName) {
                    "t" -> c.textContent
                    "r" -> c.firstChild("t")?.textContent ?: ""
                    else -> continue
                }
                if (single == null && sb == null) {
                    single = piece
                } else {
                    if (sb == null) sb = StringBuilder(single!!)
                    sb.append(piece)
                }
            }
            return sb?.toString() ?: single ?: ""
        }
    }
}
