package com.vitosstudios.suite.feature.slides

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.feature.common.OfficeEditorViewModel
import com.vitosstudios.suite.pptx.BulletStyle
import com.vitosstudios.suite.pptx.EmuRect
import com.vitosstudios.suite.pptx.LayoutInfo
import com.vitosstudios.suite.pptx.PptxPresentation
import com.vitosstudios.suite.pptx.RunFormatPatch
import com.vitosstudios.suite.pptx.ShapeType
import com.vitosstudios.suite.pptx.ShapeView
import com.vitosstudios.suite.pptx.SlideView
import com.vitosstudios.suite.pptx.TextAlign
import java.io.File

class SlidesViewModel(container: AppContainer, handle: SavedStateHandle) : OfficeEditorViewModel<PptxPresentation>(container, handle) {
    override fun openDocument(file: File): PptxPresentation = PptxPresentation.open(file)
    override fun verifyWritten(file: File) {
        PptxPresentation.open(file).use { it.slideViews() }
    }

    var current by mutableIntStateOf(0)
        private set
    var selectedShapeId by mutableStateOf<Int?>(null)
        private set
    var editingText by mutableStateOf(false)
    var presenting by mutableStateOf(false)
    var presentStart by mutableIntStateOf(0)
        private set

    /** Paragraph and selection inside the text panel; null paragraph = the whole shape. */
    var textParagraph by mutableStateOf<Int?>(null)
    var textSelection by mutableStateOf(0..0)

    private var cacheVersion = -1L
    private var cache: List<SlideView> = emptyList()

    fun slides(): List<SlideView> {
        val d = document ?: return emptyList()
        if (cacheVersion != version || cache.size != d.slideCount) {
            cache = d.slideViews()
            cacheVersion = version
        }
        return cache
    }

    fun currentSlide(): SlideView? = slides().getOrNull(current.coerceIn(0, (slides().size - 1).coerceAtLeast(0)))

    fun selectedShape(): ShapeView? = selectedShapeId?.let { id -> currentSlide()?.shape(id) }

    fun layouts(): List<LayoutInfo> = document?.layouts().orEmpty()

    fun goTo(index: Int) {
        current = index.coerceIn(0, (slides().size - 1).coerceAtLeast(0))
        selectedShapeId = null
        editingText = false
    }

    fun select(shape: ShapeView?) {
        selectedShapeId = shape?.takeIf { it.isEditable }?.id
        if (shape != null && !shape.isEditable && shape.isLocked) notify("${shape.lockReason ?: "This content"} is kept unchanged and cannot be edited")
        editingText = false
        textParagraph = null
    }

    // Slides

    fun addSlide(layout: LayoutInfo) {
        val d = document ?: return
        var idx = 0
        edit("New slide") { idx = d.insertSlide((current + 1).coerceAtMost(d.slideCount), layout.partName) }
        goTo(idx)
    }

    fun duplicateSlide() {
        val d = document ?: return
        var idx = current
        edit("Duplicate slide") { idx = d.duplicateSlide(current) }
        goTo(idx)
    }

    fun deleteSlide() {
        val d = document ?: return
        if (d.slideCount <= 1) return notify("A presentation needs at least one slide")
        edit("Delete slide") { d.deleteSlide(current) }
        goTo(current.coerceAtMost(d.slideCount - 1))
    }

    fun moveSlide(from: Int, to: Int) {
        val d = document ?: return
        if (to !in 0 until d.slideCount || from == to) return
        edit("Move slide") { d.moveSlide(from, to) }
        current = to
    }

    fun changeLayout(layout: LayoutInfo) = edit("Change layout") { it.changeLayout(current, layout.partName) }

    fun toggleHidden() {
        val s = currentSlide() ?: return
        edit("Hide slide") { it.setSlideHidden(current, !s.hidden) }
    }

    // Shapes

    private fun centeredBox(wFraction: Float, hFraction: Float): EmuRect {
        val (w, h) = document?.slideSize ?: (9144000L to 6858000L)
        val cx = (w * wFraction).toLong()
        val cy = (h * hFraction).toLong()
        return EmuRect((w - cx) / 2, (h - cy) / 2, cx, cy)
    }

    fun insertTextBox() {
        var id: Int? = null
        edit("Insert text box") { id = it.insertTextBox(current, centeredBox(0.6f, 0.15f), "Text") }
        selectedShapeId = id
        editingText = true
    }

    fun insertShape(type: ShapeType) {
        var id: Int? = null
        val box = if (type == ShapeType.LINE) centeredBox(0.4f, 0.001f) else centeredBox(0.3f, 0.25f)
        edit("Insert shape") { id = it.insertShape(current, type, box) }
        selectedShapeId = id
    }

