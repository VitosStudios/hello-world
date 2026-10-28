package com.vitosstudios.suite.xml

class XmlParseException(message: String, val offset: Int = -1) : Exception(
    if (offset >= 0) "$message at offset $offset" else message,
)

/**
 * A small, strict-enough, lossless XML parser. It accepts the XML used in Office Open XML
 * packages (no external entities, no DTD processing) and keeps every source character so that
 * [XDocument.toXmlString] reproduces the input.
 */
class XmlParser private constructor(private val s: String) {
    private var i = 0
    private val n = s.length

    companion object {
        fun parse(text: String): XDocument = XmlParser(text).parseDocument()
    }

    private fun fail(msg: String): Nothing = throw XmlParseException(msg, i)

    private fun parseDocument(): XDocument {
        var decl: String? = null
        if (s.startsWith("<?xml", 0) && n > 5 && (s[5].isXmlWhitespace() || s[5] == '?')) {
            val end = s.indexOf("?>", 5)
            if (end < 0) fail("Unterminated XML declaration")
            decl = s.substring(0, end + 2)
            i = end + 2
        }
        val prolog = ArrayList<XNode>()
        parseMisc(prolog, allowDoctype = true)
        if (i >= n || s[i] != '<') fail("Missing root element")
        val root = parseElementTree()
        val epilog = ArrayList<XNode>()
        parseMisc(epilog, allowDoctype = false)
        if (i < n) fail("Content after the root element")
        return XDocument(root, decl, prolog, epilog)
    }

    private fun parseMisc(into: MutableList<XNode>, allowDoctype: Boolean) {
        while (i < n) {
            when {
                s.startsWith("<!--", i) -> into += parseComment()
                s.startsWith("<?", i) -> into += parsePI()
                allowDoctype && s.startsWith("<!DOCTYPE", i) -> into += parseDocType()
                s[i] == '<' -> return
                else -> {
                    val start = i
                    while (i < n && s[i] != '<') {
                        if (!s[i].isXmlWhitespace()) fail("Text outside the root element")
                        i++
                    }
                    into += XText(s.substring(start, i))
                }
            }
        }
    }

    private fun parseComment(): XComment {
        val end = s.indexOf("-->", i + 4)
        if (end < 0) fail("Unterminated comment")
        val c = XComment(s.substring(i + 4, end))
        i = end + 3
        return c
    }

    private fun parsePI(): XProcessingInstruction {
        val end = s.indexOf("?>", i + 2)
        if (end < 0) fail("Unterminated processing instruction")
        val pi = XProcessingInstruction(s.substring(i + 2, end))
        i = end + 2
        return pi
    }

    private fun parseCData(): XCData {
        val end = s.indexOf("]]>", i + 9)
        if (end < 0) fail("Unterminated CDATA section")
        val c = XCData(s.substring(i + 9, end))
        i = end + 3
        return c
    }

    private fun parseDocType(): XDocType {
        val start = i
        var depth = 0
        var quote: Char? = null
        while (i < n) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) {
                    i++
                    return XDocType(s.substring(start, i))
                }
            }
            i++
        }
        fail("Unterminated DOCTYPE")
    }

    private fun readName(): String {
        val start = i
        while (i < n) {
            val c = s[i]
            if (c.isXmlWhitespace() || c == '>' || c == '/' || c == '=' || c == '<') break
            i++
        }
        if (i == start) fail("Expected a name")
        return s.substring(start, i)
    }

    private fun readWhitespace(): String {
        val start = i
        while (i < n && s[i].isXmlWhitespace()) i++
        return if (start == i) "" else s.substring(start, i)
    }

    /** Parses the start tag at [i]; returns the element and whether it was self-closing. */
    private fun parseStartTag(): XElement {
        i++ // '<'
        val name = readName()
        val attrs = ArrayList<XAttribute>(4)
        while (true) {
            val ws = readWhitespace()
            if (i >= n) fail("Unterminated start tag <$name")
            val c = s[i]
            if (c == '/') {
                if (i + 1 >= n || s[i + 1] != '>') fail("Expected '/>'")
                i += 2
                return XElement(name, attrs, ws, "", selfClosing = true)
            }
            if (c == '>') {
                i++
                return XElement(name, attrs, ws, "", selfClosing = false)
            }
            if (ws.isEmpty() && attrs.isNotEmpty()) fail("Missing whitespace between attributes")
            val attrName = readName()
            val eqStart = i
            readWhitespace()
            if (i >= n || s[i] != '=') fail("Expected '=' after attribute $attrName")
            i++
            readWhitespace()
            val eq = s.substring(eqStart, i)
            if (i >= n) fail("Unterminated attribute")
            val q = s[i]
            if (q != '"' && q != '\'') fail("Expected a quote")
            val end = s.indexOf(q, i + 1)
            if (end < 0) fail("Unterminated attribute value")
            val raw = s.substring(i + 1, end)
            if (raw.indexOf('<') >= 0) fail("'<' in attribute value")
            i = end + 1
            attrs += XAttribute(attrName, raw, q, ws, eq)
        }
    }

    private fun parseElementTree(): XElement {
        val root = parseStartTag()
        if (root.selfClosing) return root
        val stack = ArrayDeque<XElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (i >= n) fail("Unexpected end of input inside <${stack.last().qName}>")
            val current = stack.last()
            val c = s[i]
            if (c != '<') {
                val start = i
                val lt = s.indexOf('<', i)
                i = if (lt < 0) n else lt
                current.attachParsed(XText(s.substring(start, i)))
                continue
            }
            when {
                s.startsWith("</", i) -> {
                    i += 2
                    val name = readName()
                    if (name != current.qName) fail("Mismatched end tag </$name>, expected </${current.qName}>")
                    current.endTagTrailing = readWhitespace()
                    if (i >= n || s[i] != '>') fail("Expected '>'")
                    i++
                    stack.removeLast()
                }
                s.startsWith("<!--", i) -> current.attachParsed(parseComment())
                s.startsWith("<![CDATA[", i) -> current.attachParsed(parseCData())
                s.startsWith("<?", i) -> current.attachParsed(parsePI())
                s.startsWith("<!", i) -> fail("Unexpected markup declaration")
                else -> {
                    val child = parseStartTag()
                    current.attachParsed(child)
                    if (!child.selfClosing) stack.addLast(child)
                }
            }
        }
        return root
    }
}

internal fun Char.isXmlWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'
