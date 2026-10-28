package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.PartNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlEscaping
import com.vitosstudios.suite.xml.XmlParser

enum class ChartType { BAR, COLUMN, LINE, PIE, UNSUPPORTED }

/** Where a drawing object sits: cells are 1-based; offsets and sizes are in EMU. */
data class DrawingAnchor(
    val from: CellRef,
    val to: CellRef?,
    val fromColOffsetEmu: Long = 0,
    val fromRowOffsetEmu: Long = 0,
    val toColOffsetEmu: Long = 0,
    val toRowOffsetEmu: Long = 0,
    /** Size for one-cell anchors. */
    val widthEmu: Long? = null,
    val heightEmu: Long? = null,
)

data class ChartSeries(val name: String?, val categories: List<String>, val values: List<Double?>)

data class ChartInfo(
    val type: ChartType,
    /** The chart element name for unsupported types, e.g. "scatterChart". */
    val typeName: String,
    val title: String?,
    val series: List<ChartSeries>,
    val anchor: DrawingAnchor?,
    val partName: String,
)

data class PictureInfo(val anchor: DrawingAnchor?, val imagePartName: String?, val name: String?)

/** Reading, drawing data for, and creating charts on worksheets. Existing charts are never modified. */
internal object Charts {
    private const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"

    fun drawingPart(ws: Worksheet): Part? {
        val rels = ws.workbook.pkg.relationships(ws.part)
        val id = ws.root.firstChild("drawing")?.attr(Namespaces.R, "id") ?: return null
        return rels.targetPart(id)
    }

    private fun anchors(drawing: XElement): List<XElement> =
        drawing.elements().filter { it.localName == "twoCellAnchor" || it.localName == "oneCellAnchor" || it.localName == "absoluteAnchor" }.toList()

    private fun anchorOf(a: XElement): DrawingAnchor? {
        fun cell(e: XElement?): Triple<CellRef, Long, Long>? {
            if (e == null) return null
            val col = e.element(Namespaces.XDR, "col")?.textContent?.trim()?.toIntOrNull() ?: return null
            val row = e.element(Namespaces.XDR, "row")?.textContent?.trim()?.toIntOrNull() ?: return null
            val co = e.element(Namespaces.XDR, "colOff")?.textContent?.trim()?.toLongOrNull() ?: 0
            val ro = e.element(Namespaces.XDR, "rowOff")?.textContent?.trim()?.toLongOrNull() ?: 0
            return Triple(CellRef(row + 1, col + 1), co, ro)
        }
        val from = cell(a.element(Namespaces.XDR, "from")) ?: return null
        val to = cell(a.element(Namespaces.XDR, "to"))
        val ext = a.element(Namespaces.XDR, "ext")
        return DrawingAnchor(
            from.first, to?.first, from.second, from.third, to?.second ?: 0, to?.third ?: 0,
            ext?.attr("cx")?.toLongOrNull(), ext?.attr("cy")?.toLongOrNull(),
        )
    }

    fun chartsOf(ws: Worksheet): List<ChartInfo> {
        val drawing = drawingPart(ws) ?: return emptyList()
        val root = drawing.xmlOrNull()?.root ?: return emptyList()
        val rels = ws.workbook.pkg.relationships(drawing)
        val out = ArrayList<ChartInfo>()
        for (a in anchors(root)) {
            val chartRef = a.descendants(Namespaces.C, "chart").firstOrNull() ?: continue
            val id = chartRef.attr(Namespaces.R, "id") ?: continue
            val part = rels.targetPart(id) ?: continue
            out += readChart(ws.workbook, part, anchorOf(a))
        }
        return out
    }

