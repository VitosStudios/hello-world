package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.xml.XElement

/** Child sequences from the SpreadsheetML schema, used to insert new elements in a valid order. */
internal object XlsxSchema {
    val WORKSHEET = listOf(
        "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData", "sheetCalcPr", "sheetProtection",
        "protectedRanges", "scenarios", "autoFilter", "sortState", "dataConsolidate", "customSheetViews", "mergeCells",
        "phoneticPr", "conditionalFormatting", "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup",
        "headerFooter", "rowBreaks", "colBreaks", "customProperties", "cellWatches", "ignoredErrors", "smartTags", "drawing",
        "legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts",
        "extLst",
    )
    val WORKBOOK = listOf(
        "fileVersion", "fileSharing", "workbookPr", "workbookProtection", "bookViews", "sheets", "functionGroups",
        "externalReferences", "definedNames", "calcPr", "oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr",
        "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst",
    )
    val STYLESHEET = listOf("numFmts", "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst")
    val FONT = listOf("b", "i", "strike", "condense", "extend", "outline", "shadow", "u", "vertAlign", "sz", "color", "name", "family", "charset", "scheme")
    val XF = listOf("alignment", "protection", "extLst")
    val BORDER = listOf("start", "left", "end", "right", "top", "bottom", "diagonal", "vertical", "horizontal")
    val PATTERN_FILL = listOf("fgColor", "bgColor")
    val CELL = listOf("f", "v", "is", "extLst")
    val SHEET_VIEW = listOf("pane", "selection", "pivotSelection", "extLst")
    val AUTO_FILTER = listOf("filterColumn", "sortState", "extLst")
    val FILTER_COLUMN = listOf("filters", "top10", "customFilters", "dynamicFilter", "colorFilter", "iconFilter", "extLst")
}

/** Small helpers for hot loops: unprefixed attribute access by raw name avoids namespace work. */
internal fun XElement.a(name: String): String? = attribute(name)?.value

internal fun XElement.firstChild(local: String): XElement? {
    for (c in children) if (c is XElement && c.localName == local) return c
    return null
}

internal fun XElement.childElements(local: String): List<XElement> {
    val out = ArrayList<XElement>()
    for (c in children) if (c is XElement && c.localName == local) out += c
    return out
}

/** Writes a number the way spreadsheet files store it (shortest round-trip form, no ".0"). */
internal fun formatXmlNumber(d: Double): String {
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    val s = d.toString()
    return if (s.contains('E')) s.replace("E", "E") else s
}
