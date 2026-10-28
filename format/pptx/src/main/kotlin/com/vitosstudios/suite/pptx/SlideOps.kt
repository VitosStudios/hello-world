package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.PartNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.Namespaces.R
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlCodec
import com.vitosstudios.suite.xml.XmlParser

/** Slide list operations: insert from layout, delete, duplicate, reorder, layout and visibility. */
internal class SlideOps(private val pres: PptxPresentation) {
    private val pkg get() = pres.pkg

    private fun sldIdLst(): XElement =
        pres.presentation.getOrCreate(P, "sldIdLst", Schema.PRESENTATION)

    private fun nextSlideId(): Long =
        maxOf(255L, pres.slideIdElements().maxOfOrNull { it.attr("id")?.toLongOrNull() ?: 0L } ?: 0L) + 1

    // ----------------------------------------------------------------------------------------

    fun insert(index: Int, layoutPartName: String): Int {
        val count = pres.slideCount
        if (index < 0 || index > count) throw PptxEditException("Index $index outside 0..$count")
        val layoutPart = pkg.part(layoutPartName) ?: throw PptxEditException("No layout $layoutPartName")
        if (layoutPart.contentType != LAYOUT_CONTENT_TYPE) throw PptxEditException("$layoutPartName is not a slide layout")
        val layout = layoutPart.xml().root
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append("\r\n")
        sb.append("""<p:sld xmlns:a="${com.vitosstudios.suite.xml.Namespaces.A}" xmlns:r="${R}" xmlns:p="${P}">""")
        sb.append("""<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""")
        sb.append("""<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""")
        var id = 2
        val tree = layout.element(P, "cSld")?.element(P, "spTree")
        for (sp in tree?.elements(P, "sp").orEmpty()) {
            val ph = sp.element(P, "nvSpPr")?.element(P, "nvPr")?.element(P, "ph") ?: continue
            val type = ph.attr("type") ?: "obj"
            if (type in setOf("dt", "ftr", "sldNum", "hdr")) continue
            val name = sp.element(P, "nvSpPr")?.element(P, "cNvPr")?.attr("name") ?: "Placeholder $id"
            val phXml = buildString {
                append("<p:ph")
                ph.attr("type")?.let { append(" type=\"").append(Fragments.attr(it)).append('"') }
                ph.attr("orient")?.let { append(" orient=\"").append(Fragments.attr(it)).append('"') }
                ph.attr("sz")?.let { append(" sz=\"").append(Fragments.attr(it)).append('"') }
                ph.attr("idx")?.let { append(" idx=\"").append(Fragments.attr(it)).append('"') }
                append("/>")
            }
            sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${Fragments.attr(name)}"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""")
            sb.append("<p:nvPr>").append(phXml).append("</p:nvPr></p:nvSpPr><p:spPr/>")
            sb.append("""<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody></p:sp>""")
            id++
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")

        val partName = pkg.uniquePartName("/ppt/slides/slide", "xml")
        pkg.addXmlPart(partName, ContentTypeNames.PML_SLIDE, XmlParser.parse(sb.toString()))
        pkg.relationshipsOf(partName).addTo(RelTypes.SLIDE_LAYOUT, layoutPart.name)
        addToList(partName, index)
        return index
    }

    private fun addToList(slidePartName: String, index: Int) {
        val rel = pkg.relationships(pres.presentationPart).addTo(RelTypes.SLIDE, slidePartName)
        val list = sldIdLst()
        val sldId = list.createChild(P, "sldId")
        val newId = nextSlideId()
        sldId.setAttr("id", newId.toString())
        sldId.setAttr(R, "id", rel.id)
        val existing = pres.slideIdElements()
        if (index >= existing.size) {
            val ext = list.elements().firstOrNull { it.localName == "extLst" }
            if (ext != null) list.insertBefore(sldId, ext) else list.appendChild(sldId)
        } else {
            list.insertBefore(sldId, existing[index])
        }
        syncSections()
    }

    // ----------------------------------------------------------------------------------------

