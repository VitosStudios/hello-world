package com.vitosstudios.suite.docx

import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement

/** WordprocessingML names and schema sequences used when the app creates elements. */
internal object Wml {
    const val W = Namespaces.W
    const val R = Namespaces.R

    /** CT_PPr / CT_PPrBase children in schema order. */
    val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
        "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap",
        "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
        "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
        "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr",
        "sectPr", "pPrChange",
    )

    /** CT_RPr children in schema order. */
    val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
        "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing",
        "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText",
        "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath", "rPrChange",
    )

    /** CT_SectPr children in schema order (header/footer references come first, in any order). */
    val SECTPR_ORDER = listOf(
        "headerReference", "footerReference", "footnotePr", "endnotePr", "type", "pgSz", "pgMar",
        "paperSrc", "pgBorders", "lnNumType", "pgNumType", "cols", "formProt", "vAlign", "noEndnote",
        "titlePg", "textDirection", "bidi", "rtlGutter", "docGrid", "printerSettings", "sectPrChange",
    )

    val TBLPR_ORDER = listOf(
        "tblStyle", "tblpPr", "tblOverlap", "bidiVisual", "tblStyleRowBandSize", "tblStyleColBandSize",
        "tblW", "jc", "tblCellSpacing", "tblInd", "tblBorders", "shd", "tblLayout", "tblCellMar", "tblLook",
        "tblCaption", "tblDescription", "tblPrChange",
    )

    val TCPR_ORDER = listOf(
        "cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar",
        "textDirection", "tcFitText", "vAlign", "hideMark", "headers", "cellIns", "cellDel", "cellMerge", "tcPrChange",
    )

    /** CT_Style children in schema order. */
    val STYLE_ORDER = listOf(
        "name", "aliases", "basedOn", "next", "link", "autoRedefine", "hidden", "uiPriority", "semiHidden",
        "unhideWhenUsed", "qFormat", "locked", "personal", "personalCompose", "personalReply", "rsid", "pPr",
        "rPr", "tblPr", "trPr", "tcPr", "tblStylePr",
    )

    /** CT_Lvl children in schema order. */
    val LVL_ORDER = listOf(
        "start", "numFmt", "lvlRestart", "pStyle", "isLgl", "suff", "lvlText", "lvlPicBulletId", "legacy",
        "lvlJc", "pPr", "rPr",
    )

    /** CT_Numbering children: all abstractNum before all num. */
    val NUMBERING_ORDER = listOf("numPicBullet", "abstractNum", "num", "numIdMacAtCleanup")

    /** CT_Body: block content, then sectPr. */
    const val SECT_PR = "sectPr"
}

internal fun XElement.w(local: String): XElement? = element(Wml.W, local)
internal fun XElement.ws(local: String): Sequence<XElement> = elements(Wml.W, local)
internal fun XElement.wAttr(local: String): String? = attr(Wml.W, local)
internal fun XElement.isW(local: String): Boolean = isA(Wml.W, local)

/** Reads an ST_OnOff property element (`<w:b/>`, `<w:b w:val="false"/>`). Null when absent. */
internal fun XElement.onOff(local: String): Boolean? {
    val e = w(local) ?: return null
    return when (e.wAttr("val")) {
        null, "1", "true", "on" -> true
        else -> false
    }
}

internal fun XElement.newW(local: String): XElement = createChild(Wml.W, local)

/** Builds a detached `w:` element in the scope of [context] with attributes. */
internal fun XElement.wElement(local: String, vararg attrs: Pair<String, String>): XElement {
    val e = createChild(Wml.W, local)
    for ((k, v) in attrs) e.setNsAttr(this, Wml.W, k, v)
    return e
}

/**
 * Sets a namespaced attribute on a (possibly detached) element using the prefix bound at
 * [context], so that it resolves once the element is inserted there. Declares a prefix only if
 * [context] has none for [nsUri].
 */
internal fun XElement.setNsAttr(context: XElement, nsUri: String, local: String, value: String) {
    val existing = attribute(nsUri, local)
    if (existing != null) {
        setAttribute(existing.qName, value)
        return
    }
    val p = context.lookupPrefix(nsUri)
    if (!p.isNullOrEmpty()) setAttribute("$p:$local", value) else setAttr(nsUri, local, value)
}

object Units {
    const val EMU_PER_INCH = 914400L
    const val TWIPS_PER_INCH = 1440
    const val EMU_PER_TWIP = 635L
    const val EMU_PER_POINT = 12700L

    fun twipsToPoints(twips: Int): Float = twips / 20f
    fun emuToPoints(emu: Long): Float = emu / EMU_PER_POINT.toFloat()
    fun pointsToEmu(points: Float): Long = (points * EMU_PER_POINT).toLong()
}
