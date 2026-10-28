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

/**
 * One edit per feature: the saved file re-opens with this reader, only the expected parts
 * changed, and LibreOffice (the second reader) opens it and sees the change.
 */
class XlsxEditTest {
    private val excel = Corpus.file("xlsx/excel-poi-sample.xlsx")
    private val pylib = Corpus.file("xlsx/pylib-features.xlsx")
    private val out = Corpus.tempDir("xlsx-edit")

    private fun edit(source: File, name: String, block: (XlsxWorkbook) -> Unit): File {
        val target = File(out, "$name.xlsx")
        XlsxWorkbook.open(source).use { wb ->
            block(wb)
            wb.save(target)
        }
        return target
    }

    private fun csvLines(file: File): List<String> = SecondReader.csv(file).lines()

    @Test
    fun numberValueRecalculatesDependents() {
        val f = edit(excel, "number") { it.sheet(0).setCellInput("B1", "1000") }
        XlsxWorkbook.open(f).use { wb ->
            assertEquals(1000.0, wb.sheet(0).cell("B1")!!.number)
            assertEquals(5884.0, wb.sheet(0).cell("B10")!!.number, "SUM recalculated")
        }
        PackageCompare.assertOnlyChanged(excel, f, setOf("xl/worksheets/sheet1.xml", "xl/workbook.xml"))
        SecondReader.assume()
        val lines = csvLines(f)
        assertTrue(lines[0].endsWith(",1000"), lines[0])
        assertTrue(lines[9].endsWith(",5884"), lines[9])
    }

    @Test
    fun textValueUsesSharedStrings() {
        val f = edit(excel, "text") { it.sheet(0).setCellInput("C3", "Grüße, \"quoted\"") }
        XlsxWorkbook.open(f).use { assertEquals("Grüße, \"quoted\"", it.sheet(0).cell("C3")!!.text) }
        PackageCompare.assertOnlyChanged(excel, f, setOf("xl/worksheets/sheet1.xml", "xl/workbook.xml", "xl/sharedStrings.xml"))
        SecondReader.assume()
        assertTrue(csvLines(f)[2].endsWith(",\"Grüße, \"\"quoted\"\"\""), csvLines(f)[2])
    }

    @Test
    fun formulaIsStoredAndCalculated() {
        val f = edit(excel, "formula") { wb ->
            wb.sheet(0).setCellInput("C1", "=sum(b1:b3)*2")
            wb.sheet(0).setCellInput("C2", "=concat(A1,\"-\",B2)")
            wb.sheet(0).setCellInput("C4", "=VLOOKUP(444,B1:B9,1,FALSE)")
        }
        XlsxWorkbook.open(f).use { wb ->
            val c1 = wb.sheet(0).cell("C1")!!
            assertEquals("SUM(B1:B3)*2", c1.formula)
            assertEquals(1332.0, c1.number)
            assertEquals("_xlfn.CONCAT(A1,\"-\",B2)", wb.sheet(0).cell("C2")!!.formula)
            assertEquals(444.0, wb.sheet(0).cell("C4")!!.number)
        }
        PackageCompare.assertOnlyChanged(excel, f, setOf("xl/worksheets/sheet1.xml", "xl/workbook.xml"))
        SecondReader.assume()
        val lines = csvLines(f)
        assertTrue(lines[0].endsWith(",1332"), lines[0])
        val a1 = XlsxWorkbook.open(f).use { it.sheet(0).displayText(1, 1) }
        assertTrue(lines[1].endsWith(",$a1-222"), "LibreOffice evaluates _xlfn.CONCAT: ${lines[1]}")
        assertTrue(lines[3].endsWith(",444"), lines[3])
    }

    @Test
    fun replacingAFormulaDropsTheCalculationChain() {
        val f = edit(excel, "formula-removed") { it.sheet(0).setCellInput("B10", "42") }
        XlsxWorkbook.open(f).use { wb ->
            assertNull(wb.sheet(0).cell("B10")!!.formula)
            assertNull(wb.pkg.part("/xl/calcChain.xml"))
        }
        PackageCompare.assertOnlyChanged(
            excel, f,
            setOf("xl/worksheets/sheet1.xml", "xl/workbook.xml", "xl/calcChain.xml", "xl/_rels/workbook.xml.rels", "[Content_Types].xml"),
        )
        SecondReader.assume()
        assertTrue(csvLines(f)[9].endsWith(",42"))
    }

