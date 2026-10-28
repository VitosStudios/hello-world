package com.vitosstudios.suite.xlsx

import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser
import kotlin.math.roundToInt

/** Resolved formatting of one cell format record (`cellXfs/xf`), for display. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontSize: Double = 11.0,
    val fontName: String? = null,
    /** ARGB, or null for automatic. */
    val fontColor: Int? = null,
    /** ARGB of a solid fill, or null. */
    val fillColor: Int? = null,
    val borderLeft: String? = null,
    val borderRight: String? = null,
    val borderTop: String? = null,
    val borderBottom: String? = null,
    /** "left", "center", "right", "justify", "fill", ... or null for general. */
    val horizontal: String? = null,
    /** "top", "center", "bottom" (default) ... */
    val vertical: String? = null,
    val wrapText: Boolean = false,
    val numFmtId: Int = 0,
    val numFmtCode: String = "General",
)

enum class BorderSide { LEFT, RIGHT, TOP, BOTTOM }

/** A border change: [style] is "thin", "medium", "thick", "dashed", "dotted", "double" or null to remove. */
data class BorderPatch(val sides: Set<BorderSide>, val style: String?, val color: Int? = null)

/** A style change applied to a range; null fields are left as they are. */
data class StylePatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val fontSize: Double? = null,
    val fontName: String? = null,
    /** ARGB; [clearFontColor] resets to automatic. */
    val fontColor: Int? = null,
    val clearFontColor: Boolean = false,
    /** ARGB solid fill; [clearFill] removes the fill. */
    val fillColor: Int? = null,
    val clearFill: Boolean = false,
    val border: BorderPatch? = null,
    val horizontal: String? = null,
    val vertical: String? = null,
    val wrapText: Boolean? = null,
    /** A number format code, e.g. "0.00%" or one of [NumberFormats]. */
    val numberFormat: String? = null,
) {
    val touchesFont: Boolean get() = bold != null || italic != null || underline != null || strike != null || fontSize != null || fontName != null || fontColor != null || clearFontColor
    val touchesFill: Boolean get() = fillColor != null || clearFill
    val touchesAlignment: Boolean get() = horizontal != null || vertical != null || wrapText != null
}

/** Ready-made number formats offered by the UI. */
object NumberFormats {
    const val GENERAL = "General"
    const val NUMBER = "#,##0.00"
    const val INTEGER = "#,##0"
    const val CURRENCY = "\"$\"#,##0.00"
    const val PERCENT = "0%"
    const val PERCENT_2 = "0.00%"
    const val DATE_ISO = "yyyy-mm-dd"
    const val DATE_US = "m/d/yyyy"
    const val TIME = "h:mm"
    const val DATE_TIME = "yyyy-mm-dd h:mm"
    const val SCIENTIFIC = "0.00E+00"
    const val TEXT = "@"
}

/**
 * The style sheet: number formats, fonts, fills, borders and cell formats. New combinations are
 * appended; existing records are never modified because other cells may share them.
 */
internal class Styles(private val pkg: OpcPackage, private val workbookPart: Part) {
    private val part: Part? get() = pkg.related(workbookPart, RelTypes.STYLES)
    private var cacheVersion = -1L
    private var cachePart: Part? = null
    private val styleCache = HashMap<Int, CellStyle>()
    private var customFormats: Map<Int, String> = emptyMap()
    private var theme: List<Int>? = null

    private fun root(): XElement? = part?.xmlOrNull()?.root

    private var xfList: List<XElement> = emptyList()
    private val dateCache = HashMap<Int, Boolean>()

    private fun refresh() {
        val p = part
        val doc = p?.xmlOrNull()
        val v = doc?.modCount ?: -2
        if (p === cachePart && v == cacheVersion) return
        styleCache.clear()
        dateCache.clear()
        customFormats = doc?.root?.firstChild("numFmts")?.childElements("numFmt")?.mapNotNull { e ->
            val id = e.a("numFmtId")?.toIntOrNull() ?: return@mapNotNull null
            id to (e.a("formatCode") ?: "General")
        }?.toMap() ?: emptyMap()
        xfList = doc?.root?.firstChild("cellXfs")?.childElements("xf") ?: emptyList()
        cachePart = p
        cacheVersion = v
    }

