package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XText

/** Characters used in [Paragraph.text] for content that is not plain text. */
object InlineChars {
    const val TAB = '\t'
    /** Line break inside a paragraph (w:br, w:cr). */
    const val LINE_BREAK = ' '
    /** Any inline object: image, field, footnote reference, symbol, page break, locked content. */
    const val OBJECT = '￼'
}

enum class AtomKind {
    IMAGE, LOCKED_DRAWING, LOCKED_OBJECT, EQUATION, FIELD, FOOTNOTE_REFERENCE, ENDNOTE_REFERENCE,
    COMMENT_REFERENCE, SYMBOL, PAGE_BREAK, COLUMN_BREAK, OTHER,
}

/**
 * An inline object occupying one [InlineChars.OBJECT] position. [deletable] atoms (images,
 * breaks, symbols) are removed when the user deletes across them; all others are protected and
 * survive text deletion so that unsupported content is never destroyed.
 */
class InlineAtom internal constructor(
    val kind: AtomKind,
    val element: XElement,
    /** Short label shown on the placeholder, e.g. "Chart" or the field result text. */
    val label: String,
    val deletable: Boolean,
    val image: DocxImage? = null,
)

internal enum class PieceKind { TEXT, TAB, BREAK, ATOM }

internal class Piece(
    val kind: PieceKind,
    val element: XElement,
    val run: XElement?,
    val start: Int,
    val text: String,
    val atom: InlineAtom?,
    val hyperlink: XElement?,
) {
    val end: Int get() = start + text.length
}

/** A span of [Paragraph.text] with one resolved character format. */
data class TextSpan(
    val start: Int,
    val end: Int,
    val format: RunFormat,
    /** External URL or "#bookmark" if the span is inside a hyperlink. */
    val link: String? = null,
)

/**
 * View over one `w:p` element. All reads go to the DOM, so the view stays valid across undo and
 * redo. Text-changing operations are only allowed when [isTextEditable]; paragraph formatting is
 * always allowed because it never touches inline content.
 */
class Paragraph internal constructor(val doc: DocxDocument, val element: XElement, val part: Part) {
    internal class Scan(val pieces: List<Piece>, val text: String, val lockReasons: Set<String>)

    private var scanVersion = -1L
    private var scanCache: Scan? = null

    internal fun scan(): Scan {
        val v = element.document?.modCount ?: -2
        scanCache?.let { if (v == scanVersion) return it }
        val pieces = ArrayList<Piece>()
        val sb = StringBuilder()
        val locks = LinkedHashSet<String>()
        Scanner(doc, pieces, sb, locks).container(element, null)
        element.w("pPr")?.let { pPr ->
            if (pPr.w("pPrChange") != null) locks += "tracked changes"
            pPr.w("rPr")?.let { if (it.w("ins") != null || it.w("del") != null) locks += "tracked changes" }
        }
        val s = Scan(pieces, sb.toString(), locks)
        scanCache = s
        scanVersion = v
        return s
    }

    /** Paragraph text; tabs, line breaks and inline objects appear as [InlineChars]. */
    val text: String get() = scan().text

    /** Plain text for search and word count: objects removed, line breaks as spaces. */
    val plainText: String
        get() = text.replace(InlineChars.OBJECT.toString(), "").replace(InlineChars.LINE_BREAK, ' ')

    val isTextEditable: Boolean get() = scan().lockReasons.isEmpty()

    /** Why the text is read-only (for the placeholder explanation), empty if editable. */
    val lockReasons: Set<String> get() = scan().lockReasons

    /** Inline objects with their positions in [text]. */
    val atoms: List<Pair<Int, InlineAtom>> get() = scan().pieces.filter { it.atom != null }.map { it.start to it.atom!! }

    val pPr: XElement? get() = element.w("pPr")

    val styleId: String? get() = pPr?.w("pStyle")?.wAttr("val")

    val format: ParagraphFormat get() = doc.styles.resolveParagraph(pPr)

    /** Heading level 1-6 (from the style or outline level), or 0 for body text. */
    val headingLevel: Int
        get() {
            val f = format
            val lvl = f.outlineLevel ?: return 0
            return if (lvl in 0..5) lvl + 1 else 0
        }

    /** True if this paragraph carries the section break of a section. */
    val endsSection: Boolean get() = pPr?.w("sectPr") != null

