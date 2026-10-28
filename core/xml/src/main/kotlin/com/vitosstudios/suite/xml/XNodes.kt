package com.vitosstudios.suite.xml

import com.vitosstudios.suite.common.LambdaChange
import java.nio.charset.Charset

/**
 * Lossless XML tree.
 *
 * Every node keeps the exact source text it was parsed from: attribute order, quote characters,
 * the whitespace between attributes, entity and character references, empty-element syntax,
 * comments and processing instructions. Serialising an unmodified tree reproduces the input
 * byte for byte. Mutations go through methods that report an undoable change to the owning
 * [XDocument]; nodes that are not attached to a document (being built) are mutated silently.
 */
sealed class XNode {
    var parent: XElement? = null
        internal set

    /** The document this node is attached to, or null while detached. */
    val document: XDocument?
        get() {
            var n: XNode = this
            while (true) {
                val p = n.parent ?: return (n as? XElement)?.ownerDocument
                n = p
            }
        }

    abstract fun writeTo(sb: StringBuilder)
    abstract fun deepCopy(): XNode

    /** Removes this node from its parent (undoable). */
    fun detach() {
        parent?.removeChild(this)
    }

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()
}

/** Character data, stored as written in the source (entity references unexpanded). */
class XText(raw: String) : XNode() {
    var raw: String = raw
        private set

    /** Decoded text. Setting it stores an escaped form. */
    var text: String
        get() = XmlEscaping.decode(raw)
        set(value) = setRaw(XmlEscaping.escapeText(value))

    fun setRaw(newRaw: String) {
        val old = raw
        if (old == newRaw) return
        raw = newRaw
        document?.recordChange(LambdaChange({ raw = old; document?.touch() }, { raw = newRaw; document?.touch() }))
    }

    override fun writeTo(sb: StringBuilder) { sb.append(raw) }
    override fun deepCopy(): XNode = XText(raw)
}

class XCData(val content: String) : XNode() {
    override fun writeTo(sb: StringBuilder) { sb.append("<![CDATA[").append(content).append("]]>") }
    override fun deepCopy(): XNode = XCData(content)
}

class XComment(val content: String) : XNode() {
    override fun writeTo(sb: StringBuilder) { sb.append("<!--").append(content).append("-->") }
    override fun deepCopy(): XNode = XComment(content)
}

/** Processing instruction; [content] is everything between `<?` and `?>`. */
class XProcessingInstruction(val content: String) : XNode() {
    override fun writeTo(sb: StringBuilder) { sb.append("<?").append(content).append("?>") }
    override fun deepCopy(): XNode = XProcessingInstruction(content)
}

/** A DOCTYPE declaration kept verbatim. */
class XDocType(val raw: String) : XNode() {
    override fun writeTo(sb: StringBuilder) { sb.append(raw) }
    override fun deepCopy(): XNode = XDocType(raw)
}

/**
 * An attribute as written: [leadingWhitespace] precedes the name, [equals] is the raw text
 * between name and opening quote (normally "="), [rawValue] is the escaped value.
 */
class XAttribute(
    val qName: String,
    rawValue: String,
    val quote: Char = '"',
    val leadingWhitespace: String = " ",
    val equals: String = "=",
) {
    var rawValue: String = rawValue
        internal set

    val prefix: String? get() = qName.indexOf(':').let { if (it < 0) null else qName.substring(0, it) }
    val localName: String get() = qName.substringAfter(':')
    val value: String get() = XmlEscaping.decode(rawValue)

    /** True for `xmlns` and `xmlns:p` declarations. */
    val isNamespaceDeclaration: Boolean get() = qName == "xmlns" || qName.startsWith("xmlns:")

    fun copy(): XAttribute = XAttribute(qName, rawValue, quote, leadingWhitespace, equals)

    internal fun writeTo(sb: StringBuilder) {
        sb.append(leadingWhitespace).append(qName).append(equals).append(quote).append(rawValue).append(quote)
    }
}

