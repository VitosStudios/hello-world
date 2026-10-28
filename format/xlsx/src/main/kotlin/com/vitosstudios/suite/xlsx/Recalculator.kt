package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.xml.XElement

/**
 * Recomputes the formulas the engine supports. Cells with other formulas keep their cached
 * values, which dependent formulas then use. Circular references are left untouched.
 */
internal class Recalculator(private val wb: XlsxWorkbook) {
    private data class Key(val sheet: Int, val row: Int, val col: Int)

    private class Compiled(val ast: FNode, val dRow: Int, val dCol: Int, val supported: Boolean)

    private class CircularReference : RuntimeException(null, null, false, false)

    private val sheets: List<Worksheet> = wb.worksheets()
    private val sheetIndexByName = HashMap<String, Int>().also { m -> sheets.forEachIndexed { i, s -> m[s.name.lowercase()] = i } }
    private val memo = HashMap<Key, FValue>()
    private val inProgress = HashSet<Key>()
    private val compiled = HashMap<Key, Compiled?>()
    private val astCache = HashMap<String, FNode?>()
    private val names: Map<String, String> by lazy {
        // Workbook-scoped names only; sheet-scoped names are resolved per sheet below.
        wb.definedNames.filter { it.second == null }.associate { it.first.lowercase() to it.third }
    }
    private val localNames: Map<Pair<Int, String>, String> by lazy {
        wb.definedNames.filter { it.second != null }.associate { (it.second!! to it.first.lowercase()) to it.third }
    }

    private fun parse(text: String): FNode? = astCache.getOrPut(text) {
        try { FormulaParser.parse(text) } catch (e: FormulaSyntaxException) { null }
    }

    private inner class Ctx(val sheet: Int) : EvalContext, SheetBoundsProvider {
        override fun cellValue(sheet: String?, row: Int, col: Int): FValue {
            val s = if (sheet == null) this.sheet else sheetIndexByName[sheet.lowercase()] ?: return FValue.REF
            return valueOf(s, row, col)
        }

        override fun nonBlankCells(sheet: String?, r1: Int, c1: Int, r2: Int, c2: Int): Sequence<FValue> {
            val s = if (sheet == null) this.sheet else sheetIndexByName[sheet.lowercase()] ?: return emptySequence()
            val keys = ArrayList<Pair<Int, Int>>()
            sheets[s].forEachCell(r1, c1, r2, c2) { r, c, _ -> keys += r to c }
            return keys.asSequence().map { (r, c) -> valueOf(s, r, c) }.filter { it !is FValue.Blank }
        }

        override fun resolveName(name: String): FNode? {
            val text = localNames[sheet to name.lowercase()] ?: names[name.lowercase()] ?: return null
            return parse(text)
        }

        override fun sheetExists(sheet: String): Boolean = sheetIndexByName.containsKey(sheet.lowercase())

        override val date1904: Boolean get() = wb.date1904

        override fun lastRow(sheet: String?): Int {
            val s = if (sheet == null) this.sheet else sheetIndexByName[sheet.lowercase()] ?: return 0
            return sheets[s].rowNumbers().lastOrNull() ?: 0
        }
    }

    private val contexts = HashMap<Int, Ctx>()
    private fun ctx(sheet: Int) = contexts.getOrPut(sheet) { Ctx(sheet) }

    private fun compile(sheet: Int, row: Int, col: Int, el: XElement): Compiled? {
        val key = Key(sheet, row, col)
        if (compiled.containsKey(key)) return compiled[key]
        val f = el.firstChild("f")
        val result = if (f == null) null else {
            val type = f.a("t")
            val text = f.textContent
            when {
                type == "array" || type == "dataTable" -> Compiled(FNode.Unsupported(type), 0, 0, false)
                type == "shared" && text.isEmpty() -> {
                    val m = f.a("si")?.let { sheets[sheet].sharedMaster(it) }
                    val ast = m?.let { parse(it.text) }
                    if (m == null || ast == null) Compiled(FNode.Unsupported("shared"), 0, 0, false)
                    else Compiled(ast, row - m.row, col - m.col, FormulaEvaluator.isSupported(ast, ctx(sheet)))
                }
                text.isEmpty() -> null
                else -> {
                    val ast = parse(text)
                    if (ast == null) Compiled(FNode.Unsupported("syntax"), 0, 0, false)
                    else Compiled(ast, 0, 0, FormulaEvaluator.isSupported(ast, ctx(sheet)))
                }
            }
        }
        compiled[key] = result
        return result
    }

