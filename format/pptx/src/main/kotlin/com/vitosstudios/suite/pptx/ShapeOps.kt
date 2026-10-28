package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.ContentTypeNames
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.Namespaces.R
import com.vitosstudios.suite.xml.XElement
import kotlin.math.roundToLong

/** Shape-level edits: insert text boxes, pictures and simple shapes; move, resize, recolor, delete. */
internal class ShapeOps(private val pres: PptxPresentation) {
    private val pkg get() = pres.pkg

    private fun spTree(slide: Int): XElement =
        pres.slideRoot(slide).element(P, "cSld")?.element(P, "spTree") ?: throw PptxEditException("Slide $slide has no shape tree")

    private fun nextShapeId(slide: Int): Int {
        val root = pres.slideRoot(slide)
        val max = root.descendants().filter { it.localName == "cNvPr" }.maxOfOrNull { it.attr("id")?.toIntOrNull() ?: 0 } ?: 1
        return max + 1
    }

    /** Appends a new shape element at the top of the z-order (before the tree's extLst). */
    private fun append(slide: Int, shape: XElement) {
        val tree = spTree(slide)
        val ext = tree.elements().firstOrNull { it.localName == "extLst" }
        if (ext != null) tree.insertBefore(shape, ext) else tree.appendChild(shape)
    }

    private fun xfrmXml(b: EmuRect) =
        """<a:xfrm><a:off x="${b.x}" y="${b.y}"/><a:ext cx="${maxOf(0, b.cx)}" cy="${maxOf(0, b.cy)}"/></a:xfrm>"""

    // ----------------------------------------------------------------------------------------

    fun insertTextBox(slide: Int, bounds: EmuRect, text: String): Int {
        val id = nextShapeId(slide)
        val paragraphs = text.replace("\r\n", "\n").split('\n').joinToString("") { line ->
            if (line.isEmpty()) """<a:p><a:endParaRPr lang="en-US" dirty="0"/></a:p>"""
            else """<a:p><a:r><a:rPr lang="en-US" dirty="0"/><a:t>${Fragments.esc(line)}</a:t></a:r></a:p>"""
        }
        val xml = """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="TextBox ${id - 1}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""" +
            """<p:spPr>${xfrmXml(bounds)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>""" +
            """<p:txBody><a:bodyPr wrap="square" rtlCol="0"><a:spAutoFit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"""
        append(slide, Fragments.build(xml, spTree(slide)))
        return id
    }

