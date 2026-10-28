package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.XElement

/**
 * Inserting and deleting rows and columns. Everything that addresses cells by position is moved
 * along: cell and row addresses, formulas on every sheet, defined names, chart references,
 * merged ranges, conditional formatting and data validation ranges and formulas, the
 * auto-filter, hyperlinks, page breaks, column widths and the dimension.
 *
 * Sheets with content whose positions this code does not rewrite (drawings, comments, tables,
 * pivot tables, array formulas, extension lists with ranges, embedded objects) are refused.
 */
internal object StructureEdits {
    enum class Axis { ROWS, COLUMNS }

    fun blocker(ws: Worksheet): String? {
        val wb = ws.workbook
        val rels = wb.pkg.relationships(ws.part)
        val root = ws.root
        if (root.firstChild("drawing") != null) return "it contains charts, pictures or shapes"
        if (root.firstChild("legacyDrawing") != null || root.firstChild("legacyDrawingHF") != null || rels.ofType(RelTypes.COMMENTS).isNotEmpty()) return "it contains comments or notes"
        if (rels.ofType(RelTypes.TABLE).isNotEmpty() || root.firstChild("tableParts") != null) return "it contains tables"
        if (rels.ofType(RelTypes.PIVOT_TABLE).isNotEmpty()) return "it contains pivot tables"
        if (root.firstChild("oleObjects") != null || root.firstChild("controls") != null || root.firstChild("picture") != null) return "it contains embedded objects or controls"
        if (root.firstChild("scenarios") != null || root.firstChild("dataConsolidate") != null || root.firstChild("protectedRanges") != null ||
            root.firstChild("customSheetViews") != null || root.firstChild("sortState") != null || root.firstChild("cellWatches") != null
        ) return "it contains scenarios, protected ranges or saved views"
        val ext = root.firstChild("extLst")
        if (ext != null && ext.descendants().any { it.localName in setOf("conditionalFormatting", "dataValidation", "sparklineGroup", "sqref", "f", "slicerList") }) {
            return "it contains extended formatting, validation or sparklines"
        }
        for ((_, _, el) in ws.allCellsSnapshot()) {
            val t = el.firstChild("f")?.a("t")
            if (t == "array" || t == "dataTable") return "it contains array formulas or data tables"
        }
        // Pivot caches built from this sheet would point at the wrong cells.
        for (p in wb.pkg.contentParts) {
            if (p.contentType?.contains("pivotCacheDefinition") != true) continue
            val src = p.xmlOrNull()?.root?.descendants()?.firstOrNull { it.localName == "worksheetSource" }
            if (src?.a("sheet")?.equals(ws.name, true) == true) return "a pivot table uses its data"
        }
        if (!ws.isEditable) return "the sheet cannot be edited safely"
        return null
    }

    fun insert(ws: Worksheet, axis: Axis, at: Int, count: Int) = apply(ws, axis, at, count, insert = true)

    fun delete(ws: Worksheet, axis: Axis, at: Int, count: Int) = apply(ws, axis, at, count, insert = false)

