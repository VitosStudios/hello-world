package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.OfficeDocument
import com.vitosstudios.suite.opc.OpcException
import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.opc.Part
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.Namespaces.R
import com.vitosstudios.suite.xml.XElement
import java.io.File

/**
 * A PPTX presentation opened for viewing and editing.
 *
 * Reading never modifies anything. Every edit runs in one undo transaction on [pkg]'s
 * [com.vitosstudios.suite.common.UndoManager] and touches only the parts it must change; all other
 * parts are written back byte-identical. Slides are addressed by their index in the slide list,
 * shapes by their `cNvPr` id (unique within a slide).
 */
class PptxPresentation private constructor(override val pkg: OpcPackage) : OfficeDocument {
    val presentationPart: Part = pkg.mainDocument() ?: throw OpcException("No main presentation part")

    init {
        val ct = presentationPart.contentType
        if (ct == null || ct !in MAIN_CONTENT_TYPES) throw OpcException("Not a presentation (content type $ct)")
        // Fails early (PartNotEditableException) if the presentation part cannot be edited safely.
        presentationPart.xml()
    }

    internal val presentation: XElement get() = presentationPart.xml().root

    internal val text = TextEditor(this)
    internal val slideOps = SlideOps(this)
    internal val shapeOps = ShapeOps(this)
    internal val notes = NotesOps(this)

    // ----------------------------------------------------------------------------------------
    // Structure

    /** Slide size in EMU (width, height). */
    val slideSize: Pair<Long, Long>
        get() {
            val sz = presentation.element(P, "sldSz")
            return (sz?.attr("cx")?.toLongOrNull() ?: 9144000L) to (sz?.attr("cy")?.toLongOrNull() ?: 6858000L)
        }

    internal fun slideIdElements(): List<XElement> =
        presentation.element(P, "sldIdLst")?.elements(P, "sldId")?.toList().orEmpty()

    /** Slide parts in presentation order (entries whose target is missing are skipped). */
    fun slideParts(): List<Part> {
        val rels = pkg.relationships(presentationPart)
        return slideIdElements().mapNotNull { e -> e.attr(R, "id")?.let { rels.targetPart(it) } }
    }

    val slideCount: Int get() = slideParts().size

    fun slidePart(index: Int): Part = slideParts().getOrNull(index) ?: throw PptxEditException("No slide $index")

    /** Every layout of every slide master, in master order, for "new slide from layout". */
    fun layouts(): List<LayoutInfo> {
        val out = ArrayList<LayoutInfo>()
        val presRels = pkg.relationships(presentationPart)
        val masters = presentation.element(P, "sldMasterIdLst")?.elements(P, "sldMasterId")
            ?.mapNotNull { m -> m.attr(R, "id")?.let { presRels.targetPart(it) } }?.toList().orEmpty()
        for (master in masters) {
            val masterRoot = readRoot(master) ?: continue
            val rels = pkg.relationships(master)
            val ids = masterRoot.element(P, "sldLayoutIdLst")?.elements(P, "sldLayoutId")?.toList().orEmpty()
            for (id in ids) {
                val layout = id.attr(R, "id")?.let { rels.targetPart(it) } ?: continue
                val root = readRoot(layout) ?: continue
                out += LayoutInfo(layout.name, root.element(P, "cSld")?.attr("name") ?: "Layout", root.attr("type"), master.name)
            }
        }
        return out
    }

    /** The resolved, render-ready view of slide [index]. */
    fun slideView(index: Int): SlideView {
        val part = slidePart(index)
        return SlideReader(SlideContext.forSlide(pkg, part, presentation)).read(index)
    }

    fun slideViews(): List<SlideView> = slideParts().indices.map(::slideView)

    /** Bytes of a media part referenced by a view (images, backgrounds). */
    fun partBytes(partName: String): ByteArray? = pkg.part(partName)?.bytes()

    // ----------------------------------------------------------------------------------------
    // Text editing

    /** Replaces [start, end) of paragraph [paragraph] with [newText]; keeps the formatting at [start]. */
    fun replaceText(slide: Int, shapeId: Int, paragraph: Int, start: Int, end: Int, newText: String) =
        edit("Edit text") { text.replaceText(paragraphElement(slide, shapeId, paragraph, create = true), start, end, newText) }

