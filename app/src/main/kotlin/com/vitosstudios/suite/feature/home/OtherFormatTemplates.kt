package com.vitosstudios.suite.feature.home

import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.pptx.PptxPresentation
import com.vitosstudios.suite.xlsx.XlsxWorkbook

/** Spreadsheet and presentation templates, built by the format layers themselves. */
internal object OtherFormatTemplates {
    fun list(): List<NewFileTemplate> = listOf(
        NewFileTemplate("xlsx-blank", "Blank spreadsheet", "XLSX workbook", FileKind.XLSX, "Spreadsheet") { f ->
            XlsxWorkbook.createNew().use { it.save(f) }
        },
        NewFileTemplate("xlsx-budget", "Budget", "XLSX with formulas", FileKind.XLSX, "Budget") { f ->
            XlsxWorkbook.createNew("Budget").use { wb ->
                val ws = wb.sheet(0)
                val rows = listOf(
                    listOf("Item", "Planned", "Actual", "Difference"),
                    listOf("Rent", "1200", "1200", "=C2-B2"),
                    listOf("Food", "450", "", "=C3-B3"),
                    listOf("Transport", "120", "", "=C4-B4"),
                    listOf("Other", "200", "", "=C5-B5"),
                    listOf("Total", "=SUM(B2:B5)", "=SUM(C2:C5)", "=C6-B6"),
                )
                rows.forEachIndexed { r, cols -> cols.forEachIndexed { c, v -> if (v.isNotEmpty()) ws.setCellInput(r + 1, c + 1, v) } }
                ws.applyStyle(com.vitosstudios.suite.xlsx.CellRange(1, 1, 1, 4), com.vitosstudios.suite.xlsx.StylePatch(bold = true))
                ws.applyStyle(com.vitosstudios.suite.xlsx.CellRange(2, 2, 6, 4), com.vitosstudios.suite.xlsx.StylePatch(numberFormat = com.vitosstudios.suite.xlsx.NumberFormats.CURRENCY))
                ws.setColumnWidth(1, 16.0)
                ws.freezePanes(1, 0)
                wb.save(f)
            }
        },
        NewFileTemplate("pptx-blank", "Blank presentation", "PPTX slides", FileKind.PPTX, "Presentation") { f ->
            PptxPresentation.createNew().use { it.save(f) }
        },
    )
}