    /** Text spans with resolved formatting, covering [text] without gaps. */
    fun spans(): List<TextSpan> {
        val s = scan()
        val pStyle = styleId
        val out = ArrayList<TextSpan>()
        var cacheRun: XElement? = null
        var cacheFmt: RunFormat? = null
        for (p in s.pieces) {
            val fmt = if (p.run === cacheRun && cacheFmt != null) cacheFmt else
                doc.styles.resolveRun(pStyle, p.run?.w("rPr")).also { cacheRun = p.run; cacheFmt = it }
            val link = p.hyperlink?.let { doc.hyperlinkTarget(part, it) }
            val last = out.lastOrNull()
            if (last != null && last.end == p.start && last.format == fmt && last.link == link) {
                out[out.size - 1] = last.copy(end = p.end)
            } else {
                out += TextSpan(p.start, p.end, fmt, link)
            }
        }
        return out
    }

    /** Resolved format at [offset] (of the character before it, like a caret). */
    fun formatAt(offset: Int): RunFormat {
        val spans = spans()
        val span = spans.lastOrNull { it.start < offset && offset <= it.end } ?: spans.firstOrNull()
        return span?.format ?: doc.styles.resolveRun(styleId, pPr?.w("rPr"))
    }

    // ------------------------------------------------------------------------------------
    // Text editing

    private fun requireEditable() {
        if (!isTextEditable) throw ReadOnlyContentException("This paragraph contains ${lockReasons.joinToString()} and cannot be edited as text")
    }

    /** Replaces [start, end) with [newText] (undoable). Protected objects in the range survive. */
    fun replaceText(start: Int, end: Int, newText: String) {
        requireEditable()
        val len = text.length
        require(start in 0..end && end <= len) { "Range $start..$end outside 0..$len" }
        if (start == end && newText.isEmpty()) return
        doc.pkg.undo.transaction("Typing") {
            if (end > start) deleteRange(start, end)
            if (newText.isNotEmpty()) insertRaw(start, newText)
            removeEmptyRuns()
        }
    }

    fun insertText(offset: Int, value: String) = replaceText(offset, offset, value)

    fun deleteText(start: Int, end: Int) = replaceText(start, end, "")

    private fun deleteRange(start: Int, end: Int) {
        for (p in scan().pieces) {
            if (p.end <= start || p.start >= end) continue
            when (p.kind) {
                PieceKind.TEXT -> {
                    val a = maxOf(start, p.start) - p.start
                    val b = minOf(end, p.end) - p.start
                    val remaining = p.text.removeRange(a, b)
                    if (remaining.isEmpty()) p.element.detach() else setT(p.element, remaining)
                }
                PieceKind.TAB, PieceKind.BREAK -> if (p.start >= start && p.end <= end) p.element.detach()
                PieceKind.ATOM -> if (p.start >= start && p.end <= end && p.atom!!.deletable) p.element.detach()
            }
        }
    }

    private fun insertRaw(offset: Int, value: String) {
        val pieces = scan().pieces
        val before = pieces.lastOrNull { it.start < offset && offset <= it.end }
        if (before != null && before.kind == PieceKind.TEXT && before.run != null) {
            val k = offset - before.start
            if (value.none { it == InlineChars.TAB || it == InlineChars.LINE_BREAK || it == '\n' }) {
                setT(before.element, before.text.substring(0, k) + value + before.text.substring(k))
            } else {
                val run = before.run
                val left = before.text.substring(0, k)
                val right = before.text.substring(k)
                var anchor: XElement = before.element
                if (left.isEmpty()) {
                    // Keep the element, it becomes the right part.
                    val idx = run.indexOf(anchor)
                    insertNodes(run, idx, value)
                    setT(anchor, right)
                    if (right.isEmpty()) anchor.detach()
                } else {
                    setT(anchor, left)
                    val idx = run.indexOf(anchor) + 1
                    val count = insertNodes(run, idx, value)
                    if (right.isNotEmpty()) {
                        val t = newT(run, right)
                        run.insertChild(idx + count, t)
                    }
                }
            }
            return
        }
        if (before != null && before.run != null) {
            // After a tab, break or object in a run: continue in the same run.
            val run = before.run
            val ownerIdx = run.indexOf(before.element)
            if (ownerIdx >= 0) {
                insertNodes(run, ownerIdx + 1, value)
                return
            }
        }
        val after = pieces.firstOrNull { it.start == offset }
        if (after?.run != null && run(after) != null) {
            val run = after.run
            val idx = run.indexOf(after.element)
            if (idx >= 0) {
                insertNodes(run, idx, value)
                return
            }
        }
        // No run to join: create one with the paragraph mark's formatting.
        val run = element.newW("r")
        markRunProperties()?.let { run.appendChild(it) }
        insertNodes(run, run.children.size, value)
        val anchorPiece = after ?: before
        val anchorTop = anchorPiece?.let { topLevelChild(it.element) }
        when {
            anchorTop != null && anchorPiece === after -> element.insertBefore(run, anchorTop)
            anchorTop != null -> element.insertAfter(run, anchorTop)
            else -> element.appendChild(run)
        }
    }

