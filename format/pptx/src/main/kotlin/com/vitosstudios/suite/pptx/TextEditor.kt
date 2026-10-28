package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.XElement
import kotlin.math.roundToInt

/**
 * Text edits on DrawingML paragraphs (a:p). Paragraph text is the concatenation of runs (a:r),
 * line breaks (a:br, one [LINE_BREAK] character) and fields (a:fld). Fields are atomic: they are
 * removed only when a deletion covers them completely.
 */
internal class TextEditor(private val pres: PptxPresentation) {
    data class Seg(val el: XElement, val kind: RunKind, val start: Int, val end: Int)

    fun segments(p: XElement): List<Seg> {
        val out = ArrayList<Seg>()
        var pos = 0
        for (c in p.elements()) {
            val kind = when (c.localName) {
                "r" -> RunKind.TEXT
                "br" -> RunKind.BREAK
                "fld" -> RunKind.FIELD
                else -> continue
            }
            val len = if (kind == RunKind.BREAK) 1 else (c.element(A, "t")?.textContent?.length ?: 0)
            out += Seg(c, kind, pos, pos + len)
            pos += len
        }
        return out
    }

    fun textOf(p: XElement): String = buildString {
        for (s in segments(p)) append(if (s.kind == RunKind.BREAK) LINE_BREAK.toString() else s.el.element(A, "t")?.textContent ?: "")
    }

    private fun runText(r: XElement): String = r.element(A, "t")?.textContent ?: ""

    private fun setRunText(r: XElement, value: String) {
        val t = r.element(A, "t") ?: r.createChild(A, "t").also { r.appendChild(it) }
        t.setText(value)
    }

    // ----------------------------------------------------------------------------------------

    fun replaceText(p: XElement, start: Int, end: Int, newText: String) {
        val length = textOf(p).length
        if (start < 0 || end > length || start > end) throw PptxEditException("Range $start..$end outside 0..$length")
        val template = templateProperties(p, start)
        deleteRange(p, start, end)
        if (newText.isEmpty()) return
        val normalized = Fragments.sanitize(newText).replace("\r\n", "\n").replace('\r', '\n').replace('\n', LINE_BREAK)
        if (LINE_BREAK !in normalized && insertIntoRun(p, start, normalized)) return
        val at = splitAt(p, start)
        val nodes = ArrayList<XElement>()
        for ((i, piece) in normalized.split(LINE_BREAK).withIndex()) {
            if (i > 0) nodes += newBreak(p, template)
            if (piece.isNotEmpty()) nodes += newRun(p, piece, template)
        }
        var index = at
        for (n in nodes) p.insertChild(index++, n)
    }

    /** Run properties to use for text inserted at [offset]. */
    private fun templateProperties(p: XElement, offset: Int): XElement? {
        val segs = segments(p).filter { it.kind != RunKind.BREAK }
        val seg = segs.firstOrNull { offset > it.start && offset < it.end }
            ?: segs.lastOrNull { it.end == offset && it.kind == RunKind.TEXT }
            ?: segs.firstOrNull { it.start >= offset && it.kind == RunKind.TEXT }
            ?: segs.lastOrNull { it.kind == RunKind.TEXT }
        seg?.el?.element(A, "rPr")?.let { return it.deepCopy() }
        if (seg != null) return null
        return p.element(A, "endParaRPr")?.let { Fragments.copyAs(it, it.qName.replace("endParaRPr", "rPr")) }
    }

    private fun deleteRange(p: XElement, start: Int, end: Int) {
        if (start == end) return
        for (s in segments(p).reversed()) {
            val a = maxOf(s.start, start)
            val b = minOf(s.end, end)
            if (a >= b) continue
            val covered = a == s.start && b == s.end
            when (s.kind) {
                RunKind.TEXT -> {
                    if (covered) s.el.detach() else {
                        val t = runText(s.el)
                        setRunText(s.el, t.substring(0, a - s.start) + t.substring(b - s.start))
                    }
                }
                RunKind.BREAK, RunKind.FIELD -> if (covered) s.el.detach()
            }
        }
    }