    @Test
    fun stylesAndNumberFormats() {
        val f = edit(excel, "style") { wb ->
            val ws = wb.sheet(0)
            ws.applyStyle(CellRange.parse("A1:B2"), StylePatch(bold = true, italic = true, fillColor = 0xFFFFEB3B.toInt(), fontColor = 0xFFC00000.toInt()))
            ws.applyStyle(CellRange.parse("B1:B10"), StylePatch(numberFormat = NumberFormats.CURRENCY, horizontal = "right"))
            ws.applyStyle(CellRange.parse("A10:B10"), StylePatch(border = BorderPatch(setOf(BorderSide.TOP), "thin"), wrapText = true, underline = true, fontSize = 14.0))
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            val a1 = ws.cell("A1")!!.style
            assertTrue(a1.bold && a1.italic)
            assertEquals(0xFFFFEB3B.toInt(), a1.fillColor)
            assertEquals(0xFFC00000.toInt(), a1.fontColor)
            assertEquals("$111.00", ws.cell("B1")!!.display)
            assertEquals("$4,995.00", ws.cell("B10")!!.display)
            val b10 = ws.cell("B10")!!.style
            assertEquals("thin", b10.borderTop)
            assertTrue(b10.wrapText && b10.underline)
            assertEquals(14.0, b10.fontSize)
            assertEquals("right", b10.horizontal)
            assertTrue(!ws.cell("A3")!!.style.bold, "cells outside the range keep their style")
        }
        PackageCompare.assertOnlyChanged(excel, f, setOf("xl/worksheets/sheet1.xml", "xl/styles.xml"))
        SecondReader.assume()
        // LibreOffice's CSV export ignores currency formats, so look at its HTML rendering.
        val html = SecondReader.convert(f, "html").readText()
        assertTrue(html.contains("$4,995.00") && html.contains("bgcolor=\"#FFEB3B\"") && html.contains("<b>"), "formatting visible in LibreOffice")
    }

    @Test
    fun typedInputGetsFormats() {
        val f = edit(excel, "input-formats") { wb ->
            val ws = wb.sheet(0)
            ws.setCellInput("D1", "12.5%")
            ws.setCellInput("D2", "$1,234.50")
            ws.setCellInput("D3", "2024-03-15")
            ws.setCellInput("D4", "TRUE")
            ws.setCellInput("D5", "'00123")
            ws.setCellInput("D6", "1,234")
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals(0.125, ws.cell("D1")!!.number)
            assertEquals("12.5%", ws.cell("D1")!!.display)
            assertEquals("$1,234.50", ws.cell("D2")!!.display)
            assertEquals(45366.0, ws.cell("D3")!!.number)
            assertEquals("2024-03-15", ws.cell("D3")!!.display)
            assertTrue(ws.cell("D3")!!.isDate)
            assertEquals(true, ws.cell("D4")!!.value)
            assertEquals("00123", ws.cell("D5")!!.text)
            assertEquals("1,234", ws.cell("D6")!!.display)
        }
        SecondReader.assume()
        val lines = csvLines(f)
        assertTrue(lines[0].endsWith(",12.5%"), lines[0])
        assertTrue(lines[2].endsWith(",2024-03-15"), lines[2])
        assertTrue(lines[3].endsWith(",TRUE"), lines[3])
    }

    @Test
    fun addRenameMoveDeleteSheets() {
        val f = edit(pylib, "sheets") { wb ->
            wb.renameSheet(0, "Fruit list")
            val added = wb.addSheet("Added", 1)
            added.setCellInput("A1", "=SUM('Fruit list'!B2:B5)")
            wb.moveSheet(2, 0)
            assertEquals(listOf("Second sheet", "Fruit list", "Added"), wb.sheets.map { it.name })
        }
        XlsxWorkbook.open(f).use { wb ->
            assertEquals(listOf("Second sheet", "Fruit list", "Added"), wb.sheets.map { it.name })
            assertEquals("'Fruit list'!B7*2", wb.sheet(0).cell("A2")!!.formula, "references follow the rename")
            assertEquals(46.0, wb.sheet(2).cell("A1")!!.number)
            assertEquals(92.0, wb.sheet(0).cell("A2")!!.number)
            assertEquals("'Fruit list'!\$A\$1:\$F\$5", wb.definedNames.single().third)
            assertEquals(1, wb.definedNames.single().second, "local name follows its sheet")
            val chart = wb.sheet(1).charts().single()
            assertEquals(listOf(10.0, 4.0, 25.0, 7.0), chart.series.single().values, "chart references follow the rename")
        }
        SecondReader.assume()
        val text = SecondReader.text(f)
        assertTrue(text.contains("table:name=\"Fruit list\"") && text.contains("table:name=\"Added\""))

        val g = edit(f, "sheet-deleted") { wb ->
            wb.deleteSheet(0)
            assertFailsWith<XlsxEditException> { wb.renameSheet(0, "Added") }
        }
        XlsxWorkbook.open(g).use { wb ->
            assertEquals(listOf("Fruit list", "Added"), wb.sheets.map { it.name })
            assertNull(wb.pkg.part("/xl/worksheets/sheet2.xml"), "the deleted sheet's part is gone")
            assertEquals(0, wb.definedNames.single().second)
        }
        assertTrue(SecondReader.text(g).contains("table:name=\"Added\""))
    }