    /** Replaces all text of a shape; "\n" separates paragraphs. Formatting of the first run is kept. */
    fun setShapeText(slide: Int, shapeId: Int, value: String) =
        edit("Edit text") { text.setBodyText(textBody(slide, shapeId, create = true), value) }

    fun applyRunFormat(slide: Int, shapeId: Int, paragraph: Int, start: Int, end: Int, patch: RunFormatPatch) =
        edit("Format text") { text.applyFormat(paragraphElement(slide, shapeId, paragraph), start, end, patch) }

    fun setParagraphAlignment(slide: Int, shapeId: Int, paragraph: Int, alignment: TextAlign) =
        edit("Align") { text.setAlignment(paragraphElement(slide, shapeId, paragraph), alignment) }

    fun setBullet(slide: Int, shapeId: Int, paragraph: Int, style: BulletStyle) =
        edit("Bullets") { text.setBullet(paragraphElement(slide, shapeId, paragraph), style) }

    fun setParagraphLevel(slide: Int, shapeId: Int, paragraph: Int, level: Int) =
        edit("Indent") { text.setLevel(paragraphElement(slide, shapeId, paragraph), level) }

    /** Splits a paragraph at [offset]; the new paragraph has index [paragraph] + 1. */
    fun splitParagraph(slide: Int, shapeId: Int, paragraph: Int, offset: Int) =
        edit("New paragraph") { text.split(paragraphElement(slide, shapeId, paragraph, create = true), offset) }

    /** Appends paragraph [paragraph] + 1 to [paragraph]. */
    fun mergeWithNextParagraph(slide: Int, shapeId: Int, paragraph: Int) =
        edit("Join paragraphs") { text.mergeWithNext(paragraphElement(slide, shapeId, paragraph)) }

    fun deleteParagraph(slide: Int, shapeId: Int, paragraph: Int) =
        edit("Delete paragraph") { text.deleteParagraph(paragraphElement(slide, shapeId, paragraph)) }

    // ----------------------------------------------------------------------------------------
    // Slides

    /** Inserts a new slide based on [layoutPartName] at [index]; returns the index. */
    fun insertSlide(index: Int, layoutPartName: String): Int = edit("New slide") { slideOps.insert(index, layoutPartName) }

    fun deleteSlide(index: Int) = edit("Delete slide") { slideOps.delete(index) }

    /** Duplicates slide [index] right after it; returns the new index. */
    fun duplicateSlide(index: Int): Int = edit("Duplicate slide") { slideOps.duplicate(index) }

    fun moveSlide(from: Int, to: Int) = edit("Move slide") { slideOps.move(from, to) }

    /** Switches slide [index] to another layout of the deck. Placeholders follow by type/index. */
    fun changeLayout(index: Int, layoutPartName: String) = edit("Change layout") { slideOps.changeLayout(index, layoutPartName) }

    fun setSlideHidden(index: Int, hidden: Boolean) = edit("Hide slide") { slideOps.setHidden(index, hidden) }

    // ----------------------------------------------------------------------------------------
    // Shapes

    /** Inserts a text box; returns its shape id. */
    fun insertTextBox(slide: Int, bounds: EmuRect, initialText: String = ""): Int =
        edit("Insert text box") { shapeOps.insertTextBox(slide, bounds, initialText) }

    /** Inserts a picture (PNG, JPEG, GIF, WebP, BMP); [bounds] null centres it at its natural size. */
    fun insertImage(slide: Int, bytes: ByteArray, extension: String, bounds: EmuRect? = null): Int =
        edit("Insert picture") { shapeOps.insertImage(slide, bytes, extension, bounds) }

    fun insertShape(slide: Int, type: ShapeType, bounds: EmuRect): Int =
        edit("Insert shape") { shapeOps.insertShape(slide, type, bounds) }

    /** Moves/resizes (bounds in slide coordinates) and optionally rotates a shape. */
    fun setShapeBounds(slide: Int, shapeId: Int, bounds: EmuRect, rotationDegrees: Double? = null) =
        edit("Move shape") { shapeOps.setBounds(slide, shapeId, bounds, rotationDegrees) }