    /** Inserts plain text into an existing text run at [offset]; false if there is none. */
    private fun insertIntoRun(p: XElement, offset: Int, value: String): Boolean {
        val segs = segments(p).filter { it.kind == RunKind.TEXT }
        val seg = segs.firstOrNull { offset > it.start && offset < it.end }
            ?: segs.lastOrNull { it.end == offset }
            ?: segs.firstOrNull { it.start == offset }
            ?: return false
        val t = runText(seg.el)
        val k = offset - seg.start
        setRunText(seg.el, t.substring(0, k) + value + t.substring(k))
        return true
    }

    /**
     * Splits the run containing [offset] so that a segment boundary falls on it and returns the
     * child index where content at [offset] starts (or where new content should go).
     */
    fun splitAt(p: XElement, offset: Int): Int {
        val segs = segments(p)
        val inside = segs.firstOrNull { it.kind == RunKind.TEXT && offset > it.start && offset < it.end }
        if (inside != null) {
            val t = runText(inside.el)
            val k = offset - inside.start
            val tail = inside.el.deepCopy()
            setRunText(inside.el, t.substring(0, k))
            p.insertAfter(tail, inside.el)
            setRunText(tail, t.substring(k))
            return p.indexOf(tail)
        }
        segs.firstOrNull { it.start >= offset }?.let { return p.indexOf(it.el) }
        p.element(A, "endParaRPr")?.let { return p.indexOf(it) }
        p.elements().lastOrNull { it.localName == "extLst" }?.let { return p.indexOf(it) }
        return p.children.size
    }

    private fun newRun(context: XElement, value: String, template: XElement?): XElement {
        val r = context.createChild(A, "r")
        r.appendChild(template?.deepCopy()?.also { cleanRunProperties(it) } ?: defaultRunProperties(context))
        val t = context.createChild(A, "t")
        t.setText(value)
        r.appendChild(t)
        return r
    }

    private fun newBreak(context: XElement, template: XElement?): XElement {
        val br = context.createChild(A, "br")
        br.appendChild(template?.deepCopy()?.also { cleanRunProperties(it) } ?: defaultRunProperties(context))
        return br
    }

    private fun defaultRunProperties(context: XElement): XElement =
        context.createChild(A, "rPr").also { it.setAttr("lang", "en-US"); it.setAttr("dirty", "0") }

    /** Spell-check and "dirty" flags do not describe the new text. */
    private fun cleanRunProperties(rPr: XElement) {
        rPr.removeAttribute("err")
        rPr.removeAttribute("smtClean")
        rPr.removeAttribute("smtId")
    }

    // ----------------------------------------------------------------------------------------

    fun applyFormat(p: XElement, start: Int, end: Int, patch: RunFormatPatch) {
        val length = textOf(p).length
        if (start < 0 || end > length || start > end) throw PptxEditException("Range $start..$end outside 0..$length")
        if (start == end) {
            // Nothing selected: an empty paragraph takes the formatting for the text typed next.
            if (length == 0) patchProperties(p.element(A, "endParaRPr") ?: createEndParaRPr(p), patch)
            return
        }
        splitAt(p, end)
        splitAt(p, start)
        for (s in segments(p)) {
            if (s.kind == RunKind.BREAK || s.start < start || s.end > end || s.start == s.end) continue
            val rPr = s.el.element(A, "rPr") ?: s.el.createChild(A, "rPr").also {
                it.setAttr("lang", "en-US")
                s.el.insertChild(0, it)
            }
            patchProperties(rPr, patch)
        }
    }

    private fun createEndParaRPr(p: XElement): XElement {
        val e = p.createChild(A, "endParaRPr")
        e.setAttr("lang", "en-US")
        p.insertInOrder(e, Schema.PARAGRAPH)
        return e
    }

