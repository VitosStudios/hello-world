package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.common.UndoManager
import com.vitosstudios.suite.opc.CompatibilityItem
import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.OfficeDocument
import com.vitosstudios.suite.opc.OpcException
import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.PartNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.opc.SupportLevel
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XDocument
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XText
import com.vitosstudios.suite.xml.XmlEscaping
import com.vitosstudios.suite.xml.XmlParser
import java.io.File

enum class SheetState { VISIBLE, HIDDEN, VERY_HIDDEN }

data class SheetInfo(
    val index: Int,
    val name: String,
    val sheetId: Int,
    val state: SheetState,
    /** Chart sheets have no cells; they are shown as placeholders and kept unchanged. */
    val isChartsheet: Boolean,
    /** Absolute part name of the sheet, or null if the relationship is broken. */
    val partName: String?,
)

/**
 * An XLSX workbook opened for viewing and editing. Every edit is one undoable step on
 * [undo]; saving writes the same format and verifies the result by re-reading it.
 */
class XlsxWorkbook private constructor(override val pkg: OpcPackage) : OfficeDocument {
    val undo: UndoManager get() = pkg.undo

    internal val workbookPart: Part = pkg.mainDocument() ?: throw OpcException("Workbook part is missing")
    private val wbDoc: XDocument = workbookPart.xmlOrNull() ?: throw OpcException("The workbook part cannot be read")
    private val wbRoot: XElement get() = wbDoc.root

    internal val sharedStrings = SharedStrings(pkg, workbookPart)
    internal val styles = Styles(pkg, workbookPart)
    private val sheetCache = HashMap<String, Worksheet>()

    init {
        val ct = workbookPart.contentType ?: ""
        if (!ct.contains("spreadsheetml") && !ct.contains("ms-excel")) throw OpcException("Not a spreadsheet: $ct")
    }

    /** True for workbooks using the 1904 date system. */
    val date1904: Boolean
        get() = wbRoot.firstChild("workbookPr")?.a("date1904").let { it == "1" || it == "true" }

    // ------------------------------------------------------------------------------------------
    // Sheets

    private var sheetsVersion = -1L
    private var sheetsCache: List<SheetInfo> = emptyList()

    val sheets: List<SheetInfo>
        get() {
            if (sheetsVersion == wbDoc.modCount + relsVersion()) return sheetsCache
            val rels = pkg.relationships(workbookPart)
            sheetsCache = sheetElements().mapIndexed { i, e ->
                val rid = e.attr(Namespaces.R, "id")
                val rel = rid?.let { rels.byId(it) }
                SheetInfo(
                    index = i,
                    name = e.a("name") ?: "Sheet${i + 1}",
                    sheetId = e.a("sheetId")?.toIntOrNull() ?: (i + 1),
                    state = when (e.a("state")) { "hidden" -> SheetState.HIDDEN; "veryHidden" -> SheetState.VERY_HIDDEN; else -> SheetState.VISIBLE },
                    isChartsheet = rel?.type == RelTypes.CHARTSHEET,
                    partName = rel?.let { rels.resolve(it) },
                )
            }
            sheetsVersion = wbDoc.modCount + relsVersion()
            return sheetsCache
        }

    private fun relsVersion(): Long = pkg.part(PartNames.relsPartFor(workbookPart.name))?.xmlOrNull()?.modCount?.times(1_000_003L) ?: 0L

    private fun sheetsElement(): XElement = wbRoot.firstChild("sheets") ?: throw OpcException("Workbook has no sheets element")
    private fun sheetElements(): List<XElement> = sheetsElement().childElements("sheet")

    /** The worksheet at [index]; throws for chart sheets. */
    fun sheet(index: Int): Worksheet {
        val info = sheets.getOrNull(index) ?: throw IndexOutOfBoundsException("No sheet $index")
        if (info.isChartsheet) throw XlsxEditException("\"${info.name}\" is a chart sheet")
        val partName = info.partName ?: throw XlsxEditException("Sheet \"${info.name}\" has no part")
        val part = pkg.part(partName) ?: throw XlsxEditException("Sheet part $partName is missing")
        val key = PartNames.key(part.name)
        sheetCache[key]?.let { if (it.part === part) return it }
        return Worksheet(this, part).also { sheetCache[key] = it }
    }

