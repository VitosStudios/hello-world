package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.xml.XElement

class TableCell internal constructor(val table: DocxTable, val element: XElement) {
    val gridSpan: Int get() = element.w("tcPr")?.w("gridSpan")?.wAttr("val")?.toIntOrNull() ?: 1

    /** "restart", "continue" or null. */
    val verticalMerge: String?
        get() = element.w("tcPr")?.w("vMerge")?.let { it.wAttr("val") ?: "continue" }

    val widthTwips: Int? get() = element.w("tcPr")?.w("tcW")?.takeIf { it.wAttr("type") == "dxa" }?.wAttr("w")?.toIntOrNull()

    val shading: String? get() = element.w("tcPr")?.w("shd")?.wAttr("fill")?.takeUnless { it.equals("auto", true) }

    /** Cell content (paragraphs and nested tables). */
    val blocks: List<Block> get() = table.doc.blocksOf(table.part, element)

    val paragraphs: List<Paragraph> get() = blocks.filterIsInstance<Block.Para>().map { it.paragraph }
}

class TableRow internal constructor(val table: DocxTable, val element: XElement) {
    val cells: List<TableCell> get() = element.ws("tc").map { TableCell(table, it) }.toList()
    val isHeader: Boolean get() = element.w("trPr")?.onOff("tblHeader") == true
    val heightTwips: Int? get() = element.w("trPr")?.w("trHeight")?.wAttr("val")?.toIntOrNull()
}

/**
 * View over a `w:tbl`. Cell text is edited through the cell paragraphs. Row and column insertion
 * and deletion are offered only for tables without merged cells; tables with merges keep their
 * structure read-only (their text stays editable).
 */
class DocxTable internal constructor(val doc: DocxDocument, val element: XElement, val part: Part) {
    val rows: List<TableRow> get() = element.ws("tr").map { TableRow(this, it) }.toList()

    val columnWidthsTwips: List<Int>
        get() = element.w("tblGrid")?.ws("gridCol")?.map { it.wAttr("w")?.toIntOrNull() ?: 0 }?.toList().orEmpty()

