package com.vitosstudios.suite.feature.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.toArgb
import com.vitosstudios.suite.docx.Alignment
import com.vitosstudios.suite.docx.AtomKind
import com.vitosstudios.suite.docx.Block
import com.vitosstudios.suite.docx.DocxDocument
import com.vitosstudios.suite.docx.DocxTable
import com.vitosstudios.suite.docx.HeaderFooter
import com.vitosstudios.suite.docx.InlineChars
import com.vitosstudios.suite.docx.Paragraph
import com.vitosstudios.suite.feature.common.ImageLoader
import java.io.File

/**
 * Lays out a DOCX document on pages and writes a PDF. Units are points (the PDF canvas uses
 * 1/72 inch). Supports the editable feature set: run formatting, alignment, indentation,
 * spacing, lists, tables, images, page breaks, headers and footers with page numbers.
 * Content the app does not edit is drawn as a labelled box.
 */
class DocxPdfExporter(private val doc: DocxDocument) {
    private val pdf = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0
    private var y = 0f

    private val section = doc.sections().lastOrNull()
    private val pageW = section?.pageWidthPt ?: 595f
    private val pageH = section?.pageHeightPt ?: 842f
    private val left = section?.marginLeftPt ?: 72f
    private val right = section?.marginRightPt ?: 72f
    private val top = section?.marginTopPt ?: 72f
    private val bottom = section?.marginBottomPt ?: 72f
    private val contentW = pageW - left - right
    private val labels = doc.listLabels()