    fun deleteShape(slide: Int, shapeId: Int) = edit("Delete shape") { shapeOps.delete(slide, shapeId) }

    /** Solid fill as 0xRRGGBB, or null for no fill. */
    fun setShapeFill(slide: Int, shapeId: Int, rgb: Int?) = edit("Fill") { shapeOps.setFill(slide, shapeId, rgb) }

    /** Outline color 0xRRGGBB (null = no line) and width in EMU (null = unchanged). */
    fun setShapeLine(slide: Int, shapeId: Int, rgb: Int?, widthEmu: Long? = null) =
        edit("Outline") { shapeOps.setLine(slide, shapeId, rgb, widthEmu) }

    // ----------------------------------------------------------------------------------------
    // Notes

    fun notesText(slide: Int): String = notes.text(slide)

    /** Sets the speaker notes; creates the notes page (and a notes master if needed). */
    fun setNotesText(slide: Int, value: String) = edit("Speaker notes") { notes.setText(slide, value) }

    // ----------------------------------------------------------------------------------------
    // Document

    override fun compatibility(): CompatibilityReport = PptxCompatibility.report(this)

    override fun save(target: File) {
        pkg.save(target) { tmp ->
            open(tmp).use { reopened -> reopened.slideViews() }
        }
    }

    internal fun <T> edit(label: String, block: () -> T): T = pkg.undo.transaction(label, block)

    internal fun slideRoot(index: Int): XElement = slidePart(index).xml().root

    internal fun shapeElement(slide: Int, shapeId: Int): XElement =
        findShape(slideRoot(slide), shapeId) ?: throw PptxEditException("No shape $shapeId on slide $slide")

    internal fun textBody(slide: Int, shapeId: Int, create: Boolean = false): XElement {
        val shape = shapeElement(slide, shapeId)
        if (shape.localName != "sp") throw PptxEditException("Shape $shapeId has no editable text")
        return shape.element(P, "txBody") ?: if (create) text.createTextBody(shape) else throw PptxEditException("Shape $shapeId has no text")
    }

    internal fun paragraphElement(slide: Int, shapeId: Int, paragraph: Int, create: Boolean = false): XElement {
        val body = textBody(slide, shapeId, create)
        val paragraphs = body.elements(com.vitosstudios.suite.xml.Namespaces.A, "p").toList()
        return paragraphs.getOrNull(paragraph) ?: throw PptxEditException("No paragraph $paragraph in shape $shapeId")
    }

    companion object {
        val MAIN_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
            "application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
            "application/vnd.ms-powerpoint.template.macroEnabled.main+xml",
        )

        fun open(file: File): PptxPresentation {
            val pkg = OpcPackage.open(file)
            return try { PptxPresentation(pkg) } catch (e: Exception) { pkg.close(); throw e }
        }

        fun open(bytes: ByteArray): PptxPresentation {
            val pkg = OpcPackage.open(bytes)
            return try { PptxPresentation(pkg) } catch (e: Exception) { pkg.close(); throw e }
        }

        /** A new 16:9 deck with one title slide and the layouts of [BlankDeck]. */
        fun createNew(): PptxPresentation {
            val pres = PptxPresentation(OpcPackage.fromEntries(BlankDeck.entries()))
            pres.pkg.undo.clear()
            return pres
        }

        /** The shape element with cNvPr id [id] in a slide's tree (not inside markup-compatibility blocks). */
        internal fun findShape(root: XElement, id: Int): XElement? {
            val tree = root.element(P, "cSld")?.element(P, "spTree") ?: return null
            return tree.descendants().firstOrNull { e ->
                e.localName in EDITABLE_SHAPES && shapeId(e) == id && e.ancestors().none { it.localName == "AlternateContent" }
            }
        }

        internal fun shapeId(e: XElement): Int? =
            e.elements().firstOrNull { it.localName.startsWith("nv") }?.element(P, "cNvPr")?.attr("id")?.toIntOrNull()

        internal val EDITABLE_SHAPES = setOf("sp", "pic", "cxnSp", "graphicFrame", "grpSp")
    }
}