class XElement(
    qName: String,
    attributes: MutableList<XAttribute> = ArrayList(2),
    /** Raw whitespace before `>` or `/>` in the start tag. */
    var startTagTrailing: String = "",
    /** Raw whitespace inside the end tag, before `>`. */
    var endTagTrailing: String = "",
    /** True if the element was written as `<x/>`. Ignored as soon as it has children. */
    var selfClosing: Boolean = true,
) : XNode() {
    var qName: String = qName
        private set
    private val attrs: MutableList<XAttribute> = attributes
    private val kids: MutableList<XNode> = ArrayList(0)

    /** Set on the root element only. */
    internal var ownerDocument: XDocument? = null

    val attributes: List<XAttribute> get() = attrs
    val children: List<XNode> get() = kids

    val prefix: String? get() = qName.indexOf(':').let { if (it < 0) null else qName.substring(0, it) }
    val localName: String get() = qName.substringAfter(':')

    /** Canonical namespace URI of this element (see [Namespaces.canonical]). */
    val ns: String? get() = lookupNamespaceUri(prefix)?.let(Namespaces::canonical)

    fun isA(nsUri: String, local: String): Boolean = localName == local && ns == nsUri

    // ----------------------------------------------------------------------------------------
    // Namespaces

    /** Raw (non-canonical) namespace URI bound to [prefix] in scope, or null. */
    fun lookupNamespaceUri(prefix: String?): String? {
        if (prefix == "xml") return Namespaces.XML
        val key = if (prefix == null) "xmlns" else "xmlns:$prefix"
        var e: XElement? = this
        while (e != null) {
            for (a in e.attrs) if (a.qName == key) return a.value.ifEmpty { null }
            e = e.parent
        }
        return null
    }

    /** A prefix bound (in scope) to a URI whose canonical form is [nsUri]; "" for the default namespace. */
    fun lookupPrefix(nsUri: String): String? {
        var e: XElement? = this
        while (e != null) {
            for (a in e.attrs) {
                if (!a.isNamespaceDeclaration) continue
                if (Namespaces.canonical(a.value) != nsUri) continue
                val p = if (a.qName == "xmlns") "" else a.qName.substring(6)
                // Make sure the binding is not shadowed closer to this element.
                val resolved = lookupNamespaceUri(p.ifEmpty { null })
                if (resolved != null && Namespaces.canonical(resolved) == nsUri) return p
            }
            e = e.parent
        }
        return null
    }

    // ----------------------------------------------------------------------------------------
    // Navigation

    fun elements(): Sequence<XElement> = kids.asSequence().filterIsInstance<XElement>()

    fun elements(nsUri: String, local: String): Sequence<XElement> =
        elements().filter { it.localName == local && it.ns == nsUri }

    fun element(nsUri: String, local: String): XElement? = elements(nsUri, local).firstOrNull()

    fun descendants(): Sequence<XElement> = sequence {
        val stack = ArrayDeque<XElement>()
        for (i in kids.indices.reversed()) (kids[i] as? XElement)?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            yield(e)
            for (i in e.kids.indices.reversed()) (e.kids[i] as? XElement)?.let { stack.addLast(it) }
        }
    }

    fun descendants(nsUri: String, local: String): Sequence<XElement> =
        descendants().filter { it.localName == local && it.ns == nsUri }

    fun ancestors(): Sequence<XElement> = generateSequence(parent) { it.parent }

    fun indexOf(child: XNode): Int = kids.indexOfFirst { it === child }

    /** Concatenated decoded text of all descendant text and CDATA nodes. */
    val textContent: String
        get() {
            val sb = StringBuilder()
            fun walk(n: XNode) {
                when (n) {
                    is XText -> sb.append(n.text)
                    is XCData -> sb.append(n.content)
                    is XElement -> n.kids.forEach(::walk)
                    else -> {}
                }
            }
            kids.forEach(::walk)
            return sb.toString()
        }

    // ----------------------------------------------------------------------------------------
    // Attributes

    fun attribute(qName: String): XAttribute? = attrs.firstOrNull { it.qName == qName }

    /** Attribute lookup by namespace; [nsUri] null means an unprefixed attribute. */
    fun attribute(nsUri: String?, local: String): XAttribute? {
        for (a in attrs) {
            if (a.localName != local || a.isNamespaceDeclaration) continue
            val p = a.prefix
            if (nsUri == null) {
                if (p == null) return a
            } else if (p != null) {
                val uri = lookupNamespaceUri(p) ?: continue
                if (Namespaces.canonical(uri) == nsUri) return a
            }
        }
        return null
    }

    /** Decoded value of the unprefixed attribute [local]. */
    fun attr(local: String): String? = attribute(null, local)?.value

    /** Decoded value of the namespaced attribute. */
    fun attr(nsUri: String, local: String): String? = attribute(nsUri, local)?.value

    /** Sets an attribute by raw qualified name. Existing attributes keep their position. */
    fun setAttribute(qName: String, value: String) {
        val raw = XmlEscaping.escapeAttribute(value)
        val idx = attrs.indexOfFirst { it.qName == qName }
        if (idx >= 0) {
            val a = attrs[idx]
            val old = a.rawValue
            if (old == raw) return
            a.rawValue = raw
            record({ a.rawValue = old }, { a.rawValue = raw })
        } else {
            val a = XAttribute(qName, raw)
            attrs.add(a)
            record({ attrs.remove(a) }, { attrs.add(a) })
        }
    }

    /**
     * Sets an attribute by namespace. For [nsUri] null the attribute is unprefixed. A namespaced
     * attribute reuses the prefix bound in scope; if none is bound, one is declared here.
     */
    fun setAttr(nsUri: String?, local: String, value: String) {
        val existing = attribute(nsUri, local)
        if (existing != null) {
            setAttribute(existing.qName, value)
            return
        }
        if (nsUri == null) {
            setAttribute(local, value)
            return
        }
        var p = lookupPrefix(nsUri)
        if (p.isNullOrEmpty()) {
            // Attributes cannot use the default namespace; declare a prefix.
            p = Namespaces.preferredPrefix(nsUri) ?: "ns${attrs.size}"
            if (lookupNamespaceUri(p) != null) p = uniquePrefix(p)
            setAttribute("xmlns:$p", Namespaces.serializedUri(nsUri, this))
        }
        setAttribute("$p:$local", value)
    }

    fun setAttr(local: String, value: String) = setAttr(null, local, value)

    fun removeAttribute(qName: String): Boolean {
        val idx = attrs.indexOfFirst { it.qName == qName }
        if (idx < 0) return false
        val a = attrs.removeAt(idx)
        record({ attrs.add(idx, a) }, { attrs.removeAt(idx) })
        return true
    }

    fun removeAttr(nsUri: String?, local: String): Boolean {
        val a = attribute(nsUri, local) ?: return false
        return removeAttribute(a.qName)
    }

    private fun uniquePrefix(base: String): String {
        var i = 1
        while (lookupNamespaceUri("$base$i") != null) i++
        return "$base$i"
    }

    // ----------------------------------------------------------------------------------------
    // Children

    fun insertChild(index: Int, node: XNode) {
        require(node.parent == null && !(node is XElement && node.ownerDocument != null)) { "Node is already attached" }
        require(index in 0..kids.size) { "Index $index out of range" }
        kids.add(index, node)
        node.parent = this
        record({ kids.removeAt(index); node.parent = null }, { kids.add(index, node); node.parent = this })
    }

    fun appendChild(node: XNode) = insertChild(kids.size, node)

    fun insertBefore(node: XNode, reference: XNode) {
        val i = indexOf(reference)
        require(i >= 0) { "Reference is not a child" }
        insertChild(i, node)
    }

    fun insertAfter(node: XNode, reference: XNode) {
        val i = indexOf(reference)
        require(i >= 0) { "Reference is not a child" }
        insertChild(i + 1, node)
    }

    fun removeChild(node: XNode) {
        val index = indexOf(node)
        require(index >= 0) { "Not a child" }
        kids.removeAt(index)
        node.parent = null
        record({ kids.add(index, node); node.parent = this }, { kids.removeAt(index); node.parent = null })
    }

    fun removeChildren(predicate: (XNode) -> Boolean) {
        for (c in kids.filter(predicate)) removeChild(c)
    }

    fun clearChildren() {
        for (c in kids.toList()) removeChild(c)
    }

    /** Replaces this element in its parent with [replacement]. */
    fun replaceWith(replacement: XNode) {
        val p = parent ?: error("Cannot replace a detached or root element")
        val i = p.indexOf(this)
        p.removeChild(this)
        p.insertChild(i, replacement)
    }

    /**
     * Inserts [child] respecting a schema sequence given as local names in [order]: directly after
     * the last sibling that may precede it, otherwise before the first sibling that must follow it.
     * Siblings whose names are not in [order] (extensions, markup compatibility) keep their place.
     */
    fun insertInOrder(child: XElement, order: List<String>) {
        val rank = order.indexOf(child.localName)
        if (rank < 0) {
            appendChild(child)
            return
        }
        var lastLower = -1
        var firstHigher = -1
        for ((i, k) in kids.withIndex()) {
            if (k !is XElement) continue
            val r = order.indexOf(k.localName)
            if (r < 0) continue
            if (r <= rank) lastLower = i else if (firstHigher < 0) firstHigher = i
        }
        val insertAt = when {
            lastLower >= 0 && (firstHigher < 0 || lastLower < firstHigher) -> lastLower + 1
            firstHigher >= 0 -> firstHigher
            else -> kids.size
        }
        insertChild(insertAt, child)
    }

    /** Returns the first child [nsUri]:[local], creating it at the schema position if missing. */
    fun getOrCreate(nsUri: String, local: String, order: List<String>): XElement =
        element(nsUri, local) ?: createChild(nsUri, local).also { insertInOrder(it, order) }

    /** Creates a detached element in [nsUri] whose prefix resolves from this element's scope. */
    fun createChild(nsUri: String, local: String): XElement {
        val p = lookupPrefix(nsUri)
        return when {
            p == null -> XElement(local).also {
                val decl = Namespaces.preferredPrefix(nsUri)
                if (decl != null && lookupNamespaceUri(decl) == null) {
                    it.qName = "$decl:$local"
                    it.attrs.add(XAttribute("xmlns:$decl", XmlEscaping.escapeAttribute(Namespaces.serializedUri(nsUri, this))))
                } else {
                    it.attrs.add(XAttribute("xmlns", XmlEscaping.escapeAttribute(Namespaces.serializedUri(nsUri, this))))
                }
            }
            p.isEmpty() -> XElement(local)
            else -> XElement("$p:$local")
        }
    }

    /** Sets the decoded text content, replacing all children with one text node. */
    fun setText(value: String) {
        val only = kids.singleOrNull()
        if (only is XText) {
            only.text = value
            return
        }
        clearChildren()
        if (value.isNotEmpty()) appendChild(XText(XmlEscaping.escapeText(value)))
    }

    /** Renames the element (prefix included). */
    fun rename(newQName: String) {
        val old = qName
        if (old == newQName) return
        qName = newQName
        record({ qName = old }, { qName = newQName })
    }

    /** Parser-only: appends without recording a change. */
    internal fun attachParsed(node: XNode) {
        kids.add(node)
        node.parent = this
    }

    override fun deepCopy(): XElement {
        val copy = XElement(qName, attrs.mapTo(ArrayList(attrs.size)) { it.copy() }, startTagTrailing, endTagTrailing, selfClosing)
        for (k in kids) {
            val c = k.deepCopy()
            c.parent = copy
            copy.kids.add(c)
        }
        return copy
    }

    /**
     * A detached deep copy that carries the namespace declarations it needs from its ancestors,
     * so it can be inserted into another document.
     */
    fun deepCopyWithNamespaces(): XElement {
        val copy = deepCopy()
        val declared = copy.attrs.filter { it.isNamespaceDeclaration }.map { it.qName }.toHashSet()
        val needed = LinkedHashMap<String, String>()
        for (e in ancestors()) {
            for (a in e.attrs) {
                if (a.isNamespaceDeclaration && a.qName !in declared && a.qName !in needed) needed[a.qName] = a.rawValue
            }
        }
        for ((q, v) in needed) copy.attrs.add(XAttribute(q, v))
        return copy
    }

    override fun writeTo(sb: StringBuilder) {
        // Iterative to cope with deeply nested input without stack overflows.
        val stack = ArrayDeque<Any>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            when (val item = stack.removeLast()) {
                is String -> sb.append(item)
                is XElement -> {
                    sb.append('<').append(item.qName)
                    for (a in item.attrs) a.writeTo(sb)
                    sb.append(item.startTagTrailing)
                    if (item.kids.isEmpty() && item.selfClosing) {
                        sb.append("/>")
                    } else {
                        sb.append('>')
                        stack.addLast("</" + item.qName + item.endTagTrailing + ">")
                        for (i in item.kids.indices.reversed()) stack.addLast(item.kids[i])
                    }
                }
                is XNode -> item.writeTo(sb)
            }
        }
    }

    private fun record(undo: () -> Unit, redo: () -> Unit) {
        val doc = document ?: return
        doc.recordChange(LambdaChange({ undo(); doc.touch() }, { redo(); doc.touch() }))
    }
}