    private fun apply(ws: Worksheet, axis: Axis, at: Int, count: Int, insert: Boolean) {
        require(count >= 1) { "Count must be at least 1" }
        val limit = if (axis == Axis.ROWS) SheetLimits.MAX_ROWS else SheetLimits.MAX_COLS
        require(at in 1..limit) { "Position out of range" }
        val what = if (axis == Axis.ROWS) "Rows" else "Columns"
        blocker(ws)?.let { throw XlsxEditException("$what cannot be ${if (insert) "inserted" else "deleted"} on \"${ws.name}\" because $it") }
        if (insert) {
            val used = ws.usedRange()
            val last = if (axis == Axis.ROWS) used?.bottom else used?.right
            if (last != null && last + count > limit) throw XlsxEditException("Inserting would push data off the sheet")
        }
        val wb = ws.workbook
        val shift = Shift(ws.name, axis, at, count, insert)
        val label = (if (insert) "Insert " else "Delete ") + what.lowercase()
        wb.edit(label) {
            unshareGroups(ws)
            // Formulas on every sheet, plus conditional formatting and validation formulas.
            for (other in wb.worksheets()) {
                if (!other.isEditable) continue
                val implicit = other === ws
                for ((_, _, el) in other.allCellsSnapshot()) {
                    val f = el.firstChild("f") ?: continue
                    val t = f.textContent
                    if (t.isEmpty()) continue
                    val r = shift.formula(t, implicit)
                    if (r != t) f.setText(r)
                }
                for (fe in other.root.descendants().filter { it.localName == "formula" || it.localName == "formula1" || it.localName == "formula2" }.toList()) {
                    val t = fe.textContent
                    val r = shift.formula(t, implicit)
                    if (r != t) fe.setText(r)
                }
            }
            for (n in wb.definedNameElements()) {
                val t = n.textContent
                val r = shift.formula(t, implicit = false)
                if (r != t) n.setText(r)
            }
            Charts.rewriteChartReferences(wb) { shift.formula(it, implicit = false) }
            moveCells(ws, shift)
            moveSheetRanges(ws, shift)
            if (axis == Axis.COLUMNS) moveColumns(ws, shift)
            wb.formulaRemoved()
            wb.afterValueEdit()
        }
    }

    /** Shared formula groups that could straddle the change become plain formulas first. */
    private fun unshareGroups(ws: Worksheet) {
        val wb = ws.workbook
        for (sheet in wb.worksheets()) {
            if (!sheet.isEditable) continue
            val snapshot = sheet.allCellsSnapshot()
            val masters = HashMap<String, Triple<Int, Int, String>>()
            for ((r, c, el) in snapshot) {
                val f = el.firstChild("f") ?: continue
                if (f.a("t") != "shared") continue
                val text = f.textContent
                val si = f.a("si") ?: continue
                if (text.isNotEmpty()) masters[si] = Triple(r, c, text)
            }
            val affected = masters.filter { (_, m) -> sheet === ws || FormulaRewrite.referencesSheet(m.third, ws.name) }.keys
            if (affected.isEmpty()) continue
            for ((r, c, el) in snapshot) {
                val f = el.firstChild("f") ?: continue
                if (f.a("t") != "shared") continue
                val si = f.a("si") ?: continue
                if (si !in affected) continue
                val m = masters[si] ?: continue
                val text = if (f.textContent.isNotEmpty()) f.textContent else FormulaRewrite.shift(m.third, r - m.first, c - m.second)
                for (attr in listOf("t", "si", "ref")) f.removeAttribute(attr)
                f.setText(text)
            }
        }
    }

    private fun moveCells(ws: Worksheet, s: Shift) {
        val sd = ws.root.firstChild("sheetData") ?: return
        // Give every row and cell an explicit address: implicit ones would silently move.
        var lastRow = 0
        val rows = sd.childElements("row")
        for (row in rows) {
            val r = row.a("r")?.toIntOrNull() ?: (lastRow + 1)
            lastRow = r
            if (row.a("r") == null) row.setAttr("r", r.toString())
            var lastCol = 0
            for (c in row.childElements("c")) {
                val ref = c.a("r")?.let { CellRef.parseCellAttr(it) }
                val col = ref?.col ?: (lastCol + 1)
                lastCol = col
                if (ref == null) c.setAttr("r", CellRef(r, col).a1)
            }
        }
        for (row in rows) {
            val r = row.a("r")!!.toInt()
            if (s.axis == Axis.ROWS) {
                val nr = s.position(r)
                if (nr == null) { row.detach(); continue }
                if (nr != r) {
                    row.setAttr("r", nr.toString())
                    for (c in row.childElements("c")) {
                        val ref = CellRef.parseCellAttr(c.a("r")!!) ?: continue
                        c.setAttr("r", CellRef(nr, ref.col).a1)
                    }
                }
            } else {
                row.removeAttribute("spans") // optional hint; recomputed by the next application
                for (c in row.childElements("c")) {
                    val ref = CellRef.parseCellAttr(c.a("r")!!) ?: continue
                    val nc = s.position(ref.col)
                    if (nc == null) c.detach() else if (nc != ref.col) c.setAttr("r", CellRef(r, nc).a1)
                }
            }
        }
    }