    /** Missing style sheets are created on the first formatting edit; unreadable ones block edits. */
    val isEditable: Boolean get() = part.let { it == null || it.xmlOrNull() != null }

    fun formatCode(numFmtId: Int): String {
        refresh()
        return customFormats[numFmtId] ?: BuiltinFormats.code(numFmtId) ?: "General"
    }

    private fun xfs(): List<XElement> { refresh(); return xfList }

    fun xfCount(): Int = xfs().size

    fun numFmtIdOf(xfIndex: Int): Int = xfs().getOrNull(xfIndex)?.a("numFmtId")?.toIntOrNull() ?: 0

    fun numFmtCodeOf(xfIndex: Int): String = formatCode(numFmtIdOf(xfIndex))

    fun isDateStyle(xfIndex: Int): Boolean {
        refresh()
        return dateCache.getOrPut(xfIndex) {
            val id = numFmtIdOf(xfIndex)
            BuiltinFormats.isDateId(id) || NumberFormat.isDateFormat(formatCode(id))
        }
    }

    /** The style sheet root, adding a minimal style sheet to workbooks that have none. */
    private fun editableRoot(): XElement {
        root()?.let { return it }
        if (part != null) throw XlsxEditException("The style sheet cannot be edited")
        val dir = workbookPart.name.substringBeforeLast('/')
        val name = if (pkg.part("$dir/styles.xml") == null) "$dir/styles.xml" else pkg.uniquePartName("$dir/styles", "xml")
        pkg.addXmlPart(name, com.vitosstudios.suite.opc.ContentTypeNames.SML_STYLES, minimalDocument())
        pkg.relationships(workbookPart).addTo(RelTypes.STYLES, name)
        return root()!!
    }

    fun style(xfIndex: Int): CellStyle {
        refresh()
        styleCache[xfIndex]?.let { return it }
        val r = root() ?: return CellStyle()
        val xf = xfs().getOrNull(xfIndex) ?: xfs().firstOrNull() ?: return CellStyle()
        val fonts = r.firstChild("fonts")?.childElements("font") ?: emptyList()
        val fills = r.firstChild("fills")?.childElements("fill") ?: emptyList()
        val borders = r.firstChild("borders")?.childElements("border") ?: emptyList()
        val font = fonts.getOrNull(xf.a("fontId")?.toIntOrNull() ?: 0)
        val fill = fills.getOrNull(xf.a("fillId")?.toIntOrNull() ?: 0)
        val border = borders.getOrNull(xf.a("borderId")?.toIntOrNull() ?: 0)
        val align = xf.firstChild("alignment")
        val numFmtId = xf.a("numFmtId")?.toIntOrNull() ?: 0
        fun flag(e: XElement?, name: String): Boolean {
            val c = e?.firstChild(name) ?: return false
            val v = c.a("val") ?: return true
            return v == "1" || v.equals("true", true)
        }
        val fillColor = fill?.firstChild("patternFill")?.let { pf ->
            val type = pf.a("patternType")
            if (type == null || type == "none") null else color(pf.firstChild("fgColor")) ?: if (type == "solid") null else color(pf.firstChild("bgColor"))
        }
        fun side(n: String) = border?.firstChild(n)?.a("style")?.takeIf { it != "none" }
        val st = CellStyle(
            bold = flag(font, "b"),
            italic = flag(font, "i"),
            underline = font?.firstChild("u")?.let { it.a("val") != "none" } ?: false,
            strike = flag(font, "strike"),
            fontSize = font?.firstChild("sz")?.a("val")?.toDoubleOrNull() ?: defaultFontSize(),
            fontName = font?.firstChild("name")?.a("val"),
            fontColor = color(font?.firstChild("color")),
            fillColor = fillColor,
            borderLeft = side("left") ?: side("start"),
            borderRight = side("right") ?: side("end"),
            borderTop = side("top"),
            borderBottom = side("bottom"),
            horizontal = align?.a("horizontal")?.takeIf { it != "general" },
            vertical = align?.a("vertical"),
            wrapText = align?.a("wrapText").let { it == "1" || it == "true" },
            numFmtId = numFmtId,
            numFmtCode = formatCode(numFmtId),
        )
        styleCache[xfIndex] = st
        return st
    }