    fun patchProperties(rPr: XElement, patch: RunFormatPatch) {
        patch.bold?.let { rPr.setAttr("b", if (it) "1" else "0") }
        patch.italic?.let { rPr.setAttr("i", if (it) "1" else "0") }
        patch.underline?.let { rPr.setAttr("u", if (it) "sng" else "none") }
        patch.strike?.let { rPr.setAttr("strike", if (it) "sngStrike" else "noStrike") }
        patch.sizePt?.let { rPr.setAttr("sz", (it * 100).roundToInt().coerceIn(100, 400000).toString()) }
        patch.rgb?.let { rgb ->
            rPr.removeChildren { it is XElement && it.localName in Schema.FILLS }
            rPr.insertInOrder(solidFill(rPr, rgb), Schema.RUN_PROPERTIES)
        }
        patch.typeface?.let { face ->
            val latin = rPr.getOrCreate(A, "latin", Schema.RUN_PROPERTIES)
            latin.setAttr("typeface", Fragments.sanitize(face).replace(LINE_BREAK, ' '))
        }
    }

    fun solidFill(context: XElement, rgb: Int): XElement {
        val fill = context.createChild(A, "solidFill")
        val c = context.createChild(A, "srgbClr")
        c.setAttr("val", Colors.hex(rgb))
        fill.appendChild(c)
        return fill
    }

    // ----------------------------------------------------------------------------------------
    // Paragraphs

    private fun pPr(p: XElement): XElement =
        p.element(A, "pPr") ?: p.createChild(A, "pPr").also { p.insertChild(0, it) }

    fun setAlignment(p: XElement, alignment: TextAlign) {
        pPr(p).setAttr("algn", when (alignment) {
            TextAlign.LEFT -> "l"
            TextAlign.CENTER -> "ctr"
            TextAlign.RIGHT -> "r"
            TextAlign.JUSTIFY -> "just"
            TextAlign.DISTRIBUTED -> "dist"
        })
    }

    fun setLevel(p: XElement, level: Int) {
        val pr = pPr(p)
        val lvl = level.coerceIn(0, 8)
        // A hanging indent stored on the paragraph would fight the level's own indent.
        if (pr.attr("marL") != null && pr.element(A, "buNone") == null) pr.removeAttribute("marL")
        if (pr.attr("indent") != null && pr.element(A, "buNone") == null) pr.removeAttribute("indent")
        if (lvl == 0) pr.removeAttribute("lvl") else pr.setAttr("lvl", lvl.toString())
    }

    fun setBullet(p: XElement, style: BulletStyle) {
        val pr = pPr(p)
        pr.removeChildren { it is XElement && (it.localName in Schema.BULLET_TYPES || it.localName == "buFont") }
        val level = pr.attr("lvl")?.toIntOrNull() ?: 0
        when (style) {
            BulletStyle.None -> {
                pr.insertInOrder(pr.createChild(A, "buNone"), Schema.PARAGRAPH_PROPERTIES)
                pr.setAttr("marL", "0")
                pr.setAttr("indent", "0")
            }
            is BulletStyle.Char -> {
                pr.insertInOrder(pr.createChild(A, "buFont").also { it.setAttr("typeface", "Arial") }, Schema.PARAGRAPH_PROPERTIES)
                pr.insertInOrder(pr.createChild(A, "buChar").also { it.setAttr("char", style.char) }, Schema.PARAGRAPH_PROPERTIES)
                hangingIndent(pr, level)
            }
            is BulletStyle.Numbered -> {
                pr.insertInOrder(pr.createChild(A, "buFont").also { it.setAttr("typeface", "+mj-lt") }, Schema.PARAGRAPH_PROPERTIES)
                pr.insertInOrder(pr.createChild(A, "buAutoNum").also {
                    it.setAttr("type", style.scheme)
                    if (style.startAt != 1) it.setAttr("startAt", style.startAt.toString())
                }, Schema.PARAGRAPH_PROPERTIES)
                hangingIndent(pr, level)
            }
        }
    }

    private fun hangingIndent(pr: XElement, level: Int) {
        val marL = pr.attr("marL")?.toLongOrNull()
        if (marL == null || marL == 0L) {
            pr.setAttr("marL", (342900L + level * 457200L).toString())
            pr.setAttr("indent", "-342900")
        }
    }