    @Test
    fun cannotDeleteLastVisibleSheet() {
        XlsxWorkbook.open(excel).use { wb ->
            wb.deleteSheet(2)
            wb.deleteSheet(1)
            assertFailsWith<XlsxEditException> { wb.deleteSheet(0) }
            assertFailsWith<XlsxEditException> { wb.addSheet("bad:name") }
        }
    }

    @Test
    fun columnWidthRowHeightAndFreeze() {
        val f = edit(excel, "layout") { wb ->
            val ws = wb.sheet(0)
            ws.setColumnWidth(2, 25.0)
            ws.setRowHeight(3, 40.0)
            ws.setRowHeight(20, 30.0)
            ws.freezePanes(1, 1)
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals(25.0, ws.columnWidth(2))
            assertEquals(40.0, ws.rowHeight(3))
            assertEquals(30.0, ws.rowHeight(20))
            assertEquals(1, ws.frozenRows)
            assertEquals(1, ws.frozenColumns)
        }
        PackageCompare.assertOnlyChanged(excel, f, setOf("xl/worksheets/sheet1.xml"))
        SecondReader.convert(f, "pdf")
        // Splitting an existing column range (E:XFD here) keeps its neighbours.
        val src = Corpus.file("xlsx/excel-poi-NumberFormatTests.xlsx")
        val before = XlsxWorkbook.open(src).use { wb -> listOf(4, 5, 100).map { wb.sheet(1).columnWidth(it) } }
        val g = edit(src, "col-split") { wb -> wb.sheet(1).setColumnWidth(6, 12.0) }
        XlsxWorkbook.open(g).use { wb ->
            val ws = wb.sheet(1)
            assertEquals(listOf(before[0], before[1], 12.0, before[2]), listOf(4, 5, 6, 100).map { ws.columnWidth(it) })
        }
        SecondReader.convert(g, "pdf")
    }

    @Test
    fun sortAndFilter() {
        val f = edit(pylib, "sort") { wb ->
            wb.sheet(0).sortRange(CellRange.parse("A1:C5"), keyColumn = 2, ascending = false, hasHeader = true)
            assertFailsWith<XlsxEditException> { wb.sheet(0).sortRange(CellRange.parse("A1:D5"), 2) }
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals(listOf("Plums", "Apples", "Kiwis", "Pears"), (2..5).map { ws.cell(it, 1)!!.text })
            assertEquals(5.0, ws.cell("D2")!!.number, "formulas recalculate after the sort")
        }
        SecondReader.assume()
        assertTrue(csvLines(f)[1].startsWith("Plums,25,"), csvLines(f)[1])

        val g = edit(pylib, "filter") { wb ->
            val ws = wb.sheet(0)
            assertEquals(listOf("Apples", "Pears", "Plums", "Kiwis"), ws.filterValues(CellRange.parse("A1:F5"), 1))
            ws.setFilter(CellRange.parse("A1:F5"), 1, setOf("Apples", "Plums"))
        }
        XlsxWorkbook.open(g).use { wb ->
            val ws = wb.sheet(0)
            assertEquals(listOf(false, true, false, true), (2..5).map { ws.isRowHidden(it) })
            ws.clearFilter()
            assertEquals(listOf(false, false, false, false), (2..5).map { ws.isRowHidden(it) })
        }
        SecondReader.convert(g, "pdf")
    }

