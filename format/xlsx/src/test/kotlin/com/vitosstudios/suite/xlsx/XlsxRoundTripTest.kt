package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.testing.Corpus
import com.vitosstudios.suite.testing.PackageCompare
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Open every corpus workbook through the XLSX layer, read everything the editor shows (cells,
 * formats, charts, pictures, compatibility report), save without edits and require every part
 * to be byte-identical.
 */
class XlsxRoundTripTest {
    @Test
    fun openReadSaveIsByteIdentical() {
        val out = Corpus.tempDir("xlsx-roundtrip")
        var cells = 0
        for (f in Corpus.files("xlsx")) {
            val target = File(out, f.name)
            XlsxWorkbook.open(f).use { wb ->
                for (s in wb.sheets) {
                    if (s.isChartsheet || s.partName == null) continue
                    val ws = wb.sheet(s.index)
                    cells += ws.readAllCells()
                    ws.usedRange()
                    ws.mergedRanges
                    ws.conditionalFormats
                    ws.hyperlinks
                    ws.tables
                    ws.frozenRows
                    ws.charts()
                    ws.pictures()
                    ws.usedRange()?.let { r -> for (c in r.left..minOf(r.right, 30)) ws.columnWidth(c) }
                }
                wb.compatibility()
                wb.save(target)
            }
            PackageCompare.assertIdentical(f, target)
        }
        assertTrue(cells > 50_000, "read $cells cells")
    }

    @Test
    fun chartsInCorpusAreRecognised() {
        XlsxWorkbook.open(Corpus.file("xlsx/excel-poi-WithChart.xlsx")).use { wb ->
            val charts = wb.worksheets().flatMap { it.charts() }
            assertTrue(charts.isNotEmpty(), "WithChart.xlsx has a chart")
            assertTrue(charts.all { it.series.isNotEmpty() })
        }
        XlsxWorkbook.open(Corpus.file("xlsx/pylib-features.xlsx")).use { wb ->
            val chart = wb.sheet(0).charts().single()
            assertTrue(chart.type == ChartType.COLUMN || chart.type == ChartType.BAR, "type ${chart.type}")
            assertTrue(chart.series.single().values == listOf(10.0, 4.0, 25.0, 7.0), "values ${chart.series}")
            assertTrue(chart.series.single().categories == listOf("Apples", "Pears", "Plums", "Kiwis"))
        }
    }
}
