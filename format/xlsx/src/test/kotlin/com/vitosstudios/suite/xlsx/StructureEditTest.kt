package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import com.vitosstudios.suite.testing.SecondReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureEditTest {
    private val excel = Corpus.file("xlsx/excel-poi-sample.xlsx")
    private val out = Corpus.tempDir("xlsx-structure")

    private fun edit(source: File, name: String, block: (XlsxWorkbook) -> Unit): File {
        val target = File(out, "$name.xlsx")
        XlsxWorkbook.open(source).use { wb -> block(wb); wb.save(target) }
        return target
    }

    @Test
    fun insertAndDeleteRows() {
        val f = edit(excel, "insert-rows") { wb ->
            val ws = wb.sheet(0)
            ws.setCellInput("D1", "=B10*2")
            wb.sheet(2).setCellInput("A1", "=Sheet1!B5+Sheet1!B9")
            ws.insertRows(5, 2)
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("SUM(B1:B11)", ws.cell("B12")!!.formula)
            assertEquals(4995.0, ws.cell("B12")!!.number)
            assertEquals("B12*2", ws.cell("D1")!!.formula)
            assertNull(ws.cell("B5"))
            assertEquals(555.0, ws.cell("B7")!!.number)
            assertEquals("Sheet1!B7+Sheet1!B11", wb.sheet(2).cell("A1")!!.formula)
            assertEquals(1554.0, wb.sheet(2).cell("A1")!!.number)
            assertEquals("A1:D12", ws.root.firstChild("dimension")!!.a("ref"))
        }
        SecondReader.assume()
        val lines = SecondReader.csv(f).lines()
        assertTrue(lines[11].startsWith("at,4995"), lines[11])
        assertTrue(lines[4].replace(",", "").isEmpty(), "inserted row is empty: ${lines[4]}")

        val g = edit(f, "delete-rows") { wb ->
            val ws = wb.sheet(0)
            ws.deleteRows(5, 2)
            ws.deleteRows(1)
        }
        XlsxWorkbook.open(g).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("SUM(B1:B8)", ws.cell("B9")!!.formula)
            assertEquals(4884.0, ws.cell("B9")!!.number)
            assertEquals("Sheet1!B4+Sheet1!B8", wb.sheet(2).cell("A1")!!.formula)
            ws.deleteRows(4)
            assertEquals("#REF!+Sheet1!B7", wb.sheet(2).cell("A1")!!.formula, "reference into a deleted row")
            assertEquals(FValue.REF, wb.evaluateFormula(2, 1, 1))
        }
        assertTrue(SecondReader.csv(g).lines()[8].startsWith("at,4884"), SecondReader.csv(g).lines()[8])
    }

    @Test
    fun insertAndDeleteColumns() {
        val f = edit(excel, "insert-cols") { wb ->
            val ws = wb.sheet(0)
            ws.setColumnWidth(2, 20.0)
            ws.insertColumns(1)
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertNull(ws.cell("A1"))
            assertEquals(111.0, ws.cell("C1")!!.number)
            assertEquals("SUM(C1:C9)", ws.cell("C10")!!.formula)
            assertEquals(20.0, ws.columnWidth(3), "column widths move with their columns")
        }
        assertTrue(SecondReader.csv(f).lines()[9].endsWith(",4995"))
        val g = edit(f, "delete-cols") { wb -> wb.sheet(0).deleteColumns(1, 2) }
        XlsxWorkbook.open(g).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("SUM(A1:A9)", ws.cell("A10")!!.formula)
            assertEquals(20.0, ws.columnWidth(1))
        }
        assertTrue(SecondReader.csv(g).lines()[9].endsWith("4995"))
    }

    @Test
    fun sharedFormulasAreSplitBeforeMoving() {
        val src = Corpus.file("xlsx/excel-poi-shared_formulas.xlsx")
        val f = edit(src, "shared-insert") { wb -> wb.sheet(0).insertRows(10) }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("B9", ws.cell("A9")!!.formula)
            assertEquals("B11", ws.cell("A11")!!.formula)
            assertEquals("B42", ws.cell("A42")!!.formula)
            assertNull(ws.cell("A10"))
            assertEquals(ws.cell("B11")!!.text, ws.cell("A11")!!.text)
        }
        SecondReader.convert(f, "pdf")
    }

    @Test
    fun conditionalFormattingAndMergedRangesFollow() {
        val src = Corpus.file("xlsx/excel-poi-WithConditionalFormatting.xlsx")
        val before = XlsxWorkbook.open(src).use { it.sheet(0).root.childElements("conditionalFormatting").map { e -> e.a("sqref")!! } }
        assertTrue(before.isNotEmpty())
        val f = edit(src, "cf-insert") { wb -> wb.sheet(0).insertRows(1) }
        XlsxWorkbook.open(f).use { wb ->
            val after = wb.sheet(0).root.childElements("conditionalFormatting").map { it.a("sqref")!! }
            val expected = before.map { sq -> CellRange.parseList(sq).joinToString(" ") { CellRange(it.top + 1, it.left, it.bottom + 1, it.right).a1 } }
            assertEquals(expected, after)
        }
        SecondReader.convert(f, "pdf")

        XlsxWorkbook.createNew().use { wb ->
            val ws = wb.sheet(0)
            ws.setCellInput("A1", "x")
            val mc = ws.root.createChild(com.vitosstudios.suite.xml.Namespaces.S, "mergeCells")
            val m = ws.root.createChild(com.vitosstudios.suite.xml.Namespaces.S, "mergeCell")
            m.setAttr("ref", "A2:B4")
            mc.appendChild(m)
            ws.root.insertInOrder(mc, XlsxSchema.WORKSHEET)
            ws.insertRows(3)
            assertEquals(listOf(CellRange.parse("A2:B5")), ws.mergedRanges)
            ws.deleteRows(2, 4)
            assertTrue(ws.mergedRanges.isEmpty())
        }
    }

    @Test
    fun refusedWhereContentCannotFollow() {
        XlsxWorkbook.open(Corpus.file("xlsx/pylib-features.xlsx")).use { wb ->
            assertNotNull(wb.sheet(0).structureEditBlocker)
            val e = assertFailsWith<XlsxEditException> { wb.sheet(0).insertRows(2) }
            assertTrue(e.message!!.contains("charts"), e.message)
            assertNull(wb.sheet(1).structureEditBlocker)
        }
        XlsxWorkbook.open(Corpus.file("xlsx/excel-poi-WithTable.xlsx")).use { wb ->
            assertTrue(wb.sheet(0).structureEditBlocker!!.contains("tables"))
        }
    }

    @Test
    fun undoRestoresOriginal() {
        val target = File(out, "undo.xlsx")
        XlsxWorkbook.open(excel).use { wb ->
            wb.sheet(0).insertRows(3, 4)
            wb.sheet(0).deleteColumns(1)
            while (wb.undo.canUndo) wb.undo.undo()
            assertEquals("SUM(B1:B9)", wb.sheet(0).cell("B10")!!.formula)
            wb.save(target)
        }
        PackageCompare.assertIdentical(excel, target)
    }
}