    @Test
    fun createCharts() {
        val f = edit(excel, "chart-new-drawing") { wb ->
            val chart = wb.sheet(0).createChart(ChartType.COLUMN, CellRange.parse("A1:B9"), CellRange.parse("D2:J15"), "Values")
            assertEquals(9, chart.series.single().values.size)
        }
        XlsxWorkbook.open(f).use { wb ->
            val chart = wb.sheet(0).charts().single()
            assertEquals(ChartType.COLUMN, chart.type)
            assertEquals("Values", chart.title)
            assertEquals(111.0, chart.series.single().values.first())
        }
        PackageCompare.assertOnlyChanged(
            excel, f, setOf("xl/worksheets/sheet1.xml", "[Content_Types].xml", "xl/worksheets/_rels/sheet1.xml.rels"),
        ) { it.startsWith("xl/charts/") || it.startsWith("xl/drawings/") }
        SecondReader.convert(f, "pdf")

        val g = edit(pylib, "chart-existing-drawing") { wb ->
            wb.sheet(0).createChart(ChartType.PIE, CellRange.parse("A1:B5"), CellRange.parse("H20:N35"))
            wb.sheet(0).createChart(ChartType.LINE, CellRange.parse("A1:C5"), CellRange.parse("A20:F35"))
            wb.sheet(0).createChart(ChartType.BAR, CellRange.parse("A1:B5"), CellRange.parse("A40:F55"))
        }
        XlsxWorkbook.open(g).use { wb ->
            val types = wb.sheet(0).charts().map { it.type }
            assertTrue(types.size == 4 && types.containsAll(listOf(ChartType.PIE, ChartType.LINE, ChartType.BAR)), "$types")
            assertEquals(2, wb.sheet(0).charts().first { it.type == ChartType.LINE }.series.size)
        }
        PackageCompare.assertOnlyChanged(pylib, g, setOf("xl/drawings/drawing1.xml", "xl/drawings/_rels/drawing1.xml.rels", "[Content_Types].xml")) {
            it.startsWith("xl/charts/")
        }
        SecondReader.convert(g, "pdf")
    }

    @Test
    fun csvImportAndExport() {
        val csv = "Name;Amount;When\r\n\"Smith; John\";1,234.5;2024-01-31\r\nDoe;=1+1;\"multi\nline\"\r\n"
        val target = File(out, "from-csv.xlsx")
        XlsxWorkbook.fromCsv(csv).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("Smith; John", ws.cell("A2")!!.text)
            assertEquals(1234.5, ws.cell("B2")!!.number)
            assertEquals("2024-01-31", ws.cell("C2")!!.display)
            assertEquals("=1+1", ws.cell("B3")!!.text, "CSV never creates formulas")
            assertEquals("multi\nline", ws.cell("C3")!!.text)
            wb.save(target)
        }
        XlsxWorkbook.open(target).use { wb ->
            val exported = wb.exportCsv(0)
            assertEquals("Name,Amount,When\r\nSmith; John,\"1,234.50\",2024-01-31\r\nDoe,=1+1,\"multi\nline\"\r\n", exported)
            assertEquals("Name,Amount,When\r\nSmith; John,1234.5,45322\r\nDoe,=1+1,\"multi\nline\"\r\n", wb.exportCsv(0, displayed = false))
        }
        SecondReader.assume()
        assertTrue(csvLines(target)[1].startsWith("Smith; John,"), csvLines(target)[1])