/**
 * A parsed XML part. [xmlDeclaration] is the raw `<?xml ...?>` (or null), [prolog] and [epilog]
 * hold whatever surrounds the root element.
 */
class XDocument(
    root: XElement,
    var xmlDeclaration: String? = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""",
    val prolog: MutableList<XNode> = mutableListOf(XText("\r\n")),
    val epilog: MutableList<XNode> = mutableListOf(),
    var charset: Charset = Charsets.UTF_8,
    var byteOrderMark: ByteArray? = null,
) {
    var root: XElement = root
        private set

    init {
        root.ownerDocument = this
    }

    /** Receives every change for undo/redo. */
    var changeSink: ((com.vitosstudios.suite.common.Change) -> Unit)? = null

    /** Called after every mutation, including undo and redo. */
    var onModified: (() -> Unit)? = null

    /** Number of mutations since parsing. */
    var modCount: Long = 0
        private set

    internal fun recordChange(change: com.vitosstudios.suite.common.Change) {
        touch()
        changeSink?.invoke(change)
    }

    internal fun touch() {
        modCount++
        onModified?.invoke()
    }

    fun replaceRoot(newRoot: XElement) {
        val old = root
        old.ownerDocument = null
        newRoot.ownerDocument = this
        root = newRoot
        recordChange(LambdaChange({
            newRoot.ownerDocument = null; old.ownerDocument = this; root = old; touch()
        }, {
            old.ownerDocument = null; newRoot.ownerDocument = this; root = newRoot; touch()
        }))
    }

    fun toXmlString(): String {
        val sb = StringBuilder()
        xmlDeclaration?.let { sb.append(it) }
        prolog.forEach { it.writeTo(sb) }
        root.writeTo(sb)
        epilog.forEach { it.writeTo(sb) }
        return sb.toString()
    }

    fun toBytes(): ByteArray = XmlCodec.serialize(this)
}