    private fun moveSheetRanges(ws: Worksheet, s: Shift) {
        val root = ws.root
        fun moveRef(e: XElement, attr: String, removeIfGone: XElement? = null) {
            val v = e.a(attr) ?: return
            val parts = v.trim().split(' ').filter { it.isNotEmpty() }
            val moved = parts.mapNotNull { p -> CellRange.parseOrNull(p)?.let { s.range(it) }?.a1 }
            if (moved.isEmpty()) {
                if (removeIfGone != null) removeIfGone.detach() else e.removeAttribute(attr)
            } else {
                val nv = moved.joinToString(" ")
                if (nv != v) e.setAttr(attr, nv)
            }
        }
        root.firstChild("dimension")?.let { moveRef(it, "ref") }
        root.firstChild("mergeCells")?.let { mc ->
            for (m in mc.childElements("mergeCell")) moveRef(m, "ref", removeIfGone = m)
            val n = mc.childElements("mergeCell").size
            if (n == 0) mc.detach() else if (mc.a("count") != null) mc.setAttr("count", n.toString())
        }
        for (cf in root.childElements("conditionalFormatting")) moveRef(cf, "sqref", removeIfGone = cf)
        root.firstChild("dataValidations")?.let { dvs ->
            for (dv in dvs.childElements("dataValidation")) moveRef(dv, "sqref", removeIfGone = dv)
            val n = dvs.childElements("dataValidation").size
            if (n == 0) dvs.detach() else if (dvs.a("count") != null) dvs.setAttr("count", n.toString())
        }
        root.firstChild("autoFilter")?.let { af ->
            val oldRange = af.a("ref")?.let(CellRange::parseOrNull)
            moveRef(af, "ref", removeIfGone = af)
            val newRange = af.a("ref")?.let(CellRange::parseOrNull)
            if (s.axis == Axis.COLUMNS && af.parent != null && oldRange != null && newRange != null) {
                // Filter columns are numbered from the filter's first column.
                for (fc in af.childElements("filterColumn")) {
                    val id = fc.a("colId")?.toIntOrNull() ?: continue
                    val nc = s.position(oldRange.left + id)
                    if (nc == null) fc.detach() else if (nc - newRange.left != id) fc.setAttr("colId", (nc - newRange.left).toString())
                }
            }
        }
        root.firstChild("hyperlinks")?.let { hl ->
            for (h in hl.childElements("hyperlink")) moveRef(h, "ref", removeIfGone = h)
            if (hl.childElements("hyperlink").isEmpty()) hl.detach()
        }
        root.firstChild("ignoredErrors")?.let { ie -> for (e in ie.childElements("ignoredError")) moveRef(e, "sqref", removeIfGone = e) }
        val breaks = if (s.axis == Axis.ROWS) root.firstChild("rowBreaks") else root.firstChild("colBreaks")
        breaks?.let { b ->
            for (brk in b.childElements("brk")) {
                val id = brk.a("id")?.toIntOrNull() ?: continue
                // A break after row/column `id` belongs to position id + 1.
                val np = s.position(id + 1)
                if (np == null) brk.detach() else if (np - 1 != id) brk.setAttr("id", (np - 1).toString())
            }
            val n = b.childElements("brk").size
            if (b.a("count") != null) b.setAttr("count", n.toString())
            if (b.a("manualBreakCount") != null) b.setAttr("manualBreakCount", b.childElements("brk").count { it.a("man") == "1" }.toString())
        }
        // Selections are cosmetic; keep them valid.
        for (view in root.firstChild("sheetViews")?.childElements("sheetView") ?: emptyList()) {
            for (sel in view.childElements("selection")) {
                sel.a("activeCell")?.let { ac ->
                    val r = CellRange.parseOrNull(ac)?.let { s.range(it) }
                    if (r == null) sel.removeAttribute("activeCell") else if (r.first.a1 != ac) sel.setAttr("activeCell", r.first.a1)
                }
                sel.a("sqref")?.let { sq ->
                    val moved = CellRange.parseList(sq).mapNotNull { s.range(it) }
                    if (moved.isEmpty()) sel.removeAttribute("sqref") else {
                        val nv = moved.joinToString(" ") { it.a1 }
                        if (nv != sq) sel.setAttr("sqref", nv)
                    }
                }
            }
        }
    }

