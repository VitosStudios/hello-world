package com.vitosstudios.suite.pptx

/**
 * Child-element sequences from the PresentationML and DrawingML schemas (ECMA-376 Part 1).
 * New elements are inserted with [com.vitosstudios.suite.xml.XElement.insertInOrder] so the
 * result stays schema-valid; strict consumers reject out-of-order children.
 */
internal object Schema {
    /** CT_TextCharacterProperties: a:rPr, a:defRPr, a:endParaRPr. */
    val RUN_PROPERTIES = listOf(
        "ln", "noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill", "effectLst", "effectDag",
        "highlight", "uLnTx", "uLn", "uFillTx", "uFill", "latin", "ea", "cs", "sym", "hlinkClick",
        "hlinkMouseOver", "rtl", "extLst",
    )

    /** CT_TextParagraphProperties: a:pPr and a:lvlNpPr. */
    val PARAGRAPH_PROPERTIES = listOf(
        "lnSpc", "spcBef", "spcAft", "buClrTx", "buClr", "buSzTx", "buSzPct", "buSzPts", "buFontTx", "buFont",
        "buNone", "buAutoNum", "buChar", "buBlip", "tabLst", "defRPr", "extLst",
    )

    /** CT_TextParagraph: a:p. Runs, breaks and fields share one rank. */
    val PARAGRAPH = listOf("pPr", "r", "br", "fld", "endParaRPr")

    /** CT_ShapeProperties: p:spPr. */
    val SHAPE_PROPERTIES = listOf(
        "xfrm", "custGeom", "prstGeom", "noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill",
        "ln", "effectLst", "effectDag", "scene3d", "sp3d", "extLst",
    )

    /** CT_LineProperties: a:ln. */
    val LINE_PROPERTIES = listOf(
        "noFill", "solidFill", "gradFill", "pattFill", "prstDash", "custDash", "round", "bevel", "miter",
        "headEnd", "tailEnd", "extLst",
    )

    /** CT_Shape: p:sp. */
    val SHAPE = listOf("nvSpPr", "spPr", "style", "txBody", "extLst")

    /** CT_Slide: p:sld. */
    val SLIDE = listOf("cSld", "clrMapOvr", "transition", "timing", "extLst")

    /** CT_NotesSlide: p:notes. */
    val NOTES = listOf("cSld", "clrMapOvr", "extLst")

    /** CT_CommonSlideData: p:cSld. */
    val COMMON_SLIDE_DATA = listOf("bg", "spTree", "custDataLst", "controls", "extLst")

    /** CT_Presentation: p:presentation. */
    val PRESENTATION = listOf(
        "sldMasterIdLst", "notesMasterIdLst", "handoutMasterIdLst", "sldIdLst", "sldSz", "notesSz", "smartTags",
        "embeddedFontLst", "custShowLst", "photoAlbum", "custDataLst", "kinsoku", "defaultTextStyle",
        "modifyVerifier", "extLst",
    )

    /** Fill choice shared by spPr, rPr and ln. */
    val FILLS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

    val BULLET_TYPES = setOf("buNone", "buAutoNum", "buChar", "buBlip")
}