    val columnCount: Int get() = maxOf(columnWidthsTwips.size, rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan } } ?: 0)

    val styleId: String? get() = element.w("tblPr")?.w("tblStyle")?.wAttr("val")

    val hasBorders: Boolean
        get() {
            val direct = element.w("tblPr")?.w("tblBorders")
            if (direct != null) return direct.elements().any { (it.wAttr("val") ?: "none") !in setOf("none", "nil") }
            return styleId?.let { id -> doc.styles.chain(id).any { s -> s.w("tblPr")?.w("tblBorders") != null } } == true
        }

    val hasMergedCells: Boolean
        get() = element.descendants(Wml.W, "tcPr").any { (it.w("gridSpan")?.wAttr("val")?.toIntOrNull() ?: 1) > 1 || it.w("vMerge") != null || it.w("hMerge") != null }

    /** Structural edits are safe only on a plain grid. */
    val isStructureEditable: Boolean
        get() = !hasMergedCells && element.ws("tr").all { it.ws("tc").count() == columnCount } &&
            element.descendants(Wml.W, "ins").none() && element.descendants(Wml.W, "del").none()

    private fun requireStructure() {
        if (!isStructureEditable) throw ReadOnlyContentException("This table has merged cells or tracked changes; its structure is read-only")
    }

    /** Inserts an empty row at [index] (0..rows.size), copying the formatting of a neighbour row. */
    fun insertRow(index: Int) {
        requireStructure()
        val rowEls = element.ws("tr").toList()
        require(index in 0..rowEls.size)
        doc.pkg.undo.transaction("Insert row") {
            val template = rowEls[(index - 1).coerceAtLeast(0).coerceAtMost(rowEls.size - 1)]
            val copy = template.deepCopy()
            if (index < rowEls.size) element.insertBefore(copy, rowEls[index]) else element.insertAfter(copy, rowEls.last())
            // Clear once attached, so namespace-aware lookups resolve.
            for (tc in copy.ws("tc").toList()) clearCell(tc)
            copy.w("trPr")?.w("tblHeader")?.detach()
        }
    }

    fun deleteRow(index: Int) {
        requireStructure()
        val rowEls = element.ws("tr").toList()
        require(index in rowEls.indices)
        if (rowEls.size == 1) throw IllegalStateException("A table needs at least one row; delete the table instead")
        doc.pkg.undo.transaction("Delete row") { rowEls[index].detach() }
    }

    /** Inserts an empty column at [index] (0..columnCount); widths are rescaled to keep the table width. */
    fun insertColumn(index: Int) {
        requireStructure()
        val cols = columnCount
        require(index in 0..cols)
        doc.pkg.undo.transaction("Insert column") {
            val widths = columnWidthsTwips.toMutableList()
            val total = widths.sum()
            val newWidth = if (widths.isEmpty()) 1440 else total / (cols + 1)
            if (widths.isNotEmpty()) {
                val scale = (total - newWidth).toDouble() / total
                for (i in widths.indices) widths[i] = (widths[i] * scale).toInt()
            }
            widths.add(index, newWidth)
            for (tr in element.ws("tr").toList()) {
                val tcs = tr.ws("tc").toList()
                val template = tcs[(index - 1).coerceAtLeast(0).coerceAtMost(tcs.size - 1)]
                val copy = template.deepCopy()
                if (index < tcs.size) tr.insertBefore(copy, tcs[index]) else tr.insertAfter(copy, tcs.last())
                clearCell(copy)
            }
            applyWidths(widths)
        }
    }

    fun deleteColumn(index: Int) {
        requireStructure()
        val cols = columnCount
        require(index in 0 until cols)
        if (cols == 1) throw IllegalStateException("A table needs at least one column; delete the table instead")
        doc.pkg.undo.transaction("Delete column") {
            val widths = columnWidthsTwips.toMutableList()
            val removed = if (index < widths.size) widths.removeAt(index) else 0
            if (widths.isNotEmpty() && removed > 0) {
                val total = widths.sum()
                for (i in widths.indices) widths[i] = widths[i] + removed * widths[i] / maxOf(total, 1)
            }
            for (tr in element.ws("tr").toList()) tr.ws("tc").toList().getOrNull(index)?.detach()
            applyWidths(widths)
        }
    }

    private fun applyWidths(widths: List<Int>) {
        val grid = element.w("tblGrid") ?: return
        val gridCols = grid.ws("gridCol").toList()
        for ((i, w) in widths.withIndex()) {
            val gc = gridCols.getOrNull(i) ?: grid.newW("gridCol").also { grid.appendChild(it) }
            gc.setAttr(Wml.W, "w", w.toString())
        }
        for (extra in gridCols.drop(widths.size)) extra.detach()
        for (tr in element.ws("tr")) {
            for ((i, tc) in tr.ws("tc").withIndex()) {
                val tcW = tc.w("tcPr")?.w("tcW") ?: continue
                if (tcW.wAttr("type") == "dxa" && i < widths.size) tcW.setAttr(Wml.W, "w", widths[i].toString())
            }
        }
    }

    /** Leaves one empty paragraph that keeps the first paragraph's properties. */
    private fun clearCell(tc: XElement) {
        tc.w("tcPr")?.w("vMerge")?.detach()
        val firstP = tc.ws("p").firstOrNull()
        val keepPPr = firstP?.w("pPr")?.deepCopy()
        for (c in tc.children.toList()) {
            if (c is XElement && c.isW("tcPr")) continue
            tc.removeChild(c)
        }
        val p = tc.newW("p")
        tc.appendChild(p)
        keepPPr?.let { pPr ->
            p.appendChild(pPr)
            pPr.w("sectPr")?.detach()
        }
    }
}
