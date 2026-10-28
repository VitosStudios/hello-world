package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser

/** Character formatting after resolving defaults, styles and direct formatting. */
data class RunFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points. */
    val sizePt: Float = 11f,
    /** RGB hex without '#', or null for automatic. */
    val color: String? = null,
    /** Highlight colour name (ST_HighlightColor) or null. */
    val highlight: String? = null,
    val fontFamily: String? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
    val hidden: Boolean = false,
    val characterStyleId: String? = null,
)

enum class Alignment { LEFT, CENTER, RIGHT, JUSTIFY }

/** Paragraph formatting after resolving defaults and the style chain. Lengths in points. */
data class ParagraphFormat(
    val alignment: Alignment = Alignment.LEFT,
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    /** Positive: first-line indent; negative: hanging indent. */
    val indentFirstLine: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Line spacing multiple (1.0 = single) when [lineExactPt] is null. */
    val lineMultiple: Float = 1f,
    /** Exact or minimum line height in points. */
    val lineExactPt: Float? = null,
    val keepWithNext: Boolean = false,
    val pageBreakBefore: Boolean = false,
    /** 0-based outline level (headings), or null. */
    val outlineLevel: Int? = null,
    val numId: String? = null,
    val ilvl: Int = 0,
    val styleId: String? = null,
    val shading: String? = null,
)

data class StyleInfo(
    val styleId: String,
    val name: String,
    /** paragraph, character, table or numbering */
    val type: String,
    val basedOn: String?,
    val next: String?,
    val isDefault: Boolean,
    val hidden: Boolean,
    val uiPriority: Int?,
    val quickFormat: Boolean,
    /** 1-6 for heading styles, otherwise null. */
    val headingLevel: Int?,
)

/**
 * The document's styles part (word/styles.xml). Resolution follows the usual cascade:
 * document defaults -> paragraph style chain -> character style chain -> direct formatting.
 */
class DocxStyles internal constructor(private val doc: DocxDocument) {
    private val part: Part? get() = doc.pkg.related(doc.mainPart, RelTypes.STYLES)
    private val root: XElement? get() = part?.xmlOrNull()?.root

    private var cacheVersion = -1L
    private var cache: Map<String, XElement> = emptyMap()

    private fun styleElements(): Map<String, XElement> {
        val r = root ?: return emptyMap()
        val v = r.document?.modCount ?: 0
        if (v == cacheVersion && cache.isNotEmpty()) return cache
        cache = r.ws("style").mapNotNull { e -> e.wAttr("styleId")?.let { it to e } }.toMap()
        cacheVersion = v
        return cache
    }

    fun all(): List<StyleInfo> = styleElements().values.map(::info)

    fun style(styleId: String): StyleInfo? = styleElements()[styleId]?.let(::info)

    /** Paragraph styles worth offering in a picker, most relevant first. */
    fun paragraphStylesForPicker(): List<StyleInfo> = all()
        .filter { it.type == "paragraph" && !it.hidden }
        .sortedWith(compareBy<StyleInfo>({ it.headingLevel ?: if (it.isDefault) 0 else 99 }, { it.uiPriority ?: 99 }, { it.name }))

    val defaultParagraphStyleId: String?
        get() = styleElements().values.firstOrNull { it.wAttr("type") == "paragraph" && it.isDefaultStyle() }?.wAttr("styleId")

    private fun XElement.isDefaultStyle(): Boolean = wAttr("default").let { it == "1" || it == "true" || it == "on" }

    private fun info(e: XElement): StyleInfo {
        val id = e.wAttr("styleId") ?: ""
        val name = e.w("name")?.wAttr("val") ?: id
        val type = e.wAttr("type") ?: "paragraph"
        return StyleInfo(
            styleId = id, name = name, type = type,
            basedOn = e.w("basedOn")?.wAttr("val"),
            next = e.w("next")?.wAttr("val"),
            isDefault = e.isDefaultStyle(),
            hidden = e.onOff("hidden") == true || e.onOff("semiHidden") == true,
            uiPriority = e.w("uiPriority")?.wAttr("val")?.toIntOrNull(),
            quickFormat = e.onOff("qFormat") == true,
            headingLevel = if (type == "paragraph") headingLevelOf(e, id, name) else null,
        )
    }

