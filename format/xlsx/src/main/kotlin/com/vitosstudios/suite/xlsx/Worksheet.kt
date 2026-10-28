package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XDocument
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XText
import com.vitosstudios.suite.xml.XmlCodec
import com.vitosstudios.suite.xml.XmlEscaping
import java.util.TreeMap

enum class CellType { BLANK, NUMBER, STRING, BOOLEAN, ERROR }

/** A read-only snapshot of one cell. Rows and columns are 1-based. */
data class CellView(
    val row: Int,
    val col: Int,
    val type: CellType,
    /** Number (also for dates), text, boolean or error code, depending on [type]. */
    val value: Any?,
    /** Formula text without "=" (shared formulas translated to this cell), or null. */
    val formula: String?,
    val isSharedFormula: Boolean,
    val isArrayFormula: Boolean,
    /** The value formatted with the cell's number format. */
    val display: String,
    val styleIndex: Int,
    val style: CellStyle,
    /** True if the number format shows a date or time. */
    val isDate: Boolean,
) {
    val ref: String get() = CellRef(row, col).a1
    val number: Double? get() = value as? Double
    val text: String? get() = value as? String
}

data class HyperlinkInfo(val range: CellRange, val target: String?, val location: String?, val display: String?)
data class TableInfo(val name: String, val range: CellRange, val partName: String)

/**
 * One worksheet. Reads go through a row/column index over the `sheetData` element that is
 * rebuilt only when the part changed behind its back (undo/redo); edits keep it in sync.
 */