    fun defaultFontSize(): Double =
        root()?.firstChild("fonts")?.childElements("font")?.firstOrNull()?.firstChild("sz")?.a("val")?.toDoubleOrNull() ?: 11.0

    /** Resolves `rgb`, `indexed` and `theme` (+`tint`) colours to ARGB. */
    fun color(e: XElement?): Int? {
        if (e == null) return null
        if (e.a("auto") == "1" || e.a("auto") == "true") return null
        val base: Int? = e.a("rgb")?.let { parseArgb(it) }
            ?: e.a("indexed")?.toIntOrNull()?.let { indexedColor(it) }
            ?: e.a("theme")?.toIntOrNull()?.let { themeColor(it) }
        val tint = e.a("tint")?.toDoubleOrNull() ?: 0.0
        return if (base != null && tint != 0.0) applyTint(base, tint) else base
    }

    private fun indexedColor(i: Int): Int? {
        val custom = root()?.firstChild("colors")?.firstChild("indexedColors")?.childElements("rgbColor")
        if (custom != null && i < custom.size) return custom[i].a("rgb")?.let { parseArgb(it) }
        return if (i in DEFAULT_INDEXED.indices) DEFAULT_INDEXED[i] else null
    }

    private fun themeColor(i: Int): Int? {
        val t = theme ?: loadTheme().also { theme = it }
        return t.getOrNull(i)
    }

    /** Theme colours in SpreadsheetML index order: lt1, dk1, lt2, dk2, accent1-6, hlink, folHlink. */
    private fun loadTheme(): List<Int> {
        val themePart = pkg.related(workbookPart, RelTypes.THEME) ?: return DEFAULT_THEME
        val scheme = themePart.xmlOrNull()?.root?.descendants(Namespaces.A, "clrScheme")?.firstOrNull() ?: return DEFAULT_THEME
        fun c(name: String): Int? {
            val e = scheme.element(Namespaces.A, name) ?: return null
            val v = e.element(Namespaces.A, "srgbClr")?.attr("val") ?: e.element(Namespaces.A, "sysClr")?.attr("lastClr") ?: return null
            return parseArgb(v)
        }
        val names = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        return names.mapIndexed { idx, n -> c(n) ?: DEFAULT_THEME[idx] }
    }

    // ----------------------------------------------------------------------------------------
    // Editing

    private val xfMemo = HashMap<Pair<Int, StylePatch>, Int>()

    /** Call before a batch of [patched] calls; the memo is only valid within one edit. */
    fun beginBatch() = xfMemo.clear()

    /** Index of a cell format equal to [xfIndex] with [patch] applied, appending records as needed. */
    fun patched(xfIndex: Int, patch: StylePatch): Int {
        xfMemo[xfIndex to patch]?.let { return it }
        val r = editableRoot()
        val cellXfs = r.firstChild("cellXfs") ?: throw XlsxEditException("The style sheet has no cell formats")
        val base = xfs().getOrNull(xfIndex) ?: xfs().firstOrNull() ?: throw XlsxEditException("The style sheet has no cell formats")
        val xf = base.deepCopy()
        if (patch.touchesFont) {
            xf.setAttr("fontId", patchFont(xf.a("fontId")?.toIntOrNull() ?: 0, patch).toString())
            xf.setAttr("applyFont", "1")
        }
        if (patch.touchesFill) {
            xf.setAttr("fillId", patchFill(patch).toString())
            xf.setAttr("applyFill", "1")
        }
        patch.border?.let {
            xf.setAttr("borderId", patchBorder(xf.a("borderId")?.toIntOrNull() ?: 0, it).toString())
            xf.setAttr("applyBorder", "1")
        }
        patch.numberFormat?.let {
            xf.setAttr("numFmtId", numFmtId(it).toString())
            xf.setAttr("applyNumberFormat", "1")
        }
        if (patch.touchesAlignment) {
            val align = xf.firstChild("alignment") ?: cellXfs.createChild(Namespaces.S, "alignment").also { xf.insertInOrder(it, XlsxSchema.XF) }
            patch.horizontal?.let { if (it == "general") align.removeAttribute("horizontal") else align.setAttr("horizontal", it) }
            patch.vertical?.let { if (it == "bottom") align.removeAttribute("vertical") else align.setAttr("vertical", it) }
            patch.wrapText?.let { if (it) align.setAttr("wrapText", "1") else align.removeAttribute("wrapText") }
            xf.setAttr("applyAlignment", "1")
        }
        val idx = findOrAppend(cellXfs, "xf", xf)
        xfMemo[xfIndex to patch] = idx
        return idx
    }