    private fun headingLevelOf(e: XElement, id: String, name: String): Int? {
        Regex("""^heading\s*([1-9])$""", RegexOption.IGNORE_CASE).find(name.trim())?.let { return it.groupValues[1].toInt() }
        Regex("""^heading([1-9])$""", RegexOption.IGNORE_CASE).find(id)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** The document's own style for heading [level] (1-6), if it has one. */
    fun headingStyleId(level: Int): String? = all().firstOrNull { it.headingLevel == level }?.styleId

    /**
     * Returns the heading style id for [level], adding a heading style to the styles part if the
     * document has none (based on the default paragraph style, so it matches the document).
     */
    fun ensureHeadingStyle(level: Int): String {
        headingStyleId(level)?.let { return it }
        val r = root ?: createStylesPart()
        var id = "Heading$level"
        while (styleElements().containsKey(id)) id += "x"
        val sizes = listOf(16f, 13f, 12f, 11f, 11f, 11f)
        val base = defaultParagraphStyleId
        val xml = buildString {
            append("""<w:style xmlns:w="${Wml.W}" w:type="paragraph" w:styleId="$id">""")
            append("""<w:name w:val="heading $level"/>""")
            if (base != null) append("""<w:basedOn w:val="$base"/>""")
            if (base != null) append("""<w:next w:val="$base"/>""")
            append("""<w:uiPriority w:val="9"/><w:qFormat/>""")
            append("""<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="240" w:after="60"/><w:outlineLvl w:val="${level - 1}"/></w:pPr>""")
            append("""<w:rPr><w:b/><w:sz w:val="${(sizes[level - 1] * 2).toInt()}"/></w:rPr>""")
            append("</w:style>")
        }
        val e = XmlParser.parse(xml).root.deepCopy()
        stripRedundantNamespace(e, r)
        r.appendChild(e)
        return id
    }

    /** Character style used for new hyperlinks, if the document defines one. */
    fun hyperlinkStyleId(): String? =
        all().firstOrNull { it.type == "character" && (it.name.equals("Hyperlink", true) || it.styleId == "Hyperlink") }?.styleId

    /** Table style with grid borders, if the document defines one. */
    fun tableGridStyleId(): String? =
        all().firstOrNull { it.type == "table" && (it.name.equals("Table Grid", true) || it.styleId == "TableGrid") }?.styleId

    /** Paragraph style for bulleted/numbered lists ("List Bullet"/"List Number"), if present. */
    fun listStyleId(numbered: Boolean): String? {
        val wanted = if (numbered) "list number" else "list bullet"
        return all().firstOrNull { it.type == "paragraph" && it.name.equals(wanted, true) }?.styleId
    }

    // ------------------------------------------------------------------------------------
    // Resolution

    private fun docDefaultsRPr(): XElement? = root?.w("docDefaults")?.w("rPrDefault")?.w("rPr")
    private fun docDefaultsPPr(): XElement? = root?.w("docDefaults")?.w("pPrDefault")?.w("pPr")

    /** Style chain from the most basic ancestor to [styleId] itself. */
    internal fun chain(styleId: String?): List<XElement> {
        val out = ArrayList<XElement>()
        var id = styleId
        val seen = HashSet<String>()
        val map = styleElements()
        while (id != null && seen.add(id)) {
            val e = map[id] ?: break
            out.add(0, e)
            id = e.w("basedOn")?.wAttr("val")
        }
        return out
    }

    /** Resolves character formatting of a run inside a paragraph with [paragraphStyleId]. */
    fun resolveRun(paragraphStyleId: String?, rPr: XElement?): RunFormat {
        val pStyle = paragraphStyleId ?: defaultParagraphStyleId
        val layers = ArrayList<XElement>()
        docDefaultsRPr()?.let(layers::add)
        for (s in chain(pStyle)) s.w("rPr")?.let(layers::add)
        val rStyle = rPr?.w("rStyle")?.wAttr("val")
        for (s in chain(rStyle)) s.w("rPr")?.let(layers::add)
        rPr?.let(layers::add)
        var f = RunFormat(sizePt = 10f, characterStyleId = rStyle)
        for (l in layers) f = applyRPr(f, l)
        return f
    }

    internal fun applyRPr(base: RunFormat, rPr: XElement): RunFormat {
        var f = base
        rPr.onOff("b")?.let { f = f.copy(bold = it) }
        rPr.onOff("i")?.let { f = f.copy(italic = it) }
        rPr.onOff("strike")?.let { f = f.copy(strike = it) }
        rPr.onOff("dstrike")?.let { if (it) f = f.copy(strike = true) }
        rPr.onOff("caps")?.let { f = f.copy(caps = it) }
        rPr.onOff("smallCaps")?.let { f = f.copy(smallCaps = it) }
        rPr.onOff("vanish")?.let { f = f.copy(hidden = it) }
        rPr.w("u")?.let { f = f.copy(underline = (it.wAttr("val") ?: "single") != "none") }
        rPr.w("sz")?.wAttr("val")?.toFloatOrNull()?.let { f = f.copy(sizePt = it / 2f) }
        rPr.w("color")?.wAttr("val")?.let { f = f.copy(color = if (it.equals("auto", true)) null else it) }
        rPr.w("highlight")?.wAttr("val")?.let { f = f.copy(highlight = if (it == "none") null else it) }
        rPr.w("shd")?.wAttr("fill")?.let { if (!it.equals("auto", true) && f.highlight == null) f = f.copy(highlight = "#$it") }
        rPr.w("vertAlign")?.wAttr("val")?.let {
            f = f.copy(superscript = it == "superscript", subscript = it == "subscript")
        }
        rPr.w("rFonts")?.let { rf ->
            val name = rf.wAttr("ascii") ?: rf.wAttr("hAnsi")
                ?: (rf.wAttr("asciiTheme") ?: rf.wAttr("hAnsiTheme"))?.let { doc.theme.font(it) }
            if (name != null) f = f.copy(fontFamily = name)
        }
        return f
    }

    /** Resolves paragraph formatting from defaults, the style chain and direct [pPr]. */
    fun resolveParagraph(pPr: XElement?): ParagraphFormat {
        val styleId = pPr?.w("pStyle")?.wAttr("val") ?: defaultParagraphStyleId
        val layers = ArrayList<XElement>()
        docDefaultsPPr()?.let(layers::add)
        for (s in chain(styleId)) s.w("pPr")?.let(layers::add)
        pPr?.let(layers::add)
        var f = ParagraphFormat(styleId = styleId)
        for (l in layers) f = applyPPr(f, l)
        if (f.outlineLevel == null) {
            style(styleId ?: "")?.headingLevel?.let { f = f.copy(outlineLevel = it - 1) }
        }
        return f
    }

    private fun applyPPr(base: ParagraphFormat, pPr: XElement): ParagraphFormat {
        var f = base
        pPr.w("jc")?.wAttr("val")?.let {
            f = f.copy(alignment = when (it) {
                "center" -> Alignment.CENTER
                "right", "end" -> Alignment.RIGHT
                "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> Alignment.JUSTIFY
                else -> Alignment.LEFT
            })
        }
        pPr.w("ind")?.let { ind ->
            (ind.wAttr("left") ?: ind.wAttr("start"))?.toIntOrNull()?.let { f = f.copy(indentLeft = Units.twipsToPoints(it)) }
            (ind.wAttr("right") ?: ind.wAttr("end"))?.toIntOrNull()?.let { f = f.copy(indentRight = Units.twipsToPoints(it)) }
            ind.wAttr("firstLine")?.toIntOrNull()?.let { f = f.copy(indentFirstLine = Units.twipsToPoints(it)) }
            ind.wAttr("hanging")?.toIntOrNull()?.let { f = f.copy(indentFirstLine = -Units.twipsToPoints(it)) }
        }
        pPr.w("spacing")?.let { sp ->
            sp.wAttr("before")?.toIntOrNull()?.let { f = f.copy(spaceBefore = Units.twipsToPoints(it)) }
            sp.wAttr("after")?.toIntOrNull()?.let { f = f.copy(spaceAfter = Units.twipsToPoints(it)) }
            sp.wAttr("line")?.toIntOrNull()?.let { line ->
                f = when (sp.wAttr("lineRule") ?: "auto") {
                    "auto" -> f.copy(lineMultiple = line / 240f, lineExactPt = null)
                    else -> f.copy(lineExactPt = Units.twipsToPoints(line))
                }
            }
        }
        pPr.onOff("keepNext")?.let { f = f.copy(keepWithNext = it) }
        pPr.onOff("pageBreakBefore")?.let { f = f.copy(pageBreakBefore = it) }
        pPr.w("outlineLvl")?.wAttr("val")?.toIntOrNull()?.let { f = f.copy(outlineLevel = if (it in 0..8) it else null) }
        pPr.w("numPr")?.let { np ->
            np.w("numId")?.wAttr("val")?.let { f = f.copy(numId = it) }
            np.w("ilvl")?.wAttr("val")?.toIntOrNull()?.let { f = f.copy(ilvl = it) }
        }
        pPr.w("shd")?.wAttr("fill")?.let { f = f.copy(shading = if (it.equals("auto", true)) null else it) }
        return f
    }

    private fun createStylesPart(): XElement {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n" +
            """<w:styles xmlns:w="${Wml.W}"><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style></w:styles>"""
        val name = doc.pkg.uniquePartName("/word/styles", "xml").let { if (doc.pkg.part("/word/styles.xml") == null) "/word/styles.xml" else it }
        doc.pkg.addXmlPart(name, ContentTypeNames.WML_STYLES, XmlParser.parse(xml))
        doc.pkg.relationships(doc.mainPart).addTo(RelTypes.STYLES, name)
        return doc.pkg.part(name)!!.xml().root
    }
}

/** Removes an `xmlns:w` declaration that duplicates the one in scope at [target]. */
internal fun stripRedundantNamespace(e: XElement, target: XElement) {
    for (a in e.attributes.toList()) {
        if (!a.isNamespaceDeclaration) continue
        val prefix = if (a.qName == "xmlns") null else a.qName.substring(6)
        val inScope = target.lookupNamespaceUri(prefix)
        if (inScope != null && Namespaces.canonical(inScope) == Namespaces.canonical(a.value)) e.removeAttribute(a.qName)
    }
}

/** Theme fonts (major/minor latin typefaces) for resolving `asciiTheme`. */
class DocxTheme internal constructor(private val doc: DocxDocument) {
    private val fonts: Map<String, String> by lazy {
        val theme = doc.pkg.related(doc.mainPart, RelTypes.THEME)?.xmlOrNull()?.root ?: return@lazy emptyMap()
        val fs = theme.descendants(Namespaces.A, "fontScheme").firstOrNull() ?: return@lazy emptyMap()
        val major = fs.element(Namespaces.A, "majorFont")?.element(Namespaces.A, "latin")?.attr("typeface")
        val minor = fs.element(Namespaces.A, "minorFont")?.element(Namespaces.A, "latin")?.attr("typeface")
        buildMap {
            major?.let { put("major", it) }
            minor?.let { put("minor", it) }
        }
    }

    /** Maps ST_Theme values such as "minorHAnsi" or "majorAscii" to a typeface. */
    fun font(themeValue: String): String? = when {
        themeValue.startsWith("major") -> fonts["major"]
        themeValue.startsWith("minor") -> fonts["minor"]
        else -> null
    }
}