class Worksheet internal constructor(
    val workbook: XlsxWorkbook,
    val part: Part,
) {
    private val editableDoc: XDocument? = part.xmlOrNull()
    private val doc: XDocument = editableDoc ?: try { XmlCodec.parse(part.bytes()) } catch (e: Exception) { XlsxWorkbook.emptySheetDocument() }

    /** False if the sheet XML could not be parsed losslessly; such sheets are shown but not edited. */
    val isEditable: Boolean get() = editableDoc != null && workbook.styles.isEditable

    val info: SheetInfo get() = workbook.sheets.first { it.partName.equals(part.name, true) }
    val name: String get() = info.name
    val index: Int get() = info.index

    internal val root: XElement get() = doc.root

    // ------------------------------------------------------------------------------------------
    // Index

    internal class RowEntry(val el: XElement) {
        val cells = TreeMap<Int, XElement>()
    }

    internal class SharedMaster(val row: Int, val col: Int, val text: String, val ref: CellRange?)

    private var indexVersion = -1L
    private val rows = TreeMap<Int, RowEntry>()
    private val masters = HashMap<String, SharedMaster>()
    private var sheetData: XElement? = null

    internal fun ensureIndex() {
        if (indexVersion == doc.modCount && sheetData != null) return
        rows.clear()
        masters.clear()
        val sd = root.firstChild("sheetData")
        sheetData = sd
        if (sd != null) {
            var lastRow = 0
            for (rn in sd.children) {
                if (rn !is XElement || rn.localName != "row") continue
                val r = rn.a("r")?.toIntOrNull() ?: (lastRow + 1)
                lastRow = r
                val entry = rows.getOrPut(r) { RowEntry(rn) }
                var lastCol = 0
                for (cn in rn.children) {
                    if (cn !is XElement || cn.localName != "c") continue
                    val ref = cn.a("r")?.let { CellRef.parseCellAttr(it) }
                    val c = ref?.col ?: (lastCol + 1)
                    lastCol = c
                    entry.cells[c] = cn
                    val f = cn.firstChild("f")
                    if (f != null && f.a("t") == "shared") {
                        val si = f.a("si")
                        val text = f.textContent
                        if (si != null && text.isNotEmpty()) masters[si] = SharedMaster(r, c, text, f.a("ref")?.let { CellRange.parseOrNull(it) })
                    }
                }
            }
        }
        indexVersion = doc.modCount
    }

    /** Marks the index as matching the document after an edit that kept it up to date. */
    private fun synced() { indexVersion = doc.modCount }

    internal fun cellElement(row: Int, col: Int): XElement? {
        ensureIndex()
        return rows[row]?.cells?.get(col)
    }

    internal fun rowElement(row: Int): XElement? {
        ensureIndex()
        return rows[row]?.el
    }

    /** Rows that exist in the file, ascending. */
    fun rowNumbers(): List<Int> { ensureIndex(); return rows.keys.toList() }

    /** Cell elements of a row, ascending by column. */
    internal fun cellsOfRow(row: Int): Map<Int, XElement> { ensureIndex(); return rows[row]?.cells ?: emptyMap() }

    /** Columns used in [row] (cells present in the file). */
    fun columnsInRow(row: Int): List<Int> = cellsOfRow(row).keys.toList()

    internal fun forEachCell(r1: Int, c1: Int, r2: Int, c2: Int, f: (Int, Int, XElement) -> Unit) {
        ensureIndex()
        for ((r, entry) in rows.subMap(r1, true, r2, true)) {
            for ((c, el) in entry.cells.subMap(c1, true, c2, true)) f(r, c, el)
        }
    }

    internal fun allCellsSnapshot(): List<Triple<Int, Int, XElement>> {
        ensureIndex()
        val out = ArrayList<Triple<Int, Int, XElement>>()
        for ((r, entry) in rows) for ((c, el) in entry.cells) out += Triple(r, c, el)
        return out
    }

    internal fun sharedMaster(si: String): SharedMaster? { ensureIndex(); return masters[si] }

    // ------------------------------------------------------------------------------------------
    // Reading

    fun cell(row: Int, col: Int): CellView? {
        val el = cellElement(row, col) ?: return null
        return view(row, col, el)
    }

    fun cell(a1: String): CellView? = CellRef.parse(a1).let { cell(it.row, it.col) }

    /** Displayed text of a cell ("" for empty). */
    fun displayText(row: Int, col: Int): String = cell(row, col)?.display ?: ""

    internal fun view(row: Int, col: Int, el: XElement): CellView {
        val s = el.a("s")?.toIntOrNull() ?: 0
        val style = workbook.styles.style(s)
        val f = el.firstChild("f")
        val fType = f?.a("t")
        var formula: String? = null
        var shared = false
        if (f != null) {
            val text = f.textContent
            when {
                fType == "shared" -> {
                    shared = true
                    formula = if (text.isNotEmpty()) text else f.a("si")?.let { masters[it] }?.let { m ->
                        FormulaRewrite.shift(m.text, row - m.row, col - m.col)
                    }
                }
                text.isNotEmpty() -> formula = text
            }
        }
        val raw = rawValue(el)
        val isDate = raw.first == CellType.NUMBER && workbook.styles.isDateStyle(s)
        val display = when (raw.first) {
            CellType.BLANK -> ""
            CellType.NUMBER -> NumberFormat.format(raw.second as Double, style.numFmtCode, workbook.date1904)
            CellType.STRING -> NumberFormat.of(style.numFmtCode).formatText(raw.second as String)
            CellType.BOOLEAN -> if (raw.second as Boolean) "TRUE" else "FALSE"
            CellType.ERROR -> raw.second as String
        }
        return CellView(row, col, raw.first, raw.second, formula, shared, fType == "array", display, s, style, isDate)
    }

    /** Type and value stored in a cell element (the cached value for formulas). */
    internal fun rawValue(el: XElement): Pair<CellType, Any?> {
        val t = el.a("t") ?: "n"
        if (t == "inlineStr") {
            val inline = el.firstChild("is") ?: return CellType.STRING to ""
            return CellType.STRING to SharedStrings.textOf(inline)
        }
        val v = el.firstChild("v")?.textContent
        return when (t) {
            "s" -> {
                val idx = v?.trim()?.toIntOrNull() ?: return CellType.BLANK to null
                CellType.STRING to (workbook.sharedStrings.get(idx) ?: "")
            }
            "str" -> CellType.STRING to (v ?: "")
            "b" -> if (v == null) CellType.BLANK to null else CellType.BOOLEAN to (v.trim() == "1" || v.trim().equals("true", true))
            "e" -> CellType.ERROR to (v ?: "#N/A")
            "d" -> {
                val dt = v?.let { parseIsoDate(it) } ?: return CellType.BLANK to null
                CellType.NUMBER to dt
            }
            else -> {
                if (v.isNullOrBlank()) CellType.BLANK to null
                else v.trim().toDoubleOrNull()?.let { CellType.NUMBER to it } ?: (CellType.STRING to v)
            }
        }
    }

    private fun parseIsoDate(s: String): Double? = try {
        if (s.contains('T')) ExcelDates.serial(java.time.LocalDateTime.parse(s.trim().removeSuffix("Z")), workbook.date1904)
        else ExcelDates.serial(java.time.LocalDate.parse(s.trim()), workbook.date1904)
    } catch (e: Exception) { null }

    /** Smallest range covering every cell present in the file (null for an empty sheet). */
    fun usedRange(): CellRange? {
        ensureIndex()
        if (rows.isEmpty()) return null
        var minC = Int.MAX_VALUE
        var maxC = 0
        var minR = Int.MAX_VALUE
        var maxR = 0
        for ((r, e) in rows) {
            if (e.cells.isEmpty()) continue
            minR = minOf(minR, r); maxR = maxOf(maxR, r)
            minC = minOf(minC, e.cells.firstKey()); maxC = maxOf(maxC, e.cells.lastKey())
        }
        return if (maxR == 0) null else CellRange(minR, minC, maxR, maxC)
    }

    /** Reads every cell (used by save verification and tests). Returns the number of cells read. */
    fun readAllCells(): Int {
        ensureIndex()
        var n = 0
        for ((r, e) in rows) for ((c, el) in e.cells) { view(r, c, el); n++ }
        return n
    }

    val defaultColumnWidth: Double
        get() = root.firstChild("sheetFormatPr")?.let { it.a("defaultColWidth")?.toDoubleOrNull() ?: it.a("baseColWidth")?.toDoubleOrNull()?.plus(0.7109375) } ?: 8.43

    val defaultRowHeight: Double get() = root.firstChild("sheetFormatPr")?.a("defaultRowHeight")?.toDoubleOrNull() ?: 15.0

    private fun colElement(col: Int): XElement? =
        root.firstChild("cols")?.childElements("col")?.firstOrNull { col in (it.a("min")?.toIntOrNull() ?: 0)..(it.a("max")?.toIntOrNull() ?: -1) }

    /** Column width in characters. */
    fun columnWidth(col: Int): Double = colElement(col)?.a("width")?.toDoubleOrNull() ?: defaultColumnWidth

    fun isColumnHidden(col: Int): Boolean = colElement(col)?.a("hidden").let { it == "1" || it == "true" }

    /** Row height in points. */
    fun rowHeight(row: Int): Double = rowElement(row)?.a("ht")?.toDoubleOrNull() ?: defaultRowHeight

    fun isRowHidden(row: Int): Boolean = rowElement(row)?.a("hidden").let { it == "1" || it == "true" }

    private fun sheetView(): XElement? = root.firstChild("sheetViews")?.firstChild("sheetView")

    private fun frozenPane(): XElement? = sheetView()?.firstChild("pane")?.takeIf { it.a("state") == "frozen" || it.a("state") == "frozenSplit" }

    val frozenRows: Int get() = frozenPane()?.a("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
    val frozenColumns: Int get() = frozenPane()?.a("xSplit")?.toDoubleOrNull()?.toInt() ?: 0

    val mergedRanges: List<CellRange>
        get() = root.firstChild("mergeCells")?.childElements("mergeCell")?.mapNotNull { it.a("ref")?.let(CellRange::parseOrNull) } ?: emptyList()

    val autoFilterRange: CellRange? get() = root.firstChild("autoFilter")?.a("ref")?.let(CellRange::parseOrNull)

    val hyperlinks: List<HyperlinkInfo>
        get() = root.firstChild("hyperlinks")?.childElements("hyperlink")?.mapNotNull { h ->
            val range = h.a("ref")?.let(CellRange::parseOrNull) ?: return@mapNotNull null
            val target = h.attr(Namespaces.R, "id")?.let { id -> workbook.pkg.relationships(part).byId(id)?.target }
            HyperlinkInfo(range, target, h.a("location"), h.a("display"))
        } ?: emptyList()

    /** Human-readable descriptions of conditional formatting rules (kept unchanged). */
    val conditionalFormats: List<String>
        get() {
            val out = ArrayList<String>()
            for (cf in root.childElements("conditionalFormatting")) {
                val sqref = cf.a("sqref") ?: ""
                for (rule in cf.childElements("cfRule")) {
                    val parts = listOfNotNull(rule.a("type"), rule.a("operator"))
                    val formulas = rule.childElements("formula").joinToString(", ") { it.textContent }
                    out += "${parts.joinToString(" ")}${if (formulas.isNotEmpty()) " $formulas" else ""} on $sqref"
                }
            }
            val x14 = root.firstChild("extLst")?.descendants()?.count { it.localName == "conditionalFormatting" } ?: 0
            if (x14 > 0) out += "$x14 extended rule group(s) (data bars, icon sets, ...)"
            return out
        }

    val dataValidationCount: Int
        get() = (root.firstChild("dataValidations")?.childElements("dataValidation")?.size ?: 0) +
            (root.firstChild("extLst")?.descendants()?.count { it.localName == "dataValidation" } ?: 0)

    val tables: List<TableInfo>
        get() = workbook.pkg.relationships(part).ofType(RelTypes.TABLE).mapNotNull { rel ->
            val p = workbook.pkg.relationships(part).targetPart(rel) ?: return@mapNotNull null
            val r = p.xmlOrNull()?.root ?: return@mapNotNull null
            TableInfo(r.a("displayName") ?: r.a("name") ?: "Table", r.a("ref")?.let(CellRange::parseOrNull) ?: return@mapNotNull null, p.name)
        }

    val commentCount: Int
        get() = workbook.pkg.relationships(part).ofType(RelTypes.COMMENTS).sumOf { rel ->
            workbook.pkg.relationships(part).targetPart(rel)?.xmlOrNull()?.root?.descendants()?.count { it.localName == "comment" } ?: 0
        }

    val hasSparklines: Boolean get() = root.firstChild("extLst")?.descendants()?.any { it.localName == "sparklineGroup" } == true

    fun charts(): List<ChartInfo> = Charts.chartsOf(this)
    fun pictures(): List<PictureInfo> = Charts.picturesOf(this)

    // ------------------------------------------------------------------------------------------
    // Editing helpers

    private fun requireEditable() {
        if (!isEditable) throw XlsxEditException("Sheet \"$name\" cannot be edited safely and is read-only")
    }

    private fun getOrCreateRow(row: Int): RowEntry {
        requireEditable()
        require(row in 1..SheetLimits.MAX_ROWS) { "Row out of range" }
        ensureIndex()
        rows[row]?.let { return it }
        val sd = sheetData ?: root.getOrCreate(Namespaces.S, "sheetData", XlsxSchema.WORKSHEET).also { sheetData = it }
        val re = sd.createChild(Namespaces.S, "row")
        re.setAttr("r", row.toString())
        val next = rows.higherEntry(row)?.value?.el
        if (next != null) sd.insertBefore(re, next) else {
            val ext = sd.firstChild("extLst")
            if (ext != null) sd.insertBefore(re, ext) else sd.appendChild(re)
        }
        val entry = RowEntry(re)
        rows[row] = entry
        synced()
        return entry
    }

    internal fun getOrCreateCell(row: Int, col: Int): XElement {
        requireEditable()
        require(row in 1..SheetLimits.MAX_ROWS && col in 1..SheetLimits.MAX_COLS) { "Cell out of range" }
        ensureIndex()
        rows[row]?.cells?.get(col)?.let { return it }
        val entry = getOrCreateRow(row)
        val c = entry.el.createChild(Namespaces.S, "c")
        c.setAttr("r", CellRef(row, col).a1)
        val nextCell = entry.cells.higherEntry(col)?.value
        if (nextCell != null) entry.el.insertBefore(c, nextCell) else {
            val ext = entry.el.firstChild("extLst")
            if (ext != null) entry.el.insertBefore(c, ext) else entry.el.appendChild(c)
        }
        entry.cells[col] = c
        // Keep the optional "spans" hint consistent.
        entry.el.a("spans")?.let { spans ->
            val lo = spans.substringBefore(':').toIntOrNull()
            val hi = spans.substringAfter(':').toIntOrNull()
            if (lo != null && hi != null && (col < lo || col > hi)) entry.el.setAttr("spans", "${minOf(lo, col)}:${maxOf(hi, col)}")
        }
        // Inherit a row style ("customFormat") the way spreadsheet applications do.
        if (entry.el.a("customFormat").let { it == "1" || it == "true" }) entry.el.a("s")?.let { c.setAttr("s", it) }
        else colElement(col)?.a("style")?.let { if (it != "0") c.setAttr("s", it) }
        synced()
        return c
    }

    private fun removeCellElement(row: Int, col: Int) {
        val entry = rows[row] ?: return
        val el = entry.cells.remove(col) ?: return
        el.detach()
        synced()
    }

    /** Removes value, formula and inline string, keeping the style. */
    private fun clearContent(c: XElement) {
        for (k in c.children.toList()) {
            if (k is XElement && (k.localName == "f" || k.localName == "v" || k.localName == "is")) c.removeChild(k)
        }
        c.removeAttribute("t")
        c.removeAttribute("cm")
        c.removeAttribute("vm")
    }

    private fun setV(c: XElement, text: String) {
        val v = c.firstChild("v") ?: c.createChild(Namespaces.S, "v").also { c.insertInOrder(it, XlsxSchema.CELL) }
        v.setText(text)
    }

    internal fun writeCachedValue(c: XElement, value: FValue) {
        when (value) {
            is FValue.Num -> { c.removeAttribute("t"); setV(c, formatXmlNumber(value.v)) }
            is FValue.Str -> { c.setAttr("t", "str"); setV(c, value.s) }
            is FValue.Bool -> { c.setAttr("t", "b"); setV(c, if (value.b) "1" else "0") }
            is FValue.Err -> { c.setAttr("t", "e"); setV(c, value.code) }
            else -> { c.removeAttribute("t"); setV(c, "0") }
        }
    }

    /** Array formulas cover a range; only the whole range can be changed. */
    private fun arrayContaining(row: Int, col: Int): CellRange? {
        ensureIndex()
        for ((_, entry) in rows.headMap(row, true).descendingMap()) {
            for ((_, el) in entry.cells) {
                val f = el.firstChild("f") ?: continue
                if (f.a("t") != "array") continue
                val ref = f.a("ref")?.let(CellRange::parseOrNull) ?: continue
                if (ref.contains(row, col) && !ref.isSingleCell) return ref
            }
        }
        return null
    }

    /**
     * Before a cell of a shared-formula group changes, every dependent of a master gets its own
     * plain formula so the group stays valid.
     */
    private fun detachFromSharedGroup(c: XElement, row: Int, col: Int) {
        val f = c.firstChild("f") ?: return
        if (f.a("t") != "shared") return
        val si = f.a("si") ?: return
        val master = masters[si]
        if (master == null || master.row != row || master.col != col) return
        val range = master.ref ?: usedRange() ?: return
        forEachCell(range.top, range.left, range.bottom, range.right) { r, cc, el ->
            if (r == row && cc == col) return@forEachCell
            val df = el.firstChild("f") ?: return@forEachCell
            if (df.a("t") == "shared" && df.a("si") == si && df.textContent.isEmpty()) {
                val text = FormulaRewrite.shift(master.text, r - master.row, cc - master.col)
                for (a in df.attributes.toList()) if (a.qName == "t" || a.qName == "si" || a.qName == "ref") df.removeAttribute(a.qName)
                df.setText(text)
            }
        }
        masters.remove(si)
        synced()
    }

    private fun expandDimension(row: Int, col: Int) {
        val dim = root.firstChild("dimension") ?: return
        val cur = dim.a("ref")?.let(CellRange::parseOrNull)
        val wanted = if (cur == null) CellRange(row, col, row, col) else cur.union(CellRange(row, col, row, col))
        if (wanted != cur) dim.setAttr("ref", wanted.a1)
    }

    // ------------------------------------------------------------------------------------------
    // Edits

    /** Enters [input] into a cell the way a spreadsheet would (see [CellInput.parse]). */
    fun setCellInput(row: Int, col: Int, input: String) {
        val parsed = CellInput.parse(input, workbook.date1904)
        if (parsed is CellInput.Formula) FormulaParser.parse(parsed.text) // syntax check before any change
        workbook.edit("Edit ${CellRef(row, col).a1}") {
            setCellContent(row, col, parsed)
            workbook.afterValueEdit()
        }
    }

    fun setCellInput(a1: String, input: String) = CellRef.parse(a1).let { setCellInput(it.row, it.col, input) }

    internal fun setCellContent(row: Int, col: Int, parsed: CellInput, applyFormat: Boolean = true) {
        requireEditable()
        arrayContaining(row, col)?.let { throw XlsxEditException("${CellRef(row, col).a1} is part of the array formula in ${it.a1}") }
        val existing = cellElement(row, col)
        if (parsed is CellInput.Blank && existing == null) return
        val c = existing ?: getOrCreateCell(row, col)
        detachFromSharedGroup(c, row, col)
        if (c.firstChild("f") != null) workbook.formulaRemoved()
        clearContent(c)
        when (parsed) {
            is CellInput.Blank -> {
                if (c.attributes.all { it.qName == "r" }) removeCellElement(row, col)
            }
            is CellInput.Number -> {
                setV(c, formatXmlNumber(parsed.value))
                if (applyFormat && parsed.format != null) {
                    val s = c.a("s")?.toIntOrNull() ?: 0
                    if (workbook.styles.numFmtIdOf(s) == 0) {
                        workbook.styles.beginBatch()
                        c.setAttr("s", workbook.styles.patched(s, StylePatch(numberFormat = parsed.format)).toString())
                    }
                }
            }
            is CellInput.Text -> {
                val idx = workbook.sharedStrings.indexOf(parsed.value)
                c.setAttr("t", "s")
                setV(c, idx.toString())
            }
            is CellInput.Bool -> { c.setAttr("t", "b"); setV(c, if (parsed.value) "1" else "0") }
            is CellInput.Formula -> {
                val text = FormulaRewrite.normalizeInput(parsed.text)
                val f = c.createChild(Namespaces.S, "f")
                f.appendChild(XText(XmlEscaping.escapeText(text)))
                c.insertInOrder(f, XlsxSchema.CELL)
                // The value is written by the recalculation; formulas this engine cannot evaluate
                // get no cached value and are computed by the next spreadsheet application.
            }
        }
        if (parsed !is CellInput.Blank) expandDimension(row, col)
        synced()
    }

    /** Clears values and formulas in [range]; formatting stays. */
    fun clearRange(range: CellRange) {
        requireEditable()
        workbook.edit("Clear ${range.a1}") {
            val targets = ArrayList<Pair<Int, Int>>()
            forEachCell(range.top, range.left, range.bottom, range.right) { r, c, _ -> targets += r to c }
            for ((r, c) in targets) {
                val arr = arrayContaining(r, c)
                if (arr != null && !(range.contains(arr.first) && range.contains(arr.last))) {
                    throw XlsxEditException("${CellRef(r, c).a1} is part of the array formula in ${arr.a1}")
                }
            }
            for ((r, c) in targets) {
                val el = cellElement(r, c) ?: continue
                detachFromSharedGroup(el, r, c)
                if (el.firstChild("f") != null) workbook.formulaRemoved()
                clearContent(el)
                if (el.attributes.all { it.qName == "r" }) removeCellElement(r, c)
            }
            synced()
            workbook.afterValueEdit()
        }
    }

    /**
     * Applies [patch] to every cell of [range], creating cells where needed. Very large selections
     * (whole columns or rows) are limited to the used part of the sheet.
     */
    fun applyStyle(range: CellRange, patch: StylePatch) {
        requireEditable()
        val effective = if (range.rowCount.toLong() * range.colCount > 200_000) {
            val used = usedRange() ?: return
            if (!used.intersects(range)) return
            CellRange(maxOf(range.top, used.top), maxOf(range.left, used.left), minOf(range.bottom, used.bottom), minOf(range.right, used.right))
        } else range
        workbook.edit("Format ${effective.a1}") {
            workbook.styles.beginBatch()
            for (r in effective.top..effective.bottom) for (c in effective.left..effective.right) {
                val el = getOrCreateCell(r, c)
                val s = el.a("s")?.toIntOrNull() ?: 0
                val ns = workbook.styles.patched(s, patch)
                if (ns != s) {
                    if (ns == 0) el.removeAttribute("s") else el.setAttr("s", ns.toString())
                }
            }
            synced()
        }
    }

    /** Sets a column width in characters. */
    fun setColumnWidth(col: Int, width: Double) {
        requireEditable()
        require(width in 0.0..255.0) { "Column width must be between 0 and 255" }
        workbook.edit("Column width") {
            val cols = root.getOrCreate(Namespaces.S, "cols", XlsxSchema.WORKSHEET)
            val existing = cols.childElements("col").firstOrNull { col in (it.a("min")?.toIntOrNull() ?: 0)..(it.a("max")?.toIntOrNull() ?: -1) }
            val target = if (existing == null) {
                val e = cols.createChild(Namespaces.S, "col")
                e.setAttr("min", col.toString()); e.setAttr("max", col.toString())
                val next = cols.childElements("col").firstOrNull { (it.a("min")?.toIntOrNull() ?: 0) > col }
                if (next != null) cols.insertBefore(e, next) else cols.appendChild(e)
                e
            } else splitCol(cols, existing, col)
            target.setAttr("width", formatXmlNumber(width))
            target.setAttr("customWidth", "1")
            synced()
        }
    }

    /** Splits a `<col min max>` range so that [col] has its own element, which is returned. */
    private fun splitCol(cols: XElement, e: XElement, col: Int): XElement {
        val min = e.a("min")!!.toInt()
        val max = e.a("max")!!.toInt()
        if (min == col && max == col) return e
        if (min < col) {
            val before = e.deepCopy()
            before.setAttr("max", (col - 1).toString())
            cols.insertBefore(before, e)
        }
        if (max > col) {
            val after = e.deepCopy()
            after.setAttr("min", (col + 1).toString())
            cols.insertAfter(after, e)
        }
        e.setAttr("min", col.toString())
        e.setAttr("max", col.toString())
        return e
    }

    /** Sets a row height in points. */
    fun setRowHeight(row: Int, height: Double) {
        requireEditable()
        require(height in 0.0..409.0) { "Row height must be between 0 and 409 points" }
        workbook.edit("Row height") {
            val re = getOrCreateRow(row).el
            re.setAttr("ht", formatXmlNumber(height))
            re.setAttr("customHeight", "1")
            synced()
        }
    }

    /** Freezes the top [rowCount] rows and left [colCount] columns (0, 0 unfreezes). */
    fun freezePanes(rowCount: Int, colCount: Int) {
        requireEditable()
        require(rowCount >= 0 && colCount >= 0)
        workbook.edit("Freeze panes") {
            val views = root.getOrCreate(Namespaces.S, "sheetViews", XlsxSchema.WORKSHEET)
            val view = views.firstChild("sheetView") ?: views.createChild(Namespaces.S, "sheetView").also {
                it.setAttr("workbookViewId", "0"); views.appendChild(it)
            }
            view.firstChild("pane")?.detach()
            val active = when {
                rowCount > 0 && colCount > 0 -> "bottomRight"
                rowCount > 0 -> "bottomLeft"
                colCount > 0 -> "topRight"
                else -> null
            }
            val validPanes = when (active) {
                "bottomRight" -> setOf("topRight", "bottomLeft", "bottomRight")
                null -> emptySet()
                else -> setOf(active)
            }
            for (sel in view.childElements("selection")) {
                val p = sel.a("pane") ?: continue
                if (p !in validPanes) sel.detach()
            }
            if (active != null) {
                val pane = views.createChild(Namespaces.S, "pane")
                if (colCount > 0) pane.setAttr("xSplit", colCount.toString())
                if (rowCount > 0) pane.setAttr("ySplit", rowCount.toString())
                pane.setAttr("topLeftCell", CellRef(rowCount + 1, colCount + 1).a1)
                pane.setAttr("activePane", active)
                pane.setAttr("state", "frozen")
                view.insertInOrder(pane, XlsxSchema.SHEET_VIEW)
                if (view.childElements("selection").none { it.a("pane") == active }) {
                    val sel = views.createChild(Namespaces.S, "selection")
                    sel.setAttr("pane", active)
                    view.insertInOrder(sel, XlsxSchema.SHEET_VIEW)
                }
            }
        }
    }

    /**
     * Sorts the rows of [range] by the values in column [keyColumn] (absolute index). Values,
     * formatting and cell types move with their rows; the range must not contain formulas or
     * merged cells.
     */
    fun sortRange(range: CellRange, keyColumn: Int, ascending: Boolean = true, hasHeader: Boolean = false) {
        requireEditable()
        require(keyColumn in range.left..range.right) { "Sort column is outside the range" }
        val body = if (hasHeader) {
            if (range.rowCount < 2) return
            CellRange(range.top + 1, range.left, range.bottom, range.right)
        } else range
        if (mergedRanges.any { it.intersects(body) }) throw XlsxEditException("Ranges with merged cells cannot be sorted")
        forEachCell(body.top, body.left, body.bottom, body.right) { _, _, el ->
            if (el.firstChild("f") != null) throw XlsxEditException("Ranges with formulas cannot be sorted")
        }
        class RowSnap(val cells: List<XElement?>, val key: FValue)
        val snaps = ArrayList<RowSnap>()
        for (r in body.top..body.bottom) {
            val cells = (body.left..body.right).map { c -> cellElement(r, c)?.deepCopy() }
            val keyEl = cellElement(r, keyColumn)
            val key = keyEl?.let { cellFValue(it) } ?: FValue.Blank
            snaps += RowSnap(cells, key)
        }
        val cmp = Comparator<RowSnap> { a, b ->
            val ab = a.key is FValue.Blank; val bb = b.key is FValue.Blank
            when {
                ab && bb -> 0
                ab -> 1 // blanks last in both directions
                bb -> -1
                else -> {
                    val ea = a.key is FValue.Err; val eb = b.key is FValue.Err
                    val c = when {
                        ea && eb -> 0
                        ea -> 1
                        eb -> -1
                        else -> FormulaEvaluator.compare(a.key, b.key)
                    }
                    if (ascending) c else -c
                }
            }
        }
        val sorted = snaps.sortedWith(cmp) // stable
        if (sorted == snaps) return
        workbook.edit("Sort ${range.a1}") {
            for ((i, snap) in sorted.withIndex()) {
                val r = body.top + i
                for ((j, src) in snap.cells.withIndex()) {
                    val c = body.left + j
                    val cur = cellElement(r, c)
                    if (src == null) {
                        if (cur != null) {
                            clearContent(cur)
                            cur.removeAttribute("s")
                            removeCellElement(r, c)
                        }
                        continue
                    }
                    val target = cur ?: getOrCreateCell(r, c)
                    clearContent(target)
                    src.a("s")?.let { target.setAttr("s", it) } ?: target.removeAttribute("s")
                    src.a("t")?.let { target.setAttr("t", it) }
                    for (k in src.children) {
                        if (k is XElement && (k.localName == "v" || k.localName == "is")) target.insertInOrder(k.deepCopy(), XlsxSchema.CELL)
                    }
                }
            }
            synced()
            workbook.afterValueEdit()
        }
    }

    internal fun cellFValue(el: XElement): FValue {
        val (t, v) = rawValue(el)
        return when (t) {
            CellType.BLANK -> FValue.Blank
            CellType.NUMBER -> FValue.Num(v as Double)
            CellType.STRING -> FValue.Str(v as String)
            CellType.BOOLEAN -> FValue.Bool(v as Boolean)
            CellType.ERROR -> FValue.Err(v as String)
        }
    }

    /** Distinct displayed values in one column of [range] below its header row (for a filter picker). */
    fun filterValues(range: CellRange, column: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (r in range.top + 1..range.bottom) out += displayText(r, column)
        return out.toList()
    }

    /**
     * Shows only the rows of [range] (first row = header) whose value in [column] is one of
     * [allowed] (displayed text); other rows are hidden. Replaces an existing filter.
     */
    fun setFilter(range: CellRange, column: Int, allowed: Set<String>) {
        requireEditable()
        require(column in range.left..range.right) { "Filter column is outside the range" }
        workbook.edit("Filter") {
            unhideFilteredRows()
            val af = root.firstChild("autoFilter") ?: root.createChild(Namespaces.S, "autoFilter").also { root.insertInOrder(it, XlsxSchema.WORKSHEET) }
            af.setAttr("ref", range.a1)
            for (fc in af.childElements("filterColumn")) fc.detach()
            val fc = af.createChild(Namespaces.S, "filterColumn")
            fc.setAttr("colId", (column - range.left).toString())
            val filters = af.createChild(Namespaces.S, "filters")
            if ("" in allowed) filters.setAttr("blank", "1")
            for (v in allowed.filter { it.isNotEmpty() }.sorted()) {
                val f = af.createChild(Namespaces.S, "filter")
                f.setAttr("val", v)
                filters.appendChild(f)
            }
            fc.appendChild(filters)
            af.insertInOrder(fc, XlsxSchema.AUTO_FILTER)
            for (r in range.top + 1..range.bottom) {
                val visible = displayText(r, column) in allowed
                if (!visible) getOrCreateRow(r).el.setAttr("hidden", "1")
            }
            synced()
            workbook.ensureFilterDatabaseName(this, range)
        }
    }

    /** Removes filter criteria and shows the filtered rows again; the filter buttons stay. */
    fun clearFilter() {
        requireEditable()
        val af = root.firstChild("autoFilter") ?: return
        workbook.edit("Clear filter") {
            unhideFilteredRows()
            for (fc in af.childElements("filterColumn")) fc.detach()
            synced()
        }
    }

    private fun unhideFilteredRows() {
        val range = autoFilterRange ?: return
        if (root.firstChild("autoFilter")?.childElements("filterColumn").isNullOrEmpty()) return
        ensureIndex()
        for ((_, e) in rows.subMap(range.top + 1, true, range.bottom, true)) {
            if (e.el.a("hidden") == "1" || e.el.a("hidden") == "true") e.el.removeAttribute("hidden")
        }
    }

    /** Adds a chart of [type] for [dataRange], drawn over [anchor]. */
    fun createChart(type: ChartType, dataRange: CellRange, anchor: CellRange, title: String? = null): ChartInfo {
        requireEditable()
        return workbook.edit("Insert chart") { Charts.create(this, type, dataRange, anchor, title) }
    }

    override fun toString(): String = "Worksheet($name)"
}