    fun delete(index: Int) {
        val ids = pres.slideIdElements()
        if (ids.size <= 1) throw PptxEditException("A presentation needs at least one slide")
        val sldId = ids.getOrNull(index) ?: throw PptxEditException("No slide $index")
        val rId = sldId.attr(R, "id") ?: throw PptxEditException("Slide entry without relationship")
        val presRels = pkg.relationships(pres.presentationPart)
        val slidePart = presRels.targetPart(rId) ?: throw PptxEditException("Slide part is missing")
        val slideRels = pkg.relationships(slidePart)
        val notesPart = pkg.related(slidePart, RelTypes.NOTES_SLIDE)
        val owned = slideRels.all()
            .filter { !it.isExternal && it.type !in setOf(RelTypes.SLIDE_LAYOUT, RelTypes.NOTES_SLIDE, RelTypes.SLIDE) }
            .mapNotNull { slideRels.targetPart(it)?.name }

        sldId.detach()
        presRels.remove(rId)
        // Custom shows list slides by relationship id; a dangling id makes the file invalid.
        pres.presentation.element(P, "custShowLst")?.descendants(P, "sld")
            ?.filter { it.attr(R, "id") == rId }?.toList()?.forEach { it.detach() }
        syncSections()

        pkg.removePart(slidePart.name)
        if (notesPart != null) pkg.removePart(notesPart.name)
        for (name in owned.distinct()) {
            if (pkg.part(name) != null && !pkg.isReferenced(name)) pkg.removePartRecursively(name)
        }
    }

    // ----------------------------------------------------------------------------------------

    fun duplicate(index: Int): Int {
        val source = pres.slidePart(index)
        val newName = pkg.uniquePartName("/ppt/slides/slide", "xml")
        copyPartTree(source.name, newName, ContentTypeNames.PML_SLIDE)
        addToList(newName, index + 1)
        return index + 1
    }

    /**
     * Copies [sourceName] to [targetName] with its relationships. Parts owned by one slide
     * (notes, charts, diagrams, embedded objects, comments) are copied too; shared parts
     * (layouts, masters, media, other slides) are referenced, not copied.
     */
    private fun copyPartTree(sourceName: String, targetName: String, contentType: String) {
        val source = pkg.requirePart(sourceName)
        pkg.addXmlPart(targetName, contentType, XmlCodec.parse(source.bytes()))
        val relsSource = pkg.part(PartNames.relsPartFor(sourceName)) ?: return
        val relsDoc = XmlCodec.parse(relsSource.bytes())
        for (rel in relsDoc.root.elements().filter { it.localName == "Relationship" }.toList()) {
            if (rel.attr("TargetMode") == "External") continue
            val type = RelTypes.canonical(rel.attr("Type") ?: continue)
            val targetPartName = PartNames.resolve(sourceName, rel.attr("Target") ?: continue)
            val target = pkg.part(targetPartName) ?: continue
            when {
                type == RelTypes.SLIDE && sourceName.contains("/notesSlides/") -> {
                    // A copied notes page points back at the copied slide; fixed up by the caller.
                }
                type in SHARED_TYPES -> {}
                else -> {
                    val newTarget = copyName(target.name)
                    copyPartTree(target.name, newTarget, target.contentType ?: ContentTypeNames.XML, binary = !isXml(target.contentType))
                    rel.setAttr("Target", PartNames.relativize(targetName, newTarget))
                    if (type == RelTypes.NOTES_SLIDE) pointNotesAt(newTarget, targetName)
                }
            }
        }
        pkg.addXmlPart(PartNames.relsPartFor(targetName), ContentTypeNames.RELATIONSHIPS, relsDoc)
    }

    private fun copyPartTree(sourceName: String, targetName: String, contentType: String, binary: Boolean) {
        if (!binary) return copyPartTree(sourceName, targetName, contentType)
        pkg.addPart(targetName, contentType, pkg.requirePart(sourceName).bytes())
        val rels = pkg.part(PartNames.relsPartFor(sourceName)) ?: return
        pkg.addXmlPart(PartNames.relsPartFor(targetName), ContentTypeNames.RELATIONSHIPS, XmlCodec.parse(rels.bytes()))
    }

    private fun isXml(ct: String?): Boolean = ct != null && (ct.endsWith("+xml") || ct == ContentTypeNames.XML)

    private fun pointNotesAt(notesName: String, slideName: String) {
        val rels = pkg.relationshipsOf(notesName)
        val back = rels.all().firstOrNull { it.type == RelTypes.SLIDE } ?: return
        rels.retarget(back.id, PartNames.relativize(notesName, slideName))
    }