    private fun run(p: Piece): XElement? = p.run

    /** The direct child of the paragraph that contains [node]. */
    private fun topLevelChild(node: XElement): XElement? {
        var n: XElement = node
        while (true) {
            val parent = n.parent ?: return null
            if (parent === element) return n
            n = parent
        }
    }

    /** Copy of the paragraph mark's run properties without revision marks, for new runs. */
    private fun markRunProperties(): XElement? {
        val src = pPr?.w("rPr") ?: return null
        val copy = src.deepCopy()
        copy.removeChildren { it is XElement && it.localName in setOf("ins", "del", "moveFrom", "moveTo", "rPrChange") }
        return if (copy.children.none { it is XElement }) null else copy
    }

    /** Inserts text as w:t / w:tab / w:br nodes at [index] of [run]; returns the node count. */
    private fun insertNodes(run: XElement, index: Int, value: String): Int {
        var i = index
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                run.insertChild(i++, newT(run, sb.toString()))
                sb.clear()
            }
        }
        for (c in value) {
            when (c) {
                InlineChars.TAB -> { flush(); run.insertChild(i++, run.newW("tab")) }
                InlineChars.LINE_BREAK, '\n' -> { flush(); run.insertChild(i++, run.newW("br")) }
                InlineChars.OBJECT -> {}
                else -> sb.append(c)
            }
        }
        flush()
        return i - index
    }

    private fun newT(context: XElement, value: String): XElement {
        val t = context.newW("t")
        if (value.first().isWhitespace() || value.last().isWhitespace()) t.setAttribute("xml:space", "preserve")
        t.appendChild(XText(com.vitosstudios.suite.xml.XmlEscaping.escapeText(value)))
        return t
    }

    private fun setT(t: XElement, value: String) {
        t.setText(value)
        if (value.isNotEmpty() && (value.first().isWhitespace() || value.last().isWhitespace())) t.setAttribute("xml:space", "preserve")
    }

    /** Removes runs left without content and hyperlinks left without runs. */
    private fun removeEmptyRuns() {
        for (r in element.descendants(Wml.W, "r").toList()) {
            if (r.elements().none { !it.isW("rPr") }) r.detach()
        }
        for (h in element.descendants(Wml.W, "hyperlink").toList()) {
            if (h.elements().none()) h.detach()
        }
    }

    /** Ensures a run boundary at [offset]; returns nothing, the DOM changes in place. */
    internal fun splitAt(offset: Int) {
        val pieces = scan().pieces
        // Inside a text piece: split the w:t first.
        pieces.firstOrNull { it.kind == PieceKind.TEXT && it.start < offset && offset < it.end }?.let { p ->
            val k = offset - p.start
            val right = newT(p.run ?: element, p.text.substring(k))
            setT(p.element, p.text.substring(0, k))
            p.element.parent!!.insertAfter(right, p.element)
        }
        val fresh = scan().pieces
        val left = fresh.lastOrNull { it.end == offset && it.start < offset } ?: return
        val right = fresh.firstOrNull { it.start == offset } ?: return
        val run = left.run ?: return
        if (right.run !== run) return
        // Split the run between left.element and right.element.
        val leftTop = childOf(run, left.element) ?: return
        val idx = run.indexOf(leftTop) + 1
        val newRun = XElement(run.qName)
        for (a in run.attributes) if (!a.isNamespaceDeclaration) newRun.setAttribute(a.qName, a.value)
        run.w("rPr")?.let { newRun.appendChild(it.deepCopy()) }
        val moving = run.children.subList(idx, run.children.size).toList()
        for (m in moving) {
            run.removeChild(m)
            newRun.appendChild(m)
        }
        run.parent!!.insertAfter(newRun, run)
    }

    private fun childOf(container: XElement, node: XElement): XElement? {
        var n = node
        while (true) {
            val p = n.parent ?: return null
            if (p === container) return n
            n = p
        }
    }

    /** Runs whose content lies entirely within [start, end). Splits runs at the boundaries. */
    internal fun runsIn(start: Int, end: Int): List<XElement> {
        splitAt(start)
        splitAt(end)
        val byRun = LinkedHashMap<XElement, MutableList<Piece>>()
        for (p in scan().pieces) p.run?.let { byRun.getOrPut(it) { ArrayList() }.add(p) }
        return byRun.filter { (_, ps) -> ps.first().start >= start && ps.last().end <= end && ps.isNotEmpty() }.keys.toList()
    }

    /** Applies character formatting to [start, end) (undoable). */
    fun applyRunFormat(start: Int, end: Int, patch: RunPatch) {
        requireEditable()
        if (start >= end) return
        doc.pkg.undo.transaction("Format") {
            for (r in runsIn(start, end)) {
                patch.applyTo(this, r.w("rPr")) { r.newW("rPr").also { r.insertChild(0, it) } }
            }
        }
    }

    /** Applies [patch] to the paragraph mark, so new text typed in an empty paragraph uses it. */
    fun applyMarkFormat(patch: RunPatch) {
        doc.pkg.undo.transaction("Format") {
            patch.applyTo(this, pPr?.w("rPr")) { ensurePPr().getOrCreate(Wml.W, "rPr", Wml.PPR_ORDER) }
        }
    }

    // ------------------------------------------------------------------------------------
    // Paragraph formatting

    internal fun ensurePPr(): XElement = pPr ?: element.newW("pPr").also { element.insertChild(0, it) }

    private fun setPPrValue(local: String, value: String?) {
        if (value == null) {
            pPr?.w(local)?.detach()
            return
        }
        val pPr = ensurePPr()
        val e = pPr.getOrCreate(Wml.W, local, Wml.PPR_ORDER)
        e.setAttr(Wml.W, "val", value)
    }

    /** Sets the paragraph style (null = the document's default paragraph style). */
    fun setStyle(styleId: String?) {
        doc.pkg.undo.transaction("Style") {
            val default = doc.styles.defaultParagraphStyleId
            setPPrValue("pStyle", if (styleId == default) null else styleId)
        }
    }

    /** Applies heading [level] 1-6 using the document's own heading styles; 0 = body text. */
    fun setHeadingLevel(level: Int) {
        require(level in 0..6)
        doc.pkg.undo.transaction("Heading") {
            if (level == 0) {
                setPPrValue("pStyle", null)
                pPr?.w("outlineLvl")?.detach()
            } else {
                setPPrValue("pStyle", doc.styles.ensureHeadingStyle(level))
            }
        }
    }

    fun setAlignment(alignment: Alignment) {
        doc.pkg.undo.transaction("Alignment") {
            setPPrValue("jc", when (alignment) {
                Alignment.LEFT -> "left"
                Alignment.CENTER -> "center"
                Alignment.RIGHT -> "right"
                Alignment.JUSTIFY -> "both"
            })
        }
    }

    /** Indentation in points; null leaves a value unchanged. [firstLine] < 0 means hanging. */
    fun setIndent(left: Float? = null, right: Float? = null, firstLine: Float? = null) {
        doc.pkg.undo.transaction("Indent") {
            val ind = ensurePPr().getOrCreate(Wml.W, "ind", Wml.PPR_ORDER)
            left?.let {
                ind.removeAttr(Wml.W, "start")
                ind.setAttr(Wml.W, "left", twips(it))
            }
            right?.let {
                ind.removeAttr(Wml.W, "end")
                ind.setAttr(Wml.W, "right", twips(it))
            }
            firstLine?.let {
                ind.removeAttr(Wml.W, "firstLine"); ind.removeAttr(Wml.W, "hanging")
                ind.removeAttr(Wml.W, "firstLineChars"); ind.removeAttr(Wml.W, "hangingChars")
                if (it >= 0) ind.setAttr(Wml.W, "firstLine", twips(it)) else ind.setAttr(Wml.W, "hanging", twips(-it))
            }
        }
    }

    /** Increases or decreases the left indent by half an inch, never below zero. */
    fun changeIndent(increase: Boolean) {
        val current = format.indentLeft
        setIndent(left = maxOf(0f, current + if (increase) 36f else -36f))
    }

    /**
     * Paragraph spacing in points and line spacing as a multiple (1.0, 1.15, 1.5, 2.0); null
     * leaves a value unchanged.
     */
    fun setSpacing(before: Float? = null, after: Float? = null, lineMultiple: Float? = null) {
        doc.pkg.undo.transaction("Spacing") {
            val sp = ensurePPr().getOrCreate(Wml.W, "spacing", Wml.PPR_ORDER)
            before?.let { sp.removeAttr(Wml.W, "beforeAutospacing"); sp.removeAttr(Wml.W, "beforeLines"); sp.setAttr(Wml.W, "before", twips(it)) }
            after?.let { sp.removeAttr(Wml.W, "afterAutospacing"); sp.removeAttr(Wml.W, "afterLines"); sp.setAttr(Wml.W, "after", twips(it)) }
            lineMultiple?.let {
                sp.setAttr(Wml.W, "line", (it * 240).toInt().toString())
                sp.setAttr(Wml.W, "lineRule", "auto")
            }
        }
    }

    fun setPageBreakBefore(on: Boolean) {
        doc.pkg.undo.transaction("Page break") {
            if (on) ensurePPr().getOrCreate(Wml.W, "pageBreakBefore", Wml.PPR_ORDER).removeAttr(Wml.W, "val")
            else pPr?.w("pageBreakBefore")?.detach()
        }
    }

    internal fun twips(points: Float): String = Math.round(points * 20).toString()

    override fun equals(other: Any?): Boolean = other is Paragraph && other.element === element
    override fun hashCode(): Int = System.identityHashCode(element)
    override fun toString(): String = "Paragraph(${text.take(40)})"
}

