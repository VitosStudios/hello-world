package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.testing.Corpus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class FormulaEngineTest {
    /** A sheet backed by a map; "Other" is a second sheet. */
    private class MapCtx(val cells: Map<String, Any?>, val other: Map<String, Any?> = emptyMap()) : EvalContext {
        private fun toValue(v: Any?): FValue = when (v) {
            null -> FValue.Blank
            is Double -> FValue.Num(v)
            is Int -> FValue.Num(v.toDouble())
            is String -> FValue.Str(v)
            is Boolean -> FValue.Bool(v)
            is FValue -> v
            else -> error("bad $v")
        }
        private fun map(sheet: String?) = if (sheet == null) cells else if (sheet.equals("Other", true)) other else emptyMap()
        override fun cellValue(sheet: String?, row: Int, col: Int): FValue = toValue(map(sheet)[CellRef(row, col).a1])
        override fun nonBlankCells(sheet: String?, r1: Int, c1: Int, r2: Int, c2: Int): Sequence<FValue> =
            map(sheet).entries.map { CellRef.parse(it.key) to it.value }.filter { (ref, _) -> ref.row in r1..r2 && ref.col in c1..c2 }
                .sortedBy { it.first }.map { toValue(it.second) }.filter { it !is FValue.Blank }.asSequence()
        override fun resolveName(name: String): FNode? = if (name.equals("Rate", true)) FormulaParser.parse("\$B\$1") else null
        override fun sheetExists(sheet: String) = sheet.equals("Other", true)
    }

    private val ctx = MapCtx(
        mapOf(
            "A1" to 10, "A2" to 20, "A3" to 30, "A4" to "text", "A5" to true,
            "B1" to 0.5, "B2" to "5", "C1" to "Apples", "C2" to "Pears", "C3" to "Plums",
            "D1" to 1.5, "D2" to 2.5, "D3" to 3.5,
            "E1" to 1, "E2" to 5, "E3" to 10, "F1" to "low", "F2" to "mid", "F3" to "high",
        ),
        mapOf("A1" to 7),
    )

    private fun ev(formula: String, row: Int = 10, col: Int = 10): FValue = FormulaEvaluator.evaluate(FormulaParser.parse(formula), ctx, row, col)
    private fun num(formula: String) = (ev(formula) as? FValue.Num)?.v ?: fail("$formula -> ${ev(formula)}")

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(7.0, num("1+2*3"))
        assertEquals(9.0, num("(1+2)*3"))
        assertEquals(4.0, num("-2^2"))
        assertEquals(64.0, num("2^3^2"))
        assertEquals(0.5, num("50%"))
        assertEquals(15.0, num("A1+B2"), "text that looks like a number is coerced")
        assertEquals(FValue.VALUE, ev("A1+A4"))
        assertEquals(FValue.DIV0, ev("1/0"))
        assertEquals(FValue.Str("10x"), ev("A1&\"x\""))
        assertEquals(FValue.Bool(true), ev("A1<A2"))
        assertEquals(FValue.Bool(true), ev("\"abc\"=\"ABC\""))
        assertEquals(FValue.Bool(true), ev("1<\"a\""), "numbers sort before text")
        assertEquals(17.0, num("Other!A1+A1"))
        assertEquals(FValue.REF, ev("Missing!A1"))
        assertEquals(5.0, num("Rate*10"))
    }

    @Test
    fun aggregates() {
        assertEquals(60.0, num("SUM(A1:A5)"), "text and booleans in ranges are ignored")
        assertEquals(61.0, num("SUM(A1:A3,TRUE)"), "direct booleans count")
        assertEquals(20.0, num("AVERAGE(A1:A5)"))
        assertEquals(10.0, num("MIN(A1:A5)"))
        assertEquals(30.0, num("MAX(A1:A5)"))
        assertEquals(3.0, num("COUNT(A1:A5)"))
        assertEquals(5.0, num("COUNTA(A1:A5)"))
        assertEquals(0.0, num("MAX(C1:C3)"))
        assertEquals(FValue.DIV0, ev("AVERAGE(C1:C3)"))
        assertEquals(FValue.VALUE, ev("SUM(\"abc\")"))
        assertEquals(6000.0, num("PRODUCT(A1:A3)"))
        assertEquals(60.0, num("SUM(A:A)"))
    }

    @Test
    fun logicalAndText() {
        assertEquals(FValue.Str("big"), ev("IF(A3>20,\"big\",\"small\")"))
        assertEquals(FValue.Bool(false), ev("IF(A1>20,\"big\")"))
        assertEquals(FValue.Str("ok"), ev("IFERROR(1/0,\"ok\")"))
        assertEquals(FValue.Bool(true), ev("AND(A1>5,A2>5)"))
        assertEquals(FValue.Bool(true), ev("NOT(OR(A1>50,A2>50))"))
        assertEquals(FValue.Str("Apples & Pears"), ev("CONCAT(C1,\" & \",C2)"))
        assertEquals(FValue.Str("ApplesPearsPlums"), ev("_xlfn.CONCAT(C1:C3)"))
        assertEquals(FValue.Str("Apples-10"), ev("CONCATENATE(C1,\"-\",A1)"))
        assertEquals(FValue.Num(6.0), ev("LEN(C1)"))
        assertEquals(FValue.Str("APP"), ev("UPPER(LEFT(C1,3))"))
        assertEquals(FValue.Str("a b"), ev("TRIM(\"  a   b \")"))
        assertEquals(FValue.Str("ear"), ev("MID(C2,2,3)"))
        assertEquals(FValue.Str("1,234.50"), ev("TEXT(1234.5,\"#,##0.00\")"))
    }

    @Test
    fun rounding() {
        assertEquals(2.5, num("ROUND(2.49975,2)"))
        assertEquals(-3.0, num("ROUND(-2.5,0)"), "half away from zero")
        assertEquals(1200.0, num("ROUND(1234,-2)"))
        assertEquals(2.35, num("ROUNDUP(2.341,2)"))
        assertEquals(2.34, num("ROUNDDOWN(2.349,2)"))
        assertEquals(1.0, num("MOD(-3,2)"))
        assertEquals(-4.0, num("INT(-3.5)"))
    }

    @Test
    fun vlookup() {
        assertEquals(2.5, num("VLOOKUP(2.5,D1:D3,1,FALSE)"))
        assertEquals(FValue.Str("mid"), ev("VLOOKUP(7,E1:F3,2,TRUE)"))
        assertEquals(FValue.Str("high"), ev("VLOOKUP(99,E1:F3,2)"))
        assertEquals(FValue.NA, ev("VLOOKUP(0,E1:F3,2,TRUE)"))
        assertEquals(FValue.Str("mid"), ev("VLOOKUP(5,E1:F3,2,FALSE)"))
        assertEquals(FValue.NA, ev("VLOOKUP(6,E1:F3,2,FALSE)"))
        assertEquals(FValue.REF, ev("VLOOKUP(5,E1:F3,3,FALSE)"))
        assertEquals(2.5, num("VLOOKUP(\"pea*\",C1:D3,2,FALSE)"), "wildcards in exact match")
    }

    @Test
    fun supportDetection() {
        assertTrue(FormulaEvaluator.isSupported(FormulaParser.parse("SUM(A1:B2)*2")))
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("NPV(0.1,A1:A3)")))
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("SUM(Table1[Amount])")))
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("SUM({1,2,3})")))
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("SUM([1]Sheet1!A1)")))
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("SUM(A1:B2 B1:C3)")), "intersection")
        assertFalse(FormulaEvaluator.isSupported(FormulaParser.parse("SUM(Sheet1:Sheet3!A1)")), "3D reference")
        assertTrue(FormulaEvaluator.isSupported(FormulaParser.parse("_xlfn.CONCAT(A1,B1)")))
        assertFailsWith<FormulaSyntaxException> { FormulaParser.parse("SUM(A1") }
        assertFailsWith<FormulaSyntaxException> { FormulaParser.parse("1+") }
    }

    @Test
    fun rewrites() {
        assertEquals("B2*C3+\$A\$1+SUM(B:B)", FormulaRewrite.shift("A1*B2+\$A\$1+SUM(A:A)", 1, 1))
        assertEquals("#REF!+1", FormulaRewrite.shift("A1+1", -1, 0))
        assertEquals("'New name'!A1+Other!B2", FormulaRewrite.renameSheet("Data!A1+Other!B2", "data", "New name"))
        assertEquals("'It''s'!A1", FormulaRewrite.renameSheet("'Old one'!A1", "Old one", "It's"))
        assertEquals("#REF!+Other!B2", FormulaRewrite.invalidateSheet("Data!A1+Other!B2", "Data"))
        assertEquals("SUM(A1:B2)+_xlfn.CONCAT(A1)", FormulaRewrite.normalizeInput("=sum(a1:b2)+concat(a1)"))
        assertEquals("\"keep a1 text\"&A1", FormulaRewrite.normalizeInput("\"keep a1 text\"&a1"))
    }

    /**
     * Every formula in the corpus that the engine supports must reproduce the value cached by the
     * application that saved the file (Excel or LibreOffice), allowing for float noise.
     */
    @Test
    fun corpusFormulasMatchCachedValues() {
        var checked = 0
        val mismatches = ArrayList<String>()
        for (file in Corpus.files("xlsx")) {
            XlsxWorkbook.open(file).use { wb ->
                for (s in wb.sheets) {
                    if (s.isChartsheet || s.partName == null) continue
                    val ws = wb.sheet(s.index)
                    for (r in ws.rowNumbers()) for (c in ws.columnsInRow(r)) {
                        val cell = ws.cell(r, c) ?: continue
                        if (cell.formula == null || cell.type == CellType.BLANK) continue
                        // The producing application could not evaluate it either.
                        if (cell.type == CellType.ERROR && cell.value == "#NAME?") continue
                        val v = wb.evaluateFormula(s.index, r, c) ?: continue
                        checked++
                        val ok = when (cell.type) {
                            CellType.NUMBER -> v is FValue.Num && abs(v.v - cell.number!!) <= 1e-9 * maxOf(1.0, abs(cell.number!!))
                            CellType.STRING -> v is FValue.Str && v.s == cell.text
                            CellType.BOOLEAN -> v is FValue.Bool && v.b == cell.value
                            CellType.ERROR -> v is FValue.Err && v.code == cell.value
                            else -> true
                        }
                        if (!ok) mismatches += "${file.name}!${s.name}!${cell.ref} =${cell.formula}: cached ${cell.value} engine $v"
                    }
                }
            }
        }
        println("Checked $checked supported formulas against cached values; ${mismatches.size} mismatches")
        assertTrue(checked > 100, "Expected many supported formulas, got $checked")
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n"))
    }
}
