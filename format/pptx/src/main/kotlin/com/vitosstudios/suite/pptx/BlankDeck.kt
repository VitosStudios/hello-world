package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.xml.Namespaces

/**
 * The package written for "New presentation": a 16:9 deck with an original theme, one slide
 * master, four layouts (Title Slide, Title and Content, Title Only, Blank) and one title slide.
 * Everything is generated here; no third-party template is bundled.
 */
internal object BlankDeck {
    private const val HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n"
    private const val NS = """xmlns:a="${Namespaces.A}" xmlns:r="${Namespaces.R}" xmlns:p="${Namespaces.P}""""
    private const val PML = "application/vnd.openxmlformats-officedocument.presentationml"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    const val WIDTH = 12192000L
    const val HEIGHT = 6858000L

    private val layouts = listOf(
        Triple("Title Slide", "title", titleSlideShapes()),
        Triple("Title and Content", "obj", titleAndContentShapes()),
        Triple("Title Only", "titleOnly", titleOnlyShapes()),
        Triple("Blank", "blank", ""),
    )

    fun entries(): Map<String, ByteArray> {
        val m = LinkedHashMap<String, String>()
        m["[Content_Types].xml"] = contentTypes()
        m["_rels/.rels"] = rels(
            "rId1" to ("$REL/officeDocument" to "ppt/presentation.xml"),
            "rId2" to ("http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" to "docProps/core.xml"),
        )
        m["docProps/core.xml"] = HEADER + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title></dc:title></cp:coreProperties>"""
        m["ppt/presentation.xml"] = presentation()
        m["ppt/_rels/presentation.xml.rels"] = rels(
            "rId1" to ("$REL/slideMaster" to "slideMasters/slideMaster1.xml"),
            "rId2" to ("$REL/slide" to "slides/slide1.xml"),
            "rId3" to ("$REL/presProps" to "presProps.xml"),
            "rId4" to ("$REL/viewProps" to "viewProps.xml"),
            "rId5" to ("$REL/theme" to "theme/theme1.xml"),
            "rId6" to ("$REL/tableStyles" to "tableStyles.xml"),
        )
        m["ppt/presProps.xml"] = HEADER + """<p:presentationPr $NS/>"""
        m["ppt/viewProps.xml"] = HEADER + """<p:viewPr $NS><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr>""" +
            """<p:gridSpacing cx="76200" cy="76200"/></p:viewPr>"""
        m["ppt/tableStyles.xml"] = HEADER + """<a:tblStyleLst xmlns:a="${Namespaces.A}" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>"""
        m["ppt/theme/theme1.xml"] = themeXml()
        m["ppt/slideMasters/slideMaster1.xml"] = master()
        m["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = rels(
            *(layouts.indices.map { i -> "rId${i + 1}" to ("$REL/slideLayout" to "../slideLayouts/slideLayout${i + 1}.xml") } +
                listOf("rId${layouts.size + 1}" to ("$REL/theme" to "../theme/theme1.xml"))).toTypedArray(),
        )
        for ((i, l) in layouts.withIndex()) {
            m["ppt/slideLayouts/slideLayout${i + 1}.xml"] = layout(l.first, l.second, l.third)
            m["ppt/slideLayouts/_rels/slideLayout${i + 1}.xml.rels"] = rels("rId1" to ("$REL/slideMaster" to "../slideMasters/slideMaster1.xml"))
        }
        m["ppt/slides/slide1.xml"] = HEADER + """<p:sld $NS><p:cSld><p:spTree>$GROUP""" +
            placeholderSp(2, "Title 1", """<p:ph type="ctrTitle"/>""", "") +
            placeholderSp(3, "Subtitle 2", """<p:ph type="subTitle" idx="1"/>""", "") +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
        m["ppt/slides/_rels/slide1.xml.rels"] = rels("rId1" to ("$REL/slideLayout" to "../slideLayouts/slideLayout1.xml"))
        return m.mapValues { it.value.toByteArray(Charsets.UTF_8) }
    }

    private const val GROUP = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
        """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

    private fun contentTypes(): String {
        val overrides = mutableListOf(
            "/ppt/presentation.xml" to "$PML.presentation.main+xml",
            "/ppt/presProps.xml" to "$PML.presProps+xml",
            "/ppt/viewProps.xml" to "$PML.viewProps+xml",
            "/ppt/tableStyles.xml" to "$PML.tableStyles+xml",
            "/ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml",
            "/ppt/slideMasters/slideMaster1.xml" to "$PML.slideMaster+xml",
            "/ppt/slides/slide1.xml" to "$PML.slide+xml",
            "/docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml",
        )
        layouts.indices.forEach { overrides += "/ppt/slideLayouts/slideLayout${it + 1}.xml" to "$PML.slideLayout+xml" }
        return HEADER + """<Types xmlns="${Namespaces.CONTENT_TYPES}">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            overrides.joinToString("") { (n, t) -> """<Override PartName="$n" ContentType="$t"/>""" } + "</Types>"
    }

    private fun rels(vararg items: Pair<String, Pair<String, String>>): String =
        HEADER + """<Relationships xmlns="${Namespaces.PKG_RELATIONSHIPS}">""" +
            items.joinToString("") { (id, t) -> """<Relationship Id="$id" Type="${t.first}" Target="${t.second}"/>""" } +
            "</Relationships>"

    private fun levels(sizes: List<Int>, bullets: Boolean, font: String = "+mn"): String = buildString {
        for ((i, sz) in sizes.withIndex()) {
            val marL = if (bullets) 228600 + i * 457200 else i * 457200
            append("""<a:lvl${i + 1}pPr marL="$marL" """)
            if (bullets) append("""indent="-228600" """)
            append("""algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1">""")
            if (bullets) {
                append("""<a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:spcBef><a:spcPts val="${if (i == 0) 1000 else 500}"/></a:spcBef>""")
                append("""<a:buFont typeface="Arial"/><a:buChar char="${if (i % 2 == 0) "•" else "–"}"/>""")
            }
            append("""<a:defRPr sz="$sz" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill>""")
            append("""<a:latin typeface="$font-lt"/><a:ea typeface="$font-ea"/><a:cs typeface="$font-cs"/></a:defRPr></a:lvl${i + 1}pPr>""")
        }
    }

    private fun presentation(): String = HEADER + """<p:presentation $NS saveSubsetFonts="1">""" +
        """<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""" +
        """<p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>""" +
        """<p:sldSz cx="$WIDTH" cy="$HEIGHT"/><p:notesSz cx="6858000" cy="9144000"/>""" +
        """<p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr>${levels(List(9) { 1800 }, bullets = false)}</p:defaultTextStyle>""" +
        "</p:presentation>"

    private fun placeholderSp(id: Int, name: String, ph: String, spPr: String, bodyPr: String = "<a:bodyPr/>", lstStyle: String = "<a:lstStyle/>", text: String = ""): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>""" +
            (if (spPr.isEmpty()) "<p:spPr/>" else "<p:spPr>$spPr</p:spPr>") +
            "<p:txBody>$bodyPr$lstStyle" +
            (if (text.isEmpty()) """<a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p>""" else text) +
            "</p:txBody></p:sp>"

    private fun xfrm(x: Long, y: Long, cx: Long, cy: Long) =
        """<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""

    private fun para(text: String, lvl: Int = 0) =
        """<a:p>${if (lvl > 0) """<a:pPr lvl="$lvl"/>""" else ""}<a:r><a:rPr lang="en-US"/><a:t>$text</a:t></a:r></a:p>"""

    private fun master(): String {
        val body = (0 until 5).joinToString("") { para(listOf("First level", "Second level", "Third level", "Fourth level", "Fifth level")[it], it) }
        return HEADER + """<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$GROUP""" +
            placeholderSp(2, "Title Placeholder 1", """<p:ph type="title"/>""", xfrm(838200, 365125, 10515600, 1325563),
                """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0" anchor="ctr"><a:normAutofit/></a:bodyPr>""",
                text = para("Title")) +
            placeholderSp(3, "Text Placeholder 2", """<p:ph type="body" idx="1"/>""", xfrm(838200, 1825625, 10515600, 4351338),
                """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0"><a:normAutofit/></a:bodyPr>""",
                text = body) +
            placeholderSp(4, "Date Placeholder 3", """<p:ph type="dt" sz="half" idx="2"/>""", xfrm(838200, 6356350, 2743200, 365125),
                """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0" anchor="ctr"/>""",
                """<a:lstStyle><a:lvl1pPr algn="l"><a:defRPr sz="1200"><a:solidFill><a:schemeClr val="tx1"><a:tint val="75000"/></a:schemeClr></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle>""") +
            placeholderSp(5, "Footer Placeholder 4", """<p:ph type="ftr" sz="quarter" idx="3"/>""", xfrm(4038600, 6356350, 4114800, 365125),
                """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0" anchor="ctr"/>""",
                """<a:lstStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="1200"><a:solidFill><a:schemeClr val="tx1"><a:tint val="75000"/></a:schemeClr></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle>""") +
            placeholderSp(6, "Slide Number Placeholder 5", """<p:ph type="sldNum" sz="quarter" idx="4"/>""", xfrm(8610600, 6356350, 2743200, 365125),
                """<a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0" anchor="ctr"/>""",
                """<a:lstStyle><a:lvl1pPr algn="r"><a:defRPr sz="1200"><a:solidFill><a:schemeClr val="tx1"><a:tint val="75000"/></a:schemeClr></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle>""",
                """<a:p><a:fld id="{8B3C2E4F-6A1D-4C7B-9E2A-5D4F3B2A1C0E}" type="slidenum"><a:rPr lang="en-US"/><a:t>‹#›</a:t></a:fld><a:endParaRPr lang="en-US"/></a:p>""") +
            """</p:spTree></p:cSld>""" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
            "<p:sldLayoutIdLst>" + layouts.indices.joinToString("") { """<p:sldLayoutId id="${2147483649L + it}" r:id="rId${it + 1}"/>""" } + "</p:sldLayoutIdLst>" +
            """<p:txStyles><p:titleStyle><a:lvl1pPr algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1">""" +
            """<a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:spcBef><a:spcPct val="0"/></a:spcBef><a:buNone/>""" +
            """<a:defRPr sz="4400" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/><a:ea typeface="+mj-ea"/><a:cs typeface="+mj-cs"/></a:defRPr></a:lvl1pPr></p:titleStyle>""" +
            "<p:bodyStyle>${levels(listOf(2800, 2400, 2000, 1800, 1800, 1800, 1800, 1800, 1800), bullets = true)}</p:bodyStyle>" +
            """<p:otherStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr>${levels(List(9) { 1800 }, bullets = false)}</p:otherStyle></p:txStyles>""" +
            "</p:sldMaster>"
    }

    private fun titleSlideShapes(): String =
        placeholderSp(2, "Title 1", """<p:ph type="ctrTitle"/>""", xfrm(1524000, 1122363, 9144000, 2387600),
            """<a:bodyPr anchor="b"/>""",
            """<a:lstStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="6000"/></a:lvl1pPr></a:lstStyle>""", para("Presentation title")) +
            placeholderSp(3, "Subtitle 2", """<p:ph type="subTitle" idx="1"/>""", xfrm(1524000, 3602038, 9144000, 1655762),
                "<a:bodyPr/>",
                """<a:lstStyle><a:lvl1pPr marL="0" indent="0" algn="ctr"><a:buNone/><a:defRPr sz="2400"/></a:lvl1pPr></a:lstStyle>""", para("Subtitle"))

    private fun titleAndContentShapes(): String =
        placeholderSp(2, "Title 1", """<p:ph type="title"/>""", "", text = para("Title")) +
            placeholderSp(3, "Content Placeholder 2", """<p:ph idx="1"/>""", "", text = para("First level") + para("Second level", 1))

    private fun titleOnlyShapes(): String =
        placeholderSp(2, "Title 1", """<p:ph type="title"/>""", "", text = para("Title"))

    private fun layout(name: String, type: String, shapes: String): String =
        HEADER + """<p:sldLayout $NS type="$type" preserve="1"><p:cSld name="$name"><p:spTree>$GROUP$shapes</p:spTree></p:cSld>""" +
            """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    /** An original theme ("Suite"): neutral text colors and six accents. */
    fun themeXml(): String {
        fun srgb(v: String) = """<a:srgbClr val="$v"/>"""
        val colors = """<a:clrScheme name="Suite"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
            "<a:dk2>${srgb("1F2A44")}</a:dk2><a:lt2>${srgb("EEF1F5")}</a:lt2>" +
            "<a:accent1>${srgb("3B6FD8")}</a:accent1><a:accent2>${srgb("E0763A")}</a:accent2><a:accent3>${srgb("3BA676")}</a:accent3>" +
            "<a:accent4>${srgb("B0458F")}</a:accent4><a:accent5>${srgb("2AA7B8")}</a:accent5><a:accent6>${srgb("D9A929")}</a:accent6>" +
            "<a:hlink>${srgb("2F5FD0")}</a:hlink><a:folHlink>${srgb("7B4FA0")}</a:folHlink></a:clrScheme>"
        val fonts = """<a:fontScheme name="Suite"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>"""
        val solid = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val gradient = """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:lumMod val="110000"/></a:schemeClr></a:gs>""" +
            """<a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="90000"/></a:schemeClr></a:gs></a:gsLst><a:lin ang="5400000" scaled="0"/></a:gradFill>"""
        fun line(w: Int) = """<a:ln w="$w" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/><a:miter lim="800000"/></a:ln>"""
        val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
        val fmt = """<a:fmtScheme name="Suite"><a:fillStyleLst>$solid$gradient$gradient</a:fillStyleLst>""" +
            "<a:lnStyleLst>${line(6350)}${line(12700)}${line(19050)}</a:lnStyleLst>" +
            "<a:effectStyleLst>$effect$effect$effect</a:effectStyleLst>" +
            """<a:bgFillStyleLst>$solid<a:solidFill><a:schemeClr val="phClr"><a:tint val="95000"/></a:schemeClr></a:solidFill>$gradient</a:bgFillStyleLst></a:fmtScheme>"""
        return HEADER + """<a:theme xmlns:a="${Namespaces.A}" name="Suite"><a:themeElements>$colors$fonts$fmt</a:themeElements>""" +
            "<a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }
}
