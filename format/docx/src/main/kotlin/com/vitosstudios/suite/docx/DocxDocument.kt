package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.OfficeDocument
import com.vitosstudios.suite.opc.OpcException
import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.XDocument
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser
import java.io.File
import java.util.IdentityHashMap

/** Top-level content of a story (body, cell, header): paragraphs, tables or locked blocks. */
sealed class Block {
    abstract val element: XElement

    class Para(val paragraph: Paragraph) : Block() {
        override val element: XElement get() = paragraph.element
    }

    class Table(val table: DocxTable) : Block() {
        override val element: XElement get() = table.element
    }

    /** Content the app keeps but does not edit (e.g. an embedded alternative-format chunk). */
    class Locked(override val element: XElement, val label: String, val explanation: String) : Block()
}

/** One section: page setup and header/footer references. Lengths in points. */
class Section internal constructor(val doc: DocxDocument, val sectPr: XElement, val index: Int) {
    private val pgSz get() = sectPr.w("pgSz")
    private val pgMar get() = sectPr.w("pgMar")

    val pageWidthPt: Float get() = Units.twipsToPoints(pgSz?.wAttr("w")?.toIntOrNull() ?: 12240)
    val pageHeightPt: Float get() = Units.twipsToPoints(pgSz?.wAttr("h")?.toIntOrNull() ?: 15840)
    val isLandscape: Boolean get() = pgSz?.wAttr("orient") == "landscape" || pageWidthPt > pageHeightPt
    val marginTopPt: Float get() = Units.twipsToPoints(pgMar?.wAttr("top")?.toIntOrNull() ?: 1440)
    val marginBottomPt: Float get() = Units.twipsToPoints(pgMar?.wAttr("bottom")?.toIntOrNull() ?: 1440)
    val marginLeftPt: Float get() = Units.twipsToPoints((pgMar?.wAttr("left") ?: pgMar?.wAttr("start"))?.toIntOrNull() ?: 1440)
    val marginRightPt: Float get() = Units.twipsToPoints((pgMar?.wAttr("right") ?: pgMar?.wAttr("end"))?.toIntOrNull() ?: 1440)
    val textWidthPt: Float get() = pageWidthPt - marginLeftPt - marginRightPt
    val columns: Int get() = sectPr.w("cols")?.wAttr("num")?.toIntOrNull() ?: 1

    internal fun reference(kind: HeaderFooter.Kind, type: String): String? =
        sectPr.ws(if (kind == HeaderFooter.Kind.HEADER) "headerReference" else "footerReference")
            .firstOrNull { (it.wAttr("type") ?: "default") == type }?.attr(Wml.R, "id")
}

/** A header or footer part. Its paragraphs are edited like body paragraphs. */
class HeaderFooter internal constructor(val doc: DocxDocument, val part: Part, val kind: Kind, val type: String) {
    enum class Kind { HEADER, FOOTER }

    val root: XElement get() = part.xml().root
    val blocks: List<Block> get() = doc.blocksOf(part, root)
    val paragraphs: List<Paragraph> get() = doc.paragraphsIn(blocks)
}

data class FindMatch(val paragraph: Paragraph, val start: Int, val end: Int)

/** Where a paragraph sits inside a table. */
data class CellPosition(val table: DocxTable, val row: Int, val column: Int)

data class ReplaceResult(val replaced: Int, val skippedReadOnly: Int)

data class WordCount(val words: Int, val characters: Int, val charactersNoSpaces: Int, val paragraphs: Int)

/** Standard page sizes in points. */
enum class PageSize(val widthPt: Float, val heightPt: Float, val label: String) {
    LETTER(612f, 792f, "Letter"),
    A4(595.3f, 841.9f, "A4"),
    LEGAL(612f, 1008f, "Legal"),
    A5(419.55f, 595.3f, "A5"),
}

class FormatMismatchException(message: String) : Exception(message)

/**
 * A DOCX document opened for editing.
 *
 * Only the parts the user edits are re-serialised; everything else is written back byte-identical.
 * Every edit is one undo step on [pkg]'s undo manager.
 */
class DocxDocument private constructor(override val pkg: OpcPackage) : OfficeDocument {
    val mainPart: Part = pkg.mainDocument() ?: throw FormatMismatchException("The package has no main document")

    init {
        val ct = mainPart.contentType ?: ""
        if (!ct.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml.") &&
            !ct.startsWith("application/vnd.ms-word.")
        ) throw FormatMismatchException("Not a word-processing document ($ct)")
    }

