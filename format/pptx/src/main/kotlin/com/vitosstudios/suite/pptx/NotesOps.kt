package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.PartNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.Namespaces.R
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlCodec
import com.vitosstudios.suite.xml.XmlParser

/** Speaker notes: the body placeholder of a slide's notes page. */
internal class NotesOps(private val pres: PptxPresentation) {
    private val pkg get() = pres.pkg

    private fun notesPart(slide: Int): Part? = pkg.related(pres.slidePart(slide), RelTypes.NOTES_SLIDE)

    private fun bodyShape(root: XElement): XElement? =
        root.element(P, "cSld")?.element(P, "spTree")?.elements(P, "sp")?.firstOrNull { sp ->
            sp.element(P, "nvSpPr")?.element(P, "nvPr")?.element(P, "ph")?.attr("type") == "body"
        }

    fun text(slide: Int): String {
        val root = readRoot(notesPart(slide)) ?: return ""
        val body = bodyShape(root)?.element(P, "txBody") ?: return ""
        return body.elements(A, "p").joinToString("\n") { pres.text.textOf(it).replace(LINE_BREAK, '\n') }
    }

    fun setText(slide: Int, value: String) {
        if (text(slide) == value) return
        val part = notesPart(slide) ?: createNotesSlide(slide)
        val root = part.xml().root
        val shape = bodyShape(root) ?: addBodyPlaceholder(root)
        val body = shape.element(P, "txBody") ?: Fragments.build(
            """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody>""", shape,
        ).also { shape.insertInOrder(it, Schema.SHAPE) }
        pres.text.setBodyText(body, value)
    }

    private fun addBodyPlaceholder(root: XElement): XElement {
        val tree = root.element(P, "cSld")!!.element(P, "spTree")!!
        val id = (root.descendants().filter { it.localName == "cNvPr" }.maxOfOrNull { it.attr("id")?.toIntOrNull() ?: 0 } ?: 1) + 1
        val sp = Fragments.build(
            """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Notes Placeholder ${id - 1}"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""" +
                """<p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>""" +
                """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody></p:sp>""",
            tree,
        )
        tree.appendChild(sp)
        return sp
    }

    private fun createNotesSlide(slide: Int): Part {
        val master = ensureNotesMaster()
        val slidePart = pres.slidePart(slide)
        val name = pkg.uniquePartName("/ppt/notesSlides/notesSlide", "xml")
        val xml = HEADER + """<p:notes xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Slide Image Placeholder 1"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr>""" +
            """<p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Notes Placeholder 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""" +
            """<p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>""" +
            """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody></p:sp>""" +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"""
        val part = pkg.addXmlPart(name, ContentTypeNames.PML_NOTES_SLIDE, XmlParser.parse(xml))
        pkg.relationshipsOf(name).addTo(RelTypes.NOTES_MASTER, master.name)
        pkg.relationshipsOf(name).addTo(RelTypes.SLIDE, slidePart.name)
        pkg.relationships(slidePart).addTo(RelTypes.NOTES_SLIDE, name)
        return part
    }

    /** The deck's notes master; created (with its own copy of the slide theme) if missing. */
    private fun ensureNotesMaster(): Part {
        pkg.related(pres.presentationPart, RelTypes.NOTES_MASTER)?.let { return it }
        val slideMaster = pkg.related(pres.presentationPart, RelTypes.SLIDE_MASTER)
        val slideTheme = slideMaster?.let { pkg.related(it, RelTypes.THEME) }
        val themeName = pkg.uniquePartName("/ppt/theme/theme", "xml")
        val themeDoc = slideTheme?.let { XmlCodec.parse(it.bytes()) } ?: XmlParser.parse(BlankDeck.themeXml())
        pkg.addXmlPart(themeName, ContentTypeNames.THEME, themeDoc)
        // Images referenced by the theme live next to it, so the copied relationships stay valid.
        slideTheme?.let { pkg.part(PartNames.relsPartFor(it.name)) }?.let { rels ->
            pkg.addXmlPart(PartNames.relsPartFor(themeName), ContentTypeNames.RELATIONSHIPS, XmlCodec.parse(rels.bytes()))
        }

        val (nw, nh) = pres.presentation.element(P, "notesSz")?.let {
            (it.attr("cx")?.toLongOrNull() ?: 6858000L) to (it.attr("cy")?.toLongOrNull() ?: 9144000L)
        } ?: (6858000L to 9144000L)
        val (sw, sh) = pres.slideSize
        val imgW = nw * 3 / 4
        val imgH = imgW * sh / maxOf(1, sw)
        val margin = (nw - imgW) / 2
        val bodyY = margin + imgH + nh / 30
        val xml = HEADER + """<p:notesMaster xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld>""" +
            """<p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Slide Image Placeholder 1"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr>""" +
            """<p:nvPr><p:ph type="sldImg" idx="2"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="$margin" y="$margin"/><a:ext cx="$imgW" cy="$imgH"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln w="12700"><a:solidFill><a:prstClr val="black"/></a:solidFill></a:ln></p:spPr></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Notes Placeholder 2"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""" +
            """<p:nvPr><p:ph type="body" sz="quarter" idx="3"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="$margin" y="$bodyY"/><a:ext cx="$imgW" cy="${maxOf(1, nh - bodyY - margin)}"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0"/>""" +
            """<a:lstStyle/><a:p><a:pPr lvl="0"/><a:r><a:rPr lang="en-US"/><a:t>Notes</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """</p:spTree></p:cSld>""" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
            """<p:notesStyle><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1">""" +
            """<a:defRPr sz="1200" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr></p:notesStyle>""" +
            """</p:notesMaster>"""
        val name = pkg.uniquePartName("/ppt/notesMasters/notesMaster", "xml")
        val part = pkg.addXmlPart(name, ContentTypeNames.PML_NOTES_MASTER, XmlParser.parse(xml))
        pkg.relationshipsOf(name).addTo(RelTypes.THEME, themeName)
        val rel = pkg.relationships(pres.presentationPart).addTo(RelTypes.NOTES_MASTER, name)
        val list = pres.presentation.getOrCreate(P, "notesMasterIdLst", Schema.PRESENTATION)
        val entry = list.createChild(P, "notesMasterId")
        entry.setAttr(R, "id", rel.id)
        list.appendChild(entry)
        if (pres.presentation.element(P, "notesSz") == null) {
            val sz = pres.presentation.createChild(P, "notesSz")
            sz.setAttr("cx", "6858000")
            sz.setAttr("cy", "9144000")
            pres.presentation.insertInOrder(sz, Schema.PRESENTATION)
        }
        return part
    }

    companion object {
        const val HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n"
    }
}