    fun picturesOf(ws: Worksheet): List<PictureInfo> {
        val drawing = drawingPart(ws) ?: return emptyList()
        val root = drawing.xmlOrNull()?.root ?: return emptyList()
        val rels = ws.workbook.pkg.relationships(drawing)
        return anchors(root).mapNotNull { a ->
            val pic = a.element(Namespaces.XDR, "pic") ?: return@mapNotNull null
            val blip = pic.descendants(Namespaces.A, "blip").firstOrNull()
            val embed = blip?.attr(Namespaces.R, "embed")
            val name = pic.descendants(Namespaces.XDR, "cNvPr").firstOrNull()?.attr("name")
            PictureInfo(anchorOf(a), embed?.let { rels.targetPart(it)?.name }, name)
        }
    }

    /** Shapes, groups, connectors and non-chart frames (kept, not drawn). */
    fun otherDrawingObjects(ws: Worksheet): Int {
        val drawing = drawingPart(ws) ?: return 0
        val root = drawing.xmlOrNull()?.root ?: return 0
        return anchors(root).count { a ->
            a.element(Namespaces.XDR, "pic") == null && a.descendants(Namespaces.C, "chart").none()
        }
    }

    fun readChart(wb: XlsxWorkbook, part: Part, anchor: DrawingAnchor?): ChartInfo {
        val root = part.xmlOrNull()?.root ?: return ChartInfo(ChartType.UNSUPPORTED, "unreadable", null, emptyList(), anchor, part.name)
        val chart = root.element(Namespaces.C, "chart") ?: return ChartInfo(ChartType.UNSUPPORTED, "empty", null, emptyList(), anchor, part.name)
        val title = chart.element(Namespaces.C, "title")?.let { t ->
            t.descendants(Namespaces.A, "t").joinToString("") { it.textContent }.ifEmpty {
                t.descendants(Namespaces.C, "v").firstOrNull()?.textContent
            }
        }
        val plot = chart.element(Namespaces.C, "plotArea")
        val types = plot?.elements()?.filter { it.localName.endsWith("Chart") && it.ns == Namespaces.C }?.toList() ?: emptyList()
        val first = types.firstOrNull() ?: return ChartInfo(ChartType.UNSUPPORTED, "none", title, emptyList(), anchor, part.name)
        val type = if (types.size > 1) ChartType.UNSUPPORTED else when (first.localName) {
            "barChart", "bar3DChart" -> if (first.element(Namespaces.C, "barDir")?.attr("val") == "bar") ChartType.BAR else ChartType.COLUMN
            "lineChart", "line3DChart" -> ChartType.LINE
            "pieChart", "pie3DChart" -> ChartType.PIE
            else -> ChartType.UNSUPPORTED
        }
        val series = first.elements(Namespaces.C, "ser").map { ser -> readSeries(wb, ser) }.toList()
        return ChartInfo(type, first.localName, title, series, anchor, part.name)
    }

    private fun readSeries(wb: XlsxWorkbook, ser: XElement): ChartSeries {
        val tx = ser.element(Namespaces.C, "tx")
        val name = tx?.let { t ->
            val ref = t.descendants(Namespaces.C, "f").firstOrNull()?.textContent
            ref?.let { liveValues(wb, it)?.firstOrNull()?.let(FormulaEvaluator::text) }
                ?: t.descendants(Namespaces.C, "v").firstOrNull()?.textContent
        }
        val cat = ser.element(Namespaces.C, "cat") ?: ser.element(Namespaces.C, "xVal")
        val categories = cat?.let { c ->
            val ref = c.descendants(Namespaces.C, "f").firstOrNull()?.textContent
            ref?.let { liveValues(wb, it)?.map { v -> if (v is FValue.Blank) "" else displayOf(v) } } ?: cachePoints(c).map { it ?: "" }
        } ?: emptyList()
        val valEl = ser.element(Namespaces.C, "val") ?: ser.element(Namespaces.C, "yVal")
        val values = valEl?.let { v ->
            val ref = v.descendants(Namespaces.C, "f").firstOrNull()?.textContent
            ref?.let { liveValues(wb, it)?.map { x -> (FormulaEvaluator.num(x) as? FValue.Num)?.v.takeIf { x !is FValue.Blank } } }
                ?: cachePoints(v).map { it?.toDoubleOrNull() }
        } ?: emptyList()
        return ChartSeries(name, categories, values)
    }