    fun sheet(name: String): Worksheet? = sheets.firstOrNull { it.name.equals(name, true) && !it.isChartsheet }?.let { sheet(it.index) }

    /** Worksheets only (chart sheets skipped). */
    fun worksheets(): List<Worksheet> = sheets.filter { !it.isChartsheet && it.partName != null }.map { sheet(it.index) }

    internal fun <T> edit(label: String, block: () -> T): T {
        if (!isEditable) throw XlsxEditException("This workbook cannot be edited safely")
        return pkg.undo.transaction(label, block)
    }

    val isEditable: Boolean get() = styles.isEditable

    fun validateSheetName(name: String, exceptIndex: Int = -1) {
        if (name.isBlank() || name.length > 31) throw XlsxEditException("Sheet names have 1 to 31 characters")
        if (name.any { it in "[]:*?/\\" }) throw XlsxEditException("Sheet names cannot contain [ ] : * ? / \\")
        if (name.startsWith("'") || name.endsWith("'")) throw XlsxEditException("Sheet names cannot start or end with an apostrophe")
        if (name.equals("History", true)) throw XlsxEditException("\"History\" is a reserved sheet name")
        if (sheets.any { it.index != exceptIndex && it.name.equals(name, true) }) throw XlsxEditException("A sheet named \"$name\" already exists")
    }

    private fun uniqueSheetName(): String {
        var i = sheets.size + 1
        while (sheets.any { it.name.equals("Sheet$i", true) }) i++
        return "Sheet$i"
    }

    /** Adds an empty worksheet at [index] and returns it. */
    fun addSheet(name: String? = null, index: Int = sheets.size): Worksheet {
        val sheetName = name ?: uniqueSheetName()
        validateSheetName(sheetName)
        require(index in 0..sheets.size) { "Invalid position $index" }
        return edit("Add sheet") {
            val dir = PartNames.directory(workbookPart.name)
            val partName = pkg.uniquePartName("${dir}worksheets/sheet", "xml")
            pkg.addXmlPart(partName, ContentTypeNames.SML_WORKSHEET, emptySheetDocument())
            val rel = pkg.relationships(workbookPart).addTo(RelTypes.WORKSHEET, partName)
            val sheetsEl = sheetsElement()
            val e = sheetsEl.createChild(Namespaces.S, "sheet")
            e.setAttr("name", sheetName)
            e.setAttr("sheetId", ((sheets.maxOfOrNull { it.sheetId } ?: 0) + 1).toString())
            e.setAttr(Namespaces.R, "id", rel.id)
            sheetsEl.appendChild(e)
            val newIndex = sheets.size - 1
            if (index != newIndex) moveSheetInternal(newIndex, index)
            sheet(index)
        }
    }

    /** Renames a sheet and updates every formula, defined name and chart reference to it. */
    fun renameSheet(index: Int, newName: String) {
        val info = sheets.getOrNull(index) ?: throw IndexOutOfBoundsException("No sheet $index")
        if (info.name == newName) return
        validateSheetName(newName, exceptIndex = index)
        edit("Rename sheet") {
            val old = info.name
            sheetElements()[index].setAttr("name", newName)
            rewriteAllFormulas(touchesFormulas = false) { FormulaRewrite.renameSheet(it, old, newName) }
            Charts.rewriteChartReferences(this) { FormulaRewrite.renameSheet(it, old, newName) }
        }
    }

    /** Deletes a sheet. The last visible sheet cannot be deleted. */
    fun deleteSheet(index: Int) {
        val info = sheets.getOrNull(index) ?: throw IndexOutOfBoundsException("No sheet $index")
        if (info.state == SheetState.VISIBLE && sheets.count { it.state == SheetState.VISIBLE } <= 1) {
            throw XlsxEditException("A workbook must keep at least one visible sheet")
        }
        edit("Delete sheet") {
            val el = sheetElements()[index]
            val rid = el.attr(Namespaces.R, "id")
            el.detach()
            if (rid != null) pkg.relationships(workbookPart).remove(rid)
            info.partName?.let { if (!pkg.isReferenced(it)) pkg.removePartRecursively(it) }
            info.partName?.let { sheetCache.remove(PartNames.key(it)) }
            // Defined names: drop the deleted sheet's local names, shift the others.
            definedNamesElement()?.let { dn ->
                for (n in dn.childElements("definedName")) {
                    val local = n.a("localSheetId")?.toIntOrNull()
                    when {
                        local == index -> n.detach()
                        local != null && local > index -> n.setAttr("localSheetId", (local - 1).toString())
                    }
                }
                if (dn.childElements("definedName").isEmpty()) dn.detach()
            }
            rewriteAllFormulas(touchesFormulas = true) { FormulaRewrite.invalidateSheet(it, info.name) }
            fixBookViews { if (it == index) 0 else if (it > index) it - 1 else it }
            formulaRemoved()
            afterValueEdit()
        }
    }