    val document: XDocument get() = mainPart.xml()
    val body: XElement get() = document.root.w("body") ?: throw OpcException("Document has no body")

    val styles = DocxStyles(this)
    val numbering = DocxNumbering(this)
    internal val theme = DocxTheme(this)

    private val paragraphCache = IdentityHashMap<XElement, Paragraph>()

    // ------------------------------------------------------------------------------------
    // Structure

    fun blocks(): List<Block> = blocksOf(mainPart, body)

    internal fun paragraphFor(part: Part, e: XElement): Paragraph = paragraphCache.getOrPut(e) { Paragraph(this, e, part) }

    internal fun blocksOf(part: Part, container: XElement): List<Block> {
        val out = ArrayList<Block>()
        fun walk(c: XElement) {
            for (child in c.children) {
                if (child !is XElement) continue
                if (child.ns != Wml.W) {
                    out += Block.Locked(child, child.localName, "Content in an extension format is kept unchanged.")
                    continue
                }
                when (child.localName) {
                    "p" -> out += Block.Para(paragraphFor(part, child))
                    "tbl" -> out += Block.Table(DocxTable(this, child, part))
                    "sdt" -> child.w("sdtContent")?.let(::walk)
                    "customXml" -> walk(child)
                    "sectPr", "tcPr", "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "proofErr",
                    "permStart", "permEnd", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd" -> {}
                    "altChunk" -> out += Block.Locked(child, "Embedded document", "Imported content from another format is kept unchanged.")
                    "ins", "del", "moveFrom", "moveTo" -> out += Block.Locked(child, "Tracked change", "Tracked changes are kept but cannot be edited here.")
                    else -> out += Block.Locked(child, child.localName, "This content is not supported and is kept unchanged.")
                }
            }
        }
        walk(container)
        return out
    }