    fun insertImage(slide: Int, bytes: ByteArray, extension: String, bounds: EmuRect?): Int {
        val ext = extension.lowercase().removePrefix(".").let { if (it == "jpeg") "jpg" else it }
        val contentType = ContentTypeNames.forImageExtension(ext) ?: throw PptxEditException("Unsupported picture type .$ext")
        val box = bounds ?: defaultImageBounds(bytes)
        val slidePart = pres.slidePart(slide)
        pkg.contentTypes.ensureDefault(ext, contentType)
        val mediaName = pkg.uniquePartName("/ppt/media/image", ext)
        pkg.addPart(mediaName, contentType, bytes)
        val rel = pkg.relationships(slidePart).addTo(RelTypes.IMAGE, mediaName)
        val id = nextShapeId(slide)
        val xml = """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture ${id - 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
            """<p:blipFill><a:blip r:embed="${rel.id}"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
            """<p:spPr>${xfrmXml(box)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
        append(slide, Fragments.build(xml, spTree(slide)))
        return id
    }

    /** Natural size at 96 dpi, scaled down to fit 80 % of the slide, centred. */
    private fun defaultImageBounds(bytes: ByteArray): EmuRect {
        val (sw, sh) = pres.slideSize
        val (pw, ph) = ImageInfo.size(bytes) ?: (640 to 480)
        var cx = pw * 9525.0
        var cy = ph * 9525.0
        val scale = minOf(1.0, sw * 0.8 / cx, sh * 0.8 / cy)
        cx *= scale
        cy *= scale
        return EmuRect(((sw - cx) / 2).roundToLong(), ((sh - cy) / 2).roundToLong(), cx.roundToLong(), cy.roundToLong())
    }

    fun insertShape(slide: Int, type: ShapeType, bounds: EmuRect): Int {
        val id = nextShapeId(slide)
        val name = "${type.defaultName} ${id - 1}"
        val xml = if (type == ShapeType.LINE) {
            """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="$id" name="$name"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>""" +
                """<p:spPr>${xfrmXml(bounds)}<a:prstGeom prst="line"><a:avLst/></a:prstGeom></p:spPr>""" +
                """<p:style><a:lnRef idx="1"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="0"><a:schemeClr val="accent1"/></a:fillRef>""" +
                """<a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="tx1"/></a:fontRef></p:style></p:cxnSp>"""
        } else {
            """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr>${xfrmXml(bounds)}<a:prstGeom prst="${type.preset}"><a:avLst/></a:prstGeom></p:spPr>""" +
                """<p:style><a:lnRef idx="2"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef>""" +
                """<a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef>""" +
                """<a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style>""" +
                """<p:txBody><a:bodyPr rtlCol="0" anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:endParaRPr lang="en-US" dirty="0"/></a:p></p:txBody></p:sp>"""
        }
        append(slide, Fragments.build(xml, spTree(slide)))
        return id
    }

    // ----------------------------------------------------------------------------------------

    fun setBounds(slide: Int, shapeId: Int, bounds: EmuRect, rotationDegrees: Double?) {
        val shape = pres.shapeElement(slide, shapeId)
        // Shapes inside groups store child coordinates; convert from slide coordinates.
        val local = groupTransform(shape)?.let { inverse(it, bounds) } ?: bounds
        val xfrm = when (shape.localName) {
            "graphicFrame" -> shape.element(P, "xfrm") ?: shape.createChild(P, "xfrm").also {
                val nv = shape.elements().first()
                shape.insertAfter(it, nv)
            }
            "grpSp" -> shape.getOrCreate(P, "grpSpPr", listOf("nvGrpSpPr", "grpSpPr")).getOrCreate(A, "xfrm", GROUP_PROPERTIES)
            else -> {
                val spPr = shape.element(P, "spPr") ?: throw PptxEditException("Shape $shapeId has no shape properties")
                spPr.getOrCreate(A, "xfrm", Schema.SHAPE_PROPERTIES)
            }
        }
        val order = listOf("off", "ext", "chOff", "chExt")
        if (shape.localName == "grpSp" && (xfrm.element(A, "chOff") == null || xfrm.element(A, "chExt") == null)) {
            // Without a child frame the children would jump; pin it to the current frame first.
            val off = xfrm.element(A, "off")
            val ext = xfrm.element(A, "ext")
            xfrm.getOrCreate(A, "chOff", order).also {
                it.setAttr("x", off?.attr("x") ?: "0"); it.setAttr("y", off?.attr("y") ?: "0")
            }
            xfrm.getOrCreate(A, "chExt", order).also {
                it.setAttr("cx", ext?.attr("cx") ?: "0"); it.setAttr("cy", ext?.attr("cy") ?: "0")
            }
        }
        xfrm.getOrCreate(A, "off", order).also {
            it.setAttr("x", local.x.toString()); it.setAttr("y", local.y.toString())
        }
        xfrm.getOrCreate(A, "ext", order).also {
            it.setAttr("cx", maxOf(0, local.cx).toString()); it.setAttr("cy", maxOf(0, local.cy).toString())
        }
        if (rotationDegrees != null) {
            val rot = (((rotationDegrees % 360) + 360) % 360 * 60000).roundToLong()
            if (rot == 0L) xfrm.removeAttribute("rot") else xfrm.setAttr("rot", rot.toString())
        }
    }

    /** Composite transform of the groups that contain [shape], or null at top level. */
    private fun groupTransform(shape: XElement): Transform? {
        val groups = shape.ancestors().filter { it.localName == "grpSp" }.toList().reversed()
        if (groups.isEmpty()) return null
        var t = Transform.IDENTITY
        for (g in groups) {
            val x = g.element(P, "grpSpPr")?.element(A, "xfrm") ?: continue
            fun rect(offName: String, extName: String): EmuRect? {
                val off = x.element(A, offName) ?: return null
                val ext = x.element(A, extName) ?: return null
                return EmuRect(off.attr("x")?.toLongOrNull() ?: 0, off.attr("y")?.toLongOrNull() ?: 0,
                    ext.attr("cx")?.toLongOrNull() ?: 0, ext.attr("cy")?.toLongOrNull() ?: 0)
            }
            val parent = rect("off", "ext") ?: continue
            val child = rect("chOff", "chExt") ?: continue
            t = t.then(Transform.between(child, parent, 0.0))
        }
        return t
    }

    private fun inverse(t: Transform, r: EmuRect): EmuRect = EmuRect(
        ((r.x - t.dx) / t.sx).roundToLong(), ((r.y - t.dy) / t.sy).roundToLong(),
        (r.cx / t.sx).roundToLong(), (r.cy / t.sy).roundToLong(),
    )

    fun delete(slide: Int, shapeId: Int) {
        val root = pres.slideRoot(slide)
        val shape = pres.shapeElement(slide, shapeId)
        val ids = (listOf(shape) + shape.descendants().toList())
            .mapNotNull { PptxPresentation.shapeId(it) }.toSet()
        val animated = root.element(P, "timing")?.descendants()?.any { e -> e.attr("spid")?.toIntOrNull() in ids } == true
        if (animated) throw PptxEditException("This shape is animated. Remove its animation in a desktop editor before deleting it.")
        val used = relationshipIds(shape)
        shape.detach()
        val stillUsed = relationshipIds(root)
        val slidePart = pres.slidePart(slide)
        val rels = pkg.relationships(slidePart)
        for (id in used - stillUsed) {
            val rel = rels.byId(id) ?: continue
            val target = rels.targetPart(rel)
            rels.remove(id)
            if (target != null && !pkg.isReferenced(target.name)) pkg.removePartRecursively(target.name)
        }
    }

    private fun relationshipIds(e: XElement): Set<String> {
        val out = HashSet<String>()
        for (d in sequenceOf(e) + e.descendants()) {
            for (a in d.attributes) {
                if (a.isNamespaceDeclaration || a.prefix == null) continue
                if (d.lookupNamespaceUri(a.prefix)?.let { com.vitosstudios.suite.xml.Namespaces.canonical(it) } == R) out += a.value
            }
        }
        return out
    }

    private fun spPr(slide: Int, shapeId: Int): XElement {
        val shape = pres.shapeElement(slide, shapeId)
        if (shape.localName !in setOf("sp", "pic", "cxnSp")) throw PptxEditException("Shape $shapeId cannot be recolored")
        return shape.element(P, "spPr") ?: shape.createChild(P, "spPr").also { shape.insertInOrder(it, listOf("nvSpPr", "nvPicPr", "nvCxnSpPr", "blipFill", "spPr")) }
    }

    fun setFill(slide: Int, shapeId: Int, rgb: Int?) {
        val spPr = spPr(slide, shapeId)
        spPr.removeChildren { it is XElement && it.localName in Schema.FILLS }
        val fill = if (rgb == null) spPr.createChild(A, "noFill") else pres.text.solidFill(spPr, rgb)
        spPr.insertInOrder(fill, Schema.SHAPE_PROPERTIES)
    }

    fun setLine(slide: Int, shapeId: Int, rgb: Int?, widthEmu: Long?) {
        val spPr = spPr(slide, shapeId)
        val ln = spPr.getOrCreate(A, "ln", Schema.SHAPE_PROPERTIES)
        ln.removeChildren { it is XElement && it.localName in setOf("noFill", "solidFill", "gradFill", "pattFill") }
        val fill = if (rgb == null) ln.createChild(A, "noFill") else pres.text.solidFill(ln, rgb)
        ln.insertInOrder(fill, Schema.LINE_PROPERTIES)
        widthEmu?.let { ln.setAttr("w", it.coerceIn(0, 20116800).toString()) }
    }

    companion object {
        /** CT_GroupShapeProperties: p:grpSpPr. */
        val GROUP_PROPERTIES = listOf(
            "xfrm", "noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill", "effectLst", "effectDag",
            "scene3d", "extLst",
        )
    }
}

/** Reads pixel dimensions from PNG, JPEG, GIF, BMP and WebP headers. */
object ImageInfo {
    fun size(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        return try {
            when {
                b.size > 24 && u8(0) == 0x89 && u8(1) == 0x50 -> be32(16) to be32(20)
                b.size > 10 && u8(0) == 0x47 && u8(1) == 0x49 -> le16(6) to le16(8)
                b.size > 26 && u8(0) == 0x42 && u8(1) == 0x4D -> le32(18) to Math.abs(le32(22))
                b.size > 30 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                    when (String(b, 12, 4, Charsets.US_ASCII)) {
                        "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
                        "VP8 " -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                        "VP8L" -> {
                            val bits = le32(21)
                            ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
                        }
                        else -> null
                    }
                }
                b.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8 -> {
                    var i = 2
                    while (i + 9 < b.size) {
                        if (u8(i) != 0xFF) { i++; continue }
                        val marker = u8(i + 1)
                        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                            return be16(i + 7) to be16(i + 5)
                        }
                        i += 2 + be16(i + 2)
                    }
                    null
                }
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
}
