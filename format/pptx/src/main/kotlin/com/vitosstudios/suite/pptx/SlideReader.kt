package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.Namespaces.MC
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.Namespaces.R
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlCodec

private const val TABLE_URI = "http://schemas.openxmlformats.org/drawingml/2006/table"
private const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"
private const val DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
private const val OLE_URI = "http://schemas.openxmlformats.org/presentationml/2006/ole"

/** Parsed root of a part; falls back to a detached read-only tree if the part is not editable. */
internal fun readRoot(part: Part?): XElement? {
    if (part == null) return null
    return part.xmlOrNull()?.root ?: try { XmlCodec.parse(part.bytes()).root } catch (e: Exception) { null }
}

/** The slide, its layout, master and theme, with the color context they define. */
internal class SlideContext(
    val pkg: OpcPackage,
    val slidePart: Part,
    val slide: XElement,
    val layoutPart: Part?,
    val layout: XElement?,
    val masterPart: Part?,
    val master: XElement?,
    val theme: Theme,
    val colors: ColorContext,
    val presentation: XElement,
) {
    val layoutPlaceholders: List<XElement> = placeholders(layout)
    val masterPlaceholders: List<XElement> = placeholders(master)

    private fun placeholders(root: XElement?): List<XElement> =
        root?.element(P, "cSld")?.element(P, "spTree")?.descendants(P, "ph")?.mapNotNull { ph ->
            ph.ancestors().firstOrNull { it.localName in setOf("sp", "pic", "graphicFrame", "grpSp", "cxnSp") }
        }?.toList().orEmpty()

    companion object {
        fun forSlide(pkg: OpcPackage, slidePart: Part, presentation: XElement): SlideContext {
            val slide = readRoot(slidePart) ?: throw PptxEditException("Slide ${slidePart.name} cannot be read")
            val layoutPart = pkg.related(slidePart, RelTypes.SLIDE_LAYOUT)
            return forRoot(pkg, slidePart, slide, layoutPart, presentation)
        }

        /** Context for any part that follows the slide -> layout -> master chain. */
        fun forRoot(pkg: OpcPackage, part: Part, root: XElement, layoutPart: Part?, presentation: XElement): SlideContext {
            val layout = readRoot(layoutPart)
            val masterPart = layoutPart?.let { pkg.related(it, RelTypes.SLIDE_MASTER) }
            val master = readRoot(masterPart)
            val theme = Theme.read(readRoot(masterPart?.let { pkg.related(it, RelTypes.THEME) }))
            var colors = ColorContext.fromClrMap(theme, master?.element(P, "clrMap"))
            colors = colors.withOverride(layout?.element(P, "clrMapOvr")?.element(A, "overrideClrMapping"))
            colors = colors.withOverride(root.element(P, "clrMapOvr")?.element(A, "overrideClrMapping"))
            return SlideContext(pkg, part, root, layoutPart, layout, masterPart, master, theme, colors, presentation)
        }
    }
}

/** Builds [SlideView]s with placeholder, text-style and color inheritance resolved. */
internal class SlideReader(private val ctx: SlideContext) {
    private val pkg = ctx.pkg

    fun read(index: Int): SlideView {
        val slide = ctx.slide
        val shapes = ArrayList<ShapeView>()
        val showMaster = slide.attr("showMasterSp") != "0"
        val layoutShowsMaster = ctx.layout?.attr("showMasterSp") != "0"
        if (showMaster && layoutShowsMaster && ctx.master != null && ctx.masterPart != null) {
            shapes += readTree(ctx.master, ctx.masterPart, ShapeSource.MASTER, decorationsOnly = true)
        }
        if (showMaster && ctx.layout != null && ctx.layoutPart != null) {
            shapes += readTree(ctx.layout, ctx.layoutPart, ShapeSource.LAYOUT, decorationsOnly = true)
        }
        shapes += readTree(slide, ctx.slidePart, ShapeSource.SLIDE, decorationsOnly = false)
        return SlideView(
            index = index,
            partName = ctx.slidePart.name,
            layoutPartName = ctx.layoutPart?.name,
            hidden = slide.attr("show") == "0",
            background = background(),
            shapes = shapes,
            hasTransition = hasTransition(slide),
            hasAnimations = slide.element(P, "timing") != null,
        )
    }