    fun insertImage(bytes: ByteArray, extension: String) {
        var id: Int? = null
        edit("Insert picture") { id = it.insertImage(current, bytes, extension, null) }
        selectedShapeId = id
    }

    fun setBounds(id: Int, bounds: EmuRect) = edit("Move shape") { it.setShapeBounds(current, id, bounds) }

    fun rotateSelected(delta: Double) {
        val s = selectedShape() ?: return
        edit("Rotate") { it.setShapeBounds(current, s.id, s.bounds, (s.rotation + delta).mod(360.0)) }
    }

    fun deleteSelected() {
        val id = selectedShapeId ?: return
        edit("Delete shape") { it.deleteShape(current, id) }
        selectedShapeId = null
        editingText = false
    }

    fun setFill(rgb: Int?) { val id = selectedShapeId ?: return; edit("Fill") { it.setShapeFill(current, id, rgb) } }
    fun setLine(rgb: Int?) { val id = selectedShapeId ?: return; edit("Outline") { it.setShapeLine(current, id, rgb) } }

    // Text

    fun onParagraphText(paragraph: Int, old: String, new: String) {
        val id = selectedShapeId ?: return
        val (start, end, inserted) = com.vitosstudios.suite.feature.docs.DocsViewModel.diff(old, new)
        val nl = inserted.indexOf('\n')
        if (nl < 0) {
            edit("Typing", coalesceKey = "slide-$current-$id-$paragraph") {
                it.replaceText(current, id, paragraph, start, end, inserted.replace(' ', com.vitosstudios.suite.pptx.LINE_BREAK))
            }
        } else {
            edit("New paragraph") {
                it.replaceText(current, id, paragraph, start, end, inserted.substring(0, nl))
                it.splitParagraph(current, id, paragraph, start + nl)
                val rest = inserted.substring(nl + 1).replace("\n", " ")
                if (rest.isNotEmpty()) it.replaceText(current, id, paragraph + 1, 0, 0, rest)
            }
            textParagraph = paragraph + 1
        }
    }

    fun mergeIntoPrevious(paragraph: Int) {
        val id = selectedShapeId ?: return
        if (paragraph <= 0) return
        edit("Join paragraphs") { it.mergeWithNextParagraph(current, id, paragraph - 1) }
        textParagraph = paragraph - 1
    }

    private fun paragraphsToFormat(): List<Int> {
        val shape = selectedShape() ?: return emptyList()
        val count = shape.text?.paragraphs?.size ?: 0
        return textParagraph?.let { listOf(it) } ?: (0 until count).toList()
    }

    fun format(patch: RunFormatPatch) {
        val id = selectedShapeId ?: return
        val shape = selectedShape() ?: return
        edit("Format") { d ->
            val p = textParagraph
            val sel = textSelection
            if (p != null && sel.first != sel.last) {
                d.applyRunFormat(current, id, p, minOf(sel.first, sel.last), maxOf(sel.first, sel.last), patch)
            } else {
                for (i in paragraphsToFormat()) {
                    val len = shape.text?.paragraphs?.getOrNull(i)?.text?.length ?: 0
                    if (len > 0) d.applyRunFormat(current, id, i, 0, len, patch)
                }
            }
        }
    }

    fun alignment(a: TextAlign) {
        val id = selectedShapeId ?: return
        edit("Alignment") { d -> paragraphsToFormat().forEach { d.setParagraphAlignment(current, id, it, a) } }
    }

    fun bullets(style: BulletStyle) {
        val id = selectedShapeId ?: return
        edit("Bullets") { d -> paragraphsToFormat().forEach { d.setBullet(current, id, it, style) } }
    }

    fun level(delta: Int) {
        val id = selectedShapeId ?: return
        val shape = selectedShape() ?: return
        edit("List level") { d ->
            paragraphsToFormat().forEach { i ->
                val lvl = shape.text?.paragraphs?.getOrNull(i)?.level ?: 0
                d.setParagraphLevel(current, id, i, (lvl + delta).coerceIn(0, 8))
            }
        }
    }

    // Notes

    fun notes(): String = document?.notesText(current) ?: ""

    fun setNotes(text: String) = edit("Speaker notes", coalesceKey = "notes-$current") { it.setNotesText(current, text) }

    fun present(from: Int) {
        presentStart = from
        presenting = true
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                SlidesViewModel(app.container, createSavedStateHandle())
            }
        }
    }
}