    /** "/ppt/charts/chart3.xml" -> the next free "/ppt/charts/chartN.xml". */
    private fun copyName(name: String): String {
        val dir = PartNames.directory(name)
        val file = PartNames.fileName(name)
        val ext = file.substringAfterLast('.', "")
        val stem = file.substringBeforeLast('.').trimEnd { it.isDigit() }.ifEmpty { "part" }
        return pkg.uniquePartName(dir + stem, ext)
    }

    // ----------------------------------------------------------------------------------------

    fun move(from: Int, to: Int) {
        val ids = pres.slideIdElements()
        if (from !in ids.indices || to !in ids.indices) throw PptxEditException("Move $from -> $to outside 0..${ids.size - 1}")
        if (from == to) return
        val moving = ids[from]
        val list = moving.parent!!
        moving.detach()
        val remaining = pres.slideIdElements()
        if (to >= remaining.size) {
            list.insertAfter(moving, remaining.last())
        } else {
            list.insertBefore(moving, remaining[to])
        }
        syncSections(moved = moving.attr("id")?.toLongOrNull())
    }

    fun changeLayout(index: Int, layoutPartName: String) {
        val slide = pres.slidePart(index)
        val layout = pkg.part(layoutPartName) ?: throw PptxEditException("No layout $layoutPartName")
        if (layout.contentType != LAYOUT_CONTENT_TYPE) throw PptxEditException("$layoutPartName is not a slide layout")
        val rels = pkg.relationships(slide)
        val rel = rels.firstOfType(RelTypes.SLIDE_LAYOUT) ?: throw PptxEditException("Slide has no layout")
        rels.retarget(rel.id, PartNames.relativize(slide.name, layout.name))
    }

    fun setHidden(index: Int, hidden: Boolean) {
        val root = pres.slideRoot(index)
        if (hidden) root.setAttr("show", "0") else root.removeAttribute("show")
    }

    // ----------------------------------------------------------------------------------------
    // Sections (PowerPoint 2010 extension): every slide id must be listed exactly once, sections
    // in slide order. Slides without a section join the section of the slide before them.

    private fun sectionList(): XElement? = pres.presentation.element(P, "extLst")?.elements()
        ?.firstOrNull { it.localName == "ext" && it.attr("uri") == SECTION_EXT_URI }
        ?.elements()?.firstOrNull { it.localName == "sectionLst" }

    private fun syncSections(moved: Long? = null) {
        val lst = sectionList() ?: return
        val sections = lst.elements().filter { it.localName == "section" }.toList()
        if (sections.isEmpty()) return
        val order = pres.slideIdElements().mapNotNull { it.attr("id")?.toLongOrNull() }
        val membership = HashMap<Long, XElement>()
        for (s in sections) {
            s.elements().firstOrNull { it.localName == "sldIdLst" }?.elements()?.forEach { e ->
                e.attr("id")?.toLongOrNull()?.let { if (it != moved) membership[it] = s }
            }
        }
        var previous: XElement = sections.first()
        val wanted = LinkedHashMap<XElement, MutableList<Long>>()
        sections.forEach { wanted[it] = ArrayList() }
        for (id in order) {
            val s = membership[id] ?: previous
            wanted[s]!!.add(id)
            previous = s
        }
        for (s in sections) {
            val list = s.elements().firstOrNull { it.localName == "sldIdLst" }
                ?: s.createChild(Fragments.P14, "sldIdLst").also { s.insertChild(0, it) }
            val current = list.elements().filter { it.localName == "sldId" }.mapNotNull { it.attr("id")?.toLongOrNull() }.toList()
            val target = wanted[s]!!
            if (current == target) continue
            for (e in list.elements().filter { it.localName == "sldId" }.toList()) e.detach()
            for (id in target) {
                val e = list.createChild(Fragments.P14, "sldId")
                e.setAttr("id", id.toString())
                list.appendChild(e)
            }
        }
    }

    companion object {
        const val LAYOUT_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
        const val SECTION_EXT_URI = "{521415D9-36F7-43E2-AB2F-B90AF26B5E84}"

        /** Relationship targets that slides share instead of owning. */
        val SHARED_TYPES = setOf(
            RelTypes.SLIDE_LAYOUT, RelTypes.SLIDE_MASTER, RelTypes.NOTES_MASTER, RelTypes.THEME, RelTypes.IMAGE,
            RelTypes.MEDIA, RelTypes.VIDEO, RelTypes.AUDIO, RelTypes.SLIDE, RelTypes.HYPERLINK,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/tags",
        )
    }
}