class ReadOnlyContentException(message: String) : IllegalStateException(message)

/** Walks paragraph content into [Piece]s. */
private class Scanner(
    val doc: DocxDocument,
    val pieces: MutableList<Piece>,
    val sb: StringBuilder,
    val locks: MutableSet<String>,
) {
    private var inComplexField = 0

    private fun add(kind: PieceKind, e: XElement, run: XElement?, text: String, atom: InlineAtom?, link: XElement?) {
        pieces += Piece(kind, e, run, sb.length, text, atom, link)
        sb.append(text)
    }

    private fun atom(kind: AtomKind, e: XElement, run: XElement?, label: String, deletable: Boolean, link: XElement?, image: DocxImage? = null) {
        add(PieceKind.ATOM, e, run, InlineChars.OBJECT.toString(), InlineAtom(kind, e, label, deletable, image), link)
    }

    fun container(c: XElement, link: XElement?) {
        for (child in c.children) {
            if (child !is XElement) continue
            val ns = child.ns
            val name = child.localName
            when (ns) {
                Wml.W -> when (name) {
                    "pPr", "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "proofErr",
                    "permStart", "permEnd", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd",
                    "customXmlInsRangeStart", "customXmlInsRangeEnd", "customXmlDelRangeStart", "customXmlDelRangeEnd",
                    "customXmlMoveFromRangeStart", "customXmlMoveFromRangeEnd", "customXmlMoveToRangeStart", "customXmlMoveToRangeEnd" -> {}
                    "r" -> run(child, link)
                    "hyperlink" -> container(child, child)
                    "smartTag", "customXml" -> container(child, link)
                    "sdt" -> child.w("sdtContent")?.let { container(it, link) }
                    "fldSimple" -> atom(AtomKind.FIELD, child, null, child.textContent.ifEmpty { "Field" }, false, link)
                    "ins", "moveTo" -> { locks += "tracked changes"; container(child, link) }
                    "del", "moveFrom" -> locks += "tracked changes"
                    "subDoc" -> atom(AtomKind.LOCKED_OBJECT, child, null, "Subdocument", false, link)
                    else -> { locks += "unsupported content"; atom(AtomKind.OTHER, child, null, name, false, link) }
                }
                Namespaces.M -> atom(AtomKind.EQUATION, child, null, "Equation", false, link)
                Namespaces.MC -> atom(AtomKind.LOCKED_DRAWING, child, null, "Shape", false, link)
                else -> { locks += "unsupported content"; atom(AtomKind.OTHER, child, null, name, false, link) }
            }
        }
    }

    private fun run(r: XElement, link: XElement?) {
        for (c in r.children) {
            if (c !is XElement) continue
            if (c.ns != Wml.W) {
                if (c.ns == Namespaces.MC) atom(AtomKind.LOCKED_DRAWING, c, r, "Shape", false, link)
                else { locks += "unsupported content"; atom(AtomKind.OTHER, c, r, c.localName, false, link) }
                continue
            }
            when (c.localName) {
                "rPr" -> if (c.w("rPrChange") != null || c.w("ins") != null || c.w("del") != null) locks += "tracked changes"
                "t" -> if (inComplexField == 1) {} else {
                    val t = c.textContent
                    if (t.isNotEmpty()) add(PieceKind.TEXT, c, r, t, null, link)
                }
                "tab", "ptab" -> add(PieceKind.TAB, c, r, InlineChars.TAB.toString(), null, link)
                "br" -> when (c.wAttr("type")) {
                    "page" -> atom(AtomKind.PAGE_BREAK, c, r, "Page break", true, link)
                    "column" -> atom(AtomKind.COLUMN_BREAK, c, r, "Column break", true, link)
                    else -> add(PieceKind.BREAK, c, r, InlineChars.LINE_BREAK.toString(), null, link)
                }
                "cr" -> add(PieceKind.BREAK, c, r, InlineChars.LINE_BREAK.toString(), null, link)
                "noBreakHyphen" -> atom(AtomKind.SYMBOL, c, r, "‑", true, link)
                "softHyphen", "lastRenderedPageBreak", "separator", "continuationSeparator", "delText", "delInstrText" -> {}
                "sym" -> atom(AtomKind.SYMBOL, c, r, Symbols.label(c), true, link)
                "drawing" -> {
                    val img = DocxImage.fromDrawing(doc, c)
                    if (img != null) atom(AtomKind.IMAGE, c, r, "Image", true, link, img)
                    else atom(AtomKind.LOCKED_DRAWING, c, r, DocxImage.describeDrawing(c), false, link)
                }
                "pict" -> atom(AtomKind.LOCKED_DRAWING, c, r, "Legacy drawing", false, link)
                "object" -> atom(AtomKind.LOCKED_OBJECT, c, r, "Embedded object", false, link)
                "fldChar" -> {
                    locks += "a field"
                    when (c.wAttr("fldCharType")) {
                        "begin" -> inComplexField = 1
                        "separate" -> inComplexField = 2
                        "end" -> inComplexField = 0
                    }
                }
                "instrText" -> locks += "a field"
                "footnoteReference" -> atom(AtomKind.FOOTNOTE_REFERENCE, c, r, c.wAttr("id") ?: "*", false, link)
                "endnoteReference" -> atom(AtomKind.ENDNOTE_REFERENCE, c, r, c.wAttr("id") ?: "*", false, link)
                "commentReference" -> atom(AtomKind.COMMENT_REFERENCE, c, r, "Comment", false, link)
                "footnoteRef", "endnoteRef", "annotationRef" -> atom(AtomKind.FIELD, c, r, "#", false, link)
                "pgNum", "dayShort", "monthShort", "yearShort", "dayLong", "monthLong", "yearLong" ->
                    atom(AtomKind.FIELD, c, r, "Field", false, link)
                "ruby" -> atom(AtomKind.LOCKED_OBJECT, c, r, "Ruby text", false, link)
                else -> { locks += "unsupported content"; atom(AtomKind.OTHER, c, r, c.localName, false, link) }
            }
        }
    }
}