        val g = edit(excel, "csv-sheet") { wb -> wb.importCsvAsSheet("a,b\n1,2\n", "Imported") }
        XlsxWorkbook.open(g).use { wb -> assertEquals(2.0, wb.sheet("Imported")!!.cell("B2")!!.number) }
        assertTrue(SecondReader.text(g).contains("table:name=\"Imported\""))
    }

    @Test
    fun newWorkbook() {
        val target = File(out, "new.xlsx")
        XlsxWorkbook.createNew().use { wb ->
            val ws = wb.sheet(0)
            ws.setCellInput("A1", "Item")
            ws.setCellInput("B1", "Price")
            ws.setCellInput("A2", "Tea")
            ws.setCellInput("B2", "3.5")
            ws.setCellInput("B3", "=B2*2")
            ws.applyStyle(CellRange.parse("A1:B1"), StylePatch(bold = true))
            wb.addSheet()
            wb.save(target)
        }
        XlsxWorkbook.open(target).use { wb ->
            assertEquals(7.0, wb.sheet(0).cell("B3")!!.number)
            assertTrue(wb.sheet(0).cell("A1")!!.style.bold)
            assertEquals(listOf("Sheet1", "Sheet2"), wb.sheets.map { it.name })
        }
        SecondReader.assume()
        assertEquals("Item,Price", csvLines(target)[0])
        assertEquals(",7", csvLines(target)[2])
    }

    @Test
    fun workbookWithoutStylesGetsOneWhenFormatted() {
        val target = File(out, "no-styles.xlsx")
        XlsxWorkbook.createNew().use { wb ->
            val main = wb.pkg.mainDocument()!!
            val rels = wb.pkg.relationships(main)
            val rel = rels.firstOfType(com.vitosstudios.suite.opc.RelTypes.STYLES)!!
            rels.remove(rel.id)
            wb.pkg.removePart(rels.resolve(rel))
            wb.sheet(0).setCellInput("A1", "plain")
            wb.sheet(0).setCellInput("A2", "12%")
            wb.save(target)
        }
        XlsxWorkbook.open(target).use { wb -> assertEquals("12%", wb.sheet(0).cell("A2")!!.display) }
        SecondReader.assume()
        assertEquals("12%", csvLines(target)[1])
    }

    @Test
    fun sharedFormulaMasterCanBeEdited() {
        val src = Corpus.file("xlsx/excel-poi-shared_formulas.xlsx")
        val f = edit(src, "shared-master") { wb ->
            val ws = wb.sheet(0)
            assertEquals("B3", ws.cell("A3")!!.formula, "dependent formula is translated")
            ws.setCellInput("A2", "replaced")
        }
        XlsxWorkbook.open(f).use { wb ->
            val ws = wb.sheet(0)
            assertEquals("replaced", ws.cell("A2")!!.text)
            assertEquals("B3", ws.cell("A3")!!.formula)
            assertEquals("B41", ws.cell("A41")!!.formula)
            assertEquals(ws.cell("B5")!!.text, ws.cell("A5")!!.text)
        }
        SecondReader.convert(f, "pdf")
    }

    @Test
    fun undoRestoresTheOriginalBytes() {
        val target = File(out, "undone.xlsx")
        XlsxWorkbook.open(pylib).use { wb ->
            val ws = wb.sheet(0)
            ws.setCellInput("B2", "99")
            ws.applyStyle(CellRange.parse("A1:C3"), StylePatch(bold = false, fillColor = 0xFF00FF00.toInt()))
            ws.setColumnWidth(3, 30.0)
            ws.freezePanes(0, 0)
            ws.createChart(ChartType.PIE, CellRange.parse("A1:B5"), CellRange.parse("H20:N35"))
            wb.addSheet("Temp").setCellInput("A1", "new text")
            wb.renameSheet(0, "Renamed")
            ws.sortRange(CellRange.parse("A1:C5"), 2, hasHeader = true)
            ws.setFilter(CellRange.parse("A1:F5"), 1, setOf("Pears"))
            wb.deleteSheet(1)
            while (wb.undo.canUndo) wb.undo.undo()
            assertEquals(10.0, ws.cell("B2")!!.number)
            wb.save(target)
        }
        PackageCompare.assertIdentical(pylib, target)
    }

    @Test
    fun largeWorkbookStaysFast() {
        val src = Corpus.file("large/pylib-10000-rows.xlsx")
        val target = File(out, "large.xlsx")
        val start = System.nanoTime()
        XlsxWorkbook.open(src).use { wb ->
            val ws = wb.sheet(0)
            assertTrue(ws.readAllCells() >= 50_000)
            ws.setCellInput("C2", "1000")
            val sum = ws.cell(10002, 3)!!.number!!
            assertTrue(sum > 1000.0)
            wb.save(target)
        }
        val seconds = (System.nanoTime() - start) / 1e9
        println("10,000-row workbook: open, read, edit, recalc, save in %.2f s".format(seconds))
        assertTrue(seconds < 5.0, "took $seconds s")
        XlsxWorkbook.open(target).use { assertEquals(1000.0, it.sheet(0).cell("C2")!!.number) }
    }

    @Test
    fun compatibilityReportCountsContent() {
        XlsxWorkbook.open(pylib).use { wb ->
            val r = wb.compatibility()
            assertEquals("XLSX", r.format)
            assertTrue(r.items.first { it.feature == "Formulas" }.count >= 13)
            assertEquals(1, r.items.first { it.feature == "Merged cells" }.count)
            assertEquals(1, r.items.first { it.feature == "Conditional formatting" }.count)
            assertEquals(1, r.items.first { it.feature == "Charts" }.count)
        }
        XlsxWorkbook.open(Corpus.file("xlsx/excel-poi-StructuredReferences.xlsx")).use { wb ->
            val r = wb.compatibility()
            assertTrue(r.items.first { it.feature == "Other formulas" }.count > 0, "structured references are kept, not evaluated")
            assertTrue(r.items.first { it.feature == "Tables" }.count > 0)
        }
    }

    @Test
    fun arrayFormulaCellsAreProtected() {
        XlsxWorkbook.createNew().use { wb ->
            val ws = wb.sheet(0)
            // Build an array formula by hand, as another application would have saved it.
            ws.setCellInput("A1", "=1")
            val el = ws.cellElement(1, 1)!!
            el.firstChild("f")!!.setAttr("t", "array")
            el.firstChild("f")!!.setAttr("ref", "A1:A3")
            assertFailsWith<XlsxEditException> { ws.setCellInput("A2", "5") }
            assertNotNull(ws.cell("A1"))
        }
    }
}