    fun export(out: File) {
        try {
            newPage()
            for (b in doc.blocks()) drawBlock(b, left, contentW)
            finishPage()
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        val p = pdf.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNumber).create())
        page = p
        canvas = p.canvas
        y = top
        drawHeaderFooter(p.canvas)
    }

    private fun finishPage() {
        page?.let { pdf.finishPage(it) }
        page = null
    }

    private fun drawHeaderFooter(c: Canvas) {
        val s = section ?: return
        for (kind in HeaderFooter.Kind.entries) {
            val hf = doc.headerFooter(s, kind) ?: continue
            var yy = if (kind == HeaderFooter.Kind.HEADER) top / 2 else pageH - bottom / 2 - 12f
            for (p in hf.paragraphs) {
                val layout = layoutParagraph(p, contentW, pageNumberText = pageNumber.toString()) ?: continue
                c.save()
                c.translate(left, yy)
                layout.draw(c)
                c.restore()
                yy += layout.height
            }
        }
    }

    private fun ensureSpace(h: Float) {
        if (y + h > pageH - bottom && y > top + 0.5f) newPage()
    }

    private fun drawBlock(b: Block, x: Float, width: Float) {
        when (b) {
            is Block.Para -> drawParagraph(b.paragraph, x, width)
            is Block.Table -> drawTable(b.table, x, width)
            is Block.Locked -> drawPlaceholder(b.label, x, width, 36f)
        }
    }

    private fun drawParagraph(p: Paragraph, x: Float, width: Float) {
        val f = p.format
        if (f.pageBreakBefore || p.atoms.any { it.second.kind == AtomKind.PAGE_BREAK && it.first == 0 }) newPage()
        y += f.spaceBefore
        val indentL = f.indentLeft.coerceAtMost(width * 0.8f)
        val w = (width - indentL - f.indentRight).coerceAtLeast(40f)
        val layout = layoutParagraph(p, w) ?: return
        drawLayoutPaginated(layout, x + indentL)
        y += f.spaceAfter
    }

    /** Draws a layout line by line, moving to a new page when a line does not fit. */
    private fun drawLayoutPaginated(layout: StaticLayout, x: Float) {
        var line = 0
        while (line < layout.lineCount) {
            val c = canvas ?: return
            val startTop = layout.getLineTop(line).toFloat()
            var end = line
            while (end < layout.lineCount && y + (layout.getLineBottom(end) - startTop) <= pageH - bottom) end++
            if (end == line) {
                if (y > top + 0.5f) { newPage(); continue }
                end = line + 1 // a single line taller than the page: draw it clipped
            }
            val endBottom = layout.getLineBottom(end - 1).toFloat()
            c.save()
            c.translate(x, y - startTop)
            c.clipRect(0f, startTop, layout.width.toFloat(), endBottom)
            layout.draw(c)
            c.restore()
            y += endBottom - startTop
            line = end
            if (line < layout.lineCount) newPage()
        }
    }

    private fun layoutParagraph(p: Paragraph, width: Float, pageNumberText: String? = null): StaticLayout? {
        val f = p.format
        val text = p.text
        val sb = SpannableStringBuilder()
        val label = labels[p]
        if (label != null) sb.append(label).append(' ')
        val base = sb.length
        val atoms = p.atoms.toMap()
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                InlineChars.OBJECT -> {
                    val atom = atoms[i]
                    when {
                        atom == null -> sb.append(' ')
                        atom.kind == AtomKind.IMAGE && atom.image != null -> {
                            val start = sb.length
                            sb.append('￼')
                            val bytes = doc.imageBytes(p.part, atom.image!!)
                            val bmp = bytes?.let { ImageLoader.decode(it, 1600) }
                            if (bmp != null) {
                                var iw = atom.image!!.widthPt
                                var ih = atom.image!!.heightPt
                                if (iw > width) { ih *= width / iw; iw = width }
                                val d = BitmapDrawable(null, bmp).apply { setBounds(0, 0, iw.toInt().coerceAtLeast(1), ih.toInt().coerceAtLeast(1)) }
                                sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        atom.kind == AtomKind.PAGE_BREAK || atom.kind == AtomKind.COLUMN_BREAK -> sb.append(' ')
                        atom.kind == AtomKind.FIELD -> {
                            val instr = atom.element.attributes.firstOrNull { it.localName == "instr" }?.value?.trim()?.uppercase() ?: ""
                            sb.append(if (pageNumberText != null && instr.startsWith("PAGE")) pageNumberText else atom.label)
                        }
                        atom.kind == AtomKind.SYMBOL || atom.kind == AtomKind.FOOTNOTE_REFERENCE || atom.kind == AtomKind.ENDNOTE_REFERENCE -> sb.append(atom.label)
                        else -> {
                            val start = sb.length
                            sb.append("[${atom.label}]")
                            sb.setSpan(ForegroundColorSpan(0xFF777777.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                InlineChars.LINE_BREAK -> sb.append('\n')
                else -> sb.append(ch)
            }
        }
        // Run formatting. Offsets in the builder differ from the model only after expanded objects,
        // so spans are mapped through a running delta.
        val mapping = IntArray(text.length + 1)
        run {
            var pos = base
            for ((i, ch) in text.withIndex()) {
                mapping[i] = pos
                pos += if (ch == InlineChars.OBJECT) {
                    val atom = atoms[i]
                    when {
                        atom == null -> 1
                        atom.kind == AtomKind.IMAGE -> 1
                        atom.kind == AtomKind.PAGE_BREAK || atom.kind == AtomKind.COLUMN_BREAK -> 1
                        atom.kind == AtomKind.FIELD -> {
                            val instr = atom.element.attributes.firstOrNull { it.localName == "instr" }?.value?.trim()?.uppercase() ?: ""
                            (if (pageNumberText != null && instr.startsWith("PAGE")) pageNumberText else atom.label).length
                        }
                        atom.kind == AtomKind.SYMBOL || atom.kind == AtomKind.FOOTNOTE_REFERENCE || atom.kind == AtomKind.ENDNOTE_REFERENCE -> atom.label.length
                        else -> atom.label.length + 2
                    }
                } else 1
            }
            mapping[text.length] = pos
        }
        val spans = p.spans()
        for (s in spans) {
            val a = mapping[s.start.coerceIn(0, text.length)]
            val e = mapping[s.end.coerceIn(0, text.length)]
            if (a >= e) continue
            val rf = s.format
            fun set(span: Any) = sb.setSpan(span, a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            set(AbsoluteSizeSpan(rf.sizePt.toInt().coerceAtLeast(1)))
            when {
                rf.bold && rf.italic -> set(StyleSpan(Typeface.BOLD_ITALIC))
                rf.bold -> set(StyleSpan(Typeface.BOLD))
                rf.italic -> set(StyleSpan(Typeface.ITALIC))
            }
            if (rf.underline || s.link != null) set(UnderlineSpan())
            if (rf.strike) set(StrikethroughSpan())
            val color = DocsText.color(rf.color) ?: if (s.link != null) DocsText.linkColor else null
            color?.let { set(ForegroundColorSpan(it.toArgb())) }
            DocsText.highlight(rf.highlight)?.let { set(BackgroundColorSpan(it.toArgb())) }
            val family = DocsText.family(rf.fontFamily)
            set(TypefaceSpan(when (family) {
                androidx.compose.ui.text.font.FontFamily.Monospace -> "monospace"
                androidx.compose.ui.text.font.FontFamily.Serif -> "serif"
                else -> "sans-serif"
            }))
            if (rf.superscript) { set(SuperscriptSpan()); set(RelativeSizeSpan(0.7f)) }
            if (rf.subscript) { set(SubscriptSpan()); set(RelativeSizeSpan(0.7f)) }
        }
        if (sb.isEmpty()) sb.append(' ')
        val mark = p.formatAt(0)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = mark.sizePt
            color = 0xFF000000.toInt()
        }
        val first = f.indentFirstLine
        if (label != null || first != 0f) {
            val hanging = if (label != null) maxOf(18f, -first) else 0f
            val firstIndent = if (label != null) 0 else first.toInt().coerceAtLeast(0)
            val rest = if (label != null) hanging.toInt() else (-first).toInt().coerceAtLeast(0)
            sb.setSpan(LeadingMarginSpan.Standard(firstIndent, rest), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val align = when (f.alignment) {
            Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(align)
            .setIncludePad(false)
        if (f.lineExactPt != null) {
            builder.setLineSpacing(f.lineExactPt!! - mark.sizePt * 1.17f, 1f)
        } else {
            builder.setLineSpacing(0f, f.lineMultiple.coerceIn(0.5f, 4f))
        }
        if (f.alignment == Alignment.JUSTIFY) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }

    private fun drawTable(t: DocxTable, x: Float, width: Float) {
        val grid = t.columnWidthsTwips
        val totalTwips = grid.sum().coerceAtLeast(1)
        val tableW = minOf(width, totalTwips / 20f).coerceAtLeast(width * 0.3f)
        val borders = t.hasBorders
        val linePaint = Paint().apply { color = 0xFF444444.toInt(); strokeWidth = if (borders) 0.6f else 0f; style = Paint.Style.STROKE }
        val pad = 4f
        for (row in t.rows) {
            var col = 0
            val cells = row.cells.map { cell ->
                val span = cell.gridSpan
                val tw = (col until col + span).sumOf { grid.getOrElse(it) { totalTwips / maxOf(grid.size, 1) } }
                val cx = x + tableW * (grid.take(col).sum().toFloat() / totalTwips)
                col += span
                val cw = tableW * tw.toFloat() / totalTwips
                val layouts = if (cell.verticalMerge == "continue") emptyList() else
                    cell.paragraphs.mapNotNull { p -> layoutParagraph(p, (cw - 2 * pad).coerceAtLeast(10f))?.let { it to p.format } }
                val h = layouts.sumOf { (l, f) -> (l.height + f.spaceBefore + f.spaceAfter).toDouble() }.toFloat() + 2 * pad
                Triple(cx to cw, layouts, h)
            }
            val rowH = maxOf(cells.maxOfOrNull { it.third } ?: 16f, (row.heightTwips ?: 0) / 20f, 14f)
            ensureSpace(rowH)
            val c = canvas ?: return
            for ((geo, layouts, _) in cells) {
                val (cx, cw) = geo
                var cy = y + pad
                for ((l, f) in layouts) {
                    cy += f.spaceBefore
                    c.save(); c.translate(cx + pad, cy); l.draw(c); c.restore()
                    cy += l.height + f.spaceAfter
                }
                if (borders) c.drawRect(RectF(cx, y, cx + cw, y + rowH), linePaint)
            }
            y += rowH
        }
        y += 6f
    }

    private fun drawPlaceholder(label: String, x: Float, width: Float, h: Float) {
        ensureSpace(h)
        val c = canvas ?: return
        val box = Paint().apply { color = 0xFFEEEEEE.toInt() }
        c.drawRect(x, y, x + width, y + h, box)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF555555.toInt() }
        c.drawText("[$label]", x + 6f, y + h / 2 + 3f, tp)
        y += h + 4f
    }

    @Suppress("unused")
    private fun recycle(b: Bitmap) = b.recycle()
}