/** Maps w:sym characters from symbol fonts to Unicode where common. */
internal object Symbols {
    fun label(sym: XElement): String {
        val code = sym.wAttr("char")?.toIntOrNull(16) ?: return "?"
        val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
        val font = sym.wAttr("font") ?: ""
        if (font.equals("Symbol", true)) {
            val map = mapOf(0xB7 to '•', 0xD7 to '×', 0xB0 to '°', 0xA3 to '≤', 0xB3 to '≥', 0xB9 to '≠', 0x70 to 'π')
            map[c]?.let { return it.toString() }
        }
        if (font.startsWith("Wingdings", true)) {
            val map = mapOf(0xFC to '✓', 0xFB to '✗', 0xA7 to '▪', 0x6C to '●', 0x6F to '□', 0xA8 to '◻')
            map[c]?.let { return it.toString() }
        }
        return String(Character.toChars(c))
    }
}

/** A character formatting change. Null fields are left unchanged. */
data class RunPatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizePt: Float? = null,
    /** RGB hex ("C00000"), or "auto" to remove an explicit colour. */
    val color: String? = null,
    /** ST_HighlightColor name ("yellow", "green", ...) or "none". */
    val highlight: String? = null,
    val fontFamily: String? = null,
    val superscript: Boolean? = null,
    val subscript: Boolean? = null,
    /** Removes all direct character formatting first (keeps the character style). */
    val clearDirect: Boolean = false,
) {
    /** Applies the patch to [existing] run properties, creating them with [create] if needed. */
    internal fun applyTo(p: Paragraph, existing: XElement?, create: () -> XElement) {
        var rPr = existing
        if (clearDirect && rPr != null) {
            val keep = rPr.w("rStyle")?.deepCopy()
            rPr.clearChildren()
            keep?.let { rPr!!.appendChild(it) }
        }
        fun ensure(): XElement {
            rPr?.let { return it }
            val e = create()
            rPr = e
            return e
        }
        val styleFmt by lazy {
            val r = rPr
            val styleOnly = r?.deepCopyWithNamespaces()?.also { copy -> copy.removeChildren { it is XElement && it.localName != "rStyle" } }
            p.doc.styles.resolveRun(p.styleId, styleOnly)
        }
        fun toggle(local: String, value: Boolean?, inherited: () -> Boolean) {
            if (value == null) return
            val r = ensure()
            if (value == inherited()) {
                r.w(local)?.detach()
            } else {
                val e = r.getOrCreate(Wml.W, local, Wml.RPR_ORDER)
                if (value) e.removeAttr(Wml.W, "val") else e.setAttr(Wml.W, "val", "0")
            }
        }
        toggle("b", bold) { styleFmt.bold }
        toggle("i", italic) { styleFmt.italic }
        toggle("strike", strike) { styleFmt.strike }
        if (strike == false) rPr?.w("dstrike")?.detach()
        underline?.let { u ->
            val r = ensure()
            if (u == styleFmt.underline) r.w("u")?.detach()
            else r.getOrCreate(Wml.W, "u", Wml.RPR_ORDER).setAttr(Wml.W, "val", if (u) "single" else "none")
        }
        sizePt?.let { pt ->
            val r = ensure()
            val half = Math.round(pt * 2).toString()
            r.getOrCreate(Wml.W, "sz", Wml.RPR_ORDER).setAttr(Wml.W, "val", half)
            r.getOrCreate(Wml.W, "szCs", Wml.RPR_ORDER).setAttr(Wml.W, "val", half)
        }
        color?.let { c ->
            val r = ensure()
            if (c.equals("auto", true)) r.w("color")?.detach()
            else {
                val e = r.getOrCreate(Wml.W, "color", Wml.RPR_ORDER)
                e.setAttr(Wml.W, "val", c.removePrefix("#").uppercase())
                for (a in listOf("themeColor", "themeTint", "themeShade")) e.removeAttr(Wml.W, a)
            }
        }
        highlight?.let { h ->
            val r = ensure()
            if (h == "none") r.w("highlight")?.detach() else r.getOrCreate(Wml.W, "highlight", Wml.RPR_ORDER).setAttr(Wml.W, "val", h)
        }
        fontFamily?.let { f ->
            val e = ensure().getOrCreate(Wml.W, "rFonts", Wml.RPR_ORDER)
            for (a in listOf("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme")) e.removeAttr(Wml.W, a)
            e.setAttr(Wml.W, "ascii", f)
            e.setAttr(Wml.W, "hAnsi", f)
        }
        if (superscript != null || subscript != null) {
            val r = ensure()
            val v = when {
                superscript == true -> "superscript"
                subscript == true -> "subscript"
                else -> null
            }
            if (v == null) r.w("vertAlign")?.detach() else r.getOrCreate(Wml.W, "vertAlign", Wml.RPR_ORDER).setAttr(Wml.W, "val", v)
        }
        rPr?.let { if (it.children.none { c -> c is XElement }) it.detach() }
    }
}