    private fun displayOf(v: FValue): String = when (v) {
        is FValue.Num -> NumberFormat.generalNumber(v.v)
        else -> FormulaEvaluator.text(v)
    }

    /** Values of a chart reference ("Sheet1!$A$1:$A$4") read from the sheet, or null if unresolvable. */
    private fun liveValues(wb: XlsxWorkbook, ref: String): List<FValue>? {
        val tok = try { FormulaLexer.tokenize(ref.trim().removePrefix("(").removeSuffix(")")) } catch (e: Exception) { return null }
        val r = tok.singleOrNull() as? FToken.Ref ?: return null
        val sheetName = r.spec.sheet ?: return null
        val ws = wb.sheet(sheetName) ?: return null
        val spec = r.spec
        if ((spec.r2 - spec.r1 + 1).toLong() * (spec.c2 - spec.c1 + 1) > 10_000) return null
        val out = ArrayList<FValue>()
        for (row in spec.r1..spec.r2) for (col in spec.c1..spec.c2) {
            out += ws.cellElement(row, col)?.let { ws.cellFValue(it) } ?: FValue.Blank
        }
        return out
    }

    private fun cachePoints(e: XElement): List<String?> {
        val cache = e.descendants().firstOrNull { it.localName == "strCache" || it.localName == "numCache" || it.localName == "strLit" || it.localName == "numLit" }
            ?: return emptyList()
        val count = cache.element(Namespaces.C, "ptCount")?.attr("val")?.toIntOrNull()
        val pts = cache.elements(Namespaces.C, "pt").toList()
        val n = count ?: ((pts.maxOfOrNull { it.attr("idx")?.toIntOrNull() ?: 0 } ?: -1) + 1)
        val arr = arrayOfNulls<String>(n.coerceAtMost(100_000))
        for (p in pts) {
            val idx = p.attr("idx")?.toIntOrNull() ?: continue
            if (idx in arr.indices) arr[idx] = p.element(Namespaces.C, "v")?.textContent
        }
        return arr.toList()
    }