    /** Moves a sheet to a new position. */
    fun moveSheet(from: Int, to: Int) {
        require(from in sheets.indices && to in sheets.indices)
        if (from == to) return
        edit("Move sheet") { moveSheetInternal(from, to) }
    }

    private fun moveSheetInternal(from: Int, to: Int) {
        val sheetsEl = sheetsElement()
        val list = sheetElements()
        val el = list[from]
        el.detach()
        val remaining = sheetElements()
        if (to >= remaining.size) sheetsEl.insertAfter(el, remaining.last()) else sheetsEl.insertBefore(el, remaining[to])
        val order = list.indices.toMutableList()
        order.removeAt(from)
        order.add(to, from)
        val newIndexOf = IntArray(list.size)
        for ((newIdx, oldIdx) in order.withIndex()) newIndexOf[oldIdx] = newIdx
        definedNamesElement()?.let { dn ->
            for (n in dn.childElements("definedName")) {
                val local = n.a("localSheetId")?.toIntOrNull() ?: continue
                if (local in newIndexOf.indices && newIndexOf[local] != local) n.setAttr("localSheetId", newIndexOf[local].toString())
            }
        }
        fixBookViews { if (it in newIndexOf.indices) newIndexOf[it] else it }
        formulaRemoved() // the calculation chain refers to sheets by position
    }

    private fun fixBookViews(map: (Int) -> Int) {
        val count = sheetElements().size
        for (v in wbRoot.firstChild("bookViews")?.childElements("workbookView") ?: emptyList()) {
            for (attr in listOf("activeTab", "firstSheet")) {
                val cur = v.a(attr)?.toIntOrNull() ?: continue
                val n = map(cur).coerceIn(0, maxOf(0, count - 1))
                if (n != cur) v.setAttr(attr, n.toString())
            }
        }
    }

    private fun definedNamesElement(): XElement? = wbRoot.firstChild("definedNames")

    internal fun definedNameElements(): List<XElement> = definedNamesElement()?.childElements("definedName") ?: emptyList()

    /** Defined names as (name, localSheetId, formula text). */
    val definedNames: List<Triple<String, Int?, String>>
        get() = definedNamesElement()?.childElements("definedName")?.mapNotNull { n ->
            Triple(n.a("name") ?: return@mapNotNull null, n.a("localSheetId")?.toIntOrNull(), n.textContent)
        } ?: emptyList()

    internal fun ensureFilterDatabaseName(ws: Worksheet, range: CellRange) {
        val idx = ws.index
        val text = FormulaLexer.quoteSheet(ws.name) + "!" + range.absoluteA1
        val dn = definedNamesElement()
        val existing = dn?.childElements("definedName")?.firstOrNull { it.a("name") == "_xlnm._FilterDatabase" && it.a("localSheetId")?.toIntOrNull() == idx }
        if (existing != null) {
            if (existing.textContent != text) existing.setText(text)
            return
        }
        val names = dn ?: wbRoot.createChild(Namespaces.S, "definedNames").also { wbRoot.insertInOrder(it, XlsxSchema.WORKBOOK) }
        val n = names.createChild(Namespaces.S, "definedName")
        n.setAttr("name", "_xlnm._FilterDatabase")
        n.setAttr("localSheetId", idx.toString())
        n.setAttr("hidden", "1")
        n.appendChild(XText(XmlEscaping.escapeText(text)))
        names.appendChild(n)
    }