    private fun patchFont(fontId: Int, p: StylePatch): Int {
        val fonts = root()!!.firstChild("fonts") ?: throw XlsxEditException("The style sheet has no fonts")
        val font = (fonts.childElements("font").getOrNull(fontId) ?: fonts.childElements("font").first()).deepCopy()
        fun toggle(name: String, on: Boolean?) {
            if (on == null) return
            val e = font.firstChild(name)
            if (on) {
                if (e == null) font.insertInOrder(fonts.createChild(Namespaces.S, name), XlsxSchema.FONT) else e.removeAttribute("val")
            } else e?.detach()
        }
        toggle("b", p.bold)
        toggle("i", p.italic)
        toggle("strike", p.strike)
        p.underline?.let { on ->
            val e = font.firstChild("u")
            if (on) { if (e == null) font.insertInOrder(fonts.createChild(Namespaces.S, "u"), XlsxSchema.FONT) else e.removeAttribute("val") } else e?.detach()
        }
        p.fontSize?.let { setVal(font, fonts, "sz", formatXmlNumber(it)) }
        p.fontName?.let { setVal(font, fonts, "name", it) }
        if (p.fontColor != null || p.clearFontColor) {
            font.firstChild("color")?.detach()
            if (p.fontColor != null) {
                val c = fonts.createChild(Namespaces.S, "color")
                c.setAttr("rgb", argbHex(p.fontColor))
                font.insertInOrder(c, XlsxSchema.FONT)
            }
        }
        return findOrAppend(fonts, "font", font)
    }

    private fun setVal(target: XElement, context: XElement, name: String, value: String) {
        val e = target.firstChild(name) ?: context.createChild(Namespaces.S, name).also { target.insertInOrder(it, XlsxSchema.FONT) }
        e.setAttr("val", value)
    }

    private fun patchFill(p: StylePatch): Int {
        val fills = root()!!.firstChild("fills") ?: throw XlsxEditException("The style sheet has no fills")
        if (p.clearFill || p.fillColor == null) return 0
        val fill = fills.createChild(Namespaces.S, "fill")
        val pf = fills.createChild(Namespaces.S, "patternFill")
        pf.setAttr("patternType", "solid")
        val fg = fills.createChild(Namespaces.S, "fgColor")
        fg.setAttr("rgb", argbHex(p.fillColor))
        val bg = fills.createChild(Namespaces.S, "bgColor")
        bg.setAttr("indexed", "64")
        pf.appendChild(fg)
        pf.appendChild(bg)
        fill.appendChild(pf)
        return findOrAppend(fills, "fill", fill)
    }

    private fun patchBorder(borderId: Int, p: BorderPatch): Int {
        val borders = root()!!.firstChild("borders") ?: throw XlsxEditException("The style sheet has no borders")
        val existing = borders.childElements("border")
        val border = (existing.getOrNull(borderId) ?: existing.firstOrNull())?.deepCopy() ?: borders.createChild(Namespaces.S, "border")
        for (side in p.sides) {
            val name = side.name.lowercase()
            border.firstChild(name)?.detach()
            val e = borders.createChild(Namespaces.S, name)
            if (p.style != null) {
                e.setAttr("style", p.style)
                val c = borders.createChild(Namespaces.S, "color")
                if (p.color != null) c.setAttr("rgb", argbHex(p.color)) else c.setAttr("indexed", "64")
                e.appendChild(c)
            }
            border.insertInOrder(e, XlsxSchema.BORDER)
        }
        return findOrAppend(borders, "border", border)
    }