    /** Splits [p] at [offset]; returns the new paragraph that follows it. */
    fun split(p: XElement, offset: Int): XElement {
        val length = textOf(p).length
        if (offset < 0 || offset > length) throw PptxEditException("Offset $offset outside 0..$length")
        val lastRunProps = segments(p).lastOrNull { it.kind == RunKind.TEXT && it.end <= offset }?.el?.element(A, "rPr")
        val at = splitAt(p, offset)
        val next = p.createChild(A, "p")
        p.element(A, "pPr")?.let { next.appendChild(it.deepCopy()) }
        val moving = p.children.drop(at).filterIsInstance<XElement>().filter { it.localName in setOf("r", "br", "fld") }
        for (m in moving) {
            m.detach()
            next.appendChild(m)
        }
        val end = p.element(A, "endParaRPr")
        when {
            // Typing in a new empty paragraph continues with the formatting at the split point.
            moving.isEmpty() && lastRunProps != null ->
                next.appendChild(Fragments.copyAs(lastRunProps, lastRunProps.qName.replace("rPr", "endParaRPr")))
            end != null -> next.appendChild(end.deepCopy())
        }
        p.parent!!.insertAfter(next, p)
        return next
    }

    fun mergeWithNext(p: XElement) {
        val parent = p.parent ?: return
        val siblings = parent.elements(A, "p").toList()
        val i = siblings.indexOfFirst { it === p }
        val next = siblings.getOrNull(i + 1) ?: throw PptxEditException("No following paragraph")
        val moving = next.elements().filter { it.localName in setOf("r", "br", "fld") }.toList()
        val insertAt = p.element(A, "endParaRPr")?.let { p.indexOf(it) } ?: p.children.size
        var index = insertAt
        for (m in moving) {
            m.detach()
            p.insertChild(index++, m)
        }
        next.detach()
    }

    fun deleteParagraph(p: XElement) {
        val parent = p.parent ?: return
        if (parent.elements(A, "p").count() <= 1) {
            for (c in p.elements().filter { it.localName in setOf("r", "br", "fld") }.toList()) c.detach()
        } else {
            p.detach()
        }
    }

    /** Replaces the whole text of a body; one paragraph per line. */
    fun setBodyText(body: XElement, value: String) {
        val paragraphs = body.elements(A, "p").toList()
        val first = paragraphs.firstOrNull()
        val template = first?.let { p -> segments(p).firstOrNull { it.kind == RunKind.TEXT }?.el?.element(A, "rPr")?.deepCopy() }
            ?: first?.element(A, "endParaRPr")?.let { Fragments.copyAs(it, it.qName.replace("endParaRPr", "rPr")) }
        val pPrTemplate = first?.element(A, "pPr")
        val endTemplate = first?.element(A, "endParaRPr")
        val insertAt = first?.let { body.indexOf(it) } ?: body.children.size
        val lines = Fragments.sanitize(value).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val created = lines.map { line ->
            val p = body.createChild(A, "p")
            pPrTemplate?.let { p.appendChild(it.deepCopy()) }
            if (line.isNotEmpty()) {
                for ((i, piece) in line.split(LINE_BREAK).withIndex()) {
                    if (i > 0) p.appendChild(newBreak(body, template))
                    if (piece.isNotEmpty()) p.appendChild(newRun(body, piece, template))
                }
            }
            endTemplate?.let { p.appendChild(it.deepCopy()) }
            p
        }
        for (p in paragraphs) p.detach()
        var index = insertAt
        for (p in created) body.insertChild(index++, p)
    }

    /** Adds an empty text body to a shape that has none (geometric shapes). */
    fun createTextBody(shape: XElement): XElement {
        val body = Fragments.build(
            """<p:txBody><a:bodyPr rtlCol="0" anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody>""",
            shape,
        )
        shape.insertInOrder(body, Schema.SHAPE)
        return body
    }

    @Suppress("unused")
    private fun isPlaceholderShape(shape: XElement): Boolean =
        shape.element(P, "nvSpPr")?.element(P, "nvPr")?.element(P, "ph") != null
}