    private fun moveColumns(ws: Worksheet, s: Shift) {
        val cols = ws.root.firstChild("cols") ?: return
        for (col in cols.childElements("col")) {
            val min = col.a("min")?.toIntOrNull() ?: continue
            val max = col.a("max")?.toIntOrNull() ?: continue
            val moved = s.span(min, max)
            if (moved == null) { col.detach(); continue }
            if (moved.first != min) col.setAttr("min", moved.first.toString())
            if (moved.second != max) col.setAttr("max", moved.second.toString())
        }
        if (cols.childElements("col").isEmpty()) cols.detach()
    }

    /** The positional change: insert [count] before [at], or delete [at]..[at]+[count]-1. */
    class Shift(val sheet: String, val axis: Axis, val at: Int, val count: Int, val insert: Boolean) {
        private val limit = if (axis == Axis.ROWS) SheetLimits.MAX_ROWS else SheetLimits.MAX_COLS
        private val end = at + count - 1

        /** New position of a single row/column, or null if it is deleted (or pushed off the sheet). */
        fun position(p: Int): Int? = if (insert) {
            if (p >= at) (p + count).takeIf { it <= limit } else p
        } else when {
            p < at -> p
            p > end -> p - count
            else -> null
        }

        /** New extent of a span, or null if it disappears. Spans across an insertion grow. */
        fun span(a: Int, b: Int): Pair<Int, Int>? {
            if (insert) {
                val na = if (a >= at) a + count else a
                val nb = if (b >= at) minOf(limit, b + count) else b
                if (na > limit) return null
                return na to nb
            }
            val na = when { a < at -> a; a > end -> a - count; else -> at }
            val nb = when { b < at -> b; b > end -> b - count; else -> at - 1 }
            return if (nb < na) null else na to nb
        }

        fun range(r: CellRange): CellRange? {
            return if (axis == Axis.ROWS) {
                if (r.top == 1 && r.bottom == SheetLimits.MAX_ROWS) return r
                val (t, b) = span(r.top, r.bottom) ?: return null
                CellRange(t, r.left, b, r.right)
            } else {
                if (r.left == 1 && r.right == SheetLimits.MAX_COLS) return r
                val (l, rr) = span(r.left, r.right) ?: return null
                CellRange(r.top, l, r.bottom, rr)
            }
        }

        fun spec(s: RefSpec): RefSpec? {
            if (axis == Axis.ROWS) {
                if (s.kind == RefSpec.Kind.COLUMNS) return s
                if (s.kind == RefSpec.Kind.CELL) return position(s.r1)?.let { s.copy(r1 = it, r2 = it) }
                val (a, b) = span(s.r1, s.r2) ?: return null
                return s.copy(r1 = a, r2 = b)
            } else {
                if (s.kind == RefSpec.Kind.ROWS) return s
                if (s.kind == RefSpec.Kind.CELL) return position(s.c1)?.let { s.copy(c1 = it, c2 = it) }
                val (a, b) = span(s.c1, s.c2) ?: return null
                return s.copy(c1 = a, c2 = b)
            }
        }

        /** Rewrites references to this sheet; [implicit] means unqualified references point here. */
        fun formula(text: String, implicit: Boolean): String {
            val toks = try { FormulaLexer.tokenize(text) } catch (e: FormulaSyntaxException) { return text }
            val sb = StringBuilder(text.length + 8)
            var changed = false
            for (t in toks) {
                if (t is FToken.Ref && t.spec.sheet2 == null &&
                    ((t.spec.sheet == null && implicit) || t.spec.sheet.equals(sheet, ignoreCase = true))
                ) {
                    val moved = spec(t.spec)
                    if (moved != t.spec) {
                        val prefix = if (t.spec.sheet != null) t.raw.substring(0, t.raw.lastIndexOf('!')) else null
                        sb.append(moved?.toA1(prefix) ?: "#REF!")
                        changed = true
                        continue
                    }
                }
                sb.append(t.raw)
            }
            return if (changed) sb.toString() else text
        }
    }
}