    /** Rewrites the `c:f` references of every chart in the workbook. */
    fun rewriteChartReferences(wb: XlsxWorkbook, f: (String) -> String) {
        for (p in wb.pkg.contentParts) {
            if (p.contentType != ContentTypeNames.CHART) continue
            val root = p.xmlOrNull()?.root ?: continue
            for (e in root.descendants(Namespaces.C, "f").toList()) {
                val t = e.textContent
                val r = f(t)
                if (r != t) e.setText(r)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Creating

    fun create(ws: Worksheet, type: ChartType, data: CellRange, anchor: CellRange, title: String?): ChartInfo {
        require(type != ChartType.UNSUPPORTED) { "Choose a bar, column, line or pie chart" }
        val pkg = ws.workbook.pkg
        val sheetRef = FormulaLexer.quoteSheet(ws.name)
        // Layout: first column = categories (if there are 2+ columns), first row = names if it holds text.
        val hasCategories = data.colCount > 1
        val firstSeriesCol = if (hasCategories) data.left + 1 else data.left
        val headerRow = (firstSeriesCol..data.right).any { c -> ws.cell(data.top, c)?.type == CellType.STRING } && data.rowCount > 1
        val firstDataRow = if (headerRow) data.top + 1 else data.top
        val catRange = if (hasCategories) CellRange(firstDataRow, data.left, data.bottom, data.left) else null
        val categories = (firstDataRow..data.bottom).map { r -> if (catRange != null) ws.displayText(r, data.left) else (r - firstDataRow + 1).toString() }

        val drawingRoot = ensureDrawing(ws)
        val drawingPart = drawingPart(ws)!!
        val dir = PartNames.directory(ws.workbook.workbookPart.name)
        val chartName = pkg.uniquePartName("${dir}charts/chart", "xml")
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append("""<c:chartSpace xmlns:c="${Namespaces.C}" xmlns:a="${Namespaces.A}" xmlns:r="${Namespaces.R}">""")
        sb.append("""<c:roundedCorners val="0"/><c:chart>""")
        if (!title.isNullOrBlank()) {
            sb.append("""<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>""").append(XmlEscaping.escapeText(title))
            sb.append("""</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>""")
        }
        sb.append("""<c:autoTitleDeleted val="${if (title.isNullOrBlank()) 1 else 0}"/><c:plotArea><c:layout/>""")
        val chartEl = when (type) {
            ChartType.LINE -> "lineChart"
            ChartType.PIE -> "pieChart"
            else -> "barChart"
        }
        sb.append("<c:$chartEl>")
        when (type) {
            ChartType.BAR, ChartType.COLUMN -> sb.append("""<c:barDir val="${if (type == ChartType.BAR) "bar" else "col"}"/><c:grouping val="clustered"/><c:varyColors val="0"/>""")
            ChartType.LINE -> sb.append("""<c:grouping val="standard"/><c:varyColors val="0"/>""")
            else -> sb.append("""<c:varyColors val="1"/>""")
        }
        val seriesOut = ArrayList<ChartSeries>()
        val seriesCols = if (type == ChartType.PIE) listOf(firstSeriesCol) else (firstSeriesCol..data.right).toList()
        for ((i, col) in seriesCols.withIndex()) {
            val name = if (headerRow) ws.displayText(data.top, col) else "Series ${i + 1}"
            val values = (firstDataRow..data.bottom).map { r -> ws.cell(r, col)?.number }
            seriesOut += ChartSeries(name, categories, values)
            sb.append("""<c:ser><c:idx val="$i"/><c:order val="$i"/>""")
            if (headerRow) {
                sb.append("<c:tx><c:strRef><c:f>").append(XmlEscaping.escapeText(sheetRef + "!" + CellRange(data.top, col, data.top, col).absoluteA1))
                sb.append("""</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>""").append(XmlEscaping.escapeText(name))
                sb.append("</c:v></c:pt></c:strCache></c:strRef></c:tx>")
            }
            if (type == ChartType.BAR || type == ChartType.COLUMN) sb.append("""<c:invertIfNegative val="0"/>""")
            if (type == ChartType.LINE) sb.append("""<c:marker><c:symbol val="none"/></c:marker>""")
            if (catRange != null) {
                sb.append("<c:cat><c:strRef><c:f>").append(XmlEscaping.escapeText(sheetRef + "!" + catRange.absoluteA1))
                sb.append("""</c:f><c:strCache><c:ptCount val="${categories.size}"/>""")
                for ((k, c) in categories.withIndex()) sb.append("""<c:pt idx="$k"><c:v>""").append(XmlEscaping.escapeText(c)).append("</c:v></c:pt>")
                sb.append("</c:strCache></c:strRef></c:cat>")
            }
            val valRange = CellRange(firstDataRow, col, data.bottom, col)
            sb.append("<c:val><c:numRef><c:f>").append(XmlEscaping.escapeText(sheetRef + "!" + valRange.absoluteA1))
            sb.append("""</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${values.size}"/>""")
            for ((k, v) in values.withIndex()) if (v != null) sb.append("""<c:pt idx="$k"><c:v>""").append(formatXmlNumber(v)).append("</c:v></c:pt>")
            sb.append("</c:numCache></c:numRef></c:val>")
            if (type == ChartType.LINE) sb.append("""<c:smooth val="0"/>""")
            sb.append("</c:ser>")
        }
        when (type) {
            ChartType.BAR, ChartType.COLUMN -> sb.append("""<c:gapWidth val="150"/><c:axId val="500000001"/><c:axId val="500000002"/>""")
            ChartType.LINE -> sb.append("""<c:marker val="1"/><c:axId val="500000001"/><c:axId val="500000002"/>""")
            else -> sb.append("""<c:firstSliceAng val="0"/>""")
        }
        sb.append("</c:$chartEl>")
        if (type != ChartType.PIE) {
            val catPos = if (type == ChartType.BAR) "l" else "b"
            val valPos = if (type == ChartType.BAR) "b" else "l"
            sb.append("""<c:catAx><c:axId val="500000001"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/>""")
            sb.append("""<c:axPos val="$catPos"/><c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/>""")
            sb.append("""<c:tickLblPos val="nextTo"/><c:crossAx val="500000002"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>""")
            sb.append("""<c:valAx><c:axId val="500000002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/>""")
            sb.append("""<c:axPos val="$valPos"/><c:majorGridlines/><c:numFmt formatCode="General" sourceLinked="1"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/>""")
            sb.append("""<c:tickLblPos val="nextTo"/><c:crossAx val="500000001"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>""")
        }
        sb.append("""</c:plotArea><c:legend><c:legendPos val="${if (type == ChartType.PIE) "r" else "b"}"/><c:overlay val="0"/></c:legend>""")
        sb.append("""<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>""")
        pkg.addXmlPart(chartName, ContentTypeNames.CHART, XmlParser.parse(sb.toString()))
        val rel = pkg.relationships(drawingPart).addTo(RelTypes.CHART, chartName)

        // Anchor in the drawing.
        val nextId = (drawingRoot.descendants().filter { it.localName == "cNvPr" }.mapNotNull { it.attr("id")?.toIntOrNull() }.maxOrNull() ?: 1) + 1
        val anchorXml = """<xdr:twoCellAnchor xmlns:xdr="${Namespaces.XDR}" xmlns:a="${Namespaces.A}">""" +
            """<xdr:from><xdr:col>${anchor.left - 1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${anchor.top - 1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""" +
            """<xdr:to><xdr:col>${anchor.right}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${anchor.bottom}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
            """<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="$nextId" name="Chart ${nextId - 1}"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>""" +
            """<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>""" +
            """<a:graphic><a:graphicData uri="$CHART_URI"><c:chart xmlns:c="${Namespaces.C}" xmlns:r="${Namespaces.R}" r:id="${rel.id}"/></a:graphicData></a:graphic>""" +
            """</xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>"""
        val anchorEl = XmlParser.parse(anchorXml).root.deepCopy()
        // Drop redundant declarations when the drawing already binds the prefixes.
        for (decl in listOf("xmlns:xdr", "xmlns:a")) {
            val prefix = decl.substringAfter(':')
            val uri = anchorEl.attribute(decl)?.value
            if (uri != null && drawingRoot.lookupNamespaceUri(prefix) == uri) anchorEl.removeAttribute(decl)
        }
        drawingRoot.appendChild(anchorEl)
        return ChartInfo(type, chartEl, title, seriesOut, DrawingAnchor(anchor.first, CellRef(anchor.bottom + 1, anchor.right + 1)), chartName)
    }

    /** The sheet's drawing root, creating the drawing part and the sheet's `<drawing>` if needed. */
    private fun ensureDrawing(ws: Worksheet): XElement {
        drawingPart(ws)?.let { p ->
            return p.xmlOrNull()?.root ?: throw XlsxEditException("The sheet's drawing cannot be edited safely")
        }
        val pkg = ws.workbook.pkg
        val dir = PartNames.directory(ws.workbook.workbookPart.name)
        val name = pkg.uniquePartName("${dir}drawings/drawing", "xml")
        val doc = XmlParser.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<xdr:wsDr xmlns:xdr="${Namespaces.XDR}" xmlns:a="${Namespaces.A}"></xdr:wsDr>""",
        )
        pkg.addXmlPart(name, ContentTypeNames.DRAWING, doc)
        val rel = pkg.relationships(ws.part).addTo(RelTypes.DRAWING, name)
        val d = ws.root.createChild(Namespaces.S, "drawing")
        d.setAttr(Namespaces.R, "id", rel.id)
        ws.root.insertInOrder(d, XlsxSchema.WORKSHEET)
        return pkg.part(name)!!.xml().root
    }
}
