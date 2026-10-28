package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XAttribute
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlEscaping
import com.vitosstudios.suite.xml.XmlParser

/**
 * Builds new XML content from text written with the conventional prefixes (p, a, r, p14) and
 * adapts it to the prefixes a target document actually uses, so inserted elements never carry
 * redundant or conflicting namespace declarations.
 */
internal object Fragments {
    const val P14 = "http://schemas.microsoft.com/office/powerpoint/2010/main"

    private val CONVENTIONAL = linkedMapOf(
        "p" to Namespaces.P, "a" to Namespaces.A, "r" to Namespaces.R, "p14" to P14,
    )

    /** Parses [xml] (one element) and returns it detached, with prefixes valid under [target]. */
    fun build(xml: String, target: XElement): XElement {
        val decls = CONVENTIONAL.entries.joinToString(" ") { (p, u) -> "xmlns:$p=\"$u\"" }
        val doc = XmlParser.parse("<wrapper $decls>$xml</wrapper>")
        val element = doc.root.elements().single()
        doc.root.removeChild(element)
        val declared = LinkedHashMap<String, String>()
        rewrite(element, target, declared)
        for ((prefix, uri) in declared) element.setAttribute("xmlns:$prefix", Namespaces.serializedUri(uri, target))
        return element
    }

    private fun rewrite(e: XElement, target: XElement, declared: MutableMap<String, String>) {
        e.prefix?.let { p ->
            val uri = CONVENTIONAL[p] ?: return@let
            val tp = target.lookupPrefix(uri)
            when {
                tp == null -> declared[p] = uri
                tp.isEmpty() -> e.rename(e.localName)
                tp != p -> e.rename("$tp:${e.localName}")
            }
        }
        for (a in e.attributes.toList()) {
            val p = a.prefix ?: continue
            if (a.isNamespaceDeclaration) continue
            val uri = CONVENTIONAL[p] ?: continue
            val tp = target.lookupPrefix(uri)
            when {
                tp.isNullOrEmpty() -> declared[p] = uri
                tp != p -> {
                    val value = a.value
                    e.removeAttribute(a.qName)
                    e.setAttribute("$tp:${a.localName}", value)
                }
            }
        }
        for (c in e.elements()) rewrite(c, target, declared)
    }

    fun esc(s: String): String = XmlEscaping.escapeText(sanitize(s))

    /**
     * Removes characters that XML 1.0 cannot represent (control characters other than tab,
     * newline and carriage return, U+FFFE/U+FFFF and unpaired surrogates). [LINE_BREAK] is kept
     * because callers turn it into a:br before it reaches the XML.
     */
    fun sanitize(s: String): String {
        if (s.all { it >= ' ' && it < '\uD800' || it == '\t' || it == '\n' || it == '\r' || it == LINE_BREAK }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i += 2; continue
                }
                Character.isSurrogate(c) -> {}
                c < ' ' && c != '\t' && c != '\n' && c != '\r' && c != LINE_BREAK -> {}
                c == '\uFFFE' || c == '\uFFFF' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun attr(s: String): String = XmlEscaping.escapeAttribute(s)

    /** Copies attributes and children of [from] into a new detached element named [qName]. */
    fun copyAs(from: XElement, qName: String): XElement {
        val copy = from.deepCopy()
        val e = XElement(qName, copy.attributes.mapTo(ArrayList()) { XAttribute(it.qName, it.rawValue) })
        for (c in copy.children.toList()) {
            copy.removeChild(c)
            e.appendChild(c)
        }
        return e
    }
}