    private fun numFmtId(code: String): Int {
        BuiltinFormats.idFor(code)?.let { return it }
        refresh()
        customFormats.entries.firstOrNull { it.value == code }?.let { return it.key }
        val r = root()!!
        val numFmts = r.firstChild("numFmts") ?: r.createChild(Namespaces.S, "numFmts").also {
            it.setAttr("count", "0")
            r.insertInOrder(it, XlsxSchema.STYLESHEET)
        }
        val id = maxOf(163, customFormats.keys.maxOrNull() ?: 163) + 1
        val e = numFmts.createChild(Namespaces.S, "numFmt")
        e.setAttr("numFmtId", id.toString())
        e.setAttr("formatCode", code)
        numFmts.appendChild(e)
        numFmts.setAttr("count", numFmts.childElements("numFmt").size.toString())
        refresh()
        return id
    }

    /** Returns the index of an equal record in [container] or appends [record] and fixes `count`. */
    private fun findOrAppend(container: XElement, local: String, record: XElement): Int {
        val wanted = canonical(record)
        val list = container.childElements(local)
        for ((i, e) in list.withIndex()) if (canonical(e) == wanted) return i
        container.appendChild(record)
        if (container.attribute("count") != null) container.setAttr("count", (list.size + 1).toString())
        return list.size
    }

    /** Serialisation ignoring namespace declarations and whitespace, for equality checks. */
    private fun canonical(e: XElement): String {
        val sb = StringBuilder()
        fun walk(x: XElement) {
            sb.append('<').append(x.localName)
            for (a in x.attributes.filter { !it.isNamespaceDeclaration }.sortedBy { it.qName }) sb.append(' ').append(a.qName).append('=').append(a.value)
            sb.append('>')
            for (c in x.children) if (c is XElement) walk(c)
            sb.append("</>")
        }
        walk(e)
        return sb.toString()
    }

    companion object {
        fun parseArgb(s: String): Int? {
            val h = s.trim().removePrefix("#")
            return when (h.length) {
                8 -> h.toLongOrNull(16)?.toInt()?.let { if ((it ushr 24) == 0) it or (0xFF shl 24) else it }
                6 -> h.toLongOrNull(16)?.toInt()?.let { it or (0xFF shl 24) }
                else -> null
            }
        }

        fun argbHex(argb: Int): String = String.format("%08X", argb)

        /** Excel's tint: lightens (tint > 0) or darkens (tint < 0) in HSL space. */
        fun applyTint(argb: Int, tint: Double): Int {
            val r = (argb shr 16 and 0xFF) / 255.0
            val g = (argb shr 8 and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            var h = 0.0; var s = 0.0
            var l = (max + min) / 2
            if (max != min) {
                val d = max - min
                s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
                h = when (max) {
                    r -> (g - b) / d + (if (g < b) 6 else 0)
                    g -> (b - r) / d + 2
                    else -> (r - g) / d + 4
                } / 6
            }
            l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
            fun hue(p: Double, q: Double, t0: Double): Double {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 1.0 / 2 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            val (nr, ng, nb) = if (s == 0.0) Triple(l, l, l) else {
                val q = if (l < 0.5) l * (1 + s) else l + s - l * s
                val p = 2 * l - q
                Triple(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
            }
            return (argb and (0xFF shl 24)) or ((nr * 255).roundToInt().coerceIn(0, 255) shl 16) or
                ((ng * 255).roundToInt().coerceIn(0, 255) shl 8) or (nb * 255).roundToInt().coerceIn(0, 255)
        }

        private val DEFAULT_THEME = listOf(
            0xFFFFFFFF, 0xFF000000, 0xFFE7E6E6, 0xFF44546A, 0xFF4472C4, 0xFFED7D31,
            0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47, 0xFF0563C1, 0xFF954F72,
        ).map { it.toInt() }

        private val DEFAULT_INDEXED: List<Int> = listOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
            0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
            0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
            0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
            0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
            0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
        ).map { it or (0xFF shl 24) }

        /** A minimal style sheet for new workbooks. */
        fun minimalXml(): String =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<styleSheet xmlns="${Namespaces.S}">""" +
                """<fonts count="1"><font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font></fonts>""" +
                """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
                """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
                """<dxfs count="0"/><tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>""" +
                """</styleSheet>"""

        fun minimalDocument() = XmlParser.parse(minimalXml())
    }
}

class XlsxEditException(message: String) : Exception(message)