    internal fun paragraphsIn(blocks: List<Block>): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        fun walk(bs: List<Block>) {
            for (b in bs) when (b) {
                is Block.Para -> out += b.paragraph
                is Block.Table -> for (r in b.table.rows) for (c in r.cells) walk(c.blocks)
                is Block.Locked -> {}
            }
        }
        walk(blocks)
        return out
    }

    /** Body paragraphs in reading order, including those inside tables. */
    fun bodyParagraphs(): List<Paragraph> = paragraphsIn(blocks())

    /** Body, header and footer paragraphs. */
    fun allParagraphs(includeHeadersFooters: Boolean = true): List<Paragraph> =
        bodyParagraphs() + if (includeHeadersFooters) headersFooters().flatMap { it.paragraphs } else emptyList()

    /** Parts that can contain drawings (for unique ids). */
    internal fun storyParts(): List<Part> {
        val rels = pkg.relationships(mainPart)
        val types = setOf(RelTypes.HEADER, RelTypes.FOOTER, RelTypes.FOOTNOTES, RelTypes.ENDNOTES, RelTypes.COMMENTS)
        return listOf(mainPart) + rels.all().filter { it.type in types }.mapNotNull { rels.targetPart(it) }
    }

    /** List labels for [paragraphs] (defaults to the body), keyed by paragraph. */
    fun listLabels(paragraphs: List<Paragraph> = bodyParagraphs()): Map<Paragraph, String> = numbering.labels(paragraphs)

    // ------------------------------------------------------------------------------------
    // Paragraph structure edits

    private fun requireEditable(p: Paragraph) {
        if (!p.isTextEditable) throw ReadOnlyContentException("This paragraph contains ${p.lockReasons.joinToString()} and cannot be split or joined")
    }

    /** Splits [p] at [offset] (Enter key); returns the new second paragraph. */
    fun splitParagraph(p: Paragraph, offset: Int): Paragraph {
        requireEditable(p)
        require(offset in 0..p.text.length)
        return pkg.undo.transaction("New paragraph") {
            val atEnd = offset == p.text.length
            p.splitAt(offset)
            val newEl = XElement(p.element.qName)
            p.element.parent!!.insertAfter(newEl, p.element)
            p.pPr?.let { pPr ->
                val copy = pPr.deepCopy()
                newEl.appendChild(copy)
                pPr.w("sectPr")?.detach()
                copy.w("pageBreakBefore")?.detach()
                if (atEnd) {
                    val next = p.styleId?.let { styles.style(it)?.next }
                    if (next != null && next != p.styleId) {
                        val np = paragraphFor(p.part, newEl)
                        np.setStyle(next)
                        if (styles.style(next)?.headingLevel == null) copy.w("outlineLvl")?.detach()
                    }
                }
            }
            moveContentFrom(p, offset, newEl)
            paragraphFor(p.part, newEl)
        }
    }

    /** Moves every inline child of [p] starting at or after [offset] into [target]. */
    private fun moveContentFrom(p: Paragraph, offset: Int, target: XElement) {
        val pieces = p.scan().pieces
        fun firstStart(node: XElement): Int? {
            var best: Int? = null
            for (pc in pieces) {
                var n: XElement? = pc.element
                while (n != null && n !== node) n = n.parent
                if (n === node) best = minOf(best ?: pc.start, pc.start)
            }
            return best
        }
        fun lastEnd(node: XElement): Int? {
            var best: Int? = null
            for (pc in pieces) {
                var n: XElement? = pc.element
                while (n != null && n !== node) n = n.parent
                if (n === node) best = maxOf(best ?: pc.end, pc.end)
            }
            return best
        }
        val children = p.element.children.filterIsInstance<XElement>().filter { !it.isW("pPr") }
        var moveFrom = -1
        for ((i, c) in children.withIndex()) {
            val s = firstStart(c) ?: continue
            val e = lastEnd(c) ?: s
            if (s >= offset) { moveFrom = i; break }
            if (e > offset) {
                // A container (hyperlink, smart tag) straddles the split: split the container.
                val clone = XElement(c.qName)
                for (a in c.attributes) clone.setAttribute(a.qName, a.value)
                p.element.insertAfter(clone, c)
                val inner = c.children.filterIsInstance<XElement>()
                val moving = inner.filter { (firstStart(it) ?: -1) >= offset }
                for (m in moving) { m.detach(); clone.appendChild(m) }
                moveFrom = i + 1
                break
            }
        }
        if (offset == 0) moveFrom = 0
        if (moveFrom < 0) return
        val all = p.element.children.filterIsInstance<XElement>().filter { !it.isW("pPr") }
        for (c in all.drop(moveFrom)) {
            c.detach()
            target.appendChild(c)
        }
    }

    /**
     * Joins [p] to the paragraph before it (Backspace at the start). Returns the joined paragraph
     * and the caret offset, or null if there is no editable paragraph directly before it or the
     * previous paragraph ends a section.
     */
    fun mergeWithPrevious(p: Paragraph): Pair<Paragraph, Int>? {
        val prevEl = previousBlockElement(p.element) ?: return null
        if (!prevEl.isW("p")) return null
        val prev = paragraphFor(p.part, prevEl)
        if (!prev.isTextEditable || !p.isTextEditable || prev.endsSection) return null
        val caret = prev.text.length
        pkg.undo.transaction("Join paragraphs") {
            for (c in p.element.children.toList()) {
                if (c is XElement && c.isW("pPr")) continue
                c.detach()
                prevEl.appendChild(c)
            }
            p.pPr?.w("sectPr")?.let { s -> s.detach(); prev.ensurePPr().insertInOrder(s, Wml.PPR_ORDER) }
            p.element.detach()
        }
        return prev to caret
    }

    private fun previousBlockElement(e: XElement): XElement? {
        val parent = e.parent ?: return null
        val idx = parent.indexOf(e)
        for (i in idx - 1 downTo 0) {
            val c = parent.children[i] as? XElement ?: continue
            if (c.isW("p") || c.isW("tbl") || c.isW("sdt")) return c
        }
        return null
    }

    /** Inserts a new empty paragraph after [after] (or at the end of the body). */
    fun insertParagraphAfter(after: Block?, text: String = ""): Paragraph = pkg.undo.transaction("New paragraph") {
        val anchor = after?.element
        val container = anchor?.parent ?: body
        val p = container.newW("p")
        if (anchor != null) container.insertAfter(p, anchor)
        else {
            val sect = body.w("sectPr")
            if (sect != null) body.insertBefore(p, sect) else body.appendChild(p)
        }
        val para = paragraphFor(after?.let { partOf(it) } ?: mainPart, p)
        if (anchor != null && anchor.isW("p")) {
            anchor.w("pPr")?.let { src ->
                val copy = src.deepCopy()
                p.appendChild(copy)
                copy.w("sectPr")?.detach()
            }
        }
        if (text.isNotEmpty()) para.insertText(0, text)
        para
    }

    private fun partOf(b: Block): Part = when (b) {
        is Block.Para -> b.paragraph.part
        is Block.Table -> b.table.part
        is Block.Locked -> mainPart
    }

    /** Deletes a paragraph or table. Refuses blocks that end a section or would leave invalid structure. */
    fun deleteBlock(block: Block) {
        val e = block.element
        val container = e.parent ?: return
        if (block is Block.Para && block.paragraph.endsSection) throw IllegalStateException("This paragraph ends a section; remove the section break instead")
        if (block is Block.Locked) throw ReadOnlyContentException("Locked content cannot be deleted")
        val siblings = container.elements().filter { it.isW("p") || it.isW("tbl") }.toList()
        pkg.undo.transaction("Delete") {
            e.detach()
            val remaining = siblings.filter { it !== e }
            if (remaining.isEmpty() || !remaining.last().isW("p")) {
                // Bodies and cells must end with a paragraph.
                val p = container.newW("p")
                val sect = container.w("sectPr")
                if (sect != null) container.insertBefore(p, sect) else container.appendChild(p)
            }
        }
    }

    /** Moves a paragraph or table up or down among its siblings. */
    fun moveBlock(block: Block, up: Boolean): Boolean {
        val e = block.element
        val container = e.parent ?: return false
        val siblings = container.elements().filter { it.isW("p") || it.isW("tbl") }.toList()
        val i = siblings.indexOfFirst { it === e }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in siblings.indices) return false
        if ((block as? Block.Para)?.paragraph?.endsSection == true || siblings[j].w("pPr")?.w("sectPr") != null) return false
        pkg.undo.transaction("Move") {
            e.detach()
            if (up) container.insertBefore(e, siblings[j]) else container.insertAfter(e, siblings[j])
            ensureTrailingParagraph(container)
        }
        return true
    }

    private fun ensureTrailingParagraph(container: XElement) {
        val last = container.elements().filter { it.isW("p") || it.isW("tbl") }.lastOrNull()
        if (last != null && last.isW("tbl")) {
            val p = container.newW("p")
            container.insertAfter(p, last)
        }
    }

    /** Inserts a page break at [offset] of [p]: the paragraph is split and the break starts the second part. */
    fun insertPageBreak(p: Paragraph, offset: Int): Paragraph = pkg.undo.transaction("Page break") {
        val second = splitParagraph(p, offset)
        val run = second.element.newW("r")
        val br = run.newW("br")
        run.appendChild(br)
        val pPr = second.pPr
        if (pPr != null) second.element.insertAfter(run, pPr) else second.element.insertChild(0, run)
        br.setAttr(Wml.W, "type", "page")
        second
    }

    // ------------------------------------------------------------------------------------
    // Lists and headings

    fun toggleBulletList(p: Paragraph) {
        if (numbering.isBulleted(p)) numbering.removeList(p) else numbering.applyList(p, numbered = false, previous = previousParagraph(p))
    }

    fun toggleNumberedList(p: Paragraph) {
        if (numbering.isNumbered(p)) numbering.removeList(p) else numbering.applyList(p, numbered = true, previous = previousParagraph(p))
    }

    fun changeListLevel(p: Paragraph, delta: Int) = numbering.changeLevel(p, delta)

    private fun previousParagraph(p: Paragraph): Paragraph? {
        val e = previousBlockElement(p.element) ?: return null
        return if (e.isW("p")) paragraphFor(p.part, e) else null
    }

    // ------------------------------------------------------------------------------------
    // Tables

    /** Inserts a [rows] x [cols] table after [after]; a paragraph always follows the table. */
    fun insertTable(after: Paragraph, rows: Int, cols: Int): DocxTable {
        require(rows in 1..200 && cols in 1..63)
        return pkg.undo.transaction("Insert table") {
            val widthTwips = (sectionOf(after)?.textWidthPt ?: 451f) * 20
            val colW = (widthTwips / cols).toInt()
            val style = styles.tableGridStyleId()
            val xml = buildString {
                append("""<w:tbl xmlns:w="${Wml.W}"><w:tblPr>""")
                if (style != null) append("""<w:tblStyle w:val="$style"/>""")
                append("""<w:tblW w:w="0" w:type="auto"/>""")
                if (style == null) {
                    append("<w:tblBorders>")
                    for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) append("""<w:$side w:val="single" w:sz="4" w:space="0" w:color="auto"/>""")
                    append("</w:tblBorders>")
                }
                append("""<w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" w:firstColumn="1" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr><w:tblGrid>""")
                repeat(cols) { append("""<w:gridCol w:w="$colW"/>""") }
                append("</w:tblGrid>")
                repeat(rows) {
                    append("<w:tr>")
                    repeat(cols) { append("""<w:tc><w:tcPr><w:tcW w:w="$colW" w:type="dxa"/></w:tcPr><w:p/></w:tc>""") }
                    append("</w:tr>")
                }
                append("</w:tbl>")
            }
            val container = after.element.parent!!
            val tbl = XmlParser.parse(xml).root.deepCopy()
            stripRedundantNamespace(tbl, container)
            container.insertAfter(tbl, after.element)
            val next = container.children.getOrNull(container.indexOf(tbl) + 1)
            if (!(next is XElement && next.isW("p"))) container.insertAfter(container.newW("p"), tbl)
            DocxTable(this, tbl, after.part)
        }
    }

    /** The innermost table cell containing [p], or null for paragraphs outside tables. */
    fun cellOf(p: Paragraph): CellPosition? {
        var e: XElement? = p.element.parent
        while (e != null && !e.isW("tc")) e = e.parent
        val tc = e ?: return null
        val tr = tc.parent ?: return null
        val tbl = tr.parent ?: return null
        if (!tr.isW("tr") || !tbl.isW("tbl")) return null
        val row = tbl.ws("tr").indexOfFirst { it === tr }
        val col = tr.ws("tc").indexOfFirst { it === tc }
        return CellPosition(DocxTable(this, tbl, p.part), row, col)
    }

    /** Deletes a whole table (undoable); a paragraph keeps the story valid. */
    fun deleteTable(table: DocxTable) = deleteBlock(Block.Table(table))

    // ------------------------------------------------------------------------------------
    // Images

    /** Bytes of an image referenced from [part]. */
    fun imageBytes(part: Part, image: DocxImage): ByteArray? = pkg.relationships(part).targetPart(image.relId)?.bytes()

    fun imagePartName(part: Part, image: DocxImage): String? = pkg.relationships(part).targetPart(image.relId)?.name

    /**
     * Inserts a picture at [offset] of [p]. The size is given in pixels at 96 dpi and scaled down
     * to fit the text width. The original image bytes are stored unchanged.
     */
    fun insertImage(p: Paragraph, offset: Int, bytes: ByteArray, extension: String, widthPx: Int, heightPx: Int): DocxImage {
        if (!p.isTextEditable) throw ReadOnlyContentException("Images can only be inserted into editable paragraphs")
        return pkg.undo.transaction("Insert image") {
            val relId = DrawingOps.addImagePart(this, p.part, bytes, extension)
            var cx = widthPx.toLong() * Units.EMU_PER_INCH / 96
            var cy = heightPx.toLong() * Units.EMU_PER_INCH / 96
            val maxW = Units.pointsToEmu(sectionOf(p)?.textWidthPt ?: 451f)
            if (cx > maxW) { cy = cy * maxW / cx; cx = maxW }
            val id = DrawingOps.nextDocPrId(this)
            // Insert a placeholder character with the formatting at the caret, then swap it for the drawing.
            p.insertText(offset, "\uE000")
            p.splitAt(offset)
            p.splitAt(offset + 1)
            val target = p.scan().pieces.first { it.start == offset && it.kind == PieceKind.TEXT }
            val run = target.run!!
            val drawing = DrawingOps.inlinePicture(run, relId, cx, cy, id, "image.${extension.lowercase()}")
            run.insertBefore(drawing, target.element)
            target.element.detach()
            DocxImage.fromDrawing(this, drawing)!!
        }
    }

    /** Resizes a picture; [widthPt]/[heightPt] in points. The image part is not touched. */
    fun resizeImage(image: DocxImage, widthPt: Float, heightPt: Float) {
        pkg.undo.transaction("Resize image") {
            DrawingOps.resize(image.drawing, Units.pointsToEmu(widthPt), Units.pointsToEmu(heightPt))
        }
    }

    // ------------------------------------------------------------------------------------
    // Hyperlinks

    /** Target of a `w:hyperlink`: the external URL, "#bookmark" for internal links, or null. */
    fun hyperlinkTarget(part: Part, hyperlink: XElement): String? {
        hyperlink.attr(Wml.R, "id")?.let { id -> return pkg.relationships(part).byId(id)?.target }
        return hyperlink.wAttr("anchor")?.let { "#$it" }
    }

    /** Hyperlink element covering [offset] in [p], if any. */
    fun hyperlinkAt(p: Paragraph, offset: Int): XElement? =
        p.scan().pieces.firstOrNull { it.start <= offset && offset < it.end || it.end == offset && it.start < offset }?.hyperlink

    /** Turns [start, end) of [p] into a link to [url]. */
    fun insertHyperlink(p: Paragraph, start: Int, end: Int, url: String) {
        if (!p.isTextEditable) throw ReadOnlyContentException("Links can only be added to editable paragraphs")
        require(start < end)
        pkg.undo.transaction("Insert link") {
            val runs = p.runsIn(start, end)
            if (runs.isEmpty()) return@transaction
            val parent = runs.first().parent!!
            if (runs.any { it.parent !== parent } || parent.isW("hyperlink")) {
                throw IllegalStateException("The selection overlaps an existing link")
            }
            val rel = pkg.relationships(p.part).add(RelTypes.HYPERLINK, url, external = true)
            val link = parent.newW("hyperlink")
            parent.insertBefore(link, runs.first())
            link.setAttr(Wml.R, "id", rel.id)
            link.setAttr(Wml.W, "history", "1")
            for (r in runs) { r.detach(); link.appendChild(r) }
            val styleId = styles.hyperlinkStyleId()
            for (r in runs) {
                val existing = r.w("rPr")
                val create = { r.newW("rPr").also { r.insertChild(0, it) } }
                if (styleId != null) {
                    val rPr = existing ?: create()
                    rPr.getOrCreate(Wml.W, "rStyle", Wml.RPR_ORDER).setAttr(Wml.W, "val", styleId)
                } else {
                    RunPatch(color = "0563C1", underline = true).applyTo(p, existing, create)
                }
            }
        }
    }

    /** Changes the address of a link. */
    fun setHyperlinkUrl(p: Paragraph, hyperlink: XElement, url: String) {
        pkg.undo.transaction("Edit link") {
            val rels = pkg.relationships(p.part)
            val id = hyperlink.attr(Wml.R, "id")
            val sharedUse = id != null && p.part.xml().root.descendants(Wml.W, "hyperlink").count { it.attr(Wml.R, "id") == id } > 1
            if (id != null && !sharedUse) {
                rels.retarget(id, url)
            } else {
                val rel = rels.add(RelTypes.HYPERLINK, url, external = true)
                hyperlink.removeAttr(Wml.W, "anchor")
                hyperlink.setAttr(Wml.R, "id", rel.id)
            }
        }
    }

    /** Removes a link but keeps its text. */
    fun removeHyperlink(hyperlink: XElement) {
        val parent = hyperlink.parent ?: return
        pkg.undo.transaction("Remove link") {
            var idx = parent.indexOf(hyperlink)
            for (c in hyperlink.children.toList()) {
                c.detach()
                parent.insertChild(++idx, c)
            }
            hyperlink.detach()
        }
    }

    // ------------------------------------------------------------------------------------
    // Sections, page setup, headers and footers

    fun sections(): List<Section> {
        val list = ArrayList<XElement>()
        for (p in body.descendants(Wml.W, "p")) p.w("pPr")?.w("sectPr")?.let(list::add)
        body.w("sectPr")?.let(list::add)
        return list.mapIndexed { i, e -> Section(this, e, i) }
    }

    /** The section a paragraph belongs to. */
    fun sectionOf(p: Paragraph): Section? {
        if (p.part !== mainPart) return sections().firstOrNull()
        val all = sections()
        var index = 0
        for (para in body.descendants(Wml.W, "p")) {
            if (para === p.element) return all.getOrNull(index) ?: all.lastOrNull()
            if (para.w("pPr")?.w("sectPr") != null) index++
        }
        return all.lastOrNull()
    }

    /** The final body section, created if the document has none. */
    fun ensureSection(): Section {
        sections().lastOrNull()?.let { return it }
        pkg.undo.transaction("Page setup") { body.appendChild(body.newW("sectPr")) }
        return sections().last()
    }

    fun setPageSize(section: Section, widthPt: Float, heightPt: Float) {
        pkg.undo.transaction("Page size") {
            val landscape = section.isLandscape
            val w = if (landscape) maxOf(widthPt, heightPt) else minOf(widthPt, heightPt)
            val h = if (landscape) minOf(widthPt, heightPt) else maxOf(widthPt, heightPt)
            val pgSz = section.sectPr.getOrCreate(Wml.W, "pgSz", Wml.SECTPR_ORDER)
            pgSz.setAttr(Wml.W, "w", Math.round(w * 20).toString())
            pgSz.setAttr(Wml.W, "h", Math.round(h * 20).toString())
            pgSz.removeAttr(Wml.W, "code")
        }
    }

    fun setOrientation(section: Section, landscape: Boolean) {
        if (section.isLandscape == landscape) return
        pkg.undo.transaction("Orientation") {
            val pgSz = section.sectPr.getOrCreate(Wml.W, "pgSz", Wml.SECTPR_ORDER)
            val w = section.pageWidthPt
            val h = section.pageHeightPt
            pgSz.setAttr(Wml.W, "w", Math.round(h * 20).toString())
            pgSz.setAttr(Wml.W, "h", Math.round(w * 20).toString())
            if (landscape) pgSz.setAttr(Wml.W, "orient", "landscape") else pgSz.removeAttr(Wml.W, "orient")
        }
    }

    /** Margins in points; null leaves a value unchanged. */
    fun setMargins(section: Section, top: Float? = null, right: Float? = null, bottom: Float? = null, left: Float? = null) {
        pkg.undo.transaction("Margins") {
            val m = section.sectPr.getOrCreate(Wml.W, "pgMar", Wml.SECTPR_ORDER)
            fun set(name: String, v: Float?) { v?.let { m.setAttr(Wml.W, name, Math.round(it * 20).toString()) } }
            set("top", top); set("right", right); set("bottom", bottom); set("left", left)
            for (n in listOf("header", "footer", "gutter")) if (m.wAttr(n) == null) m.setAttr(Wml.W, n, if (n == "gutter") "0" else "708")
        }
    }

    /** Headers and footers referenced by any section. */
    fun headersFooters(): List<HeaderFooter> {
        val out = ArrayList<HeaderFooter>()
        val seen = HashSet<String>()
        val rels = pkg.relationships(mainPart)
        for (s in sections()) {
            for (ref in s.sectPr.elements()) {
                val kind = when {
                    ref.isW("headerReference") -> HeaderFooter.Kind.HEADER
                    ref.isW("footerReference") -> HeaderFooter.Kind.FOOTER
                    else -> continue
                }
                val part = ref.attr(Wml.R, "id")?.let(rels::targetPart) ?: continue
                if (!seen.add(part.name) || part.xmlOrNull() == null) continue
                out += HeaderFooter(this, part, kind, ref.wAttr("type") ?: "default")
            }
        }
        return out
    }

    /** The header/footer shown for [section] (inherited from earlier sections if not set). */
    fun headerFooter(section: Section, kind: HeaderFooter.Kind, type: String = "default"): HeaderFooter? {
        val all = sections()
        val rels = pkg.relationships(mainPart)
        for (i in section.index downTo 0) {
            val id = all[i].reference(kind, type) ?: continue
            val part = rels.targetPart(id) ?: return null
            if (part.xmlOrNull() == null) return null
            return HeaderFooter(this, part, kind, type)
        }
        return null
    }

    /** Returns the default header/footer of [section], creating an empty one if none is shown. */
    fun ensureHeaderFooter(section: Section, kind: HeaderFooter.Kind): HeaderFooter {
        headerFooter(section, kind)?.let { return it }
        return pkg.undo.transaction(if (kind == HeaderFooter.Kind.HEADER) "Add header" else "Add footer") {
            val isHeader = kind == HeaderFooter.Kind.HEADER
            val base = if (isHeader) "/word/header" else "/word/footer"
            val name = pkg.uniquePartName(base, "xml")
            val rootName = if (isHeader) "w:hdr" else "w:ftr"
            val styleName = if (isHeader) "header" else "footer"
            val styleId = styles.all().firstOrNull { it.type == "paragraph" && it.name.equals(styleName, true) }?.styleId
            val pXml = if (styleId != null) """<w:p><w:pPr><w:pStyle w:val="$styleId"/></w:pPr></w:p>""" else "<w:p/>"
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n" +
                """<$rootName xmlns:w="${Wml.W}" xmlns:r="${com.vitosstudios.suite.xml.Namespaces.R}">$pXml</$rootName>"""
            val part = pkg.addXmlPart(name, if (isHeader) ContentTypeNames.WML_HEADER else ContentTypeNames.WML_FOOTER, XmlParser.parse(xml))
            val rel = pkg.relationships(mainPart).addTo(if (isHeader) RelTypes.HEADER else RelTypes.FOOTER, name)
            val ref = section.sectPr.newW(if (isHeader) "headerReference" else "footerReference")
            // Header references precede footer references, which precede everything else.
            val refs = section.sectPr.elements().filter { it.isW("headerReference") || it.isW("footerReference") }.toList()
            val anchor = if (isHeader) refs.lastOrNull { it.isW("headerReference") } else refs.lastOrNull()
            if (anchor != null) section.sectPr.insertAfter(ref, anchor) else section.sectPr.insertChild(0, ref)
            ref.setAttr(Wml.W, "type", "default")
            ref.setAttr(Wml.R, "id", rel.id)
            HeaderFooter(this, part, kind, "default")
        }
    }

    // ------------------------------------------------------------------------------------
    // Find, replace, word count

    fun find(query: String, matchCase: Boolean = false, wholeWord: Boolean = false, includeHeadersFooters: Boolean = true): List<FindMatch> {
        if (query.isEmpty()) return emptyList()
        val regex = searchRegex(query, matchCase, wholeWord)
        return allParagraphs(includeHeadersFooters).flatMap { p ->
            regex.findAll(p.text).map { FindMatch(p, it.range.first, it.range.last + 1) }.toList()
        }
    }

    /** Replaces one match; returns false if its paragraph is read-only or changed since the search. */
    fun replace(match: FindMatch, replacement: String): Boolean {
        if (!match.paragraph.isTextEditable || match.end > match.paragraph.text.length) return false
        match.paragraph.replaceText(match.start, match.end, replacement)
        return true
    }

    fun replaceAll(query: String, replacement: String, matchCase: Boolean = false, wholeWord: Boolean = false): ReplaceResult {
        val matches = find(query, matchCase, wholeWord)
        var replaced = 0
        var skipped = 0
        pkg.undo.transaction("Replace all") {
            for ((p, ms) in matches.groupBy { it.paragraph }) {
                if (!p.isTextEditable) { skipped += ms.size; continue }
                for (m in ms.sortedByDescending { it.start }) {
                    p.replaceText(m.start, m.end, replacement)
                    replaced++
                }
            }
        }
        return ReplaceResult(replaced, skipped)
    }

    private fun searchRegex(query: String, matchCase: Boolean, wholeWord: Boolean): Regex {
        val core = Regex.escape(query)
        val pattern = if (wholeWord) "(?<![\\p{L}\\p{N}_])$core(?![\\p{L}\\p{N}_])" else core
        return if (matchCase) Regex(pattern) else Regex(pattern, setOf(RegexOption.IGNORE_CASE))
    }

    fun wordCount(): WordCount {
        var words = 0
        var chars = 0
        var noSpace = 0
        var paras = 0
        val wordRegex = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-_.]*")
        for (p in bodyParagraphs()) {
            val t = p.plainText
            if (t.isBlank()) continue
            paras++
            chars += t.length
            noSpace += t.count { !it.isWhitespace() }
            // CJK ideographs count as one word each, like common word processors.
            val cjk = t.count { Character.UnicodeScript.of(it.code) in CJK }
            words += cjk + wordRegex.findAll(t.filter { Character.UnicodeScript.of(it.code) !in CJK }).count()
        }
        return WordCount(words, chars, noSpace, paras)
    }

    // ------------------------------------------------------------------------------------
    // Compatibility, saving

    override fun compatibility(): CompatibilityReport = DocxCompatibility.report(this)

    override fun save(target: File) {
        pkg.save(target) { tmp -> verifyReadable(tmp) }
    }

    companion object {
        private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

        fun open(file: File): DocxDocument {
            val pkg = OpcPackage.open(file)
            return try { DocxDocument(pkg) } catch (e: Exception) { pkg.close(); throw e }
        }

        fun open(bytes: ByteArray): DocxDocument {
            val pkg = OpcPackage.open(bytes)
            return try { DocxDocument(pkg) } catch (e: Exception) { pkg.close(); throw e }
        }

        /** A new document built from one of the app's own templates. */
        fun createNew(template: DocxTemplate = DocxTemplate.BLANK): DocxDocument = DocxDocument(DocxTemplates.build(template))

        /** Re-opens a written file and reads all content; throws if anything cannot be read. */
        fun verifyReadable(file: File) {
            open(file).use { d ->
                d.allParagraphs().forEach { it.text; it.spans() }
                d.listLabels()
                d.sections()
            }
        }
    }
}
