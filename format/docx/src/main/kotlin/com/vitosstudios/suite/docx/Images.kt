package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces
import com.vitosstudios.suite.xml.XElement
import com.vitosstudios.suite.xml.XmlParser

/** A picture in a `w:drawing` (inline or floating) whose image part the app can show. */
class DocxImage internal constructor(
    val drawing: XElement,
    val isInline: Boolean,
    val widthEmu: Long,
    val heightEmu: Long,
    /** Relationship id of the embedded image (relative to the part that contains the drawing). */
    val relId: String,
    val description: String?,
) {
    val widthPt: Float get() = Units.emuToPoints(widthEmu)
    val heightPt: Float get() = Units.emuToPoints(heightEmu)

    companion object {
        private const val PIC_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"

        internal fun container(drawing: XElement): XElement? =
            drawing.element(Namespaces.WP, "inline") ?: drawing.element(Namespaces.WP, "anchor")

        internal fun fromDrawing(doc: DocxDocument, drawing: XElement): DocxImage? {
            val c = container(drawing) ?: return null
            val graphicData = c.element(Namespaces.A, "graphic")?.element(Namespaces.A, "graphicData") ?: return null
            if (graphicData.attr("uri") != PIC_URI) return null
            val pic = graphicData.element(Namespaces.PIC, "pic") ?: return null
            val blip = pic.element(Namespaces.PIC, "blipFill")?.element(Namespaces.A, "blip") ?: return null
            val embed = blip.attr(Namespaces.R, "embed") ?: return null
            val extent = c.element(Namespaces.WP, "extent")
            return DocxImage(
                drawing = drawing,
                isInline = c.localName == "inline",
                widthEmu = extent?.attr("cx")?.toLongOrNull() ?: 0,
                heightEmu = extent?.attr("cy")?.toLongOrNull() ?: 0,
                relId = embed,
                description = c.element(Namespaces.WP, "docPr")?.attr("descr"),
            )
        }

        /** Label for drawings the app does not edit. */
        internal fun describeDrawing(drawing: XElement): String {
            val uri = container(drawing)?.element(Namespaces.A, "graphic")?.element(Namespaces.A, "graphicData")?.attr("uri") ?: ""
            return when {
                uri.endsWith("/chart") -> "Chart"
                uri.endsWith("/diagram") -> "SmartArt"
                uri.endsWith("wordprocessingShape") -> "Shape"
                uri.endsWith("wordprocessingGroup") -> "Group"
                uri.endsWith("wordprocessingCanvas") -> "Drawing canvas"
                uri.contains("ink", ignoreCase = true) -> "Ink"
                uri.endsWith("/picture") -> "Linked picture"
                else -> "Drawing"
            }
        }
    }
}

internal object DrawingOps {
    private const val WP = Namespaces.WP
    private const val A = Namespaces.A
    private const val PIC = Namespaces.PIC

    /** Next free wp:docPr id across the document parts. */
    fun nextDocPrId(doc: DocxDocument): Int {
        var max = 0
        for (part in doc.storyParts()) {
            val root = part.xmlOrNull()?.root ?: continue
            for (d in root.descendants(WP, "docPr")) d.attr("id")?.toIntOrNull()?.let { if (it > max) max = it }
        }
        return max + 1
    }

    /** Builds a detached inline `w:drawing` for [relId], sized [cx]x[cy] EMU, in the scope of [context]. */
    fun inlinePicture(context: XElement, relId: String, cx: Long, cy: Long, id: Int, fileName: String): XElement {
        val xml = """<w:drawing xmlns:w="${Wml.W}" xmlns:wp="$WP" xmlns:a="$A" xmlns:pic="$PIC" xmlns:r="${Namespaces.R}">""" +
            """<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>""" +
            """<wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="$id" name="Picture $id"/>""" +
            """<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="${com.vitosstudios.suite.xml.XmlEscaping.escapeAttribute(fileName)}"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""" +
            """</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"""
        val e = XmlParser.parse(xml).root.deepCopy()
        stripRedundantNamespace(e, context)
        return e
    }

    /** Adds the image as a media part related to [part]; returns the relationship id. */
    fun addImagePart(doc: DocxDocument, part: Part, bytes: ByteArray, extension: String): String {
        val ext = extension.lowercase().removePrefix(".").let { if (it == "jpeg") "jpg" else it }
        val type = ContentTypeNames.forImageExtension(ext) ?: throw IllegalArgumentException("Unsupported image type .$ext")
        val dir = part.name.substring(0, part.name.lastIndexOf('/') + 1)
        val name = doc.pkg.uniquePartName("${dir}media/image", ext)
        doc.pkg.addPart(name, null, bytes)
        if (doc.pkg.contentTypes.defaultFor(ext) == null) doc.pkg.contentTypes.ensureDefault(ext, type)
        return doc.pkg.relationships(part).addTo(RelTypes.IMAGE, name).id
    }

    fun resize(drawing: XElement, cx: Long, cy: Long) {
        val c = DocxImage.container(drawing) ?: return
        c.element(WP, "extent")?.let { it.setAttr("cx", cx.toString()); it.setAttr("cy", cy.toString()) }
        val pic = c.element(A, "graphic")?.element(A, "graphicData")?.element(PIC, "pic")
        pic?.element(PIC, "spPr")?.element(A, "xfrm")?.element(A, "ext")?.let {
            it.setAttr("cx", cx.toString()); it.setAttr("cy", cy.toString())
        }
    }
}