    private fun readTree(root: XElement, part: Part, source: ShapeSource, decorationsOnly: Boolean): List<ShapeView> {
        val tree = root.element(P, "cSld")?.element(P, "spTree") ?: return emptyList()
        val out = ArrayList<ShapeView>()
        for (child in tree.elements()) {
            if (decorationsOnly && isPlaceholder(child)) continue
            readShape(child, part, source, Transform.IDENTITY)?.let { out += it }
        }
        return out
    }

    private fun isPlaceholder(e: XElement): Boolean = nvPr(e)?.element(P, "ph") != null

    // ----------------------------------------------------------------------------------------
    // Shapes

    fun readShape(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView? = when (e.localName) {
        "sp" -> readSp(e, part, source, transform)
        "pic" -> readPic(e, part, source, transform)
        "cxnSp" -> readConnector(e, part, source, transform)
        "grpSp" -> readGroup(e, part, source, transform)
        "graphicFrame" -> readGraphicFrame(e, part, source, transform)
        "contentPart" -> ShapeView(0, "Ink", ShapeKind.INK, source, EmuRect.ZERO, isLocked = true,
            lockReason = "Ink drawings are shown as a placeholder and saved unchanged.")
        "AlternateContent" -> readAlternate(e, part, source, transform)
        else -> null
    }

    private fun readAlternate(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView? {
        val fallback = e.element(MC, "Fallback")?.elements()?.firstOrNull()
        val choice = e.element(MC, "Choice")?.elements()?.firstOrNull()
        val inner = fallback?.let { readShape(it, part, source, transform) }
            ?: choice?.let { readShape(it, part, source, transform) }
        val choiceKind = choice?.localName
        val kind = when {
            choiceKind == "contentPart" -> ShapeKind.INK
            inner != null -> inner.kind
            else -> ShapeKind.OTHER
        }
        return (inner ?: ShapeView(0, "Content", kind, source, EmuRect.ZERO)).copy(
            kind = kind,
            isLocked = true,
            lockReason = "Uses features from a newer version of the format; shown as saved and kept unchanged.",
        )
    }

    private fun nvPr(e: XElement): XElement? {
        val nv = e.elements().firstOrNull { it.localName.startsWith("nv") } ?: return null
        return nv.element(P, "nvPr")
    }

    private fun cNvPr(e: XElement): XElement? =
        e.elements().firstOrNull { it.localName.startsWith("nv") }?.element(P, "cNvPr")

    private fun placeholderOf(e: XElement): PlaceholderInfo? {
        val ph = nvPr(e)?.element(P, "ph") ?: return null
        return PlaceholderInfo(ph.attr("type") ?: "obj", ph.attr("idx")?.toIntOrNull())
    }

    /** The matching layout and master placeholder elements, nearest first. */
    fun inheritedPlaceholders(e: XElement, source: ShapeSource): List<XElement> {
        val ph = placeholderOf(e) ?: return emptyList()
        return when (source) {
            ShapeSource.SLIDE -> listOfNotNull(
                match(ph, ctx.layoutPlaceholders),
                match(ph, ctx.masterPlaceholders, master = true),
            )
            ShapeSource.LAYOUT -> listOfNotNull(match(ph, ctx.masterPlaceholders, master = true))
            ShapeSource.MASTER -> emptyList()
        }
    }

    private fun match(ph: PlaceholderInfo, candidates: List<XElement>, master: Boolean = false): XElement? {
        val infos = candidates.map { it to placeholderOf(it)!! }
        if (!master && ph.index != null) {
            infos.firstOrNull { it.second.index == ph.index }?.let { return it.first }
        }
        val wanted = if (master) masterType(ph.type) else ph.type
        infos.firstOrNull { (if (master) masterType(it.second.type) else it.second.type) == wanted }?.let { return it.first }
        if (!master && ph.type == "obj") infos.firstOrNull { it.second.type == "body" }?.let { return it.first }
        return null
    }

    private fun masterType(type: String): String = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> type
        else -> "body"
    }

    private fun xfrmOf(e: XElement): XElement? = when (e.localName) {
        "graphicFrame" -> e.element(P, "xfrm")
        "grpSp" -> e.element(P, "grpSpPr")?.element(A, "xfrm")
        else -> e.element(P, "spPr")?.element(A, "xfrm")
    }

    private data class RawXfrm(val rect: EmuRect, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun rawXfrm(x: XElement?): RawXfrm? {
        if (x == null) return null
        val off = x.element(A, "off")
        val ext = x.element(A, "ext")
        if (off == null && ext == null) return null
        return RawXfrm(
            EmuRect(
                off?.attr("x")?.toLongOrNull() ?: 0, off?.attr("y")?.toLongOrNull() ?: 0,
                ext?.attr("cx")?.toLongOrNull() ?: 0, ext?.attr("cy")?.toLongOrNull() ?: 0,
            ),
            (x.attr("rot")?.toDoubleOrNull() ?: 0.0) / 60000.0,
            x.attr("flipH") == "1" || x.attr("flipH") == "true",
            x.attr("flipV") == "1" || x.attr("flipV") == "true",
        )
    }

    private fun geometry(e: XElement, source: ShapeSource, transform: Transform): RawXfrm {
        val own = rawXfrm(xfrmOf(e))
        val raw = own ?: inheritedPlaceholders(e, source).firstNotNullOfOrNull { rawXfrm(xfrmOf(it)) }
            ?: RawXfrm(EmuRect.ZERO, 0.0, false, false)
        // Inherited placeholder geometry is already in slide coordinates.
        val rect = if (own != null) transform.map(raw.rect) else raw.rect
        return raw.copy(rect = rect, rot = raw.rot + transform.rotation)
    }

    private fun base(e: XElement, kind: ShapeKind, source: ShapeSource, g: RawXfrm): ShapeView {
        val c = cNvPr(e)
        return ShapeView(
            id = c?.attr("id")?.toIntOrNull() ?: 0,
            name = c?.attr("name") ?: "",
            kind = kind,
            source = source,
            bounds = g.rect,
            rotation = g.rot,
            flipH = g.flipH,
            flipV = g.flipV,
            placeholder = placeholderOf(e),
            hidden = c?.attr("hidden") == "1" || c?.attr("hidden") == "true",
            hyperlink = c?.element(A, "hlinkClick")?.let { hyperlinkTarget(it, ctxPart(source)) },
        )
    }

    private fun ctxPart(source: ShapeSource): Part = when (source) {
        ShapeSource.SLIDE -> ctx.slidePart
        ShapeSource.LAYOUT -> ctx.layoutPart ?: ctx.slidePart
        ShapeSource.MASTER -> ctx.masterPart ?: ctx.slidePart
    }

    private fun readSp(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView {
        val g = geometry(e, source, transform)
        val isTextBox = e.element(P, "nvSpPr")?.element(P, "cNvSpPr")?.attr("txBox").let { it == "1" || it == "true" }
        val spPr = e.element(P, "spPr")
        val inherited = inheritedPlaceholders(e, source)
        val geometryName = geometryName(spPr) ?: inherited.firstNotNullOfOrNull { geometryName(it.element(P, "spPr")) }
        val kind = if (isTextBox || (geometryName == null || geometryName == "rect") && e.element(P, "txBody") != null && e.element(P, "style") == null) ShapeKind.TEXT_BOX else ShapeKind.SHAPE
        val style = e.element(P, "style")
        val fill = shapeFill(spPr, part, style) ?: inherited.firstNotNullOfOrNull { shapeFill(it.element(P, "spPr"), part, it.element(P, "style")) } ?: FillView.None
        val line = shapeLine(spPr, style) ?: inherited.firstNotNullOfOrNull { shapeLine(it.element(P, "spPr"), it.element(P, "style")) }
        val text = e.element(P, "txBody")?.let { readTextBody(it, e, source, part) }
        return base(e, kind, source, g).copy(geometry = geometryName, fill = fill, line = line, text = text)
    }

    private fun geometryName(spPr: XElement?): String? {
        if (spPr == null) return null
        spPr.element(A, "prstGeom")?.let { return it.attr("prst") ?: "rect" }
        if (spPr.element(A, "custGeom") != null) return "custom"
        return null
    }

    private fun readPic(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView {
        val g = geometry(e, source, transform)
        val blipFill = e.element(P, "blipFill")
        val blip = blipFill?.element(A, "blip")
        val embed = blip?.attr(R, "embed")
        val imagePart = embed?.let { pkg.relationships(part).targetPart(it) }
        val src = blipFill?.element(A, "srcRect")
        fun pct(name: String) = (src?.attr(name)?.toDoubleOrNull() ?: 0.0) / 100000.0
        val image = imagePart?.let { ImageRef(it.name, pct("l"), pct("t"), pct("r"), pct("b")) }
        val nv = nvPr(e)
        val isMedia = nv != null && (nv.element(A, "videoFile") != null || nv.element(A, "audioFile") != null ||
            nv.element(A, "quickTimeFile") != null || nv.descendants().any { it.localName == "media" })
        val spPr = e.element(P, "spPr")
        return base(e, if (isMedia) ShapeKind.MEDIA else ShapeKind.PICTURE, source, g).copy(
            geometry = geometryName(spPr) ?: "rect",
            image = image,
            line = shapeLine(spPr, e.element(P, "style")),
            isLocked = isMedia || (image == null && blip?.attr(R, "link") != null),
            lockReason = when {
                isMedia -> "Audio and video are shown as their poster frame and saved unchanged."
                image == null && blip?.attr(R, "link") != null -> "Linked pictures are kept as links and are not loaded."
                else -> null
            },
        )
    }

    private fun readConnector(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView {
        val g = geometry(e, source, transform)
        val spPr = e.element(P, "spPr")
        val style = e.element(P, "style")
        return base(e, ShapeKind.CONNECTOR, source, g).copy(
            geometry = geometryName(spPr) ?: "line",
            line = shapeLine(spPr, style) ?: LineView(ctx.colors.resolveChild(style?.element(A, "lnRef")), 12700, null),
        )
    }

    private fun readGroup(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView {
        val x = xfrmOf(e)
        val g = geometry(e, source, transform)
        val chOff = x?.element(A, "chOff")
        val chExt = x?.element(A, "chExt")
        val raw = rawXfrm(x)
        val child = if (raw != null && chOff != null && chExt != null) {
            val ch = EmuRect(chOff.attr("x")?.toLongOrNull() ?: 0, chOff.attr("y")?.toLongOrNull() ?: 0,
                chExt.attr("cx")?.toLongOrNull() ?: 0, chExt.attr("cy")?.toLongOrNull() ?: 0)
            transform.then(Transform.between(ch, raw.rect, raw.rot))
        } else transform
        val children = e.elements().filter { it.localName in SHAPE_NAMES }.mapNotNull { readShape(it, part, source, child) }.toList()
        return base(e, ShapeKind.GROUP, source, g).copy(children = children)
    }

    private fun readGraphicFrame(e: XElement, part: Part, source: ShapeSource, transform: Transform): ShapeView {
        val g = geometry(e, source, transform)
        val data = e.element(A, "graphic")?.element(A, "graphicData")
        val uri = data?.attr("uri")
        return when (uri) {
            TABLE_URI -> base(e, ShapeKind.TABLE, source, g).copy(
                table = data.element(A, "tbl")?.let(::readTable),
                isLocked = true,
                lockReason = "Tables are shown read-only and saved unchanged.",
            )
            CHART_URI -> base(e, ShapeKind.CHART, source, g).copy(isLocked = true,
                lockReason = "Charts are shown as a placeholder and saved unchanged.")
            DIAGRAM_URI -> base(e, ShapeKind.SMARTART, source, g).copy(isLocked = true,
                lockReason = "SmartArt diagrams are shown as a placeholder and saved unchanged.")
            OLE_URI -> base(e, ShapeKind.OLE, source, g).copy(isLocked = true,
                lockReason = "Embedded objects are shown as a placeholder and saved unchanged.",
                image = oleImage(data, part))
            else -> base(e, ShapeKind.OTHER, source, g).copy(isLocked = true,
                lockReason = "This object type is not supported; it is kept unchanged.")
        }
    }

    private fun oleImage(data: XElement, part: Part): ImageRef? {
        val blip = data.descendants(A, "blip").firstOrNull() ?: return null
        val embed = blip.attr(R, "embed") ?: return null
        return pkg.relationships(part).targetPart(embed)?.let { ImageRef(it.name) }
    }

    private fun readTable(tbl: XElement): TableView {
        val widths = tbl.element(A, "tblGrid")?.elements(A, "gridCol")?.map { it.attr("w")?.toLongOrNull() ?: 0 }?.toList().orEmpty()
        val rows = tbl.elements(A, "tr").toList()
        return TableView(
            rows = rows.map { tr ->
                tr.elements(A, "tc").map { tc ->
                    tc.element(A, "txBody")?.elements(A, "p")?.joinToString("\n") { p -> paragraphText(p) } ?: ""
                }.toList()
            },
            columnWidths = widths,
            rowHeights = rows.map { it.attr("h")?.toLongOrNull() ?: 0 },
        )
    }

    // ----------------------------------------------------------------------------------------
    // Fills and lines

    private fun shapeFill(spPr: XElement?, part: Part, style: XElement?): FillView? {
        spPr?.elements()?.firstOrNull { it.localName in Schema.FILLS }?.let { return fill(it, part, null) }
        val ref = style?.element(A, "fillRef") ?: return null
        val idx = ref.attr("idx")?.toIntOrNull() ?: 0
        val ph = ctx.colors.resolveChild(ref)
        if (idx == 0) return FillView.None
        val themed = ctx.theme.fillStyles.getOrNull(idx - 1)
        return themed?.let { fill(it, themePart() ?: part, ph) } ?: ph?.let { FillView.Solid(it) }
    }

    private fun themePart(): Part? = ctx.masterPart?.let { pkg.related(it, RelTypes.THEME) }

    fun fill(f: XElement, part: Part, phClr: Int?): FillView = when (f.localName) {
        "noFill" -> FillView.None
        "solidFill" -> ctx.colors.resolveChild(f, phClr)?.let { FillView.Solid(it) } ?: FillView.None
        "gradFill" -> {
            val stops = f.element(A, "gsLst")?.elements(A, "gs")?.mapNotNull { gs ->
                val c = ctx.colors.resolveChild(gs, phClr) ?: return@mapNotNull null
                ((gs.attr("pos")?.toDoubleOrNull() ?: 0.0) / 100000.0) to c
            }?.toList().orEmpty()
            val angle = (f.element(A, "lin")?.attr("ang")?.toDoubleOrNull() ?: 0.0) / 60000.0
            if (stops.isEmpty()) FillView.None else FillView.Gradient(stops, angle)
        }
        "blipFill" -> {
            val embed = f.element(A, "blip")?.attr(R, "embed")
            val target = embed?.let { pkg.relationships(part).targetPart(it) }
            if (target != null) FillView.Image(target.name) else FillView.Unsupported("blipFill", null)
        }
        "pattFill" -> FillView.Unsupported("pattFill", ctx.colors.resolveChild(f.element(A, "fgClr"), phClr))
        "grpFill" -> FillView.Unsupported("grpFill", null)
        else -> FillView.None
    }

    private fun shapeLine(spPr: XElement?, style: XElement?): LineView? {
        val ln = spPr?.element(A, "ln")
        val ref = style?.element(A, "lnRef")
        val refIdx = ref?.attr("idx")?.toIntOrNull() ?: 0
        val themed = if (refIdx > 0) ctx.theme.lineStyles.getOrNull(refIdx - 1) else null
        if (ln == null && themed == null) return null
        val phClr = ctx.colors.resolveChild(ref)
        val width = ln?.attr("w")?.toLongOrNull() ?: themed?.attr("w")?.toLongOrNull() ?: 9525L
        val fillEl = ln?.elements()?.firstOrNull { it.localName in Schema.FILLS }
            ?: themed?.elements()?.firstOrNull { it.localName in Schema.FILLS }
        val color = when (fillEl?.localName) {
            "noFill" -> return null
            "solidFill" -> ctx.colors.resolveChild(fillEl, phClr)
            "gradFill" -> fillEl.element(A, "gsLst")?.elements(A, "gs")?.firstOrNull()?.let { ctx.colors.resolveChild(it, phClr) }
            null -> phClr
            else -> phClr
        }
        val dash = (ln?.element(A, "prstDash") ?: themed?.element(A, "prstDash"))?.attr("val")
        return LineView(color, width, dash)
    }

    private fun background(): FillView {
        for ((root, part) in listOf(ctx.slide to ctx.slidePart, ctx.layout to ctx.layoutPart, ctx.master to ctx.masterPart)) {
            if (root == null || part == null) continue
            val bg = root.element(P, "cSld")?.element(P, "bg") ?: continue
            bg.element(P, "bgPr")?.let { pr ->
                val f = pr.elements().firstOrNull { it.localName in Schema.FILLS } ?: return@let
                return fill(f, part, null)
            }
            bg.element(P, "bgRef")?.let { ref ->
                val idx = ref.attr("idx")?.toIntOrNull() ?: 0
                val ph = ctx.colors.resolveChild(ref)
                val style = when {
                    idx >= 1001 -> ctx.theme.bgFillStyles.getOrNull(idx - 1001)
                    idx >= 1 -> ctx.theme.fillStyles.getOrNull(idx - 1)
                    else -> null
                }
                return style?.let { fill(it, themePart() ?: part, ph) } ?: ph?.let { FillView.Solid(it) } ?: FillView.Solid(WHITE)
            }
        }
        return FillView.Solid(ctx.colors.resolveChild(null) ?: ctx.theme.colors["lt1"] ?: WHITE)
    }

    private fun hasTransition(slide: XElement): Boolean =
        slide.element(P, "transition") != null ||
            slide.elements().any { it.localName == "AlternateContent" && it.descendants().any { d -> d.localName == "transition" } }

    // ----------------------------------------------------------------------------------------
    // Text

    /** Ordered property sources for paragraphs at [level] of shape [e] (nearest first). */
    fun levelSources(e: XElement, source: ShapeSource, level: Int): List<XElement> {
        val lvlName = "lvl${level + 1}pPr"
        val out = ArrayList<XElement>()
        fun fromLstStyle(shape: XElement?) {
            val lst = shape?.element(P, "txBody")?.element(A, "lstStyle") ?: return
            lst.element(A, lvlName)?.let { out += it }
            lst.element(A, "defPPr")?.let { out += it }
        }
        fromLstStyle(e)
        val inherited = inheritedPlaceholders(e, source)
        inherited.forEach(::fromLstStyle)
        val ph = placeholderOf(e)
        val txStyles = ctx.master?.element(P, "txStyles")
        if (ph != null) {
            val styleName = when (ph.type) {
                "title", "ctrTitle" -> "titleStyle"
                "dt", "ftr", "sldNum", "hdr" -> "otherStyle"
                else -> "bodyStyle"
            }
            txStyles?.element(P, styleName)?.element(A, lvlName)?.let { out += it }
        } else {
            ctx.presentation.element(P, "defaultTextStyle")?.let { d ->
                d.element(A, lvlName)?.let { out += it }
                d.element(A, "defPPr")?.let { out += it }
            }
            txStyles?.element(P, "otherStyle")?.element(A, lvlName)?.let { out += it }
        }
        return out
    }

    fun readTextBody(txBody: XElement, shape: XElement, source: ShapeSource, part: Part): TextBodyView {
        val bodies = listOf(txBody.element(A, "bodyPr")) +
            inheritedPlaceholders(shape, source).map { it.element(P, "txBody")?.element(A, "bodyPr") }
        fun bodyAttr(name: String): String? = bodies.firstNotNullOfOrNull { it?.attr(name) }
        val autofitEl = bodies.firstNotNullOfOrNull { b -> b?.elements()?.firstOrNull { it.localName in setOf("noAutofit", "normAutofit", "spAutoFit") } }
        val autofit = when (autofitEl?.localName) {
            "normAutofit" -> Autofit.SHRINK
            "spAutoFit" -> Autofit.RESIZE_SHAPE
            else -> Autofit.NONE
        }
        val fontScale = if (autofitEl?.localName == "normAutofit") (autofitEl.attr("fontScale")?.toDoubleOrNull() ?: 100000.0) / 100000.0 else 1.0
        val fontRefColor = shape.element(P, "style")?.element(A, "fontRef")?.let { ctx.colors.resolveChild(it) }

        val paragraphs = ArrayList<ParagraphView>()
        val counters = HashMap<Int, Pair<String, Int>>()
        for ((i, p) in txBody.elements(A, "p").withIndex()) {
            val view = readParagraph(i, p, shape, source, part, fontRefColor)
            // Auto-numbering: continue while the same scheme repeats at a level.
            val numbered = if (view.bullet.type == BulletType.NUMBER) {
                val prev = counters[view.level]
                val n = if (prev != null && prev.first == view.bullet.scheme) prev.second + 1 else view.bullet.startAt
                counters[view.level] = (view.bullet.scheme ?: "") to n
                counters.keys.filter { it > view.level }.forEach { counters.remove(it) }
                view.copy(bullet = view.bullet.copy(number = n))
            } else {
                counters.keys.filter { it >= view.level }.forEach { counters.remove(it) }
                view
            }
            paragraphs += numbered
        }
        return TextBodyView(
            paragraphs = paragraphs,
            insetLeft = bodyAttr("lIns")?.toLongOrNull() ?: 91440,
            insetTop = bodyAttr("tIns")?.toLongOrNull() ?: 45720,
            insetRight = bodyAttr("rIns")?.toLongOrNull() ?: 91440,
            insetBottom = bodyAttr("bIns")?.toLongOrNull() ?: 45720,
            anchor = when (bodyAttr("anchor")) {
                "ctr" -> TextAnchor.MIDDLE
                "b" -> TextAnchor.BOTTOM
                else -> TextAnchor.TOP
            },
            autofit = autofit,
            fontScale = fontScale,
            wrap = bodyAttr("wrap") != "none",
            vertical = bodyAttr("vert").let { it != null && it != "horz" },
        )
    }

    private fun readParagraph(index: Int, p: XElement, shape: XElement, source: ShapeSource, part: Part, fontRefColor: Int?): ParagraphView {
        val pPr = p.element(A, "pPr")
        val level = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
        val sources = listOfNotNull(pPr) + levelSources(shape, source, level)
        fun attr(name: String) = sources.firstNotNullOfOrNull { it.attr(name) }
        fun child(name: String) = sources.firstNotNullOfOrNull { it.element(A, name) }
        val defRPrs = sources.filter { it !== pPr }.mapNotNull { it.element(A, "defRPr") }

        val runs = ArrayList<RunView>()
        val sb = StringBuilder()
        for (c in p.elements()) {
            val kind = when (c.localName) {
                "r" -> RunKind.TEXT
                "br" -> RunKind.BREAK
                "fld" -> RunKind.FIELD
                else -> continue
            }
            val text = if (kind == RunKind.BREAK) LINE_BREAK.toString() else c.element(A, "t")?.textContent ?: ""
            val start = sb.length
            sb.append(text)
            runs += runView(start, sb.length, text, kind, listOfNotNull(c.element(A, "rPr")) + defRPrs, part, fontRefColor)
                .copy(fieldType = if (kind == RunKind.FIELD) c.attr("type") else null)
        }
        val endProps = listOfNotNull(p.element(A, "endParaRPr")) + defRPrs
        val endSize = endProps.firstNotNullOfOrNull { it.attr("sz")?.toDoubleOrNull() }?.div(100.0) ?: DEFAULT_SIZE
        val sizeForSpacing = runs.firstOrNull()?.sizePt ?: endSize

        val bulletEl = sources.firstNotNullOfOrNull { s -> s.elements().firstOrNull { it.localName in Schema.BULLET_TYPES } }
        val bulletFont = child("buFont")?.attr("typeface")?.let { ctx.theme.typeface(it) }
        val bulletColor = child("buClr")?.let { ctx.colors.resolveChild(it) }
        val bulletSize = (child("buSzPct")?.attr("val")?.toDoubleOrNull() ?: 100000.0) / 100000.0
        val bullet = when (bulletEl?.localName) {
            "buChar" -> BulletView(BulletType.CHAR, char = bulletEl.attr("char") ?: "•", typeface = bulletFont, argb = bulletColor, sizeRatio = bulletSize)
            "buAutoNum" -> BulletView(BulletType.NUMBER, scheme = bulletEl.attr("type") ?: "arabicPeriod",
                startAt = bulletEl.attr("startAt")?.toIntOrNull() ?: 1, typeface = bulletFont, argb = bulletColor, sizeRatio = bulletSize)
            "buBlip" -> BulletView(BulletType.PICTURE, argb = bulletColor, sizeRatio = bulletSize)
            else -> BulletView.NONE
        }

        fun spacing(name: String): Double {
            val el = child(name) ?: return 0.0
            el.element(A, "spcPts")?.attr("val")?.toDoubleOrNull()?.let { return it / 100.0 }
            el.element(A, "spcPct")?.attr("val")?.toDoubleOrNull()?.let { return it / 100000.0 * sizeForSpacing * 1.2 }
            return 0.0
        }
        val lnSpc = child("lnSpc")?.let { l ->
            l.element(A, "spcPct")?.attr("val")?.toDoubleOrNull()?.let { LineSpacing(multiple = it / 100000.0) }
                ?: l.element(A, "spcPts")?.attr("val")?.toDoubleOrNull()?.let { LineSpacing(points = it / 100.0) }
        }
        return ParagraphView(
            index = index,
            text = sb.toString(),
            runs = runs,
            level = level,
            alignment = when (attr("algn")) {
                "ctr" -> TextAlign.CENTER
                "r" -> TextAlign.RIGHT
                "just", "justLow" -> TextAlign.JUSTIFY
                "dist", "thaiDist" -> TextAlign.DISTRIBUTED
                else -> TextAlign.LEFT
            },
            bullet = bullet,
            marginLeftEmu = attr("marL")?.toLongOrNull() ?: 0,
            indentEmu = attr("indent")?.toLongOrNull() ?: 0,
            lineSpacing = lnSpc,
            spaceBeforePt = spacing("spcBef"),
            spaceAfterPt = spacing("spcAft"),
            endSizePt = endSize,
        )
    }

    private fun runView(start: Int, end: Int, text: String, kind: RunKind, props: List<XElement>, part: Part, fontRefColor: Int?): RunView {
        fun attr(name: String) = props.firstNotNullOfOrNull { it.attr(name) }
        fun flag(name: String) = attr(name).let { it == "1" || it == "true" }
        val fillEl = props.firstNotNullOfOrNull { p -> p.elements().firstOrNull { it.localName in Schema.FILLS } }
        val color = when (fillEl?.localName) {
            "solidFill" -> ctx.colors.resolveChild(fillEl)
            "gradFill" -> fillEl.element(A, "gsLst")?.elements(A, "gs")?.firstOrNull()?.let { ctx.colors.resolveChild(it) }
            "noFill" -> 0
            else -> null
        } ?: fontRefColor ?: ctx.colors.resolve(schemeTx1()) ?: BLACK
        val typeface = props.firstNotNullOfOrNull { it.element(A, "latin")?.attr("typeface") }?.let { ctx.theme.typeface(it) }
            ?: ctx.theme.minorLatin
        val link = props.firstOrNull()?.takeIf { it.localName == "rPr" }?.element(A, "hlinkClick")?.let { hyperlinkTarget(it, part) }
        return RunView(
            start = start, end = end, text = text, kind = kind,
            sizePt = attr("sz")?.toDoubleOrNull()?.div(100.0) ?: DEFAULT_SIZE,
            bold = flag("b"),
            italic = flag("i"),
            underline = attr("u").let { it != null && it != "none" },
            strike = attr("strike").let { it != null && it != "noStrike" },
            argb = color,
            typeface = typeface,
            hyperlink = link,
            baseline = (attr("baseline")?.toIntOrNull() ?: 0) / 1000,
        )
    }

    private val tx1Element: XElement by lazy {
        XElement("a:schemeClr").also { it.setAttribute("val", "tx1") }
    }

    private fun schemeTx1(): XElement = tx1Element

    private fun hyperlinkTarget(h: XElement, part: Part): String? {
        val id = h.attr(R, "id")
        if (id.isNullOrEmpty()) return h.attr("action")
        return pkg.relationships(part).byId(id)?.target
    }

    private fun paragraphText(p: XElement): String = buildString {
        for (c in p.elements()) when (c.localName) {
            "r", "fld" -> append(c.element(A, "t")?.textContent ?: "")
            "br" -> append(LINE_BREAK)
        }
    }

    companion object {
        const val DEFAULT_SIZE = 18.0
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        val SHAPE_NAMES = setOf("sp", "grpSp", "graphicFrame", "cxnSp", "pic", "contentPart", "AlternateContent")
    }
}

/** Affine map from a group's child coordinate space to slide coordinates (no shear). */
internal data class Transform(val sx: Double, val sy: Double, val dx: Double, val dy: Double, val rotation: Double) {
    fun map(r: EmuRect): EmuRect = EmuRect(
        Math.round(r.x * sx + dx), Math.round(r.y * sy + dy), Math.round(r.cx * sx), Math.round(r.cy * sy),
    )

    /** This transform applied after [inner] (inner maps grandchild -> child space). */
    fun then(inner: Transform): Transform = Transform(
        sx * inner.sx, sy * inner.sy, inner.dx * sx + dx, inner.dy * sy + dy, rotation + inner.rotation,
    )

    companion object {
        val IDENTITY = Transform(1.0, 1.0, 0.0, 0.0, 0.0)

        /** Maps rectangle [child] (chOff/chExt) onto [parent] (off/ext). */
        fun between(child: EmuRect, parent: EmuRect, rotation: Double): Transform {
            val sx = if (child.cx == 0L) 1.0 else parent.cx.toDouble() / child.cx
            val sy = if (child.cy == 0L) 1.0 else parent.cy.toDouble() / child.cy
            return Transform(sx, sy, parent.x - child.x * sx, parent.y - child.y * sy, rotation)
        }
    }
}