    fun isSupported(ws: Worksheet, row: Int, col: Int): Boolean {
        val s = sheets.indexOfFirst { it === ws }.takeIf { it >= 0 } ?: return false
        val el = ws.cellElement(row, col) ?: return false
        return compile(s, row, col, el)?.supported == true
    }

    fun valueOf(sheet: Int, row: Int, col: Int): FValue {
        val key = Key(sheet, row, col)
        memo[key]?.let { return it }
        val ws = sheets[sheet]
        val el = ws.cellElement(row, col) ?: return FValue.Blank
        val c = compile(sheet, row, col, el)
        if (c == null || !c.supported) return ws.cellFValue(el)
        if (!inProgress.add(key)) throw CircularReference()
        val v = try {
            FormulaEvaluator.evaluate(c.ast, ctx(sheet), row, col, c.dRow, c.dCol)
        } finally {
            inProgress.remove(key)
        }
        memo[key] = v
        return v
    }

    /** Evaluates one cell's formula without writing anything; null if unsupported or circular. */
    fun evaluate(ws: Worksheet, row: Int, col: Int): FValue? {
        val s = sheets.indexOfFirst { it === ws }.takeIf { it >= 0 } ?: return null
        if (!isSupported(ws, row, col)) return null
        var result: FValue? = null
        runWithLargeStack {
            result = try { valueOf(s, row, col) } catch (e: CircularReference) { null }
        }
        return result
    }

    /** Evaluates all supported formulas and writes changed results. Returns the number written. */
    fun run(): Int {
        val targets = ArrayList<Triple<Int, Int, Int>>()
        for ((si, ws) in sheets.withIndex()) {
            if (!ws.isEditable) continue
            for ((r, c, el) in ws.allCellsSnapshot()) if (el.firstChild("f") != null) targets += Triple(si, r, c)
        }
        if (targets.isEmpty()) return 0
        val results = HashMap<Key, FValue>()
        runWithLargeStack {
            // Row order keeps typical dependency chains (running totals) shallow.
            for ((s, r, c) in targets) {
                try {
                    val ws = sheets[s]
                    val el = ws.cellElement(r, c) ?: continue
                    val comp = compile(s, r, c, el) ?: continue
                    if (!comp.supported) continue
                    results[Key(s, r, c)] = valueOf(s, r, c)
                } catch (e: CircularReference) {
                    inProgress.clear()
                }
            }
        }
        var written = 0
        for ((key, value) in results) {
            val ws = sheets[key.sheet]
            val el = ws.cellElement(key.row, key.col) ?: continue
            if (sameCached(ws, el, value)) continue
            ws.writeCachedValue(el, value)
            written++
        }
        return written
    }

    private fun sameCached(ws: Worksheet, el: XElement, v: FValue): Boolean {
        val t = el.a("t") ?: "n"
        val raw = el.firstChild("v")?.textContent ?: return false
        return when (v) {
            is FValue.Num -> t == "n" && raw.trim().toDoubleOrNull() == v.v
            is FValue.Str -> t == "str" && raw == v.s
            is FValue.Bool -> t == "b" && raw.trim() == (if (v.b) "1" else "0")
            is FValue.Err -> t == "e" && raw == v.code
            else -> false
        }
    }

    private fun runWithLargeStack(block: () -> Unit) {
        var error: Throwable? = null
        val t = Thread(null, { try { block() } catch (e: Throwable) { error = e } }, "xlsx-recalc", 64L * 1024 * 1024)
        t.start()
        t.join()
        error?.let { throw it }
    }
}