    /**
     * Applies [f] to the text of every cell formula, defined name, conditional-format and
     * data-validation formula. Only formulas that change are written.
     */
    private fun rewriteAllFormulas(touchesFormulas: Boolean, f: (String) -> String) {
        for (n in definedNamesElement()?.childElements("definedName") ?: emptyList()) {
            val t = n.textContent
            val r = f(t)
            if (r != t) n.setText(r)
        }
        for (ws in worksheets()) {
            if (!ws.isEditable) continue
            var changed = false
            for ((_, _, el) in ws.allCellsSnapshot()) {
                val fe = el.firstChild("f") ?: continue
                val t = fe.textContent
                if (t.isEmpty()) continue
                val r = f(t)
                if (r != t) { fe.setText(r); changed = true }
            }
            for (fe in ws.root.descendants().filter { it.localName == "formula" || it.localName == "formula1" || it.localName == "formula2" }.toList()) {
                val t = fe.textContent
                val r = f(t)
                if (r != t) fe.setText(r)
            }
            if (changed && touchesFormulas) formulaRemoved()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Calculation

    private var calcFlagged = false

    /** Called by every value edit: recalculates and asks the next application to recalculate too. */
    internal fun afterValueEdit() {
        recalculate()
        markFullCalcOnLoad()
    }

    private fun markFullCalcOnLoad() {
        val calc = wbRoot.firstChild("calcPr") ?: wbRoot.createChild(Namespaces.S, "calcPr").also { wbRoot.insertInOrder(it, XlsxSchema.WORKBOOK) }
        if (calc.a("fullCalcOnLoad") != "1") calc.setAttr("fullCalcOnLoad", "1")
        calcFlagged = true
    }

    /**
     * The calculation chain lists formula cells; it must not name cells that no longer hold a
     * formula. It is optional, so it is dropped when formulas are removed.
     */
    internal fun formulaRemoved() {
        val rels = pkg.relationships(workbookPart)
        val rel = rels.firstOfType(RelTypes.CALC_CHAIN) ?: return
        val target = rels.resolve(rel)
        rels.remove(rel.id)
        pkg.removePart(target)
    }

    /** Recomputes every formula the engine supports and stores changed results. */
    fun recalculate(): Int = Recalculator(this).run()

    /**
     * Evaluates the formula in a cell with the built-in engine without changing anything.
     * Returns null if the cell has no formula the engine supports (or it is circular).
     */
    fun evaluateFormula(sheetIndex: Int, row: Int, col: Int): FValue? = Recalculator(this).evaluate(sheet(sheetIndex), row, col)

    /** Whether the cell's formula is computed by the built-in engine (otherwise its cached value is kept). */
    fun isFormulaSupported(sheetIndex: Int, row: Int, col: Int): Boolean = Recalculator(this).isSupported(sheet(sheetIndex), row, col)

    // ------------------------------------------------------------------------------------------
    // CSV

    /** The sheet as CSV ([displayed] = formatted text, otherwise raw values). */
    fun exportCsv(sheetIndex: Int, delimiter: Char = ',', displayed: Boolean = true): String = Csv.export(sheet(sheetIndex), delimiter, displayed)

    /** Adds a sheet filled from CSV text; fields are typed like typed input but never become formulas. */
    fun importCsvAsSheet(csv: String, name: String? = null, delimiter: Char? = null): Worksheet =
        edit("Import CSV") {
            val ws = addSheet(name ?: uniqueSheetName())
            Csv.fill(ws, csv, delimiter)
            afterValueEdit()
            ws
        }

    // ------------------------------------------------------------------------------------------
    // Compatibility

    override fun compatibility(): CompatibilityReport {
        val items = ArrayList<CompatibilityItem>()
        var cells = 0
        var formulas = 0
        var supported = 0
        var merged = 0
        var cf = 0
        var dv = 0
        var tables = 0
        var comments = 0
        var hyperlinks = 0
        var charts = 0
        var unsupportedCharts = 0
        var pictures = 0
        var shapes = 0
        var sparklines = 0
        var pivots = 0
        var arrays = 0
        val unparsed = ArrayList<String>()
        val probe = Recalculator(this)
        for (ws in worksheets()) {
            if (!ws.isEditable) unparsed += ws.part.name
            for ((r, c, el) in ws.allCellsSnapshot()) {
                cells++
                val f = el.firstChild("f") ?: continue
                formulas++
                if (f.a("t") == "array") arrays++
                if (probe.isSupported(ws, r, c)) supported++
            }
            merged += ws.mergedRanges.size
            cf += ws.root.childElements("conditionalFormatting").size + (ws.root.firstChild("extLst")?.descendants()?.count { it.localName == "conditionalFormatting" } ?: 0)
            dv += ws.dataValidationCount
            tables += ws.tables.size
            comments += ws.commentCount
            hyperlinks += ws.hyperlinks.size
            val ch = ws.charts()
            charts += ch.size
            unsupportedCharts += ch.count { it.type == ChartType.UNSUPPORTED }
            pictures += ws.pictures().size
            shapes += Charts.otherDrawingObjects(ws)
            if (ws.hasSparklines) sparklines++
            pivots += pkg.relationships(ws.part).ofType(RelTypes.PIVOT_TABLE).size
        }
        val chartSheets = sheets.count { it.isChartsheet }
        val vba = pkg.parts.any { it.name.endsWith("vbaProject.bin", true) }
        val externalLinks = pkg.parts.count { it.name.contains("/externalLinks/", true) && it.name.endsWith(".xml", true) }
        val slicers = pkg.parts.count { it.name.contains("/slicers/", true) || it.name.contains("/timelines/", true) }
        val numFmts = styles.xfCount()
        items += CompatibilityItem("Cells and values", SupportLevel.EDITABLE, cells, "Numbers, text, dates, booleans and errors can be viewed and edited.")
        items += CompatibilityItem("Formulas", SupportLevel.EDITABLE, supported, "Calculated in the app: ${FormulaEvaluator.FUNCTIONS.sorted().joinToString(", ")}.")
        items += CompatibilityItem("Other formulas", SupportLevel.READ_ONLY, formulas - supported, "Kept as written; their last calculated value is shown and the next spreadsheet application recalculates them.")
        items += CompatibilityItem("Array formulas", SupportLevel.READ_ONLY, arrays, "Kept unchanged; cells inside an array cannot be edited one by one.")
        items += CompatibilityItem("Cell formats", SupportLevel.EDITABLE, numFmts, "Number formats, fonts, fills, borders, alignment and wrap.")
        items += CompatibilityItem("Merged cells", SupportLevel.READ_ONLY, merged, "Shown merged; merging and unmerging are not available.")
        items += CompatibilityItem("Conditional formatting", SupportLevel.PRESERVED, cf, "Rules are listed and kept unchanged but not evaluated.")
        items += CompatibilityItem("Data validation", SupportLevel.PRESERVED, dv, "Kept unchanged; entries are not validated in the app.")
        items += CompatibilityItem("Tables", SupportLevel.READ_ONLY, tables, "Cells are editable; the table definition is kept unchanged.")
        items += CompatibilityItem("Pivot tables", SupportLevel.PRESERVED, pivots, "Kept unchanged and not refreshed.")
        items += CompatibilityItem("Charts", SupportLevel.READ_ONLY, charts - unsupportedCharts, "Bar, column, line and pie charts are drawn; new charts can be created.")
        items += CompatibilityItem("Other chart types", SupportLevel.READ_ONLY, unsupportedCharts + chartSheets, "Shown as a placeholder and kept unchanged.")
        items += CompatibilityItem("Pictures", SupportLevel.READ_ONLY, pictures, "Shown at their position and kept unchanged.")
        items += CompatibilityItem("Shapes and other drawing objects", SupportLevel.PRESERVED, shapes, "Kept unchanged.")
        items += CompatibilityItem("Comments and notes", SupportLevel.PRESERVED, comments, "Kept unchanged.")
        items += CompatibilityItem("Hyperlinks", SupportLevel.READ_ONLY, hyperlinks, "Shown and kept; editing links is not available.")
        items += CompatibilityItem("Sparklines", SupportLevel.PRESERVED, sparklines, "Kept unchanged, not drawn.")
        items += CompatibilityItem("Slicers and timelines", SupportLevel.PRESERVED, slicers, "Kept unchanged.")
        items += CompatibilityItem("External links", SupportLevel.PRESERVED, externalLinks, "Kept unchanged; never refreshed (no network access).")
        items += CompatibilityItem("Macros (VBA)", SupportLevel.PRESERVED, if (vba) 1 else 0, "Kept byte-identical; never run.")
        items += CompatibilityItem("Chart sheets", SupportLevel.READ_ONLY, chartSheets, "Listed as sheets and kept unchanged.")
        return CompatibilityReport("XLSX", items, unparsed)
    }

    // ------------------------------------------------------------------------------------------
    // Saving

    override fun save(target: File) {
        pkg.save(target) { tmp -> verifyReadable(tmp) }
    }

    companion object {
        fun open(file: File): XlsxWorkbook {
            val pkg = OpcPackage.open(file)
            return try { XlsxWorkbook(pkg) } catch (e: Exception) { pkg.close(); throw e }
        }

        fun open(pkg: OpcPackage): XlsxWorkbook = XlsxWorkbook(pkg)

        /** Opens [file] and reads every cell of every sheet; throws if anything is unreadable. */
        fun verifyReadable(file: File) {
            open(file).use { wb ->
                for (s in wb.sheets) {
                    if (s.isChartsheet || s.partName == null) continue
                    wb.sheet(s.index).readAllCells()
                }
            }
        }

        /** A new workbook with one empty sheet. */
        fun createNew(sheetName: String = "Sheet1"): XlsxWorkbook {
            val ct = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<Types xmlns="${Namespaces.CONTENT_TYPES}">""" +
                """<Default Extension="rels" ContentType="${ContentTypeNames.RELATIONSHIPS}"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="${ContentTypeNames.SML_WORKSHEET}"/>""" +
                """<Override PartName="/xl/styles.xml" ContentType="${ContentTypeNames.SML_STYLES}"/>""" +
                """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
                """</Types>"""
            val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<Relationships xmlns="${Namespaces.PKG_RELATIONSHIPS}">""" +
                """<Relationship Id="rId1" Type="${RelTypes.OFFICE_DOCUMENT}" Target="xl/workbook.xml"/>""" +
                """<Relationship Id="rId2" Type="${RelTypes.EXTENDED_PROPERTIES}" Target="docProps/app.xml"/>""" +
                """</Relationships>"""
            val app = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<Properties xmlns="${Namespaces.EXTENDED_PROPERTIES}"><Application>ArtCraft Mobile</Application></Properties>"""
            val wb = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<workbook xmlns="${Namespaces.S}" xmlns:r="${Namespaces.R}">""" +
                """<bookViews><workbookView activeTab="0"/></bookViews>""" +
                """<sheets><sheet name="${XmlEscaping.escapeAttribute(sheetName)}" sheetId="1" r:id="rId1"/></sheets>""" +
                """<calcPr calcId="191029"/></workbook>"""
            val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<Relationships xmlns="${Namespaces.PKG_RELATIONSHIPS}">""" +
                """<Relationship Id="rId1" Type="${RelTypes.WORKSHEET}" Target="worksheets/sheet1.xml"/>""" +
                """<Relationship Id="rId2" Type="${RelTypes.STYLES}" Target="styles.xml"/>""" +
                """</Relationships>"""
            val entries = linkedMapOf(
                "[Content_Types].xml" to ct.toByteArray(),
                "_rels/.rels" to rels.toByteArray(),
                "docProps/app.xml" to app.toByteArray(),
                "xl/workbook.xml" to wb.toByteArray(),
                "xl/_rels/workbook.xml.rels" to wbRels.toByteArray(),
                "xl/worksheets/sheet1.xml" to emptySheetXml().toByteArray(),
                "xl/styles.xml" to Styles.minimalXml().toByteArray(),
            )
            return XlsxWorkbook(OpcPackage.fromEntries(entries))
        }

        /** A new workbook containing [csv] in its first sheet. */
        fun fromCsv(csv: String, sheetName: String = "Sheet1", delimiter: Char? = null): XlsxWorkbook {
            val wb = createNew(sheetName)
            Csv.fill(wb.sheet(0), csv, delimiter)
            wb.recalculate()
            wb.undo.clear()
            return wb
        }

        internal fun emptySheetXml(): String =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<worksheet xmlns="${Namespaces.S}" xmlns:r="${Namespaces.R}">""" +
                """<dimension ref="A1"/><sheetViews><sheetView workbookViewId="0"/></sheetViews>""" +
                """<sheetFormatPr defaultRowHeight="15"/><sheetData/>""" +
                """<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>"""

        internal fun emptySheetDocument(): XDocument = XmlParser.parse(emptySheetXml())
    }
}
